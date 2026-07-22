/*
 * Making an ext4 filesystem out of nothing.
 *
 * Clean-room, from the published on-disk format. Every other file here changes a
 * filesystem that mke2fs already made; this is the one that has to produce the
 * starting point, because the device has no mke2fs on it and "create a container"
 * is the feature.
 *
 * The geometry is an input, not a decision made in here. mke2fs reads its block
 * size and inode ratio out of /etc/mke2fs.conf, and reimplementing that table
 * inside the formatter would put policy in the place where arithmetic has to be
 * checkable: the harness compares our image against one mke2fs made with the same
 * numbers, which is only possible while the numbers can be handed in.
 * ext4_mkfs_default_params is that policy, kept separate and small.
 */
#ifndef ARCANUM_EXT4_MKFS_H
#define ARCANUM_EXT4_MKFS_H

#include "ext4_io.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_MKFS_OK         0
#define EXT4_MKFS_ERR_IO    -1
#define EXT4_MKFS_ERR_PARAM -2   /* a parameter is not a value this can format with */
#define EXT4_MKFS_ERR_SMALL -3   /* too little space for even one group's metadata */
#define EXT4_MKFS_ERR_NOMEM -4

typedef struct {
    uint64_t blocks_count;   /* total blocks, including the ones metadata takes */
    uint32_t block_size;     /* 1024, 2048 or 4096 */
    uint32_t inodes_count;   /* rounded to a whole number per group, and capped at
                              * what one bitmap block per group can address */
    uint32_t inode_size;     /* 256 */
    uint32_t when;           /* creation time, stamped into every timestamp */

    /*
     * Both must be random, and on the device both come from the same generator the
     * container's keys do. The UUID is not decoration: s_checksum_seed is derived
     * from it, so two containers formatted with the same UUID checksum their
     * metadata identically.
     *
     * There is deliberately no volume label. A label is a name that survives on
     * unencrypted media in nothing but this structure, and an app whose point is
     * that a container does not announce itself has no use for one.
     */
    uint8_t  uuid[16];
    uint8_t  hash_seed[16];
} ext4_mkfs_params;

/*
 * What was actually made, which is not always what was asked for: a size whose
 * last group cannot pay for its own bitmaps and inode table ends the filesystem
 * before that group, and the inode count is rounded up to a whole number per
 * group. A caller that reports capacity has to read it from here rather than from
 * what it passed in.
 */
typedef struct {
    uint64_t blocks_count;
    uint32_t block_size;
    uint32_t inodes_count;
    uint32_t groups;
} ext4_mkfs_result;

/*
 * Fills in block size, block count, inode size and inode count for a container of
 * `size_bytes`, following the same thresholds mke2fs.conf uses so that a container
 * looks like an ordinary filesystem rather than a distinctive one. Leaves
 * uuid/hash_seed and `when` alone - the caller supplies those.
 */
void ext4_mkfs_default_params(ext4_mkfs_params *p, uint64_t size_bytes);

/*
 * Writes a whole filesystem through `io`. `io->block_size` is set from the
 * geometry before the first write, so the caller does not have to know it - there
 * is no superblock to read it out of yet, which is the one way this differs from
 * every other entry point here.
 *
 * Everything it touches it writes: no
 * region is left holding whatever the medium had in it and then described as
 * meaningful. What it deliberately does not write is the inode tables past the
 * eleven reserved inodes, which bg_itable_unused declares never-used - the inode
 * allocator zeroes those as it takes them, and formatting a container would
 * otherwise mean writing tens of megabytes nothing will ever read.
 */
int ext4_mkfs(ext4_io *io, const ext4_mkfs_params *p, ext4_mkfs_result *out);

#ifdef __cplusplus
}
#endif
#endif
