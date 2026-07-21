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
#define EXT4_GOOD_EXTRA_ISIZE   32

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
 * left zero are listed here so that a later reader knows they were considered:
 *
 *   i_dtime      zero means "not deleted"; a nonzero one on a linked inode is
 *                the contradiction e2fsck reports first
 *   i_uid/i_gid  zero, which is what the container is mounted as
 *   i_blocks     zero, no blocks yet
 *   i_generation zero, matching what mke2fs writes for its own files
 */
static void init_regular_inode(uint8_t *inode, uint32_t inode_size,
                               uint16_t mode, uint32_t when) {
    memset(inode, 0, inode_size);

    wr16(inode + INODE_MODE_OFF, (uint16_t)(EXT4_S_IFREG | (mode & 0x0FFF)));
    wr16(inode + INODE_LINKS_COUNT_OFF, 1);
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
    /* Checked before an inode is taken. Finding the clash afterwards would mean
     * handing one back, and a rollback that is never needed cannot be wrong. */
    uint32_t existing = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &existing);
    if (rc == EXT4_DIRW_OK) return EXT4_DIRW_ERR_EXISTS;
    if (rc != EXT4_DIRW_ERR_ABSENT) return rc;

    int64_t ino = ext4_alloc_inode(w);
    if (ino < 0) return EXT4_CREATE_ERR_NOINODE;

    uint8_t *inode = malloc(w->inode_size);
    if (!inode) { ext4_free_inode(w, (uint32_t)ino); return EXT4_DIRW_ERR_IO; }
    init_regular_inode(inode, w->inode_size, mode, when);

    rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);
    free(inode);
    if (rc != EXT4_DIRW_OK) {
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    /* The inode is complete and claims one link, so the name it is about to get
     * is already accounted for. Only now does anything point at it. */
    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_REG_FILE, name);
    if (rc != EXT4_DIRW_OK) {
        ext4_free_inode(w, (uint32_t)ino);
        return rc;
    }

    if (ino_out) *ino_out = (uint32_t)ino;
    return ext4_fs_flush(w) ? EXT4_DIRW_ERR_IO : EXT4_DIRW_OK;
}

int ext4_unlink_file(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                     const char *name, uint32_t when) {
    uint32_t ino = 0;
    int rc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (rc != EXT4_DIRW_OK) return rc;

    /* Name first. A crash after this leaves an inode nothing refers to, which
     * e2fsck can tidy; the other order leaves a name pointing at blocks that
     * have already been handed to somebody else. */
    rc = ext4_dir_remove(w, r, dir_ino, name);
    if (rc != EXT4_DIRW_OK) return rc;

    uint8_t inode[256];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    uint16_t links = rd16(inode + INODE_LINKS_COUNT_OFF);
    if (links > 1) {
        /* Another name still refers to it, so only the count moves. */
        return ext4_inode_adjust_links(w, ino, -1) == EXTW_OK
                   ? EXT4_DIRW_OK : EXT4_DIRW_ERR_IO;
    }

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
