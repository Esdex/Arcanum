#include "arcanum_impl.h"
#include "ff.h"
#include "diskio.h"
#include <unistd.h>
#include <cstring>
#include <fcntl.h>
#include <sys/types.h>

static bool sector_range_valid(const DriveContext *ctx, LBA_t sector, UINT count) {
    if (count == 0) return false;
    uint64_t sector64 = (uint64_t)sector;
    uint64_t count64 = (uint64_t)count;
    if (sector64 > ctx->sectorCount) return false;
    if (count64 > ctx->sectorCount - sector64) return false;
    return true;
}

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
    if (!sector_range_valid(ctx, sector, count)) return RES_PARERR;
    uint64_t baseSector = ctx->dataOffset / VC_SECTOR_SIZE;
    for (UINT i = 0; i < count; i++) {
        off_t off = (off_t)(ctx->dataOffset + (uint64_t)(sector + i) * VC_SECTOR_SIZE);
        if (pread(ctx->fd, buf + (size_t)i * VC_SECTOR_SIZE, VC_SECTOR_SIZE, off) != VC_SECTOR_SIZE)
            return RES_ERROR;
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
    if (!sector_range_valid(ctx, sector, count)) return RES_PARERR;

    /* Protect hidden volume area when outer volume is mounted */
    if (ctx->hiddenBoundary > 0) {
        uint64_t sectorEnd = (uint64_t)sector + (uint64_t)count;
        uint64_t writeEnd = ctx->dataOffset + sectorEnd * VC_SECTOR_SIZE;
        if (writeEnd > ctx->hiddenBoundary) {
            ctx->hiddenBoundaryTripped = true;
            return RES_WRPRT;
        }
    }

    uint64_t baseSector = ctx->dataOffset / VC_SECTOR_SIZE;
    uint8_t tmp[VC_SECTOR_SIZE];
    for (UINT i = 0; i < count; i++) {
        memcpy(tmp, buf + (size_t)i * VC_SECTOR_SIZE, VC_SECTOR_SIZE);
        vc_crypt_sector(ctx->cipherCtx,
                        tmp,
                        baseSector + (uint64_t)(sector + i),
                        true);
        off_t off = (off_t)(ctx->dataOffset + (uint64_t)(sector + i) * VC_SECTOR_SIZE);
        if (pwrite(ctx->fd, tmp, VC_SECTOR_SIZE, off) != VC_SECTOR_SIZE)
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
