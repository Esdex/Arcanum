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
 * Driver for the one edge the extent writer refuses: EXTW_ERR_FULL.
 *
 *   fullwrite <image> setup <per>   build a checkerboard, create an empty target
 *   fullwrite <image> fill          append to the target until it is refused
 *
 * EXTW_ERR_FULL is raised when a leaf fills and its parent - an index block below
 * the root - is full too, which the append path does not split (see the header and
 * "Later: splitting a full leaf"). It is real but hard to reach: the allocator asks
 * for the block after the file's current end, so a file in free space stays one
 * extent and never approaches the cap. Only a container fragmented so badly that a
 * single file needs a separate extent per block gets there.
 *
 * The cap, in extents, is (index entries per block) x (leaf entries per block) =
 * ((bs - 16) / 12)^2 - because the root pushes down once (depth 1 -> 2) and then
 * tops out when the single index block below the root is full. At 1 KiB that is
 * 84 x 84 = 7056 extents, the lowest of any block size and the one this exercises.
 *
 * ## The construction
 *
 * A file only takes a new extent when the block after its last one is not free, so
 * to make every append a fresh extent the free space has to be a checkerboard:
 * every free block surrounded by used ones. That is built with four "comb" filler
 * files interleaved a block at a time. Appending one block to each in turn, the
 * allocator hands them consecutive blocks, so filler i ends up owning every fourth
 * block (block == i mod 4). Unlinking two of them - the ones owning blocks 1 and 3
 * mod 4 - frees an isolated block between every pair of kept ones (0 and 2 mod 4).
 *
 * Four rather than two because each filler must itself stay under the cap: a single
 * file owning every-other block over the whole region would hit EXTW_ERR_FULL while
 * being built. Splitting the used half and the freed half across two files each
 * keeps every filler well under 7056 extents.
 *
 * `per` is blocks per filler; the freed pool is a bit over 2*per (the two unlinked
 * fillers' data plus the tree blocks their unlink returns). It must exceed what the
 * target consumes reaching the cap - about 7056 data + one tree block per leaf - so
 * that the target hits EXTW_ERR_FULL rather than running out of space first.
 *
 * The setup uses only sub-cap appends, creates and unlinks; none of it touches the
 * FULL path, so a break in the commit-on-FULL logic changes only the fill step.
 * Everything is judged by e2fsprogs and fuse2fs in fullcheck.py, never by our own
 * reader.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_path.h"
#include "ext4_create.h"
#include "ext4_dirwrite.h"
#include "ext4_extwrite.h"
#include "ext4_extents.h"
#include "ext4_alloc.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define WHEN 1784639915

/* The reader over the plain host file. The writer opens the same path itself. */
typedef struct { FILE *fp; uint32_t block_size; } rctx;

static int r_read(void *ctx, uint64_t block, void *buf) {
    rctx *c = (rctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->block_size, SEEK_SET)) return EXT4_ERR_IO;
    return fread(buf, 1, c->block_size, c->fp) == c->block_size ? EXT4_OK : EXT4_ERR_IO;
}

static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

/* A block filled with its own logical number, so a misplaced block would read back
 * wrong - not that fullcheck reads the target's bytes, but the fillers share it. */
static int fill_pat(void *u, uint32_t logical, uint8_t *buf) {
    uint32_t bs = *(const uint32_t *)u;
    for (uint32_t k = 0; k + 8 <= bs; k += 8) { wr32(buf + k, logical); wr32(buf + k + 4, k / 8); }
    return 0;
}

static uint32_t lookup(ext4_fs *r, const char *name) {
    uint32_t ino = 0;
    return ext4_dir_lookup(r, EXT4_ROOT_INO, name, &ino) == EXT4_DIRW_OK ? ino : 0;
}

static const char *FILLERS[4] = { "c0", "c1", "c2", "c3" };

/* Builds the checkerboard and an empty target file. */
static int do_setup(const char *img, ext4_fs *r, int per) {
    ext4_wfs w;

    /* Create the four fillers and the target in one writer session. Root does not
     * grow for five entries, so the reader stays consistent across the creates. */
    if (ext4_fs_open(&w, img)) { fprintf(stderr, "setup: cannot open %s\n", img); return 1; }
    uint32_t ino;
    for (int i = 0; i < 4; i++)
        if (ext4_create_file(&w, r, EXT4_ROOT_INO, FILLERS[i], 0644, WHEN, &ino) != EXT4_DIRW_OK) {
            fprintf(stderr, "setup: create %s failed\n", FILLERS[i]); ext4_fs_close(&w); return 1;
        }
    if (ext4_create_file(&w, r, EXT4_ROOT_INO, "target", 0644, WHEN, &ino) != EXT4_DIRW_OK) {
        fprintf(stderr, "setup: create target failed\n"); ext4_fs_close(&w); return 1;
    }
    ext4_fs_close(&w);

    uint32_t fino[4];
    for (int i = 0; i < 4; i++)
        if (!(fino[i] = lookup(r, FILLERS[i]))) { fprintf(stderr, "setup: lost %s\n", FILLERS[i]); return 1; }

    /* Comb: one block into each filler per round, so each ends up owning every
     * fourth block. Writer-only, so no reader/writer handle can drift here. */
    if (ext4_fs_open(&w, img)) { fprintf(stderr, "setup: reopen failed\n"); return 1; }
    uint32_t bs = w.block_size;
    for (int round = 0; round < per; round++)
        for (int i = 0; i < 4; i++) {
            uint32_t got = 0;
            int arc = ext4_append_blocks(&w, fino[i], 1, fill_pat, &bs, &got);
            if (arc != EXTW_OK || got != 1) {
                fprintf(stderr, "setup: filler %s append failed at round %d (rc=%d got=%u)\n",
                        FILLERS[i], round, arc, got);
                ext4_fs_close(&w); return 1;
            }
        }
    ext4_fs_close(&w);

    /* Unlink the two fillers owning blocks 1 and 3 mod 4, leaving a free block
     * isolated between every pair of kept ones. */
    if (ext4_fs_open(&w, img)) { fprintf(stderr, "setup: reopen for unlink failed\n"); return 1; }
    if (ext4_unlink_file(&w, r, EXT4_ROOT_INO, "c1", WHEN) != EXT4_DIRW_OK ||
        ext4_unlink_file(&w, r, EXT4_ROOT_INO, "c3", WHEN) != EXT4_DIRW_OK) {
        fprintf(stderr, "setup: unlink of a filler failed\n"); ext4_fs_close(&w); return 1;
    }
    ext4_fs_close(&w);

    printf("setup_ok target_inode=%u per=%d\n", lookup(r, "target"), per);
    return 0;
}

/* Appends to the target until the writer refuses, and reports how far it got. */
static int do_fill(const char *img, ext4_fs *r) {
    uint32_t tino = lookup(r, "target");
    if (!tino) { fprintf(stderr, "fill: no target file - run setup first\n"); return 1; }

    ext4_wfs w;
    if (ext4_fs_open(&w, img)) { fprintf(stderr, "fill: cannot open %s\n", img); return 1; }
    uint32_t bs = w.block_size, appended = 0;
    /* Far more than the cap; the point is to be refused, not to succeed. */
    int rc = ext4_append_blocks(&w, tino, 100000u, fill_pat, &bs, &appended);
    ext4_fs_close(&w);

    printf("appended=%u rc=%d target_inode=%u\n", appended, rc, tino);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3 || (strcmp(argv[2], "setup") && strcmp(argv[2], "fill"))) {
        fprintf(stderr, "usage: %s <image> setup <per>\n"
                        "       %s <image> fill\n", argv[0], argv[0]);
        return 2;
    }
    const char *img = argv[1];

    rctx rc = { fopen(img, "rb"), 1024 };
    if (!rc.fp) { perror("open ro"); return 2; }
    ext4_fs r;
    if (ext4_open(&r, r_read, &rc) != EXT4_OK) { fprintf(stderr, "not an ext4 image\n"); return 2; }
    rc.block_size = r.block_size;

    int ret;
    if (!strcmp(argv[2], "setup")) {
        if (argc != 4) { fprintf(stderr, "setup needs <per>\n"); return 2; }
        ret = do_setup(img, &r, atoi(argv[3]));
    } else {
        ret = do_fill(img, &r);
    }

    fclose(rc.fp);
    return ret;
}
