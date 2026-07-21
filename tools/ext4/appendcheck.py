#!/usr/bin/env python3
r"""
The harness for the extent writer.

This is the first one that can demand a completely clean e2fsck. The allocator on
its own could not: a block taken from the bitmap and attached to nothing is an
orphan, so fsckcheck.py had to gate an expected residual. Once the block is
reachable from an inode it is just a file getting longer, and any complaint at all
is a real one.

    ./appendcheck.py --cases /tmp/cases

Five checks per file, each answering something the others cannot:

  fsck        output must be byte-identical to the same image's pristine run.
              Not "clean" - 17 of the 40 cases already carry an mke2fs remark
              about an extent tree that could be narrower.
  debugfs     the extent map after the append, read by e2fsprogs rather than by
              our own reader, which would otherwise be marking its own homework
  content     the bytes before the append must be untouched, and the appended
              region must match the pattern for its logical block number - which
              catches two logical blocks pointed at one physical block, something
              a size check and a checksum both sail past
  inode       i_size and i_blocks, cross-checked against debugfs stat
  checksums   the inode's own checksum covers the extent root, so a correct tree
              with a stale checksum is caught here rather than at mount time

Only files whose tree is depth 0 are used, because that is all the writer handles
so far. A file whose root already holds four extents is reported as skipped rather
than failed: refusing to split the root is deliberate, not a defect.
"""

import argparse
import glob
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs      # noqa: E402


import re

# "fs.img: 616/12288 files (0.8% non-contiguous), 7514/24576 blocks"
SUMMARY = re.compile(r"^.*: \d+/\d+ files \([\d.]+% non-contiguous\), "
                     r"(\d+)/(\d+) blocks$")


def fsck(img):
    """-> (rc, lines without the summary, blocks-in-use from the summary)

    The summary line is pulled out rather than compared, because it is the one
    line that legitimately changes when a file grows. Its block count is then
    worth more as a check than as noise: e2fsck counts blocks in use from the
    inodes it can reach, so it moving by exactly the number appended is an
    independent confirmation that the new blocks are attached to the file and not
    merely marked in the bitmap.
    """
    r = subprocess.run(["e2fsck", "-fn", img], capture_output=True, text=True)
    lines, used = [], None
    for line in r.stdout.splitlines():
        m = SUMMARY.match(line)
        if m:
            used = int(m.group(1))
        else:
            lines.append(line)
    return r.returncode, lines, used


def bench_map(bench, img, ino):
    r = subprocess.run([bench, img, str(ino)], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    runs = []
    for line in r.stdout.split("\n"):
        if line.strip():
            lo, phys, length, uninit = line.split()
            runs.append((int(lo), int(phys), int(length), int(uninit)))
    return sorted(runs)


def debugfs_map(img, ino):
    """The same map, according to e2fsprogs."""
    rows = parse_extents(debugfs(img, f"dump_extents <{ino}>\n"))
    return sorted((e["logical_start"], e["physical_start"],
                   e["length"], 1 if "Uninit" in e["flags"] else 0)
                  for e in rows if not e["is_index"])


def bench_read(bench, img, ino):
    r = subprocess.run([bench, img, str(ino), "--read"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def bench_csum_ok(bench, img, ino):
    r = subprocess.run([bench, img, str(ino), "--csum"], capture_output=True, text=True)
    return r.returncode == 0, r.stdout.strip()


def debugfs_stat(img, ino):
    """-> (size, blocks) as e2fsprogs reports them."""
    text = debugfs(img, f"stat <{ino}>\n")
    size = blocks = None
    for tok in text.split():
        pass
    import re
    m = re.search(r"Size:\s*(\d+)", text)
    if m:
        size = int(m.group(1))
    # "Blockcount" on some builds, the sector count either way.
    m = re.search(r"Blockcount:\s*(\d+)", text)
    if m:
        blocks = int(m.group(1))
    return size, blocks


def expected_pattern(logical, block_size):
    """Must mirror fill_pattern() in extwrite.c exactly."""
    buf = bytearray(block_size)
    for k in range(0, block_size - 7, 8):
        struct.pack_into("<II", buf, k, logical, k // 8)
    return bytes(buf)


def check_file(case, truth, f, img, bench, extwrite, fsmeta, count):
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
        return ["could not read the file before appending"], False

    # Where the append will land: the later of the last extent's end and the
    # block-rounded size, which is what the writer computes too.
    mapped_end = max((lo + ln for lo, _, ln, _ in before_map), default=0)
    start = max(mapped_end, (before_size + bs - 1) // bs)

    r = subprocess.run([extwrite, img, str(ino), "append", str(count)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        err = r.stderr.strip()
        if "root is full" in err:
            return [], True         # deliberate refusal, not a failure
        return [f"append failed: {err}"], False

    rc, lines, used = fsck(img)
    if rc != base_rc or lines != base_lines:
        new = [l for l in lines if l not in base_lines]
        problems.append(f"fsck changed (rc {base_rc} -> {rc}): "
                        f"{new[:4] if new else 'output differs'}")
    if base_used is not None and used is not None and used - base_used != count:
        problems.append(f"e2fsck counts {used - base_used} more blocks in use, "
                        f"expected {count}")

    ours = bench_map(bench, img, ino)
    theirs = debugfs_map(img, ino)
    if ours is None:
        problems.append("our reader could not walk the tree after the append")
    elif ours != theirs:
        problems.append(f"extent map disagrees with debugfs: ours={ours[:4]} "
                        f"debugfs={theirs[:4]}")

    # The tree has to be left in merged form. Two adjacent extents that agree in
    # logical order, physical order and flags should have been one entry, and a
    # writer that does not merge burns through a root that holds only four - which
    # is a silent loss of capacity rather than a wrong answer, so nothing else
    # here would notice. No pristine file in the corpus contains such a pair.
    for a, b in zip(theirs, theirs[1:]):
        if a[0] + a[2] == b[0] and a[1] + a[2] == b[1] and a[3] == b[3]:
            problems.append(f"extents {a} and {b} are adjacent and should have "
                            f"been merged into one")
            break

    ok, detail = bench_csum_ok(bench, img, ino)
    if not ok:
        problems.append(f"checksums do not verify after the append ({detail})")

    after_size, after_blocks = debugfs_stat(img, ino)
    want_size = (start + count) * bs
    if after_size != want_size:
        problems.append(f"i_size is {after_size}, expected {want_size}")
    if before_blocks is not None and after_blocks is not None:
        want_blocks = before_blocks + count * (bs // 512)
        if after_blocks != want_blocks:
            problems.append(f"i_blocks is {after_blocks}, expected {want_blocks}")

    after_data = bench_read(bench, img, ino)
    if after_data is None:
        problems.append("could not read the file back after appending")
    else:
        if after_data[:len(before_data)] != before_data:
            problems.append("the bytes that were already there changed")
        for i in range(count):
            logical = start + i
            got = after_data[logical * bs:(logical + 1) * bs]
            if got != expected_pattern(logical, bs):
                problems.append(f"appended block {logical} does not read back "
                                f"as the pattern that was written")
                break

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
    ap.add_argument("--count", type=int, default=3, help="blocks to append")
    ap.add_argument("--limit", type=int)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for tool in (args.bench, args.extwrite, args.fsmeta):
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]
    if not cases:
        sys.exit(f"no cases under {args.cases}")

    checked = failed = skipped = 0
    for case in cases:
        truth = json.load(open(os.path.join(case, "truth.json")))
        for f in truth["files"]:
            if f["max_depth"] != 0:
                continue          # the writer refuses deeper trees, by design
            with tempfile.TemporaryDirectory() as tmp:
                img = os.path.join(tmp, "fs.img")
                shutil.copy(os.path.join(case, "fs.img"), img)
                problems, was_skipped = check_file(case, truth, f, img, args.bench,
                                                   args.extwrite, args.fsmeta,
                                                   args.count)
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

    print(f"\nappended {args.count} blocks to {checked} depth-0 files, {failed} failed"
          + (f", {skipped} skipped with a full root" if skipped else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
