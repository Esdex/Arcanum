#!/usr/bin/env bash
# Measures mkfscheck.py, not the formatter.
#
#   ./mutants-mkfs.sh
#
# A green harness means nothing until a deliberately broken formatter is shown to
# fail it. This one has an unusually strong oracle - the image is compared against
# mke2fs's byte for byte - so the interesting question is not whether a broken
# layout is caught but whether anything is being let through by the list of
# differences the comparison permits. Every mutant below that lands inside one of
# those permitted regions is the one that matters.
#
# The geometries are chosen per mutant rather than run in full each time. Three
# groups need saying out loud:
#
#   33M at 1024   the default. Five groups, backups in 0, 1 and 3, groups mke2fs
#                 left uninitialised, and a last group that stops part way - the
#                 only shape in which bitmap padding exists at all.
#   64M at 4096   first_data_block is 0 and the superblock lives inside block 0
#                 rather than owning a block, which is a different write.
#   300M at 1024  38 groups. Below twelve, every odd group is a power of 3, 5 or
#                 7, so "backups in the odd groups" and the real sparse_super rule
#                 agree and a mutant swapping one for the other passes.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" mkfs.c

fail=0

# try <desc> <sed-expr> [geometry-args] [why-untestable]
try() {
    local desc="$1" expr="$2" geom="${3:---geometry 33M:1024 --no-random}"
    local expect_miss="${4:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_mkfs.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" mkfs.c mk; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    # shellcheck disable=SC2086
    if "$HERE/mkfscheck.py" $geom --mkfs "$WORK/mk" --bench "$HERE/bench" \
                            --dirwrite "$HERE/dirwrite" --extwrite "$HERE/extwrite" \
                            --fsmeta "$HERE/fsmeta" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"; echo "              $expect_miss"
        else
            echo "  MISS  $desc - the harness did not catch it"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the harness caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "formatter mutation tests (each should read caught, or untestable with a reason):"

# ── bitmaps ──────────────────────────────────────────────────────────────────
# The padding mutants only exist as failures on a group that stops part way, and
# on an inode bitmap they are always reachable: inodes_per_group never fills the
# block its bitmap gets.

try "blocks past the end of the last group left free in the bitmap" \
    's@    for (uint32_t b = gb; b < m->blocks_per_group; b++) set_bit(m->blk, b);@@'

try "inode bitmap padding left clear" \
    's@    for (uint32_t i = m->inodes_per_group; i < m->block_size \* 8; i++) set_bit(m->blk, i);@@'

try "the group's own metadata not marked as used" \
    's@    for (uint32_t b = 0; b < used; b++) set_bit(m->blk, b);@@'

try "the eleven reserved inodes not marked as used" \
    's@    for (uint32_t i = 0; i < used_inodes; i++) set_bit(m->blk, i);@@'

try "inode bitmap checksummed over the whole block instead of the real inodes" \
    's@ext4_bitmap_csum(m->csum_seed, m->blk, m->inodes_per_group / 8)@ext4_bitmap_csum(m->csum_seed, m->blk, m->block_size)@'

try "root directory and lost+found left out of the used run" \
    's@    return group_meta_blocks(m, g) + (g == 0 ? 1 + m->lpf_blocks : 0);@    return group_meta_blocks(m, g);@'

# ── group descriptors ────────────────────────────────────────────────────────

try "inode table recorded one block past where it is" \
    's@static uint64_t group_itable(const mkfs \*m, uint32_t g)  { return group_bbitmap(m, g) + 2; }@static uint64_t group_itable(const mkfs *m, uint32_t g)  { return group_bbitmap(m, g) + 3; }@'

try "free block count does not subtract what the group spent" \
    's@EXT4_GD_FREE_BLOCKS_HI_OFF,  gb - used@EXT4_GD_FREE_BLOCKS_HI_OFF,  gb@'

try "free inode count does not subtract the reserved inodes" \
    's@    uint32_t free_inodes = m->inodes_per_group - used_inodes;@    uint32_t free_inodes = m->inodes_per_group;@'

try "the two directories not counted in bg_used_dirs_count" \
    's@GD_USED_DIRS_HI_OFF, (g == 0) ? 2 : 0@GD_USED_DIRS_HI_OFF, 0@'

try "bg_itable_unused counts the reserved inodes as never used" \
    's@    uint32_t never_used  = m->inodes_per_group - used_inodes;@    uint32_t never_used  = m->inodes_per_group;@'

try "descriptor left without a checksum" \
    's@    store_desc_csum(m, g);@@'

try "descriptor checksummed over the classic 32 bytes instead of all 64" \
    's@ext4_group_desc_csum(m->csum_seed, g, d, EXT4_DESC_SIZE)@ext4_group_desc_csum(m->csum_seed, g, d, 32)@'

# ── inodes ───────────────────────────────────────────────────────────────────
# Root has three links, not two: its own name, its own "..", and the ".." inside
# lost+found. That third one is the easy one to lose.

try "root directory given two links instead of three" \
    's@init_dir_inode(m, inode, 0755, 3, m->root_block, 1);@init_dir_inode(m, inode, 0755, 2, m->root_block, 1);@'

try "lost+found given one link instead of two" \
    's@init_dir_inode(m, inode, 0700, 2, m->lpf_block, m->lpf_blocks);@init_dir_inode(m, inode, 0700, 1, m->lpf_block, m->lpf_blocks);@'

try "i_blocks counted in filesystem blocks rather than 512-byte units" \
    's@(uint32_t)((uint64_t)nblocks \* m->block_size / 512))@(uint32_t)((uint64_t)nblocks))@'

try "directory inode not marked as using extents" \
    's@    wr32(inode + INODE_FLAGS_OFF, EXT4_INODE_FLAG_EXTENTS);@@'

try "the extent claims one block however many the directory has" \
    's@    wr16(e + 4, (uint16_t)nblocks);@    wr16(e + 4, 1);@'

try "i_extra_isize left zero, so the checksum covers the wrong half" \
    's@        wr16(inode + INODE_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);@@'

try "the reserved inodes never written, so their checksums are absent" \
    's@    rc = write_reserved_inodes(&m);@    rc = EXT4_MKFS_OK;@'

try "inode 1 left without the creation time mke2fs stamps into it" \
    's@        if (ino == 1) {@        if (0) {@'

# ── the two directories ──────────────────────────────────────────────────────

try "root left with no entry for lost+found" \
    's@    put_entry(m->blk + off, LPF_INO, limit - off, "lost+found");@@'

try "the last entry does not stretch to the end of the block" \
    's@    put_entry(m->blk + off, LPF_INO, limit - off, "lost+found");@    put_entry(m->blk + off, LPF_INO, 20, "lost+found");@'

try "directory blocks left without their checksum tail" \
    's@    dir_tail(m, root_seed);@@'

try "the empty blocks of lost+found left unformatted" \
    's@    for (uint32_t b = 1; b < m->lpf_blocks; b++) {@    for (uint32_t b = 1; b < 1; b++) {@'

try "lost+found made its own parent" \
    's@    put_entry(m->blk + off, ROOT_INO, limit - off, "\.\.");@    put_entry(m->blk + off, LPF_INO, limit - off, "..");@'

# ── superblock ───────────────────────────────────────────────────────────────

try "checksum seed derived from half the UUID" \
    's@ext4_crc32c(0xFFFFFFFFu, p->uuid, 16)@ext4_crc32c(0xFFFFFFFFu, p->uuid, 8)@'

try "s_first_ino says the reserved range ends a slot late" \
    's@    wr32(sb + SB_FIRST_INO_OFF, RESERVED_INODES);@    wr32(sb + SB_FIRST_INO_OFF, RESERVED_INODES + 1);@'

try "s_free_inodes_count does not subtract the reserved inodes" \
    's@    wr32(sb + EXT4_SB_FREE_INODES_OFF, m->inodes_count - RESERVED_INODES);@    wr32(sb + EXT4_SB_FREE_INODES_OFF, m->inodes_count);@'

try "the free block count left as it was before the bitmaps were counted" \
    's@    wr32(m.sb + EXT4_SB_FREE_BLOCKS_LO_OFF, (uint32_t)m.free_blocks);@@'

try "s_overhead_clusters left zero, so df would overstate the container" \
    's@    wr32(sb + SB_OVERHEAD_CLUSTERS_OFF, (uint32_t)overhead);@@'

try "a filesystem whose last group cannot pay for itself is kept anyway" \
    's@        if (group_blocks(m, last) >= group_used_blocks(m, last)) break;@        break;@' \
    "--geometry 33558528:1024 --no-random"

# ── superblock copies ────────────────────────────────────────────────────────

try "a backup superblock in every group" \
    's@static int has_super(uint32_t g) {@static int has_super(uint32_t g) { return 1;@'

try "backups in the odd groups rather than the powers of 3, 5 and 7" \
    's@    return is_power_of(g, 3) || is_power_of(g, 5) || is_power_of(g, 7);@    return 1;@' \
    "--geometry 300M:1024 --no-random"

try "a backup superblock that does not record which group it is in" \
    's@    wr16(m->sb + SB_BLOCK_GROUP_NR_OFF, (uint16_t)g);@@'

# The one mutant the byte comparison cannot see. It has to excuse the descriptor
# bytes in order to compare them field by field instead, and that excuses them in
# the backups too; e2fsck is no help because it never reads a backup. Only the
# check that each copy equals the primary catches this.

try "the descriptor table never copied into the backup groups" \
    's@    if (ext4_io_pwrite(m->io, (group_start(m, g) + 1) \* (uint64_t)m->block_size,@    if (g == 0 \&\& ext4_io_pwrite(m->io, (group_start(m, g) + 1) * (uint64_t)m->block_size,@'

try "every superblock copy claims to be cleanly unmounted" \
    's@    wr16(m->sb + SB_STATE_OFF, g == 0 ? EXT4_STATE_CLEAN : 0);@@'

try "the superblock written where a 4 KiB filesystem does not keep it" \
    's@    uint32_t inpart = (g == 0 && m->block_size > 1024) ? 1024 : 0;@    uint32_t inpart = 0;@' \
    "--geometry 64M:4096 --no-random"

# Both of the last two are invisible to every checker and invisible over a sparse
# file, where the medium reads as zeroes whether or not anything wrote there. Only
# the run over random media can see them, which is what that rung is for.

try "the descriptor table written without its zero padding" \
    's@                       m->desc, (size_t)m->gdt_blocks \* m->block_size)@                       m->desc, (size_t)m->groups * EXT4_DESC_SIZE)@' \
    "--geometry 32M:1024"

try "the boot block left holding whatever the medium had in it" \
    's@    if (m.block_size == 1024) {@    if (0) {@' \
    "--geometry 8M:1024"

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken formatter passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
