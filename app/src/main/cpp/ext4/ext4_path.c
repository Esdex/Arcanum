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

/*
 * Resolving a path to an inode. See the header.
 *
 * Clean-room, from the published on-disk format. The whole of it is a loop over
 * the slash-separated components of the path, each one a single-name lookup in the
 * directory the last one resolved to. The only subtlety is the two ways a walk can
 * fail that read the same to a careless caller and are not the same: a component
 * that is not there (ENOENT) and a component that is there but is a file being used
 * as though it were a directory (ENOTDIR). They are kept apart because the thing a
 * caller does about each is different.
 */
#define _POSIX_C_SOURCE 200809L

#include "ext4_path.h"
#include "ext4_dirwrite.h"   /* ext4_dir_lookup */
#include "ext4_log.h"

#include <stdlib.h>
#include <string.h>

#define INODE_MODE_OFF 0x00
#define EXT4_S_IFMT    0xF000
#define EXT4_S_IFDIR   0x4000
#define EXT4_S_IFLNK   0xA000

/*
 * How far a symlink may be followed (#163).
 *
 * Both numbers are copied from Linux rather than invented, and that is the reason
 * for them: a volume written on a desktop was built under those rules, so anything
 * they allow has to work here, and anything they refuse is already known not to
 * work anywhere. 40 bounds the whole resolution, which is what stops a ring of
 * links from spinning forever. 8 bounds how deeply one expansion may sit inside
 * another, which is what bounds this file's stack, since every level holds an
 * inode buffer and a component buffer of its own.
 */
#define EXT4_FOLLOW_TOTAL 40
#define EXT4_FOLLOW_DEPTH  8

typedef struct { int total; int depth; } follow_state;

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }

/* Whether `ino` is a directory. Sets *err on a read failure so the caller can tell
 * "not a directory" from "could not find out". */
static int inode_is_dir(const ext4_fs *r, uint32_t ino, int *err) {
    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) {
        *err = 1;
        return 0;
    }
    *err = 0;
    return (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR;
}

/*
 * Advances past one component of `*cursor`, copying it into `comp` and leaving the
 * cursor on the start of the next. Empty components - the ones repeated, leading
 * and trailing slashes produce - are skipped, so "//a//b/" yields "a" then "b"
 * then nothing.
 *
 * Returns 1 when a component was produced, 0 at the end of the path, and
 * EXT4_PATH_ENAMETOOLONG when a component would not fit a directory entry.
 */
static int next_component(const char **cursor, char comp[256], size_t cap) {
    const char *p = *cursor;
    while (*p == '/') p++;
    if (*p == '\0') { *cursor = p; return 0; }

    const char *start = p;
    while (*p != '/' && *p != '\0') p++;
    size_t len = (size_t)(p - start);
    if (len >= cap) return EXT4_PATH_ENAMETOOLONG;

    memcpy(comp, start, len);
    comp[len] = '\0';
    *cursor = p;
    return 1;
}

static int lookup_rc_to_path_rc(int rc) {
    switch (rc) {
    case EXT4_DIRW_OK:         return EXT4_PATH_OK;
    case EXT4_DIRW_ERR_ABSENT: return EXT4_PATH_ENOENT;
    case EXT4_DIRW_ERR_NAME:   return EXT4_PATH_ENAMETOOLONG;
    default:                   return EXT4_PATH_EIO;
    }
}

/*
 * The shared walk. Resolves every component up to but not including the last
 * `stop_short` of them, starting at the root, and leaves the cursor positioned so
 * the caller can deal with whatever remains: nothing (resolve_path consumed it all)
 * or the final component (resolve_parent left it).
 */
static int walk_from(const ext4_fs *r, uint32_t start, const char *path,
                     int stop_short, int follow_final, follow_state *fs_state,
                     uint32_t *ino_out, const char **tail_out);

/*
 * Replaces a symlink with whatever it names, again and again until it names
 * something that is not one.
 *
 * `dir` is the directory the link was found in, because a target that does not
 * begin with a slash is relative to that and not to where the resolution started.
 * An absolute target starts again at the root - and the root of the volume, which
 * is the whole reason a link written on a desktop as "/home/user/x" is dead here
 * rather than dangerous: there is no way for it to name anything outside.
 */
static int follow_links(const ext4_fs *r, uint32_t dir, uint32_t *ino,
                        follow_state *fs_state) {
    for (;;) {
        uint8_t inode[EXT4_MAX_INODE_SIZE];
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(r, *ino, inode, sizeof(inode)) != EXT4_OK)
            return EXT4_PATH_EIO;
        if ((rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) != EXT4_S_IFLNK)
            return EXT4_PATH_OK;

        if (fs_state->total <= 0 || fs_state->depth <= 0) return EXT4_PATH_ELOOP;
        fs_state->total--;

        uint64_t len = ext4_inode_size(inode);
        if (len == 0 || len > EXT4_PATH_MAX) return EXT4_PATH_ENAMETOOLONG;
        char *target = (char *)malloc((size_t)len + 1);
        if (!target) return EXT4_PATH_EIO;
        if (ext4_readlink(r, inode, target, (size_t)len + 1) < 0) {
            free(target);
            return EXT4_PATH_EIO;
        }

        uint32_t base = (target[0] == '/') ? EXT4_ROOT_INO : dir;
        uint32_t resolved = 0;
        const char *tail = NULL;
        /* The target's own last component is left unfollowed on purpose: the loop
         * above takes it, so a chain of links is spent out of one budget rather
         * than each expansion starting a fresh one. */
        fs_state->depth--;
        int rc = walk_from(r, base, target, 0, 0, fs_state, &resolved, &tail);
        fs_state->depth++;
        free(target);
        if (rc != EXT4_PATH_OK) return rc;
        *ino = resolved;
    }
}

static int walk_from(const ext4_fs *r, uint32_t start, const char *path,
                     int stop_short, int follow_final, follow_state *fs_state,
                     uint32_t *ino_out, const char **tail_out) {
    /* First count the components, so "resolve all but the last" knows where the
     * last one is without a second parser that could disagree with the first. */
    uint32_t total = 0;
    const char *p = path;
    char comp[256];
    for (;;) {
        int rc = next_component(&p, comp, sizeof(comp));
        if (rc == EXT4_PATH_ENAMETOOLONG) return rc;
        if (rc == 0) break;
        total++;
    }

    uint32_t resolve = (total >= (uint32_t)stop_short) ? total - (uint32_t)stop_short : 0;

    uint32_t ino = start;
    const char *cursor = path;
    for (uint32_t i = 0; i < resolve; i++) {
        int rc = next_component(&cursor, comp, sizeof(comp));
        if (rc <= 0) return EXT4_PATH_EIO;   /* counted more than there are: impossible */

        /* Every component along the way has to be a directory. Checking it before
         * the lookup is what turns "used a file as a directory" into ENOTDIR
         * instead of the lookup inside it failing as ENOENT. */
        int err = 0;
        if (!inode_is_dir(r, ino, &err))
            return err ? EXT4_PATH_EIO : EXT4_PATH_ENOTDIR;

        uint32_t child = 0;
        int lrc = ext4_dir_lookup(r, ino, comp, &child);
        if (lrc != EXT4_DIRW_OK) return lookup_rc_to_path_rc(lrc);

        /* Every component a path goes THROUGH is followed, whatever the caller
         * asked for: "/link/file.txt" has to reach into the directory the link
         * names, or a link to a directory is a directory nothing can be done with.
         * Only the component the path ENDS on is the caller's choice, and only
         * when this walk is the one that resolves it. */
        int last = (i + 1 == resolve) && stop_short == 0;
        if (!last || follow_final) {
            int drc = follow_links(r, ino, &child, fs_state);
            if (drc != EXT4_PATH_OK) return drc;
        }
        ino = child;
    }

    *ino_out  = ino;
    *tail_out = cursor;
    return EXT4_PATH_OK;
}

static int resolve_with(const ext4_fs *r, const char *path, int follow_final,
                        uint32_t *ino_out, int *is_dir_out) {
    if (!path) return EXT4_PATH_EINVAL;

    follow_state fs_state = { EXT4_FOLLOW_TOTAL, EXT4_FOLLOW_DEPTH };
    uint32_t ino = 0;
    const char *tail = NULL;
    int rc = walk_from(r, EXT4_ROOT_INO, path, 0, follow_final, &fs_state,
                       &ino, &tail);
    if (rc != EXT4_PATH_OK) {
        /*
         * Only an unreadable volume is an error here. The rest are answers: a
         * name that is not there, a name used as a directory, a ring of links.
         * Since a listing now resolves every symlink it meets (#163), a folder
         * holding four dead links from a desktop wrote sixty-four error lines in
         * one browsing session - and these logs are what a crash report carries,
         * so filling them with expected outcomes is how a real fault gets lost.
         */
        if (rc == EXT4_PATH_EIO)
            EXT4_LOGE("resolve '%s': the volume could not be read", path);
        else
            EXT4_LOGD("resolve '%s': no (%d)", path, rc);
        return rc;
    }

    if (is_dir_out) {
        int err = 0;
        *is_dir_out = inode_is_dir(r, ino, &err);
        if (err) return EXT4_PATH_EIO;
    }
    if (ino_out) *ino_out = ino;
    EXT4_LOGD("resolve '%s' -> inode %u", path, ino);
    return EXT4_PATH_OK;
}

int ext4_resolve_path(const ext4_fs *r, const char *path,
                      uint32_t *ino_out, int *is_dir_out) {
    return resolve_with(r, path, 1, ino_out, is_dir_out);
}

int ext4_resolve_path_nofollow(const ext4_fs *r, const char *path,
                               uint32_t *ino_out, int *is_dir_out) {
    return resolve_with(r, path, 0, ino_out, is_dir_out);
}

int ext4_resolve_parent(const ext4_fs *r, const char *path,
                        uint32_t *parent_out, char *name_out, size_t name_cap) {
    if (!path || !name_out || name_cap == 0) return EXT4_PATH_EINVAL;

    follow_state fs_state = { EXT4_FOLLOW_TOTAL, EXT4_FOLLOW_DEPTH };
    uint32_t parent = 0;
    const char *tail = NULL;
    int rc = walk_from(r, EXT4_ROOT_INO, path, 1, 0, &fs_state, &parent, &tail);
    if (rc != EXT4_PATH_OK) return rc;

    /* Whatever the walk stopped short of is the final component. There may be none
     * of it - the path was the root, which has no parent to create anything in. */
    char comp[256];
    int have = next_component(&tail, comp, sizeof(comp));
    if (have == EXT4_PATH_ENAMETOOLONG) return have;
    if (have == 0) return EXT4_PATH_EINVAL;

    if (strlen(comp) >= name_cap) return EXT4_PATH_ENAMETOOLONG;

    /* The directory the last component goes in has to actually be a directory. */
    int err = 0;
    if (!inode_is_dir(r, parent, &err))
        return err ? EXT4_PATH_EIO : EXT4_PATH_ENOTDIR;

    memcpy(name_out, comp, strlen(comp) + 1);
    if (parent_out) *parent_out = parent;
    EXT4_LOGD("resolve parent of '%s' -> dir %u name '%s'", path, parent, name_out);
    return EXT4_PATH_OK;
}
