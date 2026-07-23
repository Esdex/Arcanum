#!/usr/bin/env python3
r"""
The harness for a file streamed in as chunks - the write path the JNI bridge uses
for import, and where a real device bug lived: a file over one chunk was written
whole and then rolled back, because every append past the first chunk was refused.

    ./chunkcheck.py

chunkwrite drives the same composition ext4jni_write_file does - chunk 0 creates
the file, every later chunk appends at its current end - feeding a byte pattern
that depends on each byte's absolute position. So a chunk that lands at the wrong
offset is caught, not just a wrong total length; that is exactly the failure the
fix addressed (the fill was indexing by the file-absolute block, not the chunk's).

The oracle is another driver, not our own reader: the file is read back through
fuse2fs and every byte compared to the pattern, and e2fsck must be clean. The
sizes are chosen for their boundaries - a sub-block file, one exactly a chunk
(block-aligned end, the append path's happy case), one a chunk plus a few bytes
(a partial last block after a full one), and several chunks (the device case).
"""

import os
import subprocess
import sys
import tempfile
import time


def sh(*a, **k):
    return subprocess.run(a, capture_output=True, text=True, **k)


def pattern(i):
    return (i ^ (i >> 8) ^ (i >> 16)) & 0xFF


CHUNK = 1024 * 1024

# (name, total bytes) - boundaries around the 1 MiB chunk and the block size.
CASES = [
    ("sub-block.bin", 500),
    ("one-block.bin", 4096),
    ("one-chunk.bin", CHUNK),
    ("chunk-plus.bin", CHUNK + 123),
    ("two-chunks.bin", 2 * CHUNK),
    ("device-photo.jpg", 2500000),
    ("empty.bin", 0),
]


def check_geometry(tools, block_size, problems):
    mkfs, chunkwrite, bench = tools
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "cw.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)
        sh("truncate", "-s", "48M", img)
        if sh(mkfs, img, "--bs", str(block_size)).returncode:
            problems.append(f"mkfs at block size {block_size} failed")
            return

        for name, total in CASES:
            r = sh(chunkwrite, img, "/" + name, str(total), str(CHUNK))
            if r.returncode != 0:
                problems.append(f"[{block_size}] chunkwrite {name}: {r.stderr.strip()}")

        rc = sh("e2fsck", "-fn", img)
        if rc.returncode != 0:
            problems.append(f"[{block_size}] e2fsck rejects the image (rc={rc.returncode})")

        # Read back through another driver and compare every byte to the pattern.
        proc = subprocess.Popen(["fuse2fs", img, mnt, "-o", "ro", "-f"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        mounted = False
        for _ in range(60):
            if os.path.ismount(mnt):
                mounted = True
                break
            if proc.poll() is not None:
                break
            time.sleep(0.1)
        if not mounted:
            problems.append(f"[{block_size}] fuse2fs would not mount")
            proc.kill()
            return
        try:
            for name, total in CASES:
                data = open(os.path.join(mnt, name), "rb").read()
                if len(data) != total:
                    problems.append(f"[{block_size}] {name}: length {len(data)}, "
                                    f"expected {total}")
                    continue
                bad = next((i for i in range(total) if data[i] != pattern(i)), None)
                if bad is not None:
                    problems.append(f"[{block_size}] {name}: byte {bad} is "
                                    f"{data[bad]}, expected {pattern(bad)} - a chunk "
                                    f"landed at the wrong offset")
        finally:
            sh("fusermount", "-u", mnt)
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    import argparse
    import shutil
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--chunkwrite", default=os.path.join(here, "chunkwrite"))
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.mkfs, args.chunkwrite, args.bench)
    for t in tools:
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    if not shutil.which("fuse2fs"):
        sys.exit("fuse2fs not found - it is the independent oracle here")

    problems = []
    # 1024 and 4096: the device uses 4096 at 1 GiB (chunk is 256 blocks) and 1024
    # at smaller sizes (chunk is 1024 blocks), and the two exercise different
    # extent-tree depths for the same chunk.
    for bs in (1024, 4096):
        check_geometry(tools, bs, problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print(f"{len(CASES)} files streamed in 1 MiB chunks at two block sizes, every "
          f"byte read back through fuse2fs matches its position, e2fsck clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
