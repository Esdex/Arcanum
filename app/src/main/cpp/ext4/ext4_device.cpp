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
 * ext4_io over a mounted VeraCrypt container. See the header, and diskio.cpp,
 * which does the same job for FatFs and whose sector arithmetic this mirrors.
 *
 * A filesystem block is 1-4 KiB; the container works in 512-byte sectors, each
 * XTS-crypted with its own absolute sector number. So one block callback is a run
 * of block_size / 512 sectors: read the ciphertext in one backend read, decrypt each
 * sector in place; or encrypt each sector into a scratch buffer and hand it to one
 * backend write. The read-modify-write that keeps sub-block updates from wiping their
 * neighbours is one layer up, in ext4_io.c, on host-tested code; this layer only
 * ever moves whole blocks.
 *
 * The same rules diskio.cpp enforces apply, because a wrong write here corrupts a
 * real user's container:
 *
 *   read-only     refused at this layer, not merely relied on from the O_RDONLY
 *                 fd, so all three guards (fd, ctx flag, this) still agree
 *   hidden volume a write that would reach into the hidden volume's area of an
 *                 outer mount is refused and trips the boundary flag, exactly as
 *                 the FatFs path does
 */
#include "ext4_device.h"
#include "ext4_blockcache.h"
#include "ext4_log.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>

/*
 * The block cache (#155) is a module of its own - ext4_blockcache.c - because the
 * host harness can build a C file and cannot build this one, and a cache whose
 * invalidation is wrong corrupts a user's files without saying so. Everything that
 * needs judgement lives there, where a stand drives it: what to do when the block
 * size changes, when a smaller read arrives, when a smaller write does. This file
 * only says what happened - a read completed, a write completed, a write failed -
 * and the module decides what that means.
 *
 * It sits at the point where ciphertext becomes plaintext, so a hit saves the
 * backend read AND the XTS decrypt, for a file-hosted vault and a USB one alike.
 * The chunk cache in usb_backend.cpp was already absorbing this traffic for USB
 * volumes; a file-hosted vault had nothing in front of it at all.
 */
static ext4_blockcache *cache_for(DriveContext *ctx) {
    if (!ctx->ext4Cache) ctx->ext4Cache = ext4_blockcache_new();  /* null is fine: we go to the device */
    return ctx->ext4Cache;
}

void ext4_device_cache_release(DriveContext *drive) {
    if (!drive) return;
    ext4_blockcache_free(drive->ext4Cache);
    drive->ext4Cache = nullptr;
}

/*
 * One block, in either direction. block_size is a whole number of 512-byte
 * sectors (ext4 block sizes are powers of two from 1 KiB up), so the run is
 * exact. The absolute sector numbers, which the cipher folds into every sector,
 * are measured from the volume's data offset - the same base diskio.cpp uses.
 */
static int dev_rw(DriveContext *ctx, uint64_t block, uint32_t block_size,
                  void *buf, bool writing) {
    if (!ctx->active) {
        EXT4_LOGE("%s block %llu on an inactive drive",
                  writing ? "write" : "read", (unsigned long long)block);
        return -1;
    }
    if (block_size == 0 || (block_size % VC_SECTOR_SIZE) != 0) {
        EXT4_LOGE("block_size %u is not a multiple of the %d-byte sector",
                  block_size, VC_SECTOR_SIZE);
        return -1;
    }

    uint32_t nsec       = block_size / VC_SECTOR_SIZE;
    uint64_t baseSector = ctx->dataOffset / VC_SECTOR_SIZE;
    uint64_t firstSector = block * (uint64_t)nsec;
    uint64_t byteOff    = ctx->dataOffset + firstSector * (uint64_t)VC_SECTOR_SIZE;

    ext4_blockcache *cache = cache_for(ctx);

    if (!writing) {
        if (cache) {
            const void *hit = ext4_blockcache_get(cache, byteOff, block_size);
            if (hit) {
                memcpy(buf, hit, block_size);
                return 0;
            }
        }
        if (!ctx->backend.read(ctx->backend.self, buf, block_size, byteOff)) {
            EXT4_LOGE("read block %llu (%u sectors at offset %llu) failed",
                      (unsigned long long)block, nsec, (unsigned long long)byteOff);
            return -1;
        }
        uint8_t *p = static_cast<uint8_t *>(buf);
        /* A plaintext drive has no cipher context at all - see DriveContext::plaintext. */
        if (!ctx->plaintext)
            for (uint32_t i = 0; i < nsec; i++)
                vc_crypt_sector(ctx->cipherCtx, p + (size_t)i * VC_SECTOR_SIZE,
                                baseSector + firstSector + i, /*encrypt=*/false);
        if (cache) ext4_blockcache_read(cache, byteOff, block_size, buf);
        /* Only misses reach here, so this line now counts device traffic rather than
         * requests - which is what a measurement of #155 wants to see. */
        EXT4_LOGD("read block %llu (%u sectors)", (unsigned long long)block, nsec);
        return 0;
    }

    /* Refused at the block layer, matching diskio.cpp: the fd being O_RDONLY is
     * not enough on its own to be sure a write never leaves this code. */
    if (ctx->readOnly) {
        EXT4_LOGE("write block %llu refused: drive is read-only",
                  (unsigned long long)block);
        return -1;
    }

    /* A write must not reach into the hidden volume's territory when the outer
     * volume is what is mounted. Same check, same tripped-flag, as FatFs. */
    if (ctx->hiddenBoundary > 0) {
        uint64_t writeEnd = byteOff + block_size;
        if (writeEnd > ctx->hiddenBoundary) {
            ctx->hiddenBoundaryTripped = true;
            EXT4_LOGE("write block %llu refused: would reach into the hidden "
                      "volume (end %llu > boundary %llu)", (unsigned long long)block,
                      (unsigned long long)writeEnd, (unsigned long long)ctx->hiddenBoundary);
            return -1;
        }
    }

    /* Encrypt into a scratch copy - the caller's buffer is plaintext it may still
     * be using, and encrypting in place would corrupt it. */
    uint8_t *enc = static_cast<uint8_t *>(malloc(block_size));
    if (!enc) {
        EXT4_LOGE("write block %llu: out of memory for the %u-byte encrypt buffer",
                  (unsigned long long)block, block_size);
        return -1;
    }
    memcpy(enc, buf, block_size);
    if (!ctx->plaintext)
        for (uint32_t i = 0; i < nsec; i++)
            vc_crypt_sector(ctx->cipherCtx, enc + (size_t)i * VC_SECTOR_SIZE,
                            baseSector + firstSector + i, /*encrypt=*/true);
    bool ok = ctx->backend.write(ctx->backend.self, enc, block_size, byteOff);
    free(enc);
    if (!ok) {
        /* What is on the device is now unknown, so holding a copy that claims to know
         * would be worse than holding nothing. */
        if (cache) ext4_blockcache_drop(cache, byteOff, block_size);
        EXT4_LOGE("write block %llu (%u sectors at offset %llu) failed",
                  (unsigned long long)block, nsec, (unsigned long long)byteOff);
        return -1;
    }
    if (cache) ext4_blockcache_wrote(cache, byteOff, block_size, buf);
    EXT4_LOGD("wrote block %llu (%u sectors)", (unsigned long long)block, nsec);
    return 0;
}

static int dev_read_block(void *user, uint64_t block, uint32_t block_size, void *buf) {
    return dev_rw(static_cast<DriveContext *>(user), block, block_size, buf, false);
}

static int dev_write_block(void *user, uint64_t block, uint32_t block_size, const void *buf) {
    return dev_rw(static_cast<DriveContext *>(user), block, block_size,
                  const_cast<void *>(buf), true);
}

ext4_io ext4_device_io(DriveContext *drive) {
    ext4_io io;
    memset(&io, 0, sizeof(io));
    io.read_block  = dev_read_block;
    io.write_block = dev_write_block;
    io.flush       = nullptr;   /* CTRL_SYNC is issued by the JNI layer on unmount */
    io.user        = drive;
    io.block_size  = 0;         /* set from the superblock during open */
    return io;
}

void ext4_device_reader_init(ext4_device_reader *rd, DriveContext *drive) {
    rd->drive      = drive;
    rd->block_size = 1024;      /* the provisional view the superblock is read at */
}

int ext4_device_read_block(void *ctx, uint64_t block, void *buf) {
    ext4_device_reader *rd = static_cast<ext4_device_reader *>(ctx);
    /* Same decrypt-a-run-of-sectors path as the writable side's read half, sized
     * by the reader's own block_size rather than a parameter. */
    return dev_rw(rd->drive, block, rd->block_size, buf, /*writing=*/false) == 0
               ? EXT4_OK : EXT4_ERR_IO;
}
