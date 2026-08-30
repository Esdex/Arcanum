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

/* Block allocation: the first thing here that writes. See the .c for the order
 * the updates have to happen in, which is not free to rearrange. */
#ifndef ARCANUM_EXT4_ALLOC_H
#define ARCANUM_EXT4_ALLOC_H

#include "ext4_io.h"

#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Superblock */
#define EXT4_SB_OFFSET              1024
#define EXT4_SB_BLOCKS_LO_OFF       0x04
#define EXT4_SB_FREE_BLOCKS_LO_OFF  0x0C
#define EXT4_SB_FIRST_DATA_BLK_OFF  0x14
#define EXT4_SB_LOG_BLOCK_SIZE_OFF  0x18
#define EXT4_SB_BLOCKS_PER_GRP_OFF  0x20
/* s_wtime, the last time anything wrote this filesystem. See write_superblock. */
#define EXT4_SB_WTIME_OFF           0x30
/* s_state. Bit 0 is what every ext4 driver means by "this volume was put down
 * tidily"; a driver clears it while it has writes outstanding and sets it again
 * when they are all on disk, so finding it clear on open means the last session
 * did not finish. See ext4_fs_mark_dirty. */
#define EXT4_SB_STATE_OFF           0x3A
#define EXT4_STATE_CLEAN            0x0001
#define EXT4_SB_FEATURE_INCOMPAT_OFF 0x60
#define EXT4_SB_FEATURE_RO_COMPAT_OFF 0x64
/* Room kept in front of the descriptor table for it to grow, behind every backup
 * superblock. Part of a group's metadata even though nothing lives there yet. */
#define EXT4_SB_RESERVED_GDT_OFF    0xCE
#define EXT4_SB_DESC_SIZE_OFF       0xFE
#define EXT4_SB_BLOCKS_HI_OFF       0x150
#define EXT4_SB_FREE_BLOCKS_HI_OFF  0x158
#define EXT4_SB_CSUM_SEED_OFF       0x270
#define EXT4_SB_UUID_OFF            0x68

#define EXT4_FEATURE_INCOMPAT_64BIT   0x80
/* Whether s_checksum_seed holds the seed outright. Without this feature the field
 * is not maintained and the seed is the crc32c of the UUID instead. */
#define EXT4_FEATURE_INCOMPAT_CSUM_SEED 0x2000
#define EXT4_FEATURE_INCOMPAT_RECOVER 0x04   /* the journal has work outstanding */
/* Backup superblocks only in groups 0, 1 and the powers of 3, 5 and 7. Without
 * it every group carries one, which changes where a group's free space starts. */
#define EXT4_FEATURE_RO_COMPAT_SPARSE_SUPER 0x01

#define EXT4_SB_INODES_COUNT_OFF    0x00
#define EXT4_SB_FREE_INODES_OFF     0x10
#define EXT4_SB_INODES_PER_GRP_OFF  0x28
#define EXT4_SB_INODE_SIZE_OFF      0x58

/* Group descriptor */
#define EXT4_GD_BLOCK_BITMAP_LO_OFF 0x00
#define EXT4_GD_INODE_BITMAP_LO_OFF 0x04
#define EXT4_GD_FREE_INODES_LO_OFF  0x0E
#define EXT4_GD_USED_DIRS_LO_OFF    0x10
#define EXT4_GD_USED_DIRS_HI_OFF    0x30
#define EXT4_GD_ITABLE_UNUSED_LO_OFF 0x1C
#define EXT4_GD_INODE_BITMAP_HI_OFF 0x24
#define EXT4_GD_FREE_INODES_HI_OFF  0x2E
#define EXT4_GD_ITABLE_UNUSED_HI_OFF 0x32
#define EXT4_GD_INODE_TABLE_LO_OFF  0x08
#define EXT4_GD_FREE_BLOCKS_LO_OFF  0x0C
#define EXT4_GD_FLAGS_OFF           0x12
#define EXT4_GD_BLOCK_BITMAP_HI_OFF 0x20
#define EXT4_GD_INODE_TABLE_HI_OFF  0x28
#define EXT4_GD_FREE_BLOCKS_HI_OFF  0x2C

/* bg_flags. The two uninit bits are easy to mistake for each other, and doing so
 * fails silently - see the note in fsmeta.c. */
#define EXT4_BG_INODE_UNINIT        0x0001
#define EXT4_BG_BLOCK_UNINIT        0x0002
#define EXT4_BG_INODE_ZEROED        0x0004

/*
 * A writable handle on a whole image, holding the descriptor table in memory.
 *
 * Distinct from the reader's `ext4_fs` in ext4_extents.h on purpose: that one
 * reaches the disk through a read-only block callback, and this one mutates. Both
 * now reach the disk the same way - through block callbacks - so both can run
 * over an encrypted container; `io` is where this one's writes go. A caller that
 * needs both keeps one of each.
 *
 * `host_fp` is set only by ext4_fs_open, the convenience opener that backs `io`
 * with a plain file for the host tools. On the device ext4_fs_open_io is used
 * instead and host_fp stays NULL.
 */
typedef struct {
    ext4_io  io;
    FILE    *host_fp;
    uint8_t  sb[1024];
    uint8_t *desc;             /* the whole descriptor table, held in memory */
    /*
     * What the descriptor table looked like on disk after the last successful flush.
     *
     * A flush writes only the blocks of `desc` that differ from this and then brings it
     * up to date, which is what stops one changed counter from rewriting the whole table
     * (#160). It is a shadow copy rather than a set of dirty flags on purpose: six places
     * in two files take a mutable pointer into `desc`, and any new one would have to
     * remember to raise a flag. Nothing has to remember a memcmp. The cost is one more
     * copy of the table - 32 KB on a 64 GB volume - against writing it in full on every
     * operation.
     */
    uint8_t *desc_shadow;
    uint8_t *bitmap;           /* the block bitmap of group `bitmap_group` */
    int64_t  bitmap_group;     /* which group `bitmap` holds, or -1 for none */
    /*
     * A run of blocks taken from the bitmap ahead of being needed (#161). See the
     * long note above ext4_alloc_block_goal in the .c for why this exists and, more
     * importantly, for the ordering it must not break. `resv_left` blocks starting
     * at `resv_next` are marked in use ON DISK and belong to nobody yet; the flush
     * gives back whatever was not handed out.
     */
    uint64_t resv_next;
    uint32_t resv_left;
    uint32_t resv_group;
    uint64_t last_alloc;       /* the last block handed out, to spot a run forming */
    uint32_t block_size;
    uint32_t blocks_per_group;
    uint32_t first_data_block;
    uint32_t desc_size;
    uint32_t groups;
    uint32_t csum_seed;
    uint32_t bitmap_bytes;
    uint32_t inode_size;
    uint32_t inodes_per_group;
    uint64_t blocks_count;
    /*
     * What to stamp the superblock's last-write time with, or 0 to leave that field
     * exactly as it was found (#156). Passed in rather than read from the clock, for the
     * same reason ext4_create_file takes its `when`: the same inputs must give the same
     * image, or no test can compare one byte for byte. Every opener zeroes the struct, so
     * a caller that does not care about the field does not have to know it exists.
     */
    uint32_t now;
} ext4_wfs;

/* Opens `path` for writing, backing the block callbacks with that file. This is
 * the host tools' opener; the device uses ext4_fs_open_io. */
int  ext4_fs_open(ext4_wfs *fs, const char *path);

/* Opens over a caller-supplied block interface - the device path. `io.block_size`
 * may be left 0; it is set from the superblock. The caller keeps ownership of
 * whatever `io.user` points at. */
int  ext4_fs_open_io(ext4_wfs *fs, ext4_io io);

int  ext4_fs_flush(ext4_wfs *fs);
void ext4_fs_close(ext4_wfs *fs);

/*
 * Says on disk whether a write is outstanding, so that a session cut short can be
 * told from one that finished.
 *
 * There is no journal here (see #7), so an operation stopped part way leaves
 * whatever its last completed write put there - always something e2fsck repairs
 * without cost, which faultcheck.py sweeps every write of every operation to
 * establish, but something. Nothing on disk said so until this: s_state was
 * stamped clean by the formatter and never touched again, so a vault killed in
 * the middle still claimed to be tidy and a desktop mounting it ran no check.
 *
 * mark_dirty clears the clean bit and puts the superblock down before the
 * operation's first write; mark_clean sets it and flushes everything after the
 * last. Ordering is the whole point and it is not symmetric: dirty has to reach
 * disk before anything it warns about, clean only after everything it covers.
 *
 * This is the field every ext4 driver already uses, rather than a marker of our
 * own, so a Linux desktop opening the same container reaches the same conclusion
 * and runs fsck by itself.
 */
int  ext4_fs_mark_dirty(ext4_wfs *fs);
int  ext4_fs_mark_clean(ext4_wfs *fs);

/* Reading the flag is the reader's job: ext4_open parses it into ext4_fs.is_clean,
 * so nothing has to reach for the superblock bytes to ask. */

/* Takes one block. Returns its number, or -1 when there is nowhere to put it. */
int64_t ext4_alloc_block(ext4_wfs *fs);

/*
 * The same, but tries `goal` first, then the rest of goal's group, before falling
 * back to a scan from the start. Passing the block after a file's last one is what
 * keeps an appended block adjacent to the data in front of it, which is the
 * difference between extending an existing extent and needing a new entry - and
 * the root inside an inode only holds four.
 */
int64_t ext4_alloc_block_goal(ext4_wfs *fs, uint64_t goal);

/* Gives one back. Returns 0, or -1 if the block is out of range, in a group whose
 * bitmap was never written, or was not allocated in the first place. */
int  ext4_free_block(ext4_wfs *fs, uint64_t block);

uint64_t ext4_sb_free_blocks(const ext4_wfs *fs);
uint32_t ext4_sb_free_inodes(const ext4_wfs *fs);

/*
 * Takes one inode, returning its number - which is 1-based, unlike everything
 * else here. Returns -1 when there is none to take.
 *
 * The inode is zeroed on the way out. An allocator that left the previous
 * tenant's bytes behind would hand a caller a file already claiming a size and a
 * set of extents, and on a filesystem meant to hide its contents that is a leak
 * as much as a bug.
 */
int64_t ext4_alloc_inode(ext4_wfs *fs);

/* Gives one back. Returns 0, or -1 if it is out of range, reserved, in a group
 * whose bitmap was never written, or was not allocated in the first place. */
int ext4_free_inode(ext4_wfs *fs, uint32_t ino);

/*
 * Moves bg_used_dirs_count for the group `ino` belongs to, by +1 or -1.
 *
 * Kept apart from allocating the inode because only some inodes are directories,
 * and the descriptor cannot tell: to it an inode is an inode. Nothing else in the
 * write path touches this counter, which is exactly why it is easy to forget -
 * e2fsck counts directories for itself and compares, so a mkdir that does not
 * move it leaves every structure well-formed and the filesystem disagreeing with
 * itself about how many directories it holds.
 */
int ext4_adjust_used_dirs(ext4_wfs *fs, uint32_t ino, int delta);

#ifdef __cplusplus
}
#endif
#endif
