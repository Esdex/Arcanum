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
 * The filesystem handles a mount holds on to, instead of opening them again for
 * every operation.
 *
 * Why it exists (#155, second half). The block cache took the same metadata blocks
 * out of the device's way, and what it could not touch was the reason they were
 * asked for: every single operation - a listing, a chunk of a file read, a chunk
 * written, a mkdir, a rename - opened the filesystem from scratch and closed it
 * again. The superblock alone came back 9980 times in one session, and it is the
 * one read a block cache cannot serve: it is fetched through a provisional 1 KiB
 * view before the real block size is known, and the cache holds one size, the
 * larger. A cache can only make that read cheaper. It cannot make it stop.
 *
 * So the handles live for as long as the mount does. What that saves per operation
 * is the superblock read and its parse, the feature-bit validation, and - on the
 * writable side - a re-read of the whole group descriptor table into two fresh
 * mallocs.
 *
 * ## The rule this module exists to hold
 *
 * A handle that is kept is a handle that can drift from the disk it describes.
 * Closing after every operation was never an optimisation choice; it was what made
 * a failed operation self-healing, because the next one re-read everything. That
 * property has to be kept, so it is stated here as two rules rather than left to
 * whoever remembers:
 *
 *   - **A failed write poisons the writable handle.** Every write and flush the
 *     handle makes passes through this module, so the poisoning is structural: a
 *     caller cannot forget to report one. The next ask closes the handle and opens
 *     a fresh one off the disk - exactly what used to happen unconditionally.
 *   - **ext4_session_drop is for everything else** that leaves memory ahead of the
 *     disk with no write having failed: an operation abandoned part way through
 *     (jni_ext4.cpp's WriteSession::tear), or the volume replaced underneath, as a
 *     format does.
 *
 * ## Why the reader is not dropped with it
 *
 * ext4_fs is a parse of geometry that cannot change while a filesystem exists -
 * block size, inode size, inodes per group, first data block, descriptor size,
 * block count, checksum seed. It holds no memory and mutates nothing, so there is
 * nothing about it for a failed write to invalidate.
 *
 * The single exception is `is_clean`, read from s_state at open, which does move:
 * the writable side clears it while writes are outstanding. It is not read through
 * a session anywhere - ext4jni_probe reads it once at mount time through a handle
 * of its own, deliberately, and that is the only use in the app. Should a second
 * reader of that field ever appear, it must not come from here.
 *
 * ## Lifetime
 *
 * Every pointer handed out is borrowed and belongs to the session; nothing the
 * caller receives may be closed or freed by it, and none of it survives a drop or
 * the next ask that reopens. The read callback, its context and the io are held by
 * value from ext4_session_new onwards, so all three must outlive the session.
 *
 * No locking. The caller serialises - in the app every path arrives under
 * g_fatfs_mutex, taken above this layer and never re-entered from below.
 */
#ifndef ARCANUM_EXT4_SESSION_H
#define ARCANUM_EXT4_SESSION_H

#include "ext4_alloc.h"
#include "ext4_extents.h"
#include "ext4_io.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ext4_session ext4_session;

/*
 * Told that the read context should now serve blocks of `block_size`.
 *
 * The reader carries its block size in its context rather than in the callback's
 * arguments, and ext4_open changes that size in the middle of itself: the
 * superblock is read through a provisional 1 KiB view, then everything after it at
 * the size that read reported. The caller owns the context, so only the caller can
 * move it, and this is how it is asked to.
 *
 * It matters on the second open as much as the first. A context left at the real
 * size from a previous handle would put the next bootstrap read at the wrong
 * offset, so the session drives it in both directions: down to 1 KiB before an
 * open, up to the real size after one.
 */
typedef void (*ext4_session_bs_fn)(void *ctx, uint32_t block_size);

/*
 * A session that holds nothing yet. NULL if the memory cannot be had, which the
 * caller must treat as a reason to fail the operation rather than to open a handle
 * around this module - a handle outside the session is a handle outside the rule.
 *
 * `read_block` and `reader_ctx` are the read-only side, `io` the writable one, and
 * `set_bs` may be NULL only for a context whose block size is not carried in it.
 */
ext4_session *ext4_session_new(ext4_read_block_fn read_block, void *reader_ctx,
                               ext4_session_bs_fn set_bs, ext4_io io);

/* Closes whatever is held and frees. NULL is accepted and ignored. */
void ext4_session_free(ext4_session *s);

/*
 * The read-only handle, opened on the first ask and after a drop. Returns 0 and
 * sets *out, or -1 when the filesystem will not open - in which case nothing is
 * held and the next ask tries again.
 */
int ext4_session_reader(ext4_session *s, ext4_fs **out);

/*
 * The writable handle, opened on the first ask, after a drop, and after a write
 * through it failed. `now` is what the superblock's last-write time is stamped
 * from (#156) and is applied on every ask, held handle or fresh one, so the field
 * records the operation happening rather than the one that opened the mount.
 *
 * Returns 0 and sets *out, or -1 when the filesystem will not open for writing.
 */
int ext4_session_writer(ext4_session *s, uint32_t now, ext4_wfs **out);

/*
 * Forget both handles. For the cases no failed write reports: an operation
 * abandoned with its allocations already in memory, or a volume rewritten
 * underneath by a format. The next ask opens from the disk.
 */
void ext4_session_drop(ext4_session *s);

/*
 * How many times each handle was actually opened. Diagnostics, and the only way a
 * test can tell reuse from a session that quietly reopens every time - which
 * produces byte-identical results and is exactly the failure worth catching.
 * Never reset; a drop does not clear them.
 */
void ext4_session_opens(const ext4_session *s, unsigned *reader, unsigned *writer);

#ifdef __cplusplus
}
#endif
#endif
