/*
 * Fault-injection driver: run one create-layer operation with the Nth block write
 * forced to fail, so the operation's rollback can be exercised.
 *
 *   faultop <image> <fail_at> mkdir  <parent_ino> <name>
 *   faultop <image> <fail_at> rename <src_parent> <src_name> <dst_parent> <dst_name>
 *
 * The writer opens through ext4_fs_open_io with a write callback that fails write
 * number `fail_at` and lets every other write - including the rollback's - through.
 * A rollback that is correct then leaves the image e2fsck-clean whatever the fault
 * point; faultcheck.py sweeps `fail_at` and checks exactly that. `fail_at` of 0
 * never fails, which is how the sweep learns the write count.
 *
 * Prints "rc=<n> writes=<n>".
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_create.h"
#include "ext4_dirwrite.h"
#include "ext4_extwrite.h"
#include "ext4_extents.h"
#include "ext4_alloc.h"
#include "ext4_io.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define WHEN 1784639915

/* Reader over the plain file (its callback carries no block_size, so it holds one). */
typedef struct { FILE *fp; uint32_t bs; } rctx;
static int r_read(void *ctx, uint64_t block, void *buf) {
    rctx *c = (rctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->bs, SEEK_SET)) return EXT4_ERR_IO;
    size_t got = fread(buf, 1, c->bs, c->fp);
    if (got < c->bs) memset((uint8_t *)buf + got, 0, c->bs - got);   /* sparse tail */
    return EXT4_OK;
}

/* Writer over the same file, failing exactly the fail_at-th write. */
typedef struct { FILE *fp; long count; long fail_at; } wctx;
static int w_read(void *u, uint64_t block, uint32_t bs, void *buf) {
    wctx *c = (wctx *)u;
    if (fseeko(c->fp, (off_t)block * bs, SEEK_SET)) return -1;
    size_t got = fread(buf, 1, bs, c->fp);
    if (got < bs) memset((uint8_t *)buf + got, 0, bs - got);
    return 0;
}
static int w_write(void *u, uint64_t block, uint32_t bs, const void *buf) {
    wctx *c = (wctx *)u;
    c->count++;
    if (c->fail_at != 0 && c->count == c->fail_at) return -1;   /* the injected fault */
    if (fseeko(c->fp, (off_t)block * bs, SEEK_SET)) return -1;
    return fwrite(buf, 1, bs, c->fp) == bs ? 0 : -1;
}
static int w_flush(void *u) { return fflush(((wctx *)u)->fp); }

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <image> <fail_at> mkdir <parent> <name>\n"
                        "       %s <image> <fail_at> rename <sp> <sn> <dp> <dn>\n",
                argv[0], argv[0]);
        return 2;
    }
    const char *img = argv[1];
    long fail_at = strtol(argv[2], NULL, 10);
    const char *op = argv[3];

    FILE *fp = fopen(img, "r+b");
    if (!fp) { perror(img); return 2; }

    rctx rc_ctx = { fp, 1024 };
    ext4_fs r;
    if (ext4_open(&r, r_read, &rc_ctx) != EXT4_OK) { fprintf(stderr, "not ext4\n"); return 2; }
    rc_ctx.bs = r.block_size;

    wctx wc = { fp, 0, fail_at };
    ext4_io io;
    memset(&io, 0, sizeof(io));
    io.read_block = w_read; io.write_block = w_write; io.flush = w_flush; io.user = &wc;
    ext4_wfs w;
    if (ext4_fs_open_io(&w, io) != 0) { fprintf(stderr, "writer open failed\n"); return 2; }

    int orc;
    if (!strcmp(op, "mkdir") && argc == 6) {
        uint32_t out = 0;
        orc = ext4_mkdir(&w, &r, (uint32_t)strtoul(argv[4], NULL, 10), argv[5], 0755, WHEN, &out);
    } else if (!strcmp(op, "rename") && argc == 8) {
        orc = ext4_rename(&w, &r, (uint32_t)strtoul(argv[4], NULL, 10), argv[5],
                          (uint32_t)strtoul(argv[6], NULL, 10), argv[7]);
    } else {
        fprintf(stderr, "bad op/args\n"); return 2;
    }

    ext4_fs_close(&w);
    fflush(fp);
    fclose(fp);
    printf("rc=%d writes=%ld\n", orc, wc.count);
    return 0;
}
