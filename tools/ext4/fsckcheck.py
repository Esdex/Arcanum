#!/usr/bin/env python3
r"""
The harness for the write path. Everything up to now compared our answer against
debugfs and a wrong answer showed up as a wrong number. That does not carry over:
an allocator can leave every structure individually well-formed, with a correct
checksum on each, and still leave the filesystem inconsistent between them. Only
e2fsck sees that, so it runs after every write.

Three legs, because no one of them is sufficient:

  fsck      cross-structure consistency - counters that disagree, blocks claimed
            twice, a bitmap that does not match what the inodes say
  fsmeta    every checksum recomputed correctly, on every group
  round-trip allocate then free and require the image back byte for byte, which
            catches an update that is not symmetric

    ./fsckcheck.py --cases /tmp/cases

The allocator is driven through this contract:

    ./alloc <image> alloc <count>    prints one allocated block per line
    ./alloc <image> free <block>...  releases the listed blocks

## Why fsck cannot simply be required to be clean

e2fsck builds the in-use block map from the inodes' extent trees and checks the
bitmap against it. A block that is allocated but attached to no inode is therefore
an orphan, and fsck reports it - so a *correct* allocation still exits 4 with

    Block bitmap differences:  -(3012--3014)

That is inherent to testing allocation on its own, before there is an extent
writer to attach the blocks to. Rather than lose the oracle, the residual is
pinned down exactly: the orphan list has to equal the blocks the allocator says it
took, and any other new line at all is a failure. That keeps every other class of
complaint - free counts, descriptor checksums, superblock checksums - fatal.

## Two things that will silently weaken this if changed

Pristine images are not fsck-silent. 17 of the 40 generated cases already print
`extent tree could be narrower. Optimize? no` at rc=0, from mke2fs. So the
comparison is against a per-image baseline, never against an empty output.

e2fsck collapses runs into `-(3012--3014)`. A pattern like `-(\d+)` reads one
block out of that and quietly under-reports, which makes the orphan comparison
weaker without failing anything - the tokeniser below handles both forms.
"""

import argparse
import glob
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

# -(N), +(N), -(A--B), +(A--B) - both forms e2fsck uses in a differences list.
DIFF_TOKEN = re.compile(r"([+-])(?:\((\d+)--(\d+)\)|(\d+))")

FSCK_OK = 0        # pristine images
FSCK_UNCORRECTED = 4   # what -n reports when it declines to fix the orphans


def fsck(img):
    r = subprocess.run(["e2fsck", "-fn", img], capture_output=True, text=True)
    return r.returncode, r.stdout.splitlines()


def split_bitmap_diff(lines, header="Block bitmap differences:"):
    """Separate the bitmap differences report from everything else.

    The list can wrap onto continuation lines, so it runs from the header up to
    the `Fix?` that closes it rather than being a single line.
    """
    rest, diff_lines = [], []
    i = 0
    while i < len(lines):
        if lines[i].startswith(header):
            while i < len(lines) and not lines[i].startswith("Fix?"):
                diff_lines.append(lines[i])
                i += 1
            if i < len(lines):      # the closing "Fix? no"
                i += 1
            continue
        rest.append(lines[i])
        i += 1
    return diff_lines, rest


def parse_diff(diff_lines):
    """-> (blocks fsck says are marked but unreferenced, blocks it says are missing)"""
    marked, missing = set(), set()
    for line in diff_lines:
        body = line.split(":", 1)[1] if ":" in line else line
        for sign, lo, hi, single in DIFF_TOKEN.findall(body):
            rng = range(int(lo), int(hi) + 1) if single == "" else [int(single)]
            (marked if sign == "-" else missing).update(rng)
    return marked, missing


def residual_ok(new_lines, expect_blocks, img, header="Block bitmap differences:",
                noun="blocks"):
    """The only permitted new output after an allocation, and nothing else."""
    diff_lines, rest = split_bitmap_diff(new_lines, header)
    marked, missing = parse_diff(diff_lines)
    problems = []

    if marked != set(expect_blocks):
        extra = sorted(marked - set(expect_blocks))[:8]
        absent = sorted(set(expect_blocks) - marked)[:8]
        problems.append(f"orphan {noun} list does not match what was allocated: "
                        f"fsck-only={extra} allocator-only={absent}")
    if missing:
        problems.append(f"fsck says {noun} should be marked but are not: "
                        f"{sorted(missing)[:8]}")

    base = os.path.basename(img)
    for line in rest:
        if not line.strip():
            continue
        if re.fullmatch(r".*: \*+ WARNING: Filesystem still has errors \*+", line):
            continue
        if re.fullmatch(r".*: \d+/\d+ files \([\d.]+% non-contiguous\), \d+/\d+ blocks", line):
            continue
        problems.append(f"unexpected fsck output: {line.strip()}")
    return problems


def sb_free_inodes(img):
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
    return struct.unpack_from("<I", sb, 0x10)[0]


def sb_free_blocks(img):
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
    lo = struct.unpack_from("<I", sb, 0x0C)[0]
    hi = struct.unpack_from("<I", sb, 0x158)[0]
    return lo | (hi << 32)


EXT4_BG_BLOCK_UNINIT = 0x0002


def crc32c(crc, data):
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc >> 1) ^ (0x82F63B78 & -(crc & 1))) & 0xFFFFFFFF
    return crc


def check_recover_refused(alloc, img):
    """A filesystem whose journal still needs replaying must be refused.

    The INCOMPAT_RECOVER flag is set synthetically - and the superblock checksum
    fixed up so the image is otherwise valid - rather than by producing a real
    dirty journal, which needs a kernel mount and a crash under it. That checks
    the guard, which is a real guard: writing around an unreplayed journal loses
    the writes at the next replay. It does not check journal support, which does
    not exist. Same shape as the htree guard.
    """
    with open(img, "r+b") as f:
        f.seek(1024)
        sb = bytearray(f.read(1024))
        incompat = struct.unpack_from("<I", sb, 0x60)[0] | 0x4
        struct.pack_into("<I", sb, 0x60, incompat)
        struct.pack_into("<I", sb, 0x3FC, crc32c(0xFFFFFFFF, sb[:0x3FC]))
        f.seek(1024)
        f.write(sb)
    r = subprocess.run([alloc, img, "alloc", "1"], capture_output=True, text=True)
    return r.returncode != 0


def read_groups(img):
    """-> (first_data_block, blocks_per_group, [(flags, free_blocks), ...])

    Parsed here rather than taken from the allocator, so the expectations the fill
    check measures against do not come from the thing being checked.
    """
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u32 = lambda o: struct.unpack_from("<I", sb, o)[0]
        u16 = lambda o: struct.unpack_from("<H", sb, o)[0]
        bs = 1024 << u32(0x18)
        bpg = u32(0x20)
        first = u32(0x14)
        blocks = u32(0x04) | (u32(0x150) << 32)
        dsz = u16(0xFE) if (u32(0x60) & 0x80) else 32
        ngroups = (blocks - first + bpg - 1) // bpg
        f.seek((first + 1) * bs)
        desc = f.read(ngroups * dsz)
    out = []
    for g in range(ngroups):
        d = desc[g * dsz:(g + 1) * dsz]
        flags = struct.unpack_from("<H", d, 0x12)[0]
        free = struct.unpack_from("<H", d, 0x0C)[0]
        if dsz >= 64:
            free |= struct.unpack_from("<H", d, 0x2C)[0] << 16
        out.append((flags, free))
    return first, bpg, out


def run_alloc(alloc, img, *args, stdin=None):
    r = subprocess.run([alloc, img, *[str(a) for a in args]],
                       capture_output=True, text=True, input=stdin)
    if r.returncode != 0:
        return None, (r.stderr.strip() or r.stdout.strip() or "allocator exited non-zero")
    blocks = [int(x) for x in r.stdout.split()]
    return blocks, None


def check_fill(img, blocks, problems):
    """A filled image pins down the two rules a nine-block run never reaches.

    Everything outside a BLOCK_UNINIT group has to be taken, and everything inside
    one has to be left, so the free count left in the superblock is exactly the
    free space those groups hold - no more, and no less either, since stopping
    early would also land on that side of the comparison.
    """
    first, bpg, groups = read_groups(img)
    uninit = {g for g, (flags, _) in enumerate(groups) if flags & EXT4_BG_BLOCK_UNINIT}

    trespass = sorted({(b - first) // bpg for b in blocks} & uninit)
    if trespass:
        problems.append(f"allocated inside BLOCK_UNINIT groups {trespass} - "
                        f"those bitmaps were never written")

    want = sum(free for g, (_, free) in enumerate(groups) if g in uninit)
    got = sb_free_blocks(img)
    if got != want:
        problems.append(f"after filling, {got} blocks are still free but the "
                        f"BLOCK_UNINIT groups only hold {want}")


EXT4_BG_INODE_UNINIT = 0x0001


def read_inode_groups(img):
    """-> (inodes_per_group, [(flags, free_inodes), ...]), read independently."""
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u32 = lambda o: struct.unpack_from("<I", sb, o)[0]
        u16 = lambda o: struct.unpack_from("<H", sb, o)[0]
        bs = 1024 << u32(0x18)
        bpg, ipg, first = u32(0x20), u32(0x28), u32(0x14)
        blocks = u32(0x04) | (u32(0x150) << 32)
        dsz = u16(0xFE) if (u32(0x60) & 0x80) else 32
        ngroups = (blocks - first + bpg - 1) // bpg
        f.seek((first + 1) * bs)
        desc = f.read(ngroups * dsz)
    out = []
    for g in range(ngroups):
        d = desc[g * dsz:(g + 1) * dsz]
        flags = struct.unpack_from("<H", d, 0x12)[0]
        free = struct.unpack_from("<H", d, 0x0E)[0]
        if dsz >= 64:
            free |= struct.unpack_from("<H", d, 0x2E)[0] << 16
        out.append((flags, free))
    return ipg, out


def check_ifill(img, inodes, problems):
    """Filling pins down the rule four inodes never reach: a group whose bitmap
    was never written must be left alone, so what stays free is exactly what
    those groups hold - no more, and no less either."""
    ipg, groups = read_inode_groups(img)
    uninit = {g for g, (flags, _) in enumerate(groups) if flags & EXT4_BG_INODE_UNINIT}

    trespass = sorted({(i - 1) // ipg for i in inodes} & uninit)
    if trespass:
        problems.append(f"allocated inside INODE_UNINIT groups {trespass} - "
                        f"those bitmaps were never written")

    want = sum(free for g, (_, free) in enumerate(groups) if g in uninit)
    got = sb_free_inodes(img)
    if got != want:
        problems.append(f"after filling, {got} inodes are still free but the "
                        f"INODE_UNINIT groups only hold {want}")


def check_inode_case(case, alloc, fsmeta, count):
    """The same gate, for inodes.

    An inode taken from the bitmap and named in no directory is the exact
    counterpart of a block attached to no inode, and e2fsck reports it the same
    way - an "Inode bitmap differences" list. So the residual is pinned to the
    inodes the allocator says it took, and everything else stays fatal.

    The round trip cannot ask for the image back byte for byte here, because
    allocation deliberately zeroes the inode it hands out and a reused one rarely
    held zeroes before. Freeing has to return the filesystem to its baseline
    instead, which is the property that actually matters.
    """
    img_src = os.path.join(case, "fs.img")
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "fs.img")
        shutil.copy(img_src, img)

        base_rc, base_lines = fsck(img)
        if base_rc != FSCK_OK:
            return [f"pristine image is not fsck-clean (rc={base_rc})"]
        free_before = sb_free_inodes(img)

        inodes, err = (run_alloc(alloc, img, "ifill") if count is None
                       else run_alloc(alloc, img, "ialloc", count))
        if err:
            return [f"inode allocator failed: {err}"]
        if len(inodes) != len(set(inodes)):
            problems.append("allocator returned the same inode twice")
        if any(i < 11 for i in inodes):
            problems.append(f"allocator handed out a reserved inode: "
                            f"{sorted(i for i in inodes if i < 11)}")

        rc, lines = fsck(img)
        baseline = list(base_lines)
        new = []
        for line in lines:
            if line in baseline:
                baseline.remove(line)
            else:
                new.append(line)
        problems += residual_ok(new, inodes, img,
                                header="Inode bitmap differences:", noun="inodes")
        if inodes and rc != FSCK_UNCORRECTED:
            problems.append(f"expected fsck rc={FSCK_UNCORRECTED} after allocating, got {rc}")

        if free_before - sb_free_inodes(img) != len(inodes):
            problems.append(f"superblock free inode count moved by "
                            f"{free_before - sb_free_inodes(img)}, "
                            f"expected {len(inodes)}")

        if count is None:
            check_ifill(img, inodes, problems)

        m = subprocess.run([fsmeta, img], capture_output=True, text=True)
        if m.returncode != 0:
            problems.append(f"checksums no longer verify: {m.stdout.strip()}")

        _, err = run_alloc(alloc, img, "ifree", "-",
                           stdin="".join(f"{i}\n" for i in inodes))
        if err:
            problems.append(f"ifree failed: {err}")
        else:
            rt_rc, rt_lines = fsck(img)
            if rt_rc != FSCK_OK or rt_lines != base_lines:
                left = [l for l in rt_lines if l not in base_lines]
                problems.append(f"freeing did not return the filesystem to its "
                                f"baseline (rc={rt_rc}): {left[:3]}")
            if sb_free_inodes(img) != free_before:
                problems.append("free inode count did not come back")
    return problems


def check_case(case, alloc, fsmeta, count, keep, fill=False):
    img_src = os.path.join(case, "fs.img")
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        # Same path for the baseline and the post-write run, so the image name
        # inside fsck's own output lines matches and never needs substituting.
        img = os.path.join(tmp, "fs.img")
        shutil.copy(img_src, img)

        base_rc, base_lines = fsck(img)
        if base_rc != FSCK_OK:
            return [f"pristine image is not fsck-clean (rc={base_rc}) - "
                    f"the baseline is unusable"]
        free_before = sb_free_blocks(img)
        pristine = open(img, "rb").read()

        if fill:
            blocks, err = run_alloc(alloc, img, "fill")
        else:
            blocks, err = run_alloc(alloc, img, "alloc", count)
        if err:
            return [f"allocator failed: {err}"]
        if len(blocks) != len(set(blocks)):
            problems.append("allocator returned the same block twice")
        if fill:
            check_fill(img, blocks, problems)

        rc, lines = fsck(img)
        baseline = list(base_lines)
        new = []
        for line in lines:                      # remove baseline lines once each
            if line in baseline:
                baseline.remove(line)
            else:
                new.append(line)
        problems += residual_ok(new, blocks, img)

        if blocks and rc != FSCK_UNCORRECTED:
            problems.append(f"expected fsck rc={FSCK_UNCORRECTED} after allocating, got {rc}")

        free_after = sb_free_blocks(img)
        if free_before - free_after != len(blocks):
            problems.append(f"superblock free count moved by "
                            f"{free_before - free_after}, expected {len(blocks)}")

        m = subprocess.run([fsmeta, img], capture_output=True, text=True)
        if m.returncode != 0:
            problems.append(f"checksums no longer verify: {m.stdout.strip()}")

        # Round trip. Freeing what was just taken has to restore the image
        # exactly - not merely something fsck accepts, but the same bytes.
        _, err = run_alloc(alloc, img, "free", "-",
                           stdin="".join(f"{b}\n" for b in blocks))
        if err:
            problems.append(f"free failed: {err}")
        else:
            after = open(img, "rb").read()
            if after != pristine:
                diff_at = next((i for i in range(min(len(after), len(pristine)))
                                if after[i] != pristine[i]), None)
                problems.append(f"round trip did not restore the image "
                                f"(first differing byte at offset {diff_at})")
            rt_rc, _ = fsck(img)
            if rt_rc != FSCK_OK:
                problems.append(f"image is not clean again after freeing (rc={rt_rc})")

        if problems and keep:
            shutil.copy(img, keep)
            problems.append(f"failing image kept at {keep}")
    return problems


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--alloc", default=os.path.join(here, "alloc"))
    ap.add_argument("--fsmeta", default=os.path.join(here, "fsmeta"))
    ap.add_argument("--count", type=int, default=9,
                    help="blocks to allocate per image")
    ap.add_argument("--fill", action="store_true",
                    help="take every reachable block instead, which is the only "
                         "way the BLOCK_UNINIT rule gets exercised")
    ap.add_argument("--limit", type=int,
                    help="stop after this many images")
    ap.add_argument("--inodes", type=int, metavar="N",
                    help="allocate N inodes instead of blocks")
    ap.add_argument("--ifill", action="store_true",
                    help="take every reachable inode instead, which is the only "
                         "way the INODE_UNINIT rule gets exercised")
    ap.add_argument("--keep", help="copy a failing image here for inspection")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for tool in (args.alloc, args.fsmeta):
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if not cases:
        sys.exit(f"no cases under {args.cases}")
    if args.limit:
        cases = cases[:args.limit]

    # One standalone check, on its own copy so the synthetic flag does not touch
    # what the per-case runs see: a filesystem needing journal recovery is refused.
    import shutil as _shutil, tempfile as _tempfile
    with _tempfile.TemporaryDirectory() as _t:
        _img = os.path.join(_t, "fs.img")
        _shutil.copy(os.path.join(cases[0], "fs.img"), _img)
        if not check_recover_refused(args.alloc, _img):
            print("FAIL a filesystem needing journal recovery was opened for writing")
            return 1

    failed = 0
    for case in cases:
        if args.inodes is not None or args.ifill:
            problems = check_inode_case(case, args.alloc, args.fsmeta,
                                        None if args.ifill else args.inodes)
        else:
            problems = check_case(case, args.alloc, args.fsmeta, args.count,
                                  args.keep, fill=args.fill)
        if problems:
            failed += 1
            print(f"FAIL {os.path.basename(case)}")
            for p in problems:
                print(f"     {p}")
        elif args.verbose:
            print(f"ok   {os.path.basename(case)}")

    what = ("take every inode" if args.ifill
            else f"allocate {args.inodes} inodes" if args.inodes is not None
            else "fill" if args.fill else f"allocate {args.count}")
    print(f"\n{len(cases) - failed}/{len(cases)} images survived "
          f"{what} + fsck + checksums + round trip")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
