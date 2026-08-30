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
 * Binds the clean-room ext4 code to a mounted VeraCrypt container.
 *
 * ext4 talks to the disk through ext4_io - two callbacks, read one block and
 * write one block. A DriveContext is the far end: a file descriptor into the
 * encrypted volume plus the cipher state that turns 512-byte sectors to and from
 * plaintext. This is where the two meet, and it is the exact counterpart of
 * fatfs/diskio.cpp for the extent filesystem.
 */
#ifndef ARCANUM_EXT4_DEVICE_H
#define ARCANUM_EXT4_DEVICE_H

#include "ext4_io.h"
#include "ext4_alloc.h"
#include "ext4_extents.h"
#include "ext4_session.h"
#include "arcanum_impl.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * An ext4_io that reads and writes filesystem blocks over `drive`. `block_size`
 * is left 0 and set from the superblock by ext4_fs_open_io / ext4_open, the same
 * bootstrap the host file backend uses.
 *
 * The DriveContext must outlive every use of the returned io - it is borrowed,
 * not owned, exactly as the JNI file operations already borrow g_drives slots.
 */
ext4_io ext4_device_io(DriveContext *drive);

/*
 * The reader side.
 *
 * The read-only API (ext4_open and everything that takes a const ext4_fs*) uses a
 * narrower callback than the writable side: it is handed only (ctx, block, buf),
 * with no block size, because the reader carries the size in fs->block_size and
 * expects the callback's ctx to already know it. So the reader gets its own tiny
 * context - the drive plus a block size the caller keeps in step, exactly as the
 * host tools keep block_size in their img ctx.
 *
 * Bootstrap is the same chicken-and-egg the host has: block_size starts at 1024 so
 * the superblock read lands, then the caller sets it to fs.block_size once
 * ext4_open has read that field. Initialise with ext4_device_reader_init.
 */
typedef struct {
    DriveContext *drive;
    uint32_t      block_size;   /* 1024 to bootstrap, then the real size */
} ext4_device_reader;

void ext4_device_reader_init(ext4_device_reader *rd, DriveContext *drive);

/*
 * Releases this drive's block cache (#155), wiping it first - it holds plaintext
 * metadata. Called from free_drive before its memset, which is the last moment the
 * pointer is reachable. Safe on a drive that never held an ext4 volume, and safe to
 * call twice.
 */
void ext4_device_cache_release(DriveContext *drive);

/*
 * The filesystem handles this mount holds (#155, second half). ext4_session.c has
 * the rules and the reasons; this is the binding to a DriveContext, which is what
 * owns the read context the reader needs to outlive every operation.
 *
 * Both return 0 and set *out, or -1 with nothing held. The handles are borrowed:
 * never close or free one, and treat it as invalid across a drop or the next ask.
 * The caller holds g_fatfs_mutex, as it does for every other entry here.
 */
int ext4_device_session_reader(DriveContext *drive, ext4_fs **out);
int ext4_device_session_writer(DriveContext *drive, uint32_t now, ext4_wfs **out);

/*
 * Forget what is held, because memory may now be ahead of the disk with no write
 * having reported a failure: an operation abandoned part way (WriteSession::tear),
 * or a volume rewritten underneath by a format. A write that simply failed needs
 * no call - the session sees those itself.
 */
void ext4_device_session_drop(DriveContext *drive);

/*
 * Closes and frees the session. Called from free_drive next to the cache release
 * and for the same reason: after the memset the pointer cannot be reached. Safe on
 * a drive that never held an ext4 volume, and safe to call twice.
 */
void ext4_device_session_release(DriveContext *drive);

/*
 * Logs what this mount cost: block reads asked for against reads that reached the
 * device, writes, and how many times the filesystem had to be opened. Call it from
 * free_drive BEFORE the releases below, while both halves are still reachable.
 *
 * Silent for a drive that never carried out an ext4 operation, which includes
 * every FAT volume - those reach the block layer too, for the one superblock read
 * the mount-time probe makes to find out what they are.
 */
void ext4_device_report(const DriveContext *drive);

/* Reader and writer opens over the life of this drive's session, for the debug
 * census only - #155 is a claim about how often the filesystem is opened, and this
 * is what makes it checkable on a device rather than argued from the code. */
void ext4_device_session_opens(const DriveContext *drive, unsigned *reader, unsigned *writer);

/* An ext4_read_block_fn over an ext4_device_reader. Decrypts through the same
 * sector path as ext4_device_io's read half. Returns EXT4_OK / EXT4_ERR_IO. */
int ext4_device_read_block(void *ctx, uint64_t block, void *buf);

#ifdef __cplusplus
}
#endif
#endif
