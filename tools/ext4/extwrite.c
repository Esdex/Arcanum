/*
 * Driver for the extent writer.
 *
 *   extwrite <image> <inode> append <count>
 *   extwrite <image> <inode> truncate <blocks>
 *
 * Appended blocks are filled with a pattern that depends on the logical block
 * number, so reading the file back proves not just that the bytes arrived but
 * that they arrived in the right order and at the right offsets. A pattern of
 * repeated zeroes, or one constant per block, would pass a reader that mapped two
 * logical blocks to the same physical one.
 *
 * Each 8-byte slot holds the logical block number and the slot's index within the
 * block, both little-endian. appendcheck.py regenerates the same bytes.
 */
#define _POSIX_C_SOURCE 200809L

#include "ext4_extwrite.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

static int fill_pattern(void *user, uint32_t logical, uint8_t *buf) {
    uint32_t block_size = *(const uint32_t *)user;
    for (uint32_t k = 0; k + 8 <= block_size; k += 8) {
        wr32(buf + k, logical);
        wr32(buf + k + 4, k / 8);
    }
    return 0;
}

static const char *strerr(int rc) {
    switch (rc) {
    case EXTW_ERR_IO:      return "I/O error";
    case EXTW_ERR_FORMAT:  return "inode is not an extent inode, or its root is malformed";
    case EXTW_ERR_NOSPACE: return "no free blocks left";
    case EXTW_ERR_DEPTH:   return "extent tree is deeper than the format allows";
    case EXTW_ERR_FULL:    return "the rightmost leaf is full and would have to be split";
    default:               return "unknown error";
    }
}

int main(int argc, char **argv) {
    if (argc != 5 || (strcmp(argv[3], "append") && strcmp(argv[3], "truncate"))) {
        fprintf(stderr, "usage: %s <image> <inode> append <count>\n"
                        "       %s <image> <inode> truncate <blocks>\n",
                argv[0], argv[0]);
        return 2;
    }

    ext4_wfs fs;
    if (ext4_fs_open(&fs, argv[1])) {
        fprintf(stderr, "cannot open %s as an ext4 image\n", argv[1]);
        return 2;
    }

    uint32_t ino   = (uint32_t)strtoul(argv[2], NULL, 10);
    uint32_t count = (uint32_t)strtoul(argv[4], NULL, 10);

    uint32_t appended = 0;
    int rc;
    if (!strcmp(argv[3], "truncate")) {
        rc = ext4_truncate_blocks(&fs, ino, count);
        appended = count;
    } else {
        rc = ext4_append_blocks(&fs, ino, count, fill_pattern, &fs.block_size,
                                &appended);
    }
    ext4_fs_close(&fs);

    /* The count goes to stdout either way: a short append is still committed, and
     * whatever checks this needs to know how far it got rather than assuming. */
    printf("%u\n", appended);
    if (rc != EXTW_OK) fprintf(stderr, "append: %s\n", strerr(rc));

    if (rc == EXTW_OK)        return 0;
    if (rc == EXTW_ERR_NOSPACE) return 3;   /* short, but consistent */
    return 1;
}
