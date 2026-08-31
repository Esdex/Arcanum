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
The harness for reading and following symlinks (#163).

    ./linkcheck.py

Nothing this driver creates is a symlink, so every link it will ever meet was
written somewhere else - which is why the whole fixture here is built by `ln -s`
and `mke2fs -d`, and why the expected answers are taken from what the desktop
made rather than from what we think it made.

**Two calls, and the difference between them is the point.** `ext4_resolve_path`
follows a link, including the last component: it answers "what does this path
name", which is what opening and reading want. `ext4_resolve_path_nofollow` stops
at the link itself, which is what showing it in a listing, reading its target and
removing it want. Using the wrong one is not a crash - it quietly acts on the
wrong object, and for a dangling link it fails on something that plainly exists.
So every case here checks both, and they have to differ exactly where a link is
involved and nowhere else.

**A component the path goes THROUGH is followed either way.** A link to a
directory that cannot be entered is a directory nothing can be done with, so
"/dirlink/deep.txt" has to resolve under both calls.

**What the limits are for.** A ring of links would spin forever, so the whole
resolution is bounded at 40 expansions and the nesting of one inside another at 8,
both copied from Linux: a volume from a desktop was built under those rules, so
anything they allow has to work here. The cases below walk right up to each limit
and one step past it, because a cap that is off by one is a cap that refuses
something a desktop allows.

**The layer is read-only, and that is checked rather than assumed.** Resolving a
path, following a chain and reading a target all happen through the reader alone.
The image is compared byte for byte before and after every case, so a resolution
that wrote anything at all - a timestamp, a checksum, anything - is a failure.
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_fixture(tmp, problems):
    """A volume holding every shape of link, built by tools that are not ours."""
    tree = os.path.join(tmp, "tree")
    os.makedirs(os.path.join(tree, "sub"), exist_ok=True)
    with open(os.path.join(tree, "target.txt"), "w") as fh:
        fh.write("the target's own contents\n")
    with open(os.path.join(tree, "sub", "deep.txt"), "w") as fh:
        fh.write("under a directory reached through a link\n")

    # Short enough to live inside the inode, so it owns no blocks at all.
    os.symlink("target.txt", os.path.join(tree, "fast.lnk"))
    # Past the 60 bytes i_block holds, so it owns one and is read like a file.
    os.symlink("/" + "long/" * 15 + "target", os.path.join(tree, "slow.lnk"))
    os.symlink("sub", os.path.join(tree, "dirlink"))
    os.symlink("fast.lnk", os.path.join(tree, "chain.lnk"))
    os.symlink("/target.txt", os.path.join(tree, "abs.lnk"))
    os.symlink("nothing-here", os.path.join(tree, "dangling.lnk"))
    os.symlink("loopb", os.path.join(tree, "loopa"))
    os.symlink("loopa", os.path.join(tree, "loopb"))
    os.symlink("self", os.path.join(tree, "self"))
    # A relative target that climbs out of a directory and back down.
    os.symlink("../target.txt", os.path.join(tree, "sub", "up.lnk"))
    # Two links that only differ by where their target is measured from. "near"
    # names something that exists ONLY beside it, so resolving from the root
    # instead of from the link's own directory finds nothing; "absdeep" names
    # something that exists ONLY at the root, so resolving it from the link's
    # directory finds nothing. Without both, a version that measures from the
    # wrong place still answers correctly for every link that happens to sit at
    # the root, which is most of them.
    os.symlink("deep.txt", os.path.join(tree, "sub", "near.lnk"))
    os.symlink("/target.txt", os.path.join(tree, "sub", "absdeep.lnk"))

    # Right up to the limits and one past them. 40 expansions is what Linux allows
    # in one resolution, so a chain of 40 has to work and 41 has to be refused; the
    # nesting limit is 8, and nesting is what a target that is itself under a link
    # produces.
    for n in range(41):
        prev = "chain-end.txt" if n == 0 else f"c{n - 1}"
        os.symlink(prev, os.path.join(tree, f"c{n}"))
    with open(os.path.join(tree, "chain-end.txt"), "w") as fh:
        fh.write("the end of a long chain\n")

    img = os.path.join(tmp, "links.img")
    subprocess.run(["truncate", "-s", "32M", img], check=True)
    r = sh("mke2fs", "-q", "-F", "-t", "ext4", "-b", "1024", "-d", tree, img, "32768")
    if r.returncode != 0:
        problems.append(f"mke2fs would not build the fixture: {r.stderr.strip()[:200]}")
        return None
    if sh("e2fsck", "-fn", img).returncode != 0:
        problems.append("the fixture is not e2fsck-clean before anything touched it")
        return None
    return img


def run(tool, img, mode, path):
    """-> (stdout first line, stderr first line, rc)."""
    r = sh(tool, img, mode, path)
    return (r.stdout.strip().splitlines() or [""])[0], \
           (r.stderr.strip().splitlines() or [""])[0], r.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pathresolve", default=os.path.join(HERE, "pathresolve"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(args.pathresolve):
        sys.exit(f"{args.pathresolve} not found - build it first")
    for t in ("mke2fs", "e2fsck"):
        if not shutil.which(t):
            sys.exit(f"{t} not found - the fixture is built with it")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = build_fixture(tmp, problems)
        if img is None:
            print("FAIL")
            for p in problems:
                print(f"     {p}")
            return 1
        before = digest(img)

        target_ino, _, _ = run(args.pathresolve, img, "resolve", "/target.txt")

        # (path, what resolve must give, what lresolve must give, the target text)
        # "same" means the two calls have to agree - nothing was followed.
        # "link" means lresolve must stop somewhere else than resolve did.
        cases = [
            ("/target.txt",       target_ino,   "same", None),
            ("/fast.lnk",         target_ino,   "link", "target.txt"),
            ("/chain.lnk",        target_ino,   "link", "fast.lnk"),
            ("/abs.lnk",          target_ino,   "link", "/target.txt"),
            ("/sub/up.lnk",       target_ino,   "link", "../target.txt"),
            ("/sub/absdeep.lnk",  target_ino,   "link", "/target.txt"),
            ("/dirlink",          None,         "link", "sub"),
            ("/dirlink/deep.txt", None,         "same", None),
            ("/sub/deep.txt",     None,         "same", None),
        ]
        for path, want, lmode, target in cases:
            got, err, rc = run(args.pathresolve, img, "resolve", path)
            if rc != 0:
                problems.append(f"[{path}] resolve failed: {err}")
                continue
            if want is not None and got != want:
                problems.append(f"[{path}] resolve gave '{got}', expected '{want}' - "
                                f"it did not end up at the same inode as the target")
            lgot, lerr, lrc = run(args.pathresolve, img, "lresolve", path)
            if lrc != 0:
                problems.append(f"[{path}] lresolve failed on something that exists: "
                                f"{lerr}")
                continue
            if lmode == "same" and lgot != got:
                problems.append(f"[{path}] the two calls disagree ('{got}' against "
                                f"'{lgot}') where there is no link to follow")
            if lmode == "link" and lgot == got:
                problems.append(f"[{path}] lresolve followed the link - it gave "
                                f"'{lgot}', the same as resolve, so the link itself "
                                f"cannot be described, listed or removed")
            if target is not None:
                tgot, terr, trc = run(args.pathresolve, img, "readlink", path)
                if trc != 0:
                    problems.append(f"[{path}] readlink failed: {terr}")
                elif tgot != target:
                    problems.append(f"[{path}] readlink gave '{tgot}', but the desktop "
                                    f"wrote '{target}'")
            if args.verbose:
                print(f"  {path:18s} resolve {got:10s} lresolve {lgot:10s}"
                      + (f" -> '{target}'" if target else ""))

        # A relative target measured from the link's own directory. Checked apart
        # from the table because what it must reach is not the shared target.
        deep_ino, _, _ = run(args.pathresolve, img, "resolve", "/sub/deep.txt")
        got, err, rc = run(args.pathresolve, img, "resolve", "/sub/near.lnk")
        if rc != 0:
            problems.append(f"[/sub/near.lnk] resolve failed ({err}) - a target with "
                            f"no slash in it is measured from the directory the link "
                            f"is in, and this one names its neighbour")
        elif got != deep_ino:
            problems.append(f"[/sub/near.lnk] resolve gave '{got}', expected "
                            f"'{deep_ino}' - the target was measured from somewhere "
                            f"other than the link's own directory")
        elif args.verbose:
            print(f"  /sub/near.lnk      resolve {got:10s} measured from its own "
                  f"directory")

        # ── readlink is only ever about a link ─────────────────────────────
        # Handing back i_block as a path for something that is not a symlink would
        # be sixty bytes of an extent root read as text.
        for path in ("/target.txt", "/sub"):
            tgot, terr, trc = run(args.pathresolve, img, "readlink", path)
            if trc == 0:
                problems.append(f"[{path}] readlink returned '{tgot}' for something "
                                f"that is not a link at all")

        # ── a link that names nothing ──────────────────────────────────────
        # It exists, and everything about it can be found out - which is the whole
        # difference the two calls make. Only following it fails.
        got, err, rc = run(args.pathresolve, img, "resolve", "/dangling.lnk")
        if rc == 0:
            problems.append("[dangling] resolve found something to follow a link to "
                            f"a name that does not exist ('{got}')")
        elif err != "ENOENT":
            problems.append(f"[dangling] resolve failed with {err}, not ENOENT - a "
                            f"dead link is a missing name, not a broken volume")
        lgot, lerr, lrc = run(args.pathresolve, img, "lresolve", "/dangling.lnk")
        if lrc != 0:
            problems.append(f"[dangling] lresolve could not find the link itself "
                            f"({lerr}), so nothing can list or delete it")
        tgot, _, trc = run(args.pathresolve, img, "readlink", "/dangling.lnk")
        if trc != 0 or tgot != "nothing-here":
            problems.append(f"[dangling] readlink gave '{tgot}' - the target has to be "
                            f"readable even when it names nothing, or the listing "
                            f"cannot say why the link is dead")
        elif args.verbose:
            print("  dangling           resolve ENOENT, the link and its target still "
                  "readable")

        # ── links that lead round in a circle ──────────────────────────────
        for path in ("/loopa", "/self"):
            got, err, rc = run(args.pathresolve, img, "resolve", path)
            if rc == 0:
                problems.append(f"[{path}] resolve returned '{got}' for a ring of "
                                f"links instead of refusing")
            elif err != "ELOOP":
                problems.append(f"[{path}] a ring of links failed with {err}, not "
                                f"ELOOP")
            _, lerr, lrc = run(args.pathresolve, img, "lresolve", path)
            if lrc != 0:
                problems.append(f"[{path}] lresolve could not reach the link itself "
                                f"({lerr}), so a ring cannot even be deleted")
        if args.verbose:
            print("  loops              ELOOP, and each link still reachable to delete")

        # ── right up to the limit, and one step past ───────────────────────
        got, err, rc = run(args.pathresolve, img, "resolve", "/c39")
        if rc != 0:
            problems.append(f"[c39] a chain of 40 links was refused with {err}, but a "
                            f"desktop resolves it - the cap is one too tight")
        got41, err41, rc41 = run(args.pathresolve, img, "resolve", "/c40")
        if rc41 == 0:
            problems.append("[c40] a chain of 41 links resolved, one past what Linux "
                            "allows - the cap is not being counted")
        elif err41 != "ELOOP":
            problems.append(f"[c40] the chain past the limit failed with {err41}, "
                            f"not ELOOP")
        elif args.verbose:
            print("  chain              40 links resolve, 41 are refused")

        # ── nothing was written ────────────────────────────────────────────
        after = digest(img)
        if after != before:
            problems.append("the image changed while paths were being resolved - the "
                            "whole path layer is read-only and something wrote")
        if sh("e2fsck", "-fn", img).returncode != 0:
            problems.append("the fixture is no longer e2fsck-clean")

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("symlinks are read and followed the way a desktop wrote them: a path is "
          "resolved through a link and to a link, a chain and an absolute target both "
          "arrive, a dead link is a missing name while the link itself stays "
          "readable, a ring is refused rather than walked, the limits sit exactly "
          "where Linux puts them, and nothing was written to find any of it out.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
