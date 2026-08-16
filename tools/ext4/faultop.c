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
 * Fault-injection driver: run one write operation with the Nth block write forced
 * to fail, so what it leaves behind can be inspected.
 *
 *   faultop <image> <fail_at> mkdir    <parent_ino> <name>
 *   faultop <image> <fail_at> rename   <src_parent> <src_name> <dst_parent> <dst_name>
 *   faultop <image> <fail_at> create   <parent_ino> <name>
 *   faultop <image> <fail_at> unlink   <parent_ino> <name>
 *   faultop <image> <fail_at> rmdir    <parent_ino> <name>
 *   faultop <image> <fail_at> append   <ino> <blocks>
 *   faultop <image> <fail_at> truncate <ino> <keep_blocks>
 *   faultop <image> <fail_at> setsize  <ino> <bytes>
 *   faultop <image> <fail_at> writeat  <ino> <offset> <len>
 *   faultop <image> <fail_at> add      <dir_ino> <target_ino> <name>
 *
 * `add` is here for the directory rebuild a hash-indexed directory gets on its
 * first write (#141): it is a whole directory rewritten in place, which is the
 * largest thing this layer does between two consistent states.
 *
 * The writer opens through ext4_fs_open_io with a write callback that fails write
 * number `fail_at` and lets every other write - including any the code does in
 * response - through. faultcheck.py sweeps `fail_at` across every write of an
 * operation and judges what is left. `fail_at` of 0 never fails, which is how the
 * sweep learns the write count.
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

/* Appended blocks carry a pattern keyed to their logical number, so a block
 * written to the wrong place is not the same bytes as the one that belongs there.
 * Nothing here reads it back - that is appendcheck's job - but a fault sweep that
 * filled with zeroes would make a misplaced write invisible to any later check
 * run on the residual. */
typedef struct { uint32_t bs; } fill_ctx;
static int fill_pattern(void *user, uint32_t logical, uint8_t *buf) {
    const fill_ctx *c = (const fill_ctx *)user;
    for (uint32_t i = 0; i < c->bs; i++)
        buf[i] = (uint8_t)((logical * 31u + i) & 0xff);
    return 0;
}

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

    /* The same bracket the app puts around a write session (#142): the volume
     * says on disk that a write is outstanding before the first one happens, and
     * says it is finished only after the last. Done here rather than left to the
     * harness so that the sweep measures the sequence that actually ships - a
     * fault anywhere inside has to leave the volume marked not clean, which is
     * the whole point of the field. */
    if (ext4_fs_mark_dirty(&w)) { fprintf(stderr, "could not mark dirty\n"); return 2; }

    int orc;
    uint32_t a4 = argc > 4 ? (uint32_t)strtoul(argv[4], NULL, 10) : 0;
    if (!strcmp(op, "mkdir") && argc == 6) {
        uint32_t out = 0;
        orc = ext4_mkdir(&w, &r, a4, argv[5], 0755, WHEN, &out);
    } else if (!strcmp(op, "rename") && argc == 8) {
        orc = ext4_rename(&w, &r, a4, argv[5],
                          (uint32_t)strtoul(argv[6], NULL, 10), argv[7]);
    } else if (!strcmp(op, "create") && argc == 6) {
        uint32_t out = 0;
        orc = ext4_create_file(&w, &r, a4, argv[5], 0644, WHEN, &out);
    } else if (!strcmp(op, "unlink") && argc == 6) {
        orc = ext4_unlink_file(&w, &r, a4, argv[5], WHEN);
    } else if (!strcmp(op, "rmdir") && argc == 6) {
        orc = ext4_rmdir(&w, &r, a4, argv[5], WHEN);
    } else if (!strcmp(op, "append") && argc == 6) {
        uint32_t added = 0;
        fill_ctx fc = { w.block_size };
        orc = ext4_append_blocks(&w, a4, (uint32_t)strtoul(argv[5], NULL, 10),
                                 fill_pattern, &fc, &added);
    } else if (!strcmp(op, "truncate") && argc == 6) {
        orc = ext4_truncate_blocks(&w, a4, (uint32_t)strtoul(argv[5], NULL, 10));
    } else if (!strcmp(op, "setsize") && argc == 6) {
        orc = ext4_set_size(&w, a4, strtoull(argv[5], NULL, 10));
    } else if (!strcmp(op, "writeat") && argc == 7) {
        uint64_t at  = strtoull(argv[5], NULL, 10);
        uint32_t len = (uint32_t)strtoul(argv[6], NULL, 10);
        uint8_t *data = malloc(len ? len : 1);
        if (!data) { fprintf(stderr, "out of memory\n"); return 2; }
        for (uint32_t i = 0; i < len; i++) data[i] = (uint8_t)(0x40 + ((at + i) % 59));
        orc = ext4_write_at(&w, &r, a4, at, data, len);
        free(data);
    } else if (!strcmp(op, "add") && argc == 7) {
        /* A name and a link count are two halves of one link, and the sweep is
         * judged on e2fsck being able to repair what is left - so the unfaulted
         * run has to leave nothing to repair. Moving only the entry would leave a
         * link count short every time, including at fault point zero. */
        uint32_t target = (uint32_t)strtoul(argv[5], NULL, 10);
        orc = ext4_dir_add(&w, &r, a4, target, 1, argv[6]);
        if (orc == EXT4_DIRW_OK && ext4_inode_adjust_links(&w, target, +1) != EXTW_OK)
            orc = EXT4_DIRW_ERR_IO;
    } else {
        fprintf(stderr, "bad op/args\n"); return 2;
    }

    /* Only a run that got all the way through says so. A failed operation leaves
     * the volume marked not clean, which is exactly what the next open should
     * find. */
    if (orc == 0 && ext4_fs_mark_clean(&w)) orc = -1;

    ext4_fs_close(&w);
    fflush(fp);
    fclose(fp);
    printf("rc=%d writes=%ld\n", orc, wc.count);
    return 0;
}
