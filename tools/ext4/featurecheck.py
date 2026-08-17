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
Proves the open path refuses a filesystem it cannot handle - by feature, and by the
one piece of geometry that decides a buffer size.

    ./featurecheck.py

The driver reads and writes through the extent tree, the group descriptors and the
block bitmap directly. A foreign container that turns on a feature which moves any
of those - bigalloc counts the bitmap in clusters, meta_bg lays the descriptors out
elsewhere, inline_data keeps a small file's data in the inode - would be read or
allocated wrong, and since these are people's encrypted vaults that means silent
corruption. So anything outside the supported masks (ext4_extents.h) has to be
refused at open, not written blind.

Two open paths, two rules:

  reader (ext4_open, driven by `bench`)   refuses an unknown INCOMPAT bit; unknown
      RO_COMPAT is safe to *read* by definition, so it is allowed here
  writer (ext4_fs_open, driven by `dirwrite`)  refuses an unknown bit in *either*
      field, because it is about to allocate

The base image is a stock `mke2fs -t ext4` (no journal) - a real foreign ext4, not
one we made - and it must open both ways. Then each unsupported bit is OR'd into the
primary superblock and the tools must react as the rule says. The bit is flipped
raw; the open path does not verify the superblock checksum, which is exactly why a
corrupt-feature volume would otherwise sail in.

Then the inode size (#144). Unlike the feature bits this is not flipped in - the
images are built by `mke2fs -I <n>`, because every size here is one mke2fs makes on
request and a real volume could arrive at this size. 128 and 256 must open both ways;
512 and 1024 must be refused by both, because `write_inode` writes and checksums
`s_inode_size` bytes out of a caller's buffer and every one of those buffers is
EXT4_MAX_INODE_SIZE (256) bytes. Without the refusal the difference comes off the
stack and goes to disk under a checksum that verifies. That is the only reason 512 is
refused: nothing else about it is unsupported, so if the buffers are ever sized for
the format's full 1024 this check is what should change with them.
"""

import argparse
import os
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "bench")
# The writer probe must be a writer-*only* driver. dirwrite/create also opens the
# reader (ext4_create needs it), so the reader's gate would mask the writer's for an
# INCOMPAT case; `alloc` calls ext4_fs_open and nothing else, so it isolates the
# writer gate.
ALLOC = os.path.join(HERE, "alloc")
WHEN = "1784639915"

SB_OFF = 1024
INCOMPAT_OFF = 0x60
RO_COMPAT_OFF = 0x64


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def reader_opens(img):
    """True when `bench --ls` reads the root - i.e. ext4_open accepted it. Read
    only, so it never modifies the image it is handed."""
    r = sh(BENCH, img, "2", "--ls")
    return r.returncode == 0 and "lost+found" in r.stdout


def writer_opens(img):
    """True when `alloc` opens the writer - i.e. ext4_fs_open accepted it. This
    allocates a block, so it mutates the image; only ever call it on a copy."""
    r = sh(ALLOC, img, "alloc", "1")
    return r.returncode == 0


def flip(img, field_off, bit):
    with open(img, "r+b") as f:
        f.seek(SB_OFF + field_off)
        v = struct.unpack("<I", f.read(4))[0] | bit
        f.seek(SB_OFF + field_off)
        f.write(struct.pack("<I", v))


def main():
    global BENCH, ALLOC
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", default=BENCH)
    ap.add_argument("--alloc", default=ALLOC)
    args = ap.parse_args()
    BENCH, ALLOC = args.bench, args.alloc

    for t in (BENCH, ALLOC):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        base = os.path.join(tmp, "base.img")
        subprocess.run(["truncate", "-s", "64M", base], check=True)
        r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-O", "^has_journal", base, "65536")
        if r.returncode != 0:
            sys.exit(f"mke2fs failed: {r.stderr.strip()[:200]}")

        def copy_of(name):
            img = os.path.join(tmp, name)
            with open(base, "rb") as s, open(img, "wb") as d:
                d.write(s.read())
            return img

        # A real foreign ext4 must open both ways, or the masks are too tight.
        # (base stays pristine; the writer probe mutates, so it runs on a copy.)
        if not reader_opens(base):
            problems.append("the reader rejects a stock mke2fs -t ext4 image - the "
                            "supported INCOMPAT mask is too tight")
        if not writer_opens(copy_of("base_w.img")):
            problems.append("the writer rejects a stock mke2fs -t ext4 image - the "
                            "supported masks are too tight")

        # (field, bit, name, reader_should_open, writer_should_open)
        cases = [
            (INCOMPAT_OFF,  0x00000010, "incompat meta_bg",    False, False),
            (INCOMPAT_OFF,  0x00008000, "incompat inline_data", False, False),
            (INCOMPAT_OFF,  0x00010000, "incompat encrypt",     False, False),
            (RO_COMPAT_OFF, 0x00000200, "ro_compat bigalloc",   True,  False),
            (RO_COMPAT_OFF, 0x00000100, "ro_compat quota",      True,  False),
            (RO_COMPAT_OFF, 0x00008000, "ro_compat verity",     True,  False),
        ]
        for field, bit, name, want_r, want_w in cases:
            img = copy_of(f"{name.replace(' ', '_')}.img")
            flip(img, field, bit)
            got_r = reader_opens(img)   # read only, runs first
            got_w = writer_opens(img)   # mutates img, but we are done with it
            if got_r != want_r:
                problems.append(f"{name}: reader "
                                f"{'opened' if got_r else 'refused'}, expected "
                                f"{'open' if want_r else 'refuse'}")
            if got_w != want_w:
                problems.append(f"{name}: writer "
                                f"{'opened' if got_w else 'refused'}, expected "
                                f"{'open' if want_w else 'refuse'}")

        # (inode size, reader_should_open, writer_should_open). 128 is in here as
        # much as 512 is: the guard is a range, and one written as a bare upper
        # bound would let a 64-byte inode through, which is a buffer nothing reads
        # correctly rather than one nothing writes safely.
        for isize, want_r, want_w in ((128, True, True), (256, True, True),
                                      (512, False, False), (1024, False, False)):
            img = os.path.join(tmp, f"isize{isize}.img")
            subprocess.run(["truncate", "-s", "64M", img], check=True)
            r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-O", "^has_journal",
                   "-I", str(isize), img, "65536")
            if r.returncode != 0:
                # Not a failure of the code under test - say so plainly rather than
                # reporting a refusal the driver never got the chance to make.
                problems.append(f"inode size {isize}: mke2fs would not build the "
                                f"image ({r.stderr.strip()[:120]})")
                continue
            got_r = reader_opens(img)   # read only, runs first
            got_w = writer_opens(img)   # mutates img, but we are done with it
            if got_r != want_r:
                problems.append(f"inode size {isize}: reader "
                                f"{'opened' if got_r else 'refused'}, expected "
                                f"{'open' if want_r else 'refuse'}")
            if got_w != want_w:
                problems.append(f"inode size {isize}: writer "
                                f"{'opened' if got_w else 'refused'}, expected "
                                f"{'open' if want_w else 'refuse'}")

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("a stock foreign ext4 opens both ways; every unsupported feature bit is "
          "refused (reader on INCOMPAT, writer on either field); 128- and 256-byte "
          "inodes open, 512 and 1024 are refused by both")
    return 0


if __name__ == "__main__":
    sys.exit(main())
