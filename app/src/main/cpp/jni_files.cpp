/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 *
 * This file incorporates code from VeraCrypt
 * Copyright (C) 2013-2025 AM Crypto
 * Licensed under Apache License 2.0
 */

#include "arcanum_internal.h"

#include <cstdio>
#include <cstring>
#include <ctime>

/* ─── ContainerCtx registry ──────────────────────────────────────────── */
/* Single definition (see the extern declaration + rationale in
 * arcanum_internal.h). jni_volume.cpp's do_open_container publishes into it
 * and nativeCloseContainer erases from it; every read/write here and there
 * happens under g_fatfs_mutex. */
std::unordered_map<int, ContainerCtx*> g_ctxMap;

/* Converts a FatFs packed date/time to Unix epoch milliseconds (UTC).
 * fdate: bits 15-9 = year-1980, bits 8-5 = month, bits 4-0 = day
 * ftime: bits 15-11 = hour, bits 10-5 = minute, bits 4-0 = second/2 */
static jlong fatfs_to_epoch_ms(WORD fdate, WORD ftime) {
    if (fdate == 0) return 0LL;
    struct tm t = {};
    t.tm_year  = ((fdate >> 9) & 0x7F) + 1980 - 1900;
    t.tm_mon   = ((fdate >> 5) & 0x0F) - 1;
    t.tm_mday  = fdate & 0x1F;
    t.tm_hour  = (ftime >> 11) & 0x1F;
    t.tm_min   = (ftime >>  5) & 0x3F;
    t.tm_sec   = (ftime & 0x1F) * 2;
    t.tm_isdst = 0;
    time_t epoch = timegm(&t);
    if (epoch == (time_t)-1) return 0LL;
    return (jlong)epoch * 1000LL;
}

/* ─── JNI: nativeListFiles ───────────────────────────────────────────── */

extern "C" JNIEXPORT jobjectArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeListFiles(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jDirPath)
{
    if (ext4jni_is_container(handle)) return ext4jni_list_files(env, handle, jDirPath);

    jclass infoCls;
    jmethodID ctor;
    if (g_jniCache.fileInfoCls && g_jniCache.fileInfoCtor) {
        /* Fast path: resolved once in JNI_OnLoad instead of on every call. */
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

    /* Single f_readdir pass collecting into a JNI-free vector, entirely under
     * the FatFs lock; the jobjectArray is built afterward with the lock
     * released. This removes the old two-pass count/fill TOCTOU coupling
     * (directory could change between passes) and, more importantly, the
     * bug where non-UTF-8 entries were skipped with `continue` in the fill
     * pass without decrementing the array size computed by the count pass —
     * that left trailing null holes in a Java array the Kotlin side declares
     * non-null, causing an NPE the first time such an entry was iterated. */
    struct Entry {
        std::string name, path;
        uint64_t    size;
        bool        isDir;
        jlong       mtime;
    };
    std::vector<Entry> entries;
    {
        DIR  dir;
        FILINFO fno;
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = decode_handle(handle);
        if (pdrv < 0) return env->NewObjectArray(0, infoCls, nullptr);

        char fullPath[512];
        int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, dirPath.c_str());
        if (n < 0 || n >= (int)sizeof(fullPath)) return env->NewObjectArray(0, infoCls, nullptr);

        if (f_opendir(&dir, fullPath) != FR_OK)
            return env->NewObjectArray(0, infoCls, nullptr);

        FRESULT fr;
        while ((fr = f_readdir(&dir, &fno)) == FR_OK && fno.fname[0]) {
            char ep[512];
            if (dirPath.empty() || dirPath == "/") {
                snprintf(ep, sizeof(ep), "/%s", fno.fname);
            } else {
                snprintf(ep, sizeof(ep), "%s/%s", dirPath.c_str(), fno.fname);
            }
            // is_valid_utf8 remains a pre-filter for truly invalid bytes (residual
            // garbage); genuine 4-byte UTF-8 (emoji etc.) is handled by
            // utf8_to_jstring below rather than being rejected here.
            if (!is_valid_utf8(fno.fname) || !is_valid_utf8(ep)) {
                LOGE("nativeListFiles: skipping entry with non-UTF-8 name (binary bytes in fname)");
                continue;
            }
            entries.push_back(Entry{
                std::string(fno.fname), std::string(ep),
                (uint64_t)fno.fsize, (fno.fattrib & AM_DIR) != 0,
                fatfs_to_epoch_ms(fno.fdate, fno.ftime)
            });
        }
        f_closedir(&dir);
        /* Distinguish mid-listing disk error from true EOF (fno.fname[0]=='\0').
         * Return nullptr so the caller can tell "failed" from "empty directory". */
        if (fr != FR_OK) {
            LOGE("nativeListFiles: f_readdir error %d", (int)fr);
            return nullptr;
        }
    }

    jobjectArray result = env->NewObjectArray((jsize)entries.size(), infoCls, nullptr);
    if (!result) return env->NewObjectArray(0, infoCls, nullptr);

    for (size_t i = 0; i < entries.size(); i++) {
        const Entry &e = entries[i];
        jstring jName = utf8_to_jstring(env, e.name.c_str());
        jstring jPath = utf8_to_jstring(env, e.path.c_str());
        jobject fi    = env->NewObject(infoCls, ctor,
                                       jName, jPath,
                                       (jlong)e.size,
                                       (jboolean)(e.isDir ? 1 : 0),
                                       e.mtime);
        env->SetObjectArrayElement(result, (jsize)i, fi);
        if (jName) env->DeleteLocalRef(jName);
        if (jPath) env->DeleteLocalRef(jPath);
        if (fi)    env->DeleteLocalRef(fi);
    }
    return result;
}

/* ─── Streaming read cache ───────────────────────────────────────────── */
/*
 * nativeReadFile is called once per media chunk (~1 MB) while a video or audio
 * file plays. Re-opening the FIL and seeking from the start of the cluster
 * chain on every call made large-file reads O(n^2): a 2-hour video could take
 * minutes to start, or effectively never start when its MP4 moov index sits at
 * the end of the file, which the player must read before the first frame (#96).
 * Instead we keep the most-recently-read FIL open and reuse it:
 *   - a sequential read (offset == the current file pointer) skips the seek;
 *   - a random seek uses FatFs fast-seek (a cluster-map table) so it is O(1)
 *     instead of a full cluster-chain walk.
 * One cached handle is enough - media plays a single file at a time - and it is
 * safe because every f_* call already runs under g_fatfs_mutex. FF_FS_LOCK is 0,
 * so FatFs will happily free the clusters of an open file on delete/rename;
 * every mutation of the drive, and its unmount, therefore drops the cache
 * (invalidate_read_cache_for_pdrv, called from the mutating ops below and from
 * nativeCloseContainer).
 */
namespace {

struct ReadCache {
    bool        valid = false;
    int         pdrv  = -1;
    std::string path;
    FIL         fil{};
    DWORD*      clmt  = nullptr;   /* fast-seek table; null = fall back to slow seeks */
};

ReadCache g_readCache;   /* guarded by g_fatfs_mutex */

/* Caller holds g_fatfs_mutex. Closes the cached FIL and frees its table. Must
 * run while the drive is still mounted (f_close validates the filesystem). */
void close_read_cache() {
    if (g_readCache.valid) {
        f_close(&g_readCache.fil);
        /* Wipe the plaintext read window - the file object is a long-lived
         * global here, not a stack local that dies at return. */
        secure_memset((volatile uint8_t*)g_readCache.fil.buf, 0, FF_MAX_SS);
        g_readCache.valid = false;
    }
    free(g_readCache.clmt);
    g_readCache.clmt = nullptr;
    g_readCache.pdrv = -1;
    g_readCache.path.clear();
}

/* Builds a fast-seek cluster map for fp. Best effort: on OOM, or a file too
 * fragmented for a sane table, fp keeps working through slow (still correct)
 * seeks with cltbl left null. */
void build_fast_seek(FIL* fp) {
    DWORD cap = 64;   /* DWORDs: 1 header + 2 per contiguous fragment; grows if needed */
    auto* tbl = static_cast<DWORD*>(malloc((size_t)cap * sizeof(DWORD)));
    if (!tbl) { fp->cltbl = nullptr; g_readCache.clmt = nullptr; return; }
    tbl[0] = cap;
    fp->cltbl = tbl;
    FRESULT fr = f_lseek(fp, CREATE_LINKMAP);
    if (fr == FR_NOT_ENOUGH_CORE) {          /* tbl[0] now holds the size required */
        DWORD need = tbl[0];
        auto* grown = static_cast<DWORD*>(realloc(tbl, (size_t)need * sizeof(DWORD)));
        if (!grown) { free(tbl); fp->cltbl = nullptr; g_readCache.clmt = nullptr; return; }
        tbl = grown;
        tbl[0] = need;
        fp->cltbl = tbl;
        fr = f_lseek(fp, CREATE_LINKMAP);
    }
    if (fr != FR_OK) { free(tbl); fp->cltbl = nullptr; g_readCache.clmt = nullptr; return; }
    g_readCache.clmt = tbl;
}

/* Caller holds g_fatfs_mutex. Returns the cached FIL for (pdrv, path), opening
 * it (read-only, with a fast-seek table) when the cache holds a different file.
 * Returns null if the file cannot be opened. */
FIL* acquire_read_file(int pdrv, const std::string& path) {
    if (g_readCache.valid && g_readCache.pdrv == pdrv && g_readCache.path == path)
        return &g_readCache.fil;

    close_read_cache();

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return nullptr;
    if (f_open(&g_readCache.fil, fullPath, FA_READ) != FR_OK) return nullptr;

    g_readCache.valid = true;
    g_readCache.pdrv  = pdrv;
    g_readCache.path  = path;
    build_fast_seek(&g_readCache.fil);       /* leaves fptr at 0 (post-open) */
    return &g_readCache.fil;
}

} // namespace

/* Exposed to jni_volume.cpp's nativeCloseContainer. Caller holds g_fatfs_mutex. */
void invalidate_read_cache_for_pdrv(int pdrv) {
    if (g_readCache.valid && g_readCache.pdrv == pdrv) close_read_cache();
}

/* ─── JNI: nativeReadFile ────────────────────────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeReadFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath, jlong offset, jint length)
{
    if (ext4jni_is_container(handle))
        return ext4jni_read_file(env, handle, jFilePath, offset, length);

    // Reject non-positive or unreasonably large requests.
    // A negative length would wrap to ~4 GB when cast to UINT, causing a buffer overflow.
    if (length <= 0 || length > 16 * 1024 * 1024 || offset < 0)
        return env->NewByteArray(0);

    std::string path = jstring_to_string(env, jFilePath);

    // f_read into a malloc'd native buffer instead of a pinned Java array:
    // avoids GetByteArrayElements' pin/copy plus the old trim path's second
    // NewByteArray + re-pin when br < length. One JNI copy (SetByteArrayRegion
    // below), sized to exactly what was read.
    auto *nativeBuf = static_cast<uint8_t*>(malloc((size_t)length));
    if (!nativeBuf) return env->NewByteArray(0);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) { free(nativeBuf); return env->NewByteArray(0); }

    // Reuse the cached open handle (or open + build a fast-seek table on a miss).
    FIL* fp = acquire_read_file(pdrv, path);
    if (!fp) { free(nativeBuf); return env->NewByteArray(0); }

    // Only seek when the read is non-sequential; back-to-back playback reads land
    // exactly on the current file pointer and skip it entirely.
    if ((FSIZE_t)offset != fp->fptr && f_lseek(fp, (FSIZE_t)offset) != FR_OK) {
        close_read_cache();                  // FIL may be left mid-seek - drop it
        free(nativeBuf);
        return env->NewByteArray(0);
    }

    UINT br = 0;
    FRESULT fr = f_read(fp, nativeBuf, (UINT)length, &br);
    if (fr != FR_OK) {
        close_read_cache();
        free(nativeBuf);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray((jsize)br);
    if (result && br > 0) {
        env->SetByteArrayRegion(result, 0, (jsize)br, (const jbyte*)nativeBuf);
    }
    free(nativeBuf);
    return result;
}

/* ─── Write failure classification ───────────────────────────────────── */
/*
 * FR_DENIED from f_open means "no room for the new entry", which is two very
 * different problems with two different fixes:
 *
 *   - the directory table cannot take another entry. On FAT12/16 the ROOT
 *     directory is a fixed 512 entries (ff.c f_mkfs defaults n_root to 512),
 *     and with long filenames enabled one file eats several of them - a
 *     29-character name costs four. A root full of camera filenames is
 *     exhausted after ~130 files while the volume is still mostly empty.
 *     Subdirectories grow dynamically and are not affected, so the fix is to
 *     put the file in a folder.
 *   - the volume genuinely has no free cluster left, where the fix is to
 *     expand the vault or delete something.
 *
 * Reporting the wrong one sends the user off in the wrong direction (issue
 * #114 was reported as "not enough space" for every possible failure), so ask
 * f_getfree which it is. That can walk the whole FAT on a large volume, which
 * is why it stays on this error path rather than being checked up front.
 */
static jint open_failure_code(FRESULT fr, int pdrv) {
    if (fr != FR_DENIED) return ERR_FILE;

    char drv[8];
    snprintf(drv, sizeof(drv), "%d:", pdrv);
    DWORD freeClusters = 0;
    FATFS *fsPtr = nullptr;
    if (f_getfree(drv, &freeClusters, &fsPtr) == FR_OK && freeClusters == 0)
        return ERR_NO_SPACE;
    return ERR_DIR_FULL;
}

/*
 * FatFs signals a full volume as a SHORT write (FR_OK with bw < len), not an
 * error code. Both write entry points used to let that fall through as ERR_FS,
 * so the one cause the UI actually named was the one it could never report.
 * Hidden-boundary protection is checked first because it also produces a short
 * write, and it is not a space problem.
 */
static jint write_result_code(FRESULT fr, UINT bw, jsize len, int pdrv) {
    if (fr == FR_OK && (jint)bw == len) return ERR_OK;
    bool tripped = g_drives[pdrv].hiddenBoundaryTripped;
    g_drives[pdrv].hiddenBoundaryTripped = false;
    if (tripped) return ERR_HIDDEN_BOUNDARY;
    if (fr == FR_OK && (jint)bw < len) return ERR_NO_SPACE;
    return ERR_FS;
}

/* ─── JNI: nativeWriteFile ───────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeWriteFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath,
        jbyteArray jData, jlong offset)
{
    if (ext4jni_is_container(handle))
        return ext4jni_write_file(env, handle, jFilePath, jData, offset);

    std::string path = jstring_to_string(env, jFilePath);

    FIL fil;
    // For in-place writes (offset > 0) open with read+write so f_lseek works correctly
    // over the full cluster chain. FA_CREATE_ALWAYS is only safe when writing from byte 0.
    BYTE omode = (offset == 0) ? (FA_WRITE | FA_CREATE_ALWAYS)
                               : (FA_READ | FA_WRITE | FA_OPEN_EXISTING);
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    { auto it = g_ctxMap.find(pdrv); if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY; }
    invalidate_read_cache_for_pdrv(pdrv);   // this write may reallocate the cached file's clusters

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FRESULT fro = f_open(&fil, fullPath, omode);
    if (fro != FR_OK) return open_failure_code(fro, pdrv);
    if (offset > 0 && f_lseek(&fil, (FSIZE_t)offset) != FR_OK) {
        f_close(&fil);
        return ERR_FS;
    }

    jsize  len  = env->GetArrayLength(jData);
    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    if (!data) { f_close(&fil); return ERR_FS; }
    UINT   bw   = 0;
    FRESULT fr  = f_write(&fil, data, (UINT)len, &bw);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    f_close(&fil);
    return write_result_code(fr, bw, len, pdrv);
}

/* ─── JNI: nativeWriteAt ─────────────────────────────────────────────── */

/* Non-truncating positional write for the SAF DocumentsProvider. Unlike nativeWriteFile,
 * a write at offset 0 does NOT discard the rest of the file (FA_OPEN_ALWAYS, not
 * FA_CREATE_ALWAYS), so an external app that seeks backwards to patch a header can't
 * accidentally truncate the file. The file is created if it does not exist; an empty data
 * array just touches it into existence. Truncation semantics ("w" open) are handled on the
 * Kotlin side by deleting the file before the write session begins. */
extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeWriteAt(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath,
        jbyteArray jData, jlong offset)
{
    /* ext4's non-truncating positional write. Unlike ext4jni_write_file (which
     * recreates the file at offset 0, for chunked import), this opens the file and
     * writes in place at any offset without discarding the rest - the OPEN_ALWAYS
     * semantics this SAF entry point promises. */
    if (ext4jni_is_container(handle))
        return ext4jni_write_at(env, handle, jFilePath, jData, offset);

    std::string path = jstring_to_string(env, jFilePath);

    FIL fil;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    { auto it = g_ctxMap.find(pdrv); if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY; }
    invalidate_read_cache_for_pdrv(pdrv);   // this write may reallocate the cached file's clusters

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FRESULT fro = f_open(&fil, fullPath, FA_WRITE | FA_OPEN_ALWAYS);
    if (fro != FR_OK) return open_failure_code(fro, pdrv);

    jsize len = env->GetArrayLength(jData);
    if (len == 0) { f_close(&fil); return ERR_OK; }   // touch / ensure-exists only

    if (f_lseek(&fil, (FSIZE_t)offset) != FR_OK) { f_close(&fil); return ERR_FS; }

    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    if (!data) { f_close(&fil); return ERR_FS; }
    UINT   bw  = 0;
    FRESULT fr = f_write(&fil, data, (UINT)len, &bw);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    f_close(&fil);
    return write_result_code(fr, bw, len, pdrv);
}

/* ─── JNI: nativeGetAlgorithmId ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetAlgorithmId(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    return (jint)g_drives[pdrv].algId;
}

/* ─── JNI: nativeGetHashId ───────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetHashId(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    return (jint)g_drives[pdrv].hashId;
}

/* ─── JNI: nativeGetFilesystem ───────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetFilesystem(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    auto it  = g_ctxMap.find(pdrv);
    if (it == g_ctxMap.end()) return -1;
    if (it->second->isExt4) return ext4jni_get_filesystem();
    return (jint)it->second->fatFs.fs_type;
}

/* ─── JNI: nativeGetDataSize ─────────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetDataSize(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    return (jlong)(g_drives[pdrv].sectorCount * (uint64_t)VC_SECTOR_SIZE);
}

/* ─── JNI: nativeGetKeySize ──────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetKeySize(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    int algId = g_drives[pdrv].algId;
    if (algId < 0 || algId >= NUM_ALGORITHMS) return -1;
    /* All supported VeraCrypt ciphers (AES, Serpent, Twofish, Camellia, Kuznyechik)
       use 256-bit keys. Return per-cipher key size as VeraCrypt's UI does. */
    return 256;
}

/* ─── JNI: nativeGetIterationCount ──────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetIterationCount(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    return (jint)g_drives[pdrv].pkcs5Iterations;
}

/* ─── JNI: nativeDeleteFile ──────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeDeleteFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath)
{
    if (ext4jni_is_container(handle)) return ext4jni_delete_file(env, handle, jFilePath);

    std::string path = jstring_to_string(env, jFilePath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;
    invalidate_read_cache_for_pdrv(pdrv);   // deleting frees clusters a cached read may still hold

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FRESULT fr = f_unlink(fullPath);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeRenameFile ─────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRenameFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jOldPath, jstring jNewPath)
{
    if (ext4jni_is_container(handle))
        return ext4jni_rename(env, handle, jOldPath, jNewPath);

    std::string oldPath = jstring_to_string(env, jOldPath);
    std::string newPath = jstring_to_string(env, jNewPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;
    invalidate_read_cache_for_pdrv(pdrv);   // rename can relocate the cached file's directory entry

    char fullOldPath[512];
    char fullNewPath[512];
    int n1 = snprintf(fullOldPath, sizeof(fullOldPath), "%d:%s", pdrv, oldPath.c_str());
    int n2 = snprintf(fullNewPath, sizeof(fullNewPath), "%d:%s", pdrv, newPath.c_str());
    if (n1 < 0 || n1 >= (int)sizeof(fullOldPath) ||
        n2 < 0 || n2 >= (int)sizeof(fullNewPath)) return ERR_FILE;

    FRESULT fr = f_rename(fullOldPath, fullNewPath);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeCreateDirectory ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateDirectory(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jDirPath)
{
    if (ext4jni_is_container(handle))
        return ext4jni_create_directory(env, handle, jDirPath);

    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FRESULT fr = f_mkdir(fullPath);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeDeleteDirectory ────────────────────────────────────── */
/* Recursive delete — caller must NOT hold g_fatfs_mutex. */

static FRESULT unlink_recursive_locked(const char *fullPath, int depth = 0) {
    if (depth > 16) return FR_DENIED;

    FRESULT fr = f_unlink(fullPath);
    if (fr != FR_DENIED) return fr; /* FR_DENIED = directory not empty */

    DIR dir;
    FILINFO fno;
    fr = f_opendir(&dir, fullPath);
    if (fr != FR_OK) return fr;

    char entryPath[512];
    while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
        int n = snprintf(entryPath, sizeof(entryPath), "%s/%s", fullPath, fno.fname);
        if (n > 0 && n < (int)sizeof(entryPath))
            unlink_recursive_locked(entryPath, depth + 1);
    }
    f_closedir(&dir);
    return f_unlink(fullPath);
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeDeleteDirectory(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jDirPath)
{
    if (ext4jni_is_container(handle))
        return ext4jni_delete_directory(env, handle, jDirPath);

    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;
    invalidate_read_cache_for_pdrv(pdrv);   // recursive delete frees clusters a cached read may hold

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FRESULT fr = unlink_recursive_locked(fullPath);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeGetVolumeType ──────────────────────────────────────── */
/* Returns 1 if the mounted volume is hidden, 0 if standard, -1 if invalid. */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetVolumeType(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return -1;
    return g_drives[pdrv].isHidden ? 1 : 0;
}

/* ─── JNI: nativeHasHiddenVolume ────────────────────────────────────── */
/* Returns true if this outer volume was mounted with explicit hidden-volume
   protection (i.e., a protection password was supplied → hiddenBoundary > 0).  */

extern "C" JNIEXPORT jboolean JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeHasHiddenVolume(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return JNI_FALSE;
    return (g_drives[pdrv].hiddenBoundary > 0) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Filesystem capacity and free space in bytes, as {total, free}.
 *
 * Deliberately not derived from the volume size in the VeraCrypt header, because
 * the two are not the same number. Expanding a container grows the volume but
 * leaves the filesystem describing its original size - do_expand_volume never
 * touches it, and FatFs cannot grow a formatted volume - so the header size
 * counts space that no write can reach. Reporting it as capacity is what made
 * the Storage screen offer free space every import then refused to use.
 *
 * Both figures are measured against alloc_fatent rather than n_fatent so they
 * agree with f_getfree, which only counts allocatable clusters: on an outer
 * volume mounted with hidden-volume protection the ceiling is lower, and a total
 * taken from n_fatent would include clusters the free count excludes.
 *
 * f_getfree can walk the whole FAT, so this is a screen-level call, not one to
 * put on a per-file path.
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetFsUsage(
        JNIEnv *env, jobject /*thiz*/, jlong handle)
{
    if (ext4jni_is_container(handle)) return ext4jni_fs_usage(env, handle);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return nullptr;

    char drv[8];
    snprintf(drv, sizeof(drv), "%d:", pdrv);
    DWORD freeClusters = 0;
    FATFS *fs = nullptr;
    if (f_getfree(drv, &freeClusters, &fs) != FR_OK || fs == nullptr) return nullptr;

    /* FF_MIN_SS == FF_MAX_SS == 512, so the sector size is fixed and there is no
     * per-volume ssize field to read. alloc_fatent counts the two reserved entries. */
    const uint64_t bytesPerCluster = (uint64_t)fs->csize * FF_MAX_SS;
    const uint64_t dataClusters    = (fs->alloc_fatent > 2) ? (uint64_t)(fs->alloc_fatent - 2) : 0ULL;

    jlong out[2];
    out[0] = (jlong)(dataClusters * bytesPerCluster);
    out[1] = (jlong)((uint64_t)freeClusters * bytesPerCluster);

    jlongArray arr = env->NewLongArray(2);
    if (arr == nullptr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 2, out);
    return arr;
}
