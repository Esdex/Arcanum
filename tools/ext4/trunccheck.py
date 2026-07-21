#!/usr/bin/env python3
r"""
The harness for shrinking a file.

Freeing is the direction that fails quietly. An extent dropped without releasing
its blocks leaks them - nothing complains, the space is simply gone, and no
checksum or structural check notices. Blocks released while still referenced is
the opposite failure and a worse one: the next allocation hands them to a second
file. Only e2fsck sees either, the first as a bitmap difference and the second as
multiply-claimed blocks, so it runs after every cut.

    ./trunccheck.py --cases /tmp/cases --mode half
    ./trunccheck.py --cases /tmp/cases --mode zero
    ./trunccheck.py --cases /tmp/cases --mode roundtrip

  half       cut to half the mapped blocks: exercises trimming an extent that
             straddles the cut, which is the only entry that is rewritten rather
             than kept or dropped
  zero       empty the file: every block goes back and the root must return to
             depth 0, since a depth left behind describes index entries that are
             no longer there
  roundtrip  append, then cut back to where it started. The leaf extent map has
             to come back exactly - a block appended by extending the last extent
             is undone by trimming it, and one appended as a new entry is undone
             by dropping it, so anything left over is a mistake in one direction
             or the other.

The round trip cannot restore i_size when the file did not end on a block
boundary, because truncation works in whole blocks. So content is compared over
the original length rather than the rounded one, and that is a limit of the
operation rather than of the check.
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import (fsck, line_delta, BENIGN_REMARK, bench_map, debugfs_map,
                         bench_read, bench_csum_ok, debugfs_stat)      # noqa: E402


def run_tool(tool, img, ino, verb, n):
    r = subprocess.run([tool, img, str(ino), verb, str(n)],
                       capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def compare_fsck(base_rc, base_lines, img, problems):
    rc, lines, used = fsck(img)
    if rc != base_rc:
        problems.append(f"fsck return code changed: {base_rc} -> {rc}")
    new, gone = line_delta(base_lines, lines)
    appeared = [l for l in new  if not BENIGN_REMARK.match(l)]
    vanished = [l for l in gone if not BENIGN_REMARK.match(l)]
    if appeared:
        problems.append(f"fsck now complains: {appeared[:4]}")
    if vanished:
        problems.append(f"fsck stopped saying: {vanished[:4]}")
    return used


def check_file(truth, f, img, tools, mode, count):
    bench, extwrite, fsmeta = tools
    ino = f["inode"]
    bs = truth["block_size"]
    problems = []

    base_rc, base_lines, base_used = fsck(img)
    if base_rc != 0:
        return [f"pristine image is not fsck-clean (rc={base_rc})"], False

    before_map = bench_map(bench, img, ino)
    before_data = bench_read(bench, img, ino)
    before_size, before_blocks = debugfs_stat(img, ino)
    if before_map is None or before_data is None:
        return ["could not read the file before cutting"], False
    mapped_end = max((lo + ln for lo, _, ln, _ in before_map), default=0)

    if mode == "roundtrip":
        start = max(mapped_end, (before_size + bs - 1) // bs)
        rc, out, err = run_tool(extwrite, img, ino, "append", count)
        if rc not in (0, 3):
            if "would have to be split" in err:
                return [], True
            return [f"append failed: {err}"], False
        keep = start          # back to exactly where the file ended
    elif mode == "zero":
        keep = 0
    else:
        # A one-block file halves to zero, which is a real cut and worth making
        # rather than skipping.
        keep = mapped_end // 2

    rc, out, err = run_tool(extwrite, img, ino, "truncate", keep)
    if rc != 0:
        return [f"truncate failed: {err}"], False

    used = compare_fsck(base_rc, base_lines, img, problems)

    ours = bench_map(bench, img, ino)
    theirs = debugfs_map(img, ino)
    if ours is None:
        problems.append("our reader could not walk the tree after the cut")
    elif ours != theirs:
        problems.append(f"extent map disagrees with debugfs: ours={ours[:3]} "
                        f"debugfs={theirs[:3]}")

    # Nothing may survive past the cut, in either reading of the tree.
    past = [e for e in theirs if e[0] >= keep]
    if past:
        problems.append(f"extents past the cut at {keep} survived: {past[:3]}")
    over = [e for e in theirs if e[0] < keep < e[0] + e[2]]
    if over:
        problems.append(f"an extent still straddles the cut at {keep}: {over[:3]}")

    ok, detail = bench_csum_ok(bench, img, ino)
    if not ok:
        problems.append(f"checksums do not verify after the cut ({detail})")

    after_size, after_blocks = debugfs_stat(img, ino)
    want_size = min(before_size, keep * bs) if mode != "roundtrip" else None
    if mode == "roundtrip":
        want_size = min((max(mapped_end, (before_size + bs - 1) // bs) + count) * bs,
                        keep * bs)
    if after_size != want_size:
        problems.append(f"i_size is {after_size}, expected {want_size}")

    after_data = bench_read(bench, img, ino)
    if after_data is None:
        problems.append("could not read the file back after the cut")
    else:
        keep_bytes = min(len(before_data), len(after_data))
        if after_data[:keep_bytes] != before_data[:keep_bytes]:
            problems.append("the bytes that survived the cut are not the ones "
                            "that were there before")
        if mode == "zero" and after_data:
            problems.append(f"file still reads {len(after_data)} bytes after "
                            f"being emptied")

    if mode == "roundtrip":
        # The map has to come back exactly: extending the last extent is undone
        # by trimming it, adding an entry is undone by dropping it.
        if ours is not None and ours != before_map:
            problems.append(f"round trip did not restore the extent map: "
                            f"was {before_map[:3]} now {ours[:3]}")
        if before_size <= len(after_data or b"") and after_data is not None:
            if after_data[:before_size] != before_data[:before_size]:
                problems.append("round trip did not restore the file's contents")

    m = subprocess.run([fsmeta, img], capture_output=True, text=True)
    if m.returncode != 0:
        problems.append(f"filesystem checksums no longer verify: {m.stdout.strip()}")

    return problems, False


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--extwrite", default=os.path.join(here, "extwrite"))
    ap.add_argument("--fsmeta", default=os.path.join(here, "fsmeta"))
    ap.add_argument("--mode", choices=["half", "zero", "roundtrip"], default="half")
    ap.add_argument("--count", type=int, default=5,
                    help="blocks to append first, in roundtrip mode")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--max-skipped", type=int, default=0)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.bench, args.extwrite, args.fsmeta)
    for tool in tools:
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]

    checked = failed = skipped = 0
    for case in cases:
        truth = json.load(open(os.path.join(case, "truth.json")))
        for f in truth["files"]:
            with tempfile.TemporaryDirectory() as tmp:
                img = os.path.join(tmp, "fs.img")
                shutil.copy(os.path.join(case, "fs.img"), img)
                problems, was_skipped = check_file(truth, f, img, tools,
                                                   args.mode, args.count)
            if was_skipped:
                skipped += 1
                continue
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {os.path.basename(case)}/{f['name']} (inode {f['inode']})")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {os.path.basename(case)}/{f['name']}")

    print(f"\n{args.mode}: {checked} files, {failed} failed"
          + (f", {skipped} skipped" if skipped else ""))
    if skipped > args.max_skipped:
        print(f"     {skipped} files were refused, more than the "
              f"{args.max_skipped} allowed")
    return 1 if failed or skipped > args.max_skipped else 0


if __name__ == "__main__":
    sys.exit(main())
