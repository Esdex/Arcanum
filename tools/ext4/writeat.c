/*
 * Driver for the positional write (ext4_write_at): the composition behind
 * nativeWriteAt on the device - open a file, write `len` bytes at `offset` without
 * truncating, overwriting existing blocks in place and appending past the end.
 *
 *   writeat <image> <path> <offset> <len> <salt>
 *
 * Fills the written region with a position-dependent pattern keyed by <salt>, so
 * writeatcheck.py can rebuild exactly what the file should be after the write -
 * base bytes outside the region, pattern bytes inside - and read it back through
 * fuse2fs to confirm every byte, in order, landed where it belongs.
 *
 * On a refusal it exits with the primitive's own error negated (RANGE -> 6), so the
 * harness can assert a hole write was refused for the right reason.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_path.h"
#include "ext4_extwrite.h"
#include "ext4_extents.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

typedef struct { FILE *fp; uint32_t block_size; } rctx;

static int r_read(void *ctx, uint64_t block, void *buf) {
    rctx *c = (rctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->block_size, SEEK_SET)) return EXT4_ERR_IO;
    return fread(buf, 1, c->block_size, c->fp) == c->block_size ? EXT4_OK : EXT4_ERR_IO;
}

/* byte i of the written region = (i ^ (i>>8) ^ salt) & 0xFF - the same pattern
 * writeatcheck.py regenerates. */
static uint8_t pat(uint64_t i, uint32_t salt) {
    return (uint8_t)(i ^ (i >> 8) ^ salt);
}

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr, "usage: %s <image> <path> <offset> <len> <salt>\n", argv[0]);
        return 2;
    }
    const char *path = argv[2];
    uint64_t offset = strtoull(argv[3], NULL, 0);
    uint64_t len    = strtoull(argv[4], NULL, 0);
    uint32_t salt   = (uint32_t)strtoul(argv[5], NULL, 0);

    rctx rc = { fopen(argv[1], "rb"), 1024 };
    if (!rc.fp) { perror("open ro"); return 2; }
    ext4_fs r;
    if (ext4_open(&r, r_read, &rc) != EXT4_OK) { fprintf(stderr, "not ext4\n"); return 2; }
    rc.block_size = r.block_size;

    uint32_t ino = 0;
    int is_dir = 0;
    if (ext4_resolve_path(&r, path, &ino, &is_dir) != EXT4_PATH_OK || is_dir) {
        fprintf(stderr, "no such file: %s\n", path);
        return 2;
    }

    uint8_t *data = NULL;
    if (len) {
        data = malloc(len);
        if (!data) { fprintf(stderr, "oom\n"); return 2; }
        for (uint64_t i = 0; i < len; i++) data[i] = pat(i, salt);
    }

    ext4_wfs w;
    if (ext4_fs_open(&w, argv[1])) { fprintf(stderr, "open rw failed\n"); return 2; }

    int result = ext4_write_at(&w, &r, ino, offset, data, (uint32_t)len);
    ext4_fs_close(&w);
    fclose(rc.fp);
    free(data);

    if (result != EXTW_OK) {
        fprintf(stderr, "write_at failed: %d\n", result);
        return -result;               /* the refusal's own code, negated */
    }
    printf("wrote %llu bytes at %llu to %s\n",
           (unsigned long long)len, (unsigned long long)offset, path);
    return 0;
}
