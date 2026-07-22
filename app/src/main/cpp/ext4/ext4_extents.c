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
#include "ext4_csum.h"

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

/* ── Extent tree checksums ────────────────────────────────────────────────── */

typedef struct {
    const ext4_fs *fs;
    uint32_t seed;
    int      checked;
    int      bad;
} csum_ctx;

static int check_node(csum_ctx *c, const uint8_t *node, size_t capacity, int guard);

static int check_children(csum_ctx *c, const uint8_t *node, size_t capacity, int guard) {
    eh_t eh;
    if (parse_header(node, capacity, &eh) != EXT4_OK) return EXT4_ERR_FORMAT;
    if (eh.depth == 0) return EXT4_OK;

    const uint8_t *entry = node + 12;
    uint8_t child[EXT4_MAX_BLOCK_SIZE];
    for (uint16_t i = 0; i < eh.entries; i++, entry += 12) {
        uint64_t blk = idx_child(entry);
        if (blk == 0 || blk >= c->fs->blocks_count) return EXT4_ERR_RANGE;
        if (c->fs->read_block(c->fs->ctx, blk, child) != EXT4_OK) return EXT4_ERR_IO;
        int rc = check_node(c, child, entries_per_block(c->fs), guard + 1);
        if (rc != EXT4_OK) return rc;
    }
    return EXT4_OK;
}

static int check_node(csum_ctx *c, const uint8_t *node, size_t capacity, int guard) {
    if (guard > EXT4_MAX_DEPTH) return EXT4_ERR_FORMAT;
    uint32_t bs  = c->fs->block_size;
    uint32_t off = ext4_extent_tail_offset(node);
    if (off + 4 > bs) return EXT4_ERR_FORMAT;
    uint32_t stored = (uint32_t)node[off] | ((uint32_t)node[off + 1] << 8) |
                      ((uint32_t)node[off + 2] << 16) | ((uint32_t)node[off + 3] << 24);
    uint32_t want   = ext4_extent_block_csum(c->seed, node, bs);
    c->checked++;
    if (stored != want) { c->bad++; return EXT4_ERR_FORMAT; }
    return check_children(c, node, capacity, guard);
}

int ext4_check_extent_tree(const ext4_fs *fs, uint32_t ino, uint32_t generation,
                           const uint8_t *inode, int *blocks_checked) {
    if (blocks_checked) *blocks_checked = 0;
    if (!fs->has_metadata_csum) return EXT4_OK;
    if (!(rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS)) return EXT4_ERR_NOT_EXTENT;

    csum_ctx c = { fs, ext4_inode_csum_seed(fs->csum_seed, ino, generation), 0, 0 };
    /* Starts at the inode root, which itself carries no tail: only the blocks it
     * points at are checksummed here. */
    int rc = check_children(&c, inode + INODE_IBLOCK_OFF,
                            (INODE_IBLOCK_SIZE - 12) / 12, 0);
    if (blocks_checked) *blocks_checked = c.checked;
    return rc;
}

/* ── Reading file data ────────────────────────────────────────────────────── */

#define INODE_SIZE_LO_OFF     0x04
#define INODE_SIZE_HI_OFF     0x6C

uint64_t ext4_inode_size(const uint8_t *inode) {
    return (uint64_t)rd32(inode + INODE_SIZE_LO_OFF) |
           ((uint64_t)rd32(inode + INODE_SIZE_HI_OFF) << 32);
}

typedef struct {
    const ext4_fs *fs;
    uint64_t want_start;   /* byte offset of the first byte wanted */
    uint64_t want_end;     /* one past the last */
    uint8_t *out;          /* pre-zeroed, so holes need no writing */
    int      rc;
} read_ctx;

static int read_cb(void *user, const ext4_extent_run *run) {
    read_ctx *r  = (read_ctx *)user;
    uint64_t bs  = r->fs->block_size;
    uint64_t beg = (uint64_t)run->logical * bs;
    uint64_t end = beg + (uint64_t)run->length * bs;

    if (end <= r->want_start) return 0;          /* entirely before the window */
    if (beg >= r->want_end)   return 1;          /* past it, and runs are ordered */
    /* Preallocated but never written: the blocks exist and hold whatever was
     * there before, so they must read as zeroes rather than be copied out. */
    if (run->uninit) return 0;

    uint64_t from = beg > r->want_start ? beg : r->want_start;
    uint64_t to   = end < r->want_end   ? end : r->want_end;

    uint8_t block[EXT4_MAX_BLOCK_SIZE];
    while (from < to) {
        uint64_t blk_index  = from / bs;
        uint64_t blk_offset = from % bs;
        uint64_t chunk      = bs - blk_offset;
        if (chunk > to - from) chunk = to - from;

        uint64_t phys = run->physical + (blk_index - run->logical);
        if (r->fs->read_block(r->fs->ctx, phys, block) != EXT4_OK) {
            r->rc = EXT4_ERR_IO;
            return 1;
        }
        memcpy(r->out + (from - r->want_start), block + blk_offset, (size_t)chunk);
        from += chunk;
    }
    return 0;
}

long ext4_read_file(const ext4_fs *fs, const uint8_t *inode,
                    uint64_t offset, uint8_t *buf, uint64_t length) {
    uint64_t size = ext4_inode_size(inode);
    if (offset >= size) return 0;
    if (offset + length > size) length = size - offset;
    if (length == 0) return 0;

    /* Zero first: every byte not covered by an initialised extent is a hole, and
     * this way the walk only has to fill in what it actually finds. */
    memset(buf, 0, (size_t)length);

    read_ctx ctx = { fs, offset, offset + length, buf, EXT4_OK };
    int rc = ext4_walk_extents(fs, inode, read_cb, &ctx);
    if (rc != EXT4_OK && rc != 1) return rc;
    if (ctx.rc != EXT4_OK) return ctx.rc;
    return (long)length;
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
#define SB_UUID               0x68
#define SB_BLOCKS_HI          0x150
#define SB_FEATURE_RO_COMPAT  0x64
#define SB_CHECKSUM_SEED      0x270
#define RO_COMPAT_METADATA_CSUM 0x0400u
#define INCOMPAT_CSUM_SEED      0x2000u
#define INODE_GENERATION_OFF  0x64

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

    fs->has_metadata_csum = (rd32(buf + SB_FEATURE_RO_COMPAT) & RO_COMPAT_METADATA_CSUM) != 0;
    /* With CSUM_SEED the filesystem stores its seed outright, which lets the UUID
     * change without rewriting every checksum. Without it, the seed is the crc32c
     * of the UUID. */
    if (incompat & INCOMPAT_CSUM_SEED)
        fs->csum_seed = rd32(buf + SB_CHECKSUM_SEED);
    else
        fs->csum_seed = ext4_crc32c(~0u, buf + SB_UUID, 16);
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
