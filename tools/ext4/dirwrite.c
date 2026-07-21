/*
 * Driver for directory writing, so dirwcheck.py has something to run.
 *
 *   dirwrite <image> <dir-inode> add <name> <inode> <file-type>
 *   dirwrite <image> <dir-inode> remove <name>
 *
 * Opens the image twice on purpose: once through the reader's block callback,
 * which is what knows how to find a directory's blocks, and once as a writable
 * handle. The alternative was a second copy of the extent walk written for
 * writing, which is exactly the duplication that lets two readers disagree.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_dirwrite.h"
#include "ext4_extwrite.h"
#include "ext4_create.h"

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
    case EXT4_DIRW_ERR_IO:     return "I/O error";
    case EXT4_DIRW_ERR_FORMAT: return "the directory's entry chain does not parse";
    case EXT4_DIRW_ERR_EXISTS: return "a live entry already has that name";
    case EXT4_DIRW_ERR_ABSENT: return "no entry by that name";
    case EXT4_DIRW_ERR_NOROOM: return "no gap big enough in any existing block";
    case EXT4_DIRW_ERR_NAME:   return "not a usable name";
    case EXT4_CREATE_ERR_NOINODE: return "no free inode left";
    case EXT4_DIRW_ERR_HTREE:  return "hash-indexed directory, refused rather than corrupted";
    default:                   return "unknown error";
    }
}

int main(int argc, char **argv) {
    int adding    = (argc == 7 && !strcmp(argv[3], "add"));
    int removing  = (argc == 5 && !strcmp(argv[3], "remove"));
    int creating  = (argc == 6 && !strcmp(argv[3], "create"));
    int unlinking = (argc == 6 && !strcmp(argv[3], "unlink"));
    if (!adding && !removing && !creating && !unlinking) {
        fprintf(stderr,
                "usage: %s <image> <dir-inode> add <name> <inode> <file-type>\n"
                "       %s <image> <dir-inode> remove <name>\n"
                "       %s <image> <dir-inode> create <name> <when>\n"
                "       %s <image> <dir-inode> unlink <name> <when>\n",
                argv[0], argv[0], argv[0], argv[0]);
        return 2;
    }

    img_ctx ctx = { fopen(argv[1], "rb"), 1024 };
    if (!ctx.fp) { perror("open for reading"); return 2; }

    ext4_fs r;
    if (ext4_open(&r, img_read, &ctx) != EXT4_OK) {
        fprintf(stderr, "cannot read %s as an ext4 image\n", argv[1]);
        return 2;
    }
    ctx.block_size = r.block_size;

    ext4_wfs w;
    if (ext4_fs_open(&w, argv[1])) {
        fprintf(stderr, "cannot open %s for writing\n", argv[1]);
        return 2;
    }

    uint32_t dir_ino = (uint32_t)strtoul(argv[2], NULL, 10);
    /* The entry and the link count are two halves of one link, and e2fsck checks
     * them against each other. The driver does both so that a correct run leaves
     * nothing for the harness to excuse. */
    int rc;
    if (creating) {
        uint32_t made = 0;
        rc = ext4_create_file(&w, &r, dir_ino, argv[4], 0644,
                              (uint32_t)strtoul(argv[5], NULL, 10), &made);
        if (rc == EXT4_DIRW_OK) printf("%u\n", made);
    } else if (unlinking) {
        rc = ext4_unlink_file(&w, &r, dir_ino, argv[4],
                              (uint32_t)strtoul(argv[5], NULL, 10));
    } else if (adding) {
        uint32_t target = (uint32_t)strtoul(argv[5], NULL, 10);
        rc = ext4_dir_add(&w, &r, dir_ino, target,
                          (uint8_t)strtoul(argv[6], NULL, 10), argv[4]);
        if (rc == EXT4_DIRW_OK && ext4_inode_adjust_links(&w, target, +1) != EXTW_OK)
            rc = EXT4_DIRW_ERR_IO;
    } else {
        uint32_t target = 0;
        rc = ext4_dir_lookup(&r, dir_ino, argv[4], &target);
        if (rc == EXT4_DIRW_OK) rc = ext4_dir_remove(&w, &r, dir_ino, argv[4]);
        if (rc == EXT4_DIRW_OK && ext4_inode_adjust_links(&w, target, -1) != EXTW_OK)
            rc = EXT4_DIRW_ERR_IO;
    }

    if (rc == EXT4_DIRW_OK && ext4_fs_flush(&w)) {
        perror("flush");
        rc = EXT4_DIRW_ERR_IO;
    }
    if (rc != EXT4_DIRW_OK) fprintf(stderr, "%s\n", strerr(rc));

    ext4_fs_close(&w);
    fclose(ctx.fp);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}
