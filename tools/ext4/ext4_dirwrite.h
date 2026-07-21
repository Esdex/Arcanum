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

/*
 * Puts `name` in `dir_ino`, pointing at `ino`.
 *
 * Only reuses room already inside the directory's blocks - either a dead entry or
 * the padding a live one is holding beyond the length of its own name. Growing
 * the directory by a block is refused with EXT4_DIRW_ERR_NOROOM rather than done
 * half way; that needs the extent writer and a newly formatted block, and is its
 * own piece of work.
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

#ifdef __cplusplus
}
#endif
#endif
