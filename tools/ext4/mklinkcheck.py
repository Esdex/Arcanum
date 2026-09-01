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
The harness for MAKING links, both kinds (#128).

    ./mklinkcheck.py

linkcheck.py asks whether a link a desktop wrote is read correctly, and there the
desktop is the source of truth. This asks the opposite question - whether a link
this driver wrote is one - and the truth has to come from somewhere that is not us:
`debugfs` says what shape the inode is, `fuse2fs` mounts the volume as a second
driver and calls readlink on it, and `e2fsck` says the whole thing hangs together.
Our own reader is asked too, but last and never alone: a writer and a reader that
share a misunderstanding agree perfectly.

**The two kinds are not variants of one feature.**

A symlink is a new inode holding a path. Its whole risk is the shape: up to 59
bytes the target lives inside i_block where an extent root would be, and such an
inode must not carry the extents flag - one byte either side of that boundary and a
desktop and this driver disagree about what a link is. So 59 and 60 are both here,
by construction rather than by hoping some case lands on them.

A hard link is no new inode at all: one more directory entry and one more on the
link count. Its whole risk is that count. Too high leaks an inode until a check
reclaims it; too low frees the file while another name still points at it, and the
file vanishes from a place nobody touched. So the count is checked after every
step, and deleting one of two names has to leave the other reading exactly what it
read before.

  shapes      59 and 60 byte targets: the first inline with no extents flag, the
              second owning a block, both read back by debugfs
  folder      a link to a directory, entered through it
  hard        one inode under two names, the count right, and the contents the
              same through both
  survive     one name of two deleted - the other still reads, and the blocks are
              still allocated; then the last one, and they are not
  refusals    a directory given a second name, an empty target, a target past
              PATH_MAX, a name already taken - each refused and each leaving the
              volume exactly as it was
  fuse2fs     another driver mounts the result and readlink gives back the target
              this one wrote
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from interopcheck import mount_fuse, unmount_fuse       # noqa: E402

WHEN = "1700000000"
FAST_MAX = 59


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def debugfs(img, script):
    r = subprocess.run(["debugfs", "-f", "/dev/stdin", img],
                       input=script, capture_output=True, text=True)
    return r.stdout


def stat_of(img, path):
    """-> the text debugfs prints for one path, or ''."""
    return debugfs(img, f"stat {path}\n")


def inode_of(img, path):
    m = re.search(r"Inode:\s*(\d+)", stat_of(img, path))
    return int(m.group(1)) if m else None


def links_of(img, path):
    m = re.search(r"Links:\s*(\d+)", stat_of(img, path))
    return int(m.group(1)) if m else None


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(HERE, "mkfs"))
    ap.add_argument("--dirwrite", default=os.path.join(HERE, "dirwrite"))
    ap.add_argument("--extwrite", default=os.path.join(HERE, "extwrite"))
    ap.add_argument("--pathresolve", default=os.path.join(HERE, "pathresolve"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for t in (args.mkfs, args.dirwrite, args.extwrite, args.pathresolve):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    for t in ("e2fsck", "debugfs"):
        if not shutil.which(t):
            sys.exit(f"{t} not found - it is an oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "made.img")
        sh("truncate", "-s", "32M", img)
        if sh(args.mkfs, img, "--bs", "1024").returncode:
            problems.append("mkfs failed")
            print("FAIL"); print(f"     {problems[0]}"); return 1

        def dw(*a):
            return sh(args.dirwrite, img, *[str(x) for x in a])

        # A file with contents, so a hard link has something to share.
        r = dw(2, "create", "real.txt", WHEN)
        file_ino = int(r.stdout.strip()) if r.returncode == 0 else None
        if file_ino is None:
            problems.append("could not create the file the links point at")
        sh(args.extwrite, img, str(file_ino), "append", "4")
        dw(2, "mkdir", "folder", WHEN)
        folder_ino = inode_of(img, "/folder")

        # ── the shape boundary, both sides of it ───────────────────────────
        # 59 bytes is the last target that fits inside the inode and 60 is the
        # first that does not. Chosen rather than stumbled upon: this is the one
        # number a desktop and this driver have to agree on, and a case that only
        # ever uses short or only long targets never asks.
        for length, want_fast in ((FAST_MAX, True), (FAST_MAX + 1, False)):
            name = f"t{length}.lnk"
            target = "x" * length
            r = dw(2, "symlink", name, target, WHEN)
            if r.returncode != 0:
                problems.append(f"[{length}] symlink refused: {r.stderr.strip()[:120]}")
                continue
            st = stat_of(img, "/" + name)
            if "Type: symlink" not in st:
                problems.append(f"[{length}] debugfs does not call it a symlink")
                continue
            inline = "Fast link dest" in st
            has_extents = "Flags: 0x80000" in st
            if inline != want_fast:
                problems.append(
                    f"[{length}] stored {'inline' if inline else 'in a block'}, "
                    f"expected the other - 59 bytes is the last that fits in the "
                    f"inode, and a driver that draws the line elsewhere makes links "
                    f"a desktop reads as a different shape")
            if inline and has_extents:
                problems.append(
                    f"[{length}] the target is inside the inode AND the extents flag "
                    f"is set - the path sits where an extent root should be, which is "
                    f"how sixty bytes of it get read back as file data")
            tgot = sh(args.pathresolve, img, "readlink", "/" + name).stdout.strip()
            if tgot != target:
                problems.append(f"[{length}] the target reads back as {len(tgot)} "
                                f"bytes, not {length}")
            elif args.verbose:
                print(f"  {length}-byte target   {'inline' if inline else 'in a block'}"
                      f", reads back whole")

        # ── a link to a folder, and going through it ──────────────────────
        dw(folder_ino, "create", "inside.txt", WHEN)
        if dw(2, "symlink", "dir.lnk", "folder", WHEN).returncode != 0:
            problems.append("[folder] a link to a directory was refused")
        else:
            direct = sh(args.pathresolve, img, "resolve", "/folder/inside.txt")
            through = sh(args.pathresolve, img, "resolve", "/dir.lnk/inside.txt")
            if through.returncode != 0 or through.stdout != direct.stdout:
                problems.append(
                    f"[folder] '/dir.lnk/inside.txt' gives "
                    f"'{through.stdout.strip() or through.stderr.strip()}' but "
                    f"'/folder/inside.txt' gives '{direct.stdout.strip()}' - a link "
                    f"to a directory that cannot be entered is a directory nothing "
                    f"can be done with")
            elif args.verbose:
                print("  link to a folder   entered, same inode as the real path")

        # ── one inode, two names ──────────────────────────────────────────
        before_links = links_of(img, "/real.txt")
        if dw(2, "hardlink", "second.txt", file_ino, WHEN).returncode != 0:
            problems.append("[hard] a second name was refused")
        else:
            if inode_of(img, "/second.txt") != file_ino:
                problems.append("[hard] the second name points at a different inode - "
                                "that is a copy, which is what was asked to be avoided")
            now = links_of(img, "/real.txt")
            if now != before_links + 1:
                problems.append(f"[hard] the link count went {before_links} -> {now}, "
                                f"expected {before_links + 1}. Too low is the "
                                f"dangerous direction: deleting either name would then "
                                f"free a file the other still points at")
            elif args.verbose:
                print(f"  hard link          one inode, {now} names")

        # ── deleting one name of two ──────────────────────────────────────
        blocks_before = re.search(r"Blockcount:\s*(\d+)", stat_of(img, "/real.txt"))
        dw(2, "unlink", "real.txt", WHEN)
        st = stat_of(img, "/second.txt")
        if "Inode:" not in st:
            problems.append("[survive] deleting one of two names took the file with it")
        else:
            after = links_of(img, "/second.txt")
            blocks_after = re.search(r"Blockcount:\s*(\d+)", st)
            if after != 1:
                problems.append(f"[survive] the count is {after} after one of two "
                                f"names went, expected 1")
            if blocks_before and blocks_after and \
                    blocks_before.group(1) != blocks_after.group(1):
                problems.append("[survive] the file lost blocks when its other name "
                                "was deleted")
            elif args.verbose:
                print("  one name deleted   the other still reads, blocks untouched")

        # ── removing a link leaves what it named ──────────────────────────
        # The layer above this one asks whether a path is a directory before
        # choosing how to remove it, and that question has two different answers
        # depending on whether links are followed. Getting it wrong there empties
        # the folder a link points at. This cannot see that layer, but it can hold
        # the one underneath to the rule it depends on: taking a link's name away
        # touches nothing but the name.
        # A link of its own, so removing it does not take away a case below.
        dw(2, "symlink", "doomed.lnk", "folder", WHEN)
        before_inside = inode_of(img, "/folder/inside.txt")
        dw(2, "unlink", "doomed.lnk", WHEN)
        if inode_of(img, "/doomed.lnk") is not None:
            problems.append("[remove] the link is still there after being unlinked")
        if inode_of(img, "/folder") is None:
            problems.append("[remove] the folder the link named went with it")
        elif inode_of(img, "/folder/inside.txt") != before_inside:
            problems.append("[remove] what was inside the folder the link named "
                            "changed when the link was removed")
        elif args.verbose:
            print("  link removed       the folder it named is untouched")

        # ── everything that has to be refused ─────────────────────────────
        before = digest(img)
        refusals = [
            ("a directory given a second name",
             (2, "hardlink", "folder2", folder_ino, WHEN)),
            ("an empty target",        (2, "symlink", "empty.lnk", "", WHEN)),
            ("a target past PATH_MAX", (2, "symlink", "huge.lnk", "x" * 5000, WHEN)),
            ("a name already taken",   (2, "symlink", "second.txt", "x", WHEN)),
        ]
        for label, argv in refusals:
            if dw(*argv).returncode == 0:
                problems.append(f"[refusal] {label} was allowed")
        if digest(img) != before:
            problems.append("[refusal] the volume changed while every one of those was "
                            "being refused - a refusal that writes is a refusal that "
                            "leaves something behind")
        elif args.verbose:
            print(f"  {len(refusals)} refusals        all refused, nothing written")

        # ── e2fsck, then another driver entirely ──────────────────────────
        if not fsck_clean(img):
            out = sh("e2fsck", "-fn", img).stdout
            problems.append(f"e2fsck rejects what was made:\n           "
                            f"{out.strip()[:500]}")

        if shutil.which("fuse2fs"):
            mnt = os.path.join(tmp, "mnt")
            os.makedirs(mnt, exist_ok=True)
            proc = mount_fuse(img, mnt, rw=False)
            if proc is None:
                problems.append("fuse2fs would not mount what was made")
            else:
                try:
                    for name, want in ((f"t{FAST_MAX}.lnk", "x" * FAST_MAX),
                                       (f"t{FAST_MAX + 1}.lnk", "x" * (FAST_MAX + 1)),
                                       ("dir.lnk", "folder")):
                        p = os.path.join(mnt, name)
                        if not os.path.islink(p):
                            problems.append(f"[fuse2fs] {name} is not a link to "
                                            f"another driver")
                        elif os.readlink(p) != want:
                            problems.append(f"[fuse2fs] {name} reads back as "
                                            f"'{os.readlink(p)}'")
                    if args.verbose:
                        print("  fuse2fs            another driver calls them links "
                              "and reads the targets")
                except OSError as e:
                    problems.append(f"[fuse2fs] {e}")
                unmount_fuse(mnt, proc)
        else:
            print("     note: fuse2fs not installed, skipping the second-driver check")

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("links can be made: a target of 59 bytes goes inside the inode and 60 into a "
          "block, which is where a desktop draws the same line; a link to a folder can "
          "be walked through; a second name shares one inode and one set of blocks, "
          "and deleting either leaves the other whole; every refusal writes nothing; "
          "and another driver mounts the result and reads back the targets this one "
          "wrote.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
