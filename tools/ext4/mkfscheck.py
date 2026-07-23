#!/usr/bin/env python3
r"""
The harness for the formatter.

    ./mkfscheck.py                       # the default sweep
    ./mkfscheck.py --geometry 32M:1024   # one size and block size
    ./mkfscheck.py --verbose

Every other suite here starts from an image mke2fs made and checks what we did to
it. This one checks the image itself, and it can do something none of the others
can: compare our filesystem against the one mke2fs builds from the same numbers,
byte for byte.

That is a stronger oracle than e2fsck. e2fsck says a filesystem is consistent;
this says it is the same filesystem. A field that is merely self-consistent - a
free count that agrees with a bitmap we also got wrong, a checksum over a
structure in the wrong place - passes the first and fails the second.

It is only possible because the geometry is an input to ext4_mkfs rather than a
decision inside it: the UUID, the hash seed, the creation time, the block count
and the inode count are read out of the reference image and handed to our
formatter, so the two runs differ in nothing but the code that wrote them.

## What is allowed to differ, and why

Three things, each a decision recorded in ext4_mkfs.c rather than an accident:

  s_kbytes_written        mke2fs counts the kilobytes it wrote into it. It is a
                          lifetime counter with no meaning at creation.
  bg_flags, bg_checksum   mke2fs sets INODE_ZEROED because it zeroed every inode
                          table. We do not zero them - bg_itable_unused already
                          says nothing has ever been read there - so claiming it
                          would be false. The descriptor checksum follows.
  the bitmaps of groups   mke2fs marks a group BLOCK_UNINIT or INODE_UNINIT and
  mke2fs left uninit      leaves its bitmap unwritten, to be derived on first
                          use. Our allocator refuses such groups, so a container
                          formatted that way would have most of its space
                          unreachable by the code meant to fill it. We write
                          every bitmap, and the two checksum fields follow.

Everything else is required identical: where each bitmap and inode table sits,
every free count, used-dirs count and bg_itable_unused, every inode, both
directory blocks, and which groups carry a backup superblock.

## The rungs

  diff        our image against mke2fs's, as above
  fsck        e2fsck -fn on ours is clean, and says exactly what it says of the
              reference
  fuse2fs     another driver mounts it read-write, creates things, and e2fsck is
              still clean afterwards
  ourselves   our own create and append run inside a filesystem we formatted,
              which is the end-to-end claim the feature rests on
  random      the same, formatted over random bytes instead of a sparse file.
              This is what a container actually looks like, and it is the rung
              that would catch us relying on the medium being zeroed. It also
              asserts the inode table tail is still random afterwards - proof the
              check is exercising bg_itable_unused rather than quietly zeroing.
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, BENIGN_REMARK                      # noqa: E402
from interopcheck import mount_fuse, unmount_fuse                # noqa: E402

FEATURES = "^has_journal,^dir_index,^flex_bg,^resize_inode,^orphan_file"
WHEN = 1784639915

EXT4_MAGIC = 0xEF53
BG_INODE_UNINIT = 0x1
BG_BLOCK_UNINIT = 0x2

SB_KBYTES_WRITTEN = 0x178
SB_CHECKSUM = 0x3FC
SB_R_BLOCKS_LO = 0x08
SB_R_BLOCKS_HI = 0x154

# Every named field of a 64-bit group descriptor: (low offset, size, high offset).
# Compared one by one so a failure says which field disagreed rather than which
# byte did.
GD_FIELDS = {
    "block_bitmap":  (0x00, 4, 0x20),
    "inode_bitmap":  (0x04, 4, 0x24),
    "inode_table":   (0x08, 4, 0x28),
    "free_blocks":   (0x0C, 2, 0x2C),
    "free_inodes":   (0x0E, 2, 0x2E),
    "used_dirs":     (0x10, 2, 0x30),
    "flags":         (0x12, 2, None),
    "bbitmap_csum":  (0x18, 2, 0x38),
    "ibitmap_csum":  (0x1A, 2, 0x3A),
    "itable_unused": (0x1C, 2, 0x32),
    "checksum":      (0x1E, 2, None),
}


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True)


class Superblock:
    """Just enough of it to drive the comparison."""

    def __init__(self, path):
        with open(path, "rb") as f:
            f.seek(1024)
            self.raw = f.read(1024)

    def u16(self, off):
        return struct.unpack_from("<H", self.raw, off)[0]

    def u32(self, off):
        return struct.unpack_from("<I", self.raw, off)[0]

    @property
    def block_size(self):
        return 1024 << self.u32(0x18)

    @property
    def blocks_count(self):
        return self.u32(0x04) | (self.u32(0x150) << 32)

    @property
    def inodes_count(self):
        return self.u32(0x00)

    @property
    def inodes_per_group(self):
        return self.u32(0x28)

    @property
    def blocks_per_group(self):
        return self.u32(0x20)

    @property
    def first_data_block(self):
        return self.u32(0x14)

    @property
    def inode_size(self):
        return self.u16(0x58)

    @property
    def desc_size(self):
        return self.u16(0xFE) if (self.u32(0x60) & 0x80) else 32

    @property
    def uuid(self):
        return self.raw[0x68:0x78].hex()

    @property
    def hash_seed(self):
        return self.raw[0xEC:0xFC].hex()

    @property
    def mkfs_time(self):
        return self.u32(0x108)

    @property
    def groups(self):
        span = self.blocks_count - self.first_data_block
        return (span + self.blocks_per_group - 1) // self.blocks_per_group

    def group_start(self, g):
        return self.first_data_block + g * self.blocks_per_group


def read_descs(path, sb, group=0):
    """The descriptor table as stored in `group`'s copy of it."""
    with open(path, "rb") as f:
        f.seek((sb.group_start(group) + 1) * sb.block_size)
        return f.read(sb.groups * sb.desc_size)


def compare_descriptor_copies(img, sb, problems, whose):
    """Every backup descriptor table must equal the primary, byte for byte.

    Worth checking separately because nothing else here can see it. The primary
    is compared field by field against mke2fs, and the byte comparison has to
    excuse the descriptor bytes to do that - which would excuse the backups too
    if they were not pulled out and checked against the primary instead. e2fsck
    is no help either: it never reads a backup at all, so a wrong one produces no
    symptom until the day the primary is gone and it is the only copy left.
    """
    primary = read_descs(img, sb)
    for g in sorted(backup_groups(img, sb)):
        if g == 0:
            continue
        copy = read_descs(img, sb, g)
        if copy != primary:
            where = next((i for i in range(min(len(copy), len(primary)))
                          if copy[i] != primary[i]), 0)
            problems.append(f"{whose} descriptor table backed up in group {g} "
                            f"differs from the primary, from byte {where} "
                            f"(descriptor {where // sb.desc_size})")


def desc_field(descs, sb, g, name):
    lo, size, hi = GD_FIELDS[name]
    base = g * sb.desc_size
    fmt = "<H" if size == 2 else "<I"
    v = struct.unpack_from(fmt, descs, base + lo)[0]
    if hi is not None and sb.desc_size >= 64:
        v |= struct.unpack_from(fmt, descs, base + hi)[0] << (size * 8)
    return v


def backup_groups(path, sb):
    """Which groups actually carry a superblock copy, read off the image.

    Derived from the image rather than from the sparse_super rule, so that the
    rule itself is what gets compared: a formatter that put backups in the wrong
    groups would agree with a checker that reimplemented the same mistake.
    """
    found = set()
    with open(path, "rb") as f:
        for g in range(sb.groups):
            off = sb.group_start(g) * sb.block_size
            if g == 0:
                off = 1024
            f.seek(off + 0x38)
            magic = struct.unpack("<H", f.read(2))[0]
            if magic == EXT4_MAGIC:
                found.add(g)
    return found


def compare_descriptors(ref, ours, sb, problems):
    """Field by field. Returns the groups the reference left uninitialised."""
    ref_d, our_d = read_descs(ref, sb), read_descs(ours, sb)
    uninit = {}
    for g in range(sb.groups):
        ref_flags = desc_field(ref_d, sb, g, "flags")
        uninit[g] = ref_flags
        for name in GD_FIELDS:
            # bg_flags and the descriptor checksum differ on every group: mke2fs
            # zeroed the inode tables and says so, we did not.
            if name in ("flags", "checksum"):
                continue
            if name == "bbitmap_csum" and (ref_flags & BG_BLOCK_UNINIT):
                continue
            if name == "ibitmap_csum" and (ref_flags & BG_INODE_UNINIT):
                continue
            a = desc_field(ref_d, sb, g, name)
            b = desc_field(our_d, sb, g, name)
            if a != b:
                problems.append(f"group {g}: bg_{name} is {b}, mke2fs says {a}")
    return uninit


def allowed_ranges(ref, sb, uninit, shortened):
    """Byte ranges permitted to differ, each one a decision recorded in the .c."""
    ok = []
    bs = sb.block_size
    for g in backup_groups(ref, sb):
        sb_off = 1024 if g == 0 else sb.group_start(g) * bs
        ok.append((sb_off + SB_KBYTES_WRITTEN, sb_off + SB_KBYTES_WRITTEN + 8))
        ok.append((sb_off + SB_CHECKSUM, sb_off + SB_CHECKSUM + 4))
        if shortened:
            # s_r_blocks_count, the five per cent held back for root, and only on
            # a filesystem that had to be shortened. Everywhere else the two
            # agree exactly; there mke2fs lands within a block of us and does not
            # do it consistently - measured at 1 KiB blocks, filesystems of
            # 32769 blocks arrived at from files of 32772, 32790, 32850, 32900
            # and 33000 blocks get 1637, 1638, 1638, 1638, 1638 while the plain
            # reading of "five per cent of 32769" is 1638 throughout. So the
            # number depends on how mke2fs got there rather than on where it
            # ended up, which is not a property of the format and not one to
            # reproduce. Nothing reads this field for correctness.
            ok.append((sb_off + SB_R_BLOCKS_LO, sb_off + SB_R_BLOCKS_LO + 4))
            ok.append((sb_off + SB_R_BLOCKS_HI, sb_off + SB_R_BLOCKS_HI + 4))
        # The descriptors themselves are compared field by field above; only the
        # padding after them stays under byte comparison.
        gdt_off = (sb.group_start(g) + 1) * bs
        ok.append((gdt_off, gdt_off + sb.groups * sb.desc_size))

    ref_d = read_descs(ref, sb)
    for g, flags in uninit.items():
        if flags & BG_BLOCK_UNINIT:
            blk = desc_field(ref_d, sb, g, "block_bitmap")
            ok.append((blk * bs, (blk + 1) * bs))
        if flags & BG_INODE_UNINIT:
            blk = desc_field(ref_d, sb, g, "inode_bitmap")
            ok.append((blk * bs, (blk + 1) * bs))
    return ok


def byte_diff(ref, ours, sb, allowed, problems):
    a = open(ref, "rb").read()
    b = open(ours, "rb").read()
    if len(a) != len(b):
        problems.append(f"image is {len(b)} bytes, mke2fs made {len(a)}")
        return
    permitted = sorted(allowed)

    def is_allowed(pos):
        for s, e in permitted:
            if s <= pos < e:
                return True
        return False

    reported = 0
    i, n = 0, len(a)
    while i < n and reported < 6:
        if a[i] != b[i] and not is_allowed(i):
            j = i
            while j < n and a[j] != b[j]:
                j += 1
            bs = sb.block_size
            problems.append(
                f"block {i // bs} offset {i % bs}: {j - i} bytes differ - "
                f"mke2fs {a[i:i + 8].hex()}, ours {b[i:i + 8].hex()}")
            reported += 1
            i = j
        else:
            i += 1


def fsck_clean(img, problems, what):
    rc, lines, _ = fsck(img)
    if rc != 0:
        problems.append(f"e2fsck rejects the image {what} (rc={rc})")
    for line in lines:
        if not BENIGN_REMARK.match(line) and not line.startswith("Pass ") \
                and not line.startswith("e2fsck "):
            problems.append(f"e2fsck says, {what}: {line}")


def check_geometry(tools, size_bytes, block_size, verbose):
    """One (size, block size) pair, all the way up the ladder."""
    mkfs, bench, dirwrite, extwrite, fsmeta = tools
    problems = []

    with tempfile.TemporaryDirectory() as tmp:
        ref = os.path.join(tmp, "ref.img")
        ours = os.path.join(tmp, "ours.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)

        sh("truncate", "-s", str(size_bytes), ref)
        r = sh("mkfs.ext4", "-q", "-F", "-t", "ext4", "-O", FEATURES,
               "-b", str(block_size), "-I", "256", ref)
        if r.returncode != 0:
            return [f"the reference could not be made: {r.stderr.strip()[:200]}"]

        sb = Superblock(ref)
        sh("truncate", "-s", str(size_bytes), ours)
        r = sh(mkfs, ours,
               "--blocks", str(sb.blocks_count), "--bs", str(sb.block_size),
               "--inodes", str(sb.inodes_count), "--isize", str(sb.inode_size),
               "--when", str(sb.mkfs_time), "--uuid", sb.uuid,
               "--hash-seed", sb.hash_seed)
        if r.returncode != 0:
            return [f"our formatter failed: {r.stderr.strip()[:200]}"]

        # How many blocks the filesystem ends up with is a decision, not a given:
        # a size whose last group cannot pay for its own bitmaps and inode table
        # ends before that group. mke2fs makes the same decision, so handing our
        # formatter the raw count and requiring it to land exactly where mke2fs
        # landed is what checks the rule - the run above cannot, because it is
        # handed the already-decided number.
        raw_blocks = size_bytes // block_size
        derived = os.path.join(tmp, "derived.img")
        sh("truncate", "-s", str(size_bytes), derived)
        d = sh(mkfs, derived, "--blocks", str(raw_blocks), "--bs", str(block_size),
               "--inodes", str(sb.inodes_count), "--isize", str(sb.inode_size),
               "--when", str(sb.mkfs_time), "--uuid", sb.uuid,
               "--hash-seed", sb.hash_seed)
        if d.returncode != 0:
            problems.append(f"formatting {raw_blocks} raw blocks failed: "
                            f"{d.stderr.strip()[:200]}")
        else:
            got = int(d.stdout.split()[0])
            if got != sb.blocks_count:
                problems.append(f"given {raw_blocks} blocks we made a filesystem of "
                                f"{got}, mke2fs made {sb.blocks_count}")

        # Rung one: the same filesystem, not merely a valid one.
        shortened = raw_blocks != sb.blocks_count
        uninit = compare_descriptors(ref, ours, sb, problems)
        byte_diff(ref, ours, sb, allowed_ranges(ref, sb, uninit, shortened), problems)
        compare_descriptor_copies(ours, sb, problems, "our")
        compare_descriptor_copies(ref, sb, problems, "mke2fs's")

        ref_backups = backup_groups(ref, sb)
        our_backups = backup_groups(ours, sb)
        if ref_backups != our_backups:
            missing = sorted(ref_backups - our_backups)[:5]
            extra = sorted(our_backups - ref_backups)[:5]
            problems.append(f"backup superblocks are in the wrong groups: "
                            f"missing {missing}, unexpected {extra}")

        # Rung two: e2fsck, on ours and on the reference, must say the same thing.
        fsck_clean(ours, problems, "as formatted")
        ref_rc, ref_lines, _ = fsck(ref)
        our_rc, our_lines, _ = fsck(ours)
        if (ref_rc, ref_lines) != (our_rc, our_lines):
            problems.append("e2fsck does not say the same of ours as of mke2fs's")

        # Rung three: another driver mounts it read-write and uses it.
        proc = mount_fuse(ours, mnt)
        if proc:
            with open(os.path.join(mnt, "from-fuse2fs.txt"), "w") as f:
                f.write("written by another driver\n")
            os.makedirs(os.path.join(mnt, "subdir"), exist_ok=True)
            sh("sync")
            if not unmount_fuse(mnt, proc):
                problems.append("fuse2fs would not unmount")
            fsck_clean(ours, problems, "after fuse2fs wrote to it")
            listing = sh(bench, ours, "2", "--ls").stdout.split()
            for name in ("from-fuse2fs.txt", "subdir", "lost+found"):
                if name not in listing:
                    problems.append(f"we cannot see {name} in a filesystem we made")
        else:
            problems.append("fuse2fs would not mount a filesystem we formatted")

        # Rung four: our own code, inside our own filesystem. A directory as well
        # as a file - the root this formatter built is the first thing mkdir ever
        # attaches to, and the link count it has to raise is one this wrote.
        r = sh(dirwrite, ours, "2", "mkdir", "made-here", str(WHEN))
        if r.returncode != 0:
            problems.append(f"we could not make a directory in it: {r.stderr.strip()}")
        else:
            sub = r.stdout.strip()
            if sh(dirwrite, ours, sub, "create", "nested.txt", str(WHEN)).returncode:
                problems.append("we could not create a file inside that directory")
            fsck_clean(ours, problems, "after making a directory in it")

        r = sh(dirwrite, ours, "2", "create", "from-arcanum.txt", str(WHEN))
        if r.returncode != 0:
            problems.append(f"we could not create a file in it: {r.stderr.strip()}")
        else:
            ino = int(r.stdout.strip())
            a = sh(extwrite, ours, str(ino), "append", "4")
            if a.returncode not in (0, 3):
                problems.append(f"we could not write to that file: {a.stderr.strip()}")
            if sh(fsmeta, ours).returncode != 0:
                problems.append("our own checksums do not verify after writing")
            fsck_clean(ours, problems, "after our own writes")

    if verbose and not problems:
        print(f"ok   {size_bytes // (1024 * 1024)}M at {block_size} "
              f"({sb.groups} groups, {sb.inodes_count} inodes)")
    return problems


def check_random_media(tools, size_bytes, block_size):
    """Formatting over random bytes, which is what a container really is.

    A sparse file reads as zeroes, so every rung above would still pass if the
    formatter quietly depended on that. Here it cannot: anything left unwritten
    keeps whatever noise was in it.
    """
    mkfs = tools[0]
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "random.img")
        with open(img, "wb") as f:
            f.write(os.urandom(size_bytes))
        r = sh(mkfs, img, "--bs", str(block_size), "--when", str(WHEN),
               "--uuid", "00112233445566778899aabbccddeeff",
               "--hash-seed", "ffeeddccbbaa99887766554433221100")
        if r.returncode != 0:
            return [f"formatting over random media failed: {r.stderr.strip()[:200]}"]

        fsck_clean(img, problems, "formatted over random bytes")

        # The boot block holds nothing and nothing reads it, so no checker will
        # ever object to what is in it - which is exactly why it has to be
        # asserted here. A thousand bytes of the previous contents left where a
        # boot sector belongs is the kind of residue a container should not carry,
        # and over a sparse file it is invisible because the file reads as zeroes.
        if block_size == 1024:
            with open(img, "rb") as f:
                if any(f.read(1024)):
                    problems.append("the boot block still holds what was there "
                                    "before formatting")

        # The same argument for the tail of the last descriptor block. The
        # descriptors rarely fill it, and what follows them is part of a metadata
        # block - writing only as far as the last descriptor leaves the rest of
        # that block holding the medium's previous contents. Invisible to every
        # checker, and invisible over a sparse file too, which is why it is
        # asserted here and only here.
        sb = Superblock(img)
        desc_bytes = sb.groups * sb.desc_size
        gdt_blocks = -(-desc_bytes // sb.block_size)
        with open(img, "rb") as f:
            f.seek((sb.first_data_block + 1) * sb.block_size + desc_bytes)
            if any(f.read(gdt_blocks * sb.block_size - desc_bytes)):
                problems.append("the tail of the last group descriptor block still "
                                "holds what was there before formatting")

        # And the tail of the inode table must still be noise. If it were zeroed,
        # this rung would be proving something easier than what ships.
        descs = read_descs(img, sb)
        itable = desc_field(descs, sb, 0, "inode_table")
        unused = desc_field(descs, sb, 0, "itable_unused")
        used = sb.inodes_per_group - unused
        with open(img, "rb") as f:
            f.seek(itable * sb.block_size + used * sb.inode_size)
            tail = f.read(sb.inode_size * 16)
        if not any(tail):
            problems.append("the inode table tail was zeroed, so this rung is not "
                            "testing what bg_itable_unused promises")
    return problems


def parse_size(text):
    mult = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}
    if text and text[-1].upper() in mult:
        return int(text[:-1]) * mult[text[-1].upper()]
    return int(text)


# Sizes chosen for the shapes they produce, not for coverage of numbers: a single
# group, several whole ones, a partial last group, and enough groups that the
# sparse_super rule has to pick out 0, 1, 3, 5, 7, 9, 25, 27 rather than "the odd
# ones".
DEFAULT_SWEEP = [
    ("8M", 1024),
    ("32M", 1024),
    ("33M", 1024),
    # 32772 blocks: four whole groups and three blocks left over, which is not
    # enough for a fifth group's own bitmaps. Both formatters have to end the
    # filesystem at 32769 rather than describe a group nothing can use.
    ("33558528", 1024),
    ("300M", 1024),
    ("64M", 2048),
    ("140M", 2048),
    ("64M", 4096),
    ("256M", 4096),
]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--extwrite", default=os.path.join(here, "extwrite"))
    ap.add_argument("--fsmeta", default=os.path.join(here, "fsmeta"))
    ap.add_argument("--geometry", action="append",
                    help="SIZE:BLOCKSIZE, repeatable; replaces the default sweep")
    ap.add_argument("--no-random", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.mkfs, args.bench, args.dirwrite, args.extwrite, args.fsmeta)
    for t in tools:
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    if not shutil.which("fuse2fs"):
        sys.exit("fuse2fs not found - it is the only rung here that is not us "
                 "checking ourselves")

    sweep = DEFAULT_SWEEP
    if args.geometry:
        sweep = []
        for g in args.geometry:
            size, _, bs = g.partition(":")
            sweep.append((size, int(bs or 1024)))

    failed = 0
    for size, bs in sweep:
        problems = check_geometry(tools, parse_size(size), bs, args.verbose)
        if problems:
            failed += 1
            print(f"FAIL {size} at block size {bs}")
            for p in problems:
                print(f"     {p}")

    if not args.no_random:
        for size, bs in (("32M", 1024), ("64M", 4096)):
            problems = check_random_media(tools, parse_size(size), bs)
            if problems:
                failed += 1
                print(f"FAIL {size} at block size {bs}, over random media")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {size} at {bs} over random media")

    print(f"\n{len(sweep)} geometries compared against mke2fs, mounted by fuse2fs "
          f"and written to by our own code, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
