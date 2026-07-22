/*
 * The one place the writable side touches the disk.
 *
 * The device gives back one thing: whole filesystem blocks, read and written by
 * number. That is all a raw block device - or an encrypted container laid over
 * one - can do; it cannot write half a block. But ext4 is full of structures
 * smaller than a block that have to be updated in place: the superblock is 1024
 * bytes at byte offset 1024, an inode is 128 or 256, a block bitmap is usually
 * less than a block. So every sub-block write is a read-modify-write - fetch the
 * block, splice the changed bytes in, put the block back - and that splicing is
 * exactly the kind of off-by-one that corrupts a neighbour silently.
 *
 * It lives here, on top of two block callbacks, rather than on the far side of
 * the JNI boundary, so that the whole existing harness exercises it. The device
 * only has to provide read_block and write_block; the arithmetic that turns a
 * byte range into whole-block operations is host-testable code.
 */
#ifndef ARCANUM_EXT4_IO_H
#define ARCANUM_EXT4_IO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Read/write one filesystem block of `block_size` bytes. Return 0 on success,
 * non-zero on failure. The device implements these two and nothing else.
 *
 * block_size is passed in rather than left for the callback to find, because the
 * callback is handed only `user` - which on the device is the container, not
 * anything that knows the filesystem's block size. It is the size of `buf` and
 * `block` is numbered in units of it. */
typedef int (*ext4_block_read_fn)(void *user, uint64_t block, uint32_t block_size, void *buf);
typedef int (*ext4_block_write_fn)(void *user, uint64_t block, uint32_t block_size, const void *buf);
typedef int (*ext4_flush_fn)(void *user);

typedef struct {
    ext4_block_read_fn  read_block;
    ext4_block_write_fn write_block;
    ext4_flush_fn       flush;       /* may be NULL */
    void   *user;
    uint32_t block_size;             /* 1024 while bootstrapping, then the real size */
} ext4_io;

/*
 * Byte-range access, read-modify-writing whole blocks underneath. `off` and `len`
 * are arbitrary and may straddle block boundaries. Return 0 on success.
 *
 * A partial write reads its blocks first, so the bytes it does not touch survive
 * - which is the whole point, and what a bare block write would destroy.
 */
int ext4_io_pread(ext4_io *io, uint64_t off, void *buf, size_t len);
int ext4_io_pwrite(ext4_io *io, uint64_t off, const void *buf, size_t len);
int ext4_io_flush(ext4_io *io);

#ifdef __cplusplus
}
#endif
#endif
