/*
 * Driver for the rename/move primitive (ext4_rename): the same composition the JNI
 * bridge drives on the device (jni_ext4.cpp's ext4jni_rename) - resolve both ends
 * to a parent inode and a final name, then ext4_rename in its crash-safe order.
 *
 *   rename <image> <oldpath> <newpath>
 *
 * On success it prints and exits 0. On a refusal it exits with the primitive's own
 * error code negated (EXISTS -> 3, LOOP -> 11, ABSENT -> 4, ...), so renamecheck.py
 * can assert not just that a bad move was refused but that it was refused for the
 * right reason.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_path.h"
#include "ext4_create.h"
#include "ext4_dirwrite.h"
#include "ext4_extwrite.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

/* The read-only view over the plain host file. The writable side is a separate
 * handle over the same path, exactly as the device opens a reader and a writer. */
typedef struct { FILE *fp; uint32_t block_size; } rctx;

static int r_read(void *ctx, uint64_t block, void *buf) {
    rctx *c = (rctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->block_size, SEEK_SET)) return EXT4_ERR_IO;
    return fread(buf, 1, c->block_size, c->fp) == c->block_size ? EXT4_OK : EXT4_ERR_IO;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <image> <oldpath> <newpath>\n", argv[0]);
        return 2;
    }
    const char *oldp = argv[2], *newp = argv[3];

    rctx rc = { fopen(argv[1], "rb"), 1024 };
    if (!rc.fp) { perror("open ro"); return 2; }
    ext4_fs r;
    if (ext4_open(&r, r_read, &rc) != EXT4_OK) { fprintf(stderr, "not ext4\n"); return 2; }
    rc.block_size = r.block_size;

    uint32_t src_parent = 0, dst_parent = 0;
    char src_name[256], dst_name[256];
    int prc = ext4_resolve_parent(&r, oldp, &src_parent, src_name, sizeof(src_name));
    if (prc != EXT4_PATH_OK) { fprintf(stderr, "resolve old parent: %d\n", prc); return 2; }
    prc = ext4_resolve_parent(&r, newp, &dst_parent, dst_name, sizeof(dst_name));
    if (prc != EXT4_PATH_OK) { fprintf(stderr, "resolve new parent: %d\n", prc); return 2; }

    ext4_wfs w;
    if (ext4_fs_open(&w, argv[1])) { fprintf(stderr, "open rw failed\n"); return 2; }

    int result = ext4_rename(&w, &r, src_parent, src_name, dst_parent, dst_name);
    ext4_fs_close(&w);
    fclose(rc.fp);

    if (result != EXT4_DIRW_OK) {
        fprintf(stderr, "rename failed: %d\n", result);
        return -result;                 /* the refusal's own code, negated */
    }
    printf("renamed %s -> %s\n", oldp, newp);
    return 0;
}
