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
 * dispatch check in jni_files.cpp must not already hold it.
 *
 * The reader (ext4_fs) and the writable handle (ext4_wfs) are borrowed from the
 * drive for the length of an operation and belong to the mount, not to the call
 * (#155). They used to be opened fresh inside each operation and closed at its
 * end, which cost a superblock read and a re-read of the whole descriptor table
 * every time - the reason the same few blocks came back thousands of times in one
 * session. What that shape bought was self-healing: an operation that failed
 * halfway left its wreckage in memory, and the next one re-read everything. That
 * property is now a rule held by ext4_session.c, which poisons the writable handle
 * when a write through it fails and reopens on the next ask, and it is checked by
 * sessioncheck.py against the open-per-operation shape the host drivers still use.
 */

#include "arcanum_file_kind.h"
#include "arcanum_internal.h"

extern "C" {
#include "ext4/ext4_extents.h"
#include "ext4/ext4_dir.h"
#include "ext4/ext4_dirwrite.h"
#include "ext4/ext4_extwrite.h"
#include "ext4/ext4_alloc.h"
#include "ext4/ext4_create.h"
#include "ext4/ext4_path.h"
#include "ext4/ext4_mkfs.h"
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

#define INODE_MODE_OFF        0x00
#define INODE_MTIME_OFF       0x10
#define INODE_LINKS_COUNT_OFF 0x1A
#define EXT4_S_IFMT     0xF000
#define EXT4_S_IFDIR    0x4000
#define EXT4_S_IFREG    0x8000
#define EXT4_S_IFLNK    0xA000

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

/*
 * Caller holds g_fatfs_mutex. The read-only view over g_drives[pdrv].
 *
 * Borrowed from the drive's session (#155), not opened here: it is opened once for
 * the mount and handed to every operation after that, rather than re-read and
 * re-parsed from the superblock on each one. Nothing here closes it - see
 * ext4_session.h for what that costs and the rules that make it safe.
 */
bool open_reader(int pdrv, ext4_fs **out) {
    if (ext4_device_session_reader(&g_drives[pdrv], out) != 0) {
        LOGE("ext4: could not read the superblock on drive %d", pdrv);
        return false;
    }
    return true;
}

/* The wall clock, in whole seconds. Every timestamp this file writes comes from here:
 * the library itself never reads a clock, so that the same inputs give the same image
 * for the host stands. */
uint32_t now_seconds() { return (uint32_t)time(nullptr); }

/*
 * Caller holds g_fatfs_mutex. The writable handle over the same drive.
 *
 * Borrowed from the drive's session like the reader (#155), so the group
 * descriptor table is read into memory once for the mount rather than on every
 * operation. The clock is passed on each ask, not once at open: the superblock's
 * last-write time (#156) has to record the operation happening, not the one that
 * opened the mount.
 */
bool open_writer(int pdrv, ext4_wfs **w) {
    if (ext4_device_session_writer(&g_drives[pdrv], now_seconds(), w) != 0) {
        LOGE("ext4: could not open drive %d for writing", pdrv);
        return false;
    }
    return true;
}

/*
 * One write session on a drive, bracketed so the volume says on disk whether a
 * write is outstanding (#142).
 *
 * There is no journal, so an operation cut short - the app killed, the battery
 * gone, a drive pulled - leaves whatever its last completed write put there.
 * faultcheck.py sweeps every write of every operation and establishes that it is
 * always something e2fsck repairs without costing anything else on the volume.
 * That is only worth having if something says a repair is due, and nothing did:
 * s_state was stamped clean by the formatter and never touched again.
 *
 * So: mark not-clean before the first write, mark clean again in the destructor,
 * which every orderly return runs and a killed process does not. That is exactly
 * the distinction wanted - the flag means "a session started and did not finish",
 * not "an operation failed".
 *
 * tear() is for the case in between: the operation returned, but it returned
 * because a write failed, so a residual is on disk and the mark has to stay. It
 * sits next to the write_error() calls, which is where this file already decides
 * that a write went wrong.
 *
 * The bracket also removes the six hand-written ext4_fs_close calls per exit path
 * that used to be here, one of which is how a session leaked on an error return.
 *
 * Since #155 the handle itself outlives this object - it belongs to the drive, not
 * to the operation - so what is bracketed is the mark, not the open. Tearing is
 * therefore two things now rather than one: the volume keeps its needs-a-check
 * mark, AND the drive forgets the handle, because an operation abandoned part way
 * leaves allocations in memory that never reached the disk. That second half used
 * to happen by itself, in the close at the end of every operation.
 */
class WriteSession {
public:
    explicit WriteSession(int pdrv) : pdrv_(pdrv) {
        open_ = open_writer(pdrv, &w_);
        if (open_ && ext4_fs_mark_dirty(w_) != 0) {
            LOGE("ext4: could not mark drive %d as being written to", pdrv);
            ext4_device_session_drop(&g_drives[pdrv]);
            open_ = false;
        }
    }
    ~WriteSession() {
        if (!open_) return;
        if (torn_) {
            ext4_device_session_drop(&g_drives[pdrv_]);
            return;
        }
        /* A mark_clean that fails has already poisoned the session through the
         * write that failed inside it, so the next operation opens afresh; there
         * is nothing further to do here. */
        ext4_fs_mark_clean(w_);
    }
    WriteSession(const WriteSession &) = delete;
    WriteSession &operator=(const WriteSession &) = delete;

    bool ok() const { return open_; }
    ext4_wfs *fs() { return w_; }

    /* Leaves the volume marked as needing a check, and the drive without a handle. */
    void tear() { torn_ = true; }

private:
    int pdrv_;
    ext4_wfs *w_ = nullptr;
    bool open_ = false;
    bool torn_ = false;
};

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
bool ext4jni_probe(int pdrv, bool *needs_check_out) {
    /*
     * Deliberately NOT the drive's session (#155). Two reasons, and both matter.
     * This runs before the volume is known to be ext4 at all - it is what decides
     * that - so a FAT drive would be left holding a session it will never use. And
     * it is the one place `is_clean` is read, which is the single field in a
     * reader that moves while a volume is mounted; a held handle answers with what
     * s_state said when it was opened. See the note in ext4_session.h.
     */
    ext4_device_reader rd;
    ext4_device_reader_init(&rd, &g_drives[pdrv]);
    ext4_fs fs;
    if (ext4_open(&fs, ext4_device_read_block, &rd) != EXT4_OK) return false;
    /* ext4_open already verified the 0xEF53 magic and parsed the geometry; its
     * success is the probe. */
    LOGI("ext4: drive %d is ext4 (block size %u, %llu blocks)", pdrv,
         fs.block_size, (unsigned long long)fs.blocks_count);

    /* Read here and reported, never acted on. The volume mounts either way: what a
     * cut-short write leaves is always something a check repairs without cost
     * (faultcheck.py sweeps every write of every operation to establish that), so
     * refusing to open would take the user's data away over something that is not
     * a threat to it. See #142. */
    if (needs_check_out) *needs_check_out = !fs.is_clean;
    if (!fs.is_clean)
        LOGI("ext4: drive %d was left mid-write - a check is owed", pdrv);
    return true;
}

/* ─── ext4jni_get_filesystem ─────────────────────────────────────────── */

jint ext4jni_get_filesystem() { return EXT4_FS_TYPE_ID; }

/* ─── ext4jni_format (container creation) ────────────────────────────── */
/*
 * Lays down a fresh ext4 filesystem over the whole data area, the counterpart of
 * f_mkfs on the FatFs path. Called from do_create_container after the drive is up,
 * so every block goes through the cipher - the container's medium is the random
 * data the create path already filled, which is exactly the "format over random
 * bytes" case the formatter's harness covers.
 *
 * The geometry follows ext4_mkfs_default_params for the size, and the UUID and
 * directory-hash seed come from /dev/urandom. They are not keys - the whole
 * filesystem, superblock included, is encrypted on disk - but they must be random
 * so two containers do not share a checksum seed (which is derived from the UUID).
 *
 * Caller holds g_fatfs_mutex.
 */
bool ext4jni_format(int pdrv, uint64_t dataSize) {
    ext4_mkfs_params p;
    memset(&p, 0, sizeof(p));
    ext4_mkfs_default_params(&p, dataSize);
    p.when = now_seconds();
    if (!read_urandom(p.uuid, sizeof(p.uuid)) ||
        !read_urandom(p.hash_seed, sizeof(p.hash_seed))) {
        LOGE("ext4 format: /dev/urandom failed");
        return false;
    }

    ext4_io io = ext4_device_io(&g_drives[pdrv]);
    ext4_mkfs_result r;
    int rc = ext4_mkfs(&io, &p, &r);
    if (rc != EXT4_MKFS_OK) {
        LOGE("ext4 format: mkfs failed (%d) for %llu bytes", rc,
             (unsigned long long)dataSize);
        return false;
    }
    /* Every handle that could be held describes the filesystem that was just
     * written over - a different geometry, a different checksum seed. mkfs writes
     * through the drive's io directly, so no failed write reported anything and
     * nothing else would notice (#155). */
    ext4_device_session_drop(&g_drives[pdrv]);
    LOGI("ext4 format: %llu blocks of %u, %u groups, %u inodes",
         (unsigned long long)r.blocks_count, r.block_size, r.groups, r.inodes_count);
    return true;
}

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
                                "(Ljava/lang/String;Ljava/lang/String;JZJILjava/lang/String;ZZIJ)V");
        if (!ctor) return env->NewObjectArray(0, infoCls, nullptr);
    }

    std::string dirPath = jstring_to_string(env, jDirPath);

    struct Entry {
        std::string name, path;
        uint64_t    size;
        bool        isDir;
        jlong       mtime;
        jint        kind;
        std::string linkTarget;   /* empty unless kind is a symlink */
        bool        targetIsDir;
        bool        broken;
        jint        names;
        jlong       inode;      /* what the entry LEADS TO - a link's target, 0 if dead */
    };
    std::vector<Entry> entries;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = ext4_pdrv(handle);
        if (pdrv < 0) return env->NewObjectArray(0, infoCls, nullptr);

        ext4_fs *r = nullptr;
        if (!open_reader(pdrv, &r)) return env->NewObjectArray(0, infoCls, nullptr);

        uint32_t dir_ino = 0;
        int is_dir = 0;
        if (ext4_resolve_path(r, dirPath.c_str(), &dir_ino, &is_dir) != EXT4_PATH_OK
                || !is_dir)
            return env->NewObjectArray(0, infoCls, nullptr);

        uint8_t dir[EXT4_MAX_INODE_SIZE];
        memset(dir, 0, sizeof(dir));
        if (ext4_read_inode_raw(r, dir_ino, dir, sizeof(dir)) != EXT4_OK)
            return nullptr;

        std::vector<DirEnt> raw;
        if (ext4_dir_iterate(r, dir, collect_cb, &raw) != EXT4_OK) {
            LOGE("ext4: listing '%s' failed", dirPath.c_str());
            return nullptr;
        }

        /* One buffer for the whole listing rather than one per entry: a symlink
         * target may be as long as a path, and a directory can hold thousands. */
        std::string target;
        target.resize(EXT4_PATH_MAX + 1);

        for (const DirEnt &d : raw) {
            uint64_t size = 0;
            jlong    mtime = 0;
            jint     kind = d.ftype == EXT4_FT_DIR ? ARC_KIND_DIRECTORY
                                                   : ARC_KIND_REGULAR;
            bool     isDir = d.ftype == EXT4_FT_DIR;
            std::string linkTarget;
            bool     targetIsDir = false, broken = false;
            jint     names = 1;
            jlong    ino   = (jlong)d.ino;

            uint8_t inode[EXT4_MAX_INODE_SIZE];
            memset(inode, 0, sizeof(inode));
            if (ext4_read_inode_raw(r, d.ino, inode, sizeof(inode)) == EXT4_OK) {
                size  = ext4_inode_size(inode);
                mtime = (jlong)rd32(inode + INODE_MTIME_OFF) * 1000LL;
                /* The only visible trace a hard link leaves: the file itself is
                 * unchanged, so how many names it has is the one thing that says
                 * a second one was made rather than a copy (#128). */
                names = (jint)rd16(inode + INODE_LINKS_COUNT_OFF);

                /*
                 * The kind comes from the inode's mode rather than the directory
                 * entry's type byte. Both are meant to agree and on our own volumes
                 * they do (#147), but the entry's byte is only there at all when the
                 * volume carries the filetype feature - a volume made without it
                 * says 0 for everything - while the mode is always right.
                 */
                uint16_t fmt = rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT;
                switch (fmt) {
                case EXT4_S_IFDIR: kind = ARC_KIND_DIRECTORY; isDir = true;  break;
                case EXT4_S_IFREG: kind = ARC_KIND_REGULAR;   isDir = false; break;
                case EXT4_S_IFLNK: kind = ARC_KIND_SYMLINK;   isDir = false; break;
                default:           kind = ARC_KIND_SPECIAL;   isDir = false; break;
                }

                if (kind == ARC_KIND_SYMLINK) {
                    /*
                     * Three things about a link a listing has to carry, and none of
                     * them are what the inode says on its own.
                     *
                     * Its target, because a dead link that cannot say what it was
                     * looking for is indistinguishable from a broken app. Whether
                     * following it lands on a directory, so opening it can go
                     * somewhere. And its SIZE, which is the target's rather than the
                     * link's: i_size on a symlink is the length of the path string,
                     * which is how a link to a film used to list as eleven bytes.
                     */
                    long tlen = ext4_readlink(r, inode, &target[0], target.size());
                    if (tlen >= 0) linkTarget.assign(target.c_str(), (size_t)tlen);

                    std::string full = child_path(dirPath, d.name.c_str());
                    uint32_t tino = 0;
                    int tdir = 0;
                    if (linkTarget.empty() ||
                        ext4_resolve_path(r, full.c_str(), &tino, &tdir) != EXT4_PATH_OK) {
                        broken = true;
                        size   = 0;
                        ino    = 0;
                    } else {
                        targetIsDir = tdir != 0;
                        /* The target's inode, not the link's: two names for one
                         * file must compare equal whichever kind of link made the
                         * second one (#167). */
                        ino = (jlong)tino;
                        uint8_t tnode[EXT4_MAX_INODE_SIZE];
                        memset(tnode, 0, sizeof(tnode));
                        size = ext4_read_inode_raw(r, tino, tnode, sizeof(tnode)) == EXT4_OK
                                   ? ext4_inode_size(tnode) : 0;
                    }
                }
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
                size, isDir, mtime, kind, linkTarget, targetIsDir, broken, names,
                ino });
        }
    }

    jobjectArray result = env->NewObjectArray((jsize)entries.size(), infoCls, nullptr);
    if (!result) return env->NewObjectArray(0, infoCls, nullptr);

    for (size_t i = 0; i < entries.size(); i++) {
        const Entry &e = entries[i];
        jstring jName = utf8_to_jstring(env, e.name.c_str());
        jstring jPath = utf8_to_jstring(env, e.path.c_str());
        jstring jTarget = e.linkTarget.empty()
                              ? nullptr
                              : utf8_to_jstring(env, e.linkTarget.c_str());
        jobject fi    = env->NewObject(infoCls, ctor, jName, jPath,
                                       (jlong)e.size, (jboolean)(e.isDir ? 1 : 0),
                                       e.mtime, e.kind, jTarget,
                                       (jboolean)(e.targetIsDir ? 1 : 0),
                                       (jboolean)(e.broken ? 1 : 0), e.names,
                                       e.inode);
        env->SetObjectArrayElement(result, (jsize)i, fi);
        if (jName)   env->DeleteLocalRef(jName);
        if (jPath)   env->DeleteLocalRef(jPath);
        if (jTarget) env->DeleteLocalRef(jTarget);
        if (fi)      env->DeleteLocalRef(fi);
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

        ext4_fs *r = nullptr;
        if (!open_reader(pdrv, &r)) { free(nativeBuf); return env->NewByteArray(0); }

        uint32_t ino = 0;
        int is_dir = 0;
        if (ext4_resolve_path(r, path.c_str(), &ino, &is_dir) != EXT4_PATH_OK
                || is_dir) {
            free(nativeBuf);
            return env->NewByteArray(0);
        }

        uint8_t inode[EXT4_MAX_INODE_SIZE];
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) {
            free(nativeBuf);
            return nullptr;
        }

        produced = ext4_read_file(r, inode, (uint64_t)offset,
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
 * The extent writer only appends, so this supports the two writes a large file
 * is actually streamed in with, and refuses the rest:
 *
 *   offset 0            replace the file - unlink any existing one, create it
 *                       fresh, write this chunk as its whole contents.
 *   offset == its size  append at the end. A big file arrives as 1 MiB chunks
 *                       written back to back (FileManagerViewModel and the media
 *                       import), so every chunk but the first lands here, exactly
 *                       at the current end of a block-aligned file.
 *   anything else       a mid-file overwrite or a gap past the end - a positional
 *                       write the append-only writer cannot do. ERR_UNSUPPORTED.
 *
 * The old version accepted only offset 0 and rejected every later chunk, which
 * made importing any file over 1 MiB fail after the first chunk and get rolled
 * back - the file was written whole and then deleted.
 */
namespace {

struct WriteSrc {
    const uint8_t *data;
    uint64_t       len;           /* bytes in this chunk */
    uint32_t       bs;
    uint32_t       base_logical;  /* file-logical block of the first byte here */
};

int fill_from_src(void *user, uint32_t logical, uint8_t *buf) {
    const WriteSrc *s = static_cast<const WriteSrc *>(user);
    /* logical is the file-absolute block; the chunk starts at base_logical, so the
     * data offset is measured from there rather than from the start of the file. */
    uint64_t off = (uint64_t)(logical - s->base_logical) * s->bs;
    uint32_t n = 0;
    if (off < s->len) {
        uint64_t rem = s->len - off;
        n = rem < s->bs ? (uint32_t)rem : s->bs;
        memcpy(buf, s->data + off, n);
    }
    if (n < s->bs) memset(buf + n, 0, s->bs - n);   /* pad the last block */
    return 0;
}

/*
 * Appends this chunk to `ino`, whose data currently ends at byte `start` (a whole
 * number of blocks), and trims the length to `final_size`. Caller holds the lock
 * and owns `w`.
 */
jint write_chunk(JNIEnv *env, int pdrv, ext4_wfs *w, uint32_t ino,
                 jbyteArray jData, jsize len, uint64_t start, uint64_t final_size) {
    if (len <= 0) return ERR_OK;   /* an empty chunk leaves the file as it is */

    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    if (!data) return ERR_FS;

    uint32_t bs      = w->block_size;
    uint32_t nblocks = (uint32_t)(((uint64_t)len + bs - 1) / bs);
    WriteSrc src{ (const uint8_t *)data, (uint64_t)len, bs, (uint32_t)(start / bs) };
    uint32_t appended = 0;
    int arc = ext4_append_blocks(w, ino, nblocks, fill_from_src, &src, &appended);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);

    /* EXTW_ERR_FULL used to be folded into ERR_NO_SPACE, on the grounds that either
     * way the file will not fit and the user does the same thing next. It carries its
     * own code now (#125), because the two say opposite things about the volume: this
     * one fires on a volume with room to spare, and reporting it as "no space" would
     * send a bug report looking for a space problem that is not there - the three
     * weeks #114 cost me. Since #119 it is raised at one place only, the depth limit
     * the format itself sets (ext4_extwrite.c), so it is nearer unreachable than rare.
     * If it ever does turn up in a report, it needs to name itself. */
    if (arc != EXTW_OK || appended != nblocks)
        return write_error(pdrv, arc == EXTW_ERR_NOSPACE ? ERR_NO_SPACE
                               : arc == EXTW_ERR_FULL    ? ERR_TOO_FRAGMENTED
                                                         : ERR_FS);
    if (ext4_set_size(w, ino, final_size) != EXTW_OK)
        return write_error(pdrv, ERR_FS);
    /* The content changed, so move the modification time. Best-effort: the data is
     * already committed, and a stale timestamp is not worth failing an import. */
    ext4_set_mtime(w, ino, now_seconds());
    return ERR_OK;
}

/* offset 0: the chunk is the file's whole new contents. */
jint write_from_zero(JNIEnv *env, int pdrv, ext4_wfs *w, const ext4_fs *r,
                     uint32_t dir_ino, const char *name, jbyteArray jData, jsize len) {
    uint32_t existing = 0;
    int lrc = ext4_dir_lookup(r, dir_ino, name, &existing);
    if (lrc == EXT4_DIRW_OK) {
        uint8_t inode[EXT4_MAX_INODE_SIZE];
        memset(inode, 0, sizeof(inode));
        /* A failed read must refuse, not fall through. Without the mode there is no
         * way to tell a file from a directory, and unlinking a directory as if it
         * were a file frees it wrongly. The old `read == OK && IFDIR` let a read
         * error skip the guard and reach the unlink. */
        if (ext4_read_inode_raw(r, existing, inode, sizeof(inode)) != EXT4_OK)
            return ERR_FS;
        if ((rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR)
            return ERR_FS;   /* a directory is not a file to overwrite */
        if (ext4_unlink_file(w, r, dir_ino, name, now_seconds()) != EXT4_DIRW_OK)
            return write_error(pdrv, ERR_FS);
    }

    uint32_t ino = 0;
    int crc = ext4_create_file(w, r, dir_ino, name, 0644, now_seconds(), &ino);
    if (crc != EXT4_DIRW_OK)
        return crc == EXT4_CREATE_ERR_NOINODE ? ERR_NO_SPACE : write_error(pdrv, ERR_FS);

    return write_chunk(env, pdrv, w, ino, jData, len, /*start=*/0, /*final=*/(uint64_t)len);
}

/* offset > 0: append this chunk at the end of the existing file. */
jint append_at_eof(JNIEnv *env, int pdrv, ext4_wfs *w, const ext4_fs *r,
                   uint32_t dir_ino, const char *name, jbyteArray jData, jsize len,
                   uint64_t offset) {
    uint32_t ino = 0;
    int lrc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (lrc == EXT4_DIRW_ERR_ABSENT) return ERR_FILE;   /* nothing to append to */
    if (lrc != EXT4_DIRW_OK) return ERR_FS;

    uint8_t inode[EXT4_MAX_INODE_SIZE];
    memset(inode, 0, sizeof(inode));
    if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) return ERR_FS;
    if ((rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR) return ERR_FS;

    uint64_t size = ext4_inode_size(inode);
    uint32_t bs   = w->block_size;
    /* Only a sequential append onto a block-aligned end can be done by appending.
     * Chunked import always writes here exactly, chunk after chunk; anything else
     * is a positional write. */
    if (offset != size || (size % bs) != 0) {
        LOGE("ext4: write at offset %llu but file is %llu bytes (block %u) - only a "
             "sequential block-aligned append is supported",
             (unsigned long long)offset, (unsigned long long)size, bs);
        return ERR_UNSUPPORTED;
    }

    return write_chunk(env, pdrv, w, ino, jData, len, /*start=*/offset,
                       /*final=*/offset + (uint64_t)len);
}

} // namespace

jint ext4jni_write_file(JNIEnv *env, jlong handle, jstring jFilePath,
                        jbyteArray jData, jlong offset) {
    std::string path = jstring_to_string(env, jFilePath);
    jsize len = env->GetArrayLength(jData);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    jint result = (offset == 0)
        ? write_from_zero(env, pdrv, s.fs(), r, dir_ino, name, jData, len)
        : append_at_eof(env, pdrv, s.fs(), r, dir_ino, name, jData, len, (uint64_t)offset);

    /* The two helpers reach write_error() inside themselves, so the decision has
     * to be made on what came back. Everything left over from ERR_OK, a full
     * volume and a missing file means either a write that failed or a volume that
     * would not read - both of which are worth a check.
     *
     * ERR_TOO_FRAGMENTED belongs with the full volume and not with the failures:
     * an append that stops short still commits what it placed and leaves the
     * counters agreeing with it (see ext4_append_blocks), so the volume is as
     * sound after the refusal as before it. It used to arrive here as
     * ERR_NO_SPACE, and leaving it out of this list would have made a change
     * about wording start flagging vaults for a check. */
    if (result != ERR_OK && result != ERR_NO_SPACE &&
        result != ERR_TOO_FRAGMENTED && result != ERR_FILE)
        s.tear();
    return result;
}

/* ─── ext4jni_write_at ───────────────────────────────────────────────── */
/*
 * The non-truncating positional write behind nativeWriteAt (the SAF
 * DocumentsProvider). Unlike ext4jni_write_file - which recreates the file at
 * offset 0, the right thing for a chunked import - this opens the file and writes
 * where asked without discarding the rest, so an app seeking backward to patch a
 * header does not lose everything after it. The file is created if absent; an
 * empty array just touches it into being. All the real work is ext4_write_at.
 */
jint ext4jni_write_at(JNIEnv *env, jlong handle, jstring jFilePath,
                      jbyteArray jData, jlong offset) {
    std::string path = jstring_to_string(env, jFilePath);
    jsize len = env->GetArrayLength(jData);
    if (offset < 0) return ERR_FS;

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    /* Opened, not recreated: create only when the name is not there, and never
     * write over a directory of that name. */
    uint32_t ino = 0;
    int lrc = ext4_dir_lookup(r, dir_ino, name, &ino);
    if (lrc == EXT4_DIRW_ERR_ABSENT) {
        int crc = ext4_create_file(s.fs(), r, dir_ino, name, 0644, now_seconds(), &ino);
        if (crc != EXT4_DIRW_OK) {
            if (crc == EXT4_CREATE_ERR_NOINODE) return ERR_NO_SPACE;
            s.tear();
            return write_error(pdrv, ERR_FS);
        }
    } else if (lrc != EXT4_DIRW_OK) {
        s.tear();                       /* the directory would not read */
        return ERR_FS;
    } else {
        uint8_t inode[EXT4_MAX_INODE_SIZE];
        memset(inode, 0, sizeof(inode));
        if (ext4_read_inode_raw(r, ino, inode, sizeof(inode)) != EXT4_OK) {
            s.tear();                   /* nor would the inode */
            return ERR_FS;
        }
        /* Not torn: nothing was written and nothing is wrong with the volume -
         * the caller asked to write into a directory. */
        if ((rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR)
            return ERR_FS;
    }

    jint result = ERR_OK;
    if (len > 0) {
        jbyte *data = env->GetByteArrayElements(jData, nullptr);
        if (!data) return ERR_FS;
        int wrc = ext4_write_at(s.fs(), r, ino, (uint64_t)offset,
                                (const uint8_t *)data, (uint32_t)len);
        env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
        /* Split apart for the reason write_chunk() gives at length: a refusal on a
         * volume with room left has to be distinguishable from a full one (#125). */
        if (wrc != EXTW_OK) {
            if (wrc == EXTW_ERR_NOSPACE) {
                result = ERR_NO_SPACE;
            } else if (wrc == EXTW_ERR_FULL) {
                result = ERR_TOO_FRAGMENTED;
            } else if (wrc == EXTW_ERR_RANGE) {
                result = ERR_UNSUPPORTED;             /* a sparse/hole write */
            } else {
                s.tear();
                result = write_error(pdrv, ERR_FS);
            }
        } else {
            ext4_set_mtime(s.fs(), ino, now_seconds());   /* content changed */
        }
    }
    /* len == 0 has already touched the file into existence, which is what the SAF
     * path asks of an empty write. */

    return result;
}

/* ─── ext4jni_create_directory ───────────────────────────────────────── */

jint ext4jni_create_directory(JNIEnv *env, jlong handle, jstring jDirPath) {
    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    uint32_t ino = 0;
    int rc = ext4_mkdir(s.fs(), r, dir_ino, name, 0755, now_seconds(), &ino);

    if (rc == EXT4_DIRW_OK) return ERR_OK;
    if (rc == EXT4_CREATE_ERR_NOINODE || rc == EXT4_DIRW_ERR_NOROOM) return ERR_NO_SPACE;
    s.tear();
    return write_error(pdrv, ERR_FS);
}

/* ─── ext4jni_create_link ────────────────────────────────────────────── */

/*
 * Gives `targetPath` a second name at `linkPath`, and decides which KIND of link
 * that is (#128).
 *
 * The choice is made here rather than asked of the caller, because it follows
 * entirely from what is being linked and there is nothing for a user to weigh up.
 * A file gets a HARD link: one inode, two names, no extra space, and no way for it
 * to end up pointing at nothing - which is word for word what the feature was asked
 * for. A directory cannot have one; ext4 forbids it and so does every other
 * filesystem, because "." and ".." would stop saying which parent a folder has. So
 * a directory gets a symbolic link instead.
 *
 * The stored target is the path as the app gave it, which is absolute from the root
 * of the volume. That survives the LINK being moved, and it means the same thing to
 * a desktop, whose mount point is that same root. A relative target would have been
 * shorter and would break the first time the link was moved to another folder.
 *
 * The target is resolved with following on, so a link made to a link points at the
 * file underneath rather than at the middle one - the same as `ln` on a desktop.
 */
jint ext4jni_create_link(JNIEnv *env, jlong handle, jstring jLinkPath,
                         jstring jTargetPath) {
    std::string linkPath = jstring_to_string(env, jLinkPath);
    std::string targetPath = jstring_to_string(env, jTargetPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, linkPath.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    uint32_t target_ino = 0;
    int target_is_dir = 0;
    prc = ext4_resolve_path(r, targetPath.c_str(), &target_ino, &target_is_dir);
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    int rc;
    if (target_is_dir) {
        uint32_t ino = 0;
        rc = ext4_symlink(s.fs(), r, dir_ino, name, targetPath.c_str(),
                          now_seconds(), &ino);
    } else {
        rc = ext4_hardlink(s.fs(), r, dir_ino, name, target_ino, now_seconds());
    }

    if (rc == EXT4_DIRW_OK) return ERR_OK;
    if (rc == EXT4_DIRW_ERR_EXISTS) return ERR_EXISTS;
    if (rc == EXT4_CREATE_ERR_NOINODE || rc == EXT4_DIRW_ERR_NOROOM)
        return ERR_NO_SPACE;
    /* Nothing was written for the refusals above - they are decided before an
     * inode is taken - so the session is only torn for the rest. */
    s.tear();
    return write_error(pdrv, ERR_FS);
}

/* ─── ext4jni_delete_file ────────────────────────────────────────────── */

jint ext4jni_delete_file(JNIEnv *env, jlong handle, jstring jFilePath) {
    std::string path = jstring_to_string(env, jFilePath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    /* Refuse a directory here: removing one is nativeDeleteDirectory, which is
     * recursive and moves the counters a directory needs.
     *
     * Resolved WITHOUT following, because what is being removed is the name. A
     * symlink is not a directory however tempting the thing it points at looks, so
     * following here would refuse to delete a link to a folder - and it would be
     * asking about the wrong object to begin with. Removing a link has never
     * touched what it names, and must not start. */
    uint32_t ino = 0;
    int is_dir = 0;
    int rrc = ext4_resolve_path_nofollow(r, path.c_str(), &ino, &is_dir);
    if (rrc != EXT4_PATH_OK) return path_error(rrc);
    if (is_dir) return ERR_FS;

    uint32_t dir_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, path.c_str(), &dir_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;
    int rc = ext4_unlink_file(s.fs(), r, dir_ino, name, now_seconds());
    if (rc == EXT4_DIRW_OK) return ERR_OK;
    s.tear();
    return write_error(pdrv, ERR_FS);
}

/* ─── ext4jni_set_file_time ──────────────────────────────────────────── */
/*
 * Puts back the date a file arrived with. The write path cannot do it: every write
 * moves i_mtime to now, so the file's own date has to be stamped once the content is
 * in (#154). Only the caller knows that date - it comes from the source document.
 */
jint ext4jni_set_file_time(JNIEnv *env, jlong handle, jstring jPath, jlong epochMs) {
    std::string path = jstring_to_string(env, jPath);

    /* The inode's base time fields are 32-bit seconds, which is all ext4_set_mtime
     * writes. Refuse what does not fit rather than wrapping it into a wrong date. */
    long long secs = epochMs / 1000LL;
    if (secs <= 0 || secs > 0x7FFFFFFFLL) return ERR_UNSUPPORTED;

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    uint32_t ino = 0;
    int is_dir = 0;
    int rrc = ext4_resolve_path(r, path.c_str(), &ino, &is_dir);
    if (rrc != EXT4_PATH_OK) return path_error(rrc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;
    if (ext4_set_mtime(s.fs(), ino, (uint32_t)secs) != EXTW_OK) {
        s.tear();
        return write_error(pdrv, ERR_FS);
    }
    return ERR_OK;
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

    uint8_t dir[EXT4_MAX_INODE_SIZE];
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

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    /*
     * WITHOUT following, and this is the one place where the difference is
     * measured in somebody's files. `ino` is what gets emptied recursively, so
     * following a symlink here would hand this the directory the link POINTS AT
     * and empty that instead - the user removes a shortcut and the folder it named
     * loses everything in it. A link is removed by nativeDeleteFile, which takes
     * the name and leaves what it names alone; resolved without following, a link
     * is not a directory and lands there.
     */
    uint32_t ino = 0;
    int is_dir = 0;
    int rrc = ext4_resolve_path_nofollow(r, path.c_str(), &ino, &is_dir);
    if (rrc != EXT4_PATH_OK) return path_error(rrc);
    if (!is_dir) return ERR_FS;

    uint32_t parent_ino = 0;
    char name[256];
    int prc = ext4_resolve_parent(r, path.c_str(), &parent_ino, name, sizeof(name));
    if (prc != EXT4_PATH_OK) return path_error(prc);   /* EINVAL for the root */

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    uint32_t when = now_seconds();
    int rc = empty_directory(s.fs(), r, ino, when, 0);
    if (rc == ERR_OK && ext4_rmdir(s.fs(), r, parent_ino, name, when) != EXT4_DIRW_OK)
        rc = ERR_FS;

    if (rc == ERR_OK) return ERR_OK;
    /* A recursive delete that stops half way has removed some of the tree and not
     * the rest, which is a perfectly consistent volume - but it got there because
     * a write failed, so the mark stays. */
    s.tear();
    return write_error(pdrv, rc);
}

/* ─── ext4jni_rename ─────────────────────────────────────────────────── */

jint ext4jni_rename(JNIEnv *env, jlong handle, jstring jOld, jstring jNew) {
    std::string oldp = jstring_to_string(env, jOld);
    std::string newp = jstring_to_string(env, jNew);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = ext4_pdrv(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    if (is_read_only(pdrv)) return ERR_READ_ONLY;

    ext4_fs *r = nullptr;
    if (!open_reader(pdrv, &r)) return ERR_FS;

    /* Both ends are resolved to a parent inode and a final name, the shape
     * ext4_rename works in. The root has no parent, so renaming it is EINVAL from
     * resolve_parent, which path_error maps like any other bad path. The final
     * names may not exist yet - the destination must not, the source must. */
    uint32_t src_parent = 0, dst_parent = 0;
    char src_name[256], dst_name[256];
    int prc = ext4_resolve_parent(r, oldp.c_str(), &src_parent, src_name, sizeof(src_name));
    if (prc != EXT4_PATH_OK) return path_error(prc);
    prc = ext4_resolve_parent(r, newp.c_str(), &dst_parent, dst_name, sizeof(dst_name));
    if (prc != EXT4_PATH_OK) return path_error(prc);

    WriteSession s(pdrv);
    if (!s.ok()) return ERR_FS;

    int rc = ext4_rename(s.fs(), r, src_parent, src_name, dst_parent, dst_name);

    /* A destination that already exists is the one outcome worth telling apart, so
     * the UI can say "name already exists" rather than a generic failure; the FatFs
     * path reports FR_EXIST as ERR_EXISTS too. Everything else - a loop, a bad
     * name, an I/O error - stays a generic filesystem error. */
    if (rc == EXT4_DIRW_OK) return ERR_OK;
    if (rc == EXT4_DIRW_ERR_EXISTS) return ERR_EXISTS;
    s.tear();
    return write_error(pdrv, ERR_FS);
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
