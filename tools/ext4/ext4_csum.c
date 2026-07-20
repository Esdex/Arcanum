/*
 * crc32c (Castagnoli) and the ext4 checksum seeds derived from it.
 *
 * Clean-room, from the published definitions: the polynomial is the standard
 * Castagnoli one and the seeding rules come from the ext4 on-disk format, not
 * from anyone's implementation.
 *
 * Nothing can be written to a metadata_csum filesystem without this. mke2fs
 * turns that feature on by default, and e2fsck rejects any structure whose
 * checksum does not match, so getting this right is a precondition for the
 * writer rather than a refinement of it.
 */

#include "ext4_csum.h"

/* Reflected form of the Castagnoli polynomial 0x1EDC6F41. Table-free: 4 KiB of
 * lookup table is a poor trade on a phone for a checksum computed a handful of
 * times per operation. */
#define CRC32C_POLY_REFLECTED 0x82F63B78u

/*
 * Chaining primitive: `crc` is the running state and the result is another state,
 * with no inversion at either end. That is how ext4 uses it - the seed stored in
 * the superblock is a raw state, and seeds are built by feeding one call's result
 * into the next.
 *
 * It is therefore NOT "the crc32c of this message". For that, start from ~0 and
 * invert the result; see the self-test, which recovers the published check value
 * of 0xE3069283 that way. Getting this backwards was worth several hours: the
 * inverted form produced a plausible-looking wrong number for every block.
 */
uint32_t ext4_crc32c(uint32_t crc, const void *data, size_t len) {
    const uint8_t *p = (const uint8_t *)data;
    while (len--) {
        crc ^= *p++;
        for (int i = 0; i < 8; i++)
            crc = (crc >> 1) ^ (CRC32C_POLY_REFLECTED & (uint32_t)(-(int32_t)(crc & 1)));
    }
    return crc;
}

static void put_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v);
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/*
 * Per-inode seed. Metadata owned by an inode is checksummed with a seed that
 * folds in the inode number and its generation, so a block cannot be moved to
 * another inode and still look valid.
 */
uint32_t ext4_inode_csum_seed(uint32_t fs_seed, uint32_t ino, uint32_t generation) {
    uint8_t le[4];
    uint32_t seed = fs_seed;
    put_le32(le, ino);
    seed = ext4_crc32c(seed, le, 4);
    put_le32(le, generation);
    seed = ext4_crc32c(seed, le, 4);
    return seed;
}

/*
 * The tail does not sit at the end of the block: it goes immediately after the
 * space reserved for eh_max entries, at 12 + eh_max * 12. On a 2 KiB block eh_max
 * is 169, putting the tail at 2040 with eight bytes of slack after it, so reading
 * the last four bytes of the block finds padding rather than the checksum.
 *
 * The checksum covers everything up to that offset. The root inside the inode has
 * no tail at all - it is covered by the inode's own checksum instead.
 */
uint32_t ext4_extent_tail_offset(const uint8_t *block) {
    uint32_t eh_max = (uint32_t)block[4] | ((uint32_t)block[5] << 8);
    return 12u + eh_max * 12u;
}

uint32_t ext4_extent_block_csum(uint32_t inode_seed, const uint8_t *block, uint32_t block_size) {
    uint32_t off = ext4_extent_tail_offset(block);
    if (off + 4 > block_size) return 0;   /* header is not trustworthy */
    return ext4_crc32c(inode_seed, block, off);
}
