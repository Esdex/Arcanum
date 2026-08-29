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
 * Creating and deleting a file.
 *
 * Clean-room, from the published on-disk format. Nothing new is read or written
 * here - every part has its own file and its own checks. What this adds is the
 * order, and the order is the whole of it.
 *
 * Creating runs inode-first: allocate it, fill it in completely, set its link
 * count to one, and only then let a name point at it. The other order is
 * tempting - the name is what the caller asked for - and it is wrong. Between the
 * two writes there is a moment that survives a crash, and it has to be a moment
 * the filesystem can be left in:
 *
 *   inode first  an inode nobody names. e2fsck calls it unattached, moves it to
 *                lost+found, and one inode has leaked. Nothing is lost.
 *   name first   a name pointing at an inode that is not filled in yet - mode
 *                zero, link count zero, no extent root. That is a directory
 *                entry to a file that cannot be opened and, once the link count
 *                is zero with a name still referring to it, one e2fsck has to
 *                repair rather than tidy.
 *
 * Deleting runs the other way round for the same reason: the name goes first, so
 * the worst crash leaves an inode with no name - the recoverable side again -
 * rather than a name pointing at freed blocks.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_create.h"
#include "ext4_csum.h"
#include "ext4_log.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define INODE_MODE_OFF        0x00
#define INODE_SIZE_LO_OFF     0x04
#define INODE_ATIME_OFF       0x08
#define INODE_CTIME_OFF       0x0C
#define INODE_MTIME_OFF       0x10
#define INODE_DTIME_OFF       0x14
#define INODE_LINKS_COUNT_OFF 0x1A
#define INODE_BLOCKS_LO_OFF   0x1C
#define INODE_FLAGS_OFF       0x20
#define INODE_IBLOCK_OFF      0x28
#define INODE_GENERATION_OFF  0x64
#define INODE_SIZE_HI_OFF     0x6C
#define INODE_EXTRA_ISIZE_OFF 0x80
#define INODE_CRTIME_OFF      0x90

#define EXT4_INODE_FLAG_EXTENTS 0x00080000u
#define EXT4_EXTENT_MAGIC       0xF30A
#define INODE_FILE_ACL_OFF    0x68   /* i_file_acl_lo: the external xattr block */
#define INODE_FILE_ACL_HI_OFF 0x76   /* osd2.linux2.l_i_file_acl_high */

#define EXT4_S_IFREG            0x8000
#define EXT4_S_IFDIR            0x4000
#define EXT4_S_IFMT             0xF000
/* Kinds this driver never creates but will meet on a volume made elsewhere. They
 * matter here because none of them owns data blocks the way a file does. */
#define EXT4_S_IFLNK            0xA000
#define EXT4_S_IFCHR            0x2000
#define EXT4_S_IFBLK            0x6000
#define EXT4_S_IFIFO            0x1000
#define EXT4_S_IFSOCK           0xC000

/* An external extended-attribute block starts with this, and carries a count of
 * the inodes sharing it. Observed on a block libext2fs wrote, not taken from
 * anyone's source - see the clean-room note at the top of this file. */
#define EXT4_XATTR_MAGIC        0xEA020000u
#define XATTR_REFCOUNT_OFF      0x04
#define EXT4_GOOD_EXTRA_ISIZE   32
#define DIR_TAIL_SIZE           12

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;         p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

/*
 * A newly allocated inode arrives zeroed, which is not the same as empty. Zero is
 * not a valid mode, and an all-zero i_block is not an empty extent tree - it is a
 * tree with no magic number, which every reader rejects. So the fields that make
 * it a file rather than an absence are written explicitly, and the ones deliberately
 * left zero are listed here so that a later reader knows they were considered.
 *
 * `mode` carries the format bits, and `links` the count the caller's own structure
 * implies: one for a file, which the name about to be added accounts for, and two
 * for a directory, whose own "." is a link to itself. Passing both in keeps this
 * the single place that knows what an inode has to contain, whatever kind it is.
 * The deliberately-zero fields:
 *
 *   i_dtime      zero means "not deleted"; a nonzero one on a linked inode is
 *                the contradiction e2fsck reports first
 *   i_uid/i_gid  zero (root). The app reads and writes raw ext4 and ignores
 *                ownership, but a desktop mounts the decrypted container as an
 *                ordinary user, to whom a root-owned inode is only as accessible
 *                as its "other" bits allow - which is why the callers widen the
 *                mode to world rw (files) / rwx (dirs) rather than leaving the
 *                classic 0644/0755. The owner cannot be set to the desktop user
 *                because that uid is unknown when the file is written, and this
 *                mirrors FAT/exFAT, which store no ownership at all.
 *   i_blocks     zero, no blocks yet
 *   i_generation zero, matching what mke2fs writes for its own files
 */
static void init_inode(uint8_t *inode, uint32_t inode_size,
                       uint16_t mode, uint16_t links, uint32_t when) {
    memset(inode, 0, inode_size);

    wr16(inode + INODE_MODE_OFF, mode);
    wr16(inode + INODE_LINKS_COUNT_OFF, links);
    wr32(inode + INODE_FLAGS_OFF, EXT4_INODE_FLAG_EXTENTS);
    wr32(inode + INODE_SIZE_LO_OFF, 0);
    wr32(inode + INODE_SIZE_HI_OFF, 0);
    wr32(inode + INODE_BLOCKS_LO_OFF, 0);
    wr32(inode + INODE_ATIME_OFF, when);
    wr32(inode + INODE_CTIME_OFF, when);
    wr32(inode + INODE_MTIME_OFF, when);
    wr32(inode + INODE_DTIME_OFF, 0);
    wr32(inode + INODE_GENERATION_OFF, 0);

    /* An empty extent tree still needs its header: magic, no entries, room for
     * the four the inode's 60 bytes hold, depth zero. */
    uint8_t *root = inode + INODE_IBLOCK_OFF;
    wr16(root, EXT4_EXTENT_MAGIC);
    wr16(root + 2, 0);
    wr16(root + 4, 4);
    wr16(root + 6, 0);

    /* Decides whether i_checksum_hi exists, so it has to be set before the
     * checksum is computed rather than after.
     *
     * i_crtime lives in the same extra area, and declaring the area present while
     * leaving the field zero is worse than not declaring it at all: a desktop reads it
     * as a real answer and says every file in the vault was created on 1 January 1970.
     * Found on 2026-08-29 by looking at an imported folder on a Linux machine. It is the
     * time the inode came into being, which is now - not the date the imported file
     * carried, which is its modification time and is put back separately (#154). */
    if (inode_size > 128) {
        wr16(inode + INODE_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);
        wr32(inode + INODE_CRTIME_OFF, when);
    }
}

int ext4_create_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint16_t mode, uint32_t when,
                     uint32_t *ino_out) {
    EXT4_LOGI("create '%s' in dir inode %u", name, dir_ino);

    /* Checked before an inode is taken. Finding the clash afterwards would mean
     * handing one back, and a rollback that is never needed cannot be wrong. */
    uint32_t existing = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &existing);
    if (rc == EXT4_DIRW_OK) {
        EXT4_LOGE("create '%s': a name already points at inode %u", name, existing);
        return EXT4_DIRW_ERR_EXISTS;
    }
    if (rc != EXT4_DIRW_ERR_ABSENT) {
        EXT4_LOGE("create '%s': lookup failed (%d)", name, rc);
        return rc;
    }

    int64_t ino = ext4_alloc_inode(w);
    if (ino < 0) {
        EXT4_LOGE("create '%s': no free inode", name);
        return EXT4_CREATE_ERR_NOINODE;
    }

    uint8_t *inode = malloc(w->inode_size);
    if (!inode) { ext4_free_inode(w, (uint32_t)ino); return EXT4_DIRW_ERR_IO; }
    /* Widened to world rw: the inode owner is root and a desktop mounts the
     * decrypted container as an ordinary user, who would see a root-owned 0644
     * file as read-only (the lock in a file manager). See init_inode. */
    init_inode(inode, w->inode_size, (uint16_t)(EXT4_S_IFREG | (mode & 0x0FFF) | 0666),
               1, when);

    rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);
    free(inode);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("create '%s': writing inode %lld failed (%d), freeing it",
                  name, (long long)ino, rc);
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    /* The inode is complete and claims one link, so the name it is about to get
     * is already accounted for. Only now does anything point at it. */
    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_REG_FILE, name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("create '%s': adding the directory entry failed (%d), freeing "
                  "inode %lld", name, rc, (long long)ino);
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    if (ino_out) *ino_out = (uint32_t)ino;
    rc = ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
    EXT4_LOGI("create '%s': inode %lld, %s", name, (long long)ino,
              rc == EXT4_DIRW_OK ? "ok" : "flush failed");
    return rc;
}

/*
 * Whether this inode's data lives in blocks `ext4_truncate_blocks` can release.
 *
 * This driver only ever creates regular files and directories, so for a long time
 * unlink simply truncated whatever it was handed. A volume made elsewhere holds
 * more than that, and none of the rest owns data blocks: a device node keeps its
 * major and minor inside i_block, a FIFO and a socket keep nothing there, and a
 * short symlink keeps its target there. Truncating one of those fails, because
 * i_block holds no extent header - and by then unlink has already taken the name
 * out of the directory, so the entry is gone and the inode is stranded. e2fsck
 * called it "Unattached inode 13", which is one leaked inode per symlink deleted
 * on any tree that came from a Linux desktop (#147).
 *
 * The extent flag is what separates a short symlink from a long one, and it is
 * what the kernel uses for the same purpose.
 */
/*
 * The directory-entry type byte for an inode.
 *
 * A directory entry carries the kind of thing it names, so that a listing does not
 * have to read every inode. Rename used to choose between "directory" and "regular
 * file" and nothing else, which is true of everything this driver creates and not
 * of everything it can be asked to move: moving a symlink relabelled it as a file,
 * and e2fsck said so - "Entry 'moved.lnk' in /sub has an incorrect filetype (was 1,
 * should be 7)". The entry and the inode disagreed about what the thing was, on a
 * volume that was clean until we touched it (#147).
 */
static uint8_t ftype_of(const uint8_t *inode) {
    switch (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) {
    case EXT4_S_IFDIR:  return EXT4_FT_DIR;
    case EXT4_S_IFLNK:  return EXT4_FT_SYMLINK;
    case EXT4_S_IFCHR:  return EXT4_FT_CHRDEV;
    case EXT4_S_IFBLK:  return EXT4_FT_BLKDEV;
    case EXT4_S_IFIFO:  return EXT4_FT_FIFO;
    case EXT4_S_IFSOCK: return EXT4_FT_SOCK;
    default:            return EXT4_FT_REG_FILE;
    }
}

static int owns_data_blocks(const uint8_t *inode) {
    switch (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) {
    case EXT4_S_IFCHR:
    case EXT4_S_IFBLK:
    case EXT4_S_IFIFO:
    case EXT4_S_IFSOCK:
        return 0;
    case EXT4_S_IFLNK:
        return (rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS) != 0;
    default:
        return 1;
    }
}

/*
 * Gives back the external extended-attribute block, if the inode has one.
 *
 * Nothing in this driver writes extended attributes, but a file that arrived from
 * a desktop can carry them, and when they do not fit inside the inode they live in
 * a block of their own named by i_file_acl. Freeing the inode without freeing that
 * block leaves it marked in use with nothing referring to it - e2fsck reports it as
 * a block bitmap difference, and the space is gone until somebody runs a check.
 *
 * The block can be shared: identical attribute sets get pooled behind one block
 * with a count of the inodes using it. When that count is above one the block is
 * still in use by somebody else and must be left alone - decrementing it would mean
 * rewriting the block and its checksum, which is more of the attribute format than
 * this needs to understand, so the count is left as it stands and said out loud.
 * The residual is a count that reads one too high, which a check corrects; the
 * alternative is writing a checksum this driver cannot verify it got right.
 */
static void release_xattr_block(ext4_wfs *w, const uint8_t *inode, uint32_t ino) {
    uint64_t blk = rd32(inode + INODE_FILE_ACL_OFF);
    /* The high half of the block number shares its 16 bits with fields older
     * filesystems used for something else, so it is only read where the 64-bit
     * feature says it means this. */
    if (rd32(w->sb + EXT4_SB_FEATURE_INCOMPAT_OFF) & EXT4_FEATURE_INCOMPAT_64BIT)
        blk |= (uint64_t)rd16(inode + INODE_FILE_ACL_HI_OFF) << 32;
    if (blk == 0) return;
    if (blk >= w->blocks_count) {
        EXT4_LOGE("inode %u names attribute block %llu, past the end of the volume",
                  ino, (unsigned long long)blk);
        return;
    }

    uint8_t *buf = malloc(w->block_size);
    if (!buf) return;
    int readable = ext4_io_pread(&w->io, blk * (uint64_t)w->block_size,
                                 buf, w->block_size) == 0;
    uint32_t magic    = readable ? rd32(buf) : 0;
    uint32_t refcount = readable ? rd32(buf + XATTR_REFCOUNT_OFF) : 0;
    free(buf);

    if (!readable || magic != EXT4_XATTR_MAGIC) {
        /* Not something this recognises. Leaving a block alone costs the space
         * until the next check; freeing one that is not what it looks like would
         * hand live data to the next file that asks for a block. */
        EXT4_LOGE("inode %u names attribute block %llu, which does not look like "
                  "one - leaving it", ino, (unsigned long long)blk);
        return;
    }
    if (refcount > 1) {
        EXT4_LOGI("inode %u shares attribute block %llu with %u others - leaving it",
                  ino, (unsigned long long)blk, refcount - 1);
        return;
    }
    if (ext4_free_block(w, blk))
        EXT4_LOGE("inode %u: freeing attribute block %llu failed", ino,
                  (unsigned long long)blk);
    else
        EXT4_LOGI("inode %u: gave back attribute block %llu", ino,
                  (unsigned long long)blk);
}

int ext4_unlink_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint32_t when) {
    EXT4_LOGI("unlink '%s' from dir inode %u", name, dir_ino);

    uint32_t ino = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("unlink '%s': not found (%d)", name, rc);
        return rc;
    }

    /* A directory is rmdir's business, not this. Freeing one here would release its
     * blocks and inode without repairing the parent's link count or bg_used_dirs,
     * and strand every entry it holds. Refused before the name is touched, so the
     * image is left exactly as it was. */
    {
        uint8_t probe[EXT4_MAX_INODE_SIZE];
        memset(probe, 0, sizeof(probe));
        if (ext4_read_inode_raw(r, ino, probe, sizeof(probe)) != EXT4_OK)
            return EXT4_DIRW_ERR_IO;
        if ((rd16(probe + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR) {
            EXT4_LOGE("unlink '%s': it is a directory - refusing (use rmdir)", name);
            return EXT4_CREATE_ERR_ISDIR;
        }
        /* Something that owns blocks but has no extent tree is an old-style file,
         * whose data hangs off indirect blocks this driver cannot follow or
         * release. Refused here, alongside the directory check, because the one
         * thing that must not happen is discovering it after the name is gone. */
        if (owns_data_blocks(probe) &&
            !(rd32(probe + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS)) {
            EXT4_LOGE("unlink '%s': inode %u has no extent tree - refusing rather "
                      "than stranding it", name, ino);
            return EXT4_DIRW_ERR_FORMAT;
        }
    }

    /* Name first. A crash after this leaves an inode nothing refers to, which
     * e2fsck can tidy; the other order leaves a name pointing at blocks that
     * have already been handed to somebody else. */
    rc = ext4_dir_remove(w, r, dir_ino, name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("unlink '%s': removing the entry failed (%d)", name, rc);
        return rc;
    }

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    uint16_t links = rd16(inode + INODE_LINKS_COUNT_OFF);
    if (links > 1) {
        /* Another name still refers to it, so only the count moves. */
        EXT4_LOGI("unlink '%s': inode %u had %u links, dropping one", name, ino, links);
        return ext4_inode_adjust_links(w, ino, -1) == EXTW_OK
                   ? EXT4_DIRW_OK : EXT4_DIRW_ERR_IO;
    }

    EXT4_LOGI("unlink '%s': last name for inode %u, freeing its blocks and the inode",
              name, ino);

    /* The last name is gone: the blocks go back, then the inode. Blocks first -
     * an inode marked free while its blocks are still claimed leaks them with
     * nothing left to say whose they were.
     *
     * Only for the kinds that have data blocks at all. A device node, a FIFO, a
     * socket and a short symlink keep what they have inside the inode, and asking
     * truncation to walk an extent tree they do not have fails - after the name has
     * already gone, which strands the inode. */
    if (owns_data_blocks(inode) &&
        ext4_truncate_blocks(w, ino, 0) != EXTW_OK) return EXT4_DIRW_ERR_IO;

    /* Whatever kind it was, it may carry attributes in a block of their own. */
    release_xattr_block(w, inode, ino);

    /*
     * Dropping the link count to zero is not enough to say an inode is gone.
     * i_dtime has to say when, and an inode with no links and no dtime is neither
     * live nor deleted - e2fsck reports exactly that, "deleted inode has zero
     * dtime", which is how this was found. Both fields are written in one pass,
     * because they are one statement.
     */
    uint8_t *dead = malloc(w->inode_size);
    if (!dead) return EXT4_DIRW_ERR_IO;
    /* Copy no more than the smaller of the two. The source is a fixed
     * EXT4_MAX_INODE_SIZE buffer and the destination is the inode's real size, so
     * on a 128-byte-inode volume - deprecated, but `mke2fs -I 128` still makes one
     * and the open path admits it - a plain sizeof(inode) copy wrote 128 bytes off
     * the end of the allocation. Nothing downstream could see it: write_inode only
     * puts fs->inode_size bytes on disk, so the image stayed correct and e2fsck
     * stayed clean while the heap past the buffer was overwritten. Found with
     * ASan, which is the only oracle that can see it (#144). */
    size_t keep = w->inode_size < sizeof(inode) ? w->inode_size : sizeof(inode);
    memcpy(dead, inode, keep);
    if (w->inode_size > keep)
        memset(dead + keep, 0, w->inode_size - keep);
    wr16(dead + INODE_LINKS_COUNT_OFF, 0);
    wr32(dead + INODE_DTIME_OFF, when);
    int wrc = ext4_write_inode_raw(w, ino, dead);
    free(dead);
    if (wrc != EXT4_DIRW_OK) return wrc;

    if (ext4_free_inode(w, ino)) return EXT4_DIRW_ERR_IO;

    return ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
}

/* ── directories ──────────────────────────────────────────────────────────── */

/*
 * Fills the one block a new directory is born with.
 *
 * A directory is never empty on disk. "." and ".." are ordinary entries and have
 * to be there before anything can walk into it - a directory whose first block is
 * blank is not an empty directory, it is a corrupt one, because the chain of
 * rec_len has nowhere to start. ".." takes the whole rest of the block, which is
 * what leaves room for the entries that come later.
 */
typedef struct {
    uint32_t block_size;
    uint32_t ino;
    uint32_t parent;
    uint32_t seed;
} newdir_ctx;

static int fill_new_dir_block(void *user, uint32_t logical, uint8_t *buf) {
    const newdir_ctx *c = (const newdir_ctx *)user;
    (void)logical;

    uint32_t limit = c->block_size - DIR_TAIL_SIZE;
    memset(buf, 0, c->block_size);

    wr32(buf, c->ino);
    wr16(buf + 4, 12);
    buf[6] = 1;
    buf[7] = EXT4_FT_DIR;
    buf[8] = '.';

    uint8_t *up = buf + 12;
    wr32(up, c->parent);
    wr16(up + 4, (uint16_t)(limit - 12));
    up[6] = 2;
    up[7] = EXT4_FT_DIR;
    up[8] = '.';
    up[9] = '.';

    ext4_dir_stamp_tail(buf, c->block_size, c->seed);
    return 0;
}

int ext4_mkdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint16_t mode, uint32_t when,
               uint32_t *ino_out) {
    EXT4_LOGI("mkdir '%s' in dir inode %u", name, dir_ino);

    uint32_t existing = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &existing);
    if (rc == EXT4_DIRW_OK) {
        EXT4_LOGE("mkdir '%s': a name already points at inode %u", name, existing);
        return EXT4_DIRW_ERR_EXISTS;
    }
    if (rc != EXT4_DIRW_ERR_ABSENT) return rc;

    int64_t ino = ext4_alloc_inode(w);
    if (ino < 0) {
        EXT4_LOGE("mkdir '%s': no free inode", name);
        return EXT4_CREATE_ERR_NOINODE;
    }

    /*
     * Two links from the moment it exists: its own "." and the name in the parent
     * that is about to be added. Writing the count before that name is the same
     * trade the file path makes - a crash here leaves a directory nobody names,
     * which e2fsck moves to lost+found, rather than a name pointing at an inode
     * that is not a directory yet.
     */
    uint8_t *inode = malloc(w->inode_size);
    if (!inode) { ext4_free_inode(w, (uint32_t)ino); return EXT4_DIRW_ERR_IO; }
    /* Widened to world rwx for the same reason a created file is world rw: a
     * root-owned 0755 directory is read-only to a desktop user, who then cannot
     * add or remove anything inside it. See init_inode. */
    init_inode(inode, w->inode_size, (uint16_t)(EXT4_S_IFDIR | (mode & 0x0FFF) | 0777),
               2, when);
    rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);
    free(inode);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("mkdir '%s': writing inode %lld failed (%d)", name, (long long)ino, rc);
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    newdir_ctx ctx = { w->block_size, (uint32_t)ino, dir_ino,
                       ext4_inode_csum_seed(w->csum_seed, (uint32_t)ino, 0) };
    uint32_t added = 0;
    if (ext4_append_blocks(w, (uint32_t)ino, 1, fill_new_dir_block, &ctx, &added)
            != EXTW_OK || added != 1) {
        EXT4_LOGE("mkdir '%s': no room for the directory's first block", name);
        ext4_truncate_blocks(w, (uint32_t)ino, 0);
        ext4_free_inode(w, (uint32_t)ino);
        return EXT4_DIRW_ERR_NOROOM;
    }

    /* Complete and walkable. Only now does the parent name it. */
    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_DIR, name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("mkdir '%s': adding the directory entry failed (%d)", name, rc);
        ext4_truncate_blocks(w, (uint32_t)ino, 0);
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    /* The new ".." is a second name for the parent, and the group has one more
     * directory in it than it did. Neither follows from anything above.
     *
     * These two - and the same counters in ext4_rename - are not rolled back if
     * they fail: ext4_inode_adjust_links is a write followed by a flush, so a
     * failure cannot be told apart from a success whose flush was cut off, and a
     * rollback that guessed wrong would corrupt the count rather than repair it.
     * A failure here therefore leaves the parent's link count off by one, which
     * e2fsck reconciles - the same fsck-repairable residual a crash at this point
     * has, and the honest limit until this can journal its writes (issue #7).
     * faultcheck.py proves the residual never worsens into corruption. */
    if (ext4_inode_adjust_links(w, dir_ino, 1) != EXTW_OK) return EXT4_DIRW_ERR_IO;
    if (ext4_adjust_used_dirs(w, (uint32_t)ino, 1)) return EXT4_DIRW_ERR_IO;

    if (ino_out) *ino_out = (uint32_t)ino;
    rc = ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
    EXT4_LOGI("mkdir '%s': inode %lld, %s", name, (long long)ino,
              rc == EXT4_DIRW_OK ? "ok" : "flush failed");
    return rc;
}

/* Stops on the first entry that is neither "." nor "..". */
static int nonempty_cb(void *user, const ext4_dir_entry *e) {
    if (e->name_len == 1 && e->name[0] == '.') return 0;
    if (e->name_len == 2 && e->name[0] == '.' && e->name[1] == '.') return 0;
    *(int *)user = 1;
    return 1;
}

int ext4_rmdir(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
               const char *name, uint32_t when) {
    EXT4_LOGI("rmdir '%s' from dir inode %u", name, dir_ino);

    uint32_t ino = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (rc != EXT4_DIRW_OK) return rc;

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    if ((rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) != EXT4_S_IFDIR) {
        EXT4_LOGE("rmdir '%s': inode %u is not a directory", name, ino);
        return EXT4_CREATE_ERR_NOTDIR;
    }

    /*
     * Emptiness is checked before anything is written, and it is not politeness.
     * Everything inside a directory is reachable only through it, so removing one
     * that still holds names strands every inode below with nothing left to find
     * it by - blocks and inodes still marked in use, referenced by a tree whose
     * root has gone.
     */
    int nonempty = 0;
    rc = ext4_dir_iterate(r, inode, nonempty_cb, &nonempty);
    if (rc != EXT4_OK && rc != 1) return EXT4_DIRW_ERR_IO;
    if (nonempty) {
        EXT4_LOGE("rmdir '%s': not empty", name);
        return EXT4_CREATE_ERR_NOTEMPTY;
    }

    /* Name first, as unlink does, and for the same reason. */
    rc = ext4_dir_remove(w, r, dir_ino, name);
    if (rc != EXT4_DIRW_OK) return rc;

    /* Undoing the two counters mkdir moved. The parent loses the link that the
     * vanished ".." was. */
    if (ext4_inode_adjust_links(w, dir_ino, -1) != EXTW_OK) return EXT4_DIRW_ERR_IO;
    if (ext4_adjust_used_dirs(w, ino, -1)) return EXT4_DIRW_ERR_IO;

    if (ext4_truncate_blocks(w, ino, 0) != EXTW_OK) return EXT4_DIRW_ERR_IO;

    /* A directory carries attributes in a block of its own just as a file does. */
    release_xattr_block(w, inode, ino);

    uint8_t *dead = malloc(w->inode_size);
    if (!dead) return EXT4_DIRW_ERR_IO;
    /* Bounded like the one in ext4_unlink_file, and for the same reason. */
    size_t keep = w->inode_size < sizeof(inode) ? w->inode_size : sizeof(inode);
    memcpy(dead, inode, keep);
    if (w->inode_size > keep)
        memset(dead + keep, 0, w->inode_size - keep);
    wr16(dead + INODE_LINKS_COUNT_OFF, 0);
    wr32(dead + INODE_DTIME_OFF, when);
    int wrc = ext4_write_inode_raw(w, ino, dead);
    free(dead);
    if (wrc != EXT4_DIRW_OK) return wrc;

    if (ext4_free_inode(w, ino)) return EXT4_DIRW_ERR_IO;

    rc = ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
    EXT4_LOGI("rmdir '%s': inode %u freed, %s", name, ino,
              rc == EXT4_DIRW_OK ? "ok" : "flush failed");
    return rc;
}

/* ── moving an entry ──────────────────────────────────────────────────────── */

/*
 * True if moving `moving_dir` so that it lives under `dst_parent` would put it
 * inside its own subtree. Walks up from the destination by ".."; if the directory
 * being moved is on that path to the root, the move would cut a cycle off from the
 * tree - every inode in the moved subtree unreachable, its blocks still in use.
 *
 * Read-only, and bounded: a ".." that points at itself is the root and ends the
 * walk, and a depth cap ends it on a tree too deep or too damaged to prove safe,
 * which is refused rather than trusted.
 */
static int would_loop(const ext4_fs *r, uint32_t moving_dir, uint32_t dst_parent) {
    uint32_t cur = dst_parent;
    for (int depth = 0; depth < 128; depth++) {
        if (cur == moving_dir) return 1;
        uint32_t up = 0;
        if (ext4_dir_lookup(r, cur, "..", &up) != EXT4_DIRW_OK) return 0;
        if (up == cur) return 0;            /* the root: ".." names itself */
        cur = up;
    }
    return 1;
}

int ext4_rename(ext4_wfs *w, const ext4_fs *r,
                uint32_t src_parent, const char *src_name,
                uint32_t dst_parent, const char *dst_name) {
    EXT4_LOGI("rename '%s' (dir %u) -> '%s' (dir %u)",
              src_name, src_parent, dst_name, dst_parent);

    /* Renaming a thing to the name it already has, in the directory it is already
     * in, is success with nothing done. Checked before the destination's existence
     * so this is not mistaken for a clash with itself. */
    if (src_parent == dst_parent && strcmp(src_name, dst_name) == 0) {
        EXT4_LOGI("rename: source and destination are the same, nothing to do");
        return EXT4_DIRW_OK;
    }

    uint32_t src_ino = 0;
    int rc = ext4_dir_lookup(r, src_parent, src_name, &src_ino);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("rename: source '%s' not found (%d)", src_name, rc);
        return rc;
    }

    /* The destination must be free. Replacing what is there is a different
     * operation - it has to refuse a file over a directory and a directory over a
     * non-empty one, and without a journal its unlink-then-link loses the replaced
     * inode on a crash. Refused here rather than half-done. */
    uint32_t clash = 0;
    rc = ext4_dir_lookup(r, dst_parent, dst_name, &clash);
    if (rc == EXT4_DIRW_OK) {
        EXT4_LOGE("rename: destination '%s' already exists (inode %u)", dst_name, clash);
        return EXT4_DIRW_ERR_EXISTS;
    }
    if (rc != EXT4_DIRW_ERR_ABSENT) return rc;

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, src_ino, inode, sizeof(inode)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;
    int     is_dir = (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR;
    uint8_t ftype  = ftype_of(inode);
    int     across = (src_parent != dst_parent);

    /* A directory cannot descend into itself. Only possible when it changes parent,
     * and it must be caught before anything is written - the check walks the tree
     * the move is about to alter. */
    if (is_dir && across && would_loop(r, src_ino, dst_parent)) {
        EXT4_LOGE("rename: directory '%s' (inode %u) would be moved inside itself",
                  src_name, src_ino);
        return EXT4_CREATE_ERR_LOOP;
    }

    /*
     * The new name first, the old name last. Between them the inode carries two
     * names - reachable by both, which e2fsck reconciles - rather than the window
     * where it is reachable by neither. The inode's own link count is untouched:
     * one name became one name.
     */
    rc = ext4_dir_add(w, r, dst_parent, src_ino, ftype, dst_name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("rename: adding '%s' in dir %u failed (%d)", dst_name, dst_parent, rc);
        return rc;
    }

    /*
     * A directory that changed parent has a ".." that now lies, and two parent link
     * counts that are now wrong. All three move together, and before the old name
     * is removed: the new parent already names the directory, so its count and the
     * directory's own ".." should already agree with that.
     */
    if (is_dir && across) {
        rc = ext4_dir_set_dotdot(w, r, src_ino, dst_parent);
        if (rc != EXT4_DIRW_OK) {
            EXT4_LOGE("rename: repointing '..' of inode %u failed (%d)", src_ino, rc);
            return rc;
        }
        if (ext4_inode_adjust_links(w, dst_parent, 1) != EXTW_OK) return EXT4_DIRW_ERR_IO;
    }

    rc = ext4_dir_remove(w, r, src_parent, src_name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("rename: removing old name '%s' from dir %u failed (%d)",
                  src_name, src_parent, rc);
        return rc;
    }

    /* The old parent loses the link its ".." used to be. Done after the name is
     * gone, so at no point does the count claim a ".." that is still there.
     *
     * As in ext4_mkdir, a failed counter update here is left rather than rolled
     * back: the writes are not atomic, so a rollback that could not tell a failed
     * write from a cut-off flush would corrupt the count instead of repairing it.
     * What is left - a parent link off by one, or on the crash-safe add-before-
     * remove path an inode reachable by both its names - is what e2fsck reconciles.
     * The honest no-journal residual (issue #7); faultcheck.py holds it to being
     * repairable, never corruption. */
    if (is_dir && across) {
        if (ext4_inode_adjust_links(w, src_parent, -1) != EXTW_OK) return EXT4_DIRW_ERR_IO;
    }

    rc = ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
    EXT4_LOGI("rename '%s' -> '%s': %s", src_name, dst_name,
              rc == EXT4_DIRW_OK ? "ok" : "flush failed");
    return rc;
}
