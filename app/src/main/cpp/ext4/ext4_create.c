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

#define EXT4_INODE_FLAG_EXTENTS 0x00080000u
#define EXT4_EXTENT_MAGIC       0xF30A
#define EXT4_S_IFREG            0x8000
#define EXT4_S_IFDIR            0x4000
#define EXT4_S_IFMT             0xF000
#define EXT4_GOOD_EXTRA_ISIZE   32
#define DIR_TAIL_SIZE           12

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
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
 *   i_uid/i_gid  zero, which is what the container is mounted as
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
     * checksum is computed rather than after. */
    if (inode_size > 128)
        wr16(inode + INODE_EXTRA_ISIZE_OFF, EXT4_GOOD_EXTRA_ISIZE);
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
    init_inode(inode, w->inode_size, (uint16_t)(EXT4_S_IFREG | (mode & 0x0FFF)),
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

int ext4_unlink_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint32_t when) {
    EXT4_LOGI("unlink '%s' from dir inode %u", name, dir_ino);

    uint32_t ino = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("unlink '%s': not found (%d)", name, rc);
        return rc;
    }

    /* Name first. A crash after this leaves an inode nothing refers to, which
     * e2fsck can tidy; the other order leaves a name pointing at blocks that
     * have already been handed to somebody else. */
    rc = ext4_dir_remove(w, r, dir_ino, name);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("unlink '%s': removing the entry failed (%d)", name, rc);
        return rc;
    }

    uint8_t inode[256];
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
     * nothing left to say whose they were. */
    if (ext4_truncate_blocks(w, ino, 0) != EXTW_OK) return EXT4_DIRW_ERR_IO;

    /*
     * Dropping the link count to zero is not enough to say an inode is gone.
     * i_dtime has to say when, and an inode with no links and no dtime is neither
     * live nor deleted - e2fsck reports exactly that, "deleted inode has zero
     * dtime", which is how this was found. Both fields are written in one pass,
     * because they are one statement.
     */
    uint8_t *dead = malloc(w->inode_size);
    if (!dead) return EXT4_DIRW_ERR_IO;
    memcpy(dead, inode, sizeof(inode));
    if (w->inode_size > sizeof(inode))
        memset(dead + sizeof(inode), 0, w->inode_size - sizeof(inode));
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
    init_inode(inode, w->inode_size, (uint16_t)(EXT4_S_IFDIR | (mode & 0x0FFF)),
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
     * directory in it than it did. Neither follows from anything above. */
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

    uint8_t inode[256];
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

    uint8_t *dead = malloc(w->inode_size);
    if (!dead) return EXT4_DIRW_ERR_IO;
    memcpy(dead, inode, sizeof(inode));
    if (w->inode_size > sizeof(inode))
        memset(dead + sizeof(inode), 0, w->inode_size - sizeof(inode));
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
