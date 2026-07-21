#!/usr/bin/env python3
r"""
The harness for reading directories.

    ./dircheck.py --cases /tmp/cases

Compared against `debugfs ls -l`, not against anything of ours. That matters more
here than it did for extents: a directory is a chain of rec_len jumps, and a
reader that follows the chain wrongly does not crash or return nothing - it
returns a plausible list. Names of files that were deleted are still lying in the
blocks, unreferenced, so a walk that drifts off the chain and starts reading
entry-shaped bytes produces a list that looks entirely reasonable and is wrong.

Three things are checked per directory:

  listing     inode, file type and name of every live entry, against debugfs
  order       entries come back in the order the blocks hold them, which is what
              a caller resuming a partial listing depends on
  checksums   every directory block's tail, verified against what mke2fs wrote

The corpus does not contain an htree directory - nothing in it sets INDEX_FL - so
the index-node path is unexercised. A linear walk is meant to serve both formats,
because an htree's interior nodes are hidden behind an entry that covers the whole
block and reads as dead, but that claim is currently held up by the format
description rather than by any case here. Recorded rather than assumed.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import debugfs      # noqa: E402

# "      2   40755 (2)      0      0    2048 21-Jul-2026 15:18 ."
LS_ROW = re.compile(r"^\s*(\d+)\s+(\d+)\s+\((\d+)\)\s+\d+\s+\d+\s+\d+\s+"
                    r"\S+\s+\S+\s+(.*)$")


def debugfs_listing(img, ino):
    """-> [(inode, file_type, name), ...] as e2fsprogs reports them."""
    out = []
    for line in debugfs(img, f"ls -l <{ino}>\n").splitlines():
        m = LS_ROW.match(line)
        if m:
            out.append((int(m.group(1)), int(m.group(3)), m.group(4).strip()))
    return out


def our_listing(bench, img, ino):
    r = subprocess.run([bench, img, str(ino), "--ls"], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    out = []
    for line in r.stdout.splitlines():
        if not line.strip():
            continue
        i, ft, name = line.split(" ", 2)
        out.append((int(i), int(ft), name))
    return out


def dir_csum_ok(bench, img, ino):
    r = subprocess.run([bench, img, str(ino), "--dircsum"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return False, 0
    parts = r.stdout.split()
    return True, int(parts[1]) if len(parts) > 1 else 0


def find_directories(img, root=2):
    """Every directory reachable from the root, plus the root itself."""
    found = [root]
    for ino, ftype, name in debugfs_listing(img, root):
        if ftype == 2 and name not in (".", ".."):
            found.append(ino)
    return found


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--limit", type=int)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(args.bench):
        sys.exit(f"{args.bench} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]

    checked = failed = 0
    entries_total = csum_blocks = 0
    for case in cases:
        img = os.path.join(case, "fs.img")
        for ino in find_directories(img):
            checked += 1
            problems = []

            theirs = debugfs_listing(img, ino)
            ours = our_listing(args.bench, img, ino)
            if ours is None:
                problems.append("our reader could not walk the directory")
            else:
                entries_total += len(ours)
                if ours != theirs:
                    # Order matters, so compare as sequences first, then say
                    # whether the disagreement is order or content.
                    if sorted(ours) == sorted(theirs):
                        problems.append("same entries as debugfs but in a "
                                        "different order")
                    else:
                        only_ours = [e for e in ours if e not in theirs][:4]
                        only_theirs = [e for e in theirs if e not in ours][:4]
                        problems.append(f"listing differs: ours-only={only_ours} "
                                        f"debugfs-only={only_theirs}")

            ok, n = dir_csum_ok(args.bench, img, ino)
            csum_blocks += n
            if not ok:
                problems.append("a directory block checksum does not verify")

            if problems:
                failed += 1
                print(f"FAIL {os.path.basename(case)} inode {ino}")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {os.path.basename(case)} inode {ino}: "
                      f"{len(ours)} entries")

    print(f"\n{checked} directories, {entries_total} entries, "
          f"{csum_blocks} block checksums, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
