/*
 * Driver for the formatter, so mkfscheck.py has something to run.
 *
 *   mkfs <image> [--blocks N] [--bs N] [--inodes N] [--isize N]
 *                [--when N] [--uuid HEX32] [--hash-seed HEX32]
 *
 * Every part of the geometry can be given explicitly, which is the point: the
 * harness reads the numbers out of an image mke2fs made and hands the same ones
 * here, so the two images can be compared rather than merely both checked. With
 * nothing given it falls back to ext4_mkfs_default_params over the file's size,
 * which is the path the device takes.
 *
 * The image is truncated to exactly blocks * block_size, the way mke2fs sees a
 * file: a filesystem that stops short of its container is a different filesystem.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_mkfs.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

typedef struct {
    FILE    *fp;
    uint32_t block_size;
} img_ctx;

static int img_read(void *user, uint64_t block, uint32_t block_size, void *buf) {
    img_ctx *c = (img_ctx *)user;
    if (fseeko(c->fp, (off_t)block * block_size, SEEK_SET)) return -1;
    /* A block past the end of a sparse file reads short; the formatter only ever
     * does that through a read-modify-write of a region it is about to fill, so
     * zeroes are the honest answer rather than an error. */
    size_t got = fread(buf, 1, block_size, c->fp);
    if (got < block_size) memset((uint8_t *)buf + got, 0, block_size - got);
    return 0;
}

static int img_write(void *user, uint64_t block, uint32_t block_size, const void *buf) {
    img_ctx *c = (img_ctx *)user;
    if (fseeko(c->fp, (off_t)block * block_size, SEEK_SET)) return -1;
    return fwrite(buf, 1, block_size, c->fp) == block_size ? 0 : -1;
}

static int img_flush(void *user) { return fflush(((img_ctx *)user)->fp); }

/* 32 hex characters into 16 bytes. Refuses anything else rather than filling in
 * what it managed to read - a half-parsed UUID silently changes every checksum in
 * the filesystem, since the seed is derived from it. */
static int parse_hex16(const char *s, uint8_t *out) {
    if (strlen(s) != 32) return -1;
    for (int i = 0; i < 16; i++) {
        char pair[3] = { s[i * 2], s[i * 2 + 1], 0 };
        char *end = NULL;
        long v = strtol(pair, &end, 16);
        if (!end || *end || v < 0 || v > 255) return -1;
        out[i] = (uint8_t)v;
    }
    return 0;
}

static const char *strerr(int rc) {
    switch (rc) {
    case EXT4_MKFS_ERR_IO:    return "I/O error";
    case EXT4_MKFS_ERR_PARAM: return "a parameter is not one this can format with";
    case EXT4_MKFS_ERR_SMALL: return "too small to hold even one group's metadata";
    case EXT4_MKFS_ERR_NOMEM: return "out of memory";
    default:                  return "unknown error";
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr,
                "usage: %s <image> [--blocks N] [--bs N] [--inodes N] [--isize N]\n"
                "                  [--when N] [--uuid HEX32] [--hash-seed HEX32]\n",
                argv[0]);
        return 2;
    }
    const char *path = argv[1];

    ext4_mkfs_params p;
    memset(&p, 0, sizeof(p));
    p.block_size = 0;
    p.inode_size = 256;
    p.when       = 0;

    uint64_t blocks = 0;
    uint32_t inodes = 0;

    for (int i = 2; i < argc; i++) {
        const char *a = argv[i];
        const char *v = (i + 1 < argc) ? argv[i + 1] : NULL;
        if (!v) { fprintf(stderr, "%s needs a value\n", a); return 2; }
        i++;
        if      (!strcmp(a, "--blocks")) blocks = strtoull(v, NULL, 0);
        else if (!strcmp(a, "--bs"))     p.block_size = (uint32_t)strtoul(v, NULL, 0);
        else if (!strcmp(a, "--inodes")) inodes = (uint32_t)strtoul(v, NULL, 0);
        else if (!strcmp(a, "--isize"))  p.inode_size = (uint32_t)strtoul(v, NULL, 0);
        else if (!strcmp(a, "--when"))   p.when = (uint32_t)strtoul(v, NULL, 0);
        else if (!strcmp(a, "--uuid")) {
            if (parse_hex16(v, p.uuid)) { fprintf(stderr, "bad --uuid\n"); return 2; }
        } else if (!strcmp(a, "--hash-seed")) {
            if (parse_hex16(v, p.hash_seed)) { fprintf(stderr, "bad --hash-seed\n"); return 2; }
        } else {
            fprintf(stderr, "unknown option %s\n", a);
            return 2;
        }
    }

    img_ctx ctx = { NULL, 0 };
    ctx.fp = fopen(path, "r+b");
    if (!ctx.fp) ctx.fp = fopen(path, "w+b");
    if (!ctx.fp) { perror(path); return 1; }

    struct stat st;
    if (fstat(fileno(ctx.fp), &st)) { perror("fstat"); fclose(ctx.fp); return 1; }
    uint64_t size_bytes = (uint64_t)st.st_size;

    /* Nothing chosen: take the geometry the device would take for this size, then
     * let any explicit option override a piece of it. */
    if (p.block_size == 0) {
        ext4_mkfs_params d;
        memset(&d, 0, sizeof(d));
        ext4_mkfs_default_params(&d, size_bytes ? size_bytes : 32ull * 1024 * 1024);
        p.block_size = d.block_size;
        if (!blocks) blocks = d.blocks_count;
        if (!inodes) inodes = d.inodes_count;
    }
    if (!blocks) blocks = size_bytes / p.block_size;
    if (!inodes) {
        ext4_mkfs_params d;
        memset(&d, 0, sizeof(d));
        ext4_mkfs_default_params(&d, blocks * p.block_size);
        inodes = d.inodes_count;
    }
    p.blocks_count = blocks;
    p.inodes_count = inodes;

    /*
     * Grown to hold the filesystem if it is short, and otherwise left exactly as
     * it is. Shrinking it to fit would be wrong in the one case that matters: a
     * size whose last group cannot pay for itself produces a filesystem shorter
     * than the file, and mke2fs leaves those trailing bytes alone rather than
     * cutting the file down to the filesystem.
     */
    uint64_t need = blocks * p.block_size;
    if (size_bytes < need && ftruncate(fileno(ctx.fp), (off_t)need)) {
        perror("ftruncate");
        fclose(ctx.fp);
        return 1;
    }

    ctx.block_size = p.block_size;
    ext4_io io;
    memset(&io, 0, sizeof(io));
    io.read_block  = img_read;
    io.write_block = img_write;
    io.flush       = img_flush;
    io.user        = &ctx;
    io.block_size  = p.block_size;

    ext4_mkfs_result made;
    int rc = ext4_mkfs(&io, &p, &made);
    fclose(ctx.fp);
    if (rc != EXT4_MKFS_OK) {
        fprintf(stderr, "mkfs failed: %s\n", strerr(rc));
        return 1;
    }
    /* What was made, not what was asked for: a filesystem whose last group could
     * not pay for itself is shorter than the request, and printing the request
     * would tell the harness a number no structure on the disk agrees with. */
    printf("%llu %u %u %u\n", (unsigned long long)made.blocks_count,
           made.block_size, made.inodes_count, made.groups);
    return 0;
}
