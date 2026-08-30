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
 * Driver for the handles a mount holds on to (#155, second half).
 *
 *   session <image> hold|reopen <script>
 *
 * It runs one script of operations twice over identical copies of an image:
 *
 *   reopen  the shape every other driver here uses and the app used until now -
 *           open the reader and the writable handle inside each operation, close
 *           them at its end
 *   hold    through ext4_session.c, which opens them once and keeps them
 *
 * **The oracle is the other mode.** `reopen` is not a model of the code; it is the
 * behaviour that shipped, was measured against e2fsck and fuse2fs by twenty-odd
 * stands, and ran on real volumes. If holding a handle across operations is
 * equivalent, the two images are equal byte for byte at the end. Nothing here
 * judges an image by our own reader.
 *
 * ## What the script must contain to be worth running
 *
 * A failed write. That is the whole risk of holding a handle: the descriptor table
 * and the free counts live in memory, and an operation that dies part way leaves
 * them describing a disk that never received them. Closing after every operation
 * made that self-correcting for free. So `failwrite` arms a write to fail, and
 * **this driver deliberately does not tell the session about it** - no drop, no
 * hint - because the rule under test is that a failed write poisons the handle by
 * itself. A session that believed its own memory would carry the difference into
 * every later operation and the images would part.
 *
 * `drop` is the other half: the caller-driven case (jni_ext4.cpp's
 * WriteSession::tear, and a format) where nothing failed but memory is ahead of
 * the disk anyway. In `reopen` mode it is a no-op, since that mode holds nothing.
 *
 * `format` rewrites the filesystem underneath with a different checksum seed. A
 * session that kept its reader across it would keep a parse of a filesystem that
 * no longer exists, and stamp checksums from the old seed onto the new volume -
 * the exact silent corruption #147 found in another place.
 *
 * ## The clock
 *
 * Every operation is stamped with WHEN + its index, so timestamps differ between
 * operations and agree between the two modes. It is also what catches a session
 * that takes `now` at open instead of on every ask: s_wtime would then stop
 * moving in `hold` and keep moving in `reopen`.
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
#include "ext4_mkfs.h"
#include "ext4_path.h"
#include "ext4_session.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WHEN 1784639915u

/* ── the image, and the two ways of reaching it ───────────────────────────── */

/*
 * One open file serving both the read-only callback and the io. The reader carries
 * its block size here, in the context, exactly as ext4_device_reader does on the
 * device; the io is told the size on every call.
 */
typedef struct {
    FILE    *fp;
    uint32_t rd_block_size;
    long     fail_in;        /* writes remaining before one fails; 0 = disarmed */
    long     flush_fail_in;  /* the same for flushes */
    long     writes;
    long     reads;          /* what holding the handles is meant to remove */
} img;

static int r_read(void *ctx, uint64_t block, void *buf) {
    img *m = (img *)ctx;
    m->reads++;
    if (fseeko(m->fp, (off_t)block * m->rd_block_size, SEEK_SET)) return EXT4_ERR_IO;
    return fread(buf, 1, m->rd_block_size, m->fp) == m->rd_block_size
               ? EXT4_OK : EXT4_ERR_IO;
}

static void r_set_block_size(void *ctx, uint32_t block_size) {
    ((img *)ctx)->rd_block_size = block_size;
}

static int io_read(void *user, uint64_t block, uint32_t bs, void *buf) {
    img *m = (img *)user;
    m->reads++;
    if (fseeko(m->fp, (off_t)block * bs, SEEK_SET)) return -1;
    return fread(buf, 1, bs, m->fp) == bs ? 0 : -1;
}

static int io_write(void *user, uint64_t block, uint32_t bs, const void *buf) {
    img *m = (img *)user;
    m->writes++;
    if (m->fail_in > 0 && --m->fail_in == 0) {
        fprintf(stderr, "  (write %ld to block %llu refused on purpose)\n",
                m->writes, (unsigned long long)block);
        return -1;
    }
    if (fseeko(m->fp, (off_t)block * bs, SEEK_SET)) return -1;
    return fwrite(buf, 1, bs, m->fp) == bs ? 0 : -1;
}

/*
 * A flush that fails is its own case, and not the same as a failed write. The
 * bytes a flush covers were all written successfully; what is unknown is whether
 * they reached the medium, which on a device is the whole reason the barrier
 * exists. The image on disk therefore matches either way, and the only thing that
 * can tell a session that treats this as poison from one that shrugs is the open
 * counter - which is why the scenario asserts it.
 */
static int io_flush(void *user) {
    img *m = (img *)user;
    if (m->flush_fail_in > 0 && --m->flush_fail_in == 0) {
        fprintf(stderr, "  (a flush refused on purpose)\n");
        return -1;
    }
    return fflush(m->fp);
}

static ext4_io make_io(img *m) {
    ext4_io io;
    memset(&io, 0, sizeof(io));
    io.read_block  = io_read;
    io.write_block = io_write;
    io.flush       = io_flush;
    io.user        = m;
    io.block_size  = 0;
    return io;
}

/* ── the two shapes ───────────────────────────────────────────────────────── */

typedef struct {
    img          m;
    int          hold;
    ext4_session *s;        /* hold mode only */

    ext4_fs      r_local;   /* reopen mode only */
    ext4_wfs     w_local;
    int          r_local_open;
    int          w_local_open;
    ext4_wfs    *w_cur;     /* the handle this operation is using, either shape */

    unsigned     reader_opens;   /* reopen mode counts its own */
    unsigned     writer_opens;
} app;

/*
 * A handle that cannot be had is an ordinary failed operation, not a fatal one:
 * that is what the app does with it (ERR_FS back to Kotlin, and on to the next
 * call), and a flush refused inside mark_dirty arrives exactly here.
 */
static int get_reader(app *a, ext4_fs **out) {
    if (a->hold) return ext4_session_reader(a->s, out);
    if (!a->r_local_open) {
        a->m.rd_block_size = 1024;
        if (ext4_open(&a->r_local, r_read, &a->m) != EXT4_OK) return -1;
        a->m.rd_block_size = a->r_local.block_size;
        a->r_local_open = 1;
        a->reader_opens++;
    }
    *out = &a->r_local;
    return 0;
}

/* The writable handle, marked as being written to - jni_ext4.cpp's WriteSession
 * constructor, in the two shapes. */
static int get_writer(app *a, uint32_t now, ext4_wfs **out) {
    if (a->hold) {
        if (ext4_session_writer(a->s, now, out) != 0) return -1;
    } else {
        if (!a->w_local_open) {
            if (ext4_fs_open_io(&a->w_local, make_io(&a->m)) != 0) return -1;
            a->w_local_open = 1;
            a->writer_opens++;
        }
        a->w_local.now = now;
        *out = &a->w_local;
    }
    a->w_cur = *out;
    if (ext4_fs_mark_dirty(*out) != 0) return -1;
    return 0;
}

/*
 * The end of an operation. `ok` false is the torn case: the volume keeps its
 * needs-a-check mark, and - the point of this stand - nothing is told to forget
 * anything. In `reopen` the handles go because that mode has no others; in `hold`
 * the session is left to notice for itself that a write failed.
 */
static void end_op(app *a, int ok, int used_writer) {
    /* The handle this operation used, never asked for again: asking is what opens
     * a fresh one after a poisoning, and a mark_clean on a fresh handle would put
     * "everything is down" on a volume that has a residual. */
    if (used_writer && ok && a->w_cur) ext4_fs_mark_clean(a->w_cur);
    a->w_cur = NULL;
    if (!a->hold) {
        if (a->w_local_open) { ext4_fs_close(&a->w_local); a->w_local_open = 0; }
        a->r_local_open = 0;
        memset(&a->r_local, 0, sizeof(a->r_local));
    }
}

/* ── operations, each the shape of one JNI entry point ────────────────────── */

/* The position-dependent pattern chunkwrite.c uses, so a block landing at the
 * wrong offset is a difference and not just a wrong length. */
static uint8_t pat(uint64_t i) { return (uint8_t)(i ^ (i >> 8) ^ (i >> 16)); }

typedef struct { uint64_t start, len; uint32_t bs, base_logical; } src_t;

static int fill(void *user, uint32_t logical, uint8_t *buf) {
    const src_t *s = (const src_t *)user;
    uint64_t chunk_off = (uint64_t)(logical - s->base_logical) * s->bs;
    for (uint32_t k = 0; k < s->bs; k++) {
        uint64_t rel = chunk_off + k;
        buf[k] = rel < s->len ? pat(s->start + rel) : 0;
    }
    return 0;
}

static int op_mkdir(app *a, const char *path, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t dir_ino, ino;
    char name[256];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_parent(r, path, &dir_ino, name, sizeof(name)) != EXT4_PATH_OK) {
        end_op(a, 1, 0); return 1;
    }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_mkdir(w, r, dir_ino, name, 0755, now, &ino);
    end_op(a, rc == EXT4_DIRW_OK, 1);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}

static int op_create(app *a, const char *path, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t dir_ino, ino;
    char name[256];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_parent(r, path, &dir_ino, name, sizeof(name)) != EXT4_PATH_OK) {
        end_op(a, 1, 0); return 1;
    }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_create_file(w, r, dir_ino, name, 0644, now, &ino);
    end_op(a, rc == EXT4_DIRW_OK, 1);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}

/* One chunk of an import: append `bytes` at the current end of the file. */
static int op_append(app *a, const char *path, uint64_t bytes, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t ino;
    uint8_t inode[EXT4_MAX_INODE_SIZE];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_path(r, path, &ino, NULL) != EXT4_PATH_OK) { end_op(a, 1, 0); return 1; }
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) { end_op(a, 1, 0); return 1; }
    uint64_t size = ext4_inode_size(inode);
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    if (size % w->block_size) {
        fprintf(stderr, "append onto a size that is not block-aligned (%llu)\n",
                (unsigned long long)size);
        end_op(a, 1, 1); return 2;
    }
    src_t s = { size, bytes, w->block_size, (uint32_t)(size / w->block_size) };
    uint32_t nblocks = (uint32_t)((bytes + w->block_size - 1) / w->block_size), got = 0;
    int rc = ext4_append_blocks(w, ino, nblocks, fill, &s, &got);
    if (rc == EXTW_OK && got == nblocks) rc = ext4_set_size(w, ino, size + bytes);
    if (rc == EXTW_OK) rc = ext4_set_mtime(w, ino, now);
    end_op(a, rc == EXTW_OK, 1);
    return rc == EXTW_OK ? 0 : 1;
}

static int op_unlink(app *a, const char *path, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t dir_ino;
    char name[256];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_parent(r, path, &dir_ino, name, sizeof(name)) != EXT4_PATH_OK) {
        end_op(a, 1, 0); return 1;
    }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_unlink_file(w, r, dir_ino, name, now);
    end_op(a, rc == EXT4_DIRW_OK, 1);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}

static int op_rmdir(app *a, const char *path, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t dir_ino;
    char name[256];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_parent(r, path, &dir_ino, name, sizeof(name)) != EXT4_PATH_OK) {
        end_op(a, 1, 0); return 1;
    }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_rmdir(w, r, dir_ino, name, now);
    end_op(a, rc == EXT4_DIRW_OK, 1);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}

static int op_rename(app *a, const char *from, const char *to, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t sp, dp;
    char sn[256], dn[256];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_parent(r, from, &sp, sn, sizeof(sn)) != EXT4_PATH_OK ||
        ext4_resolve_parent(r, to,   &dp, dn, sizeof(dn)) != EXT4_PATH_OK) {
        end_op(a, 1, 0); return 1;
    }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_rename(w, r, sp, sn, dp, dn);
    end_op(a, rc == EXT4_DIRW_OK, 1);
    return rc == EXT4_DIRW_OK ? 0 : 1;
}

static int op_mtime(app *a, const char *path, uint32_t when, uint32_t now) {
    ext4_fs *r; ext4_wfs *w; uint32_t ino;
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_path(r, path, &ino, NULL) != EXT4_PATH_OK) { end_op(a, 1, 0); return 1; }
    if (get_writer(a, now, &w)) { end_op(a, 0, 1); return 1; }
    int rc = ext4_set_mtime(w, ino, when);
    end_op(a, rc == EXTW_OK, 1);
    return rc == EXTW_OK ? 0 : 1;
}

static int count_cb(void *user, const ext4_dir_entry *e) {
    (void)e; (*(int *)user)++; return 0;
}

static int op_list(app *a, const char *path) {
    ext4_fs *r; uint32_t ino; int n = 0;
    uint8_t inode[EXT4_MAX_INODE_SIZE];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_path(r, path, &ino, NULL) != EXT4_PATH_OK) { end_op(a, 1, 0); return 1; }
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) { end_op(a, 1, 0); return 1; }
    int rc = ext4_dir_iterate(r, inode, count_cb, &n);
    end_op(a, 1, 0);
    return rc == EXT4_OK ? 0 : 1;
}

static int op_read(app *a, const char *path, uint64_t off, uint64_t len) {
    ext4_fs *r; uint32_t ino;
    uint8_t inode[EXT4_MAX_INODE_SIZE];
    if (get_reader(a, &r)) return 1;
    if (ext4_resolve_path(r, path, &ino, NULL) != EXT4_PATH_OK) { end_op(a, 1, 0); return 1; }
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) { end_op(a, 1, 0); return 1; }
    uint8_t *buf = malloc((size_t)len ? (size_t)len : 1);
    if (!buf) { end_op(a, 1, 0); return -1; }
    long got = ext4_read_file(r, inode, off, buf, len);
    free(buf);
    end_op(a, 1, 0);
    return got < 0 ? 1 : 0;
}

/*
 * A fresh filesystem over the same image, with a checksum seed the old one did not
 * have. Anything still holding a parse of the old volume is now holding a lie, and
 * the operations after this in the script are what make that visible.
 */
static int op_format(app *a, uint64_t size_bytes, uint32_t now) {
    ext4_mkfs_params p;
    ext4_mkfs_result res;
    memset(&p, 0, sizeof(p));
    ext4_mkfs_default_params(&p, size_bytes);
    p.when = now;
    for (int i = 0; i < 16; i++) { p.uuid[i] = (uint8_t)(0xA0 + i); p.hash_seed[i] = (uint8_t)(0x5C - i); }
    ext4_io io = make_io(&a->m);
    if (ext4_mkfs(&io, &p, &res) != EXT4_MKFS_OK) return -1;
    if (a->hold) ext4_session_drop(a->s);
    else { if (a->w_local_open) { ext4_fs_close(&a->w_local); a->w_local_open = 0; }
           a->r_local_open = 0; memset(&a->r_local, 0, sizeof(a->r_local)); }
    return 0;
}

/* ── the script ───────────────────────────────────────────────────────────── */

static int run_line(app *a, char *line, int index, int *failures) {
    char *verb = strtok(line, " \t\n");
    if (!verb || verb[0] == '#') return 0;
    uint32_t now = WHEN + (uint32_t)index;
    char *a1 = strtok(NULL, " \t\n");
    char *a2 = strtok(NULL, " \t\n");
    char *a3 = strtok(NULL, " \t\n");
    int rc;

    if      (!strcmp(verb, "mkdir")  && a1) rc = op_mkdir(a, a1, now);
    else if (!strcmp(verb, "create") && a1) rc = op_create(a, a1, now);
    else if (!strcmp(verb, "append") && a1 && a2)
        rc = op_append(a, a1, strtoull(a2, NULL, 0), now);
    else if (!strcmp(verb, "unlink") && a1) rc = op_unlink(a, a1, now);
    else if (!strcmp(verb, "rmdir")  && a1) rc = op_rmdir(a, a1, now);
    else if (!strcmp(verb, "rename") && a1 && a2) rc = op_rename(a, a1, a2, now);
    else if (!strcmp(verb, "mtime")  && a1 && a2)
        rc = op_mtime(a, a1, (uint32_t)strtoul(a2, NULL, 0), now);
    else if (!strcmp(verb, "list")   && a1) rc = op_list(a, a1);
    else if (!strcmp(verb, "read")   && a1 && a2 && a3)
        rc = op_read(a, a1, strtoull(a2, NULL, 0), strtoull(a3, NULL, 0));
    else if (!strcmp(verb, "format") && a1)
        rc = op_format(a, strtoull(a1, NULL, 0), now);
    else if (!strcmp(verb, "failwrite") && a1) {
        a->m.fail_in = strtol(a1, NULL, 0);
        return 0;
    }
    else if (!strcmp(verb, "failflush") && a1) {
        a->m.flush_fail_in = strtol(a1, NULL, 0);
        return 0;
    }
    else if (!strcmp(verb, "drop")) {
        /* The caller-driven forget: WriteSession::tear, and a format. `reopen`
         * holds nothing, so there is nothing for it to do. */
        if (a->hold) ext4_session_drop(a->s);
        return 0;
    }
    else {
        fprintf(stderr, "unknown command: %s\n", verb);
        return -1;
    }

    if (rc < 0) return -1;
    if (rc > 0) (*failures)++;
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 4 || (strcmp(argv[2], "hold") && strcmp(argv[2], "reopen"))) {
        fprintf(stderr, "usage: %s <image> hold|reopen <script>\n", argv[0]);
        return 2;
    }
    app a;
    memset(&a, 0, sizeof(a));
    a.hold = !strcmp(argv[2], "hold");
    a.m.rd_block_size = 1024;
    a.m.fp = fopen(argv[1], "r+b");
    if (!a.m.fp) { perror("open image"); return 2; }
    setvbuf(a.m.fp, NULL, _IONBF, 0);   /* one buffering layer only, so a refused
                                         * write is refused and not held back */

    if (a.hold) {
        a.s = ext4_session_new(r_read, &a.m, r_set_block_size, make_io(&a.m));
        if (!a.s) { fprintf(stderr, "no session\n"); return 2; }
    }

    FILE *script = fopen(argv[3], "r");
    if (!script) { perror("open script"); return 2; }

    char line[1024];
    int index = 0, failures = 0, bad = 0;
    while (fgets(line, sizeof(line), script)) {
        if (run_line(&a, line, index, &failures)) { bad = 1; break; }
        index++;
    }
    fclose(script);

    unsigned ro = a.reader_opens, wo = a.writer_opens;
    if (a.hold) ext4_session_opens(a.s, &ro, &wo);

    if (a.hold) ext4_session_free(a.s);
    else if (a.w_local_open) ext4_fs_close(&a.w_local);
    fclose(a.m.fp);

    printf("mode=%s ops=%d failed=%d reader_opens=%u writer_opens=%u reads=%ld writes=%ld\n",
           argv[2], index, failures, ro, wo, a.m.reads, a.m.writes);
    return bad ? 1 : 0;
}
