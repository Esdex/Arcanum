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
 * A small cache of filesystem blocks, held by their absolute byte offset.
 *
 * Why it exists (#155): ext4 asks for the same few blocks relentlessly. Measured on
 * a vault in a USB partition, 587898 reads of which 98.8% were offsets already read
 * - 2.2 GB requested to touch 27 MB of distinct data. Every operation walks
 * superblock -> group descriptors -> inode table -> extents from the start, so the
 * superblock alone came back 10315 times in one log window. FatFs never did this,
 * which is why nothing above had noticed.
 *
 * It lives here, in its own module with no knowledge of volumes or ciphers, for two
 * reasons. Its caller (ext4_device.cpp) is Android-only and cannot be built by the
 * host harness, and a cache whose invalidation is wrong corrupts a user's files in
 * silence - the one class of defect that must not rest on reading the code. As a
 * plain C module it links into a host stand and is checked against a model.
 *
 * Two block sizes are in play and that is the whole difficulty. The superblock is
 * bootstrapped through a 1 KiB reader before the real block size is known, and that
 * reader keeps being used afterwards - measured on device, 2802 of its reads against
 * 20359 at the filesystem's own 4 KiB. A first version emptied the cache whenever the
 * size changed, which was correct and useless: alternating sizes meant it was empty
 * every time it was consulted, and the device saw exactly as much traffic as before.
 *
 * So the size rule lives in here, where a stand can drive it:
 *
 *   - The cache holds entries of ONE size, the largest it has been shown. A read or
 *     write at a larger size empties it and adopts that size, which happens once, when
 *     the bootstrap size gives way to the real one.
 *   - A request at a SMALLER size is not cached and does not disturb what is held. It
 *     reads through to the device, which is always correct because nothing here is
 *     ever write-back: the device holds the current bytes at all times.
 *   - A WRITE at a smaller size does have to be honoured, since it changes bytes that
 *     a held entry covers. The entry containing it is dropped. Nothing relies on the
 *     bootstrap reader being read-only.
 *
 * The rest of the contract:
 *
 *   - ext4_blockcache_get returns a pointer INTO the cache. It is valid until the next
 *     call that can evict - any put, drop, or free. Copy out, do not hold.
 *   - Nothing here fails: if memory cannot be had the cache simply holds less and the
 *     caller reaches the device instead. A cache is never a reason for I/O to fail.
 *
 * The contents are plaintext filesystem metadata, names among it, so everything is
 * wiped before it is freed or reused.
 *
 * No locking. The caller serialises - in the app every path arrives under
 * g_fatfs_mutex, taken above this layer and never re-entered from below.
 */
#ifndef ARCANUM_EXT4_BLOCKCACHE_H
#define ARCANUM_EXT4_BLOCKCACHE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ext4_blockcache ext4_blockcache;

/* An empty cache that has not yet been shown a block size, or NULL if it cannot be had. */
ext4_blockcache *ext4_blockcache_new(void);

/* Wipes every entry, then frees. NULL is accepted and ignored. */
void ext4_blockcache_free(ext4_blockcache *c);

/* The entry size currently held, or 0 for a cache that has seen nothing yet. */
uint32_t ext4_blockcache_len(const ext4_blockcache *c);

/* The cached bytes for exactly `len` bytes at `off`, or NULL - including whenever
 * `len` is not the size this cache holds. See the lifetime note above. */
const void *ext4_blockcache_get(ext4_blockcache *c, uint64_t off, uint32_t len);

/* Offer the result of a completed READ. Stored when `len` is the size held; adopted,
 * emptying the cache first, when `len` is larger; ignored when smaller. */
void ext4_blockcache_read(ext4_blockcache *c, uint64_t off, uint32_t len, const void *src);

/* Record a completed WRITE. Stored like a read when `len` is the size held or larger;
 * when smaller, the entry covering those bytes is dropped, because it no longer
 * describes the device. */
void ext4_blockcache_wrote(ext4_blockcache *c, uint64_t off, uint32_t len, const void *src);

/* Forget `off`. The only correct answer after a write whose outcome on the device is
 * unknown. Takes the length so a smaller failed write drops what contains it. */
void ext4_blockcache_drop(ext4_blockcache *c, uint64_t off, uint32_t len);

/* Entries currently held. For tests and diagnostics, not for logic. */
int ext4_blockcache_count(const ext4_blockcache *c);

#ifdef __cplusplus
}
#endif
#endif
