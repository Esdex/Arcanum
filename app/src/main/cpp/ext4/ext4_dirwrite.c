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
#include "ext4_log.h"
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
 * A hash-indexed directory cannot be written to as it stands.
 *
 * Everything here places an entry by walking blocks and taking the first gap
 * that fits. In an indexed directory that is not merely suboptimal, it is wrong:
 * which leaf a name belongs in is decided by the hash of the name, and a name put
 * in a leaf whose hash range does not cover it is invisible to every lookup that
 * goes through the index - while still being listed by a linear walk. The
 * directory would look fine and behave as though the file were missing.
 *
 * So the first write to one rebuilds it as an ordinary linear directory first
 * (flatten_htree below) and proceeds on the shape this code fully controls. See
 * issue #141.
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

    if (ext4_io_pread(&w->io, phys * (uint64_t)w->block_size, buf, w->block_size))
        return EXT4_DIRW_ERR_IO;
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

    if (ext4_io_pwrite(&w->io, phys * (uint64_t)w->block_size, buf, w->block_size))
        return EXT4_DIRW_ERR_IO;
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
    uint8_t inode[EXT4_MAX_INODE_SIZE];
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

    uint8_t dir[EXT4_MAX_INODE_SIZE];
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
void ext4_dir_stamp_tail(uint8_t *block, uint32_t block_size, uint32_t seed) {
    uint8_t *tail = block + block_size - DIR_TAIL_SIZE;
    wr32(tail, 0);
    wr16(tail + 4, DIR_TAIL_SIZE);
    tail[6] = 0;
    tail[7] = EXT4_FT_DIR_CSUM;
    wr32(tail + 8, ext4_crc32c(seed, block, block_size - DIR_TAIL_SIZE));
}

static int fill_empty_dir_block(void *user, uint32_t logical, uint8_t *buf) {
    const empty_block_ctx *c = (const empty_block_ctx *)user;
    (void)logical;

    memset(buf, 0, c->block_size);
    wr32(buf, 0);                                             /* dead */
    wr16(buf + 4, (uint16_t)(c->block_size - DIR_TAIL_SIZE));

    ext4_dir_stamp_tail(buf, c->block_size, c->seed);
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

/* ── Turning a hash-indexed directory back into a linear one ──────────────── */

/*
 * An indexed directory is a tree laid over the same blocks a linear one uses.
 * Block 0 is its root: a real "." and a real "..", whose rec_len is stretched to
 * cover the whole rest of the block, and behind that cover the hash map. Interior
 * nodes are a single dead entry spanning the block, with a map behind it in the
 * same way. Only the leaves hold names, and a leaf is an ordinary linear block.
 *
 * That is why reading works today with no knowledge of any of it: a linear walk
 * sees "." and ".." in the root, nothing at all in an interior node, and every
 * name in the leaves. Exactly the live entries, exactly once. The rebuild is
 * built on that same walk, so it inherits a reader already checked against
 * debugfs rather than adding a second one.
 *
 * Rebuilding rather than maintaining the index is a deliberate trade. Maintaining
 * it means hashing names with the volume's seed, choosing a leaf by hash range
 * and splitting one when it fills - and a split that puts one entry on the wrong
 * side leaves a name that a linear listing shows and a lookup cannot find, which
 * is the failure mode this whole layer is shaped to avoid. Rebuilding costs one
 * pass over a single directory, the shape it leaves behind is one every other
 * path here already handles, and it is legal: the dir_index feature says indexed
 * directories may exist, not that they must. What is lost is the index, on a
 * directory large enough to have earned one - the desktop's own `e2fsck -D` puts
 * it back if that ever matters.
 */

/*
 * Closes an output block: the last entry's rec_len is stretched to the end of the
 * chain so that the chain adds up to exactly the block, and the tail is stamped.
 * An empty block gets the one dead entry that spans it.
 */
static void close_out_block(ext4_wfs *w, uint8_t *dst, uint32_t last_off,
                            uint32_t used, uint32_t limit, uint32_t seed,
                            int with_tail) {
    if (used == 0) {
        wr32(dst, 0);
        wr16(dst + 4, (uint16_t)limit);
    } else {
        wr16(dst + last_off + 4, (uint16_t)(limit - last_off));
    }
    if (with_tail) ext4_dir_stamp_tail(dst, w->block_size, seed);
}

static int write_out_block(ext4_wfs *w, const ext4_fs *r, const uint8_t *inode,
                           uint32_t logical, uint8_t *buf, uint32_t seed) {
    uint64_t phys = 0;
    int uninit = 0;
    if (ext4_map_block(r, inode, logical, &phys, &uninit) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;
    if (phys == 0 || uninit) return EXT4_DIRW_ERR_FORMAT;
    return write_dir_block(w, phys, buf, seed);
}

/*
 * One pass over the directory, packing every live entry into as few linear blocks
 * as they take.
 *
 * With `dst` NULL nothing is written and the pass only measures; with `dst` given
 * it writes. The two are the same walk on purpose - what the measuring pass
 * accepts is exactly what the writing pass then does, so the checks below are
 * made once and hold for both.
 */
static int flatten_pass(ext4_wfs *w, const ext4_fs *r, const uint8_t *dir,
                        uint32_t blocks, uint32_t seed,
                        uint8_t *src, uint8_t *dst, uint32_t *need_out) {
    /* Room is reserved for a checksum tail exactly when the volume has one to
     * reserve it for. Reserving it unconditionally would cost twelve bytes a
     * block against a source that spent them on entries, and on a volume without
     * metadata_csum enough of those add up to a whole block - which would make
     * the rebuild need more blocks than it found and refuse a directory it can
     * plainly hold. Matching the volume keeps the output exactly as dense as the
     * input, which is what the guard further down rests on. */
    const int with_tail = r->has_metadata_csum;
    const uint32_t limit = with_tail ? w->block_size - DIR_TAIL_SIZE
                                     : w->block_size;
    uint32_t out_index = 0, used = 0, last_off = 0, seen = 0;

    if (dst) memset(dst, 0, w->block_size);

    for (uint32_t b = 0; b < blocks; b++) {
        uint64_t phys = 0;
        int prc = read_dir_block(w, r, dir, b, src, &phys);
        if (prc == EXT4_DIRW_ERR_NOROOM) continue;    /* a hole holds no entries */
        if (prc != EXT4_DIRW_OK) return prc;

        uint32_t src_limit = chain_limit(w, src);
        for (uint32_t off = 0; off + DIRENT_HEADER <= src_limit; ) {
            uint32_t cur_ino = rd32(src + off);
            uint16_t rec     = rd16(src + off + 4);
            uint8_t  nlen    = src[off + 6];
            uint8_t  ftype   = src[off + 7];
            if (rec < DIRENT_HEADER || (rec & 3) || off + rec > src_limit)
                return EXT4_DIRW_ERR_FORMAT;

            if (cur_ino != 0 && nlen != 0 && ftype != EXT4_FT_DIR_CSUM) {
                if (DIRENT_HEADER + (uint32_t)nlen > rec)
                    return EXT4_DIRW_ERR_FORMAT;

                /* "." and ".." lead the first block, in that order, in both
                 * shapes. Anything else at the head is a directory this does not
                 * recognise, and rebuilding it would be guessing. */
                if (seen == 0 && !(nlen == 1 && src[off + DIRENT_HEADER] == '.'))
                    return EXT4_DIRW_ERR_FORMAT;
                if (seen == 1 && !(nlen == 2 && src[off + DIRENT_HEADER] == '.' &&
                                                src[off + DIRENT_HEADER + 1] == '.'))
                    return EXT4_DIRW_ERR_FORMAT;
                seen++;

                uint32_t need = entry_size(nlen);
                if (used + need > limit) {
                    if (dst) {
                        close_out_block(w, dst, last_off, used, limit, seed, with_tail);
                        int wrc = write_out_block(w, r, dir, out_index, dst, seed);
                        if (wrc != EXT4_DIRW_OK) return wrc;
                        memset(dst, 0, w->block_size);
                    }
                    out_index++;
                    used = 0;
                    last_off = 0;

                    /* The rebuilt blocks go back over the ones being read, so the
                     * output must never get ahead of the input: block `out_index`
                     * is only overwritten once block `out_index` has been read.
                     * Packing is denser than any layout it replaces - the root and
                     * every interior node give up a whole block's worth - so this
                     * holds by construction, and being told otherwise means the
                     * directory is not one to rebuild in place. Measured before a
                     * byte is written, which is what makes refusing here safe. */
                    if (out_index > b) return EXT4_DIRW_ERR_NOROOM;
                }

                if (dst) {
                    wr32(dst + used, cur_ino);
                    wr16(dst + used + 4, (uint16_t)need);
                    dst[used + 6] = nlen;
                    dst[used + 7] = ftype;
                    memcpy(dst + used + DIRENT_HEADER, src + off + DIRENT_HEADER, nlen);
                }
                last_off = used;
                used += need;
            }
            off += rec;
        }
    }

    if (seen < 2) return EXT4_DIRW_ERR_FORMAT;   /* no "." and ".." to be found */

    if (dst) {
        close_out_block(w, dst, last_off, used, limit, seed, with_tail);
        int wrc = write_out_block(w, r, dir, out_index, dst, seed);
        if (wrc != EXT4_DIRW_OK) return wrc;

        /* Whatever the old shape needed and the new one does not stays part of the
         * directory rather than being freed: an empty block, formatted and ready,
         * which is the same thing removing every name from a block leaves behind.
         * Freeing them would mean moving i_size and the extent tree as well, and a
         * directory that just grew back would take them straight from the
         * allocator again. */
        for (uint32_t b = out_index + 1; b < blocks; b++) {
            memset(dst, 0, w->block_size);
            close_out_block(w, dst, 0, 0, limit, seed, with_tail);
            wrc = write_out_block(w, r, dir, b, dst, seed);
            if (wrc != EXT4_DIRW_OK) return wrc;
        }
    }

    if (need_out) *need_out = out_index + 1;
    return EXT4_DIRW_OK;
}

/*
 * Rewrites `dir_ino` as a linear directory and clears its INDEX flag.
 *
 * `dir` is the caller's copy of the inode and is updated in place, so the caller
 * carries on with the flag already cleared rather than reading it back through a
 * handle that may not yet see the write.
 *
 * Two orderings decide what an interrupted rebuild leaves behind, and neither is
 * free, so the one that fails better was taken:
 *
 *   - the flag is cleared first. Between that and the first block being rewritten
 *     the directory is flagged linear while still holding index-shaped blocks -
 *     which reads correctly, because a linear walk of those blocks is exactly what
 *     this pass is built on. Every name stays reachable throughout.
 *   - blocks are then rewritten from the front. A rebuild cut short leaves the
 *     blocks already written holding entries the untouched ones behind them still
 *     hold too, so the directory reads with duplicates - repairable, and nothing
 *     is lost. Writing from the back instead would move entries out of a block
 *     before their new home was written, and lose them.
 *
 * Clearing the flag last would have kept the index over blocks that no longer
 * match it, which is the one outcome where a name is present and cannot be found.
 * The remaining window is #142's ground.
 */
static int flatten_htree(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                         uint8_t *dir) {
    uint32_t blocks = dir_block_count(r, dir);
    if (blocks == 0) return EXT4_DIRW_ERR_FORMAT;

    uint32_t seed = inode_seed_of(w, r, dir_ino);
    uint8_t *src = malloc(w->block_size);
    uint8_t *dst = malloc(w->block_size);
    if (!src || !dst) { free(src); free(dst); return EXT4_DIRW_ERR_IO; }

    /* Measured in full before anything is written. A directory this cannot lay
     * out is left exactly as it was, which is the old behaviour and still the
     * right one - a rebuild that ran out of room half way would be worse than
     * never starting. */
    uint32_t need = 0;
    int rc = flatten_pass(w, r, dir, blocks, seed, src, NULL, &need);
    if (rc != EXT4_DIRW_OK) goto done;

    wr32(dir + INODE_FLAGS_OFF,
         rd32(dir + INODE_FLAGS_OFF) & ~EXT4_INODE_FLAG_INDEX);
    if (ext4_write_inode_raw(w, dir_ino, dir) != EXTW_OK) {
        rc = EXT4_DIRW_ERR_IO;
        goto done;
    }

    rc = flatten_pass(w, r, dir, blocks, seed, src, dst, NULL);
    if (rc == EXT4_DIRW_OK)
        EXT4_LOGI("dir inode %u was hash-indexed; rebuilt as %u linear block(s) "
                  "of %u", dir_ino, need, blocks);

done:
    free(src);
    free(dst);
    return rc;
}

/*
 * The one place the three write paths go through, so a directory is rebuilt on
 * whichever of them touches it first and never more than once.
 */
static int ensure_linear(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                         uint8_t *dir) {
    if (!is_htree(dir)) return EXT4_DIRW_OK;

    int rc = flatten_htree(w, r, dir_ino, dir);
    if (rc != EXT4_DIRW_OK) {
        EXT4_LOGE("dir inode %u is hash-indexed and could not be rebuilt as a "
                  "linear directory (%d); refusing to write to it rather than "
                  "corrupting it", dir_ino, rc);
        return EXT4_DIRW_ERR_HTREE;
    }
    return EXT4_DIRW_OK;
}

int ext4_dir_add(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                 uint32_t ino, uint8_t file_type, const char *name) {
    uint8_t name_len;
    if (!name_ok(name, &name_len)) return EXT4_DIRW_ERR_NAME;
    if (ino == 0) return EXT4_DIRW_ERR_FORMAT;

    uint8_t dir[EXT4_MAX_INODE_SIZE];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    int frc = ensure_linear(w, r, dir_ino, dir);
    if (frc != EXT4_DIRW_OK) return frc;

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

    uint8_t dir[EXT4_MAX_INODE_SIZE];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    int frc = ensure_linear(w, r, dir_ino, dir);
    if (frc != EXT4_DIRW_OK) return frc;

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

int ext4_dir_set_dotdot(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                        uint32_t new_parent) {
    if (new_parent == 0) return EXT4_DIRW_ERR_FORMAT;

    uint8_t dir[EXT4_MAX_INODE_SIZE];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
        return EXT4_DIRW_ERR_IO;

    int frc = ensure_linear(w, r, dir_ino, dir);
    if (frc != EXT4_DIRW_OK) return frc;

    uint32_t seed = inode_seed_of(w, r, dir_ino);
    uint8_t *buf  = malloc(w->block_size);
    if (!buf) return EXT4_DIRW_ERR_IO;

    /* ".." is always in the directory's first block - it is written there when the
     * directory is created and nothing moves it. */
    uint64_t phys = 0;
    int rc = read_dir_block(w, r, dir, 0, buf, &phys);
    if (rc != EXT4_DIRW_OK) { free(buf); return rc; }

    uint32_t limit = chain_limit(w, buf);
    rc = EXT4_DIRW_ERR_ABSENT;
    for (uint32_t off = 0; off + DIRENT_HEADER <= limit; ) {
        uint32_t cur_ino = rd32(buf + off);
        uint16_t rec     = rd16(buf + off + 4);
        uint8_t  nlen    = buf[off + 6];
        if (rec < DIRENT_HEADER || (rec & 3) || off + rec > limit) {
            rc = EXT4_DIRW_ERR_FORMAT;
            break;
        }
        if (cur_ino != 0 && nlen == 2 &&
            buf[off + DIRENT_HEADER] == '.' && buf[off + DIRENT_HEADER + 1] == '.') {
            wr32(buf + off, new_parent);                 /* only the inode field */
            rc = write_dir_block(w, phys, buf, seed);    /* restamps the checksum */
            break;
        }
        off += rec;
    }

    free(buf);
    return rc;
}
