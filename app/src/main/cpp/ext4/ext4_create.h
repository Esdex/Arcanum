/*
 * Creating and deleting a file: joining the inode allocator, the extent writer
 * and the directory writer, in the one order that survives being interrupted.
 * See the .c - the order is the substance of this layer, not an implementation
 * detail of it.
 */
#ifndef ARCANUM_EXT4_CREATE_H
#define ARCANUM_EXT4_CREATE_H

#include "ext4_alloc.h"
#include "ext4_dir.h"
#include "ext4_dirwrite.h"
#include "ext4_extents.h"
#include "ext4_extwrite.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_CREATE_ERR_NOINODE  -7
#define EXT4_CREATE_ERR_NOTDIR   -9   /* the name is there but is not a directory */
#define EXT4_CREATE_ERR_NOTEMPTY -10  /* it still holds something other than . and .. */

/*
 * Makes an empty regular file called `name` in `dir_ino` and reports its inode.
 *
 * `when` is stored in all three timestamps. Passed in rather than read from the
 * clock so that the same inputs give the same image, which is what lets a test
 * compare one byte for byte.
 */
int ext4_create_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint16_t mode, uint32_t when,
                     uint32_t *ino_out);

/*
 * Removes `name`. When it was the last name the file had, its blocks and its
 * inode go back too and `when` is recorded as its deletion time; when it was not,
 * only the link count moves.
 */
int ext4_unlink_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint32_t when);

/*
 * Makes an empty directory called `name` in `dir_ino` and reports its inode.
 *
 * A directory is not a file with a different mode bit. Three things exist here
 * that creating a file has no equivalent of, and each is checked by e2fsck
 * separately:
 *
 *   the block     a new directory is never empty on disk - it holds "." and ".."
 *                 from the moment it exists, so one block is allocated and
 *                 formatted as part of creating it
 *   the links     it starts with two, its own name and its own ".", and the
 *                 parent gains one because the new ".." points back at it
 *   the count     bg_used_dirs_count in the group descriptor, which nothing else
 *                 in this library moves
 */
int ext4_mkdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint16_t mode, uint32_t when,
               uint32_t *ino_out);

/*
 * Removes an empty directory, undoing all three.
 *
 * Refuses a name that is not a directory, and one that still holds anything other
 * than "." and "..". Emptiness is not a courtesy check: the entries inside a
 * directory are only reachable through it, so removing a populated one strands
 * every inode below it with no name left to find it by.
 */
int ext4_rmdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint32_t when);

#ifdef __cplusplus
}
#endif
#endif
