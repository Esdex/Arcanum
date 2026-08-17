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
Exercises byte offsets past 4 GB.

    ./bigcheck.py

Every other stand builds an image of 300 MB or less, so until this existed no test
in this directory had ever addressed a byte beyond that - on any build. Anything
that goes wrong only above 2 GB or 4 GB was untested by construction, and vaults
that size are ordinary (#147).

Cheap, because the images are sparse: a 5 GB filesystem costs a few megabytes of
real disk, since ext4 only writes metadata at mkfs time and this stand only ever
touches a handful of blocks.

Reaching a high offset without filling the filesystem takes two tricks:

  inodes  an inode's position is fixed by its number, so an inode in the last
          group sits in that group's inode table wherever that is. `extwrite mtime`
          is read_inode + write_inode and nothing else, so it lands there directly
          with no allocation involved.

  blocks  the allocator hands out the lowest free block, which is always in the
          first group or two. `alloc goal <block> <n>` starts the search where it
          is told - the library has always taken a goal, this exposes it.

Do not compute where a group's inode table is. `flex_bg` gathers the tables of a
whole flex group at its start, so group 17 of a 40-group filesystem is still near
the front while group 39 is at 4 GB. Read the real locations out of `dumpe2fs`,
which is what `high_group` below does.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "bench")
ALLOC = os.path.join(HERE, "alloc")
EXTWRITE = os.path.join(HERE, "extwrite")

# 2^32. Every assertion below insists the offset it exercised is past this, so a
# geometry change that quietly shrinks the images turns into a failure rather than
# a test that still passes while checking nothing.
FOUR_GB = 1 << 32

# (label, apparent size, block size, blocks). 1 KiB blocks give ~640 groups, which
# is where the descriptor table itself gets big; 4 KiB is what the app makes.
GEOMETRIES = [
    ("5G/4K", "5G", 4096, 5 * 1024 * 1024 // 4),
    ("5G/1K", "5G", 1024, 5 * 1024 * 1024),
]


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def group_layout(img):
    """[(group, inode_table_block, blocks_per_group, inodes_per_group)] from dumpe2fs."""
    r = sh("dumpe2fs", img)
    if r.returncode != 0:
        return [], 0, 0
    bpg = ipg = 0
    for ln in r.stdout.splitlines():
        if ln.startswith("Blocks per group:"):
            bpg = int(ln.split(":", 1)[1])
        elif ln.startswith("Inodes per group:"):
            ipg = int(ln.split(":", 1)[1])
    groups, cur = [], None
    for ln in r.stdout.splitlines():
        m = re.match(r"^Group (\d+):", ln)
        if m:
            cur = int(m.group(1))
        elif cur is not None:
            m = re.search(r"Inode table at (\d+)", ln)
            if m:
                groups.append((cur, int(m.group(1))))
                cur = None
    return groups, bpg, ipg


def mtime_of(img, ino):
    r = sh("debugfs", "-R", "stat <%d>" % ino, img)
    m = re.search(r"^\s*mtime:\s*(0x[0-9a-f]+)", r.stdout, re.M)
    return m.group(1) if m else None


def descriptor_diff(a, b, desc_off, desc_size, group):
    """Byte offsets that differ, split into (within `group`'s descriptor, elsewhere).

    Offsets in the first list are relative to the descriptor, in the second absolute.
    `cmp -l` does the comparing: these images are 5 GB, and reading them into Python
    to walk them byte by byte takes the memory of the machine and more time than the
    rest of this directory put together.
    """
    r = subprocess.run(["cmp", "-l", a, b], capture_output=True, text=True)
    if r.returncode not in (0, 1):
        return [], ["cmp could not compare the images: " + r.stderr.strip()[:120]]
    lo = desc_off + group * desc_size
    hi = lo + desc_size
    within, elsewhere = [], []
    for ln in r.stdout.split("\n"):
        if not ln.strip():
            continue
        # cmp -l prints 1-based positions.
        pos = int(ln.split()[0]) - 1
        (within.append(pos - lo) if lo <= pos < hi else elsewhere.append(pos))
    return within, elsewhere


def check_image(label, img, bs, stamp, problems):
    groups, bpg, ipg = group_layout(img)
    if not groups or not bpg or not ipg:
        problems.append(f"[{label}] could not read the group layout out of dumpe2fs")
        return

    # The group whose inode table sits furthest into the image - not the last group,
    # because flex_bg does not lay them out in order.
    high_group, high_table = max(groups, key=lambda g: g[1])
    table_off = high_table * bs
    if table_off <= FOUR_GB:
        problems.append(f"[{label}] the furthest inode table is at {table_off} bytes, "
                        f"which is not past 4 GB - this stand would prove nothing")
        return

    # ── inode write past 4 GB ────────────────────────────────────────────────
    # The first inode of that group. It is not allocated, which does not matter:
    # read_inode and write_inode address by number and do not consult the bitmap.
    # An unallocated inode also means the image stays fsck-clean afterwards.
    high_ino = high_group * ipg + 1
    low_ino = 11                       # lost+found, always in group 0
    before_low = mtime_of(img, low_ino)

    # The value is fresh on every run, never a constant. With --keep the images are
    # reused, and an image still carrying last run's mtime would satisfy a fixed
    # expectation without the driver having written anything at all - a mutant that
    # writes nowhere would pass.
    r = sh(EXTWRITE, img, str(high_ino), "mtime", str(stamp))
    if r.returncode != 0:
        problems.append(f"[{label}] writing inode {high_ino} (table at {table_off} "
                        f"bytes) failed: {r.stderr.strip()[:120]}")
    elif mtime_of(img, high_ino) != "0x%08x" % stamp:
        problems.append(f"[{label}] inode {high_ino} was written but its mtime did not "
                        f"land there - the offset {table_off} was computed wrong")
    if mtime_of(img, low_ino) != before_low:
        problems.append(f"[{label}] writing a high inode also changed inode {low_ino} "
                        f"in group 0 - the offset wrapped into the front of the image")

    # ── block allocate and free past 4 GB ────────────────────────────────────
    # A round trip rather than a bare allocation, so the image is required to be
    # completely fsck-clean afterwards instead of carrying the orphan-block
    # residual a standalone allocation always leaves (see fsckcheck.py).
    goal = high_table + 64             # inside the same far group, past its table
    if goal * bs <= FOUR_GB:
        problems.append(f"[{label}] the allocation goal is not past 4 GB")
        return

    pristine = img + ".pristine"
    subprocess.run(["cp", "--sparse=always", img, pristine], check=True)

    r = sh(ALLOC, img, "goal", str(goal), "4")
    if r.returncode != 0:
        problems.append(f"[{label}] allocating at block {goal} ({goal * bs} bytes) "
                        f"failed: {r.stderr.strip()[:120]}")
        return
    taken = [int(x) for x in r.stdout.split()]
    if not taken or min(taken) < high_table:
        problems.append(f"[{label}] asked for blocks near {goal} and got {taken[:4]} - "
                        f"the goal was ignored, so nothing high was exercised")
        return

    r = sh(ALLOC, img, "free", *[str(b) for b in taken])
    if r.returncode != 0:
        problems.append(f"[{label}] freeing {taken} failed: {r.stderr.strip()[:120]}")
        return
    if not fsck_clean(img):
        problems.append(f"[{label}] taking and returning blocks at {goal * bs} bytes "
                        f"left the filesystem unclean")

    # What a round trip may leave behind is exactly one thing, and it is permanent:
    # the group was BLOCK_UNINIT and had to be initialised to allocate in it, which
    # freeing cannot undo (#140). That shows up as bg_flags losing BLOCK_UNINIT, the
    # block bitmap checksum appearing in both halves, and the descriptor checksum
    # following. Anything outside that group's descriptor means an offset went
    # somewhere it should not have.
    desc_size = 64 if any("64bit" in ln for ln in sh("dumpe2fs", "-h", img).stdout.splitlines()
                          if ln.startswith("Filesystem features")) else 32
    desc_off = (1 if bs > 1024 else 2) * bs      # descriptors follow the superblock
    within, elsewhere = descriptor_diff(pristine, img, desc_off, desc_size, high_group)
    if elsewhere:
        problems.append(f"[{label}] a block round trip at {goal * bs} bytes changed "
                        f"{len(elsewhere)} byte(s) outside group {high_group}'s "
                        f"descriptor, first at {elsewhere[0]}")
    allowed = {0x12, 0x18, 0x19, 0x1E, 0x1F, 0x38, 0x39}
    unexpected = sorted(set(within) - allowed)
    if unexpected:
        problems.append(f"[{label}] the descriptor of group {high_group} changed at "
                        f"offsets {unexpected}, which are not bg_flags, the bitmap "
                        f"checksum or the descriptor checksum")
    os.unlink(pristine)


def main():
    global BENCH, ALLOC, EXTWRITE
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", default=BENCH)
    ap.add_argument("--alloc", default=ALLOC)
    ap.add_argument("--extwrite", default=EXTWRITE)
    ap.add_argument("--keep", metavar="DIR",
                    help="build the images here and leave them (they are sparse)")
    args = ap.parse_args()
    BENCH, ALLOC, EXTWRITE = args.bench, args.alloc, args.extwrite

    for t in (BENCH, ALLOC, EXTWRITE):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    problems = []
    tmp = args.keep or tempfile.mkdtemp()
    if args.keep:
        os.makedirs(tmp, exist_ok=True)
    try:
        stamp = int(time.time()) & 0x7FFFFFFF
        for label, size, bs, blocks in GEOMETRIES:
            # The template is what mke2fs made and is never written to, so --keep can
            # skip rebuilding it; the run always works on a copy. mke2fs on a 5 GB
            # image at 1 KiB blocks is most of this stand's runtime, and it is an
            # external tool, so caching it changes nothing about what is tested.
            tpl = os.path.join(tmp, "template_%s.img" % label.replace("/", "_"))
            if not (args.keep and os.path.exists(tpl)):
                subprocess.run(["truncate", "-s", size, tpl], check=True)
                r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-O", "^has_journal",
                       "-b", str(bs), tpl, str(blocks))
                if r.returncode != 0:
                    problems.append(f"[{label}] mke2fs would not build the image: "
                                    f"{r.stderr.strip()[:160]}")
                    continue
            img = os.path.join(tmp, "work_%s.img" % label.replace("/", "_"))
            subprocess.run(["cp", "--sparse=always", tpl, img], check=True)

            # A reader sanity pass first: if the image does not even open, every
            # failure below would be reported as an offset problem instead.
            r = sh(BENCH, img, "2", "--ls")
            if r.returncode != 0 or "lost+found" not in r.stdout:
                problems.append(f"[{label}] the reader will not open the image at all")
                continue
            check_image(label, img, bs, stamp, problems)
            os.unlink(img)
    finally:
        if not args.keep:
            for f in os.listdir(tmp):
                os.unlink(os.path.join(tmp, f))
            os.rmdir(tmp)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("inode writes and block round trips past 4 GB land where they should, on "
          "5 GB filesystems at 1 KiB and 4 KiB blocks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
