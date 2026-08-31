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
The harness for putting an extent tree node down once per run of appends (#162).

    ./nodecheck.py

Appending a block used to write the whole leaf every time round: find the rightmost
path, allocate, write the data, extend the last extent, write the leaf back.
Measured on a device, one block took 38647 writes in a single four-second burst -
23% of everything that session wrote, and it grew with the size of the file rather
than with the size of the tree. The path is now carried across the appends of one
call and put down once at the end.

**The measurement only works on a fragmented volume, and that is not a detail.**
A file whose blocks are contiguous has exactly one extent, that extent lives in the
four slots inside the inode, and the inode is written once at the end whatever this
code does - so the bug is invisible on an empty volume however much is appended to
it. Every case here first breaks the free space into pieces so the target's tree is
deeper than its root, and the depth is then asserted with debugfs rather than
assumed. Without that assertion a case that quietly stopped fragmenting would pass
while measuring nothing at all, which is the trap runcheck.py names, in a new place.

**What e2fsck cannot see, and what it can.**

A writer that goes back to putting the node down per block is entirely correct and
leaves a perfect filesystem, so the marginal measurement is the only thing standing
between us and all of #162 coming back. It is marginal rather than absolute so the
fixed cost of fragmenting the volume stays out of the number.

The other half - that deferring the write did not lose the last change to a node -
e2fsck cannot answer either: a tree missing its final extent is structurally sound,
it simply describes a shorter file, and a check calls that clean. So every byte is
read back through debugfs and compared with the pattern session.c wrote.

What e2fsck is exactly right about is the ordering, and it is asked on every case.
A node that reached the disk while the bitmap behind it did not is a block
referenced and free at once, which is the one damage a check cannot undo losslessly
- it hands that block to the next file that asks. Deferring only ever moves a write
later, so the direction it can break is the harmless one, a block marked and
referenced by nothing. e2fsck reports both, as bitmap differences.

  marginal   a second megabyte onto a tree deeper than its root must cost about
             what its own data costs, not twice it
  deeper     1 KiB blocks and enough fragments to push the root down, so the
             writes are spread over levels instead of landing in one leaf
  holes      free space left in small pieces, so a single append fills a node
             partway through itself and has to grow the tree while still
             holding changes it has not written
  starve     the same fragmented ground run dry, so an append fails partway
             through hanging a new chain of nodes off the tree
  regrow     grow, free the file, grow again, over free space now in awkward
             pieces - the tree is rebuilt rather than extended
  readback   every byte of the fragmented file, dumped by debugfs, against the
             pattern that was written into it
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402

IMG = "256M"
PADS = 6          # files that take blocks away from the target as it grows
ROUNDS = 6        # one block each per round, so the target ends up in fragments


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def pat(i):
    """The byte session.c writes at absolute file offset i."""
    return (i ^ (i >> 8) ^ (i >> 16)) & 0xFF


def fragment(block_size, rounds=ROUNDS, pads=PADS):
    """A script that leaves /f.bin holding `rounds` single-block extents.

    Each round hands one block to every padding file and one to the target, so no
    two of the target's blocks are next to each other and its extents cannot merge.
    """
    names = [f"/p{i}.bin" for i in range(pads)] + ["/f.bin"]
    lines = [f"create {n}" for n in names]
    for _ in range(rounds):
        lines += [f"append {n} {block_size}" for n in names]
    return "\n".join(lines) + "\n"


def run(mkfs, session, tmp, script, problems, label, block_size=4096, size=IMG):
    """One image, one script. Returns (image path, writes, failed_ops) or None."""
    img = os.path.join(tmp, f"n-{label.replace(' ', '_')}.img")
    scr = os.path.join(tmp, "n.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", str(size), img)
    if sh(mkfs, img, "--bs", str(block_size)).returncode:
        problems.append(f"[{label}] mkfs failed")
        return None
    with open(scr, "w") as f:
        f.write(script)

    r = sh(session, img, "hold", scr)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)

    fsck = sh("e2fsck", "-fn", img)
    if fsck.returncode != 0:
        problems.append(f"[{label}] e2fsck rejects the result (rc={fsck.returncode})\n"
                        f"           {fsck.stdout.strip()[:400]}")
    return img, int(fields["writes"]), int(fields["failed"])


def tree_depth(img, path="/f.bin"):
    rows = parse_extents(debugfs(img, f"dump_extents {path}\n"))
    return max((e["level"] for e in rows), default=0), len(
        [e for e in rows if not e["is_index"]])


def check_depth(img, label, want, problems, verbose=False):
    depth, leaves = tree_depth(img)
    if depth < want:
        problems.append(
            f"[{label}] the tree is {depth} deep with {leaves} extents, and this case "
            f"needs at least {want}: the whole tree still fits in the inode, so the "
            f"node write being measured never happens and the case proves nothing")
        return False
    if verbose:
        print(f"  {label:18s} depth {depth}, {leaves} extents")
    return True


def readback(img, problems, label="readback"):
    """Every byte of /f.bin, through debugfs, against the pattern written."""
    out = img + ".dump"
    debugfs(img, f"dump /f.bin {out}\n")
    if not os.path.exists(out):
        problems.append(f"[{label}] debugfs could not dump the file")
        return
    with open(out, "rb") as fh:
        data = fh.read()
    if not data:
        problems.append(f"[{label}] the file dumped empty")
        return
    want = bytes(pat(i) for i in range(len(data)))
    if data != want:
        first = next(i for i in range(len(data)) if data[i] != want[i])
        problems.append(
            f"[{label}] the file reads back wrong from offset {first} of {len(data)} "
            f"({data[first]:#04x}, expected {want[first]:#04x}) - a change to a node "
            f"never reached the disk, and e2fsck cannot see that")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--session", default=os.path.join(here, "session"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for t in (args.mkfs, args.session):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    for t in ("e2fsck", "debugfs"):
        if not shutil.which(t):
            sys.exit(f"{t} not found - it is an oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        # ── the marginal cost of a megabyte onto a tree with a leaf of its own ──
        def deep_append(megs, bs=4096):
            return fragment(bs) + f"append /f.bin 1048576\n" * megs

        one = run(args.mkfs, args.session, tmp, deep_append(1), problems, "1 MiB")
        two = run(args.mkfs, args.session, tmp, deep_append(2), problems, "2 MiB")
        if one and two:
            measured = check_depth(one[0], "marginal", 1, problems, args.verbose)
            blocks = 1048576 // 4096
            marginal = two[1] - one[1]
            budget = int(blocks * 1.15)
            if measured and marginal > budget:
                problems.append(
                    f"[marginal] the second megabyte cost {marginal} block writes for "
                    f"{blocks} blocks of data - over the {budget} allowed, so the "
                    f"extent node is being written per block again, which is all "
                    f"of #162")
            elif args.verbose:
                print(f"  second megabyte    {marginal} writes for {blocks} data blocks")
            readback(two[0], problems)

        # ── a tree the root has been pushed out of ─────────────────────────────
        # At 1 KiB a leaf holds 84 extents, so four full leaves fill the root's four
        # slots and the next one pushes the root down. That is a different shape to
        # measure: the writes are spread over three levels rather than landing in one
        # leaf, and the levels above the leaf are only touched when the tree grows.
        deep = fragment(1024, rounds=360) + "append /f.bin 1048576\n"
        got = run(args.mkfs, args.session, tmp, deep, problems, "deeper",
                  block_size=1024)
        if got:
            check_depth(got[0], "deeper", 2, problems, args.verbose)
            readback(got[0], problems, "deeper readback")

        # ── free space in holes, so one call fills a node partway through ──────
        # Every case above appends onto a node that was already there. None of them
        # fills one DURING a call, and that is the only moment when the buffer a
        # node is held in gets reused while it still holds changes - which is what
        # makes it the only moment that can lose them.
        #
        # Reaching it needs free space in small pieces rather than one run, so this
        # lays down a row of small files and takes every other one away. At 1 KiB a
        # node holds 84 extents, and an append that has to pick up a hundred holes
        # runs out of room in the middle of itself.
        holes = ("".join(f"create /h{i}.bin\nappend /h{i}.bin 2048\n"
                         for i in range(200))
                 + "".join(f"unlink /h{i}.bin\n" for i in range(1, 200, 2))
                 + "create /f.bin\nappend /f.bin 204800\n")
        got = run(args.mkfs, args.session, tmp, holes, problems, "holes",
                  block_size=1024)
        if got:
            depth, leaves = tree_depth(got[0])
            if leaves < 85:
                problems.append(
                    f"[holes] the file came out in only {leaves} extents, so the node "
                    f"never filled inside a single append and the case did not reach "
                    f"the moment it exists for")
            elif args.verbose:
                print(f"  holes              depth {depth}, {leaves} extents in "
                      f"one append")
            readback(got[0], problems, "holes readback")

        # ── to the very end of a fragmented volume ─────────────────────────────
        # Growing the tree is where an append can run out of space in the middle of
        # something: a chain of new nodes is taken a level at a time, and the second
        # can fail after the first has been linked into its parent in memory. What
        # must not happen then is that link reaching the disk, because it names a
        # block that was handed straight back - a block referenced and free at once,
        # which is the one state a check cannot undo without taking something away.
        # e2fsck is the oracle for exactly that, and running the volume dry on
        # ground that is already in pieces is what gets there.
        starve = fragment(4096, rounds=40) + "append /f.bin 1048576\n" * 80
        got = run(args.mkfs, args.session, tmp, starve, problems, "fill to refusal",
                  size="32M")
        if got:
            if got[2] == 0:
                problems.append("[fill to refusal] nothing was refused - the volume "
                                "did not fill, so this case checked nothing")
            elif args.verbose:
                print(f"  fill to refusal    {got[1]} writes, {got[2]} refused")

        # ── grown, freed and grown again ───────────────────────────────────────
        regrow = (fragment(4096) + "append /f.bin 1048576\n" * 2
                  + "unlink /f.bin\ncreate /f.bin\n"
                  + "append /f.bin 1048576\n" * 2)
        got = run(args.mkfs, args.session, tmp, regrow, problems, "regrow")
        if got and args.verbose:
            print(f"  regrow             {got[1]} writes, {got[2]} operations refused")


    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("an extent tree node is put down once per run of appends rather than once "
          "per block: a megabyte onto a fragmented file costs about its own data in "
          "writes, the tree still reads back byte for byte, and e2fsck is clean on "
          "every shape including one deep enough to have pushed the root down.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
