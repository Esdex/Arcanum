/*
 * Adding and removing directory entries.
 *
 * Clean-room, from the published on-disk format.
 *
 * Needs both handles: the reader to find which physical block holds a given
 * logical one, and the writable handle to put it back. Keeping the reader means
 * the extent walk that finds the block is the one already verified against
 * debugfs, rather than a second copy of it written for this.
 */
#ifndef ARCANUM_EXT4_DIRWRITE_H
#define ARCANUM_EXT4_DIRWRITE_H

#include "ext4_alloc.h"
#include "ext4_dir.h"
#include "ext4_extents.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_DIRW_OK          0
#define EXT4_DIRW_ERR_IO     -1
#define EXT4_DIRW_ERR_FORMAT -2
#define EXT4_DIRW_ERR_EXISTS -3   /* a live entry already has that name */
#define EXT4_DIRW_ERR_ABSENT -4   /* nothing by that name to remove */
#define EXT4_DIRW_ERR_NOROOM -5   /* no gap big enough in any existing block */
#define EXT4_DIRW_ERR_NAME   -6   /* empty, too long, or contains / or NUL */
#define EXT4_DIRW_ERR_HTREE  -8   /* hash-indexed directory, which this cannot write */

/*
 * Puts `name` in `dir_ino`, pointing at `ino`.
 *
 * Takes room already inside the directory's blocks first - either a dead entry or
 * the padding a live one holds beyond the length of its own name. When no block
 * has a gap that fits, the directory grows by one, formatted and ready, and the
 * search runs once more. A second failure is not a full directory but a block
 * that came back unusable, and is returned as EXT4_DIRW_ERR_NOROOM rather than
 * retried.
 */
int ext4_dir_add(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                 uint32_t ino, uint8_t file_type, const char *name);

/*
 * Takes `name` out of `dir_ino`.
 *
 * The entry is not blanked. The one in front of it absorbs it by growing its
 * rec_len over it, which is what the format does and what every reader expects;
 * the name stays in the block until something else is written over it. Nothing
 * here clears the inode it pointed at - unlinking and freeing are separate, and
 * conflating them is how a file gets freed while another name still refers to it.
 */
int ext4_dir_remove(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                    const char *name);

/*
 * Finds which inode `name` refers to in `dir_ino`.
 *
 * Read-only, and needed before a remove rather than after: once the entry is
 * gone there is nothing left to say whose link count to decrement.
 */
int ext4_dir_lookup(const ext4_fs *r, uint32_t dir_ino, const char *name,
                    uint32_t *ino_out);

/*
 * Writes the 12-byte tail at the end of a freshly built directory block: a dead
 * entry carrying the block's checksum, seeded per the owning inode.
 *
 * Exported because two layers build a directory block from nothing - this one
 * when a directory grows, and ext4_create.c for the first block of a new
 * directory - and the block and its checksum must never be assembled apart. The
 * caller fills the entries first; this closes the block.
 */
void ext4_dir_stamp_tail(uint8_t *block, uint32_t block_size, uint32_t seed);

/*
 * Repoints the ".." entry of `dir_ino` at `new_parent`.
 *
 * A directory's ".." is an ordinary entry in its first block naming its parent, so
 * moving the directory to a new parent is not done until this rewrites it - and
 * with it the parent's link count, which the mover handles. Reads the first block,
 * finds "..", rewrites only its inode field and restamps the block's checksum;
 * everything else in the block is left exactly as it was. Refuses a directory whose
 * first block holds no ".." (a corrupt one) rather than inventing it.
 *
 * Not for general entry editing - it is the one field rename has to touch that
 * ext4_dir_add and ext4_dir_remove do not, and keeping it here keeps the block's
 * checksum from being assembled anywhere the writer does not already own.
 */
int ext4_dir_set_dotdot(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                        uint32_t new_parent);

#ifdef __cplusplus
}
#endif
#endif
