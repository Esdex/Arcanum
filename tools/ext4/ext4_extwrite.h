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
 * Appends into the rightmost leaf of the tree, whatever its depth. When the root
 * inside the inode fills - it holds four entries, all its 60 bytes allow - it is
 * pushed down into a block of its own and the tree gains a level.
 *
 * A leaf block that fills gets an empty sibling rather than being divided, since
 * an append only ever adds at the end. That costs an index entry in its parent,
 * and a full parent below the root is still refused with EXTW_ERR_FULL.
 *
 * The new size is the number of blocks now mapped times the block size, so a file
 * whose length was not a multiple of the block size gains the remainder of its
 * last block as well. Appending to a block-aligned file - which is every file this
 * writer creates after its first call - grows it by exactly count blocks.
 *
 * `appended` receives how many blocks actually landed, which can be fewer than
 * asked for when the filesystem fills. A short append is committed rather than
 * abandoned: the blocks it did place are already on disk and referenced, so the
 * inode and the free counts have to agree with that before returning.
 */
int ext4_append_blocks(ext4_wfs *fs, uint32_t ino, uint32_t count,
                       ext4_fill_fn fill, void *user, uint32_t *appended);

/*
 * Keeps logical blocks [0, keep_blocks) and releases the rest, along with any
 * extent-tree node left holding nothing. The new size is the smaller of what the
 * file already was and keep_blocks worth of it, so this only ever shrinks.
 *
 * keep_blocks of 0 empties the file: every block goes back and the root returns
 * to depth 0 with no entries, which is what deleting a file's contents needs.
 */
int ext4_truncate_blocks(ext4_wfs *fs, uint32_t ino, uint32_t keep_blocks);

/*
 * Moves an inode's link count by `delta` and restamps its checksum.
 *
 * A directory entry is only half of a link; this is the other half. e2fsck checks
 * the two against each other, so a name added without this leaves a count that is
 * short, and a name removed without it leaves an inode nothing can ever reclaim.
 * Kept apart from the entry itself because a caller adding a second name to one
 * inode - a hard link - does one of these and two of those.
 */
int ext4_inode_adjust_links(ext4_wfs *fs, uint32_t ino, int delta);

/*
 * Writes a whole inode, stamping its checksum in first so the two cannot reach
 * disk apart. The buffer must be inode_size bytes.
 */
int ext4_write_inode_raw(ext4_wfs *fs, uint32_t ino, uint8_t *inode);

#ifdef __cplusplus
}
#endif
#endif
