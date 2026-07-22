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
#include "arcanum_impl.h"

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

#ifdef __cplusplus
}
#endif
#endif
