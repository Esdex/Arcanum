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

/*
 * Where a mounted volume's bytes actually live.
 *
 * Everything above this - XTS, the sector numbering, the read-only and hidden-volume
 * guards, FatFs and ext4 alike - works in "len bytes at byte offset off, counted from
 * the start of the volume". That is the whole contract, and it is all a backing store
 * has to provide. Splitting it out is what lets a volume sit on something that is not
 * a file: a USB drive reached over SCSI has no file descriptor to pread (issue #95).
 *
 * The backend is BORROWED, not owned. `close` exists for backends that hold a resource
 * of their own (a claimed USB interface); the file backend's is a no-op, because the
 * descriptor belongs to ContainerCtx and is closed there. Do not free the fd here -
 * that would be a double close.
 *
 * Offsets are absolute within the volume and already include dataOffset; a backend
 * never needs to know where the header ends.
 */
typedef struct BlockBackend {
    bool (*read )(void *self, void *buf, size_t len, uint64_t off);
    bool (*write)(void *self, const void *buf, size_t len, uint64_t off);
    void (*sync )(void *self);
    void (*close)(void *self);
    void  *self;
} BlockBackend;

/* Per-drive I/O state (shared with diskio.cpp) */
typedef struct {
    BlockBackend         backend;               /* how this volume reaches its bytes */
    uint64_t             dataOffset;
    uint64_t             sectorCount;
    bool                 active;
    int                  algId;                 /* ALGORITHMS[] index — set by alloc_drive */
    int                  hashId;                /* PBKDF2 hash index (0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog) */
    uint32_t             pkcs5Iterations;       /* PBKDF2 iteration count used to derive this volume's key */
    bool                 isHidden;              /* true if this slot holds a hidden volume */
    bool                 readOnly;              /* true if mounted read-only; disk_write refuses at the block layer.
                                                   Backstop to the O_RDONLY fd and the ctx->readOnly checks in the
                                                   file-op JNI entry points — all three must agree. A backend with
                                                   no OS-level equivalent of O_RDONLY (USB) must refuse writes
                                                   inside itself, so the third guard is not silently lost. */
    uint64_t             hiddenBoundary;        /* absolute file offset; outer writes must not reach or exceed this (0 = no protection) */
    bool                 hiddenBoundaryTripped; /* set to true when disk_write blocks a write due to hiddenBoundary */
    struct GenCipherCtx *cipherCtx;             /* heap-allocated, null when !active or plaintext */
    bool                 plaintext;             /* no cipher at all: the drive is a bare block device.
                                                   Only ever set by passing a null master key to
                                                   alloc_drive, which only the raw-format entry point
                                                   does - it formats the ORDINARY partition of a
                                                   partitioned USB drive (#131), where there is nothing
                                                   to encrypt. A volume must never reach this state, so
                                                   the absence of a key is the trigger rather than a
                                                   flag someone could set alongside one. */
    /* What the ext4 block layer actually did on this drive, reported once at
     * unmount (#155). Two numbers settle both halves of that issue without a
     * logcat window and without a backend of its own: how many block reads the
     * driver asked for against how many reached the device, and how many times the
     * filesystem had to be opened. Three counters and three increments per block -
     * always on, because a measurement nobody can take is how #155 stayed
     * invisible under FatFs for a year. */
    uint64_t             ext4Reads;             /* block reads asked for */
    uint64_t             ext4ReadHits;          /* of those, served without touching the device */
    uint64_t             ext4Writes;            /* block writes reaching the device */
    struct ext4_drive_session *ext4Session;      /* #155 second half: the reader and writable handle this mount
                                                   holds, instead of opening the filesystem again for every
                                                   operation. Created by ext4_device.cpp on the first ext4
                                                   operation, null for FAT and exFAT, and released by
                                                   free_drive BEFORE its memset, next to the cache and for
                                                   the same reason. Nothing outside ext4_device.cpp touches
                                                   it; jni_ext4.cpp goes through ext4_device_session_*. */
    struct ext4_blockcache *ext4Cache;          /* #155: the same metadata blocks were read and decrypted on
                                                   every operation. Allocated by ext4_device.cpp on the first
                                                   block read of an ext4 volume, null for FAT and exFAT, and
                                                   released by free_drive BEFORE its memset - it holds
                                                   plaintext, and after the memset there is no way to reach
                                                   it. Nothing outside ext4_device.cpp touches it. */
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

/* Fills `out` with the file-backed implementation: pread_all / write_all_at / fsync
 * on `fd`, which it borrows. See the BlockBackend comment on why close is a no-op. */
void fd_backend_init(BlockBackend *out, int fd);
#ifdef __cplusplus
}
#endif
