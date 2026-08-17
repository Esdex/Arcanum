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
The kinds of thing this driver never creates but will be handed.

    ./objectcheck.py

Everything else here is built out of what the driver itself makes: regular files
and directories. A tree that came from a Linux desktop holds more - symlinks above
all, but also FIFOs, device nodes, second names for one inode, and files carrying
extended attributes in a block of their own. None of that was in any corpus, so
none of it was ever exercised, and all three defects below had shipped (#147).

  deleting a symlink, a FIFO or a device node
      Unlink truncated whatever it was given. Those kinds keep what they have
      inside i_block and have no extent tree, so truncation failed - *after* the
      name had been taken out of the directory. The entry vanished, the inode was
      stranded ("Unattached inode 13"), and the user was told the delete failed.
      One leaked inode per symlink deleted.

  deleting a file with an external attribute block
      i_file_acl names a block that belongs to the inode. Freeing the inode without
      it left the block marked in use with nothing referring to it - e2fsck
      reported `Block bitmap differences: -2365`.

  renaming anything that is not a file or a directory
      A directory entry carries the kind of thing it names. Rename chose between
      "directory" and "regular file", so moving a symlink relabelled it: `Entry
      'moved.lnk' in /sub has an incorrect filetype (was 1, should be 7)`.

Every row is judged by e2fsck against a fixture that is clean beforehand, so a
failure means damage this driver did rather than a fixture that was never right.
"""

import argparse
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
RENAME = os.path.join(HERE, "rename")

WHEN = "1700000000"

# name -> the directory-entry type it must be reported and re-recorded as.
FT = {
    "fast.lnk": 7, "slow.lnk": 7, "pipe.fifo": 5, "chr.dev": 3, "blk.dev": 4,
    "regular.txt": 1, "hard.link": 1, "target.txt": 1, "sub": 2,
}


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def fsck_first_complaint(img):
    out = sh("e2fsck", "-fn", img).stdout
    for ln in out.splitlines()[2:]:
        if ln.strip() and not ln.startswith("Pass "):
            return ln.strip()[:120]
    return "(no line)"


def build_fixture(tmp, problems):
    """A volume holding one of everything, built by tools that are not ours."""
    tree = os.path.join(tmp, "tree")
    os.makedirs(os.path.join(tree, "sub"), exist_ok=True)
    with open(os.path.join(tree, "regular.txt"), "w") as f:
        f.write("regular content\n")
    with open(os.path.join(tree, "target.txt"), "w") as f:
        f.write("link target\n")
    with open(os.path.join(tree, "sub", "deep.txt"), "w") as f:
        f.write("x\n")
    os.symlink("target.txt", os.path.join(tree, "fast.lnk"))
    # Longer than the 59 bytes that fit inside the inode, so it owns a block and
    # takes the other path through unlink.
    os.symlink("/" + "long/" * 15 + "target", os.path.join(tree, "slow.lnk"))
    os.mkfifo(os.path.join(tree, "pipe.fifo"))

    img = os.path.join(tmp, "objects.img")
    subprocess.run(["truncate", "-s", "32M", img], check=True)
    r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-b", "1024", "-d", tree, img, "32768")
    if r.returncode != 0:
        problems.append(f"mke2fs would not build the fixture: {r.stderr.strip()[:140]}")
        return None

    # Device nodes and a second name need debugfs - a plain user cannot mknod, and
    # `mke2fs -d` turns a hard link into two copies.
    script = "\n".join([
        "ln /regular.txt hard.link",
        "sif /regular.txt links_count 2",
        "mknod chr.dev c 5 1",
        "mknod blk.dev b 8 0",
    ])
    subprocess.run(["debugfs", "-w", "-f", "/dev/stdin", img],
                   input=script, capture_output=True, text=True)

    # An attribute block of its own, on a file and on a directory. It only spills
    # out of the inode once the value is too big to fit inside it, and it will not
    # fit in a 1 KiB block much beyond this - hence two middling values rather than
    # one large one.
    mnt = os.path.join(tmp, "mnt")
    os.makedirs(mnt, exist_ok=True)
    proc = mount_fuse(img, mnt, rw=True)
    if proc is None:
        problems.append("fuse2fs would not mount the fixture to add attributes")
        return None
    try:
        for target in ("target.txt", "sub"):
            for n in (100, 300):
                sh("setfattr", "-n", f"user.a{n}", "-v", "v" * n,
                   os.path.join(mnt, target))
    finally:
        unmount_fuse(mnt, proc)

    for path in ("/target.txt", "/sub"):
        acl = sh("debugfs", "-R", f"stat {path}", img).stdout
        m = re.search(r"File ACL:\s*(\d+)", acl)
        if not m or m.group(1) == "0":
            problems.append(f"the fixture has no external attribute block on {path} - "
                            f"the case it exists for is not being tested")
    if not fsck_clean(img):
        problems.append(f"the fixture is not clean before anything touches it: "
                        f"{fsck_first_complaint(img)}")
        return None
    return img


def our_listing(img, ino=2):
    """{name: file_type} as our reader reports it."""
    r = sh(BENCH, img, str(ino), "--ls")
    if r.returncode != 0:
        return None
    out = {}
    for ln in r.stdout.splitlines():
        parts = ln.split(None, 2)
        if len(parts) == 3:
            out[parts[2]] = int(parts[1])
    return out


def main():
    global BENCH, DIRWRITE, RENAME
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", metavar="DIR", help="leave the fixture here")
    ap.add_argument("--bench", default=BENCH)
    ap.add_argument("--dirwrite", default=DIRWRITE)
    ap.add_argument("--rename", default=RENAME)
    args = ap.parse_args()
    BENCH, DIRWRITE, RENAME = args.bench, args.dirwrite, args.rename

    for t in (BENCH, DIRWRITE, RENAME):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    for tool in ("mke2fs", "e2fsck", "debugfs", "fuse2fs", "setfattr"):
        if not shutil.which(tool):
            sys.exit(f"{tool} not found - it is needed to build or judge the fixture")

    problems = []
    tmp = args.keep or tempfile.mkdtemp()
    os.makedirs(tmp, exist_ok=True)
    try:
        img = build_fixture(tmp, problems)
        if img is None:
            print("FAIL")
            for p in problems:
                print(f"     {p}")
            return 1

        # ── the listing knows what each thing is ─────────────────────────────
        listing = our_listing(img)
        if listing is None:
            problems.append("the reader will not list the fixture's root")
        else:
            for name, want in FT.items():
                got = listing.get(name)
                if got is None:
                    problems.append(f"listing: {name} is missing")
                elif got != want:
                    problems.append(f"listing: {name} is reported as type {got}, "
                                    f"should be {want}")

        work = os.path.join(tmp, "work.img")

        # ── deleting each kind ───────────────────────────────────────────────
        print("deleting each kind (a fresh copy each time):")
        for name in ("fast.lnk", "slow.lnk", "pipe.fifo", "chr.dev", "blk.dev",
                     "target.txt", "regular.txt"):
            shutil.copyfile(img, work)
            r = sh(DIRWRITE, work, "2", "unlink", name, WHEN)
            if r.returncode != 0:
                problems.append(f"unlink {name}: refused ({r.stdout.strip()[:60]})")
            if not fsck_clean(work):
                problems.append(f"unlink {name}: left the volume unclean - "
                                f"{fsck_first_complaint(work)}")
            print(f"  {name:<14} {'ok' if fsck_clean(work) else 'FAILED'}")

        # ── a second name is not the file ────────────────────────────────────
        shutil.copyfile(img, work)
        sh(DIRWRITE, work, "2", "unlink", "hard.link", WHEN)
        if not fsck_clean(work):
            problems.append(f"unlink hard.link: left the volume unclean - "
                            f"{fsck_first_complaint(work)}")
        body = sh("debugfs", "-R", "cat /regular.txt", work).stdout
        if "regular content" not in body:
            problems.append("unlink hard.link: the file's data did not survive "
                            "losing its second name")
        links = sh("debugfs", "-R", "stat /regular.txt", work).stdout
        m = re.search(r"Links:\s*(\d+)", links)
        if not m or m.group(1) != "1":
            problems.append(f"unlink hard.link: link count is "
                            f"{m.group(1) if m else '?'}, should be 1")
        print(f"  hard.link      {'ok' if fsck_clean(work) else 'FAILED'} "
              f"(data survives, one name left)")

        # ── removing a directory that carries attributes ─────────────────────
        shutil.copyfile(img, work)
        sh(DIRWRITE, work, "2", "unlink", "sub/deep.txt", WHEN)
        sh(DIRWRITE, work, "17", "unlink", "deep.txt", WHEN)
        r = sh(DIRWRITE, work, "2", "rmdir", "sub", WHEN)
        if r.returncode == 0 and not fsck_clean(work):
            problems.append(f"rmdir sub (it carries an attribute block): "
                            f"{fsck_first_complaint(work)}")
        print(f"  sub (rmdir)    {'ok' if fsck_clean(work) else 'FAILED'}")

        # ── renaming keeps the kind ──────────────────────────────────────────
        print("renaming each kind, which must keep its type in the new entry:")
        for name in ("fast.lnk", "slow.lnk", "pipe.fifo", "chr.dev", "blk.dev",
                     "target.txt", "sub"):
            shutil.copyfile(img, work)
            r = sh(RENAME, work, "/" + name, "/moved_" + name)
            if r.returncode != 0:
                problems.append(f"rename {name}: refused ({r.stdout.strip()[:60]})")
                continue
            if not fsck_clean(work):
                problems.append(f"rename {name}: left the volume unclean - "
                                f"{fsck_first_complaint(work)}")
            after = our_listing(work)
            got = after.get("moved_" + name) if after else None
            if got != FT[name]:
                problems.append(f"rename {name}: the new entry says type {got}, "
                                f"should be {FT[name]}")
            print(f"  {name:<14} {'ok' if fsck_clean(work) else 'FAILED'}")

        # ── an old-style file must be refused, not half-deleted ──────────────
        # Fabricated by clearing the extent flag, which is what an inode carried
        # over from ext3 looks like. The driver cannot follow indirect blocks, and
        # the thing it must not do is find that out after removing the name.
        shutil.copyfile(img, work)
        sh("debugfs", "-w", "-R", "sif /regular.txt flags 0", work)
        before = open(work, "rb").read()
        r = sh(DIRWRITE, work, "2", "unlink", "regular.txt", WHEN)
        if r.returncode == 0:
            problems.append("unlink of an inode with no extent tree was allowed")
        elif open(work, "rb").read() != before:
            problems.append("unlink of an inode with no extent tree was refused, but "
                            "the image changed - the name was taken out first")
        print(f"  no-extent file {'ok' if r.returncode != 0 else 'FAILED'} (refused, "
              f"image untouched)")
    finally:
        if not args.keep:
            shutil.rmtree(tmp, ignore_errors=True)

    if problems:
        print("\nFAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("\nsymlinks, FIFOs, device nodes, second names and attribute blocks all "
          "survive being listed, deleted and renamed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
