#include "arcanum_impl.h"
#include "ff.h"
#include "diskio.h"
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <sys/types.h>

/* IMPORTANT: every disk_* callback here runs synchronously inside an f_*
 * call from arcanum_jni.cpp, on a thread that already holds g_fatfs_mutex
 * (FatFs is not reentrant — FF_FS_REENTRANT 0). Do NOT lock g_fatfs_mutex
 * anywhere in this file: it is a plain std::mutex (non-recursive), so doing
 * so would deadlock the calling thread against itself. */

DSTATUS disk_initialize(BYTE pdrv) {
    if (pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return STA_NOINIT;
    return 0;
}

DSTATUS disk_status(BYTE pdrv) {
    if (pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return STA_NOINIT;
    return 0;
}

DRESULT disk_read(BYTE pdrv, BYTE *buf, LBA_t sector, UINT count) {
    if (pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return RES_NOTRDY;
    DriveContext *ctx = &g_drives[pdrv];
    uint64_t baseSector = ctx->dataOffset / VC_SECTOR_SIZE;

    /* Batched I/O (stage 4): one pread_all() for the whole span instead of a
     * pread() per sector, then decrypt each 512-byte sector in place. Cuts
     * syscall count by up to 256x on FatFs's larger multi-sector reads. */
    off_t  off   = (off_t)(ctx->dataOffset + (uint64_t)sector * VC_SECTOR_SIZE);
    size_t total = (size_t)count * (size_t)VC_SECTOR_SIZE;
    if (!pread_all(ctx->fd, buf, total, (long long)off))
        return RES_ERROR;

    for (UINT i = 0; i < count; i++) {
        vc_crypt_sector(ctx->cipherCtx,
                        buf + (size_t)i * VC_SECTOR_SIZE,
                        baseSector + (uint64_t)(sector + i),
                        false);
    }
    return RES_OK;
}

DRESULT disk_write(BYTE pdrv, const BYTE *buf, LBA_t sector, UINT count) {
    if (pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return RES_NOTRDY;
    DriveContext *ctx = &g_drives[pdrv];

    /* Protect hidden volume area when outer volume is mounted — checked
     * before any write is attempted, exactly as before batching. */
    if (ctx->hiddenBoundary > 0) {
        uint64_t writeEnd = ctx->dataOffset + ((uint64_t)sector + (uint64_t)count) * VC_SECTOR_SIZE;
        if (writeEnd > ctx->hiddenBoundary) {
            ctx->hiddenBoundaryTripped = true;
            return RES_WRPRT;
        }
    }

    uint64_t baseSector = ctx->dataOffset / VC_SECTOR_SIZE;
    off_t    off        = (off_t)(ctx->dataOffset + (uint64_t)sector * VC_SECTOR_SIZE);
    size_t   total       = (size_t)count * (size_t)VC_SECTOR_SIZE;

    /* Batched path: encrypt the whole span into one heap buffer, one write_all_at().
     * Falls back to the original per-sector path on malloc failure so a large
     * multi-sector write never fails purely from transient memory pressure. */
    auto *big = static_cast<uint8_t*>(malloc(total));
    if (big) {
        memcpy(big, buf, total);
        for (UINT i = 0; i < count; i++) {
            vc_crypt_sector(ctx->cipherCtx, big + (size_t)i * VC_SECTOR_SIZE,
                            baseSector + (uint64_t)(sector + i), true);
        }
        bool ok = write_all_at(ctx->fd, big, total, (long long)off);
        free(big);
        return ok ? RES_OK : RES_ERROR;
    }

    uint8_t tmp[VC_SECTOR_SIZE];
    for (UINT i = 0; i < count; i++) {
        memcpy(tmp, buf + (size_t)i * VC_SECTOR_SIZE, VC_SECTOR_SIZE);
        vc_crypt_sector(ctx->cipherCtx,
                        tmp,
                        baseSector + (uint64_t)(sector + i),
                        true);
        off_t soff = (off_t)(ctx->dataOffset + (uint64_t)(sector + i) * VC_SECTOR_SIZE);
        if (pwrite(ctx->fd, tmp, VC_SECTOR_SIZE, soff) != VC_SECTOR_SIZE)
            return RES_ERROR;
    }
    return RES_OK;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void *buff) {
    if (pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return RES_NOTRDY;
    DriveContext *ctx = &g_drives[pdrv];
    switch (cmd) {
        case CTRL_SYNC:
            fsync(ctx->fd);
            return RES_OK;
        case GET_SECTOR_COUNT:
            *(LBA_t *)buff = (LBA_t)ctx->sectorCount;
            return RES_OK;
        case GET_SECTOR_SIZE:
            *(WORD *)buff = VC_SECTOR_SIZE;
            return RES_OK;
        case GET_BLOCK_SIZE:
            *(DWORD *)buff = 1;
            return RES_OK;
        default:
            return RES_PARERR;
    }
}
