#!/usr/bin/env python3
r"""
The harness for setting a file's exact byte length.

    ./sizecheck.py --cases /tmp/cases

The append path can only make a file a whole number of blocks long, because it
maps blocks and knows nothing about bytes. Every real file ends somewhere inside
its last block, so importing one is: create it, append ceil(size / bs) blocks,
then trim the length to the byte. ext4_set_size is that trim, and this checks it.

Two things have to be true and are easy to get half-right:

  the length      i_size reads back as exactly what was asked, our reader returns
                  exactly that many bytes, and so does another driver - fuse2fs -
                  reading the same file. A reader that stopped at a block boundary
                  would pass its own check and disagree with everyone else.
  the last bytes  the tail of the last block, the part below i_size, still holds
                  the data that was written into it. Trimming the length must not
                  disturb the block, and the block's bytes up to i_size must match
                  what was appended. This is what tells an exact length apart from
                  a size field that merely reads back.

The refusals are the other half. e2fsck was measured, not assumed: for a file
whose last mapped block is L, it accepts any i_size from L * bs upward and rejects
anything below (`i_size is X, should be Y`). set_size is deliberately stricter -
it also refuses a size past the last block, because reaching past the blocks that
exist is making a trailing hole, a different operation. Both refusals are checked,
and against e2fsck's own boundary.
"""

import argparse
import glob
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, BENIGN_REMARK                       # noqa: E402
from interopcheck import mount_fuse, unmount_fuse                 # noqa: E402

WHEN = 1784639915


def run(tool, *args):
    r = subprocess.run([tool, *[str(a) for a in args]], capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def our_read(bench, img, ino):
    r = subprocess.run([bench, img, str(ino), "--read"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def inode_size(img, ino):
    """i_size straight off the disk, so the harness does not learn the length from
    the same code it is checking."""
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]
        u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
        bs = 1024 << u32(sb, 0x18)
        ipg, isize, first = u32(sb, 0x28), u16(sb, 0x58), u32(sb, 0x14)
        dsz = u16(sb, 0xFE) if (u32(sb, 0x60) & 0x80) else 32
        g, idx = (ino - 1) // ipg, (ino - 1) % ipg
        f.seek((first + 1) * bs + g * dsz)
        d = f.read(dsz)
        itable = u32(d, 8) | (u32(d, 40) << 32 if dsz >= 64 else 0)
        f.seek(itable * bs + idx * isize)
        inode = f.read(isize)
    return u32(inode, 0x04) | (u32(inode, 0x6C) << 32)


def block_size(img):
    with open(img, "rb") as f:
        f.seek(1024 + 0x18)
        return 1024 << struct.unpack("<I", f.read(4))[0]


def fsck_clean(img, base_lines, problems, when):
    rc, lines, _ = fsck(img)
    if rc != 0:
        problems.append(f"e2fsck rc={rc} {when}")
    for line in lines:
        if line in base_lines or BENIGN_REMARK.match(line):
            continue
        if line.startswith(("Pass ", "e2fsck ")):
            continue
        problems.append(f"e2fsck says {when}: {line}")


def check_file(img, dir_ino, tools, sizes):
    bench, dirwrite, extwrite, mkfs = tools
    problems = []
    bs = block_size(img)

    base_rc, base_lines, _ = fsck(img)
    if base_rc != 0:
        return [f"pristine image is not fsck-clean (rc={base_rc})"]

    for want in sizes:
        nblocks = max(1, (want + bs - 1) // bs)
        rc, out, err = run(dirwrite, img, dir_ino, "create", f"f-{want}.bin", WHEN)
        if rc != 0:
            problems.append(f"create for size {want} failed: {err}")
            continue
        ino = int(out)

        rc, got, err = run(extwrite, img, ino, "append", nblocks)
        if rc not in (0, 3) or int(got) != nblocks:
            problems.append(f"append {nblocks} for size {want} failed: {err}")
            continue

        # The appended blocks carry extwrite's per-offset pattern. Capturing it now,
        # while the file is still block-aligned, is what lets the last partial block
        # be checked against known bytes after the trim.
        full = our_read(bench, img, ino)
        if full is None or len(full) != nblocks * bs:
            problems.append(f"size {want}: the block-aligned read was "
                            f"{None if full is None else len(full)}, "
                            f"expected {nblocks * bs}")
            continue

        rc, _, err = run(extwrite, img, ino, "setsize", want)
        if rc != 0:
            problems.append(f"setsize {want} failed: {err}")
            continue

        # e2fsck stays clean, and clean means nothing new - the corpus already
        # carries the narrower-tree remark on some files.
        fsck_clean(img, base_lines, problems, f"after setsize {want}")

        # The length, three ways: the raw field, our reader, and the bytes.
        disk = inode_size(img, ino)
        if disk != want:
            problems.append(f"i_size on disk is {disk}, asked for {want}")
        body = our_read(bench, img, ino)
        if body is None or len(body) != want:
            problems.append(f"our reader returned "
                            f"{None if body is None else len(body)} bytes, "
                            f"asked for {want}")
        elif body != full[:want]:
            # The heart of it: the last partial block still holds its data, and the
            # length simply cut it off - not a zeroed or disturbed tail.
            at = next((i for i in range(want) if body[i] != full[i]), want)
            problems.append(f"size {want}: bytes differ from the appended data at "
                            f"offset {at}")

    # The two boundaries, against e2fsck's own. One file, three blocks, so the last
    # mapped block is block 2 and e2fsck's floor is 2 * bs.
    rc, out, _ = run(dirwrite, img, dir_ino, "create", "edge.bin", WHEN)
    if rc == 0:
        ino = int(out)
        run(extwrite, img, ino, "append", 3)
        floor, top = 2 * bs, 3 * bs
        # Accepted: exactly the floor, and exactly the top.
        for ok in (floor, top, floor + 1, top - 1):
            rc, _, _ = run(extwrite, img, ino, "setsize", ok)
            if rc != 0:
                problems.append(f"setsize {ok} was refused but is in the last block")
            fsck_clean(img, base_lines, problems, f"after boundary setsize {ok}")
        # Refused: one below the floor (e2fsck would reject it too) and one past
        # the top (a trailing hole, which this does not do).
        for bad in (floor - 1, top + 1):
            rc, _, _ = run(extwrite, img, ino, "setsize", bad)
            if rc == 0:
                problems.append(f"setsize {bad} was accepted but is outside the "
                                f"last block")

    return problems


def find_dirs(img):
    from dircheck import find_directories
    return find_directories(img)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--extwrite", default=os.path.join(here, "extwrite"))
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--limit", type=int)
    ap.add_argument("--no-interop", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.bench, args.dirwrite, args.extwrite, args.mkfs)
    for t in tools:
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]

    # Sizes chosen for where they land in a block: one byte, one below a boundary,
    # exactly on it, one past it, and a few blocks in. The block-boundary sizes are
    # the ones an off-by-one in the range check would let through or refuse.
    sizes = [1, 100, 1023, 1024, 1025, 2047, 2048, 3000]

    checked = failed = 0
    for case in cases:
        src = os.path.join(case, "fs.img")
        dir_ino = find_dirs(src)[0]
        with tempfile.TemporaryDirectory() as tmp:
            img = os.path.join(tmp, "fs.img")
            shutil.copy(src, img)
            problems = check_file(img, dir_ino, tools, sizes)
        checked += 1
        if problems:
            failed += 1
            print(f"FAIL {os.path.basename(case)}")
            for p in problems:
                print(f"     {p}")
        elif args.verbose:
            print(f"ok   {os.path.basename(case)}")

    if not args.no_interop:
        problems = check_interop(tools)
        if problems:
            failed += 1
            print("FAIL an exact-length file read by fuse2fs")
            for p in problems:
                print(f"     {p}")
        elif args.verbose:
            print("ok   an exact-length file read by fuse2fs")

    print(f"\n{checked} images, {len(sizes)} exact sizes each, {failed} failed")
    return 1 if failed else 0


def check_interop(tools):
    """A file of an exact, non-block length, measured by another driver.

    Our reader stopping at i_size is only half the claim; the length is only real
    if the driver a user takes the container to agrees. fuse2fs reports the file's
    size and its content, and both have to be exact.
    """
    bench, dirwrite, extwrite, mkfs = tools
    problems = []
    want = 5000
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "one.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)
        subprocess.run(["truncate", "-s", "16M", img], capture_output=True)
        if subprocess.run([mkfs, img, "--bs", "1024"], capture_output=True).returncode:
            return ["could not format an image"]

        rc, out, err = run(dirwrite, img, 2, "create", "exact.bin", WHEN)
        if rc != 0:
            return [f"create failed: {err}"]
        ino = int(out)
        bs = block_size(img)
        run(extwrite, img, ino, "append", (want + bs - 1) // bs)
        rc, _, err = run(extwrite, img, ino, "setsize", want)
        if rc != 0:
            return [f"setsize failed: {err}"]

        proc = mount_fuse(img, mnt, rw=False)
        if not proc:
            return ["fuse2fs would not mount a filesystem with an exact-length file"]
        try:
            path = os.path.join(mnt, "exact.bin")
            size = os.path.getsize(path)
            content = open(path, "rb").read()
        finally:
            unmount_fuse(mnt, proc)

        if size != want:
            problems.append(f"fuse2fs reports {size} bytes, we set {want}")
        if len(content) != want:
            problems.append(f"fuse2fs read {len(content)} bytes, we set {want}")
        our = our_read(bench, img, ino)
        if our is not None and our != content:
            problems.append("our reader and fuse2fs disagree on the file's bytes")
    return problems


if __name__ == "__main__":
    sys.exit(main())
