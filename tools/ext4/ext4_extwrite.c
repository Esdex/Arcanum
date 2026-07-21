/*
 * Appending blocks to a file's extent tree.
 *
 * Clean-room, from the published on-disk format. See the header, and issue #7.
 *
 * This is the layer that makes the allocator's output legitimate. A block taken
 * from the bitmap and attached to nothing is an orphan, and e2fsck says so; the
 * same block reachable from an inode is simply a file getting longer. So from
 * here on the harness can demand that fsck come back completely clean, rather
 * than gating a residual it was told to expect.
 *
 * What has to move, per appended block:
 *
 *   - the block's contents, written before anything points at it
 *   - either the last extent's ee_len, when the new block is physically adjacent
 *     to it, or a fresh entry in the root
 *   - i_size, i_blocks
 *   - the inode checksum, last, since it covers all of the above
 *
 * The root inside an inode holds four extents and no more, and it carries no
 * checksum of its own - the inode's covers it. That is why depth 0 is worth
 * having on its own: every write lands in the inode, and there is no separate
 * extent-block tail to keep in step yet.
 *
 * Adjacency is not luck. Each block is asked for with the block after the file's
 * current last one as the goal, so a file being extended into free space keeps
 * using one extent instead of burning through the four available.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_extwrite.h"
#include "ext4_csum.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define EXT4_EXTENT_MAGIC        0xF30A
#define EXT4_INODE_FLAG_EXTENTS  0x00080000u
#define EXT4_MAX_INIT_LEN        32768u

#define INODE_SIZE_LO_OFF   0x04
#define INODE_BLOCKS_LO_OFF 0x1C
#define INODE_FLAGS_OFF     0x20
#define INODE_IBLOCK_OFF    0x28
#define INODE_IBLOCK_SIZE   60
#define INODE_GENERATION_OFF 0x64
#define INODE_SIZE_HI_OFF   0x6C
#define INODE_BLOCKS_HI_OFF 0x74   /* osd2.linux2.l_i_blocks_hi */

#define EH_ENTRIES_OFF 0x02
#define EH_MAX_OFF     0x04
#define EH_DEPTH_OFF   0x06

#define EE_BLOCK_OFF    0x00
#define EE_LEN_OFF      0x04
#define EE_START_HI_OFF 0x06
#define EE_START_LO_OFF 0x08

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

/* ── The inode ────────────────────────────────────────────────────────────── */

/* Where inode `ino` sits, as a byte offset into the image. */
static int inode_offset(ext4_wfs *fs, uint32_t ino, off_t *out) {
    if (ino == 0 || fs->inodes_per_group == 0) return EXTW_ERR_FORMAT;
    uint32_t group = (ino - 1) / fs->inodes_per_group;
    uint32_t index = (ino - 1) % fs->inodes_per_group;
    if (group >= fs->groups) return EXTW_ERR_FORMAT;

    const uint8_t *d = fs->desc + (size_t)group * fs->desc_size;
    uint64_t itable = rd32(d + EXT4_GD_INODE_TABLE_LO_OFF);
    if (fs->desc_size >= 64)
        itable |= (uint64_t)rd32(d + EXT4_GD_INODE_TABLE_HI_OFF) << 32;
    if (itable == 0 || itable >= fs->blocks_count) return EXTW_ERR_FORMAT;

    *out = (off_t)(itable * fs->block_size + (uint64_t)index * fs->inode_size);
    return EXTW_OK;
}

static int read_inode(ext4_wfs *fs, uint32_t ino, uint8_t *buf) {
    off_t at;
    int rc = inode_offset(fs, ino, &at);
    if (rc != EXTW_OK) return rc;
    if (fseeko(fs->fp, at, SEEK_SET)) return EXTW_ERR_IO;
    if (fread(buf, 1, fs->inode_size, fs->fp) != fs->inode_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

/* Stamps the checksum in before writing, so the two can never be written apart. */
static int write_inode(ext4_wfs *fs, uint32_t ino, uint8_t *buf) {
    uint32_t generation = rd32(buf + INODE_GENERATION_OFF);
    uint32_t c = ext4_inode_csum(fs->csum_seed, ino, generation, buf, fs->inode_size);

    wr16(buf + EXT4_INODE_CSUM_LO_OFF, (uint16_t)c);
    if (fs->inode_size > 128 && ext4_inode_has_checksum_hi(buf))
        wr16(buf + EXT4_INODE_CSUM_HI_OFF, (uint16_t)(c >> 16));

    off_t at;
    int rc = inode_offset(fs, ino, &at);
    if (rc != EXTW_OK) return rc;
    if (fseeko(fs->fp, at, SEEK_SET)) return EXTW_ERR_IO;
    if (fwrite(buf, 1, fs->inode_size, fs->fp) != fs->inode_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

static uint64_t inode_size_get(const uint8_t *inode) {
    return (uint64_t)rd32(inode + INODE_SIZE_LO_OFF) |
           ((uint64_t)rd32(inode + INODE_SIZE_HI_OFF) << 32);
}
static void inode_size_set(uint8_t *inode, uint64_t v) {
    wr32(inode + INODE_SIZE_LO_OFF, (uint32_t)v);
    wr32(inode + INODE_SIZE_HI_OFF, (uint32_t)(v >> 32));
}

/* i_blocks counts 512-byte sectors, not filesystem blocks, and spills into osd2
 * past 2^32 sectors. Metadata blocks are counted here too, which is why this
 * adjusts the stored value rather than deriving it from the extent tree. */
static uint64_t inode_blocks_get(const uint8_t *inode) {
    return (uint64_t)rd32(inode + INODE_BLOCKS_LO_OFF) |
           ((uint64_t)rd16(inode + INODE_BLOCKS_HI_OFF) << 32);
}
static void inode_blocks_set(uint8_t *inode, uint64_t v) {
    wr32(inode + INODE_BLOCKS_LO_OFF, (uint32_t)v);
    wr16(inode + INODE_BLOCKS_HI_OFF, (uint16_t)(v >> 32));
}

/* ── The extent root ──────────────────────────────────────────────────────── */

static int write_data_block(ext4_wfs *fs, uint64_t block, const uint8_t *buf) {
    if (fseeko(fs->fp, (off_t)block * fs->block_size, SEEK_SET)) return EXTW_ERR_IO;
    if (fwrite(buf, 1, fs->block_size, fs->fp) != fs->block_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

static uint64_t ee_physical(const uint8_t *e) {
    return (uint64_t)rd32(e + EE_START_LO_OFF) |
           ((uint64_t)rd16(e + EE_START_HI_OFF) << 32);
}
static void ee_set_physical(uint8_t *e, uint64_t p) {
    wr32(e + EE_START_LO_OFF, (uint32_t)p);
    wr16(e + EE_START_HI_OFF, (uint16_t)(p >> 32));
}

int ext4_append_blocks(ext4_wfs *fs, uint32_t ino, uint32_t count,
                       ext4_fill_fn fill, void *user) {
    uint8_t *inode = malloc(fs->inode_size);
    uint8_t *block = malloc(fs->block_size);
    if (!inode || !block) { free(inode); free(block); return EXTW_ERR_IO; }

    int rc = read_inode(fs, ino, inode);
    if (rc != EXTW_OK) goto out;

    rc = EXTW_ERR_FORMAT;
    if (!(rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS)) goto out;

    uint8_t *root = inode + INODE_IBLOCK_OFF;
    if (rd16(root) != EXT4_EXTENT_MAGIC) goto out;

    uint16_t entries = rd16(root + EH_ENTRIES_OFF);
    uint16_t max     = rd16(root + EH_MAX_OFF);
    uint16_t depth   = rd16(root + EH_DEPTH_OFF);
    if (max > (INODE_IBLOCK_SIZE - 12) / 12 || entries > max) goto out;

    /* Depth is refused rather than mishandled. A deeper tree needs the last leaf
     * block found, extended and re-checksummed, which is a different job. */
    if (depth != 0) { rc = EXTW_ERR_DEPTH; goto out; }

    /*
     * Where the file currently ends, in logical blocks - the later of what the
     * extents map and what i_size claims, because the two disagree routinely.
     *
     * A file ending in a hole has i_size past its last extent, and appending at
     * the last extent instead would write into the middle of that hole and then
     * set a size smaller than the one it started with. A file whose length is not
     * a multiple of the block size has i_size short of a full block, and rounding
     * down would overwrite the tail of its own last block.
     */
    uint32_t next_logical = 0;
    if (entries > 0) {
        const uint8_t *last = root + 12 + (size_t)(entries - 1) * 12;
        uint16_t raw = rd16(last + EE_LEN_OFF);
        uint32_t len = raw > EXT4_MAX_INIT_LEN ? raw - EXT4_MAX_INIT_LEN : raw;
        next_logical = rd32(last + EE_BLOCK_OFF) + len;
    }
    uint32_t by_size = (uint32_t)((inode_size_get(inode) + fs->block_size - 1) /
                                  fs->block_size);
    if (by_size > next_logical) next_logical = by_size;

    uint64_t added = 0;
    for (uint32_t i = 0; i < count; i++) {
        uint8_t *last = entries ? root + 12 + (size_t)(entries - 1) * 12 : NULL;
        uint16_t last_raw = last ? rd16(last + EE_LEN_OFF) : 0;
        int last_uninit   = last_raw > EXT4_MAX_INIT_LEN;
        uint32_t last_len = last_uninit ? last_raw - EXT4_MAX_INIT_LEN : last_raw;

        uint64_t goal = last ? ee_physical(last) + last_len : 0;

        int64_t got = ext4_alloc_block_goal(fs, goal);
        if (got < 0) { rc = EXTW_ERR_NOSPACE; goto out; }

        if (fill && fill(user, next_logical, block) != 0) { rc = EXTW_ERR_IO; goto out; }
        else if (!fill) memset(block, 0, fs->block_size);
        rc = write_data_block(fs, (uint64_t)got, block);
        if (rc != EXTW_OK) goto out;

        /*
         * Merge into the last extent only when the new block continues it in both
         * senses. Physical adjacency alone is not enough: when the file ends in a
         * hole, next_logical is past where the last extent stops, and stretching
         * ee_len over that gap would claim the hole as data.
         *
         * An uninitialised extent is never merged into either - its length field
         * carries the preallocation marker, and data written into it would still
         * read back as zeroes until that is cleared.
         */
        int logically_next = last &&
            rd32(last + EE_BLOCK_OFF) + last_len == next_logical;

        if (last && logically_next && !last_uninit && (uint64_t)got == goal &&
            last_len + 1 <= EXT4_MAX_INIT_LEN) {
            wr16(last + EE_LEN_OFF, (uint16_t)(last_len + 1));
        } else {
            if (entries >= max) { rc = EXTW_ERR_FULL; goto out; }
            uint8_t *slot = root + 12 + (size_t)entries * 12;
            wr32(slot + EE_BLOCK_OFF, next_logical);
            wr16(slot + EE_LEN_OFF, 1);
            ee_set_physical(slot, (uint64_t)got);
            entries++;
            wr16(root + EH_ENTRIES_OFF, entries);
        }

        next_logical++;
        added++;
    }

    inode_size_set(inode, (uint64_t)next_logical * fs->block_size);
    inode_blocks_set(inode, inode_blocks_get(inode) + added * (fs->block_size / 512));

    rc = write_inode(fs, ino, inode);
    if (rc != EXTW_OK) goto out;
    rc = ext4_fs_flush(fs) ? EXTW_ERR_IO : EXTW_OK;

out:
    free(inode);
    free(block);
    return rc;
}
