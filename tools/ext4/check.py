#!/usr/bin/env python3
"""
Runs the extent reader over every generated case and compares it with the truth
that debugfs produced.

Comparison is on the leaf extents only, in logical order: logical start, physical
start, length and the uninitialised flag. Index rows are the tree's shape, not its
meaning - two implementations may legitimately arrange them differently, but the
mapping they describe has to match exactly.

    ./check.py --cases /tmp/ext4cases
"""

import argparse
import json
import glob
import os
import subprocess
import sys


def truth_runs(file_entry):
    """Leaf extents from debugfs, in logical order."""
    runs = []
    for e in file_entry["extents"]:
        if e["is_index"]:
            continue
        runs.append((e["logical_start"], e["physical_start"], e["length"],
                     1 if "Uninit" in e["flags"] else 0))
    runs.sort()
    return runs


def reader_runs(bench, img, inode):
    r = subprocess.run([bench, img, str(inode)], capture_output=True, text=True)
    if r.returncode != 0:
        return None, r.stderr.strip()
    runs = []
    for line in r.stdout.split("\n"):
        if not line.strip():
            continue
        lo, phys, length, uninit = line.split()
        runs.append((int(lo), int(phys), int(length), int(uninit)))
    runs.sort()
    return runs, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(os.path.dirname(__file__), "bench"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(args.bench):
        sys.exit(f"bench binary not found at {args.bench} - build it first")

    checked = failed = 0
    by_depth = {}
    for truth_path in sorted(glob.glob(os.path.join(args.cases, "case-*", "truth.json"))):
        case = os.path.dirname(truth_path)
        img = os.path.join(case, "fs.img")
        truth = json.load(open(truth_path))

        for f in truth["files"]:
            expected = truth_runs(f)
            got, err = reader_runs(args.bench, img, f["inode"])
            checked += 1
            depth = f["max_depth"]
            by_depth.setdefault(depth, [0, 0])
            by_depth[depth][0] += 1

            if err is not None:
                failed += 1
                by_depth[depth][1] += 1
                print(f"FAIL {case}/{f['name']}: reader errored: {err}")
                continue

            if got != expected:
                failed += 1
                by_depth[depth][1] += 1
                print(f"FAIL {case}/{f['name']}  "
                      f"(profile={truth['profile']} bsize={truth['block_size']} depth={depth})")
                print(f"     expected {len(expected)} runs, got {len(got)}")
                for i, (e, g) in enumerate(zip(expected, got)):
                    if e != g:
                        print(f"     first difference at run {i}: expected {e}, got {g}")
                        break
            elif args.verbose:
                print(f"ok   {case}/{f['name']}  {len(expected)} runs, depth {depth}")

    print(f"\nchecked {checked} files, {failed} failed")
    for d in sorted(by_depth):
        total, bad = by_depth[d]
        print(f"  depth {d}: {total - bad}/{total} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
