/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 *
 * The ext4 half of the file-operation surface.
 *
 * The FatFs entry points in jni_files.cpp dispatch here when the mounted container
 * is ext4 (ext4jni_is_container), so the file browser, gallery and DocumentsProvider
 * call the same nativeListFiles / nativeReadFile / ... they always have and never
 * learn which filesystem is underneath. Both sides therefore have to produce the
 * identical Java shapes - a NativeFileInfo[] here, a byte[] there, the same ERR_*
 * codes - which is the point of routing them through one entry rather than two.
 *
 * This layer is deliberately thin. Every non-trivial thing it does is a call into
 * the clean-room ext4 library in ext4/, which is host-verified against e2fsck and
 * fuse2fs and mutation-tested; the risky arithmetic lives there, where the harness
 * exercises it. Here there is only marshaling, locking and path lookup.
 *
 * Locking mirrors jni_files.cpp exactly: every entry takes g_fatfs_mutex, and the
 * dispatch check in jni_files.cpp must not already hold it. The reader (ext4_fs)
 * and writable handle (ext4_wfs) are opened fresh inside each operation and closed
 * at its end - the same open/do/flush/close shape the host drivers use and that
 * the mutation suites cover, rather than a long-lived handle whose cached
 * descriptor table could drift from disk across a failed operation.
 */

#include "arcanum_internal.h"

extern "C" {
#include "ext4/ext4_extents.h"
#include "ext4/ext4_dir.h"
#include "ext4/ext4_dirwrite.h"
#include "ext4/ext4_extwrite.h"
#include "ext4/ext4_alloc.h"
#include "ext4/ext4_create.h"
#include "ext4/ext4_path.h"
}
#include "ext4/ext4_device.h"

#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

/* The filesystem-type id ext4 reports through nativeGetFilesystem. FatFs uses 1-4
 * (FAT12/16/32/exFAT); 5 is ext4, and VeraCryptEngine.filesystemIdToString maps
 * it to the label. */
#define EXT4_FS_TYPE_ID 5

#define INODE_MODE_OFF  0x00
#define INODE_MTIME_OFF 0x10
#define EXT4_S_IFMT     0xF000
#define EXT4_S_IFDIR    0x4000

/* ─── superblock offsets (fs-usage only) ─────────────────────────────── */
#define SB_BLOCKS_LO_OFF        0x04
#define SB_FREE_BLOCKS_LO_OFF   0x0C
#define SB_LOG_BLOCK_SIZE_OFF   0x18
#define SB_BLOCKS_HI_OFF        0x150
#define SB_FREE_BLOCKS_HI_OFF   0x158
#define SB_OVERHEAD_OFF         0x248

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* ─── reader / writer setup ──────────────────────────────────────────── */

namespace {

/* The reader and its device context, kept together because the context carries
 * the block size the callback reads by and must outlive the ext4_fs. */
struct Reader {
    ext4_device_reader rd;
    ext4_fs            fs;
};

/* Caller holds g_fatfs_mutex. Opens the read-only view over g_drives[pdrv]. */
bool open_reader(int pdrv, Reader *out) {
    ext4_device_reader_init(&out->rd, &g_drives[pdrv]);
    if (ext4_open(&out->fs, ext4_device_read_block, &out->rd) != EXT4_OK) {
        LOGE("ext4: could not read the superblock on drive %d", pdrv);
        return false;
    }
    /* Reads after the superblock use the real block size; the bootstrap 1 KiB is
     * only for the superblock itself, exactly as the host img ctx does. */
    out->rd.block_size = out->fs.block_size;
    return true;
}

/* Caller holds g_fatfs_mutex. Opens the writable handle over the same drive.
 * ext4_fs_close releases it. */
bool open_writer(int pdrv, ext4_wfs *w) {
    if (ext4_fs_open_io(w, ext4_device_io(&g_drives[pdrv])) != 0) {
        LOGE("ext4: could not open drive %d for writing", pdrv);
        return false;
    }
    return true;
}

/* The pdrv behind a handle if it is an ext4 container, else -1. Caller holds
 * g_fatfs_mutex. */
int ext4_pdrv(jlong handle) {
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    auto it = g_ctxMap.find(pdrv);
    if (it == g_ctxMap.end() || !it->second->isExt4) return -1;
    return pdrv;
}

bool is_read_only(int pdrv) {
    auto it = g_ctxMap.find(pdrv);
    return it != g_ctxMap.end() && it->second->readOnly;
}

/* Maps a hidden-boundary trip on the drive to ERR_HIDDEN_BOUNDARY, clearing the
 * flag as jni_files.cpp's write_result_code does, so a refused write reports the
 * boundary rather than a generic filesystem error. */
jint write_error(int pdrv, jint fallback) {
    if (g_drives[pdrv].hiddenBoundaryTripped) {
        g_drives[pdrv].hiddenBoundaryTripped = false;
        return ERR_HIDDEN_BOUNDARY;
    }
    return fallback;
}

jint path_error(int rc) {
    switch (rc) {
    case EXT4_PATH_ENOENT: return ERR_FILE;
    default:               return ERR_FS;
    }
}

uint32_t now_seconds() { return (uint32_t)time(nullptr); }

} // namespace

/* ─── ext4jni_is_container ───────────────────────────────────────────── */

bool ext4jni_is_container(jlong handle) {
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    return ext4_pdrv(handle) >= 0;
}

/* ─── ext4jni_probe (mount-time detection) ───────────────────────────── */
/*
 * Reads the superblock through the decrypting device and checks the ext4 magic.
 * Called from do_open_container after the drive is up and before f_mount, so the
 * volume's own bytes - not the header - decide which filesystem it is. Read-only,
 * allocates nothing that outlives it.
 */
bool ext4jni_probe(int pdrv) {
    Reader r;
    if (!open_reader(pdrv, &r)) return false;
    /* ext4_open already verified the 0xEF53 magic and parsed the geometry; its
     * success is the probe. */
    LOGI("ext4: drive %d is ext4 (block size %u, %llu blocks)", pdrv,
         r.fs.block_size, (unsigned long long)r.fs.blocks_count);
    return true;
}

/* ─── ext4jni_get_filesystem ─────────────────────────────────────────── */

jint ext4jni_get_filesystem() { return EXT4_FS_TYPE_ID; }

/* ─── path helpers ───────────────────────────────────────────────────── */

namespace {

/* Builds the child path the FatFs listing uses: "/name" at the root, else
 * "dir/name". Keeps the two filesystems' NativeFileInfo.path identical. */
std::string child_path(const std::string &dir, const char *name) {
    if (dir.empty() || dir == "/") return std::string("/") + name;
    return dir + "/" + name;
}

struct DirEnt {
    std::string name;
    uint32_t    ino;
    uint8_t     ftype;
};

int collect_cb(void *user, const ext4_dir_entry *e) {
    auto *out = static_cast<std::vector<DirEnt> *>(user);
    if (e->inode == 0) return 0;
    if (e->name_len == 1 && e->name[0] == '.') return 0;
    if (e->name_len == 2 && e->name[0] == '.' && e->name[1] == '.') return 0;
    out->push_back(DirEnt{ std::string(e->name, e->name_len), e->inode, e->file_type });
    return 0;
}

} // namespace

/* ─── ext4jni_list_files ─────────────────────────────────────────────── */

jobjectArray ext4jni_list_files(JNIEnv *env, jlong handle, jstring jDirPath) {
    jclass infoCls;
    jmethodID ctor;
    if (g_jniCache.fileInfoCls && g_jniCache.fileInfoCtor) {
        infoCls = g_jniCache.fileInfoCls;
        ctor    = g_jniCache.fileInfoCtor;
    } else {
        infoCls = env->FindClass("zip/arcanum/crypto/NativeFileInfo");
        if (!infoCls) return nullptr;
        ctor = env->GetMethodID(infoCls, "<init>",
                                "(Ljava/lang/String;Ljava/lang/String;JZJ)V");
        if (!ctor) return env->NewObjectArray(0, infoCls, nullptr);
    }

    std::string dirPath = jstring_to_string(env, jDirPath);

    struct Entry {
        std::string name, path;
        uint64_t    size;
        bool        isDir;
        jlong       mtime;
    };
    std::vector<Entry> entries;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = ext4_pdrv(handle);
        if (pdrv < 0) return env->NewObjectArray(0, infoCls, nullptr);

        Reader r;
        if (!open_reader(pdrv, &r)) return env->NewObjectArray(0, infoCls, nullptr);

        uint32_t dir_ino = 0;
        int is_dir = 0;
        if (ext4_resolve_path(&r.fs, dirPath.c_str(), &dir_ino, &is_dir) != EXT4_PATH_OK
                || !is_dir)
            return env->NewObjectArray(0, infoCls, nullptr);

        uint8_t dir[256];
        memset(dir, 0, sizeof(dir));
        if (ext4_read_inode_raw(&r.fs, dir_ino, dir, sizeof(dir)) != EXT4_OK)
            return nullptr;

        std::vector<DirEnt> raw;
        if (ext4_dir_iterate(&r.fs, dir, collect_cb, &raw) != EXT4_OK) {
            LOGE("ext4: listing '%s' failed", dirPath.c_str());
            return nullptr;
        }

        for (const DirEnt &d : raw) {
            uint64_t size = 0;
            jlong    mtime = 0;
            uint8_t inode[256];
            memset(inode, 0, sizeof(inode));
            if (ext4_read_inode_raw(&r.fs, d.ino, inode, sizeof(inode)) == EXT4_OK) {
                size  = ext4_inode_size(inode);
                mtime = (jlong)rd32(inode + INODE_MTIME_OFF) * 1000LL;
            }
            /* Non-UTF-8 names cannot cross into a Java String cleanly; skip them
             * rather than leave a null hole in an array Kotlin declares non-null,
             * exactly as the FatFs listing does. */
            if (!is_valid_utf8(d.name.c_str())) {
                LOGE("ext4: skipping entry with a non-UTF-8 name");
                continue;
            }
            entries.push_back(Entry{
                d.name, child_path(dirPath, d.name.c_str()),
                size, d.ftype == EXT4_FT_DIR, mtime });
        }
    }

    jobjectArray result = env->NewObjectArray((jsize)entries.size(), infoCls, nullptr);
    if (!result) return env->NewObjectArray(0, infoCls, nullptr);

    for (size_t i = 0; i < entries.size(); i++) {
        const Entry &e = entries[i];
        jstring jName = utf8_to_jstring(env, e.name.c_str());
        jstring jPath = utf8_to_jstring(env, e.path.c_str());
        jobject fi    = env->NewObject(infoCls, ctor, jName, jPath,
                                       (jlong)e.size, (jboolean)(e.isDir ? 1 : 0),
                                       e.mtime);
        env->SetObjectArrayElement(result, (jsize)i, fi);
        if (jName) env->DeleteLocalRef(jName);
        if (jPath) env->DeleteLocalRef(jPath);
        if (fi)    env->DeleteLocalRef(fi);
    }
    return result;
}

/* ─── ext4jni_read_file ──────────────────────────────────────────────── */

jbyteArray ext4jni_read_file(JNIEnv *env, jlong handle, jstring jFilePath,
                             jlong offset, jint length) {
    if (length <= 0 || length > 16 * 1024 * 1024 || offset < 0)
        return env->NewByteArray(0);

    std::string path = jstring_to_string(env, jFilePath);

    auto *nativeBuf = static_cast<uint8_t *>(malloc((size_t)length));
    if (!nativeBuf) return env->NewByteArray(0);

    long produced = 0;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = ext4_pdrv(handle);
        if (pdrv < 0) { free(nativeBuf); return env->NewByteArray(0); }

        Reader r;
        if (!open_reader(pdrv, &r)) { free(nativeBuf); return env->NewByteArray(0); }

        uint32_t ino = 0;
        int is_dir = 0;
        if (ext4_resolve_path(&r.fs, path.c_str(), &ino, &is_dir) != EXT4_PATH_OK
                || is_dir) {
            free(nativeBuf);
            return env->NewByteArray(0);
        }

        uint8_t inode[256];
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(&r.fs, ino, inode, sizeof(inode)) != EXT4_OK) {
            free(nativeBuf);
            return nullptr;
        }

        produced = ext4_read_file(&r.fs, inode, (uint64_t)offset,
                                  nativeBuf, (uint64_t)length);
        if (produced < 0) {
            LOGE("ext4: reading '%s' failed (%ld)", path.c_str(), produced);
            free(nativeBuf);
            return nullptr;
        }
    }

    jbyteArray result = env->NewByteArray((jsize)produced);
    if (result && produced > 0)
        env->SetByteArrayRegion(result, 0, (jsize)produced, (const jbyte *)nativeBuf);
    free(nativeBuf);
    return result;
}

/* ─── ext4jni_write_file ─────────────────────────────────────────────── */
/*
 * Writes a whole file from byte 0. The extent writer only appends, so an
 * in-place write at a non-zero offset is not something this can do yet - it is
 * refused as ERR_UNSUPPORTED rather than silently mis-handled. Replacing a file
 * is unlink-then-create: the tested primitives, in the order that leaves the
 * filesystem consistent at every step.
 */
namespace {

struct WriteSrc {
    const uint8_t *data;
    uint64_t       len;
    uint32_t       bs;
};

int fill_from_src(void *user, uint32_t logical, uint8_t *buf) {
    const WriteSrc *s = static_cast<const WriteSrc *>(user);
    uint64_t off = (uint64_t)logical * s->bs;
    uint32_t n = 0;
    if (off < s->len) {
        uint64_t rem = s->len - off;
        n = rem < s->bs ? (uint32_t)rem : s->bs;
        memcpy(buf, s->data + off, n);
    }
    if (n < s->bs) memset(buf + n, 0, s->bs - n);   /* pad the last block */
    return 0;
}

} // namespace

jint ext4jni_write_file(JNIEnv *env, jlong handle, jstring jFilePath,
                        jbyteArray jData, jlong offset) {
    if (offset != 0) return ERR_UNSUPPORTED;   /* append-only writer, no in-place */

    std::string path = jstring_to_string(env, jFilePath);
    jsize len = env->GetArrayLength(jData);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    Reader r;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(&r.fs, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    ext4_wfs w;
    if (!open_writer(pdrv, &w)) return ERR_FS;
    jint result = ERR_OK;

    /* Replace: an existing file of the same name goes first. A directory of that
     * name is not a file to overwrite. */
    uint32_t existing = 0;
    int lrc = ext4_dir_lookup(&r.fs, dir_ino, name, &existing);
    if (lrc == EXT4_DIRW_OK) {
        uint8_t inode[256];
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(&r.fs, existing, inode, sizeof(inode)) == EXT4_OK &&
            (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR) {
            result = ERR_FS;
            goto done;
        }
        if (ext4_unlink_file(&w, &r.fs, dir_ino, name, now_seconds()) != EXT4_DIRW_OK) {
            result = write_error(pdrv, ERR_FS);
            goto done;
        }
    }

    {
        uint32_t ino = 0;
        int crc = ext4_create_file(&w, &r.fs, dir_ino, name, 0644, now_seconds(), &ino);
        if (crc != EXT4_DIRW_OK) {
            result = crc == EXT4_CREATE_ERR_NOINODE ? ERR_NO_SPACE
                                                    : write_error(pdrv, ERR_FS);
            goto done;
        }

        if (len > 0) {
            jbyte *data = env->GetByteArrayElements(jData, nullptr);
            if (!data) { result = ERR_FS; goto done; }

            uint32_t bs      = w.block_size;
            uint32_t nblocks = (uint32_t)(((uint64_t)len + bs - 1) / bs);
            WriteSrc src{ (const uint8_t *)data, (uint64_t)len, bs };
            uint32_t appended = 0;
            int arc = ext4_append_blocks(&w, ino, nblocks, fill_from_src, &src, &appended);
            env->ReleaseByteArrayElements(jData, data, JNI_ABORT);

            if (arc != EXTW_OK || appended != nblocks) {
                result = write_error(pdrv, arc == EXTW_ERR_NOSPACE ? ERR_NO_SPACE : ERR_FS);
                goto done;
            }
            if (ext4_set_size(&w, ino, (uint64_t)len) != EXTW_OK) {
                result = write_error(pdrv, ERR_FS);
                goto done;
            }
        }
    }

done:
    ext4_fs_close(&w);
    return result;
}

/* ─── ext4jni_create_directory ───────────────────────────────────────── */

jint ext4jni_create_directory(JNIEnv *env, jlong handle, jstring jDirPath) {
    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    Reader r;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(&r.fs, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    ext4_wfs w;
    if (!open_writer(pdrv, &w)) return ERR_FS;

    uint32_t ino = 0;
    int rc = ext4_mkdir(&w, &r.fs, dir_ino, name, 0755, now_seconds(), &ino);
    ext4_fs_close(&w);

    if (rc == EXT4_DIRW_OK) return ERR_OK;
    if (rc == EXT4_CREATE_ERR_NOINODE || rc == EXT4_DIRW_ERR_NOROOM) return ERR_NO_SPACE;
    return write_error(pdrv, ERR_FS);
}

/* ─── ext4jni_delete_file ────────────────────────────────────────────── */

jint ext4jni_delete_file(JNIEnv *env, jlong handle, jstring jFilePath) {
    std::string path = jstring_to_string(env, jFilePath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    Reader r;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    /* Refuse a directory here: removing one is nativeDeleteDirectory, which is
     * recursive and moves the counters a directory needs. */
    uint32_t ino = 0;
    int is_dir = 0;
    int rrc = ext4_resolve_path(&r.fs, path.c_str(), &ino, &is_dir);
    if (rrc != EXT4_PATH_OK) return path_error(rrc);
    if (is_dir) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(&r.fs, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    ext4_wfs w;
    if (!open_writer(pdrv, &w)) return ERR_FS;
    int rc = ext4_unlink_file(&w, &r.fs, dir_ino, name, now_seconds());
    ext4_fs_close(&w);

    return rc == EXT4_DIRW_OK ? ERR_OK : write_error(pdrv, ERR_FS);
}

/* ─── ext4jni_delete_directory (recursive) ───────────────────────────── */
/*
 * ext4_rmdir refuses a non-empty directory - a populated one removed strands every
 * inode below it - so a recursive delete empties it first. Children are snapshotted
 * before any are removed, never iterated while being modified, and the recursion is
 * depth-bounded against a hostile or corrupt tree.
 */
namespace {

int empty_directory(ext4_wfs *w, const ext4_fs *r, uint32_t dir_ino,
                    uint32_t when, int depth) {
    if (depth > 64) return ERR_FS;

    uint8_t dir[256];
    memset(dir, 0, sizeof(dir));
    if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK) return ERR_FS;

    std::vector<DirEnt> children;
    if (ext4_dir_iterate(r, dir, collect_cb, &children) != EXT4_OK) return ERR_FS;

    for (const DirEnt &c : children) {
        if (c.ftype == EXT4_FT_DIR) {
            int rc = empty_directory(w, r, c.ino, when, depth + 1);
            if (rc != ERR_OK) return rc;
            if (ext4_rmdir(w, r, dir_ino, c.name.c_str(), when) != EXT4_DIRW_OK)
                return ERR_FS;
        } else {
            if (ext4_unlink_file(w, r, dir_ino, c.name.c_str(), when) != EXT4_DIRW_OK)
                return ERR_FS;
        }
    }
    return ERR_OK;
}

} // namespace

jint ext4jni_delete_directory(JNIEnv *env, jlong handle, jstring jDirPath) {
    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    Reader r;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t ino = 0;
    int is_dir = 0;
    int rrc = ext4_resolve_path(&r.fs, path.c_str(), &ino, &is_dir);
    if (rrc != EXT4_PATH_OK) return path_error(rrc);
    if (!is_dir) return ERR_FS;

    uint32_t parent_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(&r.fs, path.c_str(), &parent_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);   /* EINVAL for the root */

    ext4_wfs w;
    if (!open_writer(pdrv, &w)) return ERR_FS;

    uint32_t when = now_seconds();
    int rc = empty_directory(&w, &r.fs, ino, when, 0);
    if (rc == ERR_OK && ext4_rmdir(&w, &r.fs, parent_ino, name, when) != EXT4_DIRW_OK)
        rc = ERR_FS;
    ext4_fs_close(&w);

    return rc == ERR_OK ? ERR_OK : write_error(pdrv, rc);
}

/* ─── ext4jni_rename ─────────────────────────────────────────────────── */

jint ext4jni_rename(JNIEnv *env, jlong handle, jstring /*jOld*/, jstring /*jNew*/) {
    (void)env; (void)handle;
    /* Rename needs a primitive the ext4 library does not have yet: moving an entry
     * between directories, and for a directory re-parenting its "..". Refused
     * rather than approximated, since a half-done move corrupts. */
    return ERR_UNSUPPORTED;
}

/* ─── ext4jni_fs_usage ───────────────────────────────────────────────── */

jlongArray ext4jni_fs_usage(JNIEnv *env, jlong handle) {
    jlong out[2] = { 0, 0 };
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = ext4_pdrv(handle);
        if (pdrv < 0) return nullptr;

        /* The superblock alone answers this, so it is read raw rather than opening
         * the writable handle: block 1 at the provisional 1 KiB view is the
         * superblock at byte 1024. */
        ext4_device_reader rd;
        ext4_device_reader_init(&rd, &g_drives[pdrv]);
        uint8_t sb[1024];
        if (ext4_device_read_block(&rd, 1, sb) != EXT4_OK) return nullptr;

        uint32_t bs = 1024u << rd32(sb + SB_LOG_BLOCK_SIZE_OFF);
        uint64_t blocks = (uint64_t)rd32(sb + SB_BLOCKS_LO_OFF) |
                          ((uint64_t)rd32(sb + SB_BLOCKS_HI_OFF) << 32);
        uint64_t freeBlocks = (uint64_t)rd32(sb + SB_FREE_BLOCKS_LO_OFF) |
                              ((uint64_t)rd32(sb + SB_FREE_BLOCKS_HI_OFF) << 32);
        uint64_t overhead = rd32(sb + SB_OVERHEAD_OFF);

        /* Report the space a file can actually occupy: total minus the blocks the
         * filesystem spends describing itself, matching what the FatFs path does
         * by counting data clusters rather than the whole volume. */
        uint64_t usable = (overhead > 0 && overhead < blocks) ? blocks - overhead : blocks;
        out[0] = (jlong)(usable * (uint64_t)bs);
        out[1] = (jlong)(freeBlocks * (uint64_t)bs);
    }

    jlongArray arr = env->NewLongArray(2);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 2, out);
    return arr;
}
