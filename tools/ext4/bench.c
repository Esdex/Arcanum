/*
 * Host harness: walks one inode's extent tree in an image file and prints the
 * result, so check.py can diff it against what debugfs reported.
 *
 * Deliberately the only file here that knows about files and stdio - the reader
 * itself talks to a block callback, which on the device will be the container.
 *
 *   cc -O2 -o bench bench.c ext4_extents.c
 *   ./bench fs.img 13
 */

/* fseeko and off_t are POSIX, not ISO C, and -std=c99 hides them without this.
 * The 64-bit offsets matter: an image can be larger than 2 GiB. */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_extents.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

typedef struct {
    FILE    *fp;
    uint32_t block_size;
} img_ctx;

static int img_read(void *ctx, uint64_t block, void *buf) {
    img_ctx *c = (img_ctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->block_size, SEEK_SET) != 0) return EXT4_ERR_IO;
    if (fread(buf, 1, c->block_size, c->fp) != c->block_size) return EXT4_ERR_IO;
    return EXT4_OK;
}

static int print_run(void *user, const ext4_extent_run *run) {
    (void)user;
    printf("%u %llu %u %d\n", run->logical,
           (unsigned long long)run->physical, run->length, run->uninit);
    return 0;
}

int main(int argc, char **argv) {
    int read_mode = (argc == 4 && strcmp(argv[3], "--read") == 0);
    int csum_mode = (argc == 4 && strcmp(argv[3], "--csum") == 0);
    if (argc != 3 && !read_mode && !csum_mode) {
        fprintf(stderr, "usage: %s <image> <inode> [--read|--csum]\n", argv[0]);
        return 2;
    }
    img_ctx ctx = { fopen(argv[1], "rb"), 1024 };
    if (!ctx.fp) { perror("open"); return 2; }

    ext4_fs fs;
    int rc = ext4_open(&fs, img_read, &ctx);
    if (rc != EXT4_OK) { fprintf(stderr, "ext4_open: %d\n", rc); return 1; }
    /* ext4_open discovered the real block size; the image reader has to follow
     * it or every read after this point lands at the wrong offset. */
    ctx.block_size = fs.block_size;

    uint8_t inode[256];
    memset(inode, 0, sizeof(inode));
    rc = ext4_read_inode_raw(&fs, (uint32_t)strtoul(argv[2], NULL, 10), inode, sizeof(inode));
    if (rc != EXT4_OK) { fprintf(stderr, "read_inode: %d\n", rc); return 1; }

    if (csum_mode) {
        /* i_generation, which the per-inode checksum seed folds in. */
        uint32_t generation = (uint32_t)inode[0x64] | ((uint32_t)inode[0x65] << 8) |
                              ((uint32_t)inode[0x66] << 16) | ((uint32_t)inode[0x67] << 24);
        int checked = 0;
        rc = ext4_check_extent_tree(&fs, (uint32_t)strtoul(argv[2], NULL, 10),
                                    generation, inode, &checked);
        printf("%d %d\n", rc, checked);
        fclose(ctx.fp);
        return rc == EXT4_OK ? 0 : 1;
    }

    if (read_mode) {
        /* Streamed in chunks rather than one allocation, so a multi-megabyte
         * file does not decide how much memory the harness needs. */
        uint64_t size = ext4_inode_size(inode);
        uint64_t done = 0;
        uint8_t  chunk[256 * 1024];
        /* Always asks for a full chunk, including past the end on the last one:
         * clamping to the file size is ext4_read_file's job, and asking politely
         * would leave that untested. */
        while (done < size) {
            long got = ext4_read_file(&fs, inode, done, chunk, sizeof(chunk));
            if (got < 0) { fprintf(stderr, "read: %ld\n", got); return 1; }
            if (got == 0) break;
            fwrite(chunk, 1, (size_t)got, stdout);
            done += (uint64_t)got;
        }
        fclose(ctx.fp);
        return 0;
    }

    rc = ext4_walk_extents(&fs, inode, print_run, NULL);
    if (rc != EXT4_OK) { fprintf(stderr, "walk: %d\n", rc); return 1; }

    fclose(ctx.fp);
    return 0;
}
