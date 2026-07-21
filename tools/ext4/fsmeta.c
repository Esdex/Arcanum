/*
 * Verifies every filesystem-wide checksum on an image without writing anything:
 * the superblock, every group descriptor, and every initialised block bitmap.
 *
 * This is the last read-only rehearsal before allocation. Each of these has to be
 * recomputed when a block is taken, and predicting the values e2fsprogs already
 * wrote proves the recipe before anything depends on it.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_csum.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

/* bg_flags, at offset 0x12 of the group descriptor. Named rather than inlined
 * because the two uninit bits are easy to mistake for each other. */
#define EXT4_BG_INODE_UNINIT  0x0001  /* inode bitmap and table are not initialised */
#define EXT4_BG_BLOCK_UNINIT  0x0002  /* block bitmap is not initialised */
#define EXT4_BG_INODE_ZEROED  0x0004  /* inode table has been zeroed */

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s <image>\n", argv[0]); return 2; }
    FILE *fp = fopen(argv[1], "rb");
    if (!fp) { perror("open"); return 2; }

    uint8_t sb[1024];
    if (fseeko(fp, 1024, SEEK_SET) || fread(sb, 1, 1024, fp) != 1024) return 2;

    uint32_t bs        = 1024u << rd32(sb + 0x18);
    uint32_t bpg       = rd32(sb + 0x20);
    uint32_t incompat  = rd32(sb + 0x60);
    uint32_t desc_size = (incompat & 0x80) ? rd16(sb + 0xFE) : 32;
    uint32_t seed      = rd32(sb + 0x270);
    uint32_t ipg       = rd32(sb + 0x28);
    uint32_t first_db  = rd32(sb + 0x14);
    uint64_t blocks    = (uint64_t)rd32(sb + 0x04) | ((uint64_t)rd32(sb + 0x150) << 32);
    uint32_t groups    = (uint32_t)((blocks - first_db + bpg - 1) / bpg);

    int bad = 0, checked_desc = 0, checked_bmap = 0, skipped_uninit = 0;
    int checked_imap = 0, skipped_iuninit = 0;

    if (ext4_superblock_csum(sb) != rd32(sb + EXT4_SB_CSUM_OFF)) {
        printf("superblock checksum mismatch\n");
        bad++;
    }

    uint8_t *desc_area = malloc((size_t)groups * desc_size);
    uint8_t *bitmap    = malloc(bpg / 8);
    uint8_t *ibitmap   = malloc(ipg / 8);
    if (!desc_area || !bitmap || !ibitmap) return 2;
    if (fseeko(fp, (off_t)(first_db + 1) * bs, SEEK_SET)) return 2;
    if (fread(desc_area, 1, (size_t)groups * desc_size, fp) != (size_t)groups * desc_size) return 2;

    for (uint32_t g = 0; g < groups; g++) {
        const uint8_t *d = desc_area + (size_t)g * desc_size;
        checked_desc++;
        if (ext4_group_desc_csum(seed, g, d, desc_size) != rd16(d + EXT4_GD_CSUM_OFF)) {
            printf("group %u: descriptor checksum mismatch\n", g);
            bad++;
        }

        /*
         * The inode bitmap, checked before the block bitmap because the two are
         * skipped for different reasons and conflating them is what went wrong
         * here once already. INODE_UNINIT is what makes an inode bitmap
         * meaningless; BLOCK_UNINIT does the same for the block bitmap. A group
         * can carry either without the other.
         *
         * The checksum covers inodes_per_group / 8 bytes, which is the used part
         * of the block rather than all of it - the bitmap rarely fills its block.
         */
        if (rd16(d + 0x12) & EXT4_BG_INODE_UNINIT) {
            skipped_iuninit++;
        } else {
            uint64_t imap = rd32(d + 0x04) |
                            ((desc_size >= 64) ? ((uint64_t)rd32(d + 0x24) << 32) : 0);
            if (fseeko(fp, (off_t)imap * bs, SEEK_SET)) return 2;
            if (fread(ibitmap, 1, ipg / 8, fp) != ipg / 8) return 2;

            uint32_t iwant = ext4_bitmap_csum(seed, ibitmap, ipg / 8);
            uint32_t igot  = rd16(d + EXT4_GD_IBITMAP_CSUM_LO_OFF);
            if (desc_size >= 64) igot |= (uint32_t)rd16(d + EXT4_GD_IBITMAP_CSUM_HI_OFF) << 16;
            else                 iwant &= 0xFFFFu;
            checked_imap++;
            if (iwant != igot) {
                printf("group %u: inode bitmap checksum mismatch (want %08X got %08X)\n",
                       g, iwant, igot);
                bad++;
            }
        }

        /* BLOCK_UNINIT means the bitmap block was never written out, so its
         * contents are not a bitmap and checking them is meaningless.
         *
         * It is bit 1 of bg_flags, not bit 0 - bit 0 is INODE_UNINIT, which says
         * nothing about the block bitmap. Testing bit 0 here still passed on every
         * image, because mke2fs sets INODE_UNINIT on every group it sets
         * BLOCK_UNINIT on, so the wrong test skipped a superset of the right one.
         * What it cost was coverage, silently: 55 of 160 bitmaps checked instead of
         * 141, with the other 86 reported as "uninit skipped". */
        if (rd16(d + 0x12) & EXT4_BG_BLOCK_UNINIT) { skipped_uninit++; continue; }

        uint64_t bmap = rd32(d) | ((desc_size >= 64) ? ((uint64_t)rd32(d + 0x20) << 32) : 0);
        if (fseeko(fp, (off_t)bmap * bs, SEEK_SET)) return 2;
        if (fread(bitmap, 1, bpg / 8, fp) != bpg / 8) return 2;

        uint32_t want = ext4_bitmap_csum(seed, bitmap, bpg / 8);
        uint32_t got  = rd16(d + EXT4_GD_BBITMAP_CSUM_LO_OFF);
        if (desc_size >= 64) got |= (uint32_t)rd16(d + EXT4_GD_BBITMAP_CSUM_HI_OFF) << 16;
        else                 want &= 0xFFFFu;
        checked_bmap++;
        if (want != got) {
            printf("group %u: block bitmap checksum mismatch (want %08X got %08X)\n",
                   g, want, got);
            bad++;
        }
    }

    printf("%s: %u descriptors, %u block bitmaps (%u uninit skipped), "
           "%u inode bitmaps (%u uninit skipped), %d bad\n",
           argv[1], checked_desc, checked_bmap, skipped_uninit,
           checked_imap, skipped_iuninit, bad);
    free(desc_area); free(bitmap); free(ibitmap); fclose(fp);
    return bad ? 1 : 0;
}
