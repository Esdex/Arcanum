/*
 * Appending blocks to a file's extent tree.
 *
 * Clean-room, from the published on-disk format. See the header, and issue #7.
 *
 * This is the layer that makes the allocator's output legitimate. A block taken
 * from the bitmap and attached to nothing is an orphan, and e2fsck says so; the
 * same block reachable from an inode is simply a file getting longer. So from
 * here on the harness can demand that fsck come back completely clean, rather
 * than gating a residual it was told to expect.
 *
 * What has to move, per appended block:
 *
 *   - the block's contents, written before anything points at it
 *   - either the last extent's ee_len, when the new block is physically adjacent
 *     to it, or a fresh entry in the rightmost leaf
 *   - that leaf's own tail checksum, when the leaf is a block rather than the root
 *   - i_size, i_blocks
 *   - the inode checksum, last, since it covers all of the above
 *
 * The root inside an inode is the one node with no checksum of its own: the
 * inode's covers it. Every other node owns a block and carries a tail, so which
 * of the two an append lands in decides whether a checksum has to be restamped
 * separately - see node_ref.
 *
 * When the root fills, it is pushed down into a block of its own and the tree
 * gains a level. A leaf block that fills is refused for now.
 *
 * Adjacency is not luck. Each block is asked for with the block after the file's
 * current last one as the goal, so a file being extended into free space keeps
 * using one extent instead of burning through the four available.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_extwrite.h"
#include "ext4_csum.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define EXT4_EXTENT_MAGIC        0xF30A
#define EXT4_INODE_FLAG_EXTENTS  0x00080000u
#define EXT4_MAX_INIT_LEN        32768u

#define INODE_SIZE_LO_OFF   0x04
#define INODE_BLOCKS_LO_OFF 0x1C
#define INODE_FLAGS_OFF     0x20
#define INODE_IBLOCK_OFF    0x28
#define INODE_IBLOCK_SIZE   60
#define INODE_GENERATION_OFF 0x64
#define INODE_SIZE_HI_OFF   0x6C
#define INODE_BLOCKS_HI_OFF 0x74   /* osd2.linux2.l_i_blocks_hi */

#define EH_ENTRIES_OFF 0x02
#define EH_MAX_OFF     0x04
#define EH_DEPTH_OFF   0x06

#define EE_BLOCK_OFF    0x00
#define EE_LEN_OFF      0x04
#define EE_START_HI_OFF 0x06
#define EE_START_LO_OFF 0x08

#define EI_BLOCK_OFF    0x00
#define EI_LEAF_LO_OFF  0x04
#define EI_LEAF_HI_OFF  0x08

#define EXT4_MAX_DEPTH  5

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

/* ── The inode ────────────────────────────────────────────────────────────── */

/* Where inode `ino` sits, as a byte offset into the image. */
static int inode_offset(ext4_wfs *fs, uint32_t ino, off_t *out) {
    if (ino == 0 || fs->inodes_per_group == 0) return EXTW_ERR_FORMAT;
    uint32_t group = (ino - 1) / fs->inodes_per_group;
    uint32_t index = (ino - 1) % fs->inodes_per_group;
    if (group >= fs->groups) return EXTW_ERR_FORMAT;

    const uint8_t *d = fs->desc + (size_t)group * fs->desc_size;
    uint64_t itable = rd32(d + EXT4_GD_INODE_TABLE_LO_OFF);
    if (fs->desc_size >= 64)
        itable |= (uint64_t)rd32(d + EXT4_GD_INODE_TABLE_HI_OFF) << 32;
    if (itable == 0 || itable >= fs->blocks_count) return EXTW_ERR_FORMAT;

    *out = (off_t)(itable * fs->block_size + (uint64_t)index * fs->inode_size);
    return EXTW_OK;
}

static int read_inode(ext4_wfs *fs, uint32_t ino, uint8_t *buf) {
    off_t at;
    int rc = inode_offset(fs, ino, &at);
    if (rc != EXTW_OK) return rc;
    if (fseeko(fs->fp, at, SEEK_SET)) return EXTW_ERR_IO;
    if (fread(buf, 1, fs->inode_size, fs->fp) != fs->inode_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

/* Stamps the checksum in before writing, so the two can never be written apart. */
static int write_inode(ext4_wfs *fs, uint32_t ino, uint8_t *buf) {
    uint32_t generation = rd32(buf + INODE_GENERATION_OFF);
    uint32_t c = ext4_inode_csum(fs->csum_seed, ino, generation, buf, fs->inode_size);

    wr16(buf + EXT4_INODE_CSUM_LO_OFF, (uint16_t)c);
    if (fs->inode_size > 128 && ext4_inode_has_checksum_hi(buf))
        wr16(buf + EXT4_INODE_CSUM_HI_OFF, (uint16_t)(c >> 16));

    off_t at;
    int rc = inode_offset(fs, ino, &at);
    if (rc != EXTW_OK) return rc;
    if (fseeko(fs->fp, at, SEEK_SET)) return EXTW_ERR_IO;
    if (fwrite(buf, 1, fs->inode_size, fs->fp) != fs->inode_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

static uint64_t inode_size_get(const uint8_t *inode) {
    return (uint64_t)rd32(inode + INODE_SIZE_LO_OFF) |
           ((uint64_t)rd32(inode + INODE_SIZE_HI_OFF) << 32);
}
static void inode_size_set(uint8_t *inode, uint64_t v) {
    wr32(inode + INODE_SIZE_LO_OFF, (uint32_t)v);
    wr32(inode + INODE_SIZE_HI_OFF, (uint32_t)(v >> 32));
}

/* i_blocks counts 512-byte sectors, not filesystem blocks, and spills into osd2
 * past 2^32 sectors. Metadata blocks are counted here too, which is why this
 * adjusts the stored value rather than deriving it from the extent tree. */
static uint64_t inode_blocks_get(const uint8_t *inode) {
    return (uint64_t)rd32(inode + INODE_BLOCKS_LO_OFF) |
           ((uint64_t)rd16(inode + INODE_BLOCKS_HI_OFF) << 32);
}
static void inode_blocks_set(uint8_t *inode, uint64_t v) {
    wr32(inode + INODE_BLOCKS_LO_OFF, (uint32_t)v);
    wr16(inode + INODE_BLOCKS_HI_OFF, (uint16_t)(v >> 32));
}

/* ── The extent root ──────────────────────────────────────────────────────── */

static int write_data_block(ext4_wfs *fs, uint64_t block, const uint8_t *buf) {
    if (fseeko(fs->fp, (off_t)block * fs->block_size, SEEK_SET)) return EXTW_ERR_IO;
    if (fwrite(buf, 1, fs->block_size, fs->fp) != fs->block_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

static int read_block_at(ext4_wfs *fs, uint64_t block, uint8_t *buf) {
    if (fseeko(fs->fp, (off_t)block * fs->block_size, SEEK_SET)) return EXTW_ERR_IO;
    if (fread(buf, 1, fs->block_size, fs->fp) != fs->block_size) return EXTW_ERR_IO;
    return EXTW_OK;
}

static uint64_t ee_physical(const uint8_t *e) {
    return (uint64_t)rd32(e + EE_START_LO_OFF) |
           ((uint64_t)rd16(e + EE_START_HI_OFF) << 32);
}
static void ee_set_physical(uint8_t *e, uint64_t p) {
    wr32(e + EE_START_LO_OFF, (uint32_t)p);
    wr16(e + EE_START_HI_OFF, (uint16_t)(p >> 32));
}

static uint64_t ei_child(const uint8_t *e) {
    return (uint64_t)rd32(e + EI_LEAF_LO_OFF) |
           ((uint64_t)rd16(e + EI_LEAF_HI_OFF) << 32);
}
static void ei_set_child(uint8_t *e, uint64_t blk) {
    wr32(e + EI_LEAF_LO_OFF, (uint32_t)blk);
    wr16(e + EI_LEAF_HI_OFF, (uint16_t)(blk >> 32));
}

/*
 * How many entries fit in a node that lives in its own block: a 12-byte header,
 * the entries, and a 4-byte tail holding the block's checksum.
 *
 * Subtracting the tail is what makes this correct rather than lucky. At 1 KiB,
 * 2 KiB and 4 KiB the two forms happen to agree - 84, 169 and 340 - because the
 * division rounds the tail away anyway, and every extent block mke2fs wrote in
 * the corpus carries exactly those values. A block size where they diverge would
 * put the tail past the end of the block.
 */
static uint32_t entries_per_extent_block(const ext4_wfs *fs) {
    return (fs->block_size - 12 - 4) / 12;
}

/* Stamps the tail checksum in before writing, so the two cannot be written
 * apart. The root inside an inode has no tail - the inode's checksum covers it -
 * which is why this only ever runs on a node that owns a block. */
static int write_extent_block(ext4_wfs *fs, uint64_t blk, uint8_t *buf,
                              uint32_t inode_seed) {
    uint32_t off = ext4_extent_tail_offset(buf);
    if (off + 4 > fs->block_size) return EXTW_ERR_FORMAT;
    wr32(buf + off, ext4_extent_block_csum(inode_seed, buf, fs->block_size));
    return write_data_block(fs, blk, buf);
}

/*
 * The rightmost path from the root down to the leaf. That is where an append
 * belongs, and also where the tree has to grow when the leaf fills.
 *
 * Level 0 is the root inside the inode, which owns no block; every level below it
 * does. The whole path is kept rather than just the leaf, because making room for
 * a new leaf means adding an index entry to its parent - and the parent may need
 * room made for it in turn.
 */
typedef struct {
    int      depth;                       /* buf[depth] is the leaf */
    uint64_t block[EXT4_MAX_DEPTH + 1];   /* 0 at level 0 - the root has no block */
    uint8_t *buf[EXT4_MAX_DEPTH + 1];
} rightmost_path;

static uint16_t node_entries(const uint8_t *n)  { return rd16(n + EH_ENTRIES_OFF); }
static uint16_t node_capacity(const uint8_t *n) { return rd16(n + EH_MAX_OFF); }

/* Writes one level back. Level 0 lives in the inode and goes out with it. */
static int flush_level(ext4_wfs *fs, rightmost_path *p, int level, uint32_t seed) {
    if (level == 0) return EXTW_OK;
    return write_extent_block(fs, p->block[level], p->buf[level], seed);
}

/* Descends the rightmost edge, giving each level a buffer of its own. */
static int find_rightmost_path(ext4_wfs *fs, uint8_t *root, uint8_t *storage,
                               rightmost_path *p) {
    uint16_t depth = rd16(root + EH_DEPTH_OFF);
    if (depth > EXT4_MAX_DEPTH) return EXTW_ERR_DEPTH;

    p->depth    = depth;
    p->block[0] = 0;
    p->buf[0]   = root;

    uint8_t *node = root;
    for (uint16_t level = 1; level <= depth; level++) {
        uint16_t ent = node_entries(node);
        if (ent == 0) return EXTW_ERR_FORMAT;
        uint64_t blk = ei_child(node + 12 + (size_t)(ent - 1) * 12);
        if (blk == 0 || blk >= fs->blocks_count) return EXTW_ERR_FORMAT;

        uint8_t *slot = storage + (size_t)(level - 1) * fs->block_size;
        if (read_block_at(fs, blk, slot) != EXTW_OK) return EXTW_ERR_IO;
        if (rd16(slot) != EXT4_EXTENT_MAGIC) return EXTW_ERR_FORMAT;
        /* A child's depth is fixed by where it hangs; disagreeing means the tree
         * is not the shape its root claims. */
        if (rd16(slot + EH_DEPTH_OFF) != depth - level) return EXTW_ERR_FORMAT;

        p->block[level] = blk;
        p->buf[level]   = slot;
        node = slot;
    }
    return EXTW_OK;
}

/*
 * Pushes everything the root holds down into a block of its own, leaving the root
 * with a single index entry pointing at it and one level deeper.
 *
 * Written for any depth rather than just the first split: the new block inherits
 * the root's depth, so moving four leaf extents down and moving four index
 * entries down are the same operation. What the root holds is only ever four
 * entries, since its 60 bytes of i_block leave room for no more.
 */
static int split_root(ext4_wfs *fs, uint8_t *root, uint8_t *scratch,
                      uint32_t inode_seed, uint64_t goal, uint64_t *meta_blocks) {
    uint16_t ent   = rd16(root + EH_ENTRIES_OFF);
    uint16_t depth = rd16(root + EH_DEPTH_OFF);
    if (ent == 0) return EXTW_ERR_FORMAT;

    int64_t blk = ext4_alloc_block_goal(fs, goal);
    if (blk < 0) return EXTW_ERR_NOSPACE;

    memset(scratch, 0, fs->block_size);
    wr16(scratch, EXT4_EXTENT_MAGIC);
    wr16(scratch + EH_ENTRIES_OFF, ent);
    wr16(scratch + EH_MAX_OFF, (uint16_t)entries_per_extent_block(fs));
    wr16(scratch + EH_DEPTH_OFF, depth);
    memcpy(scratch + 12, root + 12, (size_t)ent * 12);

    int rc = write_extent_block(fs, (uint64_t)blk, scratch, inode_seed);
    if (rc != EXTW_OK) return rc;

    /* The index's logical key is the first block its subtree covers, which is
     * whatever the root's first entry already keyed on. Read before the clear. */
    uint32_t first_logical = rd32(root + 12);

    memset(root + 12, 0, INODE_IBLOCK_SIZE - 12);
    wr16(root + EH_ENTRIES_OFF, 1);
    wr16(root + EH_DEPTH_OFF, (uint16_t)(depth + 1));
    wr32(root + 12 + EI_BLOCK_OFF, first_logical);
    ei_set_child(root + 12, (uint64_t)blk);

    (*meta_blocks)++;
    return EXTW_OK;
}

/*
 * Leaves the path's leaf with room for one more extent.
 *
 * Appending only ever adds at the end, so a full leaf does not need its contents
 * divided down the middle: a fresh empty leaf beside it is enough, and the full
 * one is left exactly as it was. What that costs is an index entry in the parent,
 * and when the parent is full the same question moves up a level.
 *
 * A full root is answered by pushing it down into a block of its own, which
 * returns it to one entry and three free slots. A full index block below the root
 * is refused - it needs a sibling of its own and an entry in *its* parent, which
 * is the same problem one level up and is not written yet.
 *
 * The new leaf reuses the buffer the full one occupied. That is safe only because
 * the full leaf is never modified here and was written back when it was last
 * touched, so the copy on disk is already current.
 */
static int grow_right_edge(ext4_wfs *fs, uint8_t *root, uint8_t *storage,
                           rightmost_path *p, uint32_t next_logical,
                           uint32_t inode_seed, uint64_t *meta_blocks) {
    for (int guard = 0; guard <= EXT4_MAX_DEPTH + 1; guard++) {
        uint8_t *leaf = p->buf[p->depth];
        if (node_entries(leaf) < node_capacity(leaf)) return EXTW_OK;

        int rc;
        /* The leaf is the root itself: pushing the root down is the whole fix. */
        if (p->depth == 0) {
            rc = split_root(fs, root, storage, inode_seed, 0, meta_blocks);
            if (rc != EXTW_OK) return rc;
            rc = find_rightmost_path(fs, root, storage, p);
            if (rc != EXTW_OK) return rc;
            continue;
        }

        int parent = p->depth - 1;
        if (node_entries(p->buf[parent]) >= node_capacity(p->buf[parent])) {
            if (parent != 0) return EXTW_ERR_FULL;
            rc = split_root(fs, root, storage, inode_seed, 0, meta_blocks);
            if (rc != EXTW_OK) return rc;
            rc = find_rightmost_path(fs, root, storage, p);
            if (rc != EXTW_OK) return rc;
            continue;
        }

        int64_t blk = ext4_alloc_block_goal(fs, p->block[p->depth] + 1);
        if (blk < 0) return EXTW_ERR_NOSPACE;

        memset(leaf, 0, fs->block_size);
        wr16(leaf, EXT4_EXTENT_MAGIC);
        wr16(leaf + EH_ENTRIES_OFF, 0);
        wr16(leaf + EH_MAX_OFF, (uint16_t)entries_per_extent_block(fs));
        wr16(leaf + EH_DEPTH_OFF, 0);
        p->block[p->depth] = (uint64_t)blk;

        /* The index key is the first logical block its subtree covers, which for
         * a leaf created to hold the next append is that block. */
        uint8_t *pn   = p->buf[parent];
        uint16_t pe   = node_entries(pn);
        uint8_t *slot = pn + 12 + (size_t)pe * 12;
        wr32(slot + EI_BLOCK_OFF, next_logical);
        ei_set_child(slot, (uint64_t)blk);
        wr16(pn + EH_ENTRIES_OFF, (uint16_t)(pe + 1));

        rc = flush_level(fs, p, parent, inode_seed);
        if (rc != EXTW_OK) return rc;
        (*meta_blocks)++;
        return EXTW_OK;
    }
    return EXTW_ERR_FULL;
}

int ext4_append_blocks(ext4_wfs *fs, uint32_t ino, uint32_t count,
                       ext4_fill_fn fill, void *user, uint32_t *appended) {
    uint8_t *inode   = malloc(fs->inode_size);
    uint8_t *block   = malloc(fs->block_size);
    /* One buffer per level below the root, so the whole rightmost path can be
     * held at once - growing the tree needs the parent as well as the leaf. */
    uint8_t *storage = malloc((size_t)EXT4_MAX_DEPTH * fs->block_size);
    if (!inode || !block || !storage) {
        free(inode); free(block); free(storage);
        return EXTW_ERR_IO;
    }

    int rc = read_inode(fs, ino, inode);
    if (rc != EXTW_OK) goto out;

    rc = EXTW_ERR_FORMAT;
    if (!(rd32(inode + INODE_FLAGS_OFF) & EXT4_INODE_FLAG_EXTENTS)) goto out;

    uint8_t *root = inode + INODE_IBLOCK_OFF;
    if (rd16(root) != EXT4_EXTENT_MAGIC) goto out;
    if (rd16(root + EH_MAX_OFF) > (INODE_IBLOCK_SIZE - 12) / 12 ||
        rd16(root + EH_ENTRIES_OFF) > rd16(root + EH_MAX_OFF)) goto out;

    uint32_t generation = rd32(inode + INODE_GENERATION_OFF);
    uint32_t inode_seed = ext4_inode_csum_seed(fs->csum_seed, ino, generation);

    rightmost_path p;
    rc = find_rightmost_path(fs, root, storage, &p);
    if (rc != EXTW_OK) goto out;

    /*
     * Where the file currently ends, in logical blocks - the later of what the
     * extents map and what i_size claims, because the two disagree routinely.
     *
     * A file ending in a hole has i_size past its last extent, and appending at
     * the last extent instead would write into the middle of that hole and then
     * set a size smaller than the one it started with. A file whose length is not
     * a multiple of the block size has i_size short of a full block, and rounding
     * down would overwrite the tail of its own last block.
     */
    uint32_t next_logical = 0;
    uint16_t ent = node_entries(p.buf[p.depth]);
    if (ent > 0) {
        const uint8_t *last = p.buf[p.depth] + 12 + (size_t)(ent - 1) * 12;
        uint16_t raw = rd16(last + EE_LEN_OFF);
        uint32_t len = raw > EXT4_MAX_INIT_LEN ? raw - EXT4_MAX_INIT_LEN : raw;
        next_logical = rd32(last + EE_BLOCK_OFF) + len;
    }
    uint32_t by_size = (uint32_t)((inode_size_get(inode) + fs->block_size - 1) /
                                  fs->block_size);
    if (by_size > next_logical) next_logical = by_size;

    uint64_t data_blocks = 0, meta_blocks = 0;
    if (appended) *appended = 0;
    for (uint32_t i = 0; i < count; i++) {
        /* Re-found every iteration, because growing the tree moves it. */
        rc = find_rightmost_path(fs, root, storage, &p);
        if (rc != EXTW_OK) goto out;

        uint8_t *leaf     = p.buf[p.depth];
        ent               = node_entries(leaf);
        uint8_t *last     = ent ? leaf + 12 + (size_t)(ent - 1) * 12 : NULL;
        uint16_t last_raw = last ? rd16(last + EE_LEN_OFF) : 0;
        int last_uninit   = last_raw > EXT4_MAX_INIT_LEN;
        uint32_t last_len = last_uninit ? last_raw - EXT4_MAX_INIT_LEN : last_raw;
        uint64_t goal     = last ? ee_physical(last) + last_len : 0;

        int64_t got = ext4_alloc_block_goal(fs, goal);
        if (got < 0) { rc = EXTW_ERR_NOSPACE; break; }

        if (fill && fill(user, next_logical, block) != 0) { rc = EXTW_ERR_IO; break; }
        else if (!fill) memset(block, 0, fs->block_size);
        rc = write_data_block(fs, (uint64_t)got, block);
        if (rc != EXTW_OK) break;

        /*
         * Merge into the last extent only when the new block continues it in both
         * senses. Physical adjacency alone is not enough: when the file ends in a
         * hole, next_logical is past where the last extent stops, and stretching
         * ee_len over that gap would claim the hole as data.
         *
         * An uninitialised extent is never merged into either - its length field
         * carries the preallocation marker, and data written into it would still
         * read back as zeroes until that is cleared.
         */
        int logically_next = last &&
            rd32(last + EE_BLOCK_OFF) + last_len == next_logical;

        if (last && logically_next && !last_uninit && (uint64_t)got == goal &&
            last_len + 1 <= EXT4_MAX_INIT_LEN) {
            wr16(last + EE_LEN_OFF, (uint16_t)(last_len + 1));
        } else {
            /* No room for another entry. The block just taken is given back
             * rather than stranded if the tree cannot be grown. */
            rc = grow_right_edge(fs, root, storage, &p, next_logical,
                                 inode_seed, &meta_blocks);
            if (rc != EXTW_OK) { ext4_free_block(fs, (uint64_t)got); break; }

            leaf = p.buf[p.depth];
            ent  = node_entries(leaf);
            uint8_t *slot = leaf + 12 + (size_t)ent * 12;
            wr32(slot + EE_BLOCK_OFF, next_logical);
            wr16(slot + EE_LEN_OFF, 1);
            ee_set_physical(slot, (uint64_t)got);
            wr16(leaf + EH_ENTRIES_OFF, (uint16_t)(ent + 1));
        }

        /* A leaf with a block of its own goes back with its checksum restamped.
         * The root rides along in the inode, written once at the end. */
        rc = flush_level(fs, &p, p.depth, inode_seed);
        if (rc != EXTW_OK) break;

        next_logical++;
        data_blocks++;
    }

    /*
     * Whatever the loop ended on, what it managed is committed.
     *
     * Bailing out without this was the wrong shape: the bitmaps and the leaf
     * blocks are written as it goes, so an abandoned run left blocks marked in
     * use and referenced by the tree, while i_size, i_blocks and the free counts
     * still described the file as it had been. Running out of space part way is
     * ordinary - a short write - and it must leave a filesystem that checks out,
     * not one that needs repairing.
     */
    int append_rc = rc;

    inode_size_set(inode, (uint64_t)next_logical * fs->block_size);
    /* i_blocks counts the tree's own blocks too, not just the file's data. */
    inode_blocks_set(inode, inode_blocks_get(inode) +
                     (data_blocks + meta_blocks) * (fs->block_size / 512));

    rc = write_inode(fs, ino, inode);
    if (rc == EXTW_OK) rc = ext4_fs_flush(fs) ? EXTW_ERR_IO : EXTW_OK;
    if (append_rc != EXTW_OK) rc = append_rc;
    if (appended) *appended = (uint32_t)data_blocks;

out:
    free(inode);
    free(block);
    free(storage);
    return rc;
}
