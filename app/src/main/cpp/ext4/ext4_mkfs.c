/*
 * Making an ext4 filesystem out of nothing.
 *
 * Clean-room, from the published on-disk format. Every structure written here has
 * a reader or a writer elsewhere in this directory that was checked against
 * e2fsprogs first, so this file is assembly plus the layout arithmetic - and the
 * arithmetic is the part with nowhere to hide, because a filesystem laid out
 * wrongly is wrong before anything has been stored in it.
 *
 * Two decisions differ from what mke2fs produces, both deliberate:
 *
 *   Every group is initialised. mke2fs marks groups BLOCK_UNINIT / INODE_UNINIT
 *   and leaves their bitmaps unwritten, to be derived on first use. The allocator
 *   here refuses such groups - synthesising a bitmap is its own piece of work -
 *   so a container formatted that way would have most of its space unreachable by
 *   the code that has to fill it. Writing two bitmap blocks per group at creation
 *   costs one write each and removes the whole question.
 *
 *   The inode tables are not zeroed. bg_itable_unused declares how many inodes at
 *   the end of a group's table have never been used, and e2fsck honours it: an
 *   image whose table tail is filled with random bytes passes -fn cleanly. The
 *   inode allocator already zeroes what it takes out of that region, which is the
 *   same promise kept from the other end. Zeroing here would mean writing tens of
 *   megabytes through the container's cipher that nothing will ever read.
 *
 * Everything else this touches it writes in full, including the padding at the
 * end of a bitmap and the blocks a backup superblock sits in. A container's
 * medium is random data, not zeroes, so "we never wrote there" and "there is
 * nothing there" are not the same statement.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_mkfs.h"
#include "ext4_alloc.h"    /* the on-disk offsets, shared rather than repeated */
#include "ext4_csum.h"
#include "ext4_dir.h"      /* EXT4_FT_DIR, EXT4_FT_DIR_CSUM */
#include "ext4_log.h"

#include <stdlib.h>
#include <string.h>

/* Superblock fields only the formatter writes, so they live here rather than in
 * the header every other module includes. */
#define SB_R_BLOCKS_LO_OFF      0x08
#define SB_LOG_CLUSTER_SIZE_OFF 0x1C
#define SB_CLUSTERS_PER_GRP_OFF 0x24
#define SB_WTIME_OFF            0x30
#define SB_MAX_MNT_COUNT_OFF    0x36
#define SB_MAGIC_OFF            0x38
#define SB_STATE_OFF            0x3A
#define SB_ERRORS_OFF           0x3C
#define SB_LASTCHECK_OFF        0x40
#define SB_REV_LEVEL_OFF        0x4C
#define SB_FIRST_INO_OFF        0x54
#define SB_BLOCK_GROUP_NR_OFF   0x5A
#define SB_FEATURE_COMPAT_OFF   0x5C
#define SB_FEATURE_RO_COMPAT_OFF 0x64
#define SB_UUID_OFF             0x68
#define SB_HASH_SEED_OFF        0xEC
#define SB_DEF_HASH_VERSION_OFF 0xFC
#define SB_DEF_MOUNT_OPTS_OFF   0x100
#define SB_MKFS_TIME_OFF        0x108
#define SB_OVERHEAD_CLUSTERS_OFF 0x248
#define SB_R_BLOCKS_HI_OFF      0x154
#define SB_MIN_EXTRA_ISIZE_OFF  0x15C
#define SB_WANT_EXTRA_ISIZE_OFF 0x15E
#define SB_FLAGS_OFF            0x160
#define SB_CSUM_TYPE_OFF        0x175

#define INODE_MODE_OFF        0x00
#define INODE_SIZE_LO_OFF     0x04
#define INODE_ATIME_OFF       0x08
#define INODE_CTIME_OFF       0x0C
#define INODE_MTIME_OFF       0x10
#define INODE_LINKS_COUNT_OFF 0x1A
#define INODE_BLOCKS_LO_OFF   0x1C
#define INODE_FLAGS_OFF       0x20
#define INODE_IBLOCK_OFF      0x28
#define INODE_EXTRA_ISIZE_OFF 0x80
#define INODE_CRTIME_OFF      0x90

#define EXT4_MAGIC              0xEF53
#define EXT4_STATE_CLEAN        1
#define EXT4_ERRORS_CONTINUE    1
#define EXT4_REV_DYNAMIC        1
#define EXT4_GOOD_EXTRA_ISIZE   32
#define EXT4_INODE_FLAG_EXTENTS 0x00080000u
#define EXT4_EXTENT_MAGIC       0xF30A
#define EXT4_S_IFDIR            0x4000
#define EXT4_DESC_SIZE          64
#define DIR_TAIL_SIZE           12

/*
 * The feature set, chosen and justified in interopcheck.py: no journal, no
 * dir_index, and - so that the layout stays the simple one this code and fuse2fs
 * both handle - no flex_bg, resize_inode or orphan_file either. Dropping
 * resize_inode is also what makes s_reserved_gdt_blocks zero, which removes a
 * whole reserved region from the arithmetic below.
 */
#define FEATURE_COMPAT    0x0008u   /* ext_attr */
#define FEATURE_INCOMPAT  0x20C2u   /* filetype | extents | 64bit | csum_seed */
#define FEATURE_RO_COMPAT 0x046Bu   /* sparse_super | large_file | huge_file |
                                     * dir_nlink | extra_isize | metadata_csum */

/* Inodes 1..10 are spoken for by the format; 11 is lost+found. So eleven are in
 * use the moment the filesystem exists, and s_first_ino - the first one a file
 * may be given - is 11 even though lost+found holds it. */
#define RESERVED_INODES 11
#define ROOT_INO         2
#define LPF_INO         11

/* lost+found is sized the way mke2fs sizes it: 16 KiB, capped at twelve blocks.
 * It is not required - e2fsck is clean on a filesystem with no lost+found at all,
 * which was measured rather than assumed - but a container that is repaired on a
 * desktop needs somewhere for the orphans to go, and the space is trivial. */
#define LPF_TARGET_BYTES 16384
#define LPF_MAX_BLOCKS      12

static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

typedef struct {
    ext4_io *io;
    uint32_t block_size;
    uint32_t first_data_block;
    uint32_t blocks_per_group;
    uint32_t groups;
    uint32_t gdt_blocks;
    uint32_t inodes_per_group;
    uint32_t itable_blocks;
    uint32_t inode_size;
    uint32_t inodes_count;
    uint32_t csum_seed;
    uint32_t lpf_blocks;
    uint32_t when;
    uint64_t blocks_count;
    uint64_t root_block;
    uint64_t lpf_block;
    uint64_t free_blocks;      /* summed as the bitmaps are written */
    uint8_t  sb[1024];
    uint8_t *desc;             /* gdt_blocks * block_size, zero-padded */
    uint8_t *blk;              /* one block of scratch */
} mkfs;

/* ── layout ───────────────────────────────────────────────────────────────── */

static int is_power_of(uint32_t n, uint32_t base) {
    while (n > 1) {
        if (n % base) return 0;
        n /= base;
    }
    return n == 1;
}

/*
 * sparse_super: a backup superblock and descriptor table go in groups 0 and 1 and
 * in every group whose number is a power of 3, 5 or 7 - so 0, 1, 3, 5, 7, 9, 25,
 * 27, 49, 81... Every one of those past 1 is odd, which is worth an early exit on
 * a filesystem with thousands of groups.
 */
static int has_super(uint32_t g) {
    if (g <= 1) return 1;
    if ((g & 1) == 0) return 0;
    return is_power_of(g, 3) || is_power_of(g, 5) || is_power_of(g, 7);
}

static uint64_t group_start(const mkfs *m, uint32_t g) {
    return (uint64_t)m->first_data_block + (uint64_t)g * m->blocks_per_group;
}

/* Every group holds blocks_per_group except the last, which stops where the
 * filesystem does. */
static uint32_t group_blocks(const mkfs *m, uint32_t g) {
    uint64_t remain = m->blocks_count - group_start(m, g);
    return remain < m->blocks_per_group ? (uint32_t)remain : m->blocks_per_group;
}

/* The group's own metadata, always a run starting at its first block: the backup
 * superblock and descriptors where there is one, then the two bitmaps and the
 * inode table. */
static uint32_t group_meta_blocks(const mkfs *m, uint32_t g) {
    return (has_super(g) ? 1 + m->gdt_blocks : 0) + 2 + m->itable_blocks;
}

/* Group 0 carries the root directory and lost+found immediately after its inode
 * table, so its used run is longer. Nothing else is allocated at format time. */
static uint32_t group_used_blocks(const mkfs *m, uint32_t g) {
    return group_meta_blocks(m, g) + (g == 0 ? 1 + m->lpf_blocks : 0);
}

static uint64_t group_bbitmap(const mkfs *m, uint32_t g) {
    return group_start(m, g) + (has_super(g) ? 1 + m->gdt_blocks : 0);
}
static uint64_t group_ibitmap(const mkfs *m, uint32_t g) { return group_bbitmap(m, g) + 1; }
static uint64_t group_itable(const mkfs *m, uint32_t g)  { return group_bbitmap(m, g) + 2; }

static uint8_t *group_desc(const mkfs *m, uint32_t g) {
    return m->desc + (size_t)g * EXT4_DESC_SIZE;
}

static void set_bit(uint8_t *map, uint32_t bit) {
    map[bit >> 3] |= (uint8_t)(1u << (bit & 7));
}

/*
 * Works out how many groups there are, how big the inode table is and where
 * everything lands.
 *
 * The loop exists for one case: a filesystem whose size leaves a final group too
 * small to hold even its own bitmaps and inode table. Such a group cannot be used
 * for anything, so the filesystem is shortened to end before it - which changes
 * the group count, which changes the descriptor table, which can in principle
 * change the answer again. It converges downwards, and the group count is the
 * measure that makes that true.
 */
static int compute_geometry(mkfs *m, const ext4_mkfs_params *p) {
    if (p->block_size != 1024 && p->block_size != 2048 && p->block_size != 4096)
        return EXT4_MKFS_ERR_PARAM;
    if (p->inode_size < 128 || p->inode_size > p->block_size ||
        (p->inode_size & (p->inode_size - 1)))
        return EXT4_MKFS_ERR_PARAM;
    if (p->inodes_count == 0) return EXT4_MKFS_ERR_PARAM;

    m->block_size       = p->block_size;
    m->inode_size       = p->inode_size;
    m->when             = p->when;
    m->blocks_count     = p->blocks_count;
    m->blocks_per_group = p->block_size * 8;
    /* Block 0 is the boot block at 1 KiB blocks; at anything larger the
     * superblock's 1024-byte offset lands inside block 0 and there is no block to
     * skip. */
    m->first_data_block = (p->block_size == 1024) ? 1 : 0;

    m->lpf_blocks = LPF_TARGET_BYTES / p->block_size;
    if (m->lpf_blocks > LPF_MAX_BLOCKS) m->lpf_blocks = LPF_MAX_BLOCKS;
    if (m->lpf_blocks == 0) m->lpf_blocks = 1;

    if (m->blocks_count <= m->first_data_block) return EXT4_MKFS_ERR_SMALL;

    for (;;) {
        uint64_t usable = m->blocks_count - m->first_data_block;
        m->groups = (uint32_t)((usable + m->blocks_per_group - 1) / m->blocks_per_group);
        if (m->groups == 0) return EXT4_MKFS_ERR_SMALL;

        m->gdt_blocks = (uint32_t)(((uint64_t)m->groups * EXT4_DESC_SIZE +
                                    m->block_size - 1) / m->block_size);

        /* Inodes are shared out evenly and a group's share has to be a whole
         * number of bitmap bytes, and cannot outgrow the one block its bitmap
         * gets. */
        uint64_t per = ((uint64_t)p->inodes_count + m->groups - 1) / m->groups;
        per = (per + 7) & ~(uint64_t)7;
        if (per == 0) per = 8;
        if (per > (uint64_t)m->block_size * 8) per = (uint64_t)m->block_size * 8;
        if (per * m->groups > 0xFFFFFFFFu) return EXT4_MKFS_ERR_PARAM;

        m->inodes_per_group = (uint32_t)per;
        m->inodes_count     = (uint32_t)(per * m->groups);
        m->itable_blocks    = (uint32_t)((per * m->inode_size + m->block_size - 1) /
                                         m->block_size);

        uint32_t last = m->groups - 1;
        if (group_blocks(m, last) >= group_used_blocks(m, last)) break;

        /* The last group cannot pay for itself. End the filesystem before it and
         * work the whole layout out again. */
        if (m->groups == 1) return EXT4_MKFS_ERR_SMALL;
        EXT4_LOGI("mkfs: last group holds %u blocks but needs %u; shortening the "
                  "filesystem by that group", group_blocks(m, last),
                  group_used_blocks(m, last));
        m->blocks_count = group_start(m, last);
    }

    /*
     * Group 0 is the strictest of all the groups that are not the last one: it
     * carries a superblock copy and the descriptor table like every backup group
     * does, and the root directory and lost+found on top. So a filesystem where
     * group 0 fits is one where every whole group fits, and between this and the
     * loop above every group has been accounted for.
     */
    if (group_blocks(m, 0) < group_used_blocks(m, 0)) return EXT4_MKFS_ERR_SMALL;

    m->root_block = group_start(m, 0) + group_meta_blocks(m, 0);
    m->lpf_block  = m->root_block + 1;
    return EXT4_MKFS_OK;
}

/* ── bitmaps and group descriptors ────────────────────────────────────────── */

static void store_desc_csum(const mkfs *m, uint32_t g) {
    uint8_t *d = group_desc(m, g);
    wr16(d + EXT4_GD_CSUM_OFF, 0);
    wr16(d + EXT4_GD_CSUM_OFF,
         (uint16_t)ext4_group_desc_csum(m->csum_seed, g, d, EXT4_DESC_SIZE));
}

static void put64(uint8_t *d, uint32_t lo_off, uint32_t hi_off, uint64_t v) {
    wr32(d + lo_off, (uint32_t)v);
    wr32(d + hi_off, (uint32_t)(v >> 32));
}
static void put32(uint8_t *d, uint32_t lo_off, uint32_t hi_off, uint32_t v) {
    wr16(d + lo_off, (uint16_t)v);
    wr16(d + hi_off, (uint16_t)(v >> 16));
}

/*
 * Writes both bitmaps for one group and fills in the descriptor that owns them.
 *
 * Two kinds of bit are set: the ones covering blocks the group has actually spent
 * on metadata, and the padding at the end. The padding matters on the last group,
 * whose bitmap has a bit for every block the group *would* hold - e2fsck checks
 * that the bits past the end of the filesystem are set, and a reader that trusted
 * a clear one would hand out a block that is not there.
 *
 * The same is true of the inode bitmap, which covers inodes_per_group inodes in a
 * block with room for eight times its size in bits. Everything past the last real
 * inode is padding and has to read as used. Note that only the real part is
 * checksummed - the two lengths are different on purpose and getting them the same
 * way round is what makes the checksum agree with the allocator's.
 */
static int write_group(mkfs *m, uint32_t g) {
    uint32_t gb   = group_blocks(m, g);
    uint32_t used = group_used_blocks(m, g);
    if (used > gb) return EXT4_MKFS_ERR_SMALL;

    uint8_t *d = group_desc(m, g);

    memset(m->blk, 0, m->block_size);
    for (uint32_t b = 0; b < used; b++) set_bit(m->blk, b);
    for (uint32_t b = gb; b < m->blocks_per_group; b++) set_bit(m->blk, b);
    if (ext4_io_pwrite(m->io, group_bbitmap(m, g) * (uint64_t)m->block_size,
                       m->blk, m->block_size))
        return EXT4_MKFS_ERR_IO;
    put32(d, EXT4_GD_BBITMAP_CSUM_LO_OFF, EXT4_GD_BBITMAP_CSUM_HI_OFF,
          ext4_bitmap_csum(m->csum_seed, m->blk, m->blocks_per_group / 8));

    uint32_t used_inodes = (g == 0) ? RESERVED_INODES : 0;
    memset(m->blk, 0, m->block_size);
    for (uint32_t i = 0; i < used_inodes; i++) set_bit(m->blk, i);
    for (uint32_t i = m->inodes_per_group; i < m->block_size * 8; i++) set_bit(m->blk, i);
    if (ext4_io_pwrite(m->io, group_ibitmap(m, g) * (uint64_t)m->block_size,
                       m->blk, m->block_size))
        return EXT4_MKFS_ERR_IO;
    put32(d, EXT4_GD_IBITMAP_CSUM_LO_OFF, EXT4_GD_IBITMAP_CSUM_HI_OFF,
          ext4_bitmap_csum(m->csum_seed, m->blk, m->inodes_per_group / 8));

    /*
     * These two are equal at creation and are not the same quantity. free_inodes
     * counts what can be handed out; never_used promises that a run at the end of
     * the table has never held an inode, which is what lets the table go
     * unwritten. Deleting a file later raises the first and must leave the second
     * alone - see the note in ext4_ialloc.c - so they are named apart here rather
     * than written as one expression twice.
     */
    uint32_t free_inodes = m->inodes_per_group - used_inodes;
    uint32_t never_used  = m->inodes_per_group - used_inodes;

    put64(d, EXT4_GD_BLOCK_BITMAP_LO_OFF, EXT4_GD_BLOCK_BITMAP_HI_OFF, group_bbitmap(m, g));
    put64(d, EXT4_GD_INODE_BITMAP_LO_OFF, EXT4_GD_INODE_BITMAP_HI_OFF, group_ibitmap(m, g));
    put64(d, EXT4_GD_INODE_TABLE_LO_OFF,  EXT4_GD_INODE_TABLE_HI_OFF,  group_itable(m, g));
    put32(d, EXT4_GD_FREE_BLOCKS_LO_OFF,  EXT4_GD_FREE_BLOCKS_HI_OFF,  gb - used);
    put32(d, EXT4_GD_FREE_INODES_LO_OFF,  EXT4_GD_FREE_INODES_HI_OFF,  free_inodes);
    put32(d, EXT4_GD_USED_DIRS_LO_OFF, EXT4_GD_USED_DIRS_HI_OFF, (g == 0) ? 2 : 0);
    put32(d, EXT4_GD_ITABLE_UNUSED_LO_OFF, EXT4_GD_ITABLE_UNUSED_HI_OFF, never_used);

    /* bg_flags stays zero: INODE_ZEROED would claim the inode table had been
     * blanked, and it has not been. */
    wr16(d + EXT4_GD_FLAGS_OFF, 0);

    store_desc_csum(m, g);
    m->free_blocks += gb - used;
    return EXT4_MKFS_OK;
}

/* ── inodes ───────────────────────────────────────────────────────────────── */

static int write_inode(mkfs *m, uint32_t ino, uint8_t *inode) {
    uint32_t g = (ino - 1) / m->inodes_per_group;
    uint32_t i = (ino - 1) % m->inodes_per_group;

    uint32_t c = ext4_inode_csum(m->csum_seed, ino, 0, inode, m->inode_size);
    wr16(inode + EXT4_INODE_CSUM_LO_OFF, (uint16_t)c);
    if (m->inode_size > 128 && ext4_inode_has_checksum_hi(inode))
        wr16(inode + EXT4_INODE_CSUM_HI_OFF, (uint16_t)(c >> 16));

    uint64_t at = group_itable(m, g) * (uint64_t)m->block_size +
                  (uint64_t)i * m->inode_size;
    return ext4_io_pwrite(m->io, at, inode, m->inode_size) ? EXT4_MKFS_ERR_IO
                                                           : EXT4_MKFS_OK;
}

/*
 * A directory of `nblocks` blocks starting at `first_block`, held in one extent in
 * the root of the tree inside the inode. Both directories written here are
 * contiguous and small enough for that, so no extent block is ever needed and
 * i_blocks counts data alone.
 */
static void init_dir_inode(mkfs *m, uint8_t *inode, uint16_t mode, uint16_t links,
                           uint64_t first_block, uint32_t nblocks) {
    memset(inode, 0, m->inode_size);

    wr16(inode + INODE_MODE_OFF, (uint16_t)(EXT4_S_IFDIR | mode));
    wr32(inode + INODE_SIZE_LO_OFF, nblocks * m->block_size);
    wr32(inode + INODE_ATIME_OFF, m->when);
    wr32(inode + INODE_CTIME_OFF, m->when);
    wr32(inode + INODE_MTIME_OFF, m->when);
    wr16(inode + INODE_LINKS_COUNT_OFF, links);
    wr32(inode + INODE_BLOCKS_LO_OFF,
         (uint32_t)((uint64_t)nblocks * m->block_size / 512));
    wr32(inode + INODE_FLAGS_OFF, EXT4_INODE_FLAG_EXTENTS);

    uint8_t *root = inode + INODE_IBLOCK_OFF;
    wr16(root, EXT4_EXTENT_MAGIC);
    wr16(root + 2, 1);      /* one extent */
    wr16(root + 4, 4);      /* the four the inode's 60 bytes hold */
    wr16(root + 6, 0);      /* depth 0: the entries are leaves */
    uint8_t *e = root + 12;
    wr32(e, 0);                                        /* first logical block */
    wr16(e + 4, (uint16_t)nblocks);
    wr16(e + 6, (uint16_t)(first_block >> 32));
    wr32(e + 8, (uint32_t)first_block);

    if (m->inode_size > 128) {
        wr16(inode + INODE_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);
        wr32(inode + INODE_CRTIME_OFF, m->when);
    }
}

/*
 * Inodes 1 to 10 exist but hold nothing. They are marked in use in the bitmap, so
 * e2fsck reads them and checks their checksums - a blank inode with no checksum is
 * reported, which is why they are written rather than left alone. Inode 1, the
 * bad-block inode, additionally carries the creation time; that is what mke2fs
 * does, and matching it keeps the two images comparable byte for byte.
 */
static int write_reserved_inodes(mkfs *m) {
    uint8_t *inode = malloc(m->inode_size);
    if (!inode) return EXT4_MKFS_ERR_NOMEM;

    int rc = EXT4_MKFS_OK;
    for (uint32_t ino = 1; ino <= 10 && rc == EXT4_MKFS_OK; ino++) {
        memset(inode, 0, m->inode_size);
        if (ino == 1) {
            wr32(inode + INODE_ATIME_OFF, m->when);
            wr32(inode + INODE_CTIME_OFF, m->when);
            wr32(inode + INODE_MTIME_OFF, m->when);
        }
        rc = write_inode(m, ino, inode);
    }
    free(inode);
    return rc;
}

/* ── the two directories ──────────────────────────────────────────────────── */

/* The 12-byte tail shaped like a dead entry, holding the block's checksum. Same
 * structure the directory writer restamps; written here because at this point
 * there is no filesystem to open and go through it with. */
static void dir_tail(mkfs *m, uint32_t seed) {
    uint8_t *t = m->blk + m->block_size - DIR_TAIL_SIZE;
    wr32(t, 0);
    wr16(t + 4, DIR_TAIL_SIZE);
    t[6] = 0;
    t[7] = EXT4_FT_DIR_CSUM;
    wr32(t + 8, ext4_crc32c(seed, m->blk, m->block_size - DIR_TAIL_SIZE));
}

static uint32_t put_entry(uint8_t *p, uint32_t ino, uint32_t rec, const char *name) {
    uint8_t len = (uint8_t)strlen(name);
    wr32(p, ino);
    wr16(p + 4, (uint16_t)rec);
    p[6] = len;
    p[7] = EXT4_FT_DIR;
    memcpy(p + 8, name, len);
    return rec;
}

static int write_directories(mkfs *m) {
    uint32_t limit = m->block_size - DIR_TAIL_SIZE;
    uint32_t root_seed = ext4_inode_csum_seed(m->csum_seed, ROOT_INO, 0);
    uint32_t lpf_seed  = ext4_inode_csum_seed(m->csum_seed, LPF_INO, 0);

    /* Root: itself, itself again as the parent, and lost+found taking the rest. */
    memset(m->blk, 0, m->block_size);
    uint32_t off = 0;
    off += put_entry(m->blk + off, ROOT_INO, 12, ".");
    off += put_entry(m->blk + off, ROOT_INO, 12, "..");
    put_entry(m->blk + off, LPF_INO, limit - off, "lost+found");
    dir_tail(m, root_seed);
    if (ext4_io_pwrite(m->io, m->root_block * (uint64_t)m->block_size,
                       m->blk, m->block_size))
        return EXT4_MKFS_ERR_IO;

    /* lost+found's first block: itself and its parent, nothing else. */
    memset(m->blk, 0, m->block_size);
    off = put_entry(m->blk, LPF_INO, 12, ".");
    put_entry(m->blk + off, ROOT_INO, limit - off, "..");
    dir_tail(m, lpf_seed);
    if (ext4_io_pwrite(m->io, m->lpf_block * (uint64_t)m->block_size,
                       m->blk, m->block_size))
        return EXT4_MKFS_ERR_IO;

    /* The rest of it is empty: one dead entry spanning the block up to the tail.
     * A block left as it was would end the walk at whatever byte came first. */
    for (uint32_t b = 1; b < m->lpf_blocks; b++) {
        memset(m->blk, 0, m->block_size);
        wr32(m->blk, 0);
        wr16(m->blk + 4, (uint16_t)limit);
        dir_tail(m, lpf_seed);
        if (ext4_io_pwrite(m->io, (m->lpf_block + b) * (uint64_t)m->block_size,
                           m->blk, m->block_size))
            return EXT4_MKFS_ERR_IO;
    }

    uint8_t *inode = malloc(m->inode_size);
    if (!inode) return EXT4_MKFS_ERR_NOMEM;

    /* Three links to root: its own name, its own "..", and lost+found's "..". */
    init_dir_inode(m, inode, 0755, 3, m->root_block, 1);
    int rc = write_inode(m, ROOT_INO, inode);
    if (rc == EXT4_MKFS_OK) {
        init_dir_inode(m, inode, 0700, 2, m->lpf_block, m->lpf_blocks);
        rc = write_inode(m, LPF_INO, inode);
    }
    free(inode);
    return rc;
}

/* ── superblock ───────────────────────────────────────────────────────────── */

static uint32_t log2_of(uint32_t v) {
    uint32_t n = 0;
    while ((1u << n) < v) n++;
    return n;
}

static void build_superblock(mkfs *m, const ext4_mkfs_params *p) {
    uint8_t *sb = m->sb;
    memset(sb, 0, 1024);

    wr32(sb + EXT4_SB_INODES_COUNT_OFF, m->inodes_count);
    wr32(sb + EXT4_SB_BLOCKS_LO_OFF, (uint32_t)m->blocks_count);
    wr32(sb + EXT4_SB_BLOCKS_HI_OFF, (uint32_t)(m->blocks_count >> 32));

    /* Five per cent held back for root, as every ext filesystem does - it is what
     * keeps a full filesystem from becoming an unrepairable one. */
    uint64_t reserved = m->blocks_count * 5 / 100;
    wr32(sb + SB_R_BLOCKS_LO_OFF, (uint32_t)reserved);
    wr32(sb + SB_R_BLOCKS_HI_OFF, (uint32_t)(reserved >> 32));

    wr32(sb + EXT4_SB_FREE_BLOCKS_LO_OFF, (uint32_t)m->free_blocks);
    wr32(sb + EXT4_SB_FREE_BLOCKS_HI_OFF, (uint32_t)(m->free_blocks >> 32));
    wr32(sb + EXT4_SB_FREE_INODES_OFF, m->inodes_count - RESERVED_INODES);

    wr32(sb + EXT4_SB_FIRST_DATA_BLK_OFF, m->first_data_block);
    wr32(sb + EXT4_SB_LOG_BLOCK_SIZE_OFF, log2_of(m->block_size) - 10);
    wr32(sb + SB_LOG_CLUSTER_SIZE_OFF, log2_of(m->block_size) - 10);
    wr32(sb + EXT4_SB_BLOCKS_PER_GRP_OFF, m->blocks_per_group);
    wr32(sb + SB_CLUSTERS_PER_GRP_OFF, m->blocks_per_group);
    wr32(sb + EXT4_SB_INODES_PER_GRP_OFF, m->inodes_per_group);

    wr32(sb + SB_WTIME_OFF, m->when);
    wr32(sb + SB_LASTCHECK_OFF, m->when);
    wr32(sb + SB_MKFS_TIME_OFF, m->when);
    wr16(sb + SB_MAX_MNT_COUNT_OFF, 0xFFFF);       /* never force a check by count */
    wr16(sb + SB_MAGIC_OFF, EXT4_MAGIC);
    wr16(sb + SB_STATE_OFF, EXT4_STATE_CLEAN);
    wr16(sb + SB_ERRORS_OFF, EXT4_ERRORS_CONTINUE);
    wr32(sb + SB_REV_LEVEL_OFF, EXT4_REV_DYNAMIC);
    wr32(sb + SB_FIRST_INO_OFF, RESERVED_INODES);
    wr16(sb + EXT4_SB_INODE_SIZE_OFF, (uint16_t)m->inode_size);
    wr16(sb + SB_BLOCK_GROUP_NR_OFF, 0);

    wr32(sb + SB_FEATURE_COMPAT_OFF, FEATURE_COMPAT);
    wr32(sb + EXT4_SB_FEATURE_INCOMPAT_OFF, FEATURE_INCOMPAT);
    wr32(sb + SB_FEATURE_RO_COMPAT_OFF, FEATURE_RO_COMPAT);
    wr16(sb + EXT4_SB_DESC_SIZE_OFF, EXT4_DESC_SIZE);

    memcpy(sb + SB_UUID_OFF, p->uuid, 16);
    memcpy(sb + SB_HASH_SEED_OFF, p->hash_seed, 16);
    sb[SB_DEF_HASH_VERSION_OFF] = 1;               /* half_md4 */
    /* Says the directory hash was computed with a signed char type. Inert here -
     * without dir_index nothing in this filesystem is ever hashed - but it is part
     * of what a reader expects to find, so it is set rather than left to mean
     * something by accident. */
    wr32(sb + SB_FLAGS_OFF, 0x1);
    wr32(sb + SB_DEF_MOUNT_OPTS_OFF, 0xC);         /* user_xattr | acl */

    /*
     * What the filesystem spends on describing itself: every group's superblock
     * copy, descriptors, bitmaps and inode table, plus the boot block where there
     * is one. The root directory and lost+found are not in it - they are files,
     * not overhead, which is the distinction mke2fs draws too.
     *
     * Nothing verifies this and a wrong value is not corruption; it is what df
     * subtracts to report a size, so leaving it zero makes a container claim to be
     * larger than it can ever hold.
     */
    uint64_t overhead = m->first_data_block;
    for (uint32_t g = 0; g < m->groups; g++) overhead += group_meta_blocks(m, g);
    wr32(sb + SB_OVERHEAD_CLUSTERS_OFF, (uint32_t)overhead);

    wr16(sb + SB_MIN_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);
    wr16(sb + SB_WANT_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);
    sb[SB_CSUM_TYPE_OFF] = 1;                      /* crc32c */

    /*
     * The seed every other checksum here is built on, and it is derived from the
     * UUID rather than stored independently: with metadata_csum_seed on, a reader
     * takes s_checksum_seed at face value, but it has to be this value or a
     * filesystem whose UUID is changed later stops verifying.
     */
    m->csum_seed = ext4_crc32c(0xFFFFFFFFu, p->uuid, 16);
    wr32(sb + EXT4_SB_CSUM_SEED_OFF, m->csum_seed);
}

/*
 * Writes the superblock and the descriptor table into one group.
 *
 * The primary copy sits at byte 1024, which at 1 KiB blocks is the block after the
 * boot block and at anything larger is the second half of block 0. Backups start
 * at their group's first block. Each copy records which group it is in, and every
 * copy but the primary is marked as not cleanly unmounted - a backup is only ever
 * read when the primary is gone, and it has not been kept up to date since.
 */
static int write_super_at(mkfs *m, uint32_t g) {
    wr16(m->sb + SB_BLOCK_GROUP_NR_OFF, (uint16_t)g);
    wr16(m->sb + SB_STATE_OFF, g == 0 ? EXT4_STATE_CLEAN : 0);
    wr32(m->sb + EXT4_SB_CSUM_OFF, ext4_superblock_csum(m->sb));

    uint32_t inpart = (g == 0 && m->block_size > 1024) ? 1024 : 0;
    memset(m->blk, 0, m->block_size);
    memcpy(m->blk + inpart, m->sb, 1024);
    if (ext4_io_pwrite(m->io, group_start(m, g) * (uint64_t)m->block_size,
                       m->blk, m->block_size))
        return EXT4_MKFS_ERR_IO;

    /* The descriptor buffer is padded to whole blocks, so the tail of the last one
     * is written as zeroes rather than left holding whatever the medium had. */
    if (ext4_io_pwrite(m->io, (group_start(m, g) + 1) * (uint64_t)m->block_size,
                       m->desc, (size_t)m->gdt_blocks * m->block_size))
        return EXT4_MKFS_ERR_IO;
    return EXT4_MKFS_OK;
}

/* ── policy ───────────────────────────────────────────────────────────────── */

/*
 * The same thresholds mke2fs.conf carries. They are not arithmetic and there is no
 * right answer to derive: what makes them the right choice is that a container
 * formatted this way has the geometry a reader expects for its size, rather than
 * one that marks it out as made by something unusual.
 */
void ext4_mkfs_default_params(ext4_mkfs_params *p, uint64_t size_bytes) {
    uint32_t block_size, ratio;

    if (size_bytes < 3ull * 1024 * 1024) {
        block_size = 1024; ratio = 8192;
    } else if (size_bytes < 512ull * 1024 * 1024) {
        block_size = 1024; ratio = 4096;
    } else {
        block_size = 4096; ratio = 16384;
    }

    p->block_size   = block_size;
    p->blocks_count = size_bytes / block_size;
    p->inode_size   = 256;
    p->inodes_count = (uint32_t)(size_bytes / ratio);
    if (p->inodes_count < RESERVED_INODES + 1)
        p->inodes_count = RESERVED_INODES + 1;
}

/* ── the whole thing ──────────────────────────────────────────────────────── */

int ext4_mkfs(ext4_io *io, const ext4_mkfs_params *p, ext4_mkfs_result *out) {
    mkfs m;
    memset(&m, 0, sizeof(m));
    m.io = io;
    if (out) memset(out, 0, sizeof(*out));

    int rc = compute_geometry(&m, p);
    if (rc != EXT4_MKFS_OK) {
        EXT4_LOGE("mkfs: cannot format %llu blocks of %u bytes (%d)",
                  (unsigned long long)p->blocks_count, p->block_size, rc);
        return rc;
    }

    EXT4_LOGI("mkfs: %llu blocks of %u, %u groups, %u inodes (%u per group), "
              "inode table %u blocks, gdt %u blocks",
              (unsigned long long)m.blocks_count, m.block_size, m.groups,
              m.inodes_count, m.inodes_per_group, m.itable_blocks, m.gdt_blocks);

    /* Reported before the first write, so that a caller which fails half way
     * still learns what was being built rather than nothing. */
    if (out) {
        out->blocks_count = m.blocks_count;
        out->block_size   = m.block_size;
        out->inodes_count = m.inodes_count;
        out->groups       = m.groups;
    }

    /* io->block_size is what turns a byte offset into a block; nothing has read a
     * superblock to learn it here, so it is set from what is about to be written. */
    io->block_size = m.block_size;

    m.desc = calloc((size_t)m.gdt_blocks, m.block_size);
    m.blk  = malloc(m.block_size);
    if (!m.desc || !m.blk) { rc = EXT4_MKFS_ERR_NOMEM; goto done; }

    /* The seed comes out of build_superblock, and every checksum below needs it,
     * so the superblock is assembled before anything is written - the free-block
     * count it carries is filled in afterwards. */
    build_superblock(&m, p);

    for (uint32_t g = 0; g < m.groups; g++) {
        rc = write_group(&m, g);
        if (rc != EXT4_MKFS_OK) {
            EXT4_LOGE("mkfs: group %u could not be written (%d)", g, rc);
            goto done;
        }
    }
    EXT4_LOGI("mkfs: %u groups initialised, %llu blocks free", m.groups,
              (unsigned long long)m.free_blocks);

    rc = write_reserved_inodes(&m);
    if (rc != EXT4_MKFS_OK) { EXT4_LOGE("mkfs: reserved inodes failed (%d)", rc); goto done; }

    rc = write_directories(&m);
    if (rc != EXT4_MKFS_OK) { EXT4_LOGE("mkfs: root/lost+found failed (%d)", rc); goto done; }
    EXT4_LOGI("mkfs: root at block %llu, lost+found at %llu (%u blocks)",
              (unsigned long long)m.root_block, (unsigned long long)m.lpf_block,
              m.lpf_blocks);

    /* Now that the bitmaps have been counted, the superblock can state how much is
     * free. Nothing has read it yet, so this is still the first write of it. */
    wr32(m.sb + EXT4_SB_FREE_BLOCKS_LO_OFF, (uint32_t)m.free_blocks);
    wr32(m.sb + EXT4_SB_FREE_BLOCKS_HI_OFF, (uint32_t)(m.free_blocks >> 32));

    /*
     * The boot block only exists at 1 KiB blocks, where the superblock's offset of
     * 1024 puts it in block 1 and leaves block 0 to itself. It holds nothing, but
     * on a container it holds random bytes, and a thousand bytes of noise where a
     * boot sector belongs is worth removing.
     */
    if (m.block_size == 1024) {
        memset(m.blk, 0, m.block_size);
        if (ext4_io_pwrite(m.io, 0, m.blk, m.block_size)) {
            rc = EXT4_MKFS_ERR_IO;
            goto done;
        }
    }

    uint32_t backups = 0;
    for (uint32_t g = 0; g < m.groups; g++) {
        if (!has_super(g)) continue;
        rc = write_super_at(&m, g);
        if (rc != EXT4_MKFS_OK) {
            EXT4_LOGE("mkfs: superblock copy in group %u failed (%d)", g, rc);
            goto done;
        }
        backups++;
    }

    rc = ext4_io_flush(io) ? EXT4_MKFS_ERR_IO : EXT4_MKFS_OK;
    EXT4_LOGI("mkfs: %u superblock copies written, %s", backups,
              rc == EXT4_MKFS_OK ? "done" : "flush failed");

done:
    free(m.desc);
    free(m.blk);
    return rc;
}
