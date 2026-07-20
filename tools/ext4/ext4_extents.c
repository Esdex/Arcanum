/*
 * Clean-room ext4 extent reader. See the header for the provenance note.
 *
 * On-disk layout implemented here, all little-endian:
 *
 *   extent header (12 bytes), at the head of the inode's i_block and of every
 *   extent block:
 *       u16 magic (0xF30A), u16 entries, u16 max, u16 depth, u32 generation
 *
 *   depth == 0, entries are extents (12 bytes):
 *       u32 first logical block, u16 length, u16 physical high, u32 physical low
 *       a length above 32768 marks an uninitialised extent; subtract 32768
 *
 *   depth > 0, entries are index nodes (12 bytes):
 *       u32 first logical block, u32 child block low, u16 child block high, u16 pad
 */

#include "ext4_extents.h"

#include <string.h>

#define EXT4_EXTENT_MAGIC     0xF30A
#define EXT4_INODE_FLAG_EXTENTS 0x00080000u
#define EXT4_FEATURE_INCOMPAT_64BIT 0x0080u
#define EXT4_MAX_INIT_LEN     32768u
/* Depth is bounded on disk; refusing to go deeper stops a corrupt or hostile
 * image from walking us into unbounded recursion. */
#define EXT4_MAX_DEPTH        5
#define EXT4_MAX_BLOCK_SIZE   65536

#define INODE_FLAGS_OFF       0x20
#define INODE_IBLOCK_OFF      0x28
#define INODE_IBLOCK_SIZE     60

static uint16_t rd16(const uint8_t *p) {
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* ── Extent tree ──────────────────────────────────────────────────────────── */

typedef struct {
    uint16_t entries;
    uint16_t max;
    uint16_t depth;
} eh_t;

/*
 * `capacity` is how many 12-byte entries the containing buffer can hold. It is
 * checked against the header's own count because eh_entries comes off the disk:
 * a corrupt value would otherwise walk us straight off the end of the block.
 */
static int parse_header(const uint8_t *node, size_t capacity, eh_t *out) {
    if (rd16(node) != EXT4_EXTENT_MAGIC) return EXT4_ERR_FORMAT;
    out->entries = rd16(node + 2);
    out->max     = rd16(node + 4);
    out->depth   = rd16(node + 6);
    if (out->depth > EXT4_MAX_DEPTH)     return EXT4_ERR_FORMAT;
    if (out->entries > capacity)         return EXT4_ERR_FORMAT;
    return EXT4_OK;
}

static uint64_t idx_child(const uint8_t *e) {
    return (uint64_t)rd32(e + 4) | ((uint64_t)rd16(e + 8) << 32);
}

static void leaf_run(const uint8_t *e, ext4_extent_run *run) {
    uint16_t len = rd16(e + 4);
    run->logical  = rd32(e);
    run->physical = (uint64_t)rd32(e + 8) | ((uint64_t)rd16(e + 6) << 32);
    run->uninit   = len > EXT4_MAX_INIT_LEN;
    run->length   = run->uninit ? (uint32_t)(len - EXT4_MAX_INIT_LEN) : len;
}

static size_t entries_per_block(const ext4_fs *fs) {
    return (fs->block_size - 12u) / 12u;
}

/* Depth-first walk. Recursion is bounded by EXT4_MAX_DEPTH, which the header
 * check enforces, so the stack cost is fixed no matter what the image claims. */
static int walk_node(const ext4_fs *fs, const uint8_t *node, size_t capacity,
                     ext4_extent_cb emit, void *user, int guard) {
    eh_t eh;
    int rc = parse_header(node, capacity, &eh);
    if (rc != EXT4_OK) return rc;
    if (guard > EXT4_MAX_DEPTH) return EXT4_ERR_FORMAT;

    const uint8_t *entry = node + 12;

    if (eh.depth == 0) {
        for (uint16_t i = 0; i < eh.entries; i++, entry += 12) {
            ext4_extent_run run;
            leaf_run(entry, &run);
            if (run.physical + run.length > fs->blocks_count) return EXT4_ERR_RANGE;
            rc = emit(user, &run);
            if (rc != 0) return rc;
        }
        return EXT4_OK;
    }

    uint8_t *child = (uint8_t *)0;
    /* One buffer per level rather than one per entry: the tree is at most a few
     * levels deep, and this keeps the peak allocation to depth * block_size. */
    uint8_t stackbuf[EXT4_MAX_BLOCK_SIZE];
    if (fs->block_size > sizeof(stackbuf)) return EXT4_ERR_FORMAT;
    child = stackbuf;

    for (uint16_t i = 0; i < eh.entries; i++, entry += 12) {
        uint64_t blk = idx_child(entry);
        if (blk == 0 || blk >= fs->blocks_count) return EXT4_ERR_RANGE;
        if (fs->read_block(fs->ctx, blk, child) != EXT4_OK) return EXT4_ERR_IO;
        rc = walk_node(fs, child, entries_per_block(fs), emit, user, guard + 1);
        if (rc != EXT4_OK) return rc;
    }
    return EXT4_OK;
}

int ext4_walk_extents(const ext4_fs *fs, const uint8_t *inode,
                      ext4_extent_cb emit, void *user) {
    if (!(rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS))
        return EXT4_ERR_NOT_EXTENT;
    /* The inode's own i_block is 60 bytes: a 12-byte header and room for 4 entries. */
    return walk_node(fs, inode + INODE_IBLOCK_OFF,
                     (INODE_IBLOCK_SIZE - 12) / 12, emit, user, 0);
}

typedef struct {
    uint32_t want;
    uint64_t physical;
    int      uninit;
    int      found;
} lookup_t;

static int lookup_cb(void *user, const ext4_extent_run *run) {
    lookup_t *l = (lookup_t *)user;
    if (l->want >= run->logical && l->want < run->logical + run->length) {
        l->physical = run->physical + (l->want - run->logical);
        l->uninit   = run->uninit;
        l->found    = 1;
        return 1;   /* stop the walk */
    }
    /* Extents come out in logical order, so once we are past the target the
     * block is a hole and there is no point reading further. */
    if (run->logical > l->want) return 1;
    return 0;
}

int ext4_map_block(const ext4_fs *fs, const uint8_t *inode,
                   uint32_t logical, uint64_t *physical, int *uninit) {
    lookup_t l = { logical, 0, 0, 0 };
    int rc = ext4_walk_extents(fs, inode, lookup_cb, &l);
    if (rc != EXT4_OK && rc != 1) return rc;
    *physical = l.found ? l.physical : 0;
    if (uninit) *uninit = l.uninit;
    return EXT4_OK;
}

/* ── Superblock and inode location ────────────────────────────────────────── */

#define SB_OFFSET             1024
#define SB_INODES_COUNT       0x00
#define SB_BLOCKS_LO          0x04
#define SB_FIRST_DATA_BLOCK   0x14
#define SB_LOG_BLOCK_SIZE     0x18
#define SB_BLOCKS_PER_GROUP   0x20
#define SB_INODES_PER_GROUP   0x28
#define SB_MAGIC              0x38
#define SB_INODE_SIZE         0x58
#define SB_FEATURE_INCOMPAT   0x60
#define SB_DESC_SIZE          0xFE
#define SB_BLOCKS_HI          0x150

int ext4_open(ext4_fs *fs, ext4_read_block_fn read_block, void *ctx) {
    uint8_t buf[EXT4_MAX_BLOCK_SIZE];

    fs->read_block = read_block;
    fs->ctx        = ctx;
    /* Read the superblock through a provisional 1 KiB view: its own block size
     * is one of the fields we are here to fetch. */
    fs->block_size = 1024;
    if (read_block(ctx, 1, buf) != EXT4_OK) return EXT4_ERR_IO;

    if (rd16(buf + SB_MAGIC) != 0xEF53) return EXT4_ERR_FORMAT;

    uint32_t log_bs = rd32(buf + SB_LOG_BLOCK_SIZE);
    if (log_bs > 6) return EXT4_ERR_FORMAT;           /* 1 KiB .. 64 KiB */
    fs->block_size       = 1024u << log_bs;
    fs->inodes_per_group = rd32(buf + SB_INODES_PER_GROUP);
    fs->first_data_block = rd32(buf + SB_FIRST_DATA_BLOCK);
    fs->inode_size       = rd16(buf + SB_INODE_SIZE);
    if (fs->inode_size < 128 || fs->inode_size > fs->block_size) return EXT4_ERR_FORMAT;
    if (fs->inodes_per_group == 0) return EXT4_ERR_FORMAT;

    uint32_t incompat = rd32(buf + SB_FEATURE_INCOMPAT);
    uint16_t desc     = rd16(buf + SB_DESC_SIZE);
    /* Group descriptors are 32 bytes unless the 64BIT feature widens them, and
     * mke2fs turns 64BIT on by default at these sizes. */
    fs->desc_size = (incompat & EXT4_FEATURE_INCOMPAT_64BIT) && desc >= 64 ? desc : 32;

    fs->blocks_count = (uint64_t)rd32(buf + SB_BLOCKS_LO) |
                       ((uint64_t)rd32(buf + SB_BLOCKS_HI) << 32);
    if (fs->blocks_count == 0) return EXT4_ERR_FORMAT;
    return EXT4_OK;
}

int ext4_read_inode_raw(const ext4_fs *fs, uint32_t ino, uint8_t *inode_out, size_t out_size) {
    uint8_t buf[EXT4_MAX_BLOCK_SIZE];
    if (ino == 0) return EXT4_ERR_RANGE;
    if (out_size < 128) return EXT4_ERR_RANGE;

    uint32_t group = (ino - 1) / fs->inodes_per_group;
    uint32_t index = (ino - 1) % fs->inodes_per_group;

    /* The descriptor table starts in the block after the superblock: block 1 on
     * a 1 KiB filesystem where the superblock has a block to itself, block 1 as
     * well on larger ones because the superblock shares block 0. */
    uint64_t desc_table = fs->first_data_block + 1;
    uint64_t desc_byte  = (uint64_t)group * fs->desc_size;
    uint64_t desc_blk   = desc_table + desc_byte / fs->block_size;
    uint32_t desc_off   = (uint32_t)(desc_byte % fs->block_size);

    if (fs->read_block(fs->ctx, desc_blk, buf) != EXT4_OK) return EXT4_ERR_IO;

    /* bg_inode_table_lo at +8, and with 64-bit descriptors bg_inode_table_hi at +40. */
    uint64_t itable = rd32(buf + desc_off + 8);
    if (fs->desc_size >= 64)
        itable |= (uint64_t)rd32(buf + desc_off + 40) << 32;
    if (itable == 0 || itable >= fs->blocks_count) return EXT4_ERR_FORMAT;

    uint64_t byte = (uint64_t)index * fs->inode_size;
    uint64_t blk  = itable + byte / fs->block_size;
    uint32_t off  = (uint32_t)(byte % fs->block_size);

    if (fs->read_block(fs->ctx, blk, buf) != EXT4_OK) return EXT4_ERR_IO;

    size_t want = fs->inode_size < out_size ? fs->inode_size : out_size;
    /* An inode may not straddle a block boundary in any valid layout, since the
     * size is a power of two that divides the block size. */
    if (off + want > fs->block_size) return EXT4_ERR_FORMAT;
    memcpy(inode_out, buf + off, want);
    return EXT4_OK;
}
