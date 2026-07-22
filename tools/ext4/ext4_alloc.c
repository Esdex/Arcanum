/*
 * Block allocation.
 *
 * Clean-room, from the published on-disk format. Taking a block is not one edit
 * but five, and they are a dependency chain rather than a list:
 *
 *   1. set the bit in the group's block bitmap
 *   2. recompute the bitmap checksum, into the group descriptor
 *   3. decrement bg_free_blocks_count in that descriptor
 *   4. recompute the descriptor checksum - it has to cover both 2 and 3
 *   5. decrement s_free_blocks_count, then recompute the superblock checksum last
 *
 * Doing 4 before 3 gives a descriptor that is internally consistent and still
 * wrong, which is the failure mode this whole layer has to be defended against:
 * every structure well-formed with a valid checksum, and no agreement between
 * them. That is invisible to a reader and invisible to a checksum verifier. Only
 * e2fsck sees it, which is why fsckcheck.py runs it after every write.
 *
 * Two groups are refused rather than handled:
 *
 *   BLOCK_UNINIT - the bitmap block was never written out, so what is on disk is
 *   not a bitmap. Allocating from it would mean synthesising the whole thing
 *   first, including the metadata blocks the group owns. That is its own piece of
 *   work and it is not this one.
 *
 *   Anything past the end of the filesystem. The final group is usually partial,
 *   and the bits covering blocks that do not exist are already set to 1, so a
 *   find-first-zero scan avoids them without being told to. The explicit clamp is
 *   what makes that true by construction instead of by luck.
 *
 * The backup superblocks and descriptor tables are deliberately left stale. The
 * kernel does the same, refreshing them on unmount and resize rather than on every
 * allocation. What matters here is that this decision cannot be checked the way
 * everything else in this file is: `e2fsck -fn` never reads the backups at all -
 * corrupting one on purpose produces byte-identical output - so no test can tell
 * a considered deferral from an oversight. It is written down instead.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_alloc.h"
#include "ext4_csum.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void wr16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8);
}
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

static uint8_t *group_desc(const ext4_wfs *fs, uint32_t g) {
    return fs->desc + (size_t)g * fs->desc_size;
}

static int is_64bit(const ext4_wfs *fs) { return fs->desc_size >= 64; }

/* Blocks this group actually covers. Every group holds blocks_per_group except
 * the last, which stops where the filesystem does. */
static uint32_t group_block_count(const ext4_wfs *fs, uint32_t g) {
    uint64_t start  = (uint64_t)fs->first_data_block +
                      (uint64_t)g * fs->blocks_per_group;
    uint64_t remain = fs->blocks_count - start;
    return remain < fs->blocks_per_group ? (uint32_t)remain : fs->blocks_per_group;
}

static uint64_t group_bitmap_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_BLOCK_BITMAP_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_BLOCK_BITMAP_HI_OFF) << 32;
    return b;
}

static uint32_t group_free_blocks(const ext4_wfs *fs, const uint8_t *d) {
    uint32_t v = rd16(d + EXT4_GD_FREE_BLOCKS_LO_OFF);
    if (is_64bit(fs)) v |= (uint32_t)rd16(d + EXT4_GD_FREE_BLOCKS_HI_OFF) << 16;
    return v;
}

static void group_set_free_blocks(const ext4_wfs *fs, uint8_t *d, uint32_t v) {
    wr16(d + EXT4_GD_FREE_BLOCKS_LO_OFF, (uint16_t)v);
    if (is_64bit(fs)) wr16(d + EXT4_GD_FREE_BLOCKS_HI_OFF, (uint16_t)(v >> 16));
}

uint64_t ext4_sb_free_blocks(const ext4_wfs *fs) {
    return (uint64_t)rd32(fs->sb + EXT4_SB_FREE_BLOCKS_LO_OFF) |
           ((uint64_t)rd32(fs->sb + EXT4_SB_FREE_BLOCKS_HI_OFF) << 32);
}

static void sb_set_free_blocks(ext4_wfs *fs, uint64_t v) {
    wr32(fs->sb + EXT4_SB_FREE_BLOCKS_LO_OFF, (uint32_t)v);
    wr32(fs->sb + EXT4_SB_FREE_BLOCKS_HI_OFF, (uint32_t)(v >> 32));
}

static int read_bitmap(ext4_wfs *fs, const uint8_t *d) {
    uint64_t at = group_bitmap_block(fs, d) * (uint64_t)fs->block_size;
    return ext4_io_pread(&fs->io, at, fs->bitmap, fs->bitmap_bytes);
}

static int write_bitmap(ext4_wfs *fs, const uint8_t *d) {
    uint64_t at = group_bitmap_block(fs, d) * (uint64_t)fs->block_size;
    return ext4_io_pwrite(&fs->io, at, fs->bitmap, fs->bitmap_bytes);
}

/* Step 2: the bitmap's checksum is stored in the descriptor that owns it, split
 * in half, and the high half only exists on a 64-bit filesystem. */
static void store_bitmap_csum(const ext4_wfs *fs, uint8_t *d) {
    uint32_t c = ext4_bitmap_csum(fs->csum_seed, fs->bitmap, fs->bitmap_bytes);
    wr16(d + EXT4_GD_BBITMAP_CSUM_LO_OFF, (uint16_t)c);
    if (is_64bit(fs)) wr16(d + EXT4_GD_BBITMAP_CSUM_HI_OFF, (uint16_t)(c >> 16));
}

/* Step 4. Must run after both the bitmap checksum and the free count are in
 * place, since it covers the bytes they live in. */
static void store_desc_csum(const ext4_wfs *fs, uint32_t g, uint8_t *d) {
    wr16(d + EXT4_GD_CSUM_OFF, 0);
    wr16(d + EXT4_GD_CSUM_OFF,
         (uint16_t)ext4_group_desc_csum(fs->csum_seed, g, d, fs->desc_size));
}

/* Host block callbacks, backing ext4_fs_open with a plain file. The device
 * supplies its own; these are why the tools need no container to run. */
static int host_read_block(void *user, uint64_t block, void *buf) {
    ext4_wfs *fs = (ext4_wfs *)user;
    off_t at = (off_t)block * fs->io.block_size;
    if (fseeko(fs->host_fp, at, SEEK_SET)) return -1;
    return fread(buf, 1, fs->io.block_size, fs->host_fp) == fs->io.block_size ? 0 : -1;
}
static int host_write_block(void *user, uint64_t block, const void *buf) {
    ext4_wfs *fs = (ext4_wfs *)user;
    off_t at = (off_t)block * fs->io.block_size;
    if (fseeko(fs->host_fp, at, SEEK_SET)) return -1;
    return fwrite(buf, 1, fs->io.block_size, fs->host_fp) == fs->io.block_size ? 0 : -1;
}
static int host_flush(void *user) {
    return fflush(((ext4_wfs *)user)->host_fp);
}

/*
 * Shared tail of both openers. `io` is already wired to its block callbacks; this
 * parses the superblock through it and fills the rest in.
 *
 * The superblock lives at byte 1024, which is not a block boundary once blocks
 * are bigger than 1 KiB, so it cannot be read until the block size is known - the
 * chicken-and-egg the reader has too. It is broken the same way: read at a
 * provisional 1 KiB block size, then switch io to the real one.
 */
static int fs_finish_open(ext4_wfs *fs) {
    fs->io.block_size = 1024;
    if (ext4_io_pread(&fs->io, EXT4_SB_OFFSET, fs->sb, sizeof(fs->sb))) goto fail;

    fs->block_size       = 1024u << rd32(fs->sb + EXT4_SB_LOG_BLOCK_SIZE_OFF);
    fs->io.block_size    = fs->block_size;
    fs->blocks_per_group = rd32(fs->sb + EXT4_SB_BLOCKS_PER_GRP_OFF);
    fs->first_data_block = rd32(fs->sb + EXT4_SB_FIRST_DATA_BLK_OFF);
    fs->csum_seed        = rd32(fs->sb + EXT4_SB_CSUM_SEED_OFF);
    fs->inodes_per_group = rd32(fs->sb + EXT4_SB_INODES_PER_GRP_OFF);
    fs->inode_size       = rd16(fs->sb + EXT4_SB_INODE_SIZE_OFF);
    fs->blocks_count     = (uint64_t)rd32(fs->sb + EXT4_SB_BLOCKS_LO_OFF) |
                           ((uint64_t)rd32(fs->sb + EXT4_SB_BLOCKS_HI_OFF) << 32);
    uint32_t incompat = rd32(fs->sb + EXT4_SB_FEATURE_INCOMPAT_OFF);
    fs->desc_size = (incompat & EXT4_FEATURE_INCOMPAT_64BIT)
                    ? rd16(fs->sb + EXT4_SB_DESC_SIZE_OFF) : 32;
    if (!fs->blocks_per_group || !fs->desc_size) goto fail;

    /*
     * Refuse a filesystem whose journal still has work in it.
     *
     * Nothing here writes through the journal, which is safe only while there is
     * nothing in it to replay. If there is - the flag is set on a mount and
     * cleared on a clean unmount, so it survives a crash or a pulled cable -
     * then every write made around it is provisional: the next thing to mount
     * this filesystem will replay those transactions over the top and quietly
     * undo them. Blocks we allocated come back marked free, entries we added
     * disappear, and nothing reports an error because replay is exactly what is
     * supposed to happen.
     *
     * That is silent data loss, and the filesystem cannot be written safely
     * until something replays the journal. Refusing to open it is the honest
     * answer until this can journal its own writes.
     */
    if (incompat & EXT4_FEATURE_INCOMPAT_RECOVER) goto fail;

    fs->groups = (uint32_t)((fs->blocks_count - fs->first_data_block +
                             fs->blocks_per_group - 1) / fs->blocks_per_group);
    fs->bitmap_bytes = fs->blocks_per_group / 8;

    fs->desc   = malloc((size_t)fs->groups * fs->desc_size);
    fs->bitmap = malloc(fs->bitmap_bytes);
    if (!fs->desc || !fs->bitmap) goto fail;

    uint64_t desc_at = (fs->first_data_block + 1) * (uint64_t)fs->block_size;
    size_t desc_len = (size_t)fs->groups * fs->desc_size;
    if (ext4_io_pread(&fs->io, desc_at, fs->desc, desc_len)) goto fail;
    return 0;

fail:
    ext4_fs_close(fs);
    return -1;
}

int ext4_fs_open(ext4_wfs *fs, const char *path) {
    memset(fs, 0, sizeof(*fs));
    fs->host_fp = fopen(path, "r+b");
    if (!fs->host_fp) return -1;
    fs->io.read_block  = host_read_block;
    fs->io.write_block = host_write_block;
    fs->io.flush       = host_flush;
    fs->io.user        = fs;
    return fs_finish_open(fs);
}

int ext4_fs_open_io(ext4_wfs *fs, ext4_io io) {
    memset(fs, 0, sizeof(*fs));
    fs->host_fp = NULL;
    fs->io      = io;
    return fs_finish_open(fs);
}

/* Step 5's second half. The superblock checksum is computed over the superblock
 * as it will be written, so this is the last thing to happen. */
int ext4_fs_flush(ext4_wfs *fs) {
    uint64_t desc_at = (fs->first_data_block + 1) * (uint64_t)fs->block_size;
    size_t desc_len = (size_t)fs->groups * fs->desc_size;
    if (ext4_io_pwrite(&fs->io, desc_at, fs->desc, desc_len)) return -1;

    wr32(fs->sb + EXT4_SB_CSUM_OFF, ext4_superblock_csum(fs->sb));
    if (ext4_io_pwrite(&fs->io, EXT4_SB_OFFSET, fs->sb, sizeof(fs->sb))) return -1;
    return ext4_io_flush(&fs->io);
}

void ext4_fs_close(ext4_wfs *fs) {
    if (fs->host_fp) fclose(fs->host_fp);
    free(fs->desc);
    free(fs->bitmap);
    memset(fs, 0, sizeof(*fs));
}

#define ALLOC_NONE    (-1)   /* nothing free here, try elsewhere */
#define ALLOC_CORRUPT (-2)   /* the group contradicts itself, stop entirely */

/*
 * Takes the first free block in group `g` at or after `start_bit`.
 *
 * A group whose free count promises space its bitmap does not have is corruption,
 * not a reason to move on quietly - but only when the whole group was searched.
 * Starting part way in, as a goal search does, can legitimately find nothing while
 * free blocks sit behind the starting point.
 */
static int64_t alloc_in_group(ext4_wfs *fs, uint32_t g, uint32_t start_bit) {
    uint8_t *d = group_desc(fs, g);
    if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_BLOCK_UNINIT) return ALLOC_NONE;
    if (group_free_blocks(fs, d) == 0) return ALLOC_NONE;
    if (read_bitmap(fs, d)) return ALLOC_CORRUPT;

    uint32_t limit = group_block_count(fs, g);
    for (uint32_t bit = start_bit; bit < limit; bit++) {
        if (fs->bitmap[bit >> 3] & (1u << (bit & 7))) continue;

        fs->bitmap[bit >> 3] |= (uint8_t)(1u << (bit & 7));   /* 1 */
        if (write_bitmap(fs, d)) return ALLOC_CORRUPT;
        store_bitmap_csum(fs, d);                             /* 2 */
        group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);  /* 3 */
        store_desc_csum(fs, g, d);                            /* 4 */
        sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) - 1);   /* 5 */

        return (int64_t)((uint64_t)fs->first_data_block +
                         (uint64_t)g * fs->blocks_per_group + bit);
    }
    return start_bit == 0 ? ALLOC_CORRUPT : ALLOC_NONE;
}

int64_t ext4_alloc_block_goal(ext4_wfs *fs, uint64_t goal) {
    if (goal >= fs->first_data_block && goal < fs->blocks_count) {
        uint64_t rel = goal - fs->first_data_block;
        uint32_t g   = (uint32_t)(rel / fs->blocks_per_group);
        if (g < fs->groups) {
            int64_t b = alloc_in_group(fs, g, (uint32_t)(rel % fs->blocks_per_group));
            if (b >= 0) return b;
            if (b == ALLOC_CORRUPT) return -1;
        }
    }
    for (uint32_t g = 0; g < fs->groups; g++) {
        int64_t b = alloc_in_group(fs, g, 0);
        if (b >= 0) return b;
        if (b == ALLOC_CORRUPT) return -1;
    }
    return -1;
}

int64_t ext4_alloc_block(ext4_wfs *fs) {
    return ext4_alloc_block_goal(fs, 0);
}

int ext4_free_block(ext4_wfs *fs, uint64_t block) {
    if (block < fs->first_data_block || block >= fs->blocks_count) return -1;

    uint64_t rel = block - fs->first_data_block;
    uint32_t g   = (uint32_t)(rel / fs->blocks_per_group);
    uint32_t bit = (uint32_t)(rel % fs->blocks_per_group);
    if (g >= fs->groups || bit >= group_block_count(fs, g)) return -1;

    uint8_t *d = group_desc(fs, g);
    if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_BLOCK_UNINIT) return -1;
    if (read_bitmap(fs, d)) return -1;
    if (!(fs->bitmap[bit >> 3] & (1u << (bit & 7)))) return -1;   /* already free */

    fs->bitmap[bit >> 3] &= (uint8_t)~(1u << (bit & 7));
    if (write_bitmap(fs, d)) return -1;
    store_bitmap_csum(fs, d);
    group_set_free_blocks(fs, d, group_free_blocks(fs, d) + 1);
    store_desc_csum(fs, g, d);
    sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) + 1);
    return 0;
}
