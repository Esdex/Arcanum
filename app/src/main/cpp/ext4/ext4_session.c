/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room ext4: no GPL ext4 source (lwext4's src/ext4_extent.c or
 * src/ext4_xattr.c) was opened or consulted. The on-disk format is implemented
 * from its published description - a data structure, not anyone's expression of
 * it - which is what keeps this code free of lwext4's copyleft. See issue #7.
 */

/* See the header for what this holds and the two rules that let it be held. */
#include "ext4_session.h"

#include <stdlib.h>
#include <string.h>

struct ext4_session {
    ext4_read_block_fn read_block;
    void              *reader_ctx;
    ext4_session_bs_fn set_bs;
    ext4_io            io;          /* the caller's, as given */

    ext4_fs   r;
    ext4_wfs  w;
    int       r_open;
    int       w_open;
    int       w_poisoned;

    unsigned  reader_opens;
    unsigned  writer_opens;
};

/*
 * The writable handle reaches the disk through these rather than through the
 * caller's io directly, so that a failed write cannot go unreported. The handle
 * gets an io whose `user` is the session; these unwrap it and pass everything
 * through unchanged, except for noticing failure.
 *
 * Reads are wrapped only because they have to be - the io is one struct and its
 * `user` had to change. A failed read is not poison: it leaves nothing of this
 * handle's in memory that the disk disagrees with.
 */
static int sess_read(void *user, uint64_t block, uint32_t block_size, void *buf) {
    ext4_session *s = (ext4_session *)user;
    return s->io.read_block(s->io.user, block, block_size, buf);
}

static int sess_write(void *user, uint64_t block, uint32_t block_size, const void *buf) {
    ext4_session *s = (ext4_session *)user;
    int rc = s->io.write_block(s->io.user, block, block_size, buf);
    if (rc) s->w_poisoned = 1;
    return rc;
}

/*
 * A flush that fails leaves what actually reached the disk unknown, which is the
 * same position a failed write leaves us in, so it poisons on the same grounds.
 */
static int sess_flush(void *user) {
    ext4_session *s = (ext4_session *)user;
    int rc = s->io.flush ? s->io.flush(s->io.user) : 0;
    if (rc) s->w_poisoned = 1;
    return rc;
}

static void close_reader(ext4_session *s) {
    if (!s->r_open) return;
    memset(&s->r, 0, sizeof(s->r));   /* ext4_fs owns nothing; there is nothing to free */
    s->r_open = 0;
}

static void close_writer(ext4_session *s) {
    if (!s->w_open) return;
    /* Deliberately no flush. A handle is only ever closed here because what it
     * holds is not to be trusted, and writing it out is the one thing that must
     * not happen. Everything trustworthy was already flushed by the operation that
     * made it - see ext4_fs_mark_clean. */
    ext4_fs_close(&s->w);
    s->w_open = 0;
    s->w_poisoned = 0;
}

ext4_session *ext4_session_new(ext4_read_block_fn read_block, void *reader_ctx,
                               ext4_session_bs_fn set_bs, ext4_io io) {
    ext4_session *s;
    if (!read_block || !io.read_block || !io.write_block) return NULL;
    s = (ext4_session *)calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->read_block = read_block;
    s->reader_ctx = reader_ctx;
    s->set_bs     = set_bs;
    s->io         = io;
    return s;
}

void ext4_session_free(ext4_session *s) {
    if (!s) return;
    close_writer(s);
    close_reader(s);
    free(s);
}

int ext4_session_reader(ext4_session *s, ext4_fs **out) {
    if (!s || !out) return -1;
    if (!s->r_open) {
        /* Down to the bootstrap view before the open, up to the real size after
         * it. Both halves matter: see ext4_session_bs_fn. */
        if (s->set_bs) s->set_bs(s->reader_ctx, 1024);
        if (ext4_open(&s->r, s->read_block, s->reader_ctx) != EXT4_OK) {
            memset(&s->r, 0, sizeof(s->r));
            return -1;
        }
        if (s->set_bs) s->set_bs(s->reader_ctx, s->r.block_size);
        s->r_open = 1;
        s->reader_opens++;
    }
    *out = &s->r;
    return 0;
}

int ext4_session_writer(ext4_session *s, uint32_t now, ext4_wfs **out) {
    if (!s || !out) return -1;
    if (s->w_poisoned) close_writer(s);
    if (!s->w_open) {
        ext4_io io = s->io;
        io.read_block  = sess_read;
        io.write_block = sess_write;
        io.flush       = sess_flush;
        io.user        = s;
        if (ext4_fs_open_io(&s->w, io) != 0) return -1;   /* the opener closes on failure */
        s->w_open = 1;
        s->writer_opens++;
    }
    s->w.now = now;
    *out = &s->w;
    return 0;
}

void ext4_session_drop(ext4_session *s) {
    if (!s) return;
    close_writer(s);
    close_reader(s);
}

void ext4_session_opens(const ext4_session *s, unsigned *reader, unsigned *writer) {
    if (reader) *reader = s ? s->reader_opens : 0;
    if (writer) *writer = s ? s->writer_opens : 0;
}
