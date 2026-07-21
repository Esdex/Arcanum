/*
 * Attaching allocated blocks to a file, by extending its extent tree.
 *
 * Clean-room, from the published on-disk format. lwext4's src/ext4_extent.c is
 * GPL and has never been opened - see issue #7. This is the layer that file most
 * obviously corresponds to, so the constraint matters here more than anywhere
 * else in this directory.
 */
#ifndef ARCANUM_EXT4_EXTWRITE_H
#define ARCANUM_EXT4_EXTWRITE_H

#include "ext4_alloc.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXTW_OK            0
#define EXTW_ERR_IO       -1
#define EXTW_ERR_FORMAT   -2   /* not an extent inode, or a header failed to parse */
#define EXTW_ERR_NOSPACE  -3   /* the filesystem has no free block left */
#define EXTW_ERR_DEPTH    -4   /* the tree is deeper than this writer handles */
#define EXTW_ERR_FULL     -5   /* the root is full and would have to be split */

/* Fills one block of data to append. Returns 0, or non-zero to abort. */
typedef int (*ext4_fill_fn)(void *user, uint32_t logical, uint8_t *buf);

/*
 * Appends `count` blocks to the end of the file at `ino`.
 *
 * Handles a tree of depth 0 only - the root that lives in the inode's 60 bytes of
 * i_block, holding at most four extents. Growing past that means splitting the
 * root into a real tree, which is the next piece of work and not this one; it is
 * refused with EXTW_ERR_FULL rather than half-done.
 *
 * The new size is the number of blocks now mapped times the block size, so a file
 * whose length was not a multiple of the block size gains the remainder of its
 * last block as well. Appending to a block-aligned file - which is every file this
 * writer creates after its first call - grows it by exactly count blocks.
 */
int ext4_append_blocks(ext4_wfs *fs, uint32_t ino, uint32_t count,
                       ext4_fill_fn fill, void *user);

#ifdef __cplusplus
}
#endif
#endif
