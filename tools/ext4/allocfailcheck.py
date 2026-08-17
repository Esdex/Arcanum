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
Fails one allocation of an operation, in turn, and judges what is left.

    ./allocfailcheck.py

`faultcheck.py` sweeps the writes of every operation and proves that a write which
fails leaves a volume a check can repair without losing anything. This is the other
half of the same question: the library allocates on nearly every operation - an
inode buffer, a block buffer, one per level of an extent tree - and each of those
`if (!p) return` branches only runs when the machine has no memory left. On a phone
that is an ordinary state, not a hypothetical one, and none of those branches had
ever been executed by anything (#147).

The bar is the same as faultcheck's, and it is deliberately not "the operation must
succeed":

  1. it must not crash, and must not be killed by a signal
  2. the volume must be e2fsck-clean, or repaired by `e2fsck -fy` without losing or
     changing anything the operation was not entitled to touch
  3. if it did not finish, the volume must not still claim to be clean - the same
     invariant #142 rests on

An operation is *allowed* to succeed despite a failed allocation. Some of what the
library allocates is for work that is optional (giving back an attribute block, for
one), and skipping that is a real choice rather than a defect.

The allocators are diverted at link time in faultop - see the note there and the
--wrap flags in build.sh - so nothing in the shipping library knows this exists.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

import faultcheck
from faultcheck import (census, file_sha, fresh, fsck_clean, ino_of,
                        is_clean_flag, make_file, repairs_losslessly, sh)

FAULTOP = faultcheck.FAULTOP


def run(img, alloc_fail, *op):
    """-> (returncode, allocs, rc, output). allocs is what the run counted."""
    env = dict(os.environ, EXT4_FAIL_ALLOC=str(alloc_fail))
    r = subprocess.run([FAULTOP, img, "0", *op], capture_output=True, text=True,
                       env=env)
    allocs = rc = -1
    for tok in r.stdout.split():
        if tok.startswith("allocs="):
            allocs = int(tok.split("=", 1)[1])
        elif tok.startswith("rc="):
            rc = int(tok.split("=", 1)[1])
    return r.returncode, allocs, rc, (r.stdout + r.stderr).strip()


def sweep(base_img, label, op, problems, may_change=()):
    may_change = set(may_change)
    with tempfile.TemporaryDirectory() as tmp:
        probe = os.path.join(tmp, "n.img")
        shutil.copy(base_img, probe)
        code, allocs, rc, out = run(probe, 0, *op)
        if code != 0 or rc != 0 or allocs <= 0:
            problems.append(f"{label}: the unfaulted operation did not succeed "
                            f"({out[:120]})")
            return
        if not fsck_clean(probe):
            problems.append(f"{label}: the unfaulted operation is not e2fsck-clean")
            return

        before = census(base_img, tmp)
        base_sha = file_sha(base_img)
        survived = succeeded = 0
        bad = 0
        for n in range(1, allocs + 1):
            img = os.path.join(tmp, f"a{n}.img")
            shutil.copy(base_img, img)
            code, _, rc, out = run(img, n, *op)

            if code < 0:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: failing allocation {n} of {allocs} "
                                    f"killed the process with signal {-code}")
                continue
            if code != 0:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: failing allocation {n} of {allocs} "
                                    f"made the driver exit {code}: {out[:100]}")
                continue
            if rc == 0:
                succeeded += 1

            # The same #142 invariant faultcheck checks: an operation that did not
            # finish must leave the volume saying so, unless it changed nothing at
            # all.
            if rc != 0 and is_clean_flag(img) is not False and file_sha(img) != base_sha:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: failing allocation {n} left the volume "
                                    f"marked clean after changing it")
                continue

            if fsck_clean(img):
                survived += 1
                continue
            ok, why = repairs_losslessly(img, before, tmp, may_change)
            if not ok:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: failing allocation {n} of {allocs}: {why}")
        if bad == 0:
            print(f"  {label:<26} {allocs:>3} allocations, {survived} left nothing to "
                  f"repair, {succeeded} finished anyway")


def main():
    global FAULTOP
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="run just the operations whose label contains this")
    ap.add_argument("--faultop", default=FAULTOP)
    args = ap.parse_args()
    # faultcheck's helpers shell out to their own copy of the name, so a mutant
    # binary has to replace it there too or half the run would use the pristine one.
    FAULTOP = faultcheck.FAULTOP = args.faultop

    if not os.path.exists(FAULTOP):
        sys.exit(f"{FAULTOP} not found - build it first")
    # A faultop built without the --wrap flags would count nothing and pass
    # everything, which is the one failure mode this stand must not have.
    with tempfile.TemporaryDirectory() as tmp:
        img = fresh(tmp, "probe.img")
        code, allocs, rc, out = run(img, 0, "mkdir", "2", "probe")
        if allocs <= 0:
            sys.exit("faultop reports no allocations - it was built without "
                     "-Wl,--wrap=malloc, so this stand would check nothing")

    problems = []
    print("failing each allocation of each operation in turn:")
    with tempfile.TemporaryDirectory() as tmp:
        base = fresh(tmp, "base.img")
        make_file(base, 2, "victim.dat", 6)
        victim = ino_of(base, "/victim.dat")
        if not victim:
            sys.exit("could not place the file the write operations act on")
        subprocess.run([FAULTOP, base, "0", "mkdir", "2", "adir"],
                       capture_output=True, text=True)
        if not fsck_clean(base):
            sys.exit("the fixture is not clean before anything is faulted")

        # The names are as census() keys them: relative to the volume root, with no
        # leading slash, because that is what `debugfs rdump` plus os.path.relpath
        # produces. Writing them with a slash makes every entry look lost.
        #
        # setsize's target has to land inside the file's last mapped block - the
        # writer refuses anything else on purpose (EXTW_ERR_RANGE), so for a
        # six-block 1 KiB file that is [5120, 6144].
        ops = [
            ("mkdir",    ["mkdir", "2", "newdir"],                  ["newdir"]),
            ("create",   ["create", "2", "newfile"],                ["newfile"]),
            ("unlink",   ["unlink", "2", "victim.dat"],             ["victim.dat"]),
            ("rmdir",    ["rmdir", "2", "adir"],                    ["adir"]),
            ("rename",   ["rename", "2", "victim.dat", "2", "moved.dat"],
                                                                    ["victim.dat", "moved.dat"]),
            ("append",   ["append", str(victim), "4"],              ["victim.dat"]),
            ("truncate", ["truncate", str(victim), "2"],            ["victim.dat"]),
            ("setsize",  ["setsize", str(victim), "5500"],          ["victim.dat"]),
            ("writeat",  ["writeat", str(victim), "1000", "900"],   ["victim.dat"]),
            ("add",      ["add", "2", str(victim), "second.dat"],   ["second.dat"]),
        ]
        for label, op, may_change in ops:
            if args.only and args.only not in label:
                continue
            sweep(base, label, op, problems, may_change)

    if problems:
        print("\nFAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("\nevery allocation of every operation can fail without a crash, and what "
          "is left is always repairable without loss")
    return 0


if __name__ == "__main__":
    sys.exit(main())
