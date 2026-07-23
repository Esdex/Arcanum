/*
 * Resolving a path to an inode.
 *
 * Everything else in this directory is addressed by inode number: the directory
 * writer takes a parent inode, the reader takes an inode, ext4_dir_lookup finds
 * one name in one directory. The world above - a file browser, a JNI caller -
 * speaks in paths: "/photos/2026/img.jpg". This walks the one into the other, a
 * component at a time, and it is the piece the whole callable surface sits on.
 *
 * Read-only, built on the reader alone. It changes nothing; it only follows the
 * directory entries that are already there.
 */
#ifndef ARCANUM_EXT4_PATH_H
#define ARCANUM_EXT4_PATH_H

#include "ext4_extents.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT4_PATH_OK            0
#define EXT4_PATH_ENOENT       -1   /* a component does not exist */
#define EXT4_PATH_ENOTDIR      -2   /* a component along the way is not a directory */
#define EXT4_PATH_ENAMETOOLONG -3   /* a component is longer than a name may be */
#define EXT4_PATH_EINVAL       -4   /* the path has no final component (it is the root) */
#define EXT4_PATH_EIO          -5   /* the filesystem could not be read */

#define EXT4_ROOT_INO 2

/*
 * Resolves an absolute path to the inode it names.
 *
 * A leading slash is optional and repeated or trailing slashes are ignored, so
 * "", "/", "//" and "/." all resolve to the root. Every component except the last
 * has to be a directory - a name used as a directory that is not one is
 * EXT4_PATH_ENOTDIR, which is a different failure from the name simply being
 * absent, because the fix is different. "." and ".." need no special handling:
 * they are real entries and resolve through the ordinary lookup.
 *
 * On success `*ino_out` is the inode, and `*is_dir_out` (may be NULL) says whether
 * it is a directory - the one bit of the resolved inode a caller almost always
 * needs next, saved a second read.
 */
int ext4_resolve_path(const ext4_fs *r, const char *path,
                      uint32_t *ino_out, int *is_dir_out);

/*
 * Resolves the directory that would contain `path`, and copies out the final
 * component's name.
 *
 * For "/a/b/c" this yields the inode of "/a/b" and the name "c". It is what
 * create, mkdir, unlink and rmdir need: they act on a parent inode and a name,
 * and the final component may not exist yet, so it is deliberately not looked up -
 * only the directory that would hold it is resolved and required to be one.
 *
 * The root has no parent and no final component, so a path that resolves to it is
 * EXT4_PATH_EINVAL: there is nothing to create or remove there.
 */
int ext4_resolve_parent(const ext4_fs *r, const char *path,
                        uint32_t *parent_out, char *name_out, size_t name_cap);

#ifdef __cplusplus
}
#endif
#endif
