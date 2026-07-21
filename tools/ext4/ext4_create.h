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

#define EXT4_CREATE_ERR_NOINODE -7

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

#ifdef __cplusplus
}
#endif
#endif
