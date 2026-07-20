/*
 * Clean-room ext4 extent reader, written from the published on-disk format.
 *
 * No GPL ext4 source was consulted. The one file in lwext4 that carries a GPL
 * notice (src/ext4_extent.c) has deliberately not been opened - see issue #7. The
 * layout implemented here comes from the format documentation, which describes a
 * data structure rather than anyone's expression of it.
 *
 * Block reads go through a callback so the same code works over a plain image on
 * a host and over an encrypted container on the device, where a "block" is
 * whatever the volume layer hands back.
 */

#ifndef ARCANUM_EXT4_EXTENTS_H
#define ARCANUM_EXT4_EXTENTS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_OK              0
#define EXT4_ERR_IO         -1
#define EXT4_ERR_FORMAT     -2   /* not ext4, or a structure failed validation */
#define EXT4_ERR_RANGE      -3   /* asked for something outside the filesystem */
#define EXT4_ERR_NOT_EXTENT -4   /* inode uses the old block map, not extents */

/* Reads one filesystem block into buf. Returns EXT4_OK or EXT4_ERR_IO. */
typedef int (*ext4_read_block_fn)(void *ctx, uint64_t block, void *buf);

typedef struct {
    ext4_read_block_fn read_block;
    void    *ctx;
    uint32_t block_size;
    uint32_t inode_size;
    uint32_t inodes_per_group;
    uint32_t first_data_block;
    uint32_t desc_size;        /* 32, or 64 when the 64BIT feature is on */
    uint64_t blocks_count;
} ext4_fs;

/* One resolved run of blocks. `uninit` marks a preallocated extent that reads
 * as zeroes rather than as stored data. */
typedef struct {
    uint32_t logical;
    uint64_t physical;
    uint32_t length;
    int      uninit;
} ext4_extent_run;

/*
 * Reads the superblock and fills fs. The callback must already be able to serve
 * 1 KiB-aligned reads: the superblock lives at byte offset 1024 and its own block
 * size is not known until it has been read, so this asks for block 0 of a
 * provisional 1 KiB size before switching to the real one.
 */
int ext4_open(ext4_fs *fs, ext4_read_block_fn read_block, void *ctx);

/* Copies the 160-byte on-disk inode for `ino` (1-based) into `inode_out`. */
int ext4_read_inode_raw(const ext4_fs *fs, uint32_t ino, uint8_t *inode_out, size_t out_size);

/*
 * Walks the extent tree of an inode and reports every leaf extent in logical
 * order through `emit`. Returning non-zero from emit stops the walk and is
 * returned to the caller.
 */
typedef int (*ext4_extent_cb)(void *user, const ext4_extent_run *run);

int ext4_walk_extents(const ext4_fs *fs, const uint8_t *inode,
                      ext4_extent_cb emit, void *user);

/* Maps one logical block to a physical one. Sets *physical to 0 for a hole. */
int ext4_map_block(const ext4_fs *fs, const uint8_t *inode,
                   uint32_t logical, uint64_t *physical, int *uninit);

/* File length in bytes, from the inode's split size fields. */
uint64_t ext4_inode_size(const uint8_t *inode);

/*
 * Reads [offset, offset+length) of the file into buf, stopping at end of file.
 * Returns the number of bytes produced, or a negative EXT4_ERR_*.
 *
 * A hole and a preallocated (uninitialised) extent both read as zeroes: neither
 * holds file data, and handing back whatever the blocks happen to contain would
 * leak the previous tenant of that space.
 */
long ext4_read_file(const ext4_fs *fs, const uint8_t *inode,
                    uint64_t offset, uint8_t *buf, uint64_t length);

#ifdef __cplusplus
}
#endif

#endif /* ARCANUM_EXT4_EXTENTS_H */
