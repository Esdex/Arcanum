/*
 * Inode allocation.
 *
 * Clean-room, from the published on-disk format. The same shape as block
 * allocation - find a free bit, take it, move two counters, restamp three
 * checksums - with one thing blocks have no equivalent of.
 *
 * bg_itable_unused says how many inodes at the *end* of a group's inode table
 * have never been used. It is a promise that nothing needs to read them, which is
 * what lets a filesystem be created without writing a whole inode table. Taking
 * an inode from inside that region breaks the promise, so the region has to be
 * shortened to exclude it - and every inode it used to cover, up to and including
 * the one being taken, has to be zeroed first. Shortening it without zeroing
 * leaves whatever the disk happened to hold looking like live inodes: a size, a
 * link count and a set of extents pointing at blocks belonging to somebody else.
 *
 * That failure has no symptom at the point it happens. Every checksum still
 * matches, because the inodes in question are not covered by any of them until
 * something claims them.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_alloc.h"
#include "ext4_csum.h"

#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

/*
 * Inodes 1 through 10 are spoken for by the format - the bad-block inode, the
 * root directory, the journal and so on - and mke2fs marks them in the bitmap of
 * group 0. The scan below finds them already set and steps over them, so this is
 * only needed to refuse a free that would hand one back.
 */
#define EXT4_FIRST_REAL_INO 11

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

static uint8_t *group_desc(const ext4_wfs *fs, uint32_t g) {
    return fs->desc + (size_t)g * fs->desc_size;
}
static int is_64bit(const ext4_wfs *fs) { return fs->desc_size >= 64; }

static uint64_t inode_bitmap_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_INODE_BITMAP_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_INODE_BITMAP_HI_OFF) << 32;
    return b;
}
static uint64_t inode_table_block(const ext4_wfs *fs, const uint8_t *d) {
    uint64_t b = rd32(d + EXT4_GD_INODE_TABLE_LO_OFF);
    if (is_64bit(fs)) b |= (uint64_t)rd32(d + EXT4_GD_INODE_TABLE_HI_OFF) << 32;
    return b;
}

static uint32_t group_free_inodes(const ext4_wfs *fs, const uint8_t *d) {
    uint32_t v = rd16(d + EXT4_GD_FREE_INODES_LO_OFF);
    if (is_64bit(fs)) v |= (uint32_t)rd16(d + EXT4_GD_FREE_INODES_HI_OFF) << 16;
    return v;
}
static void group_set_free_inodes(const ext4_wfs *fs, uint8_t *d, uint32_t v) {
    wr16(d + EXT4_GD_FREE_INODES_LO_OFF, (uint16_t)v);
    if (is_64bit(fs)) wr16(d + EXT4_GD_FREE_INODES_HI_OFF, (uint16_t)(v >> 16));
}

static uint32_t group_itable_unused(const ext4_wfs *fs, const uint8_t *d) {
    uint32_t v = rd16(d + EXT4_GD_ITABLE_UNUSED_LO_OFF);
    if (is_64bit(fs)) v |= (uint32_t)rd16(d + EXT4_GD_ITABLE_UNUSED_HI_OFF) << 16;
    return v;
}
static void group_set_itable_unused(const ext4_wfs *fs, uint8_t *d, uint32_t v) {
    wr16(d + EXT4_GD_ITABLE_UNUSED_LO_OFF, (uint16_t)v);
    if (is_64bit(fs)) wr16(d + EXT4_GD_ITABLE_UNUSED_HI_OFF, (uint16_t)(v >> 16));
}

uint32_t ext4_sb_free_inodes(const ext4_wfs *fs) {
    return rd32(fs->sb + EXT4_SB_FREE_INODES_OFF);
}
static void sb_set_free_inodes(ext4_wfs *fs, uint32_t v) {
    wr32(fs->sb + EXT4_SB_FREE_INODES_OFF, v);
}

static int read_inode_bitmap(ext4_wfs *fs, const uint8_t *d, uint8_t *buf) {
    uint64_t at = inode_bitmap_block(fs, d) * (uint64_t)fs->block_size;
    return ext4_io_pread(&fs->io, at, buf, fs->inodes_per_group / 8);
}
static int write_inode_bitmap(ext4_wfs *fs, const uint8_t *d, const uint8_t *buf) {
    uint64_t at = inode_bitmap_block(fs, d) * (uint64_t)fs->block_size;
    return ext4_io_pwrite(&fs->io, at, buf, fs->inodes_per_group / 8);
}

static void store_inode_bitmap_csum(const ext4_wfs *fs, uint8_t *d, const uint8_t *buf) {
    uint32_t c = ext4_bitmap_csum(fs->csum_seed, buf, fs->inodes_per_group / 8);
    wr16(d + EXT4_GD_IBITMAP_CSUM_LO_OFF, (uint16_t)c);
    if (is_64bit(fs)) wr16(d + EXT4_GD_IBITMAP_CSUM_HI_OFF, (uint16_t)(c >> 16));
}

static void store_desc_csum(const ext4_wfs *fs, uint32_t g, uint8_t *d) {
    wr16(d + EXT4_GD_CSUM_OFF, 0);
    wr16(d + EXT4_GD_CSUM_OFF,
         (uint16_t)ext4_group_desc_csum(fs->csum_seed, g, d, fs->desc_size));
}

/* Zeroes inode table entries [from, to] within a group, so that shortening
 * bg_itable_unused past them cannot expose whatever the disk already held. */
static int zero_inodes(ext4_wfs *fs, const uint8_t *d, uint32_t from, uint32_t to) {
    uint8_t *blank = calloc(1, fs->inode_size);
    if (!blank) return -1;
    uint64_t base = inode_table_block(fs, d) * (uint64_t)fs->block_size;
    int rc = 0;
    for (uint32_t i = from; i <= to && rc == 0; i++) {
        if (ext4_io_pwrite(&fs->io, base + (uint64_t)i * fs->inode_size,
                           blank, fs->inode_size)) rc = -1;
    }
    free(blank);
    return rc;
}

int64_t ext4_alloc_inode(ext4_wfs *fs) {
    uint32_t ipg = fs->inodes_per_group;
    if (ipg == 0 || fs->inode_size == 0) return -1;

    uint8_t *bitmap = malloc(ipg / 8);
    if (!bitmap) return -1;
    int64_t result = -1;

    for (uint32_t g = 0; g < fs->groups; g++) {
        uint8_t *d = group_desc(fs, g);
        /* INODE_UNINIT means this bitmap was never written, so what is on disk is
         * not one. Synthesising it is its own piece of work; refused for now, the
         * same way BLOCK_UNINIT is on the block side. */
        if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_INODE_UNINIT) continue;
        if (group_free_inodes(fs, d) == 0) continue;
        if (read_inode_bitmap(fs, d, bitmap)) break;

        for (uint32_t i = 0; i < ipg; i++) {
            if (bitmap[i >> 3] & (1u << (i & 7))) continue;

            /* Shorten the never-used tail before anything can read into it, and
             * blank what that exposes. Done first: if this fails the inode is
             * still free and nothing has been promised about it. */
            uint32_t unused = group_itable_unused(fs, d);
            uint32_t first_unused = ipg - unused;
            if (unused > 0 && i >= first_unused) {
                if (zero_inodes(fs, d, first_unused, i)) goto done;
                group_set_itable_unused(fs, d, ipg - (i + 1));
            } else if (zero_inodes(fs, d, i, i)) {
                goto done;
            }

            bitmap[i >> 3] |= (uint8_t)(1u << (i & 7));
            if (write_inode_bitmap(fs, d, bitmap)) goto done;
            store_inode_bitmap_csum(fs, d, bitmap);
            group_set_free_inodes(fs, d, group_free_inodes(fs, d) - 1);
            store_desc_csum(fs, g, d);
            sb_set_free_inodes(fs, ext4_sb_free_inodes(fs) - 1);

            result = (int64_t)(g * ipg + i + 1);   /* inode numbers start at 1 */
            goto done;
        }
        /* The free count promised an inode the bitmap does not have. */
        break;
    }

done:
    free(bitmap);
    return result;
}

int ext4_free_inode(ext4_wfs *fs, uint32_t ino) {
    uint32_t ipg = fs->inodes_per_group;
    if (ipg == 0 || ino < EXT4_FIRST_REAL_INO) return -1;
    if (ino > rd32(fs->sb + EXT4_SB_INODES_COUNT_OFF)) return -1;

    uint32_t g = (ino - 1) / ipg;
    uint32_t i = (ino - 1) % ipg;
    if (g >= fs->groups) return -1;

    uint8_t *d = group_desc(fs, g);
    if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_INODE_UNINIT) return -1;

    uint8_t *bitmap = malloc(ipg / 8);
    if (!bitmap) return -1;
    int rc = -1;

    if (read_inode_bitmap(fs, d, bitmap)) goto done;
    if (!(bitmap[i >> 3] & (1u << (i & 7)))) goto done;   /* already free */

    bitmap[i >> 3] &= (uint8_t)~(1u << (i & 7));
    if (write_inode_bitmap(fs, d, bitmap)) goto done;
    store_inode_bitmap_csum(fs, d, bitmap);
    group_set_free_inodes(fs, d, group_free_inodes(fs, d) + 1);
    store_desc_csum(fs, g, d);
    sb_set_free_inodes(fs, ext4_sb_free_inodes(fs) + 1);

    /* bg_itable_unused is deliberately left alone. It counts inodes never used,
     * and this one has been - growing it back would re-promise that the table's
     * tail is untouched when it is not. */
    rc = 0;

done:
    free(bitmap);
    return rc;
}
