/*
 * Byte-range access over a block device, by read-modify-write. See the header.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_io.h"

#include <stdlib.h>
#include <string.h>

/*
 * Walks the range one block at a time. The first and last blocks may be touched
 * only in part; every block in between is whole. `writing` decides whether the
 * partial blocks are read first (to keep the bytes outside the range) and whether
 * the result is written back.
 */
static int io_range(ext4_io *io, uint64_t off, void *buf, size_t len, int writing) {
    if (io->block_size == 0) return -1;
    uint8_t *p = (uint8_t *)buf;
    uint8_t *block = malloc(io->block_size);
    if (!block) return -1;
    int rc = 0;

    while (len > 0) {
        uint64_t blk    = off / io->block_size;
        uint32_t inpart = (uint32_t)(off % io->block_size);          /* offset into it */
        uint32_t chunk  = io->block_size - inpart;
        if (chunk > len) chunk = (uint32_t)len;

        int whole = (inpart == 0 && chunk == io->block_size);

        uint32_t bs = io->block_size;
        if (!writing) {
            if (io->read_block(io->user, blk, bs, block)) { rc = -1; break; }
            memcpy(p, block + inpart, chunk);
        } else if (whole) {
            /* No surrounding bytes to preserve, so no read is needed. */
            if (io->write_block(io->user, blk, bs, p)) { rc = -1; break; }
        } else {
            /* Partial: the bytes outside [inpart, inpart+chunk) have to survive,
             * so the block is fetched before the splice. Skipping this read is
             * what turns a small update into wiping its neighbours. */
            if (io->read_block(io->user, blk, bs, block)) { rc = -1; break; }
            memcpy(block + inpart, p, chunk);
            if (io->write_block(io->user, blk, bs, block)) { rc = -1; break; }
        }

        p   += chunk;
        off += chunk;
        len -= chunk;
    }

    free(block);
    return rc;
}

int ext4_io_pread(ext4_io *io, uint64_t off, void *buf, size_t len) {
    return io_range(io, off, buf, len, 0);
}

int ext4_io_pwrite(ext4_io *io, uint64_t off, const void *buf, size_t len) {
    /* io_range does not modify buf when writing, so the cast is safe. */
    return io_range(io, off, (void *)(uintptr_t)buf, len, 1);
}

int ext4_io_flush(ext4_io *io) {
    return io->flush ? io->flush(io->user) : 0;
}
