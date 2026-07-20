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
import hashlib
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


def csum_ok(bench, img, inode):
    """Every extent block's stored crc32c must equal the one we compute."""
    r = subprocess.run([bench, img, str(inode), "--csum"], capture_output=True, text=True)
    if r.returncode != 0:
        return False, r.stdout.strip() or r.stderr.strip()
    return True, r.stdout.strip()


def content_sha(bench, img, inode):
    """Hash of the file as our reader produces it, chunked so a large file does
    not have to be held in memory twice."""
    p = subprocess.Popen([bench, img, str(inode), "--read"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    h = hashlib.sha256()
    total = 0
    while True:
        chunk = p.stdout.read(1 << 20)
        if not chunk:
            break
        h.update(chunk)
        total += len(chunk)
    err = p.stderr.read().decode(errors="replace").strip()
    if p.wait() != 0:
        return None, 0, err or "reader exited non-zero"
    return h.hexdigest(), total, None


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

            ok, detail = csum_ok(args.bench, img, f["inode"])
            if not ok:
                failed += 1
                by_depth[depth][1] += 1
                print(f"FAIL {case}/{f['name']}: extent block checksum mismatch ({detail})")
                continue

            sha, nbytes, cerr = content_sha(args.bench, img, f["inode"])
            if cerr is not None:
                failed += 1
                by_depth[depth][1] += 1
                print(f"FAIL {case}/{f['name']}: reading content: {cerr}")
                continue
            if sha != f["sha256_ondisk"] or nbytes != f["size_ondisk"]:
                failed += 1
                by_depth[depth][1] += 1
                print(f"FAIL {case}/{f['name']}  content mismatch "
                      f"(profile={truth['profile']} bsize={truth['block_size']} depth={depth})")
                print(f"     expected {f['size_ondisk']} bytes {f['sha256_ondisk'][:16]}")
                print(f"     got      {nbytes} bytes {(sha or '-')[:16]}")
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

    print(f"\nchecked {checked} files (extent map + content + checksums), {failed} failed")
    for d in sorted(by_depth):
        total, bad = by_depth[d]
        print(f"  depth {d}: {total - bad}/{total} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
