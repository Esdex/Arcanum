/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * Host test harness for the clean-room ext4 library - PC-only, not shipped in the
 * app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
 * never linked or copied. See issue #7.
 */

/*
 * libFuzzer target: the whole driver over an attacker-chosen image.
 *
 * Every field this driver reads is bytes out of a container somebody handed the
 * user, and both bugs #144 and #146 were that class - found by hand, because
 * nothing here generates malformed input. The other stands all start from an image
 * mke2fs built and check that the result is correct; this one starts from
 * arbitrary bytes and only asks that the driver stays inside its own memory and
 * comes back.
 *
 * The input is the image. Both backends are plain memory, so no file is touched
 * and a run is a few microseconds - which is what makes coverage-guided fuzzing
 * worth doing rather than a scripted mutator.
 *
 * Writes are driven too, not just reads. The write path is where the damage would
 * be, it is bigger than the read path, and it is reached only if the reader
 * accepted the image first - so a mutator that never produces a plausible
 * superblock would exercise none of it. That is precisely what coverage guidance
 * is for.
 *
 * Build and run through fuzz.sh.
 */

#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_alloc.h"
#include "ext4_create.h"
#include "ext4_dir.h"
#include "ext4_dirwrite.h"
#include "ext4_extents.h"
#include "ext4_extwrite.h"
#include "ext4_io.h"
#include "ext4_path.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* An image smaller than this cannot hold a superblock at offset 1024, so there is
 * nothing to test - reject early rather than let every backend read fail. */
#define MIN_IMAGE 2048u

/* The images the corpus is built from are ~1 MB. A cap keeps a mutated size field
 * from making libFuzzer allocate something huge for a case that proves nothing. */
#define MAX_IMAGE (8u * 1024 * 1024)

/*
 * How far the driver is allowed to walk. Some of these structures are graphs the
 * image itself describes - a directory's blocks, an extent tree's children - and
 * a corrupt one can describe a very large or a cyclic walk. That is a real defect
 * class and the driver has its own bounds for it, but a fuzz run must not spend a
 * minute inside one input either. Anything that exceeds this is reported by
 * libFuzzer as a timeout, which is how a missing bound shows up.
 */
#define MAX_ENTRIES 4096
#define MAX_INODES  64

typedef struct {
    uint8_t *data;
    size_t   size;
    uint32_t block_size;
} mem_img;

/* Reads outside the image give zeroes rather than an error, deliberately. An error
 * lets the driver bail at the first odd block and leaves everything past that
 * unexercised; zeroes keep it walking into whatever it makes of them, which is the
 * behaviour a truncated or lying image would produce anyway. */
static int mem_read(void *user, uint64_t block, void *buf) {
    mem_img *m = (mem_img *)user;
    uint64_t off = block * (uint64_t)m->block_size;
    memset(buf, 0, m->block_size);
    if (off >= m->size) return EXT4_OK;
    size_t n = m->size - off;
    if (n > m->block_size) n = m->block_size;
    memcpy(buf, m->data + off, n);
    return EXT4_OK;
}

static int io_read(void *user, uint64_t block, uint32_t block_size, void *buf) {
    mem_img *m = (mem_img *)user;
    uint64_t off = block * (uint64_t)block_size;
    memset(buf, 0, block_size);
    if (off >= m->size) return 0;
    size_t n = m->size - off;
    if (n > block_size) n = block_size;
    memcpy(buf, m->data + off, n);
    return 0;
}

/* Writes past the end are dropped. Growing the buffer would let a mutated block
 * count turn one input into a multi-gigabyte allocation. */
static int io_write(void *user, uint64_t block, uint32_t block_size, const void *buf) {
    mem_img *m = (mem_img *)user;
    uint64_t off = block * (uint64_t)block_size;
    if (off >= m->size) return 0;
    size_t n = m->size - off;
    if (n > block_size) n = block_size;
    memcpy(m->data + off, buf, n);
    return 0;
}

static int io_flush(void *user) { (void)user; return 0; }

typedef struct { int n; } count_ctx;

static int count_entry(void *user, const ext4_dir_entry *e) {
    count_ctx *c = (count_ctx *)user;
    (void)e;
    return (++c->n >= MAX_ENTRIES) ? 1 : 0;
}

static int count_run(void *user, const ext4_extent_run *r) {
    count_ctx *c = (count_ctx *)user;
    (void)r;
    return (++c->n >= MAX_ENTRIES) ? 1 : 0;
}

/* Read-side pass: everything that consumes the image without changing it. */
static void drive_reader(mem_img *m) {
    ext4_fs fs;
    memset(&fs, 0, sizeof(fs));
    m->block_size = 1024;                 /* bootstrap, as every opener does */
    if (ext4_open(&fs, mem_read, m) != EXT4_OK) return;
    m->block_size = fs.block_size;

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    for (uint32_t ino = 1; ino <= MAX_INODES; ino++) {
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(&fs, ino, inode, sizeof(inode)) != EXT4_OK) continue;

        count_ctx c = { 0 };
        ext4_walk_extents(&fs, inode, count_run, &c);

        uint64_t phys = 0;
        int uninit = 0;
        ext4_map_block(&fs, inode, 0, &phys, &uninit);
        ext4_map_block(&fs, inode, 1, &phys, &uninit);

        int checked = 0;
        ext4_check_extent_tree(&fs, ino, 0, inode, &checked);

        c.n = 0;
        ext4_dir_iterate(&fs, inode, count_entry, &c);
        ext4_dir_check_csums(&fs, ino, 0, inode, &checked);

        /* Bounded so a lying i_size cannot ask for a gigabyte. */
        uint8_t buf[4096];
        ext4_read_file(&fs, inode, 0, buf, sizeof(buf));
        ext4_inode_size(inode);
    }

    /* Path resolution ties the directory reader and the inode reader together, and
     * is the shape the JNI layer actually calls. `..` and a deep path are in here
     * because both are ways an image can describe a cycle. */
    static const char *paths[] = {
        "/", "/lost+found", "/a/b/c", "/../..", "/.", "//x//y",
        "/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a",
    };
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        uint32_t ino = 0;
        int is_dir = 0;
        ext4_resolve_path(&fs, paths[i], &ino, &is_dir);
        uint32_t parent = 0;
        char name[256];
        ext4_resolve_parent(&fs, paths[i], &parent, name, sizeof(name));
    }
}

/* Write-side pass. Reached only when both opens accept the image, which is where
 * coverage guidance earns its keep. */
static void drive_writer(mem_img *m) {
    ext4_fs r;
    memset(&r, 0, sizeof(r));
    m->block_size = 1024;
    if (ext4_open(&r, mem_read, m) != EXT4_OK) return;
    m->block_size = r.block_size;

    ext4_io io;
    memset(&io, 0, sizeof(io));
    io.read_block = io_read;
    io.write_block = io_write;
    io.flush = io_flush;
    io.user = m;
    io.block_size = 1024;

    ext4_wfs w;
    memset(&w, 0, sizeof(w));
    if (ext4_fs_open_io(&w, io) != 0) return;

    const uint32_t when = 1700000000u;
    const uint32_t root = EXT4_ROOT_INO;
    uint32_t ino = 0;

    /* Ordinary work, in an order that reaches every write entry point. What each
     * call returns is not checked: on a corrupt image a refusal is the correct
     * outcome and a success is equally allowed. The only thing under test here is
     * that the driver does not step outside its own memory, or fail to come back,
     * whichever it chooses. */
    ext4_mkdir(&w, &r, root, "fuzzdir", 0755, when, &ino);
    ext4_create_file(&w, &r, root, "fuzzfile", 0644, when, &ino);
    if (ino) {
        ext4_append_blocks(&w, ino, 2, NULL, NULL, NULL);
        ext4_set_size(&w, ino, 1234);
        ext4_truncate_blocks(&w, ino, 1);
        ext4_set_mtime(&w, ino, when);
        ext4_inode_adjust_links(&w, ino, 1);
        ext4_inode_adjust_links(&w, ino, -1);
        static const uint8_t payload[600] = { 0 };
        ext4_write_at(&w, &r, ino, 0, payload, sizeof(payload));
    }
    ext4_dir_add(&w, &r, root, 12, 1, "added");
    ext4_dir_remove(&w, &r, root, "added");
    ext4_dir_set_dotdot(&w, &r, root, root);
    ext4_rename(&w, &r, root, "fuzzfile", root, "renamed");
    ext4_unlink_file(&w, &r, root, "renamed", when);
    ext4_rmdir(&w, &r, root, "fuzzdir", when);

    ext4_fs_mark_dirty(&w);
    ext4_fs_mark_clean(&w);
    ext4_fs_flush(&w);
    ext4_fs_close(&w);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < MIN_IMAGE || size > MAX_IMAGE) return 0;

    /* A private copy, so the writer may scribble on it and so ASan brackets the
     * image itself - a read one byte off either end is then a finding rather than
     * a quiet look at libFuzzer's buffer. */
    mem_img m;
    m.size = size;
    m.data = (uint8_t *)malloc(size);
    if (!m.data) return 0;
    memcpy(m.data, data, size);
    m.block_size = 1024;

    drive_reader(&m);

    /* The writer gets the image back as it was, so a run is not two passes over
     * two different things. */
    memcpy(m.data, data, size);
    m.block_size = 1024;
    drive_writer(&m);

    free(m.data);
    return 0;
}
