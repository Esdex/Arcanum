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

    if (decode_handle(handle) < 0) return env->NewObjectArray(0, infoCls, nullptr);
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

        while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
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

/* ─── JNI: nativeReadFile ────────────────────────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeReadFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath, jlong offset, jint length)
{
    if (decode_handle(handle) < 0) return env->NewByteArray(0);

    // Reject non-positive or unreasonably large requests.
    // A negative length would wrap to ~4 GB when cast to UINT, causing a buffer overflow.
    if (length <= 0 || length > 16 * 1024 * 1024)
        return env->NewByteArray(0);

    std::string path = jstring_to_string(env, jFilePath);

    // f_read into a malloc'd native buffer instead of a pinned Java array:
    // avoids GetByteArrayElements' pin/copy plus the old trim path's second
    // NewByteArray + re-pin when br < length. One JNI copy (SetByteArrayRegion
    // below), sized to exactly what was read.
    auto *nativeBuf = static_cast<uint8_t*>(malloc((size_t)length));
    if (!nativeBuf) return env->NewByteArray(0);

    FIL fil;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) { free(nativeBuf); return env->NewByteArray(0); }

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) { free(nativeBuf); return env->NewByteArray(0); }

    if (f_open(&fil, fullPath, FA_READ) != FR_OK) { free(nativeBuf); return env->NewByteArray(0); }
    if (f_lseek(&fil, (FSIZE_t)offset) != FR_OK) {
        f_close(&fil); free(nativeBuf); return env->NewByteArray(0);
    }

    UINT br = 0;
    f_read(&fil, nativeBuf, (UINT)length, &br);
    f_close(&fil);

    jbyteArray result = env->NewByteArray((jsize)br);
    if (result && br > 0) {
        env->SetByteArrayRegion(result, 0, (jsize)br, (const jbyte*)nativeBuf);
    }
    free(nativeBuf);
    return result;
}

/* ─── JNI: nativeWriteFile ───────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeWriteFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath,
        jbyteArray jData, jlong offset)
{
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
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

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    if (f_open(&fil, fullPath, omode) != FR_OK) return ERR_FILE;
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
    if (fr == FR_OK && (jint)bw == len) return ERR_OK;
    /* Distinguish hidden-boundary protection from generic I/O error */
    bool tripped = g_drives[pdrv].hiddenBoundaryTripped;
    g_drives[pdrv].hiddenBoundaryTripped = false;
    return tripped ? ERR_HIDDEN_BOUNDARY : ERR_FS;
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
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
    std::string path = jstring_to_string(env, jFilePath);

    FIL fil;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    { auto it = g_ctxMap.find(pdrv); if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY; }

    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    if (f_open(&fil, fullPath, FA_WRITE | FA_OPEN_ALWAYS) != FR_OK) return ERR_FILE;

    jsize len = env->GetArrayLength(jData);
    if (len == 0) { f_close(&fil); return ERR_OK; }   // touch / ensure-exists only

    if (f_lseek(&fil, (FSIZE_t)offset) != FR_OK) { f_close(&fil); return ERR_FS; }

    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    if (!data) { f_close(&fil); return ERR_FS; }
    UINT   bw  = 0;
    FRESULT fr = f_write(&fil, data, (UINT)len, &bw);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    f_close(&fil);
    if (fr == FR_OK && (jint)bw == len) return ERR_OK;
    bool tripped = g_drives[pdrv].hiddenBoundaryTripped;
    g_drives[pdrv].hiddenBoundaryTripped = false;
    return tripped ? ERR_HIDDEN_BOUNDARY : ERR_FS;
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
    /* Cheap unlocked pre-check to fail fast on an obviously bad handle before
     * doing string work; the authoritative check (decode_handle + readOnly)
     * happens under the lock immediately before the f_* call, in one scope,
     * so a concurrent close/free can't race between the check and the op. */
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
    std::string path = jstring_to_string(env, jFilePath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;

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
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
    std::string oldPath = jstring_to_string(env, jOldPath);
    std::string newPath = jstring_to_string(env, jNewPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;

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
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
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
    if (decode_handle(handle) < 0) return ERR_NO_SLOT;
    std::string path = jstring_to_string(env, jDirPath);

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_NO_SLOT;
    auto it = g_ctxMap.find(pdrv);
    if (it != g_ctxMap.end() && it->second->readOnly) return ERR_READ_ONLY;

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
