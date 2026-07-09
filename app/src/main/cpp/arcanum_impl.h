/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 */

#pragma once
#include <stdint.h>
#include <stdbool.h>

#define MAX_DRIVES      4
#define VC_SECTOR_SIZE  512
#define MAX_CASCADE     3

/* Cipher IDs — ordinals match Kotlin CipherAlgorithm enum for single-cipher entries */
#define CIPHER_AES        0
#define CIPHER_SERPENT    1
#define CIPHER_TWOFISH    2
#define CIPHER_CAMELLIA   3
#define CIPHER_KUZNYECHIK 4

/* Opaque cipher context — defined and heap-allocated in arcanum_jni.cpp */
struct GenCipherCtx;

/* Cascade sector crypt — called by diskio.cpp for each 512-byte sector */
#ifdef __cplusplus
void vc_crypt_sector(struct GenCipherCtx *ctx, uint8_t *buf,
                     uint64_t sectorNum, bool encrypt);
#endif

/* Per-drive I/O state (shared with diskio.cpp) */
typedef struct {
    int                  fd;
    uint64_t             dataOffset;
    uint64_t             sectorCount;
    bool                 active;
    int                  algId;                 /* ALGORITHMS[] index — set by alloc_drive */
    int                  hashId;                /* PBKDF2 hash index (0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog) */
    uint32_t             pkcs5Iterations;       /* PBKDF2 iteration count used to derive this volume's key */
    bool                 isHidden;              /* true if this slot holds a hidden volume */
    uint64_t             hiddenBoundary;        /* absolute file offset; outer writes must not reach or exceed this (0 = no protection) */
    bool                 hiddenBoundaryTripped; /* set to true when disk_write blocks a write due to hiddenBoundary */
    struct GenCipherCtx *cipherCtx;             /* heap-allocated, null when !active */
    uint32_t             generation;            /* bumped on every alloc_drive() of this slot; part of the
                                                    jlong handle so a stale handle from a freed+reused slot
                                                    is rejected instead of silently operating on the wrong
                                                    (newer) container. Preserved across free_drive()'s
                                                    memset; starts at 1 on a slot's first-ever allocation. */
} DriveContext;

extern DriveContext g_drives[MAX_DRIVES];

/* pread_all/write_all_at (stage 2a/4): loop over partial transfers, retry
 * EINTR, false on error/EOF-short. Defined in arcanum_jni.cpp, used by
 * diskio.cpp for batched sector I/O. */
#ifdef __cplusplus
extern "C" {
#endif
bool pread_all(int fd, void *buf, size_t len, long long off);
bool write_all_at(int fd, const void *buf, size_t len, long long off);
#ifdef __cplusplus
}
#endif
