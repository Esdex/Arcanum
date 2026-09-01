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
 * Creating and deleting a file: joining the inode allocator, the extent writer
 * and the directory writer, in the one order that survives being interrupted.
 * See the .c - the order is the substance of this layer, not an implementation
 * detail of it.
 */
#ifndef ARCANUM_EXT4_CREATE_H
#define ARCANUM_EXT4_CREATE_H

#include "ext4_alloc.h"
#include "ext4_dir.h"
#include "ext4_dirwrite.h"
#include "ext4_extents.h"
#include "ext4_extwrite.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_CREATE_ERR_NOINODE  -7
#define EXT4_CREATE_ERR_NOTDIR   -9   /* the name is there but is not a directory */
#define EXT4_CREATE_ERR_NOTEMPTY -10  /* it still holds something other than . and .. */
#define EXT4_CREATE_ERR_LOOP     -11  /* a directory would be moved inside itself */
#define EXT4_CREATE_ERR_ISDIR    -12  /* unlink was handed a directory - that is rmdir's */
#define EXT4_CREATE_ERR_MLINK    -13  /* the inode already has as many names as ext4 allows */

/* ext4 stops a link count at 65000 rather than at what the 16-bit field holds,
 * leaving the values above it free to mean something else. Matched here so a
 * volume this driver writes cannot hold a count a desktop would refuse. */
#define EXT4_LINK_MAX 65000

/* The longest symlink target accepted. Linux's PATH_MAX, since a longer one could
 * not have been written on a desktop and could not be followed here either. */
#define EXT4_SYMLINK_TARGET_MAX 4096

/*
 * Makes an empty regular file called `name` in `dir_ino` and reports its inode.
 *
 * `when` is stored in all three timestamps. Passed in rather than read from the
 * clock so that the same inputs give the same image, which is what lets a test
 * compare one byte for byte.
 */
int ext4_create_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint16_t mode, uint32_t when,
                     uint32_t *ino_out);

/*
 * Removes `name`. When it was the last name the file had, its blocks and its
 * inode go back too and `when` is recorded as its deletion time; when it was not,
 * only the link count moves.
 */
int ext4_unlink_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint32_t when);

/*
 * Makes an empty directory called `name` in `dir_ino` and reports its inode.
 *
 * A directory is not a file with a different mode bit. Three things exist here
 * that creating a file has no equivalent of, and each is checked by e2fsck
 * separately:
 *
 *   the block     a new directory is never empty on disk - it holds "." and ".."
 *                 from the moment it exists, so one block is allocated and
 *                 formatted as part of creating it
 *   the links     it starts with two, its own name and its own ".", and the
 *                 parent gains one because the new ".." points back at it
 *   the count     bg_used_dirs_count in the group descriptor, which nothing else
 *                 in this library moves
 */
int ext4_mkdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint16_t mode, uint32_t when,
               uint32_t *ino_out);

/*
 * Removes an empty directory, undoing all three.
 *
 * Refuses a name that is not a directory, and one that still holds anything other
 * than "." and "..". Emptiness is not a courtesy check: the entries inside a
 * directory are only reachable through it, so removing a populated one strands
 * every inode below it with no name left to find it by.
 */
int ext4_rmdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint32_t when);

/*
 * Moves `src_name` in `src_parent` to `dst_name` in `dst_parent` - a rename in one
 * directory, a move between two, or both at once. Works for a file or a directory.
 *
 * Like create and unlink, the order is the substance: the new name is added before
 * the old one is removed, so the thing being moved is reachable by at least one
 * name at every moment a crash could stop it - never by neither. What survives a
 * crash between the two is an extra name, which e2fsck reconciles, not a lost inode.
 *
 * The source inode's own link count never changes - it has exactly one name before
 * and after. What does move, and only when a directory crosses to a new parent, is
 * three things nothing about the entry implies: its ".." is repointed at the new
 * parent, the new parent gains the link that ".." is, and the old parent loses it.
 *
 * Refused rather than approximated:
 *   dst exists      replacing a destination is a separate operation with its own
 *                   rules and its own crash window that loses the replaced thing;
 *                   EXT4_DIRW_ERR_EXISTS
 *   into itself     a directory moved inside its own subtree becomes a cycle cut
 *                   off from the root; EXT4_CREATE_ERR_LOOP
 * A source and destination that name the same thing is success with nothing done.
 *
 * No timestamp is taken: a rename moves references, it does not birth or kill an
 * inode, so unlike create/unlink/mkdir/rmdir there is no dtime to set. The moved
 * inode and the two directories keep the times they had - a deliberate minimum,
 * since no check here verifies a timestamp and unverified writes are not added.
 */
/*
 * Creates a symlink `name` in `dir_ino` holding the path `target` (#128).
 *
 * The target is stored exactly as given: a path with no leading slash is measured
 * from the directory the link sits in, one with a slash from the root of the volume.
 * Nothing here interprets it, and nothing checks that it names anything - a link to
 * a name that does not exist yet is ordinary, and one that never will is how a
 * desktop's absolute paths arrive.
 *
 * EXT4_DIRW_ERR_NAME for an empty target or one past EXT4_SYMLINK_TARGET_MAX,
 * EXT4_DIRW_ERR_EXISTS if the name is taken, EXT4_CREATE_ERR_NOINODE if the volume
 * has no inode left.
 */
int ext4_symlink(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                 const char *name, const char *target, uint32_t when,
                 uint32_t *ino_out);

/*
 * Gives `target_ino` a second name in `dir_ino` - a hard link (#128).
 *
 * No new inode and no new blocks: the same file under another name, and the data
 * goes only when the last name is removed. EXT4_CREATE_ERR_ISDIR for a directory,
 * which cannot have one, and EXT4_CREATE_ERR_MLINK when it already has as many
 * names as ext4 permits.
 */
int ext4_hardlink(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                  const char *name, uint32_t target_ino, uint32_t when);

int ext4_rename(ext4_wfs *w, const ext4_fs *r,
                uint32_t src_parent, const char *src_name,
                uint32_t dst_parent, const char *dst_name);

#ifdef __cplusplus
}
#endif
#endif
