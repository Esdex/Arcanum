/*
 * Reading a directory.
 *
 * Clean-room, from the published on-disk format. Built on the extent reader
 * rather than beside it: a directory's entries are its file contents, so finding
 * them is the same problem as finding any other file's blocks, already solved.
 */
#ifndef ARCANUM_EXT4_DIR_H
#define ARCANUM_EXT4_DIR_H

#include "ext4_extents.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_FT_UNKNOWN   0
#define EXT4_FT_REG_FILE  1
#define EXT4_FT_DIR       2
#define EXT4_FT_CHRDEV    3
#define EXT4_FT_BLKDEV    4
#define EXT4_FT_FIFO      5
#define EXT4_FT_SOCK      6
#define EXT4_FT_SYMLINK   7

/*
 * A directory block's last 12 bytes can hold a checksum disguised as an entry:
 * inode 0, rec_len 12, and this in the file-type byte, which no real entry uses.
 * It is skipped like any other unused entry, so a reader that knows nothing about
 * it still lists the right names - it only matters when writing, and when
 * checking the block.
 */
#define EXT4_FT_DIR_CSUM  0xDE

#define EXT4_DIRENT_MAX_NAME 255

typedef struct {
    uint32_t inode;
    uint8_t  file_type;
    uint8_t  name_len;
    char     name[EXT4_DIRENT_MAX_NAME + 1];   /* NUL-terminated for convenience */
} ext4_dir_entry;

/*
 * Reports every live entry in logical order. Returning non-zero from `emit` stops
 * the walk and is passed back to the caller.
 *
 * Entries that are not live - deleted ones, the padding at the end of a block,
 * the checksum tail, and the whole-block filler that hides an htree index node
 * from a linear reader - all carry inode 0 and are stepped over. That is what
 * lets one linear walk serve both directory formats.
 */
typedef int (*ext4_dir_cb)(void *user, const ext4_dir_entry *entry);

int ext4_dir_iterate(const ext4_fs *fs, const uint8_t *inode,
                     ext4_dir_cb emit, void *user);

/*
 * Verifies the checksum in the tail of every block of a directory, and reports
 * how many were checked.
 *
 * Read-only, and the point of it is to be read-only: predicting the values
 * e2fsprogs already wrote proves the recipe before anything has to write one. A
 * block with no tail is counted as unchecked rather than failed - a directory
 * created without metadata_csum has none.
 */
int ext4_dir_check_csums(const ext4_fs *fs, uint32_t ino, uint32_t generation,
                         const uint8_t *inode, int *blocks_checked);

#ifdef __cplusplus
}
#endif
#endif
