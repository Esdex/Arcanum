#!/usr/bin/env python3
# Arcanum - VeraCrypt-compatible encrypted vault manager for Android
#
# Copyright (C) 2026 Esdex
# Licensed under Apache License 2.0
# SPDX-License-Identifier: Apache-2.0
#
# Host test harness for the clean-room ext4 library - PC-only, not shipped in the
# app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
# never linked or copied. See issue #7.

r"""
The harness for reading a file whose extent tree is partly unreachable (#173).

    ./partialcheck.py

Two rules, and they pull against each other, which is the whole reason this exists.

**Refusing is the default and must stay the default.** Handing back bytes out of a
structure that failed validation is how a reader invents file contents. A copy or an
import that did it would write the invention into a second place and report success,
and nothing downstream would ever know. So ext4_read_file still refuses.

**Export is the exception.** It is how a vault in trouble is emptied, and a file
whose last megabyte is unreachable should still give up the first one. So
ext4_read_file_partial returns what it could read and says how much, and the caller
marks a short result .part rather than passing it off as the whole file (#170).

Between them sits the case that used to be wrong in the ordinary direction: a read
whose window ends before the damage was refused, though it never touched it. The
walk validated the whole tree before the callback could say the rest was none of its
business. That is now fixed, and `before` below is the case that holds it fixed.

  whole      an undamaged file reads identically both ways, byte for byte - the
             guard against a partial read that quietly returns zeroes and a
             length, which would pass every length-only assertion here
  before     a strict read whose window ends before the damage returns everything
             it asked for
  overlap    a strict read that reaches the damage refuses, with nothing written
             into the caller's buffer that could be mistaken for data
  prefix     a partial read over the whole file returns exactly the good part, and
             those bytes are the file's own, compared against what the same read
             gave before the damage was made
  inside     a partial read starting inside the damage returns zero, not a hole
             full of zeroes reported as data
  deep       the same, one level down: the damaged leaf lives in a child node
             rather than in the inode, so the walk has to report it up through a
             level instead of straight out
  index      the damage is the index entry itself - the whole subtree under it is
             unreachable and the prefix ends where that subtree began

Every case asserts the tree it is testing has the depth it is meant to have. A case
that quietly stopped fragmenting would otherwise test the flat shape twice and say
nothing about the deep one, which is the trap nodecheck.py names.
"""

import argparse
import os
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
BS = 1024
IMG_BYTES = 32 * 1024 * 1024
WHEN = "1788000000"
FAR_AWAY = 15728640        # a block number far outside a 32 MiB image


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def expected_pattern(logical, block_size=BS):
    """Must mirror fill_pattern() in extwrite.c exactly, as appendcheck.py's twin of
    it does. Regenerating the bytes here rather than reading them back through the
    driver is the whole point: an oracle taken from the code under test agrees with
    it when it is wrong. A mutant that returns the right LENGTH of zeroes passed this
    stand until the comparison stopped going through the reader."""
    buf = bytearray(block_size)
    for k in range(0, block_size - 7, 8):
        struct.pack_into("<II", buf, k, logical, k // 8)
    return bytes(buf)


def expected_bytes(nbytes, block_size=BS):
    """What the first `nbytes` of a file built by extwrite must contain."""
    out = bytearray()
    logical = 0
    while len(out) < nbytes:
        out += expected_pattern(logical, block_size)
        logical += 1
    return bytes(out[:nbytes])


def geometry(img):
    """Block size, inode size and the first inode table block, from dumpe2fs."""
    out = sh("dumpe2fs", img).stdout
    g = {"inode_size": 256, "block_size": BS, "itable": None, "per_group": None}
    for line in out.splitlines():
        if line.startswith("Inode size:"):
            g["inode_size"] = int(line.split(":")[1])
        elif line.startswith("Block size:"):
            g["block_size"] = int(line.split(":")[1])
        elif line.startswith("Inodes per group:"):
            g["per_group"] = int(line.split(":")[1])
        elif "Inode table at" in line and g["itable"] is None:
            g["itable"] = int(line.split("Inode table at")[1].split("-")[0].strip())
    return g


def inode_offset(img, ino):
    g = geometry(img)
    idx = (ino - 1) % g["per_group"]
    return g["itable"] * g["block_size"] + idx * g["inode_size"]


def patch_entry(img, byte_off, entry_index, physical, is_index=False):
    """Points one 12-byte extent entry at `physical`, leaving everything else alone.

    byte_off is where the node's 12-byte header starts - inside an inode, or at the
    top of an extent block. Entries follow it, twelve bytes each.

    The two kinds of entry do NOT have the same shape, which is worth stating because
    getting it wrong writes into a field nobody reads and breaks nothing:

        leaf    logical(4)  length(2)  physical_hi(2)  physical_lo(4)
        index   logical(4)  child_lo(4)  child_hi(2)   unused(2)
    """
    at = byte_off + 12 + 12 * entry_index
    with open(img, "r+b") as f:
        f.seek(at)
        raw = f.read(12)
        logical = struct.unpack_from("<I", raw, 0)[0]
        f.seek(at)
        if is_index:
            f.write(struct.pack("<IIHH", logical, physical & 0xFFFFFFFF, 0, 0))
        else:
            length = struct.unpack_from("<H", raw, 4)[0]
            f.write(struct.pack("<IHHI", logical, length, 0, physical))
    return logical


READER = os.path.join(HERE, "partialread")


def read(img, ino, offset, length, partial=False, dump=False):
    cmd = [READER, img, str(ino), str(offset), str(length)]
    if partial:
        cmd.append("--partial")
    if dump:
        cmd.append("--dump")
        r = subprocess.run(cmd, capture_output=True)
        return int(r.stderr.decode().strip()), r.stdout
    r = sh(*cmd)
    return int(r.stdout.strip()), b""


def extents_of(img, ino):
    return parse_extents(debugfs(img, f"ex <{ino}>\n"))


def build(tmp, fragment_rounds=0, per_round=64):
    """A fresh image holding /target.bin, optionally in many pieces.

    `fragment_rounds` decides the shape of its tree: none for one extent in the
    inode, a few for a root pointing at one child node, and enough to fill a child
    (84 entries in a 1 KiB block) for a root holding two of them - which is the only
    shape where breaking an index entry leaves a reachable prefix to measure.
    """
    img = os.path.join(tmp, "p.img")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", str(IMG_BYTES), img)
    if sh(os.path.join(HERE, "mkfs"), img, "--bs", str(BS)).returncode:
        return None, None
    dw = os.path.join(HERE, "dirwrite")
    ew = os.path.join(HERE, "extwrite")
    ino = int(sh(dw, img, "2", "create", "target.bin", WHEN).stdout.strip())
    if fragment_rounds == 0:
        sh(ew, img, str(ino), "append", "2048")
    else:
        # One pad between each pair of appends, so the target's blocks cannot be
        # contiguous and its tree has to grow past the four slots in the inode.
        for i in range(fragment_rounds):
            sh(ew, img, str(ino), "append", str(per_round))
            pad = int(sh(dw, img, "2", "create", f"pad{i}.bin", WHEN).stdout.strip())
            sh(ew, img, str(pad), "append", "8")
    return img, ino


def main():
    global READER
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--partialread", default=READER,
                    help="the reader to drive, so a mutant can be measured")
    args = ap.parse_args()
    READER = args.partialread

    for t in ("mkfs", "dirwrite", "extwrite"):
        if not os.path.exists(os.path.join(HERE, t)):
            sys.exit(f"{t} not found - build it first")
    if not os.path.exists(READER):
        sys.exit(f"{READER} not found - build it first")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:

        # ── whole: an undamaged file, both ways, byte for byte ─────────────
        img, ino = build(tmp)
        if img is None:
            print("FAIL"); print("     mkfs failed"); return 1
        size = 2048 * BS
        n_strict, data_strict = read(img, ino, 0, size, dump=True)
        n_part, data_part = read(img, ino, 0, size, partial=True, dump=True)
        truth = expected_bytes(size)
        if n_strict != size or n_part != size:
            problems.append(f"[whole] an undamaged file read {n_strict} strictly and "
                            f"{n_part} partially, expected {size} both ways")
        elif data_strict != truth or data_part != truth:
            problems.append("[whole] an undamaged file did not read back as the "
                            "pattern extwrite put into it")
        elif args.verbose:
            print(f"  whole      {size} bytes, both ways, against the pattern")

        # ── flat: the damaged leaf lives in the inode itself ───────────────
        img, ino = build(tmp)
        rows = extents_of(img, ino)
        if len(rows) != 1 or rows[0]["is_index"]:
            problems.append(f"[flat] expected one leaf extent in the inode, got "
                            f"{len(rows)} rows - the case is not testing what it says")
        else:
            # Split the single extent in two by hand: the tail of the file is what
            # gets pointed away, so the first half stays readable.
            off = inode_offset(img, ino)
            with open(img, "r+b") as f:
                f.seek(off + 0x28)
                node = bytearray(f.read(60))
                b, l, hi, lo = struct.unpack_from("<IHHI", node, 12)
                half = l // 2
                struct.pack_into("<IHHI", node, 12, b, half, 0, lo)
                struct.pack_into("<IHHI", node, 24, b + half, l - half, 0, FAR_AWAY)
                struct.pack_into("<H", node, 2, 2)          # two entries now
                f.seek(off + 0x28)
                f.write(node)
            good = half * BS

            n, _ = read(img, ino, 0, good)
            if n != good:
                problems.append(f"[before] a strict read ending at the damage returned "
                                f"{n}, expected {good} - a window that never touches "
                                f"the broken part must not be refused")
            n, _ = read(img, ino, 0, size)
            if n >= 0:
                problems.append(f"[overlap] a strict read across the damage returned "
                                f"{n} instead of refusing")
            n, got = read(img, ino, 0, size, partial=True, dump=True)
            if n != good:
                problems.append(f"[prefix] a partial read returned {n}, expected the "
                                f"{good} bytes that are still reachable")
            elif got != expected_bytes(good):
                problems.append("[prefix] the partial read returned the right LENGTH "
                                "but not the file's own bytes")
            n, _ = read(img, ino, good, size - good, partial=True)
            if n != 0:
                problems.append(f"[inside] a partial read starting inside the damage "
                                f"returned {n}, expected 0")
            if args.verbose:
                print(f"  flat       strict {good} before it, refused across it, "
                      f"partial {good}")

        # ── deep / index: the damage one level down ────────────────────────
        img, ino = build(tmp, fragment_rounds=6)
        rows = extents_of(img, ino)
        depth = max((r["depth"] for r in rows), default=0)
        idx_rows = [r for r in rows if r["is_index"]]
        leaves = [r for r in rows if not r["is_index"]]
        if depth == 0 or not idx_rows:
            problems.append(f"[deep] the tree stayed flat ({len(leaves)} leaves, "
                            f"depth {depth}) - nothing below the inode was tested")
        else:
            total = sum(r["length"] for r in leaves) * BS

            # The first child block, and the leaf entry inside it to break.
            child = idx_rows[0]["physical_start"]
            in_child = sorted((r for r in leaves), key=lambda r: r["logical_start"])
            victim = in_child[1] if len(in_child) > 1 else in_child[0]
            good = victim["logical_start"] * BS

            patch_entry(img, child * BS, in_child.index(victim), FAR_AWAY)
            n, _ = read(img, ino, 0, good)
            if n != good:
                problems.append(f"[deep] a strict read ending at the damage returned "
                                f"{n}, expected {good}")
            n, got = read(img, ino, 0, total, partial=True, dump=True)
            if n != good:
                problems.append(f"[deep] a partial read returned {n}, expected {good} "
                                f"- the leaf below an index must be reported the same "
                                f"way as one in the inode")
            elif got != expected_bytes(good):
                problems.append("[deep] the partial read returned the right length "
                                "but not the file's own bytes")
            elif args.verbose:
                print(f"  deep       depth {depth}, partial {good} of {total}")

            # Now the index entry itself: everything under it goes out of reach.
            # 90 pieces rather than 6, because a child node holds 84 extents and the
            # root has to name two of them before there is a subtree to lose while
            # another stays readable.
            img, ino = build(tmp, fragment_rounds=90, per_round=8)
            rows = extents_of(img, ino)
            idx_rows = [r for r in rows if r["is_index"]]
            leaves = sorted((r for r in rows if not r["is_index"]),
                            key=lambda r: r["logical_start"])
            if len(idx_rows) < 2:
                problems.append(f"[index] the root holds {len(idx_rows)} index entry - "
                                f"90 fragments should have pushed it to two, and "
                                f"without a second subtree this case measures nothing")
            else:
                total = sum(r["length"] for r in leaves) * BS
                off = inode_offset(img, ino)
                # Entry 1 of the root, so the subtree under entry 0 stays readable.
                patch_entry(img, off + 0x28, 1, FAR_AWAY, is_index=True)
                good = idx_rows[1]["logical_start"] * BS
                n, got = read(img, ino, 0, total, partial=True, dump=True)
                if n != good:
                    problems.append(f"[index] a partial read returned {n}, expected "
                                    f"{good} - the prefix ends where the unreachable "
                                    f"subtree begins")
                elif got != expected_bytes(good):
                    problems.append("[index] the partial read returned the right "
                                    "length but not the file's own bytes")
                elif args.verbose:
                    print(f"  index      prefix {good} of {total}")

    if problems:
        print("FAIL")
        for p in problems:
            print("    ", p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
