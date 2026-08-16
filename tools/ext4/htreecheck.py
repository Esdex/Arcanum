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
The harness for hash-indexed directories.

    ./htreecheck.py                     # builds its own images, then checks
    ./htreecheck.py --images /tmp/ht    # builds them there once and reuses them

A directory that outgrows one block on a volume with dir_index becomes indexed:
its blocks stop being a flat list and become a tree keyed on a hash of each name.
Everything this layer writes stays linear and nothing it formats is ever indexed,
so an indexed directory only ever arrives from somewhere else - which is exactly
the case that matters, since a container made on a desktop is full of them.

Writing into one is refused. Instead the first write rebuilds it as an ordinary
linear directory and proceeds (#141), and that rebuild is what this measures.

Building the ground took working out how to make an indexed directory at all. It
is not mke2fs: `mke2fs -d` with several thousand files leaves INDEX_FL clear, and
so does creating them through a fuse2fs mount, because both are libext2fs and
libext2fs reads indexes without building them. `e2fsck -fyD` does build them -
that is what "optimizing directories" in pass 3A means - so the recipe is to
populate a volume, then let e2fsck index it. The earlier note in this tree saying
only the kernel can build one was wrong.

What each image is asked:

  baseline    e2fsck -fn is clean and the directory really is indexed
  add         a name goes in, and the flag is gone afterwards
  fsck        e2fsck -fn stays exactly as clean as it was
  listing     every name that was there is still there, once, pointing at the same
              inode with the same file type - checked against debugfs, and our own
              reader is required to agree with debugfs rather than with itself
  checksums   every rebuilt block carries one that verifies
  remove      taking the name back out restores the listing exactly
  again       a second add and remove, on the now-linear directory, still work -
              the rebuild has to leave a directory the ordinary path can use
  fuse2fs     a second driver lists the same names (skipped when not installed)

and two images that must be turned away untouched: one whose first entry is not
".", one whose second is not "..". A rebuild is only safe on a shape that is
recognised, and "refused" here means the image comes back byte-for-byte identical
- the rebuild is measured in full before anything is written, and that is the
property those two check.
"""

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, line_delta, BENIGN_REMARK, bench_map   # noqa: E402
from dircheck import debugfs, debugfs_listing, our_listing, dir_csum_ok  # noqa: E402
from interopcheck import mount_fuse, unmount_fuse                    # noqa: E402

INODE_FLAG_INDEX = 0x1000
DIRENT_HEADER = 8

# A rebuilt block reserves room for a checksum tail exactly when the volume has
# one to reserve it for. Reserving it on a volume without metadata_csum would cost
# twelve bytes a block against a source that spent them on entries, and once the
# shortfall adds up to a whole block the rebuild would refuse a directory it can
# plainly hold - past about 85 blocks at 1 KiB, which only the deep profile
# without metadata_csum reaches.
#
# It is honest to say what that profile does and does not prove. e2fsck -D fills
# its leaves to about 80%, so the deep image needs 178 blocks of the 218 it
# arrives with and the shortfall is nowhere near eating that margin - the profile
# would pass either way. It is here because it is the only image whose leaves are
# packed by a volume without tails at all, and because a directory that arrived
# packed tight is a shape nothing here can produce but a real volume can.
#
# Kept as small as each shape allows, because every mutant run walks all of them
# and e2fsck's cost is the size of the volume rather than of the directory. The
# inode count is given rather than derived: mke2fs sizes it from the volume, and a
# volume small enough to check quickly does not come with room for nine thousand
# files.
#
# name         block  image  inodes  files  extra mke2fs features  long names
PROFILES = [
    ("plain",     1024,  32,   2000,    400,  None,              True),
    ("deep",      1024,  64,  12000,   9000,  None,              False),
    ("4k",        4096,  64,   4000,   2000,  None,              True),
    ("nocsum",    1024,  32,   2000,    400,  "^metadata_csum",  True),
    ("nocsumdeep", 1024, 64,  12000,   9000,  "^metadata_csum",  False),
]


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True)


def inode_flags(img, ino):
    for line in debugfs(img, f"stat <{ino}>\n").splitlines():
        if "Flags:" in line:
            return int(line.split("Flags:")[1].split()[0], 16)
    return None


def inode_size_of(img, ino):
    for line in debugfs(img, f"stat <{ino}>\n").splitlines():
        if "Size:" in line and "Inode" not in line:
            return int(line.split("Size:")[1].split()[0])
    return None


def indirect_levels(img, ino):
    """How deep the index is, for the summary - 0 is a root and leaves only."""
    out = debugfs(img, f"htree_dump <{ino}>\n")
    for line in out.splitlines():
        if "Indirect levels" in line:
            return int(line.split(":")[1].strip())
    return None


def build_image(dest, profile):
    """Populates a volume, then has e2fsck index its one large directory."""
    name, bs, megs, inodes, count, features, long_names = profile
    img = os.path.join(dest, f"{name}.img")
    meta = os.path.join(dest, f"{name}.json")

    with tempfile.TemporaryDirectory() as seed:
        many = os.path.join(seed, "many")
        os.makedirs(many)
        # Somewhere to move the directory to. Renaming it across parents is the
        # only path that rewrites its "..", and that is a third write path which
        # has to rebuild the index before it touches anything.
        os.makedirs(os.path.join(seed, "spare"))
        for i in range(count):
            # A spread of name lengths, because the packing has to place entries
            # of every size and the last one in a block is the one that decides
            # where the chain ends. All-equal names would only ever exercise one
            # arrangement.
            pad = "n" * (i % 37) if long_names else ""
            with open(os.path.join(many, f"file-{i:05d}-{pad}"), "w") as f:
                f.write("x")

        with open(img, "wb") as f:
            f.truncate(megs << 20)
        cmd = ["mke2fs", "-q", "-t", "ext4", "-b", str(bs), "-I", "256",
               "-N", str(inodes), "-d", seed]
        if features:
            cmd += ["-O", features]
        cmd.append(img)
        r = sh(*cmd)
        if r.returncode != 0:
            return None, f"mke2fs failed: {r.stderr.strip()}"

    # Pass 3A is what builds the index. It rewrites the directory, so the image
    # has to be clean afterwards for the run to mean anything.
    sh("e2fsck", "-fyD", img)
    rc, lines, _ = fsck(img)
    if rc != 0:
        return None, f"the built image is not clean (rc={rc}): {lines[:3]}"

    dir_ino = next((i for i, ft, n in debugfs_listing(img, 2) if n == "many"), None)
    if dir_ino is None:
        return None, "the directory to index is not in the root"
    flags = inode_flags(img, dir_ino)
    if flags is None or not (flags & INODE_FLAG_INDEX):
        return None, (f"e2fsck -D did not index the directory (flags={flags:#x}) - "
                      f"too few entries for this block size, or a build without "
                      f"dir_index")

    json.dump({"block_size": bs, "dir_ino": dir_ino, "profile": name,
               "levels": indirect_levels(img, dir_ino)}, open(meta, "w"))
    return (img, meta), None


def ensure_images(dest, only=None):
    built = []
    for profile in PROFILES:
        if only and profile[0] != only:
            continue
        img = os.path.join(dest, f"{profile[0]}.img")
        meta = os.path.join(dest, f"{profile[0]}.json")
        if os.path.exists(img) and os.path.exists(meta):
            built.append((img, meta))
            continue
        made, err = build_image(dest, profile)
        if err:
            print(f"     could not build the {profile[0]} image: {err}")
            return None
        built.append(made)
    return built


def fsck_same(base_rc, base_lines, img, problems, when):
    rc, lines, _ = fsck(img)
    if rc != base_rc:
        problems.append(f"fsck return code changed {when}: {base_rc} -> {rc}")
    new, gone = line_delta(base_lines, lines)
    appeared = [l for l in new if not BENIGN_REMARK.match(l)]
    vanished = [l for l in gone if not BENIGN_REMARK.match(l)]
    if appeared:
        problems.append(f"fsck complains {when}: {appeared[:3]}")
    if vanished:
        problems.append(f"fsck stopped saying, {when}: {vanished[:3]}")


def listings_agree(bench, img, ino, problems, when):
    """-> the listing debugfs reports, having required ours to match it.

    Ours is never the judge. A rebuild that laid out a chain only this code can
    follow would satisfy any check written against our own reader and no other,
    which is the failure the whole harness is arranged to avoid.
    """
    theirs = sorted(debugfs_listing(img, ino))
    ours = our_listing(bench, img, ino)
    if ours is None:
        problems.append(f"our reader could not list the directory {when}")
    elif sorted(ours) != theirs:
        only_ours = [e for e in sorted(ours) if e not in theirs]
        only_theirs = [e for e in theirs if e not in sorted(ours)]
        problems.append(f"our listing and debugfs disagree {when}: "
                        f"ours only {only_ours[:3]}, debugfs only {only_theirs[:3]}")
    names = [n for _, _, n in theirs]
    dupes = sorted({n for n in names if names.count(n) > 1})
    if dupes:
        problems.append(f"duplicate names {when}: {dupes[:3]}")
    return theirs


def fuse_names(img, problems):
    """The names a second driver sees, or None when fuse2fs is not installed."""
    if not shutil.which("fuse2fs"):
        return None
    with tempfile.TemporaryDirectory() as tmp:
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)
        proc = mount_fuse(img, mnt, rw=False)
        if proc is None:
            problems.append("fuse2fs would not mount the rebuilt image")
            return None
        try:
            return sorted(os.listdir(os.path.join(mnt, "many")))
        finally:
            unmount_fuse(mnt, proc)


def check_image(img_src, meta, bench, dirwrite, tmp, verbose=False):
    problems = []
    info = json.load(open(meta))
    ino = info["dir_ino"]
    img = os.path.join(tmp, "fs.img")
    shutil.copy(img_src, img)

    base_rc, base_lines, _ = fsck(img)
    if base_rc != 0:
        return [f"the image is not fsck-clean to start with (rc={base_rc})"]
    if not (inode_flags(img, ino) & INODE_FLAG_INDEX):
        return ["the directory under test is not hash-indexed"]

    before = listings_agree(bench, img, ino, problems, "before writing")
    size_before = inode_size_of(img, ino)
    target = next((i for i, ft, n in before if ft == 1), None)
    if target is None:
        return ["no regular file in the directory to point a new name at"]

    name = "harness-entry"
    r = sh(dirwrite, img, str(ino), "add", name, str(target), "1")
    if r.returncode != 0:
        return [f"add into a hash-indexed directory failed: {r.stderr.strip()}"]

    flags = inode_flags(img, ino)
    if flags & INODE_FLAG_INDEX:
        problems.append("the directory is still flagged hash-indexed after the "
                        "rebuild")
    fsck_same(base_rc, base_lines, img, problems, "after adding")

    after = listings_agree(bench, img, ino, problems, "after adding")
    gained = [e for e in after if e not in before]
    lost = [e for e in before if e not in after]
    if gained != [(target, 1, name)]:
        problems.append(f"the listing gained {gained[:3]}, expected one new entry")
    if lost:
        problems.append(f"the rebuild lost {len(lost)} entries, e.g. {lost[:3]}")

    ok, checked = dir_csum_ok(bench, img, ino)
    if not ok:
        problems.append("a directory block checksum does not verify after the "
                        "rebuild")

    # The rebuild packs into fewer blocks than it found, so the directory has no
    # reason to grow; the one place it may is an add that lands in a last block
    # left exactly full.
    size_after = inode_size_of(img, ino)
    if size_after < size_before:
        problems.append(f"the directory shrank, {size_before} -> {size_after}: "
                        f"blocks it still reports were not rewritten")
    elif size_after > size_before + info["block_size"]:
        problems.append(f"the directory grew by more than one block, "
                        f"{size_before} -> {size_after}")

    names = fuse_names(img, problems)
    if names is not None:
        want = sorted(n for _, _, n in after if n not in (".", ".."))
        if names != want:
            missing = [n for n in want if n not in names]
            extra = [n for n in names if n not in want]
            problems.append(f"fuse2fs lists something else: missing {missing[:3]}, "
                            f"unexpected {extra[:3]}")

    r = sh(dirwrite, img, str(ino), "remove", name)
    if r.returncode != 0:
        problems.append(f"removing the name again failed: {r.stderr.strip()}")
    else:
        fsck_same(base_rc, base_lines, img, problems, "after removing")
        back = listings_agree(bench, img, ino, problems, "after removing")
        if back != before:
            problems.append("the listing did not come back after removing")

    # Once more, now that it is an ordinary linear directory. A rebuild that left
    # something the normal path cannot use would only show up here.
    r = sh(dirwrite, img, str(ino), "add", "second-entry", str(target), "1")
    if r.returncode != 0:
        problems.append(f"a second add, on the rebuilt directory, failed: "
                        f"{r.stderr.strip()}")
    else:
        fsck_same(base_rc, base_lines, img, problems, "after a second add")
        listings_agree(bench, img, ino, problems, "after a second add")
        r = sh(dirwrite, img, str(ino), "remove", "second-entry")
        if r.returncode != 0:
            problems.append(f"removing the second name failed: {r.stderr.strip()}")

    if verbose and not problems:
        print(f"ok   {info['profile']}: {len(before)} entries, "
              f"{info['levels']} indirect level(s), {checked} blocks checksummed")
    return problems


def check_dotdot(img_src, meta, bench, rename, tmp):
    """Moving the indexed directory to a new parent rewrites its "..".

    The third write path, and the one that touches a single field in the first
    block rather than adding or removing anything - so it is also the one where
    skipping the rebuild would look most harmless. On its own copy: the point is
    that this path rebuilds too, not that it rebuilds after add already has.
    """
    problems = []
    info = json.load(open(meta))
    ino = info["dir_ino"]
    img = os.path.join(tmp, "fs.img")
    shutil.copy(img_src, img)

    base_rc, base_lines, _ = fsck(img)
    before = sorted(debugfs_listing(img, ino))

    r = sh(rename, img, "/many", "/spare/many")
    if r.returncode != 0:
        return [f"moving a hash-indexed directory failed: {r.stderr.strip()}"]

    if inode_flags(img, ino) & INODE_FLAG_INDEX:
        problems.append("the moved directory is still flagged hash-indexed")
    fsck_same(base_rc, base_lines, img, problems, "after the move")

    after = sorted(debugfs_listing(img, ino))
    parent = next((i for i, ft, n in after if n == ".."), None)
    spare = next((i for i, ft, n in debugfs_listing(img, 2) if n == "spare"), None)
    if parent != spare:
        problems.append(f"'..' points at {parent}, expected the new parent {spare}")
    if [e for e in before if e[2] != ".."] != [e for e in after if e[2] != ".."]:
        problems.append("the move changed entries other than '..'")
    return problems


def corrupt_head(img, bench, ino, block_size, which):
    """Breaks the "." or ".." at the head of the directory's first block.

    Nothing else in the block is touched, so what comes back is a directory that
    still parses as a chain and still claims to be indexed - which is the only
    way to ask whether the rebuild checks the shape it is given or assumes it.
    """
    runs = bench_map(bench, img, ino)
    first = next((r for r in runs if r[0] == 0), None)
    if first is None:
        return False
    at = first[1] * block_size
    # "." is the first entry, ".." the second, and both are 12 bytes long in
    # every shape a directory takes.
    off = at + DIRENT_HEADER if which == "dot" else at + 12 + DIRENT_HEADER
    with open(img, "r+b") as f:
        f.seek(off)
        f.write(b"x")
    return True


def check_refused(img_src, meta, bench, dirwrite, tmp, which):
    info = json.load(open(meta))
    ino = info["dir_ino"]
    img = os.path.join(tmp, "fs.img")
    shutil.copy(img_src, img)

    # Chosen before the block is broken. Afterwards its checksum no longer
    # verifies and debugfs will not read the directory at all, so a target picked
    # then would come back empty and the add would be turned away by the name
    # check long before it reached the rebuild - a check that passes for the wrong
    # reason.
    target = next((i for i, ft, n in debugfs_listing(img, ino) if ft == 1), None)
    if target is None:
        return ["no regular file in the directory to point a new name at"]

    if not corrupt_head(img, bench, ino, info["block_size"], which):
        return ["could not find the directory's first block to break"]
    before = hashlib.sha256(open(img, "rb").read()).hexdigest()

    r = sh(dirwrite, img, str(ino), "add", "harness-entry", str(target), "1")
    problems = []
    if r.returncode == 0:
        problems.append(f"a directory whose head is not {which} was rebuilt anyway")
    elif "hash-indexed" not in r.stderr:
        problems.append(f"refused for the wrong reason: {r.stderr.strip()}")
    if hashlib.sha256(open(img, "rb").read()).hexdigest() != before:
        problems.append("the image was written to before the rebuild was refused")
    return problems


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", help="where to build and keep the images, so "
                                     "repeated runs do not rebuild them")
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--rename", default=os.path.join(here, "rename"))
    ap.add_argument("--only", help="run one profile by name")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for tool in (args.bench, args.dirwrite, args.rename):
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first")
    if not shutil.which("e2fsck") or not shutil.which("mke2fs"):
        sys.exit("e2fsprogs is not installed - it is the oracle here")

    keep = args.images
    if keep:
        os.makedirs(keep, exist_ok=True)
    holder = None if keep else tempfile.TemporaryDirectory()
    dest = keep or holder.name

    try:
        images = ensure_images(dest, args.only)
        if images is None:
            return 1

        checked = failed = 0
        for img, meta in images:
            profile = json.load(open(meta))["profile"]
            with tempfile.TemporaryDirectory() as tmp:
                problems = check_image(img, meta, args.bench, args.dirwrite, tmp,
                                       args.verbose)
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {profile}")
                for p in problems:
                    print(f"     {p}")

            with tempfile.TemporaryDirectory() as tmp:
                problems = check_dotdot(img, meta, args.bench, args.rename, tmp)
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {profile} (moving it to a new parent)")
                for p in problems:
                    print(f"     {p}")

            for which in ("dot", "dotdot"):
                with tempfile.TemporaryDirectory() as tmp:
                    problems = check_refused(img, meta, args.bench, args.dirwrite,
                                             tmp, which)
                checked += 1
                if problems:
                    failed += 1
                    print(f"FAIL {profile} (a broken {which} must be refused)")
                    for p in problems:
                        print(f"     {p}")
    finally:
        if holder:
            holder.cleanup()

    print(f"\n{checked} checks over {len(images)} indexed directories, "
          f"{failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
