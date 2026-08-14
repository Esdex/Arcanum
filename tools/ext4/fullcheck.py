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
Proves the extent tree keeps growing when a node below the root fills.

    ./fullcheck.py

The writer used to stop at a depth-2 tree with one full index block below the root:
a full leaf got an empty sibling, that cost an index entry in the parent, and a full
parent that was not the root had nowhere to put it, so the append gave up with
EXTW_ERR_FULL - 7056 extents at 1 KiB (#119). It now hangs a fresh chain of empty
nodes off the lowest ancestor with a free slot, and pushes the root down for a new
level when every one of them is full.

Two cases, because they need different ground:

  cap    an mke2fs image, so the filesystem the writer is judged on is not one we
         formatted. The pool is what mke2fs leaves initialised, which is enough to
         carry the file well past the old 7056-extent ceiling.
  deep   an image our own mkfs formatted, where every group is usable and the pool
         can be large enough to fill the root's four slots too. That forces the
         root split - a depth-3 tree - which the cap case never reaches.

Judged by e2fsprogs, never by our own reader:

  setup e2fsck   the fragmentation setup must itself be clean before the target is
                 touched, so a break in the growth cannot hide as a setup fault
  progress       the append must get past the old cap rather than be refused; when
                 it does stop it must be for want of space, never EXTW_ERR_FULL
  e2fsck         the image is clean afterwards: the tree, i_size, i_blocks and the
                 free counts all agree
  structure      debugfs must show the tree deeper or wider than the old ceiling,
                 and for the deep case a level-3 row - proof the root was pushed
                 down rather than the file merely being long
  read-back      every block dumped by debugfs holds its own logical number. This
                 is the check that matters: a growth that mis-keys an index entry
                 reads correctly from the left and wrongly from the right, which
                 e2fsck does not see because the tree is still structurally sound
  fuse2fs        another driver mounts the result read-write and e2fsck stays clean
                 (skipped when fuse2fs is not installed)
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402
from interopcheck import mount_fuse, unmount_fuse       # noqa: E402

EXTW_ERR_FULL = -5
EXTW_ERR_NOSPACE = -3
FEATURES = "^has_journal,^dir_index"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def fsck(img):
    """-> (rc, stripped output). rc 0 is clean; the optimize remarks stay rc 0."""
    r = sh("e2fsck", "-fn", img)
    return r.returncode, (r.stdout + r.stderr).strip()


def parse_kv(text):
    out = {}
    for tok in text.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k] = v
    return out


def debugfs_size(img, ino):
    text = debugfs(img, f"stat <{ino}>\n")
    m = re.search(r"Size:\s*(\d+)", text)
    return int(m.group(1)) if m else None


def readback_mismatch(img, ino, blocks, block_size, tmpdir):
    """Dumps the file through debugfs and checks every block against fill_pat.

    fullwrite fills block n with (n, k/8) pairs, so a block that ended up under the
    wrong index key reads back carrying someone else's number. Returns a complaint
    or None.
    """
    out = os.path.join(tmpdir, "target.bin")
    debugfs(img, f"dump <{ino}> {out}\n")
    if not os.path.exists(out):
        return "debugfs could not dump the target file"
    size = os.path.getsize(out)
    if size != blocks * block_size:
        return f"dumped {size} bytes, expected {blocks * block_size}"
    # Two pairs per block rather than all of them: the first says which block this
    # is, the last says it is not a shifted copy of one. Every block is still read.
    tail = ((block_size - 8) // 8) * 8
    with open(out, "rb") as fh:
        for n in range(blocks):
            blk = fh.read(block_size)
            for k in (0, tail):
                logical, seq = struct.unpack_from("<II", blk, k)
                if logical != n or seq != k // 8:
                    return (f"block {n} reads back as ({logical}, {seq}) at offset "
                            f"{k} - the tree points somewhere else for it")
    return None


def fuse_roundtrip(img, problems):
    if not shutil.which("fuse2fs"):
        print("     note: fuse2fs not installed, skipping the second-driver check")
        return
    with tempfile.TemporaryDirectory() as mtmp:
        mnt = os.path.join(mtmp, "mnt")
        os.makedirs(mnt)
        proc = mount_fuse(img, mnt, rw=True)
        if not proc:
            problems.append("fuse2fs would not mount the grown image - the state is "
                            "clean only to us")
            return
        try:
            with open(os.path.join(mnt, "after-growth.txt"), "w") as fh:
                fh.write("written by another driver\n")
        except OSError as e:
            problems.append(f"fuse2fs mounted but could not write: {e}")
        sh("sync")
        unmount_fuse(mnt, proc)
        rc, out = fsck(img)
        if rc != 0:
            problems.append(f"e2fsck is not clean after fuse2fs wrote to the grown "
                            f"image (rc={rc})\n{out[:400]}")


def format_image(img, case, size, block_size, inodes, mkfs_tool, problems):
    subprocess.run(["truncate", "-s", size, img], check=True)
    if case == "cap":
        r = sh("mkfs.ext4", "-q", "-F", "-O", FEATURES, "-b", str(block_size),
               "-I", "256", "-N", str(inodes), img)
        err = r.stderr.strip()[:200] if r.returncode != 0 else None
    else:
        blocks = os.path.getsize(img) // block_size
        r = sh(mkfs_tool, img, "--blocks", str(blocks), "--bs", str(block_size),
               "--inodes", str(inodes), "--isize", "256")
        err = (r.stderr + r.stdout).strip()[:200] if r.returncode != 0 else None
    if err:
        problems.append(f"[{case}] could not format the image: {err}")
        return False
    rc, _ = fsck(img)
    if rc != 0:
        problems.append(f"[{case}] a freshly formatted image is not e2fsck-clean (rc={rc})")
        return False
    return True


def run(case, img, per, block_size, fullwrite, tmpdir, problems):
    before = len(problems)

    s = sh(fullwrite, img, "setup", str(per))
    if s.returncode != 0:
        problems.append(f"[{case}] setup failed: {s.stderr.strip()[:300]}")
        return
    rc, out = fsck(img)
    if rc != 0:
        problems.append(f"[{case}] the fragmentation setup is not e2fsck-clean "
                        f"(rc={rc})\n{out[:400]}")
        return

    f = sh(fullwrite, img, "fill")
    if f.returncode != 0:
        problems.append(f"[{case}] fill failed to run: {f.stderr.strip()[:300]}")
        return
    kv = parse_kv(f.stdout)
    try:
        appended = int(kv["appended"])
        arc = int(kv["rc"])
        tino = int(kv["target_inode"])
    except (KeyError, ValueError):
        problems.append(f"[{case}] fill did not report its result: {f.stdout.strip()[:200]}")
        return

    # The ceiling this test exists for. Reaching it must no longer end the append.
    per_block = (block_size - 16) // 12
    old_cap = per_block * per_block

    if arc == EXTW_ERR_FULL:
        problems.append(f"[{case}] the append was refused with EXTW_ERR_FULL after "
                        f"{appended} blocks - the tree still stops growing")
        return
    if arc not in (0, EXTW_ERR_NOSPACE):
        problems.append(f"[{case}] the append returned {arc}, expected 0 or "
                        f"EXTW_ERR_NOSPACE ({EXTW_ERR_NOSPACE})")
        return
    if appended <= old_cap:
        problems.append(f"[{case}] only {appended} blocks landed, which is inside the "
                        f"old {old_cap}-extent ceiling - raise per, or the growth "
                        f"never happened")
        return

    rc, out = fsck(img)
    if rc != 0:
        problems.append(f"[{case}] e2fsck is not clean after the grown append "
                        f"(rc={rc})\n{out[:600]}")
        return

    rows = parse_extents(debugfs(img, f"dump_extents <{tino}>\n"))
    leaves = [e for e in rows if not e["is_index"]]
    depth = max((e["level"] for e in rows), default=0)

    if depth < 2:
        problems.append(f"[{case}] the tree is only {depth} deep - nothing below the "
                        f"root ever filled, so this proves nothing")
    if case == "deep" and depth < 3:
        problems.append(f"[{case}] the tree stopped at depth {depth}: the root's four "
                        f"slots never filled, so the root split was not exercised. "
                        f"Raise per or the image size.")

    size = debugfs_size(img, tino)
    if size != appended * block_size:
        problems.append(f"[{case}] i_size is {size}, expected {appended * block_size} "
                        f"({appended} blocks committed)")

    bad = readback_mismatch(img, tino, appended, block_size, tmpdir)
    if bad:
        problems.append(f"[{case}] {bad}")

    fuse_roundtrip(img, problems)

    if len(problems) == before:
        print(f"[{case}] {appended} blocks, {len(leaves)} extents, depth {depth} "
              f"(old ceiling {old_cap}): e2fsck clean, every block reads back right")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--fullwrite", default=os.path.join(here, "fullwrite"))
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"),
                    help="our own mkfs, used for the deep case")
    ap.add_argument("--bs", type=int, default=1024, help="block size (1024 is the "
                    "lowest ceiling and the cheapest to pass)")
    ap.add_argument("--case", choices=["cap", "deep", "both"], default="both")
    ap.add_argument("--per", type=int, default=8000,
                    help="blocks per comb filler for the cap case")
    ap.add_argument("--deep-per", type=int, default=16000,
                    help="blocks per comb filler for the deep case; must be enough "
                         "to fill the root's four slots")
    ap.add_argument("--size", default="80M", help="image size for the cap case")
    ap.add_argument("--deep-size", default="260M", help="image size for the deep case")
    ap.add_argument("--inodes", type=int, default=60000)
    args = ap.parse_args()

    for tool in (args.fullwrite, args.mkfs):
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first (build.sh)")

    cases = ["cap", "deep"] if args.case == "both" else [args.case]
    problems = []
    for case in cases:
        size = args.size if case == "cap" else args.deep_size
        per = args.per if case == "cap" else args.deep_per
        with tempfile.TemporaryDirectory() as tmp:
            img = os.path.join(tmp, "fs.img")
            if format_image(img, case, size, args.bs, args.inodes, args.mkfs, problems):
                run(case, img, per, args.bs, args.fullwrite, tmp, problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
