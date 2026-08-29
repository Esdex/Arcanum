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
 * Drives ext4_blockcache.c from a script on stdin, so cachecheck.py can compare its
 * answers against a model that knows nothing about eviction (#155).
 *
 * Entries are filled with a pattern derived from a 32-bit tag AND the word's position,
 * so three separate mistakes are visible in the bytes that come back rather than only
 * in a count: an entry returned for the wrong offset, an entry left holding an older
 * value, and an entry copied short. `get` verifies every word before printing, and
 * says `corrupt` instead of `hit` when they do not agree.
 *
 * Commands, one per line. Every one that moves bytes carries its own length, because
 * the mixing of two block sizes is the part that has to be got right (#155):
 *
 *   new                     a fresh cache, no size adopted yet
 *   free                    release it
 *   read <off> <len> <tag>  offer the result of a completed read
 *   wrote <off> <len> <tag> record a completed write
 *   get <off> <len>         -> "hit <tag>" | "miss" | "corrupt"
 *   drop <off> <len>        forget it
 *   count                   -> "count <n>"
 *   len                     -> "len <n>"
 */
#include "ext4_blockcache.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_LEN (1u << 20)

/* Position-dependent so a chunk landing at the wrong offset, or a copy that stops
 * early, cannot pass. A constant fill would let both through. */
static uint32_t word_at(uint32_t tag, uint32_t index) {
    return tag ^ (uint32_t)(index * 0x9E3779B9u);
}

static void fill(uint8_t *buf, uint32_t len, uint32_t tag) {
    uint32_t i;
    for (i = 0; i * 4 < len; i++) {
        uint32_t w = word_at(tag, i);
        uint32_t n = (len - i * 4) < 4 ? (len - i * 4) : 4;
        memcpy(buf + i * 4, &w, n);
    }
}

/* The tag the first word implies, then every word checked against it. Returns 0 when
 * the entry is self-consistent, -1 when it is not. */
static int decode(const uint8_t *buf, uint32_t len, uint32_t *tag_out) {
    uint32_t first = 0, i;
    if (len < 4) return -1;
    memcpy(&first, buf, 4);
    *tag_out = first;                       /* word 0 is tag ^ 0 */
    for (i = 0; i * 4 < len; i++) {
        uint32_t want = word_at(first, i), got = 0;
        uint32_t n = (len - i * 4) < 4 ? (len - i * 4) : 4;
        memcpy(&got, buf + i * 4, n);
        if (n == 4 && got != want) return -1;
    }
    return 0;
}

int main(void) {
    char line[256];
    ext4_blockcache *c = NULL;

    while (fgets(line, sizeof line, stdin)) {
        char cmd[32];
        unsigned long long off;
        unsigned long len, tag;

        if (sscanf(line, "%31s", cmd) != 1) continue;

        if (strcmp(cmd, "new") == 0) {
            ext4_blockcache_free(c);
            c = ext4_blockcache_new();
            if (!c) { printf("no-memory\n"); return 1; }
            printf("ok\n");
        } else if (strcmp(cmd, "free") == 0) {
            ext4_blockcache_free(c);
            c = NULL;
            printf("ok\n");
        } else if ((strcmp(cmd, "read") == 0 || strcmp(cmd, "wrote") == 0) &&
                   sscanf(line, "%*s %llu %lu %lu", &off, &len, &tag) == 3) {
            uint8_t *buf;
            if (len == 0 || len > MAX_LEN) { printf("bad-len\n"); continue; }
            /* Exactly `len` bytes and not one more, deliberately: a cache that copies
             * c->len out of a shorter buffer is reading off the end, and only an
             * exactly-sized allocation lets the sanitizer say so. asancheck.sh runs
             * this same stand for that reason. */
            buf = (uint8_t *)malloc(len);
            if (!buf) return 1;
            fill(buf, (uint32_t)len, (uint32_t)tag);
            if (cmd[0] == 'r') ext4_blockcache_read(c, off, (uint32_t)len, buf);
            else               ext4_blockcache_wrote(c, off, (uint32_t)len, buf);
            free(buf);
            printf("ok\n");
        } else if (strcmp(cmd, "get") == 0 &&
                   sscanf(line, "%*s %llu %lu", &off, &len) == 2) {
            const void *p = ext4_blockcache_get(c, off, (uint32_t)len);
            if (!p) {
                printf("miss\n");
            } else {
                uint32_t t = 0;
                if (decode((const uint8_t *)p, (uint32_t)len, &t) != 0) printf("corrupt\n");
                else printf("hit %lu\n", (unsigned long)t);
            }
        } else if (strcmp(cmd, "drop") == 0 &&
                   sscanf(line, "%*s %llu %lu", &off, &len) == 2) {
            ext4_blockcache_drop(c, off, (uint32_t)len);
            printf("ok\n");
        } else if (strcmp(cmd, "count") == 0) {
            printf("count %d\n", ext4_blockcache_count(c));
        } else if (strcmp(cmd, "len") == 0) {
            printf("len %lu\n", (unsigned long)ext4_blockcache_len(c));
        } else {
            printf("bad-command\n");
        }
        fflush(stdout);
    }
    ext4_blockcache_free(c);
    return 0;
}
