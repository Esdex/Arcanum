/*
 * Driver for the block allocator, so fsckcheck.py has something to run.
 *
 *   alloc <image> alloc <count>     takes count blocks, prints one per line
 *   alloc <image> fill              takes blocks until there are none left
 *   alloc <image> ialloc <count>    takes count inodes, prints one per line
 *   alloc <image> ifill             takes inodes until there are none left
 *   alloc <image> ifree <inode>...  gives the listed inodes back
 *   alloc <image> ifree -           the same, reading inode numbers from stdin
 *   alloc <image> free <block>...   gives the listed blocks back
 *   alloc <image> free -            the same, reading block numbers from stdin
 *
 * Allocation is all-or-nothing: if the image runs out part way, what was taken is
 * released again before exiting non-zero, so a failed run does not leave blocks
 * stranded and the image is still worth inspecting.
 *
 * `fill` exists for the harness rather than for its own sake. Taking a handful of
 * blocks never reaches a group flagged BLOCK_UNINIT, so the rule that those are
 * left alone is unexercised until the groups in front of them are full. Reading
 * the list back from stdin is for the same reason - a filled image yields tens of
 * thousands of block numbers, which is no longer a sensible argument list.
 */
#define _POSIX_C_SOURCE 200809L

#include "ext4_alloc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int usage(const char *me) {
    fprintf(stderr, "usage: %s <image> alloc <count>\n"
                    "       %s <image> fill\n"
                    "       %s <image> free <block>... | -\n"
                    "       %s <image> ialloc <count>\n"
                    "       %s <image> ifree <inode>...\n", me, me, me, me, me);
    return 2;
}

static int do_alloc(ext4_wfs *fs, long count) {
    int64_t *taken = calloc((size_t)count, sizeof(*taken));
    if (!taken) return 2;

    long n = 0;
    for (; n < count; n++) {
        taken[n] = ext4_alloc_block(fs);
        if (taken[n] < 0) {
            fprintf(stderr, "allocation failed after %ld of %ld blocks\n", n, count);
            for (long i = 0; i < n; i++) ext4_free_block(fs, (uint64_t)taken[i]);
            free(taken);
            return 1;
        }
    }
    if (ext4_fs_flush(fs)) { perror("flush"); free(taken); return 1; }
    for (long i = 0; i < n; i++) printf("%lld\n", (long long)taken[i]);
    free(taken);
    return 0;
}

/* Takes everything it can reach, which is every group whose bitmap exists. Stops
 * when ext4_alloc_block refuses, leaving the blocks inside BLOCK_UNINIT groups
 * untouched and still counted as free in the superblock. */
static int do_fill(ext4_wfs *fs) {
    size_t cap = 4096, n = 0;
    int64_t *taken = malloc(cap * sizeof(*taken));
    if (!taken) return 2;

    for (;;) {
        int64_t b = ext4_alloc_block(fs);
        if (b < 0) break;
        if (n == cap) {
            size_t ncap = cap * 2;
            int64_t *grown = realloc(taken, ncap * sizeof(*taken));
            if (!grown) { free(taken); return 2; }
            taken = grown; cap = ncap;
        }
        taken[n++] = b;
    }
    if (ext4_fs_flush(fs)) { perror("flush"); free(taken); return 1; }
    for (size_t i = 0; i < n; i++) printf("%lld\n", (long long)taken[i]);
    free(taken);
    return 0;
}

static int do_ialloc(ext4_wfs *fs, long count) {
    int64_t *taken = calloc((size_t)(count ? count : 1), sizeof(*taken));
    if (!taken) return 2;

    long n = 0;
    for (; n < count; n++) {
        taken[n] = ext4_alloc_inode(fs);
        if (taken[n] < 0) {
            fprintf(stderr, "inode allocation failed after %ld of %ld\n", n, count);
            for (long i = 0; i < n; i++) ext4_free_inode(fs, (uint32_t)taken[i]);
            free(taken);
            return 1;
        }
    }
    if (ext4_fs_flush(fs)) { perror("flush"); free(taken); return 1; }
    for (long i = 0; i < n; i++) printf("%lld\n", (long long)taken[i]);
    free(taken);
    return 0;
}

/* Takes every inode it can reach, which is every group whose bitmap exists.
 * Stops when ext4_alloc_inode refuses, leaving the inodes inside INODE_UNINIT
 * groups untouched and still counted as free in the superblock. */
static int do_ifill(ext4_wfs *fs) {
    size_t cap = 4096, n = 0;
    int64_t *taken = malloc(cap * sizeof(*taken));
    if (!taken) return 2;

    for (;;) {
        int64_t v = ext4_alloc_inode(fs);
        if (v < 0) break;
        if (n == cap) {
            size_t ncap = cap * 2;
            int64_t *grown = realloc(taken, ncap * sizeof(*taken));
            if (!grown) { free(taken); return 2; }
            taken = grown; cap = ncap;
        }
        taken[n++] = v;
    }
    if (ext4_fs_flush(fs)) { perror("flush"); free(taken); return 1; }
    for (size_t i = 0; i < n; i++) printf("%lld\n", (long long)taken[i]);
    free(taken);
    return 0;
}

static int ifree_one(ext4_wfs *fs, const char *tok) {
    char *end;
    unsigned long long v = strtoull(tok, &end, 10);
    if (*end && *end != '\n') {
        fprintf(stderr, "not an inode number: %s\n", tok);
        return 2;
    }
    if (ext4_free_inode(fs, (uint32_t)v)) {
        fprintf(stderr, "cannot free inode %llu\n", v);
        return 1;
    }
    return 0;
}

static int do_ifree(ext4_wfs *fs, int argc, char **argv) {
    int rc;
    if (argc == 1 && !strcmp(argv[0], "-")) {
        char line[64];
        while (fgets(line, sizeof(line), stdin))
            if ((rc = ifree_one(fs, line))) return rc;
    } else {
        for (int i = 0; i < argc; i++)
            if ((rc = ifree_one(fs, argv[i]))) return rc;
    }
    if (ext4_fs_flush(fs)) { perror("flush"); return 1; }
    return 0;
}

static int free_one(ext4_wfs *fs, const char *tok) {
    char *end;
    unsigned long long b = strtoull(tok, &end, 10);
    if (*end && *end != '\n') {
        fprintf(stderr, "not a block number: %s\n", tok);
        return 2;
    }
    if (ext4_free_block(fs, b)) {
        fprintf(stderr, "cannot free block %llu\n", b);
        return 1;
    }
    return 0;
}

static int do_free(ext4_wfs *fs, int argc, char **argv) {
    int rc;
    if (argc == 1 && !strcmp(argv[0], "-")) {
        char line[64];
        while (fgets(line, sizeof(line), stdin))
            if ((rc = free_one(fs, line))) return rc;
    } else {
        for (int i = 0; i < argc; i++)
            if ((rc = free_one(fs, argv[i]))) return rc;
    }
    if (ext4_fs_flush(fs)) { perror("flush"); return 1; }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) return usage(argv[0]);

    ext4_wfs fs;
    if (ext4_fs_open(&fs, argv[1])) {
        fprintf(stderr, "cannot open %s as an ext4 image\n", argv[1]);
        return 2;
    }

    int rc;
    if (!strcmp(argv[2], "alloc")) {
        if (argc != 4) { ext4_fs_close(&fs); return usage(argv[0]); }
        char *end;
        long count = strtol(argv[3], &end, 10);
        if (*end || count < 0) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_alloc(&fs, count);
    } else if (!strcmp(argv[2], "fill")) {
        if (argc != 3) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_fill(&fs);
    } else if (!strcmp(argv[2], "ialloc")) {
        if (argc != 4) { ext4_fs_close(&fs); return usage(argv[0]); }
        char *end;
        long count = strtol(argv[3], &end, 10);
        if (*end || count < 0) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_ialloc(&fs, count);
    } else if (!strcmp(argv[2], "ifill")) {
        if (argc != 3) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_ifill(&fs);
    } else if (!strcmp(argv[2], "ifree")) {
        if (argc < 4) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_ifree(&fs, argc - 3, argv + 3);
    } else if (!strcmp(argv[2], "free")) {
        if (argc < 4) { ext4_fs_close(&fs); return usage(argv[0]); }
        rc = do_free(&fs, argc - 3, argv + 3);
    } else {
        ext4_fs_close(&fs);
        return usage(argv[0]);
    }

    ext4_fs_close(&fs);
    return rc;
}
