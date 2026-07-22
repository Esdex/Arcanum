/*
 * Reading a directory, entry by entry.
 *
 * Clean-room, from the published on-disk format.
 *
 * The layout is a singly linked list packed into each block: every entry carries
 * a rec_len saying where the next one starts, and the last entry in a block has a
 * rec_len that runs to the end of it. rec_len is therefore almost never the
 * entry's actual size - it is padding, or it is space a deleted neighbour used to
 * occupy. Deleting an entry usually does not blank it; the entry before it simply
 * absorbs it by growing rec_len over it, which leaves the old name sitting in the
 * block, unreferenced and unreachable.
 *
 * Two consequences worth stating, because both are easy to get wrong:
 *
 *   - "every entry in the block" and "every live entry" are different walks. Only
 *     the chain of rec_len jumps is authoritative. Scanning for name-shaped bytes
 *     would turn up deleted names that no longer exist.
 *
 *   - rec_len comes off the disk, so it decides where the next read lands. A
 *     zero or unaligned value would loop forever or step outside the block, and a
 *     directory is exactly the structure an attacker gets to choose the contents
 *     of. It is checked before it is used, every time.
 *
 * A block cannot be parsed except from its start, so entries are never split
 * across blocks and each block is walked on its own.
 */
#define _POSIX_C_SOURCE 200809L

#include "ext4_dir.h"
#include "ext4_csum.h"

#include <string.h>

#define DIRENT_HEADER 8          /* inode, rec_len, name_len, file_type */
#define EXT4_MAX_BLOCK_SIZE 65536

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int walk_block(const uint8_t *blk, uint32_t block_size,
                      ext4_dir_cb emit, void *user) {
    uint32_t off = 0;

    while (off + DIRENT_HEADER <= block_size) {
        uint32_t ino      = rd32(blk + off);
        uint16_t rec_len  = rd16(blk + off + 4);
        uint8_t  name_len = blk[off + 6];
        uint8_t  ftype    = blk[off + 7];

        /* Checked before it is trusted to move `off`: a rec_len of zero would
         * spin here forever, and one that overruns would read past the block. */
        if (rec_len < DIRENT_HEADER || (rec_len & 3) != 0) return EXT4_ERR_FORMAT;
        if ((uint64_t)off + rec_len > block_size)           return EXT4_ERR_FORMAT;

        if (ino != 0 && name_len != 0 && ftype != EXT4_FT_DIR_CSUM) {
            if (DIRENT_HEADER + (uint32_t)name_len > rec_len) return EXT4_ERR_FORMAT;

            ext4_dir_entry e;
            e.inode     = ino;
            e.file_type = ftype;
            e.name_len  = name_len;
            memcpy(e.name, blk + off + DIRENT_HEADER, name_len);
            e.name[name_len] = '\0';

            int rc = emit(user, &e);
            if (rc != 0) return rc;
        }
        off += rec_len;
    }
    return EXT4_OK;
}

int ext4_dir_iterate(const ext4_fs *fs, const uint8_t *inode,
                     ext4_dir_cb emit, void *user) {
    if (fs->block_size > EXT4_MAX_BLOCK_SIZE) return EXT4_ERR_FORMAT;

    uint64_t size = ext4_inode_size(inode);
    uint8_t  blk[EXT4_MAX_BLOCK_SIZE];

    for (uint64_t off = 0; off < size; off += fs->block_size) {
        /* Read through the file layer rather than the block layer, so a directory
         * with a hole in it reads as zeroes - which parse as one dead entry
         * spanning the block - instead of returning whatever is on disk. */
        long got = ext4_read_file(fs, inode, off, blk, fs->block_size);
        if (got < 0) return (int)got;
        if ((uint32_t)got < fs->block_size) break;

        int rc = walk_block(blk, fs->block_size, emit, user);
        if (rc != EXT4_OK) return rc;
    }
    return EXT4_OK;
}

/*
 * The tail sits in the last 12 bytes of the block and is shaped like an entry so
 * that a reader which knows nothing about it steps over it: inode 0, rec_len 12.
 * The checksum covers everything before it.
 *
 * Seeded per inode, like an extent block's, so a directory block cannot be moved
 * to another directory and still verify.
 */
#define DIR_TAIL_SIZE 12

int ext4_dir_check_csums(const ext4_fs *fs, uint32_t ino, uint32_t generation,
                         const uint8_t *inode, int *blocks_checked) {
    if (blocks_checked) *blocks_checked = 0;
    if (fs->block_size > EXT4_MAX_BLOCK_SIZE) return EXT4_ERR_FORMAT;
    if (!fs->has_metadata_csum) return EXT4_OK;

    uint32_t seed = ext4_inode_csum_seed(fs->csum_seed, ino, generation);
    uint64_t size = ext4_inode_size(inode);
    uint8_t  blk[EXT4_MAX_BLOCK_SIZE];

    for (uint64_t off = 0; off < size; off += fs->block_size) {
        long got = ext4_read_file(fs, inode, off, blk, fs->block_size);
        if (got < 0) return (int)got;
        if ((uint32_t)got < fs->block_size) break;

        const uint8_t *tail = blk + fs->block_size - DIR_TAIL_SIZE;
        /* Shaped like a dead entry of exactly the tail's length; anything else
         * means this block does not carry one. */
        if (rd32(tail) != 0 || rd16(tail + 4) != DIR_TAIL_SIZE ||
            tail[7] != EXT4_FT_DIR_CSUM)
            continue;

        uint32_t want = ext4_crc32c(seed, blk, fs->block_size - DIR_TAIL_SIZE);
        if (want != rd32(tail + 8)) return EXT4_ERR_FORMAT;
        if (blocks_checked) (*blocks_checked)++;
    }
    return EXT4_OK;
}
