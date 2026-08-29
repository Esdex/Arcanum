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
The superblock's last-write time, s_wtime (#156).

    ./wtimecheck.py

Until this existed the field was written by mkfs and never again, so a vault written
to yesterday still claimed it was last touched when it was created. e2fsck never reads
it, which is exactly why nothing caught it: no image comparison and no filesystem check
can see a field that is merely stale. dumpe2fs can, so dumpe2fs is the oracle here.

Three things are asserted, and the second matters as much as the first:

  it lands       a caller that supplies a clock gets that second in the superblock,
                 and the volume is still e2fsck-clean afterwards - which is what says
                 the checksum was recomputed after the stamp rather than before it.
  it stays put   a caller that supplies nothing leaves the field exactly as found.
                 The library reads no clock of its own; every host stand depends on
                 that, since a clock in the middle of them would make each run differ
                 from the last.
  s_mtime alone  the last *mount* time is not touched. Nothing here mounts anything in
                 the kernel's sense, so writing one would be inventing a fact.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

WHEN = 1234567890          # a second nobody would arrive at by accident


def sh(*cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def dumpe2fs_field(img, label):
    out = sh("dumpe2fs", "-h", img).stdout
    m = re.search(rf"^{re.escape(label)}:\s+(.+)$", out, re.M)
    return m.group(1).strip() if m else None


def make_image(path, block_size=1024, size="32M"):
    subprocess.run(["truncate", "-s", size, path], check=True)
    r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-b", str(block_size), path)
    if r.returncode != 0:
        raise SystemExit("mke2fs failed: " + r.stderr)


def as_epoch(text):
    """dumpe2fs prints a local-time string; turn it back into a second."""
    r = sh("date", "-d", text, "+%s")
    return int(r.stdout.strip()) if r.returncode == 0 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--alloc", default=os.path.join(os.path.dirname(__file__), "alloc"))
    args = ap.parse_args()

    if not os.path.exists(args.alloc):
        print("alloc not built - run ./build.sh", file=sys.stderr)
        return 1

    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "fs.img")
        make_image(img)

        made_at   = as_epoch(dumpe2fs_field(img, "Last write time"))
        mount_at  = dumpe2fs_field(img, "Last mount time")

        # 1. a supplied clock lands, and the volume survives it
        r = sh(args.alloc, img, "wtime", str(WHEN))
        if r.returncode != 0:
            failures.append("alloc wtime failed: " + (r.stderr.strip() or r.stdout.strip()))
        got = as_epoch(dumpe2fs_field(img, "Last write time"))
        if got != WHEN:
            failures.append(f"last write time is {got}, expected {WHEN}")

        fsck = sh("e2fsck", "-fn", img)
        if fsck.returncode != 0:
            failures.append("e2fsck is unhappy after the stamp - the superblock checksum "
                            "is computed over the superblock as written, so stamping after "
                            "it is what breaks this:\n" + fsck.stdout.strip())

        # 2. the mount time is not ours to write
        if dumpe2fs_field(img, "Last mount time") != mount_at:
            failures.append("the last mount time changed; nothing here mounts anything")

        # 3. an operation with no clock supplied leaves the field alone
        img2 = os.path.join(tmp, "fs2.img")
        make_image(img2)
        before = as_epoch(dumpe2fs_field(img2, "Last write time"))
        r = sh(args.alloc, img2, "alloc", "8")
        if r.returncode != 0:
            failures.append("alloc failed: " + (r.stderr.strip() or r.stdout.strip()))
        after = as_epoch(dumpe2fs_field(img2, "Last write time"))
        if after != before:
            failures.append(f"a write with no clock supplied moved the time {before} -> "
                            f"{after}; the library must never read a clock of its own")
        if made_at is None:
            failures.append("could not read the original time out of dumpe2fs")

    if failures:
        for f in failures:
            print("FAIL " + f)
        return 1
    print("the superblock carries the second it was given, stays e2fsck-clean, leaves the "
          "mount time alone, and does not move at all when no clock is supplied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
