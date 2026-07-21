/*
 * Adding and removing directory entries.
 *
 * Clean-room, from the published on-disk format.
 *
 * Every entry's rec_len says where the next one begins, so the entries in a block
 * are a chain with no gaps: the space between what an entry needs and what its
 * rec_len claims is not free space in any list, it is simply unaccounted for.
 * Adding means finding such a gap and splitting it; removing means giving the gap
 * back to the entry in front.
 *
 * That makes both operations edits to a linked list held in a fixed-size buffer,
 * and the failure they share is leaving the chain not adding up to the block. A
 * chain that overshoots walks into the tail or past the block; one that
 * undershoots leaves a hole no reader will ever visit, so the space is lost for
 * good. Neither shows up as a checksum failure - the block is rewritten and
 * restamped either way - and e2fsck is what notices.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_dirwrite.h"
#include "ext4_csum.h"
#include "ext4_extwrite.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define DIRENT_HEADER   8
#define DIR_TAIL_SIZE  12
#define INODE_GENERATION_OFF 0x64
#define INODE_FLAGS_OFF      0x20
#define EXT4_INODE_FLAG_INDEX 0x00001000u


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
 * A hash-indexed directory is refused rather than written to.
 *
 * Everything here places an entry by walking blocks and taking the first gap
 * that fits. In an indexed directory that is not merely suboptimal, it is wrong:
 * which leaf a name belongs in is decided by the hash of the name, and a name put
 * in a leaf whose hash range does not cover it is invisible to every lookup that
 * goes through the index - while still being listed by a linear walk. The
 * directory would look fine and behave as though the file were missing.
 *
 * The refusal cannot be exercised by this corpus, and that was established rather
 * than assumed: mke2fs -d with 3000 files, and fuse2fs with 2000 created through
 * a mount, both leave INDEX_FL clear. Everything available here is built on
 * libext2fs, which reads hash-indexed directories but does not build them - only
 * the kernel does, and mounting for real needs privileges this has no business
 * requiring. So the guard is held up by review, and by the fact that the
 * alternative to refusing is silent corruption.
 */
static int is_htree(const uint8_t *inode) {
    return (rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_INDEX) != 0;
}

/* What an entry actually occupies: header plus name, rounded up to 4 because
 * every rec_len is. The difference from its rec_len is the gap it is sitting on. */
static uint32_t entry_size(uint8_t name_len) {
    return (DIRENT_HEADER + (uint32_t)name_len + 3u) & ~3u;
}

/*
 * A name has to be checked before it is stored, not after. A slash would make one
 * entry read as a path, and an embedded NUL would make the stored name and the
 * name every C caller sees disagree - the second is what lets "safe.txt\0.sh"
 * exist as one thing and be seen as another.
 */
static int name_ok(const char *name, uint8_t *len_out) {
    if (!name) return 0;
    size_t n = strlen(name);
    if (n == 0 || n > EXT4_DIRENT_MAX_NAME) return 0;
    for (size_t i = 0; i < n; i++)
        if (name[i] == '/' || name[i] == '\0') return 0;
    if (!strcmp(name, ".") || !strcmp(name, "..")) return 0;
    *len_out = (uint8_t)n;
    return 1;
}

static uint32_t dir_block_count(const ext4_fs *r, const uint8_t *inode) {
    uint64_t size = ext4_inode_size(inode);
    return (uint32_t)(size / r->block_size);
}

static int read_dir_block(ext4_wfs *w, const ext4_fs *r, const uint8_t *inode,
                          uint32_t logical, uint8_t *buf, uint64_t *phys_out) {
    uint64_t phys = 0;
    int uninit = 0;
    int rc = ext4_map_block(r, inode, logical, &phys, &uninit);
    if (rc != EXT4_OK) return EXT4_DIRW_ERR_IO;
    /* A directory with a hole in it is not something to write into: the block
     * would have to be allocated and formatted first, which is the growth path. */
    if (phys == 0 || uninit) return EXT4_DIRW_ERR_NOROOM;

    if (fseeko(w->fp, (off_t)phys * w->block_size, SEEK_SET)) return EXT4_DIRW_ERR_IO;
    if (fread(buf, 1, w->block_size, w->fp) != w->block_size) return EXT4_DIRW_ERR_IO;
    *phys_out = phys;
    return EXT4_DIRW_OK;
}

/* Restamps the tail before writing, so a block and its checksum cannot go to disk
 * apart. A block without a tail is written as it is - the filesystem was made
 * without metadata_csum and there is nothing to keep in step. */
static int write_dir_block(ext4_wfs *w, uint64_t phys, uint8_t *buf, uint32_t seed) {
    uint8_t *tail = buf + w->block_size - DIR_TAIL_SIZE;
    if (rd32(tail) == 0 && rd16(tail + 4) == DIR_TAIL_SIZE &&
        tail[7] == EXT4_FT_DIR_CSUM)
        wr32(tail + 8, ext4_crc32c(seed, buf, w->block_size - DIR_TAIL_SIZE));

    if (fseeko(w->fp, (off_t)phys * w->block_size, SEEK_SET)) return EXT4_DIRW_ERR_IO;
    if (fwrite(buf, 1, w->block_size, w->fp) != w->block_size) return EXT4_DIRW_ERR_IO;
    return EXT4_DIRW_OK;
}

/*
 * How far into a block entries may go. With metadata_csum the last 12 bytes are
 * the tail, and the chain has to stop exactly there - a chain that runs over it
 * would overwrite the checksum with a name.
 */
static uint32_t chain_limit(const ext4_wfs *w, const uint8_t *buf) {
    const uint8_t *tail = buf + w->block_size - DIR_TAIL_SIZE;
    if (rd32(tail) == 0 && rd16(tail + 4) == DIR_TAIL_SIZE &&
        tail[7] == EXT4_FT_DIR_CSUM)
        return w->block_size - DIR_TAIL_SIZE;
    return w->block_size;
}

static uint32_t inode_seed_of(ext4_wfs *w, const ext4_fs *r, uint32_t ino) {
    uint8_t inode[256];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) return 0;
    return ext4_inode_csum_seed(w->csum_seed, ino, rd32(inode + INODE_GENERATION_OFF));
}

typedef struct {
    const char *name;
    uint8_t     name_len;
    uint32_t    found;
} lookup_ctx;

static int lookup_cb(void *user, const ext4_dir_entry *e) {
    lookup_ctx *c = (lookup_ctx *)user;
    if (e->name_len == c->name_len && !memcmp(e->name, c->name, c->name_len)) {
        c->found = e->inode;
        return 1;               /* stops the walk */
    }
    return 0;
}

int ext4_dir_lookup(const ext4_fs *r, uint32_t dir_ino, const char *name,
                    uint32_t *ino_out) {
    size_t n = name ? strlen(name) : 0;
    if (n == 0 || n > EXT4_DIRENT_MAX_NAME) return EXT4_DIRW_ERR_NAME;

    uint8_t dir[256];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    lookup_ctx c = { name, (uint8_t)n, 0 };
    int rc = ext4_dir_iterate(r, dir, lookup_cb, &c);
    if (rc != EXT4_OK && rc != 1) return EXT4_DIRW_ERR_IO;
    if (c.found == 0) return EXT4_DIRW_ERR_ABSENT;
    if (ino_out) *ino_out = c.found;
    return EXT4_DIRW_OK;
}

typedef struct {
    uint32_t block_size;
    uint32_t seed;
} empty_block_ctx;

/*
 * Formats a block so that it is an empty directory block rather than a block of
 * whatever was there before: one dead entry claiming everything up to the tail,
 * and the tail itself.
 *
 * Both halves matter. Without the dead entry spanning the block, a reader
 * starting at offset zero finds a rec_len of nothing and stops - or worse, walks
 * on whatever bytes are there. Without the tail, the block has no checksum and
 * e2fsck says so the moment the directory is next read.
 */
static int fill_empty_dir_block(void *user, uint32_t logical, uint8_t *buf) {
    const empty_block_ctx *c = (const empty_block_ctx *)user;
    (void)logical;

    memset(buf, 0, c->block_size);
    wr32(buf, 0);                                             /* dead */
    wr16(buf + 4, (uint16_t)(c->block_size - DIR_TAIL_SIZE));

    uint8_t *tail = buf + c->block_size - DIR_TAIL_SIZE;
    wr32(tail, 0);
    wr16(tail + 4, DIR_TAIL_SIZE);
    tail[6] = 0;
    tail[7] = EXT4_FT_DIR_CSUM;
    wr32(tail + 8, ext4_crc32c(c->seed, buf, c->block_size - DIR_TAIL_SIZE));
    return 0;
}

/*
 * Adds one block to the end of a directory, formatted and ready to hold entries.
 *
 * The size a directory reports has to stay a whole number of blocks: the walk
 * stops at i_size, so a size that stopped short would make the new block
 * invisible and a size that ran over would make the walk read past the last one.
 * ext4_append_blocks sets the size from the blocks now mapped, which is exactly
 * that, but it is the property to check if this ever goes wrong.
 */
static int grow_directory(ext4_wfs *w, uint32_t dir_ino, uint32_t seed) {
    empty_block_ctx ctx = { w->block_size, seed };
    uint32_t added = 0;
    int rc = ext4_append_blocks(w, dir_ino, 1, fill_empty_dir_block, &ctx, &added);
    if (rc != EXTW_OK || added != 1) return EXT4_DIRW_ERR_NOROOM;
    return EXT4_DIRW_OK;
}

int ext4_dir_add(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                 uint32_t ino, uint8_t file_type, const char *name) {
    uint8_t name_len;
    if (!name_ok(name, &name_len)) return EXT4_DIRW_ERR_NAME;
    if (ino == 0) return EXT4_DIRW_ERR_FORMAT;

    uint8_t dir[256];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    if (is_htree(dir)) return EXT4_DIRW_ERR_HTREE;

    uint32_t need   = entry_size(name_len);
    uint32_t blocks = dir_block_count(r, dir);
    uint32_t seed   = inode_seed_of(w, r, dir_ino);
    uint8_t *buf    = malloc(w->block_size);
    if (!buf) return EXT4_DIRW_ERR_IO;

    int rc = EXT4_DIRW_ERR_NOROOM;
    int grown = 0;

again:

    /* Two passes. The name has to be unique across the whole directory, so every
     * block is scanned for a clash before any of them is written to - finding room
     * in block 0 and stopping there would happily add a second copy of a name that
     * already exists in block 3. */
    for (uint32_t b = 0; b < blocks; b++) {
        uint64_t phys;
        int prc = read_dir_block(w, r, dir, b, buf, &phys);
        if (prc == EXT4_DIRW_ERR_NOROOM) continue;
        if (prc != EXT4_DIRW_OK) { rc = prc; goto done; }

        uint32_t limit = chain_limit(w, buf);
        for (uint32_t off = 0; off + DIRENT_HEADER <= limit; ) {
            uint16_t rec = rd16(buf + off + 4);
            if (rec < DIRENT_HEADER || (rec & 3) || off + rec > limit) {
                rc = EXT4_DIRW_ERR_FORMAT;
                goto done;
            }
            if (rd32(buf + off) != 0 && buf[off + 6] == name_len &&
                !memcmp(buf + off + DIRENT_HEADER, name, name_len)) {
                rc = EXT4_DIRW_ERR_EXISTS;
                goto done;
            }
            off += rec;
        }
    }

    for (uint32_t b = 0; b < blocks; b++) {
        uint64_t phys;
        int prc = read_dir_block(w, r, dir, b, buf, &phys);
        if (prc == EXT4_DIRW_ERR_NOROOM) continue;
        if (prc != EXT4_DIRW_OK) { rc = prc; goto done; }

        uint32_t limit = chain_limit(w, buf);
        for (uint32_t off = 0; off + DIRENT_HEADER <= limit; ) {
            uint32_t cur_ino = rd32(buf + off);
            uint16_t rec     = rd16(buf + off + 4);
            uint8_t  nlen    = buf[off + 6];
            if (rec < DIRENT_HEADER || (rec & 3) || off + rec > limit) {
                rc = EXT4_DIRW_ERR_FORMAT;
                goto done;
            }

            /* A dead entry gives up all of its rec_len; a live one only what it
             * is holding beyond its own name. */
            uint32_t used = (cur_ino == 0) ? 0 : entry_size(nlen);
            if (rec - used >= need) {
                uint8_t *slot;
                if (used == 0) {
                    slot = buf + off;               /* reuse it whole */
                } else {
                    wr16(buf + off + 4, (uint16_t)used);   /* shrink to fit */
                    slot = buf + off + used;
                    wr16(slot + 4, (uint16_t)(rec - used));
                }
                wr32(slot, ino);
                slot[6] = name_len;
                slot[7] = file_type;
                memcpy(slot + DIRENT_HEADER, name, name_len);

                rc = write_dir_block(w, phys, buf, seed);
                goto done;
            }
            off += rec;
        }
    }

    /* Nowhere to put it in what the directory already has. Grow it by one block
     * and look again - once. A second failure is not a full directory, it is a
     * block that came back unusable, and retrying would spin. */
    if (rc == EXT4_DIRW_ERR_NOROOM && !grown) {
        grown = 1;
        int grc = grow_directory(w, dir_ino, seed);
        if (grc != EXT4_DIRW_OK) { rc = grc; goto done; }
        if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK) {
            rc = EXT4_DIRW_ERR_IO;
            goto done;
        }
        blocks = dir_block_count(r, dir);
        goto again;
    }

done:
    free(buf);
    return rc;
}

int ext4_dir_remove(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                    const char *name) {
    uint8_t name_len;
    if (!name_ok(name, &name_len)) return EXT4_DIRW_ERR_NAME;

    uint8_t dir[256];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    if (is_htree(dir)) return EXT4_DIRW_ERR_HTREE;

    uint32_t blocks = dir_block_count(r, dir);
    uint32_t seed   = inode_seed_of(w, r, dir_ino);
    uint8_t *buf    = malloc(w->block_size);
    if (!buf) return EXT4_DIRW_ERR_IO;

    int rc = EXT4_DIRW_ERR_ABSENT;

    for (uint32_t b = 0; b < blocks; b++) {
        uint64_t phys;
        int prc = read_dir_block(w, r, dir, b, buf, &phys);
        if (prc == EXT4_DIRW_ERR_NOROOM) continue;
        if (prc != EXT4_DIRW_OK) { rc = prc; goto done; }

        uint32_t limit = chain_limit(w, buf);
        uint32_t prev  = UINT32_MAX;

        for (uint32_t off = 0; off + DIRENT_HEADER <= limit; ) {
            uint32_t cur_ino = rd32(buf + off);
            uint16_t rec     = rd16(buf + off + 4);
            uint8_t  nlen    = buf[off + 6];
            if (rec < DIRENT_HEADER || (rec & 3) || off + rec > limit) {
                rc = EXT4_DIRW_ERR_FORMAT;
                goto done;
            }

            if (cur_ino != 0 && nlen == name_len &&
                !memcmp(buf + off + DIRENT_HEADER, name, name_len)) {
                if (prev == UINT32_MAX) {
                    /* Nothing in front to absorb it, so it becomes a dead entry
                     * in place. Its rec_len has to stay exactly as it was: the
                     * chain must still reach the end of the block. */
                    wr32(buf + off, 0);
                } else {
                    wr16(buf + prev + 4, (uint16_t)(rd16(buf + prev + 4) + rec));
                }
                rc = write_dir_block(w, phys, buf, seed);
                goto done;
            }
            prev = off;
            off += rec;
        }
    }

done:
    free(buf);
    return rc;
}
