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
The harness for taking blocks a run at a time (#161).

    ./runcheck.py

Every allocation used to write the whole 4 KiB block bitmap, so appending a 1 MiB
chunk was 256 allocations and 256 writes of the same block. Measured on a device,
63% of everything written in one session went into two bitmap blocks. The allocator
now takes a run of up to 256 consecutive free blocks in one write and hands them out
from memory.

**What must not have changed is the ordering, and e2fsck is what says so.** The
bitmap has to reach the disk before anything references a block, because a volume
mounts knowing a check is owed: if an inode pointed at a block the disk still called
free, the next session would hand that block to a second file. Reserving ahead marks
the WHOLE run before any of it is handed out, so that direction is stronger than it
was. The direction it weakens is the harmless one - a run cut short leaves its unused
tail marked, a leak - and the flush gives the tail back, which is exactly what e2fsck
reports on if it ever stops happening ("Block bitmap differences"). So every case
here ends in `e2fsck -fn`, and that single check covers both a mark that was never
written and a reservation that was never released.

**The other half cannot be checked that way at all.** An allocator that goes back to
writing the bitmap per block is completely correct and passes every fsck. So the
measurement is a marginal one: appending a second megabyte must cost about what its
data costs, not twice that. Marginal rather than absolute so that the fixed cost of
creating the file, and any change to it, stays out of the number.

The last case fills a volume to refusal. That is where reserving ahead could go
wrong in a way nothing else here would reach - reserving more than is left, or
reporting no space while holding a reservation that would have covered it.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

MIB_BLOCKS = 256          # 1 MiB at a 4 KiB block size
IMG = 512 * 1024 * 1024


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def run(mkfs, session, tmp, script, problems, label, size=IMG):
    """One image, one script. Returns (writes, failed_ops) or None."""
    img = os.path.join(tmp, "r.img")
    scr = os.path.join(tmp, "r.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", str(size), img)
    if sh(mkfs, img, "--bs", "4096").returncode:
        problems.append(f"[{label}] mkfs failed")
        return None
    with open(scr, "w") as f:
        f.write(script)

    r = sh(session, img, "hold", scr)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)

    # The one oracle that covers both directions of the ordering: a block marked and
    # never referenced is a leaked reservation, one referenced and never marked is a
    # mark that never reached the disk. e2fsck reports both as bitmap differences.
    fsck = sh("e2fsck", "-fn", img)
    if fsck.returncode != 0:
        problems.append(f"[{label}] e2fsck rejects the result (rc={fsck.returncode})\n"
                        f"           {fsck.stdout.strip()[:400]}")
    return int(fields["writes"]), int(fields["failed"])


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
    if not shutil.which("e2fsck"):
        sys.exit("e2fsck not found - it is the oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        # ── the marginal cost of a megabyte ──────────────────────────────
        def appends(n):
            return "create /f.bin\n" + "append /f.bin 1048576\n" * n

        one = run(args.mkfs, args.session, tmp, appends(1), problems, "append 1 MiB")
        two = run(args.mkfs, args.session, tmp, appends(2), problems, "append 2 MiB")
        if one and two:
            marginal = two[0] - one[0]
            budget   = int(MIB_BLOCKS * 1.15)
            if marginal > budget:
                problems.append(
                    f"[marginal] the second megabyte cost {marginal} block writes for "
                    f"{MIB_BLOCKS} blocks of data - over the {budget} allowed, so the "
                    f"bitmap is being written per block again, which is all of #161")
            elif args.verbose:
                print(f"  second megabyte      {marginal} writes for {MIB_BLOCKS} "
                      f"data blocks")

        # ── the shapes that allocate differently ─────────────────────────
        cases = {
            "one big file":    appends(8),
            "many small files": "".join(f"create /s{i}.bin\nappend /s{i}.bin 8192\n"
                                        for i in range(40)),
            "grow, free, grow": appends(4) + "unlink /f.bin\n" + appends(4),
            "interleaved":     "".join(f"create /i{i}.bin\n" for i in range(6))
                               + "".join(f"append /i{i}.bin 262144\n"
                                         for _ in range(3) for i in range(6)),
        }
        for name, script in cases.items():
            got = run(args.mkfs, args.session, tmp, script, problems, name)
            if got and args.verbose:
                print(f"  {name:20s} {got[0]} writes, {got[1]} operations refused")

        # ── to the very end of the volume ────────────────────────────────
        # 512 MB holds about 130000 blocks; 600 megabytes of appends cannot fit, so
        # this runs into the refusal and keeps going. What is checked is the state it
        # leaves: a reservation that was never given back, or one taken beyond what
        # was free, both come out as bitmap differences.
        full = run(args.mkfs, args.session, tmp,
                   "create /big.bin\n" + "append /big.bin 1048576\n" * 600,
                   problems, "fill to refusal")
        if full:
            if full[1] == 0:
                problems.append("[fill to refusal] nothing was refused - the volume did "
                                "not fill, so this case checked nothing")
            elif args.verbose:
                print(f"  fill to refusal      {full[0]} writes, {full[1]} appends "
                      f"refused, e2fsck clean afterwards")

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("blocks are taken a run at a time: a second megabyte costs about its own "
          "data in writes rather than twice it, and every shape - one big file, many "
          "small ones, reuse after a delete, interleaved growth, and a volume filled "
          "to refusal - comes out e2fsck-clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
