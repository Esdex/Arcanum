/* Block allocation: the first thing here that writes. See the .c for the order
 * the updates have to happen in, which is not free to rearrange. */
#ifndef ARCANUM_EXT4_ALLOC_H
#define ARCANUM_EXT4_ALLOC_H

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

#define EXT4_FEATURE_INCOMPAT_64BIT 0x80

/* Group descriptor */
#define EXT4_GD_BLOCK_BITMAP_LO_OFF 0x00
#define EXT4_GD_FREE_BLOCKS_LO_OFF  0x0C
#define EXT4_GD_FLAGS_OFF           0x12
#define EXT4_GD_BLOCK_BITMAP_HI_OFF 0x20
#define EXT4_GD_FREE_BLOCKS_HI_OFF  0x2C

/* bg_flags. The two uninit bits are easy to mistake for each other, and doing so
 * fails silently - see the note in fsmeta.c. */
#define EXT4_BG_INODE_UNINIT        0x0001
#define EXT4_BG_BLOCK_UNINIT        0x0002
#define EXT4_BG_INODE_ZEROED        0x0004

typedef struct {
    FILE    *fp;
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
    uint64_t blocks_count;
} ext4_fs;

int  ext4_fs_open(ext4_fs *fs, const char *path);
int  ext4_fs_flush(ext4_fs *fs);
void ext4_fs_close(ext4_fs *fs);

/* Takes one block. Returns its number, or -1 when there is nowhere to put it. */
int64_t ext4_alloc_block(ext4_fs *fs);

/* Gives one back. Returns 0, or -1 if the block is out of range, in a group whose
 * bitmap was never written, or was not allocated in the first place. */
int  ext4_free_block(ext4_fs *fs, uint64_t block);

uint64_t ext4_sb_free_blocks(const ext4_fs *fs);

#ifdef __cplusplus
}
#endif
#endif
