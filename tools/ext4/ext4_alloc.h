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
#define EXT4_SB_FEATURE_INCOMPAT_OFF 0x60
#define EXT4_SB_DESC_SIZE_OFF       0xFE
#define EXT4_SB_BLOCKS_HI_OFF       0x150
#define EXT4_SB_FREE_BLOCKS_HI_OFF  0x158
#define EXT4_SB_CSUM_SEED_OFF       0x270

#define EXT4_FEATURE_INCOMPAT_64BIT   0x80
#define EXT4_FEATURE_INCOMPAT_RECOVER 0x04   /* the journal has work outstanding */

#define EXT4_SB_INODES_COUNT_OFF    0x00
#define EXT4_SB_FREE_INODES_OFF     0x10
#define EXT4_SB_INODES_PER_GRP_OFF  0x28
#define EXT4_SB_INODE_SIZE_OFF      0x58

/* Group descriptor */
#define EXT4_GD_BLOCK_BITMAP_LO_OFF 0x00
#define EXT4_GD_INODE_BITMAP_LO_OFF 0x04
#define EXT4_GD_FREE_INODES_LO_OFF  0x0E
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
    uint8_t *bitmap;           /* scratch for one group's block bitmap */
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

#ifdef __cplusplus
}
#endif
#endif
