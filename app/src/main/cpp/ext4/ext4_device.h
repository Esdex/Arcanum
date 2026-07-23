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
#include "ext4_extents.h"
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

/* An ext4_read_block_fn over an ext4_device_reader. Decrypts through the same
 * sector path as ext4_device_io's read half. Returns EXT4_OK / EXT4_ERR_IO. */
int ext4_device_read_block(void *ctx, uint64_t block, void *buf);

#ifdef __cplusplus
}
#endif
#endif
