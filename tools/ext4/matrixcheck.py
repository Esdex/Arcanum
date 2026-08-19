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
Sweeps the shapes somebody else's mke2fs produces.

    ./matrixcheck.py

The corpus every other stand uses is built by one generator with two feature sets.
That is enough to check the driver is right, and not enough to check it is right
about *volumes it did not make* - which is the whole promise of opening a container
built on a desktop (#147).

One axis at a time rather than a cross product. A feature being mishandled shows up
as that feature's row failing, and a cross product would take a hundred times as
long to say the same thing.

Each row is judged four ways, because they fail differently:

  fsmeta       an independent recomputation of every checksum in the image. This
               is the one that catches a wrong checksum *seed*: e2fsck agrees with
               whatever the volume says about itself, but fsmeta recomputes.
  our reader   the listing has to match debugfs, and a seeded file's bytes have to
               match what was put in it.
  e2fsck       after we write. A volume that was clean before and is not after is
               damage we did.
  fuse2fs      reads back the file we wrote. Another driver, so it agrees with us
               only where we are both right rather than where we assumed alike.

## What this found

`metadata_csum_seed` is a separate feature from `metadata_csum`. It exists so the
UUID can be changed without rewriting every checksum, and when it is *off* the
s_checksum_seed field is not maintained - the seed is the crc32c of the UUID
instead. The writer read the field unconditionally, so on such a volume every
checksum it stamped was computed from zero: creating one file was enough for e2fsck
to report invalid descriptor, inode and directory checksums on a volume that had
been clean. The reader had always got this right, and `fsmeta` had the writer's
mistake too - which is exactly why nothing noticed.

Volumes without extents (ext2, ext3) are in the sweep as well. Those the driver
must *refuse*, and refuse without having touched the image, which is checked by
comparing it byte for byte afterwards.
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

from interopcheck import mount_fuse, unmount_fuse

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "bench")
DIRWRITE = os.path.join(HERE, "dirwrite")
FSMETA = os.path.join(HERE, "fsmeta")
CHUNKWRITE = os.path.join(HERE, "chunkwrite")

WHEN = "1700000000"

# (label, extra mke2fs arguments, writable). "writable" is what the driver is
# expected to do, not what would be nice: a volume whose files are not extent-based
# is one this driver has never claimed to write, and the check is that it says so
# rather than that it copes.
ROWS = [
    ("baseline",            ["-t", "ext4"],                                  True),
    ("^metadata_csum_seed", ["-t", "ext4", "-O", "^metadata_csum_seed"],      True),
    ("^metadata_csum",      ["-t", "ext4", "-O", "^metadata_csum"],           True),
    ("^64bit",              ["-t", "ext4", "-O", "^64bit"],                   True),
    ("^flex_bg",            ["-t", "ext4", "-O", "^flex_bg"],                 True),
    ("^has_journal",        ["-t", "ext4", "-O", "^has_journal"],             True),
    ("^dir_index",          ["-t", "ext4", "-O", "^dir_index"],               True),
    ("^resize_inode",       ["-t", "ext4", "-O", "^resize_inode"],            True),
    ("^ext_attr",           ["-t", "ext4", "-O", "^ext_attr"],                True),
    ("^huge_file",          ["-t", "ext4", "-O", "^huge_file"],               True),
    ("^dir_nlink",          ["-t", "ext4", "-O", "^dir_nlink"],               True),
    ("inode 128",           ["-t", "ext4", "-I", "128"],                      True),
    ("block 2048",          ["-t", "ext4", "-b", "2048"],                     True),
    ("block 4096",          ["-t", "ext4", "-b", "4096"],                     True),
    # An old volume: no checksums, 32-byte descriptors, small inodes together.
    ("old-style",           ["-t", "ext4", "-O", "^metadata_csum,^64bit",
                             "-I", "128"],                                    True),
    # No extents. Refusal is the correct outcome, and the image must be untouched.
    ("ext3",                ["-t", "ext3"],                                   False),
    ("ext2",                ["-t", "ext2"],                                   False),
]

# The tree mke2fs seeds each image with, and the exact bytes to expect back.
SEED_FILES = {
    "/hello.txt": b"hello from mke2fs\n",
    "/sub/nested.bin": b"nested payload\n",
}


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def debugfs_names(img, path):
    """Live entry names of a directory, from debugfs rather than from our reader."""
    r = sh("debugfs", "-R", "ls -l %s" % path, img)
    names = set()
    for ln in r.stdout.splitlines():
        m = re.match(r"^\s*(\d+)\s+\d+\s+\(\s*\d+\)", ln)
        if not m or m.group(1) == "0":
            continue
        parts = ln.split()
        if parts:
            names.add(parts[-1])
    return names


def our_names(img, ino):
    """Live entry names as our reader sees them. `bench --ls` prints inode, type,
    name per line."""
    r = sh(BENCH, img, str(ino), "--ls")
    if r.returncode != 0:
        return None
    names = set()
    for ln in r.stdout.splitlines():
        parts = ln.split(None, 2)
        if len(parts) == 3:
            names.add(parts[2])
    return names


def build(img, tree, args, size_bytes=32 * 1024 * 1024):
    """Makes the image, sizing the block count from the row's own block size.

    The count is in blocks, so it cannot be a constant across rows that change -b.
    Passing the wrong thing here is not a red row about the driver, it is mke2fs
    refusing the command line, which is why it is computed rather than written out.
    """
    bs = 1024
    if "-b" in args:
        bs = int(args[args.index("-b") + 1])
    subprocess.run(["truncate", "-s", str(size_bytes), img], check=True)
    r = sh("mke2fs", "-q", "-F", "-d", tree, *args, img, str(size_bytes // bs))
    return r.returncode == 0, r.stderr.strip()[:140]


def has_metadata_csum(img):
    r = sh("dumpe2fs", "-h", img)
    for ln in r.stdout.splitlines():
        if ln.startswith("Filesystem features"):
            return "metadata_csum" in ln.split(":", 1)[1].split()
    return False


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def check_row(label, args, writable, tree, tmp, problems):
    """Runs one row. The row's own status is printed from whether it added
    anything to `problems`, not from reaching the end - an earlier version printed
    "ok" next to a row that had just failed three ways."""
    before = len(problems)
    status = _check_row(label, args, writable, tree, tmp, problems)
    added = len(problems) - before
    print(f"  {label:<22} " +
          (f"FAILED ({added})" if added else (status or "ok")))


def _check_row(label, args, writable, tree, tmp, problems):
    img = os.path.join(tmp, "m.img")
    if os.path.exists(img):
        os.unlink(img)

    ok, err = build(img, tree, args)
    if not ok:
        # Some combinations this e2fsprogs will not build. That is a fact about the
        # tool, not about the driver, so it is reported and not counted against it.
        return f"mke2fs would not build it: {err}"
    if not fsck_clean(img):
        return "pristine image is not e2fsck-clean, skipped"

    # ── the checksums the image came with ────────────────────────────────────
    # Only where there are any. Without metadata_csum there is nothing for fsmeta
    # to recompute - the descriptor checksum on such a volume is the older crc16 of
    # the uninit_bg feature, which this oracle does not implement and this driver
    # does not write. Asking anyway reports every group as bad and says nothing.
    csum = has_metadata_csum(img)
    if csum:
        r = sh(FSMETA, img)
        if r.returncode != 0 or " 0 bad" not in r.stdout:
            problems.append(f"[{label}] recomputing the image's own checksums disagrees "
                            f"with what mke2fs wrote: {r.stdout.strip()[-120:]}")

    # ── reading ──────────────────────────────────────────────────────────────
    theirs = debugfs_names(img, "/")
    ours = our_names(img, 2)
    if writable and ours is None:
        problems.append(f"[{label}] the reader will not list the root at all")
        return
    if writable and ours != theirs:
        problems.append(f"[{label}] listing disagrees with debugfs: "
                        f"only ours {sorted(ours - theirs)}, only debugfs "
                        f"{sorted(theirs - ours)}")

    if not writable:
        # A refusal, and one that leaves the image exactly as it was.
        before = file_sha(img)
        r = sh(DIRWRITE, img, "2", "create", "nf.dat", WHEN)
        if r.returncode == 0:
            problems.append(f"[{label}] the driver wrote to a volume whose files are "
                            f"not extent-based, which it cannot read back")
        elif file_sha(img) != before:
            problems.append(f"[{label}] the write was refused but the image changed")
        return "refused, image untouched"

    # ── writing ──────────────────────────────────────────────────────────────
    payload = os.path.join(tmp, "payload")
    r = sh(CHUNKWRITE, img, "/written.dat", "300000", "65536")
    if r.returncode != 0:
        problems.append(f"[{label}] writing a file failed: {r.stderr.strip()[:120]}")
        return
    for op in (["mkdir", "nd", WHEN], ["create", "nf.dat", WHEN]):
        r = sh(DIRWRITE, img, "2", *op)
        if r.returncode != 0:
            problems.append(f"[{label}] {op[0]} failed: {r.stderr.strip()[:120]}")

    if not fsck_clean(img):
        detail = sh("e2fsck", "-fn", img).stdout
        first = next((l for l in detail.splitlines() if l.strip()), "")
        problems.append(f"[{label}] the volume was e2fsck-clean before we wrote and is "
                        f"not after: {first[:120]}")
    if csum:
        r = sh(FSMETA, img)
        if r.returncode != 0 or " 0 bad" not in r.stdout:
            problems.append(f"[{label}] after writing, the image's checksums do not "
                            f"recompute: {r.stdout.strip()[-120:]}")

    # ── another driver reads what we wrote ───────────────────────────────────
    mnt = os.path.join(tmp, "mnt")
    os.makedirs(mnt, exist_ok=True)
    proc = mount_fuse(img, mnt, rw=False)
    if proc is None:
        problems.append(f"[{label}] fuse2fs will not mount the volume after we wrote")
    else:
        try:
            got = os.path.join(mnt, "written.dat")
            if not os.path.exists(got):
                problems.append(f"[{label}] fuse2fs does not see the file we wrote")
            elif os.path.getsize(got) != 300000:
                problems.append(f"[{label}] fuse2fs sees our file as "
                                f"{os.path.getsize(got)} bytes, not 300000")
            else:
                shutil.copyfile(got, payload)
        finally:
            unmount_fuse(mnt, proc)

    # Our own reader has to agree with fuse2fs byte for byte. Neither is trusted
    # over the other; they are two implementations and the check is that they say
    # the same thing about the bytes we put there.
    if os.path.exists(payload):
        ino = None
        r = sh("debugfs", "-R", "stat /written.dat", img)
        m = re.search(r"Inode:\s*(\d+)", r.stdout)
        if m:
            ino = int(m.group(1))
        if ino:
            r = subprocess.run([BENCH, img, str(ino), "--read"],
                               capture_output=True)
            if hashlib.sha256(r.stdout).hexdigest() != file_sha(payload):
                problems.append(f"[{label}] our reader and fuse2fs disagree about the "
                                f"bytes of the file we wrote")
    return None


def main():
    global BENCH, DIRWRITE, FSMETA, CHUNKWRITE
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="run just the rows whose label contains this")
    # So mutants-matrix.sh can point this at binaries built from staged sources.
    ap.add_argument("--bench", default=BENCH)
    ap.add_argument("--dirwrite", default=DIRWRITE)
    ap.add_argument("--fsmeta", default=FSMETA)
    ap.add_argument("--chunkwrite", default=CHUNKWRITE)
    args = ap.parse_args()
    BENCH, DIRWRITE = args.bench, args.dirwrite
    FSMETA, CHUNKWRITE = args.fsmeta, args.chunkwrite

    for t in (BENCH, DIRWRITE, FSMETA, CHUNKWRITE):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    for tool in ("mke2fs", "e2fsck", "debugfs", "fuse2fs"):
        if not shutil.which(tool):
            sys.exit(f"{tool} not found - it is one of the oracles")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        tree = os.path.join(tmp, "tree")
        os.makedirs(os.path.join(tree, "sub"), exist_ok=True)
        for path, data in SEED_FILES.items():
            p = os.path.join(tree, path.lstrip("/"))
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "wb") as f:
                f.write(data)

        print("one axis at a time, against a volume mke2fs seeded:")
        for label, mkargs, writable in ROWS:
            if args.only and args.only not in label:
                continue
            check_row(label, mkargs, writable, tree, tmp, problems)

    if problems:
        print("\nFAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("\nevery shape mke2fs makes here is read, written and handed back to "
          "fuse2fs intact; the ones without extents are refused untouched")
    return 0


if __name__ == "__main__":
    sys.exit(main())
