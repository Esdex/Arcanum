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
    uint32_t first_db  = rd32(sb + 0x14);
    uint64_t blocks    = (uint64_t)rd32(sb + 0x04) | ((uint64_t)rd32(sb + 0x150) << 32);
    uint32_t groups    = (uint32_t)((blocks - first_db + bpg - 1) / bpg);

    int bad = 0, checked_desc = 0, checked_bmap = 0, skipped_uninit = 0;

    if (ext4_superblock_csum(sb) != rd32(sb + EXT4_SB_CSUM_OFF)) {
        printf("superblock checksum mismatch\n");
        bad++;
    }

    uint8_t *desc_area = malloc((size_t)groups * desc_size);
    uint8_t *bitmap    = malloc(bpg / 8);
    if (!desc_area || !bitmap) return 2;
    if (fseeko(fp, (off_t)(first_db + 1) * bs, SEEK_SET)) return 2;
    if (fread(desc_area, 1, (size_t)groups * desc_size, fp) != (size_t)groups * desc_size) return 2;

    for (uint32_t g = 0; g < groups; g++) {
        const uint8_t *d = desc_area + (size_t)g * desc_size;
        checked_desc++;
        if (ext4_group_desc_csum(seed, g, d, desc_size) != rd16(d + EXT4_GD_CSUM_OFF)) {
            printf("group %u: descriptor checksum mismatch\n", g);
            bad++;
        }

        /* BLOCK_UNINIT (bg_flags bit 0) means the bitmap block was never written
         * out, so its contents are not a bitmap and checking them is meaningless. */
        if (rd16(d + 0x12) & 0x0001) { skipped_uninit++; continue; }

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

    printf("%s: %u descriptors, %u bitmaps checked, %u uninit skipped, %d bad\n",
           argv[1], checked_desc, checked_bmap, skipped_uninit, bad);
    free(desc_area); free(bitmap); fclose(fp);
    return bad ? 1 : 0;
}
