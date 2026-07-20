#!/usr/bin/env python3
"""
Generates ext4 images plus ground truth for testing an extent-tree reader.

The point is that the truth comes from e2fsprogs rather than from us: mke2fs lays
the filesystem out, debugfs reports the extent tree it sees, and our reader has to
agree with that. A test suite we wrote ourselves would only prove the reader does
what we expected, not what ext4 does.

Everything runs unprivileged on image files - no loop mounts, no root.

Usage:
    ./genimages.py --out /tmp/ext4cases --count 40 --seed 1
    ./genimages.py --out /tmp/ext4cases --count 200 --seed 7 --keep-blobs

Each case directory holds:
    fs.img      the filesystem
    truth.json  per-file inode, size, sha256 and the extent tree from debugfs
"""

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile

# 1 KiB blocks reach a deeper tree far sooner: an extent block holds roughly
# (block_size - 12) / 12 entries, so ~84 at 1 KiB against ~340 at 4 KiB.
BLOCK_SIZES = [1024, 2048, 4096]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def debugfs(img, script, write=False):
    """Runs debugfs commands from a here-doc, returning stdout."""
    cmd = ["debugfs"] + (["-w"] if write else []) + ["-f", "/dev/stdin", img]
    r = subprocess.run(cmd, input=script, capture_output=True, text=True)
    return r.stdout


# " 1/ 2   3/ 83   128 -   191  6729 -  6792     64  Uninit"
# Index rows carry a single physical block and no range, hence the optional group.
EXTENT_ROW = re.compile(
    r"^\s*(\d+)/\s*(\d+)\s+(\d+)/\s*(\d+)\s+"      # level/depth, entry/total
    r"(\d+)\s*-\s*(\d+)\s+"                        # logical range
    r"(\d+)(?:\s*-\s*(\d+))?\s+"                   # physical, range only on leaves
    r"(\d+)\s*(\S+)?\s*$"                          # length, optional flags
)


def parse_extents(text):
    rows = []
    for line in text.splitlines():
        if line.startswith("Level") or not line.strip():
            continue
        m = EXTENT_ROW.match(line)
        if not m:
            continue
        lvl, depth, ent, total, lo, hi, pstart, pend, length, flags = m.groups()
        rows.append({
            "level": int(lvl), "depth": int(depth),
            "entry": int(ent), "entries": int(total),
            "logical_start": int(lo), "logical_end": int(hi),
            "physical_start": int(pstart),
            # An index row points at one block, so start == end there.
            "physical_end": int(pend) if pend else int(pstart),
            "length": int(length),
            "flags": flags or "",
            "is_index": pend is None,
        })
    return rows


def _has_hole(extents):
    """True when leaf extents leave a gap in logical numbering."""
    leaves = sorted((e for e in extents if not e["is_index"]),
                    key=lambda e: e["logical_start"])
    nxt = 0
    for e in leaves:
        if e["logical_start"] > nxt:
            return True
        nxt = e["logical_end"] + 1
    return False


def stat_file(img, name):
    out = debugfs(img, f"stat {name}\n")
    inode = re.search(r"Inode:\s+(\d+)", out)
    size = re.search(r"Size:\s+(\d+)", out)
    return (int(inode.group(1)) if inode else None,
            int(size.group(1)) if size else None)


def make_case(case_dir, blob_dir, rng, keep_blobs):
    os.makedirs(case_dir, exist_ok=True)
    img = os.path.join(case_dir, "fs.img")

    # Fragmentation profile. The tree only gets deep when the file has to weave
    # through many gaps, and gap size is what decides how many extents that takes:
    # 64 KiB fillers gave at most ~110 extents, which never leaves depth 1, while
    # 4 KiB fillers on a small volume reach depth 2 with over a thousand.
    profile = rng.choice(["clean", "light", "heavy", "shred"])
    if profile == "shred":
        block_size, size_mb  = 1024, 24
        chunk_kb, fillers    = 4, 3000
    elif profile == "heavy":
        block_size = rng.choice([1024, 2048])
        size_mb, chunk_kb, fillers = 48, rng.choice([8, 16]), 1200
    elif profile == "light":
        block_size = rng.choice(BLOCK_SIZES)
        size_mb, chunk_kb, fillers = rng.choice([48, 64, 96]), 64, rng.choice([200, 900])
    else:
        block_size = rng.choice(BLOCK_SIZES)
        size_mb, chunk_kb, fillers = rng.choice([64, 128]), 64, 0
    delete_nth = rng.choice([2, 3])

    if os.path.exists(img):
        os.remove(img)
    subprocess.run(["truncate", "-s", f"{size_mb}M", img], check=True)
    r = run(["mkfs.ext4", "-q", "-F", "-b", str(block_size), img])
    if r.returncode != 0:
        return None, f"mkfs failed: {r.stderr.strip()[:120]}"

    # Fill, then punch holes, so the file written afterwards has to weave through
    # the gaps instead of landing in one run.
    if fillers:
        chunk = os.path.join(blob_dir, f"chunk{chunk_kb}.bin")
        if not os.path.exists(chunk):
            with open(chunk, "wb") as f:
                f.write(os.urandom(chunk_kb * 1024))
        debugfs(img, "".join(f"write {chunk} pad{i}\n" for i in range(fillers)), write=True)
        debugfs(img, "".join(f"rm pad{i}\n" for i in range(0, fillers, delete_nth)), write=True)

    files = []
    shapes = [
        ("tiny",   rng.randint(1, 900)),
        ("small",  rng.randint(4_000, 60_000)),
        ("medium", rng.randint(300_000, 900_000)),
        ("large",  rng.randint(2_000_000, 5_000_000) if profile == "shred"
                    else rng.randint(2_000_000, 9_000_000)),
    ]
    for name, nbytes in shapes:
        blob = os.path.join(blob_dir, f"{name}.bin")
        data = os.urandom(nbytes)
        with open(blob, "wb") as f:
            f.write(data)
        debugfs(img, f"write {blob} {name}\n", write=True)

        # Two shapes a plain write never produces, and both were invisible to the
        # corpus until a mutation test showed the reader could ignore the
        # uninitialised bit entirely without a single case noticing:
        #   punch     - a hole, so logical numbering stops being contiguous
        #   fallocate - a preallocated run, flagged Uninit, which reads as zeroes
        blocks = max(1, nbytes // block_size)
        if name in ("medium", "large") and blocks > 40:
            hole_start = blocks // 4
            hole_end   = hole_start + max(1, blocks // 8)
            debugfs(img, f"punch {name} {hole_start} {hole_end}\n", write=True)
        if name in ("small", "medium"):
            fa_start = blocks + 16
            debugfs(img, f"fallocate {name} {fa_start} {fa_start + rng.randint(8, 400)}\n",
                    write=True)

        inode, size = stat_file(img, name)
        if inode is None:
            return None, f"could not stat {name} after writing it"
        extents = parse_extents(debugfs(img, f"dump_extents {name}\n"))
        files.append({
            "name": name,
            "inode": inode,
            "size": size,
            "sha256": hashlib.sha256(data).hexdigest(),
            "max_depth": max((e["depth"] for e in extents), default=0),
            "extent_count": sum(1 for e in extents if not e["is_index"]),
            "uninit_count": sum(1 for e in extents if "Uninit" in e["flags"]),
            "has_hole": _has_hole(extents),
            "extents": extents,
        })
        if not keep_blobs:
            os.remove(blob)

    # A case whose own filesystem does not check out would send us chasing a
    # phantom later, so it is rejected here rather than shipped as truth.
    fsck = run(["e2fsck", "-fn", img])
    if fsck.returncode not in (0,):
        return None, f"e2fsck rejected the generated image (rc={fsck.returncode})"

    truth = {
        "profile": profile,
        "block_size": block_size,
        "size_mb": size_mb,
        "chunk_kb": chunk_kb,
        "fillers": fillers,
        "delete_nth": delete_nth,
        "files": files,
    }
    with open(os.path.join(case_dir, "truth.json"), "w") as f:
        json.dump(truth, f, indent=2)
    return truth, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=20)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--keep-blobs", action="store_true",
                    help="keep the source files that were written into each image")
    args = ap.parse_args()

    for tool in ("mkfs.ext4", "debugfs", "e2fsck", "truncate"):
        if shutil.which(tool) is None:
            sys.exit(f"required tool not found: {tool}")

    rng = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    blob_dir = tempfile.mkdtemp(prefix="ext4blobs-")

    depths = {}
    uninit_files = holed_files = 0
    failures = []
    for i in range(args.count):
        case_dir = os.path.join(args.out, f"case-{i:03d}")
        truth, err = make_case(case_dir, blob_dir, rng, args.keep_blobs)
        if err:
            failures.append((case_dir, err))
            continue
        for f in truth["files"]:
            depths[f["max_depth"]] = depths.get(f["max_depth"], 0) + 1
            if f["uninit_count"]: uninit_files += 1
            if f["has_hole"]:     holed_files += 1

    if not args.keep_blobs:
        shutil.rmtree(blob_dir, ignore_errors=True)

    total = sum(depths.values())
    print(f"cases: {args.count - len(failures)}/{args.count}   files: {total}")
    for d in sorted(depths):
        print(f"  tree depth {d}: {depths[d]} files")
    print(f"  with uninitialised extents: {uninit_files} files")
    print(f"  with holes:                 {holed_files} files")
    for case_dir, err in failures:
        print(f"  FAILED {case_dir}: {err}")
    # Depth 0 alone would mean the corpus never exercises index blocks, which is
    # where the interesting logic lives.
    if not any(d > 0 for d in depths):
        print("WARNING: no case produced a tree deeper than the inode - "
              "raise --count or the filler counts")


if __name__ == "__main__":
    main()
