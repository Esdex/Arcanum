#!/usr/bin/env python3
r"""
The harness for the positional write (ext4_write_at): writing bytes at an offset
without truncating - overwriting blocks the file already has, and appending past
its end - the write the append-only path could not do.

    ./writeatcheck.py

A file is laid down by another driver (fuse2fs, through a real mount) with a known
base pattern. Then writeat overwrites or extends a region with a second pattern,
and the whole file is read back through fuse2fs and matched, byte for byte, against
what it should now be: base bytes everywhere the write did not touch, the second
pattern inside the written region, the file grown to the write's end if it reached
past it. e2fsck must be clean after every write.

That byte-for-byte match is the point. The one thing ext4_write_at adds over the
append and set-size paths it is built from is the arithmetic that decides which
byte goes to which place - the in-block offset, the split between overwrite and
append, the exact final length - and a misplaced byte is caught here even when the
length is right. It is the same class of bug the chunked-write check exists for.

Holes are refused, and that is checked too: a write starting past the end, and a
write into an empty file at a non-zero offset, must both be turned away with the
image left byte-for-byte unchanged.

Two block sizes, because the same offsets fall in different blocks at each.
"""

import os
import shutil
import subprocess
import sys
import tempfile

from interopcheck import mount_fuse, unmount_fuse

EXIT_RANGE = 6    # EXT4_ERR_RANGE negated - the driver's exit for a refused hole


def sh(*a, **k):
    return subprocess.run(a, capture_output=True, text=True, **k)


def pat(i, salt):
    return (i ^ (i >> 8) ^ salt) & 0xFF


def content(n, salt):
    return bytes(pat(i, salt) for i in range(n))


BASE_SALT = 7
ALIGNED_SALT = 9
WRITE_SALT = 200


def expected_after(base, offset, length, salt):
    """What the file should be after writing `length` pattern bytes at `offset`."""
    end = offset + length
    out = bytearray(base)
    if end > len(out):
        out.extend(b"\0" * (end - len(out)))
    for i in range(offset, end):
        out[i] = pat(i - offset, salt)
    return bytes(out)


def fsck_clean(img, problems, label):
    r = sh("e2fsck", "-fn", img)
    if r.returncode != 0:
        detail = (r.stdout + r.stderr).strip().replace("\n", " ")[:240]
        problems.append(f"[{label}] e2fsck is not clean (rc={r.returncode}): {detail}")
        return False
    return True


def read_via_fuse(img, mnt, rel, problems, label):
    proc = mount_fuse(img, mnt, rw=False)
    if not proc:
        problems.append(f"[{label}] fuse2fs would not mount for read-back")
        return None
    try:
        with open(os.path.join(mnt, rel), "rb") as f:
            return f.read()
    except OSError as e:
        problems.append(f"[{label}] cannot read {rel} through fuse2fs: {e}")
        return None
    finally:
        unmount_fuse(mnt, proc)


def build_base(img, mnt, bs, problems):
    """base.bin (mid-block), aligned.bin (block-aligned), empty.bin (0 bytes)."""
    proc = mount_fuse(img, mnt, rw=True)
    if not proc:
        problems.append("fuse2fs would not mount to build the base files")
        return False
    try:
        with open(os.path.join(mnt, "base.bin"), "wb") as f:
            f.write(content(5000, BASE_SALT))
        with open(os.path.join(mnt, "aligned.bin"), "wb") as f:
            f.write(content(2 * bs, ALIGNED_SALT))
        open(os.path.join(mnt, "empty.bin"), "wb").close()
        # A real sparse file - a hole at the start - so the refusal to write into a
        # hole has something to refuse. Seeking past the end and writing one byte
        # leaves logical block 0 unmapped (confirmed with debugfs: only the far
        # block is allocated).
        with open(os.path.join(mnt, "sparse.bin"), "wb") as f:
            f.seek(200000)
            f.write(b"x")
        sh("sync")
    finally:
        ok = unmount_fuse(mnt, proc)
    if not ok:
        problems.append("fuse2fs would not unmount after building the base files")
    return ok


def expect_write(writeat, base_img, tmp, mnt, label, rel, base_bytes,
                 offset, length, problems):
    """One successful positional write, held to e2fsck and a byte-exact read-back."""
    img = os.path.join(tmp, "work.img")
    shutil.copy(base_img, img)

    r = sh(writeat, img, "/" + rel, str(offset), str(length), str(WRITE_SALT))
    if r.returncode != 0:
        problems.append(f"[{label}] writeat {rel} @{offset}+{length} was refused "
                        f"(rc={r.returncode}): {r.stderr.strip()}")
        return
    if not fsck_clean(img, problems, label):
        return

    want = expected_after(base_bytes, offset, length, WRITE_SALT)
    got = read_via_fuse(img, mnt, rel, problems, label)
    if got is None:
        return
    if len(got) != len(want):
        problems.append(f"[{label}] {rel} is {len(got)} bytes, expected {len(want)}")
        return
    bad = next((i for i in range(len(want)) if got[i] != want[i]), None)
    if bad is not None:
        problems.append(f"[{label}] {rel} byte {bad} is {got[bad]}, expected "
                        f"{want[bad]} - a byte landed in the wrong place")


def expect_refusal(writeat, base_img, tmp, label, rel, offset, length, problems):
    """A hole write that must be refused, with the image left unchanged."""
    img = os.path.join(tmp, "work.img")
    shutil.copy(base_img, img)

    r = sh(writeat, img, "/" + rel, str(offset), str(length), str(WRITE_SALT))
    if r.returncode != EXIT_RANGE:
        problems.append(f"[{label}] writeat {rel} @{offset}+{length} gave "
                        f"rc={r.returncode}, expected {EXIT_RANGE} (a refused hole)")
    if sh("cmp", "-s", img, base_img).returncode != 0:
        problems.append(f"[{label}] the image changed even though the write was refused")
    fsck_clean(img, problems, label)


def check_at(bs, tools, problems):
    mkfs, writeat = tools
    with tempfile.TemporaryDirectory() as tmp:
        base_img = os.path.join(tmp, "base.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)

        sh("truncate", "-s", "32M", base_img)
        if sh(mkfs, base_img, "--bs", str(bs)).returncode:
            problems.append(f"[{bs}] mkfs failed")
            return
        if not build_base(base_img, mnt, bs, problems):
            return
        if not fsck_clean(base_img, problems, f"{bs}/base"):
            return

        base5000 = content(5000, BASE_SALT)
        aligned = content(2 * bs, ALIGNED_SALT)

        def L(s):
            return f"{bs}/{s}"

        # ── overwrites (no growth) and growth, all on base.bin ───────────────
        cases = [
            ("in-block",        "base.bin", base5000, 100,      200),
            ("cross-block",     "base.bin", base5000, bs - 96,  300),
            ("partial-last",    "base.bin", base5000, 4200,     500),
            ("grow-a-little",   "base.bin", base5000, 4900,     300),
            ("grow-new-blocks", "base.bin", base5000, 4900,     4000),
            ("append-at-eof",   "base.bin", base5000, 5000,     1000),
            ("append-aligned",  "aligned.bin", aligned, 2 * bs, 100),
            ("from-empty",      "empty.bin", b"",     0,        3000),
        ]
        for name, rel, base_bytes, off, length in cases:
            expect_write(writeat, base_img, tmp, mnt, L(name), rel, base_bytes,
                         off, length, problems)

        # ── refused holes ────────────────────────────────────────────────────
        expect_refusal(writeat, base_img, tmp, L("hole-past-end"),
                       "base.bin", 6000, 100, problems)
        expect_refusal(writeat, base_img, tmp, L("hole-in-empty"),
                       "empty.bin", 100, 50, problems)
        # Into the hole at the front of a sparse file: within i_size, but block 0 is
        # not mapped, so it cannot be written without allocating into the middle.
        expect_refusal(writeat, base_img, tmp, L("hole-in-sparse"),
                       "sparse.bin", 0, 500, problems)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--writeat", default=os.path.join(here, "writeat"))
    args = ap.parse_args()

    mkfs, writeat = args.mkfs, args.writeat
    for t in (mkfs, writeat):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first (tools/ext4/build.sh)")
    for prog in ("fuse2fs", "e2fsck"):
        if not shutil.which(prog):
            sys.exit(f"{prog} not found - it is one of the independent oracles here")

    problems = []
    for bs in (1024, 4096):
        check_at(bs, (mkfs, writeat), problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("positional writes at two block sizes: overwrote in place, grew within a "
          "block and into new ones, appended at the end; every byte read back through "
          "fuse2fs is in its place, e2fsck clean; hole writes refused unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
