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
The harness for giving blocks back a run at a time (#165).

    ./freecheck.py

Freeing wrote the whole block bitmap after every single bit it cleared, so deleting
a file cost one 4 KiB write per block it held. Measured on a device, 100931 of a
session's 216868 writes - 47% - were deletes, and 99.97% of one delete phase went
into three bitmap blocks. Deleting an 8 MiB file was 2058 writes; it is 11 now.

**This is the easier half of the same idea as #161, and the difference is worth
saying.** Taking blocks had to reserve ahead, because a caller asks for one block
at a time and the run exists only in the allocator's head. Freeing is handed the
run: an extent IS a length of consecutive blocks, and the extent writer walked it
one block at a time only because that was the API. So nothing is deferred and no
state is kept between calls - the bits are cleared and the bitmap written before
`ext4_free_run` returns, exactly as before, once for the run instead of once per
block. There is no new window in which the disk disagrees with memory, which is
the whole reason this shape was chosen over a mirror of #161's reservation.

**What e2fsck is the oracle for.** Everything about correctness. A bit left set is
a leak and a bit cleared too eagerly is a block that is free on disk while an inode
still points at it - the one damage a check cannot undo without taking something
away - and both come back as bitmap differences. Every case here ends in `e2fsck
-fn`, and the counts in the descriptors and the superblock have to agree with it
too, which is what catches a run whose length went into the bitmap but not into the
free counts.

**What e2fsck cannot see, and needs a measurement.** A version that goes back to
writing the bitmap per block is perfectly correct and leaves a perfect filesystem.
So the win is a marginal one: deleting a second megabyte must cost almost nothing
beyond deleting the first.

  marginal    the delete of a second megabyte costs nearly nothing
  fragment    a file in many short extents, where every extent is its own run and
              the win is smallest - correctness still has to hold
  deep        a file whose tree has nodes of its own, so index blocks are freed
              alongside the data they map
  neighbours  every other file of a row deleted, so freeing has to leave the ones
              between alone
  groups      an extent that runs past the end of the group it starts in, so the
              run is cut there and each group's own bitmap written. Built with
              mke2fs on purpose: our own formatter puts every group's metadata at
              the start of that group, which breaks free space at each boundary,
              so an extent physically cannot cross one on a vault Arcanum made.
              A desktop uses flex_bg and they do. The split is a foreign-volume
              path, and foreign volumes are the ones this driver has to be careful
              with
  truncate    a cut that falls INSIDE an extent, which frees the tail of a run
              rather than a whole one - a different call and a different arithmetic
  already
  free        a bitmap corrupted from outside, so a run holds a block the disk
              already calls free. That has to be refused whole: freeing it halfway
              puts the free count above what was ever taken, and a count that reads
              high is how a later session hands one block to two files
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402

MIB = 1048576


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def build(mkfs, session, tmp, script, problems, label, bs=4096, size="256M"):
    """One image, one script. Returns (image, writes) or None."""
    img = os.path.join(tmp, f"f-{label.replace(' ', '_')}.img")
    scr = os.path.join(tmp, "f.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", size, img)
    if sh(mkfs, img, "--bs", str(bs)).returncode:
        problems.append(f"[{label}] mkfs failed")
        return None
    with open(scr, "w") as fh:
        fh.write(script)
    r = sh(session, img, "hold", scr)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
    if int(fields.get("failed", 0)):
        problems.append(f"[{label}] {fields['failed']} operation(s) were refused")
        return None
    fsck = sh("e2fsck", "-fn", img)
    if fsck.returncode != 0:
        problems.append(f"[{label}] e2fsck rejects the result (rc={fsck.returncode})\n"
                        f"           {fsck.stdout.strip()[:400]}")
    return img, int(fields["writes"])


def run_on(session, img, script, problems, label):
    """Runs a script against an image that already exists. Returns writes or None."""
    scr = img + ".txt"
    with open(scr, "w") as fh:
        fh.write(script)
    r = sh(session, img, "hold", scr)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
    if int(fields.get("failed", 0)):
        problems.append(f"[{label}] {fields['failed']} operation(s) were refused")
        return None
    return int(fields["writes"])


def geometry(img):
    """(blocks_per_group, first_data_block), straight out of the superblock.

    Both are needed and the second is the one that is easy to forget: on a 1 KiB
    filesystem the first data block is 1, not 0, so a group starts at
    first_data_block + g * blocks_per_group. An earlier version of this divided by
    the group size alone, reported a crossing where there was none, and let a
    mutant that stops after the first group live.
    """
    with open(img, "rb") as fh:
        fh.seek(1024)
        sb = fh.read(1024)
    u32 = lambda o: int.from_bytes(sb[o:o+4], "little")
    return u32(0x20), u32(0x14)


def crossing_extents(img):
    """How many of the file's extents run past the end of the group they start in.

    Each group's bitmap is a block of its own, so an extent reaching past that
    boundary is exactly what ext4_free_run has to cut in two.
    """
    per_group, first_data = geometry(img)
    rows = parse_extents(debugfs(img, "dump_extents /f.bin\n"))
    n = 0
    for e in rows:
        if e["is_index"]:
            continue
        first = e["physical_start"] - first_data
        last = first + e["length"] - 1
        if first // per_group != last // per_group:
            n += 1
    return n


def one_file(megs, delete=True):
    s = "create /f.bin\n" + f"append /f.bin {MIB}\n" * megs
    return s + ("unlink /f.bin\n" if delete else "")


def fragmented(rounds, pads=5, block=4096):
    """A target whose blocks are scattered, so its extents are one block each."""
    names = [f"/p{i}.bin" for i in range(pads)] + ["/f.bin"]
    out = [f"create {n}" for n in names]
    for _ in range(rounds):
        out += [f"append {n} {block}" for n in names]
    return "\n".join(out) + "\n"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--session", default=os.path.join(here, "session"))
    ap.add_argument("--extwrite", default=os.path.join(here, "extwrite"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for t in (args.mkfs, args.session, args.extwrite):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    for t in ("e2fsck", "debugfs"):
        if not shutil.which(t):
            sys.exit(f"{t} not found - it is an oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        # ── what a megabyte costs to give back ─────────────────────────────
        # Measured against the SAME script without the delete, so the append's own
        # cost - which this change must not touch - stays out of the number.
        kept = build(args.mkfs, args.session, tmp, one_file(8, delete=False),
                     problems, "kept")
        gone = build(args.mkfs, args.session, tmp, one_file(8), problems, "deleted")
        if kept and gone:
            blocks = 8 * MIB // 4096
            cost = gone[1] - kept[1]
            budget = int(blocks * 0.05)
            if cost > budget:
                problems.append(
                    f"[marginal] deleting {blocks} blocks cost {cost} block writes, "
                    f"over the {budget} allowed - the bitmap is being written per "
                    f"freed block again, which is all of #165")
            elif args.verbose:
                print(f"  delete 8 MiB       {cost} writes for {blocks} blocks freed")

        # ── the shapes that free differently ───────────────────────────────
        cases = {
            # Every extent is one block, so every run is one block and this is the
            # case the change cannot help. It is here for correctness, not speed.
            "fragment": fragmented(400) + "unlink /f.bin\n",
            # Big enough that the tree needs nodes of its own, which are freed one
            # at a time alongside the runs of data they map.
            "deep": fragmented(400) + "append /f.bin 4194304\nunlink /f.bin\n",
            # Several files, some kept: freeing has to leave the neighbours alone.
            "neighbours": "".join(f"create /n{i}.bin\nappend /n{i}.bin {MIB}\n"
                                  for i in range(8))
                          + "".join(f"unlink /n{i}.bin\n" for i in range(0, 8, 2)),
        }
        for name, script in cases.items():
            got = build(args.mkfs, args.session, tmp, script, problems, name)
            if got and args.verbose:
                print(f"  {name:18s} {got[1]} writes")

        # ── a run that has to be split at a group boundary ─────────────────
        # Each group's bitmap is a block of its own, so a run reaching past a group
        # boundary has to be cut there and the two halves written separately. A cut
        # in the wrong place clears bits belonging to the next group, which e2fsck
        # reports as blocks in use by nobody.
        #
        # **The volume has to be one mke2fs made, and that is the point of the
        # case.** Our own formatter puts each group's metadata at the start of that
        # group, so free space is broken at every boundary and an extent physically
        # cannot cross one - the split is unreachable on a vault Arcanum created. A
        # desktop uses flex_bg, which packs sixteen groups' metadata into the first
        # of them and leaves the rest continuous, and there extents do cross. So
        # this path only ever runs on a volume that came from somewhere else, which
        # is exactly the kind of volume this driver has to mount.
        img = os.path.join(tmp, "groups.img")
        sh("truncate", "-s", "64M", img)
        r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-O",
               "^has_journal,^dir_index", "-b", "1024", img)
        if r.returncode != 0:
            problems.append(f"[groups] mke2fs would not build the volume: "
                            f"{r.stderr.strip()[:200]}")
            got = None
        else:
            w = run_on(args.session, img, one_file(24, delete=False), problems,
                       "groups")
            got = (img, w) if w is not None else None
        if got:
            crossing = crossing_extents(got[0])
            if crossing == 0:
                problems.append(
                    "[groups] not one of the file's extents reaches past the end of "
                    "the group it starts in, so nothing here was ever split and the "
                    "case checked the same path as every other one")
            else:
                w = run_on(args.session, got[0], "unlink /f.bin\n", problems, "groups")
                fsck = sh("e2fsck", "-fn", got[0])
                if fsck.returncode != 0:
                    problems.append(f"[groups] e2fsck rejects the delete "
                                    f"(rc={fsck.returncode})\n"
                                    f"           {fsck.stdout.strip()[:400]}")
                elif args.verbose and w is not None:
                    print(f"  groups             {crossing} extent(s) cross a group "
                          f"boundary, the delete cost {w} writes")

        # ── a cut that lands inside an extent ──────────────────────────────
        # truncate frees the tail of a run rather than a whole one, which is its own
        # call with its own arithmetic - off by one there takes a block the file
        # still uses, or leaves one it does not.
        cut = build(args.mkfs, args.session, tmp, one_file(4, delete=False),
                    problems, "truncate")
        if cut:
            img = cut[0]
            ino = None
            m = re.search(r"Inode:\s*(\d+)", debugfs(img, "stat /f.bin\n"))
            if m:
                ino = int(m.group(1))
            if ino is None:
                problems.append("[truncate] could not find the file's inode")
            else:
                # 1000 blocks of the 1024 it holds, so the cut is well inside the
                # single extent rather than on its edge.
                r = sh(args.extwrite, img, str(ino), "truncate", "1000")
                if r.returncode != 0:
                    problems.append(f"[truncate] {r.stderr.strip()[:200]}")
                fsck = sh("e2fsck", "-fn", img)
                if fsck.returncode != 0:
                    problems.append(f"[truncate] e2fsck rejects the cut "
                                    f"(rc={fsck.returncode})\n"
                                    f"           {fsck.stdout.strip()[:400]}")
                rows = parse_extents(debugfs(img, "dump_extents /f.bin\n"))
                mapped = sum(e["length"] for e in rows if not e["is_index"])
                if mapped != 1000:
                    problems.append(f"[truncate] the file maps {mapped} blocks after "
                                    f"a cut to 1000 - the tail of the run was freed "
                                    f"by the wrong amount")
                elif args.verbose:
                    print(f"  truncate           cut inside an extent, {mapped} "
                          f"blocks left mapped")

        # ── a run holding a block the bitmap already calls free ────────────
        # A vault mounts knowing a check may be owed, so the driver can be handed a
        # volume where an extent names a block the bitmap says is free. Freeing that
        # run must be refused outright rather than done halfway: the count would go
        # up by the length of the run while only part of it was ever taken, and a
        # free count that reads high is how a later session hands a block to two
        # files.
        #
        # The damage is made from outside, with debugfs, so this is a state the
        # driver has to survive rather than one it produced.
        bad = build(args.mkfs, args.session, tmp, one_file(1, delete=False),
                    problems, "already free")
        if bad:
            img = bad[0]
            rows = parse_extents(debugfs(img, "dump_extents /f.bin\n"))
            leaves = [e for e in rows if not e["is_index"]]
            if not leaves or leaves[0]["length"] < 4:
                problems.append("[already free] the file did not come out as a run, "
                                "so there is no run to half-free")
            else:
                # Inside the run rather than on its edge, so a version that checks
                # only the first or last block is caught as well.
                blk = leaves[0]["physical_start"] + 2
                debugfs(img, f"freeb {blk}\n", write=True)
                scr = img + ".u.txt"
                with open(scr, "w") as fh:
                    fh.write("unlink /f.bin\n")
                r = sh(args.session, img, "hold", scr)
                fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
                if int(fields.get("failed", 0)) == 0:
                    problems.append(
                        f"[already free] the delete went through over a bitmap that "
                        f"already called block {blk} free, so the free count went up "
                        f"by more than was ever taken - it has to be refused whole")
                elif args.verbose:
                    print(f"  already free       refused, as it must be")

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("blocks are given back a run at a time: deleting a megabyte costs almost "
          "nothing beyond deleting the first, a run is split at every group boundary "
          "it crosses, a cut inside an extent frees exactly its tail, and e2fsck is "
          "clean on every shape.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
