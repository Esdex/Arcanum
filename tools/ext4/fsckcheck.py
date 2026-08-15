#!/usr/bin/env python3
# Arcanum - VeraCrypt-compatible encrypted vault manager for Android
#
# Copyright (C) 2026 Esdex
# Licensed under Apache License 2.0
# SPDX-License-Identifier: Apache-2.0
#
# Host test harness for the clean-room ext4 library - PC-only, not shipped in the
# app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
# never linked or copied. See issue #7.

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

Under --fill the round trip makes one exception, and only one: a group that
arrived with no block bitmap on disk keeps the bitmap the allocator built for it,
and keeps the cleared flag, because that is not something freeing the blocks again
undoes - the kernel does not undo it either. Those two regions are named
explicitly (uninit_regions) and every other byte in the image is still required
back unchanged.

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


def read_layout(img):
    """Everything the synthetic check below needs to edit a group descriptor."""
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u32 = lambda o: struct.unpack_from("<I", sb, o)[0]
        u16 = lambda o: struct.unpack_from("<H", sb, o)[0]
        bs = 1024 << u32(0x18)
        bpg, first = u32(0x20), u32(0x14)
        blocks = u32(0x04) | (u32(0x150) << 32)
        dsz = u16(0xFE) if (u32(0x60) & 0x80) else 32
        ngroups = (blocks - first + bpg - 1) // bpg
        desc_at = (first + 1) * bs
        f.seek(desc_at)
        desc = f.read(ngroups * dsz)
    return dict(bs=bs, bpg=bpg, first=first, blocks=blocks, dsz=dsz,
                ngroups=ngroups, desc_at=desc_at, desc=desc,
                seed=u32(0x270))


def group_desc_csum(seed, group, desc):
    """bg_checksum: seeded with the group number, over the descriptor with the
    checksum field itself reading as zero."""
    d = bytearray(desc)
    struct.pack_into("<H", d, 0x1E, 0)
    return crc32c(crc32c(seed, struct.pack("<I", group)), bytes(d)) & 0xFFFF


def read_at(img, offset, length):
    with open(img, "rb") as f:
        f.seek(offset)
        return f.read(length)


def write_at(img, offset, data):
    with open(img, "r+b") as f:
        f.seek(offset)
        f.write(data)


def check_rebuilt_bitmap_matches_mke2fs(alloc, fsmeta, tmp):
    """Rebuilding a group's bitmap has to produce what mke2fs wrote for it.

    Every generated case uses flex_bg, which moves the bitmaps and inode tables out
    of the groups they belong to - so in the corpus a BLOCK_UNINIT group holds no
    metadata at all and its rebuilt bitmap is zeroes. Two parts of the rebuild are
    therefore never reached there: a group that owns its own bitmaps and inode
    table, and a group carrying a backup superblock and descriptor table. Neither
    is exotic; both are what a container formatted without flex_bg looks like.

    So the image here is made without flex_bg, and a group carrying a backup
    superblock is flagged BLOCK_UNINIT by hand with its bitmap block scribbled
    over, the same trick the journal-recovery check uses. mke2fs had already
    written the true bitmap for that group, so after filling the image and freeing
    it all again the block has to come back byte for byte. That is the rebuild
    judged against e2fsprogs rather than against our own idea of the layout.

    Not the last group, which is the one place this cannot be staged: e2fsck
    refuses a BLOCK_UNINIT final group outright ("Last group block bitmap
    uninitialized"), because the padding covering blocks past the end of the volume
    has to be on disk. So the rebuild's padding step has no valid filesystem to be
    exercised on. It is kept anyway - a group arriving in that state is one
    e2fsck already calls damaged, and initialising it with the padding set is the
    repair rather than the damage.
    """
    img = os.path.join(tmp, "ownmeta.img")
    subprocess.run(["truncate", "-s", "64M", img], check=True)
    r = subprocess.run(["mkfs.ext4", "-q", "-F", "-b", "1024", "-I", "256",
                        "-O", "^has_journal,^dir_index,^flex_bg", img],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return [f"could not format the no-flex_bg image: {r.stderr.strip()[:200]}"]

    lay = read_layout(img)
    dsz, desc_at, bs, bpg = lay["dsz"], lay["desc_at"], lay["bs"], lay["bpg"]

    def slot_of(g):
        return lay["desc"][g * dsz:(g + 1) * dsz]

    def owns_metadata(g, d):
        start = lay["first"] + g * bpg
        return all(start <= struct.unpack_from("<I", d, off)[0] < start + bpg
                   for off in (0x00, 0x04, 0x08))

    # A group that owns its metadata and whose bitmap does not sit at the group's
    # very first block: what is in front of it is the backup superblock and the
    # descriptor table, which is the run the rebuild has to reproduce. Group 0 is
    # excluded because it holds the root directory, and the last because e2fsck
    # will not accept it uninitialised.
    target = None
    for g in range(lay["ngroups"] - 2, 0, -1):
        d = slot_of(g)
        if struct.unpack_from("<H", d, 0x12)[0] & EXT4_BG_BLOCK_UNINIT:
            continue
        if owns_metadata(g, d) and struct.unpack_from("<I", d, 0x00)[0] > lay["first"] + g * bpg:
            target = g
            break
    if target is None:
        return ["no group in the no-flex_bg image carries both a backup superblock "
                "and its own bitmaps, so the rebuild cannot be judged here"]

    g = target
    slot = desc_at + g * dsz
    orig_desc = slot_of(g)
    bitmap_block = struct.unpack_from("<I", orig_desc, 0x00)[0]
    if dsz >= 64:
        bitmap_block |= struct.unpack_from("<I", orig_desc, 0x20)[0] << 32
    bitmap_at = bitmap_block * bs
    orig_bitmap = read_at(img, bitmap_at, bs)

    problems = []
    # The group has to be untouched for "uninitialised" to be true of it, and an
    # untouched group's used blocks are one run at its front. Checked from the
    # bitmap mke2fs wrote, so it says nothing about where we think metadata lies.
    bits = "".join(f"{byte:08b}"[::-1] for byte in orig_bitmap[:bpg // 8])
    if bits.rstrip("0").rstrip("1") != "":
        problems.append(f"group {g} has blocks in use beyond the run at its front, "
                        f"so calling it uninitialised is not true of it")

    # Flag it, drop the bitmap checksum the way mke2fs leaves it on an
    # uninitialised group, restamp the descriptor, and destroy the bitmap so a
    # rebuild that quietly reuses what is on disk cannot pass.
    d = bytearray(orig_desc)
    struct.pack_into("<H", d, 0x12,
                     struct.unpack_from("<H", d, 0x12)[0] | EXT4_BG_BLOCK_UNINIT)
    struct.pack_into("<H", d, 0x18, 0)
    if dsz >= 64:
        struct.pack_into("<H", d, 0x38, 0)
    struct.pack_into("<H", d, 0x1E, group_desc_csum(lay["seed"], g, bytes(d)))
    write_at(img, slot, bytes(d))
    write_at(img, bitmap_at, b"\xA5" * lay["bs"])

    base_rc, base_lines = fsck(img)
    if base_rc != FSCK_OK:
        return problems + [f"the hand-flagged image is not fsck-clean to begin with "
                           f"(rc={base_rc}) - the check cannot judge anything"]

    before_groups = read_groups(img)[2]
    blocks, err = run_alloc(alloc, img, "fill")
    if err:
        return problems + [f"filling the no-flex_bg image failed: {err}"]

    check_fill(img, before_groups, problems)

    rc, lines = fsck(img)
    baseline = list(base_lines)
    new = []
    for line in lines:
        if line in baseline:
            baseline.remove(line)
        else:
            new.append(line)
    problems += residual_ok(new, blocks, img)

    m = subprocess.run([fsmeta, img], capture_output=True, text=True)
    if m.returncode != 0:
        problems.append(f"checksums no longer verify: {m.stdout.strip()}")

    _, err = run_alloc(alloc, img, "free", "-",
                       stdin="".join(f"{b}\n" for b in blocks))
    if err:
        return problems + [f"free failed on the no-flex_bg image: {err}"]

    rebuilt = read_at(img, bitmap_at, lay["bs"])
    if rebuilt != orig_bitmap:
        at = next(i for i in range(lay["bs"]) if rebuilt[i] != orig_bitmap[i])
        problems.append(f"the rebuilt bitmap for group {g} is not the one mke2fs "
                        f"wrote: first difference at byte {at} of the block "
                        f"(ours {rebuilt[at]:#04x}, mke2fs {orig_bitmap[at]:#04x})")
    back = read_at(img, slot, dsz)
    if back != orig_desc:
        problems.append(f"group {g}'s descriptor did not come back to what mke2fs "
                        f"wrote once the blocks were freed again")

    rt_rc, _ = fsck(img)
    if rt_rc != FSCK_OK:
        problems.append(f"the no-flex_bg image is not clean after the round trip "
                        f"(rc={rt_rc})")
    return problems


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


def uninit_regions(img):
    """-> byte ranges that initialising a BLOCK_UNINIT group is allowed to change.

    Building a bitmap for a group that never had one is not reversible by freeing
    the blocks again: the bitmap block now holds a bitmap where it held whatever
    the volume did before, the descriptor's flag is gone for good, and its bitmap
    checksum covers real bytes. That is the kernel's behaviour too. So the round
    trip stops asking for those two regions back and keeps asking for every other
    byte in the image, which is where a one-sided update would show up.
    """
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u32 = lambda o: struct.unpack_from("<I", sb, o)[0]
        u16 = lambda o: struct.unpack_from("<H", sb, o)[0]
        bs = 1024 << u32(0x18)
        bpg, first = u32(0x20), u32(0x14)
        blocks = u32(0x04) | (u32(0x150) << 32)
        dsz = u16(0xFE) if (u32(0x60) & 0x80) else 32
        ngroups = (blocks - first + bpg - 1) // bpg
        desc_at = (first + 1) * bs
        f.seek(desc_at)
        desc = f.read(ngroups * dsz)

    out = []
    for g in range(ngroups):
        d = desc[g * dsz:(g + 1) * dsz]
        if not struct.unpack_from("<H", d, 0x12)[0] & EXT4_BG_BLOCK_UNINIT:
            continue
        bitmap = struct.unpack_from("<I", d, 0x00)[0]
        if dsz >= 64:
            bitmap |= struct.unpack_from("<I", d, 0x20)[0] << 32
        out.append((desc_at + g * dsz, dsz))
        out.append((bitmap * bs, bs))
    return out


def first_difference_outside(after, pristine, regions):
    """-> offset of the first byte that differs and is not in an allowed region.

    Compared segment by segment rather than byte by byte: these images run to tens
    of megabytes and a Python loop over every byte of forty of them is the whole
    runtime of the suite.
    """
    if len(after) != len(pristine):
        return min(len(after), len(pristine))
    segments, pos = [], 0
    for start, length in sorted(regions):
        if start > pos:
            segments.append((pos, start))
        pos = max(pos, start + length)
    if pos < len(after):
        segments.append((pos, len(after)))

    for lo, hi in segments:
        if after[lo:hi] == pristine[lo:hi]:
            continue
        a, p = after[lo:hi], pristine[lo:hi]
        return lo + next(i for i in range(len(a)) if a[i] != p[i])
    return None


def run_alloc(alloc, img, *args, stdin=None):
    r = subprocess.run([alloc, img, *[str(a) for a in args]],
                       capture_output=True, text=True, input=stdin)
    if r.returncode != 0:
        return None, (r.stderr.strip() or r.stdout.strip() or "allocator exited non-zero")
    blocks = [int(x) for x in r.stdout.split()]
    return blocks, None


def check_fill(img, before_groups, problems):
    """A filled image pins down the rule a nine-block run never reaches.

    Every block in the volume has to be taken, including the ones in groups whose
    bitmap did not exist until the allocator built it (#140). A group left flagged
    is a group left unreachable, and the free count it still carries is the space
    that was refused - so both are checked, and the free count catches a group that
    was initialised but then skipped anyway.

    Only some images have such a group - mke2fs sets the flag on the groups it does
    not need to touch, which depends on the size - so how many did is reported in
    the summary rather than required here. Coverage that does not depend on the
    corpus comes from check_rebuilt_bitmap_matches_mke2fs, which manufactures the
    state instead of hoping for it.
    """
    uninit = {g for g, (flags, _) in enumerate(before_groups)
              if flags & EXT4_BG_BLOCK_UNINIT}
    _, _, after = read_groups(img)
    still = sorted(g for g, (flags, _) in enumerate(after)
                   if flags & EXT4_BG_BLOCK_UNINIT)
    if still:
        problems.append(f"groups {still[:8]} are still BLOCK_UNINIT after filling - "
                        f"their blocks were never reachable")

    got = sb_free_blocks(img)
    if got != 0:
        held = sum(free for g, (_, free) in enumerate(after) if g in uninit)
        problems.append(f"after filling, {got} blocks are still free "
                        f"({held} of them in groups that started BLOCK_UNINIT)")


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


def check_ifill(img, before_groups, problems):
    """The same rule for inodes: every one of them has to be reachable, including
    those in a group whose bitmap the allocator had to build first."""
    _, after = read_inode_groups(img)
    still = sorted(g for g, (flags, _) in enumerate(after)
                   if flags & EXT4_BG_INODE_UNINIT)
    if still:
        problems.append(f"groups {still[:8]} are still INODE_UNINIT after filling - "
                        f"their inodes were never reachable")

    got = sb_free_inodes(img)
    if got != 0:
        problems.append(f"after filling, {got} inodes are still free")


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

        before_groups = read_inode_groups(img)[1]
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
            check_ifill(img, before_groups, problems)

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
        before_groups = read_groups(img)[2]
        allowed = uninit_regions(img) if fill else []

        if fill:
            blocks, err = run_alloc(alloc, img, "fill")
        else:
            blocks, err = run_alloc(alloc, img, "alloc", count)
        if err:
            return [f"allocator failed: {err}"]
        if len(blocks) != len(set(blocks)):
            problems.append("allocator returned the same block twice")
        if fill:
            check_fill(img, before_groups, problems)

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
            diff_at = first_difference_outside(after, pristine, allowed)
            if diff_at is not None:
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

    # Standalone checks, on their own images so the synthetic edits do not touch
    # what the per-case runs see: a filesystem needing journal recovery is refused,
    # and a rebuilt block bitmap matches the one mke2fs wrote.
    import shutil as _shutil, tempfile as _tempfile
    with _tempfile.TemporaryDirectory() as _t:
        _img = os.path.join(_t, "fs.img")
        _shutil.copy(os.path.join(cases[0], "fs.img"), _img)
        if not check_recover_refused(args.alloc, _img):
            print("FAIL a filesystem needing journal recovery was opened for writing")
            return 1
        problems = check_rebuilt_bitmap_matches_mke2fs(args.alloc, args.fsmeta, _t)
        if problems:
            print("FAIL rebuilding a BLOCK_UNINIT group's bitmap")
            for p in problems:
                print(f"     {p}")
            return 1
        if args.verbose:
            print("ok   rebuilt bitmap matches mke2fs (no flex_bg, partial last group)")

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

    # How much of the corpus actually held a group with no bitmap on disk. A run
    # where that is zero still proves the rest, but nothing about #140.
    if args.fill or args.ifill:
        if args.ifill:
            flag, noun = EXT4_BG_INODE_UNINIT, "INODE_UNINIT"
            groups_of = lambda p: read_inode_groups(p)[1]
        else:
            flag, noun = EXT4_BG_BLOCK_UNINIT, "BLOCK_UNINIT"
            groups_of = lambda p: read_groups(p)[2]
        covered = sum(1 for c in cases
                      if any(f & flag for f, _ in groups_of(os.path.join(c, "fs.img"))))
        print(f"{covered}/{len(cases)} of them had a {noun} group for the allocator "
              f"to build a bitmap for")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
