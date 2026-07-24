#!/usr/bin/env python3
r"""
Proves the one refusal the extent writer makes is a clean one.

    ./fullcheck.py

EXTW_ERR_FULL is raised when a leaf fills and the index block below the root is
full too - the append path adds an empty sibling leaf but does not split that index
block, so the tree cannot gain the level it would need (see the writer header and
the memory note's "Later: splitting a full leaf"). It is real but very hard to
reach: the allocator asks for the block after the file's current end, so a file in
free space stays one extent. Only a container fragmented to a near-checkerboard - a
separate extent per block - gets a single file to the cap.

The claim under test is that when the append bails with EXTW_ERR_FULL the partial
write is *committed*, not abandoned: the blocks it placed are on disk and referenced
by the tree, and i_size, i_blocks and the free counts already agree with that, so
the filesystem it leaves behind checks out. This is the same "a short append is
committed" property the out-of-space path has, on a different bail.

Everything is judged by e2fsprogs and fuse2fs, never by our own reader:

  setup e2fsck  the fragmentation setup - built with our create/append/unlink, all
                sub-cap - must itself be e2fsck-clean before the target is touched
  rc            the fill must be refused with exactly EXTW_ERR_FULL, not run out of
                space (EXTW_ERR_NOSPACE) and not silently succeed
  fill e2fsck   after the refused append the image must still be e2fsck-clean: the
                partially written file, its size and block counts, and the free
                counts all consistent. This is the check the mutation breaks.
  structure     debugfs must show the tree at exactly the cap: (bs-16)/12 leaves,
                each with (bs-16)/12 extents, under one full index block below the
                root - proof it is the structural limit that was hit, not an
                accident of running short
  size          i_size equals the blocks that landed, cross-read by debugfs
  fuse2fs       another ext4 driver mounts the post-refusal image read-write, writes
                to it, and e2fsck stays clean - a second opinion that the state is
                not merely clean to the tool that made it
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402
from interopcheck import mount_fuse, unmount_fuse       # noqa: E402

EXTW_ERR_FULL = -5
EXTW_ERR_NOSPACE = -3
FEATURES = "^has_journal,^dir_index"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def fsck(img):
    """-> (rc, stripped output). rc 0 is clean; the optimize remarks stay rc 0."""
    r = sh("e2fsck", "-fn", img)
    return r.returncode, (r.stdout + r.stderr).strip()


def parse_kv(text):
    out = {}
    for tok in text.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k] = v
    return out


def debugfs_size(img, ino):
    text = debugfs(img, f"stat <{ino}>\n")
    m = re.search(r"Size:\s*(\d+)", text)
    return int(m.group(1)) if m else None


def run(img, mkfs_opts, per, block_size, fullwrite, problems):
    # An ext4 the container feature set matches, made by mke2fs so the filesystem
    # the writer is judged on is not one we formatted. 1 KiB is the lowest cap.
    r = sh("mkfs.ext4", "-q", "-F", "-O", FEATURES, "-b", str(block_size),
           "-I", "256", *mkfs_opts, img)
    if r.returncode != 0:
        problems.append(f"could not format the image: {r.stderr.strip()[:200]}")
        return
    rc, _ = fsck(img)
    if rc != 0:
        problems.append(f"a freshly formatted image is not e2fsck-clean (rc={rc})")
        return

    # Build the checkerboard and an empty target, then prove the setup alone is
    # clean - a break in the FULL commit must not be able to hide as a setup fault.
    s = sh(fullwrite, img, "setup", str(per))
    if s.returncode != 0:
        problems.append(f"setup failed: {s.stderr.strip()[:300]}")
        return
    rc, out = fsck(img)
    if rc != 0:
        problems.append(f"the fragmentation setup is not e2fsck-clean (rc={rc})\n{out[:400]}")
        return

    # The append that must be refused.
    f = sh(fullwrite, img, "fill")
    if f.returncode != 0:
        problems.append(f"fill failed to run: {f.stderr.strip()[:300]}")
        return
    kv = parse_kv(f.stdout)
    try:
        appended = int(kv["appended"])
        arc = int(kv["rc"])
        tino = int(kv["target_inode"])
    except (KeyError, ValueError):
        problems.append(f"fill did not report its result: {f.stdout.strip()[:200]}")
        return

    # Exactly EXTW_ERR_FULL. NOSPACE would mean the free pool was too small and the
    # cap was never reached; success would mean the edge does not exist.
    if arc != EXTW_ERR_FULL:
        if arc == EXTW_ERR_NOSPACE:
            problems.append("the target ran out of space before reaching the cap - "
                            "the free pool is too small, raise per")
        else:
            problems.append(f"the append returned {arc}, expected EXTW_ERR_FULL "
                            f"({EXTW_ERR_FULL})")
        return
    if appended <= 0:
        problems.append("EXTW_ERR_FULL but nothing was committed - a refusal that "
                        "wrote nothing is not the partial-commit case")

    # The core proof: after the refused append the image is still clean.
    rc, out = fsck(img)
    if rc != 0:
        problems.append(f"e2fsck is not clean after the refused append (rc={rc}) - "
                        f"the partial write was not committed consistently\n{out[:600]}")
        return

    # The tree is at exactly the structural cap, so it was the limit that was hit.
    per_block = (block_size - 16) // 12
    cap = per_block * per_block
    rows = parse_extents(debugfs(img, f"dump_extents <{tino}>\n"))
    leaves = [e for e in rows if not e["is_index"]]
    index_rows = [e for e in rows if e["is_index"]]
    if len(leaves) != cap:
        problems.append(f"the tree has {len(leaves)} extents, expected the cap {cap} "
                        f"({per_block} x {per_block}) - the wrong thing was hit")
    # One index block below the root plus (cap / per_block) leaves = the depth-2
    # tree topped out at a full index block. Each index row is one child block.
    want_tree_blocks = 1 + cap // per_block
    if len(index_rows) != want_tree_blocks:
        problems.append(f"the tree has {len(index_rows)} index entries, expected "
                        f"{want_tree_blocks} (one index block below the root, "
                        f"{cap // per_block} leaves)")

    size = debugfs_size(img, tino)
    if size != appended * block_size:
        problems.append(f"i_size is {size}, expected {appended * block_size} "
                        f"({appended} blocks committed)")

    # A second driver's opinion: it mounts the post-refusal image read-write, writes
    # to it, and e2fsck stays clean afterward.
    with tempfile.TemporaryDirectory() as mtmp:
        mnt = os.path.join(mtmp, "mnt")
        os.makedirs(mnt)
        proc = mount_fuse(img, mnt, rw=True)
        if not proc:
            problems.append("fuse2fs would not mount the image after the refused "
                            "append - the state is clean only to us")
        else:
            try:
                with open(os.path.join(mnt, "after-refusal.txt"), "w") as fh:
                    fh.write("written by another driver\n")
            except OSError as e:
                problems.append(f"fuse2fs mounted but could not write: {e}")
            sh("sync")
            unmount_fuse(mnt, proc)
            rc, out = fsck(img)
            if rc != 0:
                problems.append(f"e2fsck is not clean after fuse2fs wrote to the "
                                f"post-refusal image (rc={rc})\n{out[:400]}")

    if not problems:
        print(f"EXTW_ERR_FULL at {len(leaves)} extents (bs {block_size}): refused "
              f"cleanly, {appended} blocks committed, e2fsck and fuse2fs both clean")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--fullwrite", default=os.path.join(here, "fullwrite"))
    ap.add_argument("--bs", type=int, default=1024, help="block size (1024 is the "
                    "lowest cap and the cheapest to reach)")
    ap.add_argument("--per", type=int, default=3800,
                    help="blocks per comb filler; the freed pool is a bit over 2*per "
                    "and must exceed what the target consumes reaching the cap")
    ap.add_argument("--size", default="40M", help="image size")
    ap.add_argument("--inodes", type=int, default=20000)
    args = ap.parse_args()

    if not os.path.exists(args.fullwrite):
        sys.exit(f"{args.fullwrite} not found - build it first (build.sh)")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "fs.img")
        subprocess.run(["truncate", "-s", args.size, img], check=True)
        run(img, ["-N", str(args.inodes)], args.per, args.bs, args.fullwrite, problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
