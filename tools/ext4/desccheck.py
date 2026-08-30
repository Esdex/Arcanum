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
The harness for flushing only the descriptor blocks that changed (#160).

    ./desccheck.py

`ext4_fs_flush` used to write the whole group descriptor table, and it is called at
the end of every high-level operation and again by `mark_clean`. So the cost of a
write followed the size of the VOLUME rather than the size of the change: creating
one empty file cost 9 block writes on a 256 MB vault and 23 on a 64 GB one, because
the table there is 8 blocks and it went down twice.

It now writes only the blocks whose bytes differ from a shadow copy of what the disk
last received. Two things have to hold, and this stand checks them separately
because they fail in opposite directions and only one of them is loud.

**Nothing may be lost.** A change that never reaches the disk leaves the counters on
the volume disagreeing with the ones the driver is allocating from - silent until
e2fsck is run, or until a block is handed out twice. `session --verify-desc` reads
the table back off the image after every flush, through the plain file rather than
through the io the writer uses, and compares it byte for byte with the copy in
memory. That is a direct check of the whole property, not a proxy for it.

**Nothing may be written that did not change.** A flush that writes everything is
perfectly correct and gives up the entire point, and no correctness check anywhere
would notice. So the second assertion is the one from the issue: the SAME script
must write the SAME number of blocks on a 256 MB volume and on a 64 GB one. Before
the change those differed by two and a half times; if they ever differ again,
something has gone back to writing the table whole.

There is a third thing, and it needs a volume shaped for it. Everything above moves
descriptors that live in the FIRST block of the table, because one 4 KiB block holds
64 of them and nothing here allocates far enough to leave it. Two defects hide in
that gap: a run of adjacent changed blocks written only as its first block, and a
shadow that is never brought up to date so every block that ever changed is written
for the rest of the mount. Both need a table more than one block long with more than
one block changed.

So the second phase uses 1 KiB blocks, where a descriptor block holds only 16 groups
and a 256 MB volume has 32 of them in two blocks - the second covering groups 16 to
31, which start at 134 MB. Appending 140 MB reaches them, and then the measurement is
of operations that change NO descriptor at all: stamping a timestamp writes an inode
and nothing else. What that costs must not depend on what the volume did earlier. A
shadow left stale makes those flushes drag every previously-changed block along with
them.

e2fsck judges the result in both sizes, as everywhere else here.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

SMALL = 256 * 1024 * 1024
LARGE = 64 * 1024 * 1024 * 1024

# Scripts chosen for the descriptors they move: an inode counter, a directory count,
# block counters in a group far from group 0, and a case that touches two groups.
SCRIPTS = {
    "create a file":        "create /a.bin\n",
    "make a directory":     "mkdir /d\n",
    "create then rename":   "create /a.bin\nrename /a.bin /b.bin\n",
    "create then unlink":   "create /a.bin\nunlink /a.bin\n",
    "append a megabyte":    "create /c.bin\nappend /c.bin 1048576\n",
    "several files":        "".join(f"create /f{i}.bin\nappend /f{i}.bin 65536\n" for i in range(6)),
    # The unlink before the rmdir is not decoration: a directory with anything left in
    # it is refused, and the point here is a run of operations that all succeed.
    "a directory tree":     "mkdir /a\nmkdir /a/b\nmkdir /a/b/c\ncreate /a/b/c/d.bin\n"
                            "append /a/b/c/d.bin 8192\nunlink /a/b/c/d.bin\nrmdir /a/b/c\n",
    "read only":            "list /\nlist /\n",
}


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def run_one(mkfs, session, tmp, size, name, script, problems):
    img = os.path.join(tmp, "d.img")
    scr = os.path.join(tmp, "d.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", str(size), img)
    if sh(mkfs, img, "--bs", "4096").returncode:
        problems.append(f"mkfs at {size} bytes failed")
        return None
    with open(scr, "w") as f:
        f.write(script)

    r = sh(session, img, "hold", scr, "--verify-desc")
    if r.returncode != 0:
        problems.append(f"[{name} @ {size >> 20} MB] {r.stderr.strip() or r.stdout.strip()}")
        return None

    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
    if fields.get("desc_mismatch") != "0":
        problems.append(f"[{name} @ {size >> 20} MB] a descriptor change never reached "
                        f"the disk")
    if int(fields.get("failed", 0)) != 0:
        problems.append(f"[{name} @ {size >> 20} MB] {fields['failed']} operations failed")

    fsck = sh("e2fsck", "-fn", img)
    if fsck.returncode != 0:
        problems.append(f"[{name} @ {size >> 20} MB] e2fsck rejects the result "
                        f"(rc={fsck.returncode})\n           {fsck.stdout.strip()[:300]}")
    return int(fields["writes"])


def run_script(session, img, scr_path, script, problems, label):
    """One session over `img`. Returns its block-write count, or None on a failure."""
    with open(scr_path, "w") as f:
        f.write(script)
    r = subprocess.run([session, img, "hold", scr_path, "--verify-desc"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
    if fields.get("desc_mismatch") != "0":
        problems.append(f"[{label}] a descriptor change never reached the disk")
        return None
    if int(fields.get("failed", 0)) != 0:
        problems.append(f"[{label}] {fields['failed']} operations failed")
        return None
    return int(fields["writes"])


# Four stamps of a timestamp: they write an inode and move no descriptor at all, so
# what they cost is a direct reading of how much a flush drags along with it.
TAIL = "mtime /x.bin 1700000000\n" * 4


def check_multi_block_run(mkfs, session, tmp, problems, verbose):
    """A descriptor table two blocks long, with both of them changed in one session.

    The measurement is a MARGINAL cost: the same session run with and without the
    tail, subtracted. It has to be one session, because the defect this is here for -
    a shadow that is never brought up to date - only shows while a handle is held.
    Reopening re-reads the table and hides it, which is exactly what an earlier
    version of this stand did wrong.
    """
    scr = os.path.join(tmp, "m.txt")
    FAR  = "create /x.bin\nappend /x.bin 146800640\n"   # 140 MB, into groups 16+
    NEAR = "create /x.bin\n"
    marginals = {}

    for name, prelude in (("nothing allocated far", NEAR), ("after 140 MB", FAR)):
        costs = []
        for script in (prelude, prelude + TAIL):
            img = os.path.join(tmp, "m.img")
            if os.path.exists(img):
                os.remove(img)
            sh("truncate", "-s", str(SMALL), img)
            if sh(mkfs, img, "--bs", "1024").returncode:
                problems.append("mkfs at 1 KiB blocks failed")
                return
            w = run_script(session, img, scr, script, problems, f"1 KiB, {name}")
            if w is None:
                return
            costs.append(w)
            fsck = sh("e2fsck", "-fn", img)
            if fsck.returncode != 0:
                problems.append(f"[1 KiB, {name}] e2fsck rejects the result "
                                f"(rc={fsck.returncode})\n           {fsck.stdout.strip()[:300]}")
        marginals[name] = costs[1] - costs[0]

    a, b = marginals["nothing allocated far"], marginals["after 140 MB"]
    if a != b:
        problems.append(
            f"[two-block table] four timestamp stamps cost {a} extra block writes in a "
            f"session that allocated only near the start, and {b} in one that reached "
            f"the second descriptor block - they move no descriptor at all, so a flush "
            f"is carrying blocks it has already written")
    elif verbose:
        print(f"  {'two-block table':22s} 4 stamps cost {a} writes either way")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--session", default=os.path.join(here, "session"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for t in (args.mkfs, args.session):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    if not shutil.which("e2fsck"):
        sys.exit("e2fsck not found - it is the oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, script in SCRIPTS.items():
            small = run_one(args.mkfs, args.session, tmp, SMALL, name, script, problems)
            large = run_one(args.mkfs, args.session, tmp, LARGE, name, script, problems)
            if small is None or large is None:
                continue
            if small != large:
                problems.append(
                    f"[{name}] {small} block writes on a 256 MB volume against {large} "
                    f"on a 64 GB one - the cost of a write is following the size of the "
                    f"volume again, which is the whole of #160")
            elif args.verbose:
                print(f"  {name:22s} {small} writes, the same at both sizes")

        check_multi_block_run(args.mkfs, args.session, tmp, problems, args.verbose)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print(f"{len(SCRIPTS)} scripts plus a two-block descriptor table: every flush left "
          f"the table on disk equal to the one in memory, e2fsck clean, each script wrote "
          f"the same number of blocks at 256 MB as at 64 GB, and an operation that moves "
          f"no descriptor costs the same however far the volume has allocated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
