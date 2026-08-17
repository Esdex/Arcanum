/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room ext4: no GPL ext4 source (lwext4's src/ext4_extent.c or
 * src/ext4_xattr.c) was opened or consulted. The on-disk format is implemented
 * from its published description - a data structure, not anyone's expression of
 * it - which is what keeps this code free of lwext4's copyleft. See issue #7.
 */

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
 * A group flagged BLOCK_UNINIT has no bitmap on disk at all - what sits in the
 * bitmap block is whatever was there before the filesystem was made. It is not a
 * damaged group, it is one nobody has needed yet, and mke2fs leaves most of a
 * fresh volume in that state. Skipping them, which is what this used to do, made
 * two thirds of a desktop-formatted container unreachable and reported the result
 * as "no space" (#140). They are initialised on first use instead, which is what
 * the kernel does: rebuild the bitmap from the layout, clear the flag, allocate
 * normally.
 *
 * The rebuild is checked against the group's own free count before a byte of it
 * is written - see init_block_group. That is the whole safety argument: nothing
 * here trusts its model of where the metadata lies, it derives one and then
 * requires the descriptor to agree with it.
 *
 * Anything past the end of the filesystem is refused. The final group is usually
 * partial, and the bits covering blocks that do not exist are already set to 1, so
 * a find-first-zero scan avoids them without being told to. The explicit clamp is
 * what makes that true by construction instead of by luck - and once a group can
 * be initialised here, setting those bits is this file's job rather than mke2fs's.
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
#include "ext4_extents.h"   /* EXT4_SUPPORTED_INCOMPAT / EXT4_SUPPORTED_RO_COMPAT */
#include "ext4_log.h"

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

static uint64_t group_first_block(const ext4_wfs *fs, uint32_t g) {
    return (uint64_t)fs->first_data_block + (uint64_t)g * fs->blocks_per_group;
}

/* Blocks this group actually covers. Every group holds blocks_per_group except
 * the last, which stops where the filesystem does. */
static uint32_t group_block_count(const ext4_wfs *fs, uint32_t g) {
    uint64_t remain = fs->blocks_count - group_first_block(fs, g);
    return remain < fs->blocks_per_group ? (uint32_t)remain : fs->blocks_per_group;
}

static uint64_t group_bitmap_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_BLOCK_BITMAP_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_BLOCK_BITMAP_HI_OFF) << 32;
    return b;
}

static uint64_t group_ibitmap_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_INODE_BITMAP_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_INODE_BITMAP_HI_OFF) << 32;
    return b;
}

static uint64_t group_itable_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_INODE_TABLE_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_INODE_TABLE_HI_OFF) << 32;
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

/*
 * Loads group `g`'s block bitmap into fs->bitmap, but only when it is not the one
 * already there. Every change to the bitmap goes through fs->bitmap and is written
 * back in the same call (write_bitmap), so the in-memory copy is authoritative for
 * the group it holds - re-reading it would fetch what we just wrote. A file being
 * streamed in allocates block after block in one group, so this turns N reads of
 * the bitmap into one. The write per allocation stays: it is what keeps the on-disk
 * bitmap consistent after every block, the "a short append is committed" property.
 */
static int load_bitmap(ext4_wfs *fs, uint32_t g, const uint8_t *d) {
    if (fs->bitmap_group == (int64_t)g) return 0;
    if (read_bitmap(fs, d)) return -1;
    fs->bitmap_group = (int64_t)g;
    return 0;
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
static int host_read_block(void *user, uint64_t block, uint32_t block_size, void *buf) {
    ext4_wfs *fs = (ext4_wfs *)user;
    off_t at = (off_t)block * block_size;
    if (fseeko(fs->host_fp, at, SEEK_SET)) return -1;
    return fread(buf, 1, block_size, fs->host_fp) == block_size ? 0 : -1;
}
static int host_write_block(void *user, uint64_t block, uint32_t block_size, const void *buf) {
    ext4_wfs *fs = (ext4_wfs *)user;
    off_t at = (off_t)block * block_size;
    if (fseeko(fs->host_fp, at, SEEK_SET)) return -1;
    return fwrite(buf, 1, block_size, fs->host_fp) == block_size ? 0 : -1;
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

    uint32_t log_bs = rd32(fs->sb + EXT4_SB_LOG_BLOCK_SIZE_OFF);
    if (log_bs > 6) goto fail;                      /* keep the shift well-defined */
    fs->block_size       = 1024u << log_bs;
    /* Match the reader: refuse a block larger than the buffers hold. */
    if (fs->block_size > EXT4_MAX_BLOCK_SIZE) goto fail;
    fs->io.block_size    = fs->block_size;
    fs->blocks_per_group = rd32(fs->sb + EXT4_SB_BLOCKS_PER_GRP_OFF);
    fs->first_data_block = rd32(fs->sb + EXT4_SB_FIRST_DATA_BLK_OFF);
    fs->csum_seed        = rd32(fs->sb + EXT4_SB_CSUM_SEED_OFF);
    fs->inodes_per_group = rd32(fs->sb + EXT4_SB_INODES_PER_GRP_OFF);
    fs->inode_size       = rd16(fs->sb + EXT4_SB_INODE_SIZE_OFF);
    /* Match the reader: refuse an inode larger than the buffers hold. This side is
     * the one that needs it - `write_inode` writes and checksums fs->inode_size
     * bytes out of a caller's buffer, so without this bound a bigger inode takes
     * the difference off the stack and stamps a valid checksum over it (#144). */
    if (fs->inode_size < 128 || fs->inode_size > EXT4_MAX_INODE_SIZE) goto fail;
    fs->blocks_count     = (uint64_t)rd32(fs->sb + EXT4_SB_BLOCKS_LO_OFF) |
                           ((uint64_t)rd32(fs->sb + EXT4_SB_BLOCKS_HI_OFF) << 32);
    uint32_t incompat = rd32(fs->sb + EXT4_SB_FEATURE_INCOMPAT_OFF);
    fs->desc_size = (incompat & EXT4_FEATURE_INCOMPAT_64BIT)
                    ? rd16(fs->sb + EXT4_SB_DESC_SIZE_OFF) : 32;
    /* The block bitmap is held as blocks_per_group/8 bytes, so a count that is not
     * a whole number of bytes puts the last few blocks of every group past the end
     * of that buffer - a read on the allocation path and a write on the rebuild
     * one. It is not a real geometry either: mke2fs refuses a -g that is not a
     * multiple of 8.
     *
     * Both counts are also capped at eight per byte of a block, because each bitmap
     * is exactly one block - that is what decides how much a group holds, and every
     * image mke2fs makes sits at or under it. Uncapped, these are lengths the image
     * chooses for a malloc, a read and a crc32c: blocks_per_group drives fs->bitmap
     * and inodes_per_group the buffer in ext4_free_inode, so a superblock naming a
     * few hundred million turns one file operation into hundreds of megabytes of
     * work. Nothing overflows - the sizes agree with each other - it simply never
     * comes back, which on a phone is an OOM kill at mount time on a container
     * somebody else supplied. fuzz.sh found it in under two minutes (#147). */
    if (!fs->blocks_per_group || (fs->blocks_per_group & 7) ||
        fs->blocks_per_group > 8 * fs->block_size ||
        !fs->inodes_per_group || (fs->inodes_per_group & 7) ||
        fs->inodes_per_group > 8 * fs->block_size ||
        !fs->desc_size) goto fail;

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
    if (incompat & EXT4_FEATURE_INCOMPAT_RECOVER) {
        EXT4_LOGE("refusing to open: journal needs recovery (INCOMPAT_RECOVER); "
                  "writing around an unreplayed journal would lose the writes");
        goto fail;
    }

    /*
     * A writable open must understand every feature that governs layout or
     * allocation, in both fields: INCOMPAT to read it at all, RO_COMPAT because
     * this is about to write. A bit outside the supported masks (bigalloc,
     * meta_bg, inline_data, ...) would mean allocating or freeing the wrong
     * blocks - silent corruption of a foreign container - so refuse instead.
     */
    uint32_t ro_compat = rd32(fs->sb + EXT4_SB_FEATURE_RO_COMPAT_OFF);
    if ((incompat & ~EXT4_SUPPORTED_INCOMPAT) ||
        (ro_compat & ~EXT4_SUPPORTED_RO_COMPAT)) {
        EXT4_LOGE("refusing to open for writing: unsupported ext4 features "
                  "(incompat=0x%x ro_compat=0x%x)", incompat, ro_compat);
        goto fail;
    }

    fs->groups = (uint32_t)((fs->blocks_count - fs->first_data_block +
                             fs->blocks_per_group - 1) / fs->blocks_per_group);
    fs->bitmap_bytes = fs->blocks_per_group / 8;

    fs->desc   = malloc((size_t)fs->groups * fs->desc_size);
    fs->bitmap = malloc(fs->bitmap_bytes);
    if (!fs->desc || !fs->bitmap) goto fail;
    fs->bitmap_group = -1;   /* nothing loaded yet; group 0 is a valid value */

    uint64_t desc_at = (fs->first_data_block + 1) * (uint64_t)fs->block_size;
    size_t desc_len = (size_t)fs->groups * fs->desc_size;
    if (ext4_io_pread(&fs->io, desc_at, fs->desc, desc_len)) goto fail;

    EXT4_LOGI("opened: block_size=%u blocks=%llu groups=%u inodes/group=%u "
              "desc_size=%u", fs->block_size, (unsigned long long)fs->blocks_count,
              fs->groups, fs->inodes_per_group, fs->desc_size);
    return 0;

fail:
    EXT4_LOGE("open failed: not an openable ext4 image for writing");
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
/* The superblock and nothing else, checksum restamped so the two cannot be
 * written apart. */
static int write_superblock(ext4_wfs *fs) {
    wr32(fs->sb + EXT4_SB_CSUM_OFF, ext4_superblock_csum(fs->sb));
    return ext4_io_pwrite(&fs->io, EXT4_SB_OFFSET, fs->sb, sizeof(fs->sb)) ? -1 : 0;
}

int ext4_fs_flush(ext4_wfs *fs) {
    uint64_t desc_at = (fs->first_data_block + 1) * (uint64_t)fs->block_size;
    size_t desc_len = (size_t)fs->groups * fs->desc_size;
    if (ext4_io_pwrite(&fs->io, desc_at, fs->desc, desc_len)) return -1;

    if (write_superblock(fs)) return -1;
    return ext4_io_flush(&fs->io);
}

int ext4_fs_mark_dirty(ext4_wfs *fs) {
    uint16_t state = rd16(fs->sb + EXT4_SB_STATE_OFF);
    if (!(state & EXT4_STATE_CLEAN)) return 0;      /* already said so */
    wr16(fs->sb + EXT4_SB_STATE_OFF, (uint16_t)(state & ~EXT4_STATE_CLEAN));

    /* Only the superblock, and flushed on its own. The warning has to be on disk
     * before the writes it warns about, so batching it into the flush at the end
     * of the operation - where the descriptors go - would put it there after
     * everything it exists to cover. */
    if (write_superblock(fs)) return -1;
    return ext4_io_flush(&fs->io);
}

int ext4_fs_mark_clean(ext4_wfs *fs) {
    wr16(fs->sb + EXT4_SB_STATE_OFF,
         (uint16_t)(rd16(fs->sb + EXT4_SB_STATE_OFF) | EXT4_STATE_CLEAN));
    /* Through the ordinary flush, so the descriptors and the free counts reach
     * disk in the same breath: the clean bit means "everything is down", and it
     * would be a lie written before the things it speaks for. */
    return ext4_fs_flush(fs);
}

void ext4_fs_close(ext4_wfs *fs) {
    if (fs->host_fp) fclose(fs->host_fp);
    free(fs->desc);
    free(fs->bitmap);
    memset(fs, 0, sizeof(*fs));
}

/* ── Initialising a BLOCK_UNINIT group ────────────────────────────────────── */

/* Blocks the group descriptor table occupies, and the room kept in front of it
 * for the table to grow. Both sit immediately behind every backup superblock. */
static uint32_t gdt_blocks(const ext4_wfs *fs) {
    uint64_t bytes = (uint64_t)fs->groups * fs->desc_size;
    return (uint32_t)((bytes + fs->block_size - 1) / fs->block_size);
}

static uint32_t itable_blocks(const ext4_wfs *fs) {
    uint64_t bytes = (uint64_t)fs->inodes_per_group * fs->inode_size;
    return (uint32_t)((bytes + fs->block_size - 1) / fs->block_size);
}

static int is_power_of(uint32_t n, uint32_t base) {
    while (n > 1) {
        if (n % base) return 0;
        n /= base;
    }
    return n == 1;
}

/*
 * Whether group `g` carries a backup superblock and descriptor table.
 *
 * With sparse_super - on by default, and in the supported RO_COMPAT set - that is
 * groups 0, 1 and every power of 3, 5 or 7. Without it, every group has one. The
 * distinction matters here and nowhere else in this file: getting it wrong marks
 * a run of real data blocks as metadata, or hands the backup superblock out as
 * free space.
 */
static int group_has_super(const ext4_wfs *fs, uint32_t g) {
    if (!(rd32(fs->sb + EXT4_SB_FEATURE_RO_COMPAT_OFF) & EXT4_FEATURE_RO_COMPAT_SPARSE_SUPER))
        return 1;
    if (g <= 1) return 1;
    if ((g & 1) == 0) return 0;         /* every later one is odd */
    return is_power_of(g, 3) || is_power_of(g, 5) || is_power_of(g, 7);
}

/* Marks whatever part of the run [start, start+count) falls inside the group
 * covered by fs->bitmap, whose blocks run [gstart, gend). */
static void mark_run(ext4_wfs *fs, uint64_t gstart, uint64_t gend,
                     uint64_t start, uint32_t count) {
    uint64_t lo = start < gstart ? gstart : start;
    uint64_t hi = start + count > gend ? gend : start + count;
    for (uint64_t b = lo; b < hi; b++) {
        uint32_t bit = (uint32_t)(b - gstart);
        fs->bitmap[bit >> 3] |= (uint8_t)(1u << (bit & 7));
    }
}

static uint32_t count_set_bits(const uint8_t *map, uint32_t bits) {
    static const uint8_t nibble[16] = { 0,1,1,2,1,2,2,3,1,2,2,3,2,3,3,4 };
    uint32_t whole = bits >> 3, n = 0;
    for (uint32_t i = 0; i < whole; i++)
        n += nibble[map[i] & 0xF] + nibble[map[i] >> 4];
    for (uint32_t bit = whole << 3; bit < bits; bit++)
        if (map[bit >> 3] & (1u << (bit & 7))) n++;
    return n;
}

/*
 * Builds group `g`'s block bitmap from the layout and writes it out, so the group
 * can be allocated from like any other.
 *
 * What is in use in a never-touched group is exactly its metadata, and where that
 * lies is derivable: the backup superblock and descriptor table at the group's
 * front where there is one, plus any group's two bitmaps and inode table that
 * happen to land inside this group - which under flex_bg is usually none of them,
 * since flex_bg is what moves them elsewhere. Every group's descriptor is
 * consulted rather than only this one's, so a layout that puts a neighbour's
 * inode table here cannot go unnoticed.
 *
 * Nothing is written until the rebuilt bitmap agrees with the free count the
 * descriptor already carries. That is the check that makes this safe rather than
 * hopeful: if the two disagree, the model of the layout is wrong, and marking the
 * wrong blocks free would hand out an inode table. The group is left flagged and
 * skipped instead - the old behaviour, which costs space and nothing else.
 *
 * Returns 0 with the bitmap loaded and the flag cleared, or -1 having changed
 * nothing on disk.
 */
static int init_block_group(ext4_wfs *fs, uint32_t g, uint8_t *d) {
    uint32_t limit  = group_block_count(fs, g);
    uint64_t gstart = group_first_block(fs, g);
    uint64_t gend   = gstart + fs->blocks_per_group;

    /* fs->bitmap is about to hold something that is not on disk yet, so the group
     * it claims to cache has to be dropped first - a failure below must not leave
     * a reader believing this is still some other group's bitmap. */
    fs->bitmap_group = -1;
    memset(fs->bitmap, 0, fs->bitmap_bytes);

    if (group_has_super(fs, g))
        mark_run(fs, gstart, gend, gstart, 1 + gdt_blocks(fs) +
                 rd16(fs->sb + EXT4_SB_RESERVED_GDT_OFF));

    uint32_t itb = itable_blocks(fs);
    for (uint32_t g2 = 0; g2 < fs->groups; g2++) {
        const uint8_t *d2 = group_desc(fs, g2);
        mark_run(fs, gstart, gend, group_bitmap_block(fs, d2), 1);
        mark_run(fs, gstart, gend, group_ibitmap_block(fs, d2), 1);
        mark_run(fs, gstart, gend, group_itable_block(fs, d2), itb);
    }

    /* The last group stops before the group boundary. The bits past it cover
     * blocks that do not exist and have to read as used, which is the bookkeeping
     * leaving the group uninitialised used to avoid. */
    for (uint32_t bit = limit; bit < fs->blocks_per_group; bit++)
        fs->bitmap[bit >> 3] |= (uint8_t)(1u << (bit & 7));

    uint32_t free_here = limit - count_set_bits(fs->bitmap, limit);
    if (free_here != group_free_blocks(fs, d)) {
        EXT4_LOGE("group %u: rebuilt bitmap leaves %u blocks free, the descriptor "
                  "says %u - leaving the group uninitialised", g, free_here,
                  group_free_blocks(fs, d));
        return -1;
    }

    if (write_bitmap(fs, d)) return -1;
    fs->bitmap_group = (int64_t)g;
    store_bitmap_csum(fs, d);
    wr16(d + EXT4_GD_FLAGS_OFF,
         (uint16_t)(rd16(d + EXT4_GD_FLAGS_OFF) & ~EXT4_BG_BLOCK_UNINIT));
    store_desc_csum(fs, g, d);

    EXT4_LOGI("group %u initialised: %u of %u blocks free", g, free_here, limit);
    return 0;
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
    if (group_free_blocks(fs, d) == 0) return ALLOC_NONE;
    if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_BLOCK_UNINIT) {
        /* No bitmap on disk yet. Build one; a group that cannot be modelled is
         * skipped rather than guessed at, so this stays ALLOC_NONE. */
        if (init_block_group(fs, g, d)) return ALLOC_NONE;
    } else if (load_bitmap(fs, g, d)) {
        return ALLOC_CORRUPT;
    }

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
    /* A group still flagged BLOCK_UNINIT has never been allocated from - taking a
     * block is what initialises it - so a block being handed back from one did not
     * come from here. Refuse rather than build a bitmap to clear a bit in. */
    if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_BLOCK_UNINIT) return -1;
    if (load_bitmap(fs, g, d)) return -1;
    if (!(fs->bitmap[bit >> 3] & (1u << (bit & 7)))) return -1;   /* already free */

    fs->bitmap[bit >> 3] &= (uint8_t)~(1u << (bit & 7));
    if (write_bitmap(fs, d)) return -1;
    store_bitmap_csum(fs, d);
    group_set_free_blocks(fs, d, group_free_blocks(fs, d) + 1);
    store_desc_csum(fs, g, d);
    sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) + 1);
    return 0;
}
