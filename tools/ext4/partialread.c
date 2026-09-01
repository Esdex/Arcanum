/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * Host test harness for the clean-room ext4 library - PC-only, not shipped in the
 * app. e2fsprogs (e2fsck/debugfs/mke2fs) and fuse2fs are used as external oracles,
 * run as separate processes, never linked or copied. See issue #7.
 */

/*
 * Host driver for one read of one range, either way round:
 *
 *   ./partialread img 13 0 1048576            strict  - ext4_read_file
 *   ./partialread img 13 0 1048576 --partial  lenient - ext4_read_file_partial
 *
 * Prints the return value on stdout, one number, so a stand can assert on it. With
 * --dump the bytes go to stdout instead and the number to stderr, so the same tool
 * answers "how much" and "what", and a partial read that quietly returned zeroes
 * cannot pass for one that returned the file's own data (#173).
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_extents.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

typedef struct { FILE *fp; uint32_t bs; } dev_t_;

static int read_block(void *ctx, uint64_t block, void *out) {
    dev_t_ *d = (dev_t_ *)ctx;
    if (fseeko(d->fp, (off_t)block * d->bs, SEEK_SET) != 0) return EXT4_ERR_IO;
    if (fread(out, 1, d->bs, d->fp) != d->bs) return EXT4_ERR_IO;
    return EXT4_OK;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr,
                "usage: %s <image> <inode> <offset> <length> [--partial] [--dump]\n",
                argv[0]);
        return 2;
    }
    int partial = 0, dump = 0;
    for (int i = 5; i < argc; i++) {
        if (!strcmp(argv[i], "--partial")) partial = 1;
        else if (!strcmp(argv[i], "--dump")) dump = 1;
        else { fprintf(stderr, "unknown option %s\n", argv[i]); return 2; }
    }

    dev_t_ d = { fopen(argv[1], "rb"), 1024 };
    if (!d.fp) { perror("open"); return 2; }

    ext4_fs fs;
    if (ext4_open(&fs, read_block, &d) != EXT4_OK) {
        fprintf(stderr, "not an ext4 image\n");
        return 2;
    }
    d.bs = fs.block_size;

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof inode);
    if (ext4_read_inode_raw(&fs, (uint32_t)strtoul(argv[2], 0, 0),
                            inode, sizeof inode) != EXT4_OK) {
        fprintf(stderr, "inode could not be read\n");
        return 2;
    }

    uint64_t offset = strtoull(argv[3], 0, 0);
    uint64_t length = strtoull(argv[4], 0, 0);
    uint8_t *buf = malloc(length ? (size_t)length : 1);
    if (!buf) { fprintf(stderr, "out of memory\n"); return 2; }

    long got = partial
        ? ext4_read_file_partial(&fs, inode, offset, buf, length)
        : ext4_read_file(&fs, inode, offset, buf, length);

    if (dump) {
        if (got > 0) fwrite(buf, 1, (size_t)got, stdout);
        fprintf(stderr, "%ld\n", got);
    } else {
        printf("%ld\n", got);
    }
    free(buf);
    fclose(d.fp);
    return got < 0 ? 1 : 0;
}
