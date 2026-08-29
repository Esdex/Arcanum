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

#include "ext4_blockcache.h"

#include <stdlib.h>
#include <string.h>

/*
 * Sixty-four entries. The hot set measured in #155 was five blocks - superblock,
 * group descriptors, the inode table block of the file in hand - so this is chosen
 * to survive a directory walk between two touches of them, not to hold a working
 * set. At a 4 KiB block that is 256 KB per mounted volume, and only for entries
 * actually used: a slot's buffer is allocated the first time it is filled.
 *
 * A linear scan of 64 is a few hundred nanoseconds against the microseconds of an
 * XTS decrypt it saves, let alone the millisecond of a USB command, so the simplest
 * structure that cannot be got wrong wins.
 */
#define CACHE_SLOTS 64
#define SLOT_EMPTY  UINT64_MAX

typedef struct {
    uint64_t off;     /* absolute byte offset, SLOT_EMPTY when unused */
    uint64_t stamp;   /* last use, for eviction */
    uint8_t *data;    /* `len` bytes, allocated on first fill */
} cache_slot;

struct ext4_blockcache {
    cache_slot slots[CACHE_SLOTS];
    uint32_t   len;
    uint64_t   clock;
};

/* Wipes and releases every buffer, leaving an empty cache of the same size. Used by
 * free and by resize, which must not let a shorter entry survive under a longer len. */
static void empty_all(ext4_blockcache *c) {
    int i;
    for (i = 0; i < CACHE_SLOTS; i++) {
        if (c->slots[i].data) {
            memset(c->slots[i].data, 0, c->len);
            free(c->slots[i].data);
            c->slots[i].data = NULL;
        }
        c->slots[i].off   = SLOT_EMPTY;
        c->slots[i].stamp = 0;
    }
}

ext4_blockcache *ext4_blockcache_new(void) {
    int i;
    ext4_blockcache *c = (ext4_blockcache *)calloc(1, sizeof(*c));
    if (!c) return NULL;
    for (i = 0; i < CACHE_SLOTS; i++) c->slots[i].off = SLOT_EMPTY;
    c->len = 0;                       /* no size until the first block is offered */
    return c;
}

void ext4_blockcache_free(ext4_blockcache *c) {
    if (!c) return;
    empty_all(c);
    free(c);
}

uint32_t ext4_blockcache_len(const ext4_blockcache *c) {
    return c ? c->len : 0;
}

/* The slot holding `off`, or NULL. Touching the stamp is what makes eviction LRU,
 * and a read counts as a use - the superblock is read far more often than written. */
static cache_slot *find_slot(ext4_blockcache *c, uint64_t off) {
    int i;
    for (i = 0; i < CACHE_SLOTS; i++)
        if (c->slots[i].off == off && c->slots[i].data)
            return &c->slots[i];
    return NULL;
}

/* Where the entry covering [off, off+len) starts, given entries of size c->len. The
 * grid is anchored at 0 and every offer is a whole number of blocks from the volume's
 * data offset, so containment is plain arithmetic. */
static uint64_t containing(const ext4_blockcache *c, uint64_t off) {
    return off - (off % c->len);
}

static void store(ext4_blockcache *c, uint64_t off, const void *src) {
    int i, victim = 0;
    cache_slot *s = find_slot(c, off);
    if (s) {                                  /* already held: overwrite in place */
        memcpy(s->data, src, c->len);
        s->stamp = ++c->clock;
        return;
    }
    /* An empty slot first, otherwise the oldest stamp. Both are found in one pass. */
    for (i = 0; i < CACHE_SLOTS; i++) {
        if (c->slots[i].off == SLOT_EMPTY && !c->slots[i].data) { victim = i; break; }
        if (c->slots[i].stamp < c->slots[victim].stamp) victim = i;
    }
    if (!c->slots[victim].data) {
        c->slots[victim].data = (uint8_t *)malloc(c->len);
        if (!c->slots[victim].data) {         /* hold less rather than fail */
            c->slots[victim].off   = SLOT_EMPTY;
            c->slots[victim].stamp = 0;
            return;
        }
    }
    memcpy(c->slots[victim].data, src, c->len);
    c->slots[victim].off   = off;
    c->slots[victim].stamp = ++c->clock;
}

/* Adopt `len` as the size held, emptying first. Only ever called for a size larger
 * than the current one, so nothing smaller can survive underneath a longer entry. */
static void adopt(ext4_blockcache *c, uint32_t len) {
    empty_all(c);
    c->len = len;
}

const void *ext4_blockcache_get(ext4_blockcache *c, uint64_t off, uint32_t len) {
    cache_slot *s;
    if (!c || len == 0 || len != c->len || off == SLOT_EMPTY) return NULL;
    s = find_slot(c, off);
    if (!s) return NULL;
    s->stamp = ++c->clock;
    return s->data;
}

void ext4_blockcache_read(ext4_blockcache *c, uint64_t off, uint32_t len, const void *src) {
    if (!c || !src || len == 0 || off == SLOT_EMPTY) return;
    if (len > c->len) adopt(c, len);
    /* A read at a smaller size than the cache holds is the 1 KiB bootstrap of the
     * superblock. Storing it is impossible without two sizes coexisting, and dropping
     * what contains it would throw away a hot entry to no purpose: a read changes
     * nothing, so what is held is still true. */
    if (len != c->len) return;
    store(c, off, src);
}

void ext4_blockcache_wrote(ext4_blockcache *c, uint64_t off, uint32_t len, const void *src) {
    if (!c || !src || len == 0 || off == SLOT_EMPTY) return;
    if (len > c->len) adopt(c, len);
    if (len == c->len) {
        store(c, off, src);
        return;
    }
    /* Smaller than what is held: these bytes sit inside an entry, which now describes
     * something the device no longer holds. Nothing currently writes at the bootstrap
     * size, and this exists so that nothing has to keep being true. */
    ext4_blockcache_drop(c, containing(c, off), c->len);
}

void ext4_blockcache_drop(ext4_blockcache *c, uint64_t off, uint32_t len) {
    int i;
    uint64_t target;
    if (!c || c->len == 0 || len == 0 || off == SLOT_EMPTY) return;
    target = (len < c->len) ? containing(c, off) : off;
    for (i = 0; i < CACHE_SLOTS; i++) {
        if (c->slots[i].off == target) {
            /* The buffer is kept for reuse but wiped: it held plaintext. */
            if (c->slots[i].data) memset(c->slots[i].data, 0, c->len);
            c->slots[i].off   = SLOT_EMPTY;
            c->slots[i].stamp = 0;
        }
    }
}

int ext4_blockcache_count(const ext4_blockcache *c) {
    int i, n = 0;
    if (!c) return 0;
    for (i = 0; i < CACHE_SLOTS; i++)
        if (c->slots[i].off != SLOT_EMPTY && c->slots[i].data) n++;
    return n;
}
