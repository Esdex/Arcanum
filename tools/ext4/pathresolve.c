/*
 * Driver for path resolution, so pathcheck.py has something to run.
 *
 *   pathresolve <image> resolve <path>   -> "<inode> <dir|file>"
 *   pathresolve <image> parent  <path>   -> "<parent-inode> <name>"
 *
 * Read-only: it opens the image through the reader's block callback and walks the
 * directory tree, changing nothing. On failure it prints the error name to stderr
 * and exits non-zero, so the harness can check the refusals as well as the hits.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_path.h"

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

static const char *strerr(int rc) {
    switch (rc) {
    case EXT4_PATH_ENOENT:        return "ENOENT";
    case EXT4_PATH_ENOTDIR:       return "ENOTDIR";
    case EXT4_PATH_ENAMETOOLONG:  return "ENAMETOOLONG";
    case EXT4_PATH_EINVAL:        return "EINVAL";
    case EXT4_PATH_EIO:           return "EIO";
    default:                      return "UNKNOWN";
    }
}

int main(int argc, char **argv) {
    int resolving = (argc == 4 && !strcmp(argv[2], "resolve"));
    int parenting = (argc == 4 && !strcmp(argv[2], "parent"));
    if (!resolving && !parenting) {
        fprintf(stderr, "usage: %s <image> resolve <path>\n"
                        "       %s <image> parent <path>\n", argv[0], argv[0]);
        return 2;
    }

    img_ctx ctx = { fopen(argv[1], "rb"), 1024 };
    if (!ctx.fp) { perror("open"); return 2; }

    ext4_fs r;
    if (ext4_open(&r, img_read, &ctx) != EXT4_OK) {
        fprintf(stderr, "cannot read %s as an ext4 image\n", argv[1]);
        fclose(ctx.fp);
        return 2;
    }
    ctx.block_size = r.block_size;

    int rc;
    if (resolving) {
        uint32_t ino = 0;
        int is_dir = 0;
        rc = ext4_resolve_path(&r, argv[3], &ino, &is_dir);
        if (rc == EXT4_PATH_OK) printf("%u %s\n", ino, is_dir ? "dir" : "file");
    } else {
        uint32_t parent = 0;
        char name[256];
        rc = ext4_resolve_parent(&r, argv[3], &parent, name, sizeof(name));
        if (rc == EXT4_PATH_OK) printf("%u %s\n", parent, name);
    }

    if (rc != EXT4_PATH_OK) fprintf(stderr, "%s\n", strerr(rc));
    fclose(ctx.fp);
    return rc == EXT4_PATH_OK ? 0 : 1;
}
