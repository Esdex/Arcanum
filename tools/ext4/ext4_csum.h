/* crc32c and the ext4 checksum seeds built on it. See the .c for provenance. */
#ifndef ARCANUM_EXT4_CSUM_H
#define ARCANUM_EXT4_CSUM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

uint32_t ext4_crc32c(uint32_t crc, const void *data, size_t len);
uint32_t ext4_inode_csum_seed(uint32_t fs_seed, uint32_t ino, uint32_t generation);
uint32_t ext4_extent_tail_offset(const uint8_t *block);
uint32_t ext4_extent_block_csum(uint32_t inode_seed, const uint8_t *block, uint32_t block_size);

#ifdef __cplusplus
}
#endif
#endif
