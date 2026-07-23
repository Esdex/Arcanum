/*
 * Driver that reproduces the JNI write path (jni_ext4.cpp's ext4jni_write_file):
 * a file streamed in as a run of chunks, chunk 0 creating the file and every
 * later chunk appending at the current end. This is the exact composition the
 * media/file import uses, and the shape a device bug turned up in - a large file
 * written whole then rolled back because appends past the first chunk were
 * refused. The bytes are position-dependent so a chunk landing at the wrong
 * offset is caught, not just a wrong length.
 *
 *   chunkwrite <image> <path> <total-bytes> <chunk-bytes>
 *
 * The pattern at absolute byte i is a mix of i's low bytes, so every byte is a
 * function of exactly where it belongs. chunkcheck.py regenerates it.
 */
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64

#include "ext4_path.h"
#include "ext4_dirwrite.h"
#include "ext4_create.h"
#include "ext4_extwrite.h"
#include "ext4_extents.h"
#include "ext4_alloc.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define INODE_MODE_OFF 0x00
#define EXT4_S_IFMT    0xF000
#define EXT4_S_IFDIR   0x4000
#define WHEN 1784639915

/* The reader over the plain host file, and a writable handle over the same. */
typedef struct { FILE *fp; uint32_t block_size; } rctx;

static int r_read(void *ctx, uint64_t block, void *buf) {
    rctx *c = (rctx *)ctx;
    if (fseeko(c->fp, (off_t)block * c->block_size, SEEK_SET)) return EXT4_ERR_IO;
    return fread(buf, 1, c->block_size, c->fp) == c->block_size ? EXT4_OK : EXT4_ERR_IO;
}

/* The position-dependent pattern: byte i = i xor (i>>8) xor (i>>16), low 8 bits. */
static uint8_t pat(uint64_t i) {
    return (uint8_t)(i ^ (i >> 8) ^ (i >> 16));
}

typedef struct { uint64_t start; uint64_t len; uint32_t bs; uint32_t base_logical; } src_t;

static int fill(void *user, uint32_t logical, uint8_t *buf) {
    const src_t *s = (const src_t *)user;
    uint64_t chunk_off = (uint64_t)(logical - s->base_logical) * s->bs;
    for (uint32_t k = 0; k < s->bs; k++) {
        uint64_t rel = chunk_off + k;
        buf[k] = rel < s->len ? pat(s->start + rel) : 0;
    }
    return 0;
}

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: %s <image> <path> <total-bytes> <chunk-bytes>\n", argv[0]);
        return 2;
    }
    const char *path = argv[2];
    uint64_t total = strtoull(argv[3], NULL, 0);
    uint64_t chunk = strtoull(argv[4], NULL, 0);
    if (chunk == 0) return 2;

    rctx rc = { fopen(argv[1], "rb"), 1024 };
    if (!rc.fp) { perror("open ro"); return 2; }
    ext4_fs r;
    if (ext4_open(&r, r_read, &rc) != EXT4_OK) { fprintf(stderr, "not ext4\n"); return 2; }
    rc.block_size = r.block_size;

    uint32_t dir_ino = 0;
    char name[256];
    if (ext4_resolve_parent(&r, path, &dir_ino, name, sizeof(name)) != EXT4_PATH_OK) {
        fprintf(stderr, "cannot resolve parent of %s\n", path);
        return 1;
    }

    /* Write chunk by chunk, each its own open/flush/close, exactly like a run of
     * writeFile JNI calls. */
    for (uint64_t off = 0; off < total || (off == 0 && total == 0); off += chunk) {
        uint64_t n = total - off < chunk ? total - off : chunk;

        ext4_wfs w;
        if (ext4_fs_open(&w, argv[1])) { fprintf(stderr, "open rw failed\n"); return 1; }

        uint32_t ino = 0;
        if (off == 0) {
            uint32_t existing = 0;
            if (ext4_dir_lookup(&r, dir_ino, name, &existing) == EXT4_DIRW_OK)
                ext4_unlink_file(&w, &r, dir_ino, name, WHEN);
            if (ext4_create_file(&w, &r, dir_ino, name, 0644, WHEN, &ino) != EXT4_DIRW_OK) {
                fprintf(stderr, "create failed\n"); ext4_fs_close(&w); return 1;
            }
        } else {
            if (ext4_dir_lookup(&r, dir_ino, name, &ino) != EXT4_DIRW_OK) {
                fprintf(stderr, "lookup failed at off %llu\n", (unsigned long long)off);
                ext4_fs_close(&w); return 1;
            }
            uint8_t inode[256];
            memset(inode, 0, sizeof(inode));
            ext4_read_inode_raw(&r, ino, inode, sizeof(inode));
            uint64_t size = ext4_inode_size(inode);
            uint32_t bs = w.block_size;
            if (off != size || (size % bs) != 0) {
                fprintf(stderr, "append offset %llu != size %llu (bs %u)\n",
                        (unsigned long long)off, (unsigned long long)size, bs);
                ext4_fs_close(&w); return 1;
            }
        }

        if (n > 0) {
            src_t s = { off, n, w.block_size, (uint32_t)(off / w.block_size) };
            uint32_t nblocks = (uint32_t)((n + w.block_size - 1) / w.block_size);
            uint32_t appended = 0;
            int arc = ext4_append_blocks(&w, ino, nblocks, fill, &s, &appended);
            if (arc != EXTW_OK || appended != nblocks) {
                fprintf(stderr, "append failed (%d) at off %llu\n", arc,
                        (unsigned long long)off);
                ext4_fs_close(&w); return 1;
            }
            if (ext4_set_size(&w, ino, off + n) != EXTW_OK) {
                fprintf(stderr, "set_size failed at off %llu\n", (unsigned long long)off);
                ext4_fs_close(&w); return 1;
            }
        }
        ext4_fs_close(&w);
        if (total == 0) break;
    }

    (void)rd16; (void)EXT4_S_IFMT; (void)EXT4_S_IFDIR; (void)INODE_MODE_OFF;
    printf("wrote %llu bytes to %s in %llu-byte chunks\n",
           (unsigned long long)total, path, (unsigned long long)chunk);
    return 0;
}
