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

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include "Crypto/Aes.h"
}
#include "Common/Xts.h"    /* EncryptBufferXTS, UINT64_STRUCT */

/* ─── JNI_OnLoad: cached classes / method IDs ───────────────────────── */
/*
 * nativeListFiles (jni_files.cpp) previously did
 * FindClass("zip/arcanum/crypto/NativeFileInfo") + GetMethodID on every
 * call, and utf8_to_jstring's non-BMP fallback path looked up
 * java/lang/String the same way (via a function-local static, so only the
 * FIRST call paid for it, but that lookup pattern is redundant with this
 * one). Both classes are resolved once here, at load time, and held as
 * GlobalRefs for the life of the process. Call sites fall back to a per-call
 * lookup if the cache failed to populate (e.g. a JNI_OnLoad edge case) so
 * behavior is identical either way, just slower on the fallback path.
 */
JniCache g_jniCache;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    g_jniCache.vm = vm;

    jclass localFileInfo = env->FindClass("zip/arcanum/crypto/NativeFileInfo");
    if (localFileInfo) {
        g_jniCache.fileInfoCls = (jclass)env->NewGlobalRef(localFileInfo);
        g_jniCache.fileInfoCtor = env->GetMethodID(g_jniCache.fileInfoCls, "<init>",
                                       "(Ljava/lang/String;Ljava/lang/String;JZJILjava/lang/String;ZZIJ)V");
        env->DeleteLocalRef(localFileInfo);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass localString = env->FindClass("java/lang/String");
    if (localString) {
        g_jniCache.stringCls = (jclass)env->NewGlobalRef(localString);
        g_jniCache.stringCtor = env->GetMethodID(g_jniCache.stringCls, "<init>",
                                     "([BLjava/lang/String;)V");
        env->DeleteLocalRef(localString);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jstring localUtf8 = env->NewStringUTF("UTF-8");
    if (localUtf8) {
        g_jniCache.utf8Name = (jstring)env->NewGlobalRef(localUtf8);
        env->DeleteLocalRef(localUtf8);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    return JNI_VERSION_1_6;
}

/* ─── JNI helpers ────────────────────────────────────────────────────── */
/* jstring_to_string / utf8_to_jstring / is_valid_utf8 are declared
 * (non-static) in arcanum_internal.h — jni_files.cpp's nativeListFiles and
 * the file-op natives reuse them. */

/* Returns true iff every byte in s forms a valid UTF-8 sequence.
   NewStringUTF aborts the process on invalid Modified UTF-8, so we must
   validate before calling it. */
bool is_valid_utf8(const char *s) {
    const auto *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        if (*p < 0x80) {
            p++;
        } else if ((*p & 0xE0) == 0xC0) {
            if (!p[1] || (p[1] & 0xC0) != 0x80) return false;
            p += 2;
        } else if ((*p & 0xF0) == 0xE0) {
            if (!p[1] || (p[1] & 0xC0) != 0x80 ||
                !p[2] || (p[2] & 0xC0) != 0x80) return false;
            p += 3;
        } else if ((*p & 0xF8) == 0xF0) {
            if (!p[1] || (p[1] & 0xC0) != 0x80 ||
                !p[2] || (p[2] & 0xC0) != 0x80 ||
                !p[3] || (p[3] & 0xC0) != 0x80) return false;
            p += 4;
        } else {
            return false;
        }
    }
    return true;
}

/* jstring → UTF-8 std::string, manually converting UTF-16 (GetStringChars)
 * instead of using GetStringUTFChars, which returns Modified UTF-8 (CESU-8
 * surrogate pairs for non-BMP characters). That distinction matters:
 *  - File/directory paths with non-BMP characters (e.g. emoji) wouldn't
 *    round-trip through FatFs, which expects standard UTF-8 (FF_LFN_UNICODE=2).
 *  - Historically this was also used for passwords, where the same distinction
 *    mattered for key derivation (must match desktop VeraCrypt's real-UTF-8
 *    hashing) — passwords now arrive as jbyteArray (already-encoded UTF-8 from
 *    Kotlin's String.toByteArray(Charsets.UTF_8)) and go through
 *    get_password_bytes() instead, so this function is path-only.
 * Unpaired surrogates are replaced with U+FFFD. No wiping here — paths are
 * not secret. */
std::string jstring_to_string(JNIEnv *env, jstring js) {
    if (!js) return {};
    const jchar *chars = env->GetStringChars(js, nullptr);
    if (!chars) return {};
    jsize len = env->GetStringLength(js);

    std::string out;
    out.reserve((size_t)len);
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                uint32_t lo = chars[i + 1];
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            } else {
                cp = 0xFFFD; /* unpaired high surrogate */
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD; /* unpaired low surrogate */
        }

        if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

/* Copies a password jbyteArray into a fixed-size SecureBuffer, clamping to
 * VC_MAX_PWD_LEN exactly the way the old jstring-based code clamped
 * password.size() (see the do_* cores below). A null array or a zero-length
 * array is treated like the previous empty-string case: returns 0 and
 * leaves the buffer zeroed. Uses GetByteArrayRegion, which copies into
 * caller-owned memory, rather than pinning/mutating the caller's array —
 * Kotlin's usePasswordBytes() zeroes its own copy once this call returns.
 * Every caller owns a SecureBuffer<VC_MAX_PWD_LEN>, so this is the single
 * site where the jbyteArray→native-secret conversion happens. */
/* True when a JNI keyfile array carries at least one entry. Only used to decide
 * whether the legacy-pool retry could change anything (see
 * keyfile_pool_has_legacy_variant) — apply_keyfile_buffers no-ops on empty. */
static bool jni_has_keyfiles(JNIEnv *env, jobjectArray arr) {
    return arr != nullptr && env->GetArrayLength(arr) > 0;
}

/*
 * Runs `attempt` against the effective password already built in effPwd; if it
 * fails, rebuilds effPwd with the pre-#112 legacy 64-byte keyfile pool and runs
 * it once more. Volumes Arcanum wrote before that fix derived their header from
 * the 64-byte pool even with a password over 64 bytes, and would otherwise stop
 * opening the moment the pool selection was corrected.
 *
 * The retry is skipped whenever the two pools cannot differ, so the common case
 * (no keyfiles, or a password of 64 bytes or less) never pays for a second
 * PBKDF2 sweep on a wrong password.
 *
 * On return effPwd holds whichever variant was tried last, and *usedLegacyPool
 * (when not null) says which one succeeded. Any caller that goes on to WRITE a
 * header MUST NOT write with a legacy-pool credential - call
 * rebuild_standard_pool_password() first, so the header it writes is correct
 * and the volume heals instead of carrying the bug forward.
 */
template <typename AuthFn>
static int auth_with_legacy_pool_retry(
        JNIEnv *env, jobjectArray keyfileData,
        const uint8_t *pwd, int pwdLen,
        uint8_t *effPwd, int *effPwdLen,
        const char *logTag, bool *usedLegacyPool, AuthFn &&attempt)
{
    if (usedLegacyPool) *usedLegacyPool = false;

    int rc = attempt((const uint8_t*)effPwd, *effPwdLen);
    if (rc == ERR_OK) return rc;
    if (!keyfile_pool_has_legacy_variant(pwdLen, jni_has_keyfiles(env, keyfileData))) return rc;

    LOGI("[%s] auth failed with the standard keyfile pool - retrying with the "
         "pre-#112 legacy 64-byte pool", logTag);
    *effPwdLen = pwdLen;
    memcpy(effPwd, pwd, (size_t)pwdLen);
    if (!apply_keyfile_buffers(env, keyfileData, effPwd, effPwdLen, /*forceLegacyPool=*/true))
        return ERR_RAND;

    rc = attempt((const uint8_t*)effPwd, *effPwdLen);
    if (rc == ERR_OK && usedLegacyPool) *usedLegacyPool = true;
    return rc;
}

/* Rebuilds effPwd with the correct pool. Used after a successful legacy retry
 * by every path that then writes a header, so the write is always in the
 * corrected format. */
static bool rebuild_standard_pool_password(
        JNIEnv *env, jobjectArray keyfileData,
        const uint8_t *pwd, int pwdLen, uint8_t *effPwd, int *effPwdLen)
{
    *effPwdLen = pwdLen;
    memcpy(effPwd, pwd, (size_t)pwdLen);
    return apply_keyfile_buffers(env, keyfileData, effPwd, effPwdLen,
                                 /*forceLegacyPool=*/false);
}

static int get_password_bytes(JNIEnv *env, jbyteArray jPwd, SecureBuffer<VC_MAX_PWD_LEN> &out) {
    if (!jPwd) return 0;
    jsize len = env->GetArrayLength(jPwd);
    if (len <= 0) return 0;
    if (len > VC_MAX_PWD_LEN) len = VC_MAX_PWD_LEN;
    env->GetByteArrayRegion(jPwd, 0, len, (jbyte*)out.data());
    return (int)len;
}

/* Constructs a jstring from a genuine UTF-8 C string, correctly handling
 * non-BMP characters (4-byte sequences, e.g. emoji) that NewStringUTF cannot:
 * NewStringUTF expects Modified UTF-8 and aborts the process under CheckJNI
 * when given a real 4-byte UTF-8 sequence (leading byte >= 0xF0). FatFs
 * (FF_LFN_UNICODE=2) emits standard UTF-8, so names containing such
 * characters need this path; everything else takes the fast NewStringUTF path. */
struct Utf8JStringCache { jclass stringCls; jmethodID ctor; jstring utf8Name; };

static Utf8JStringCache make_utf8_jstring_cache(JNIEnv *env) {
    Utf8JStringCache c{};
    jclass local = env->FindClass("java/lang/String");
    if (!local) return c;
    c.stringCls = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    c.ctor = env->GetMethodID(c.stringCls, "<init>", "([BLjava/lang/String;)V");
    jstring localName = env->NewStringUTF("UTF-8");
    c.utf8Name = localName ? (jstring)env->NewGlobalRef(localName) : nullptr;
    if (localName) env->DeleteLocalRef(localName);
    return c;
}

jstring utf8_to_jstring(JNIEnv *env, const char *s) {
    bool hasNonBmp = false;
    for (const auto *q = reinterpret_cast<const unsigned char*>(s); *q; q++) {
        if (*q >= 0xF0) { hasNonBmp = true; break; }
    }
    if (!hasNonBmp) return env->NewStringUTF(s);

    /* Fast path: JNI_OnLoad already resolved these. Fall back to the
     * per-call (but still one-time-per-process, via a function-local static)
     * lookup if the cache didn't populate for some reason. */
    jclass    stringCls;
    jmethodID ctor;
    jstring   utf8Name;
    if (g_jniCache.stringCls && g_jniCache.stringCtor && g_jniCache.utf8Name) {
        stringCls = g_jniCache.stringCls;
        ctor      = g_jniCache.stringCtor;
        utf8Name  = g_jniCache.utf8Name;
    } else {
        /* Thread-safe one-time init (C++11 function-local static "magic statics"). */
        static const Utf8JStringCache cache = make_utf8_jstring_cache(env);
        if (!cache.stringCls || !cache.ctor || !cache.utf8Name) return env->NewStringUTF("?");
        stringCls = cache.stringCls;
        ctor      = cache.ctor;
        utf8Name  = cache.utf8Name;
    }

    size_t len = strlen(s);
    jbyteArray bytes = env->NewByteArray((jsize)len);
    if (!bytes) return env->NewStringUTF("?");
    env->SetByteArrayRegion(bytes, 0, (jsize)len, (const jbyte*)s);
    auto result = (jstring)env->NewObject(stringCls, ctor, bytes, utf8Name);
    env->DeleteLocalRef(bytes);
    return result;
}

static jmethodID resolve_progress_mid(JNIEnv *env, jobject listener) {
    if (!listener) return nullptr;
    jclass cls = env->GetObjectClass(listener);
    if (!cls) return nullptr;
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(FFJ)V");
    env->DeleteLocalRef(cls);
    return mid;
}

static void report_progress(JNIEnv *env, jobject listener, jmethodID mid,
                             float frac, float speedMbps, jlong written) {
    if (!listener || !mid) return;
    env->CallVoidMethod(listener, mid, frac, speedMbps, written);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* ─── Progress throttling ────────────────────────────────────────────── */
/*
 * The create fill loops and preallocate_fd's zero-fill fallback previously
 * called report_progress() every 64/128 KB chunk —
 * hundreds of thousands of JNI CallVoidMethod round-trips for a multi-GB
 * container. monotonic_ms() (crypto_core.cpp; CLOCK_MONOTONIC, immune to
 * wall-clock jumps) plus ProgressThrottle cap that to roughly 10 reports/sec
 * while still letting the last chunk of each loop through unthrottled so the
 * phase's progress fraction visibly reaches its target instead of stalling a
 * few percent short. This does not touch the standalone frac=1.0 completion
 * reports fired after these loops return — those remain unconditional.
 */
struct ProgressThrottle {
    uint64_t lastReportMs = 0;
    bool     reportedOnce = false;
    static constexpr uint64_t MIN_INTERVAL_MS = 100;

    /* force=true (e.g. the loop's final chunk) always reports, regardless of
     * how recently the last report fired. */
    bool should_report(bool force = false) {
        uint64_t now = monotonic_ms();
        if (force || !reportedOnce || (now - lastReportMs) >= MIN_INTERVAL_MS) {
            lastReportMs = now;
            reportedOnce = true;
            return true;
        }
        return false;
    }
};

/* ─── Pre-allocate helper (FAT32 / FUSE-safe) ───────────────────────── */
/*
 * ftruncate() on FAT32/exFAT SD cards via Android's FUSE/MediaProvider layer
 * blocks the FUSE daemon for the full duration of FAT chain allocation — which
 * serializes all storage I/O for that volume and can freeze the device for
 * minutes on a multi-GB container.
 *
 * Fix: try fallocate(2) first (completes in milliseconds on exFAT, fails
 * quickly with EOPNOTSUPP on FAT32), then fall back to a chunked zero-write
 * that works on any filesystem, reports progress, and keeps the UI responsive.
 *
 * allocWeight — fraction of the overall 0→1 progress budget consumed by
 * allocation (before headers and any random fill):
 *   0.9f  quickFormat  (allocation IS nearly all the work; mkfs gets ~10%)
 *   0.5f  secureFormat (allocation + random-fill share the budget evenly)
 *
 * dataSize — caller's payload size; used to produce a monotonically increasing
 * bytesWritten that spans both allocation and fill phases for stable ETA.
 *
 * On failure: truncates fd back to 0 so that the subsequent
 * DocumentsContract.deleteDocument() call returns instantly instead of
 * walking a multi-GB FAT chain and freezing the device a second time.
 */
static bool preallocate_fd(
        JNIEnv *env, jobject progressListener, jmethodID progressMid,
        int fd, uint64_t fileSize, uint64_t dataSize, float allocWeight)
{
    if (fallocate(fd, 0, 0, (off_t)fileSize) == 0) {
        lseek(fd, 0, SEEK_SET);
        jlong pseudo = (jlong)((double)allocWeight * (double)dataSize);
        report_progress(env, progressListener, progressMid, allocWeight, 0.f, pseudo);
        return true;
    }

    if (errno != EOPNOTSUPP && errno != ENOSYS && errno != EINVAL && errno != EPERM) {
        LOGE("[fd/create] fallocate failed: errno=%d", errno);
        ftruncate(fd, 0);
        return false;
    }
    LOGI("[fd/create] fallocate unsupported (errno=%d), using zero-fill fallback", errno);

    const size_t CHUNK = 65536;
    auto *zeros = static_cast<uint8_t*>(malloc(CHUNK));
    if (!zeros) { ftruncate(fd, 0); return false; }
    memset(zeros, 0, CHUNK);

    lseek(fd, 0, SEEK_SET);
    uint64_t remaining = fileSize;
    uint64_t t0 = monotonic_ms();
    ProgressThrottle throttle;

    while (remaining > 0) {
        size_t sz = (remaining > CHUNK) ? CHUNK : (size_t)remaining;
        ssize_t w = write(fd, zeros, sz);
        if (w <= 0) {
            if (errno == EINTR) continue;
            LOGE("[fd/create] zero-fill failed at offset %llu: errno=%d",
                 (unsigned long long)(fileSize - remaining), errno);
            free(zeros);
            ftruncate(fd, 0);
            return false;
        }
        remaining -= (uint64_t)w;
        uint64_t allocated = fileSize - remaining;
        float frac = (float)allocated / (float)fileSize * allocWeight;
        uint64_t elMs = monotonic_ms() - t0;
        float speed = elMs > 0 ? (float)(allocated / 1048576ULL) / ((float)elMs / 1000.f) : 0.f;
        jlong pseudo = (jlong)((double)frac / (double)allocWeight * (double)dataSize);
        if (throttle.should_report(remaining == 0))
            report_progress(env, progressListener, progressMid, frac, speed, pseudo);
    }

    free(zeros);
    lseek(fd, 0, SEEK_SET);
    return true;
}

/* ─── Create-container core ─────────────────────────────────────────── */
/*
 * Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd
 * (always closed before returning).
 *
 * unlinkPathOnFail — non-null for the path wrapper: the file was freshly
 * created by open(O_CREAT|O_TRUNC), so failure cleanup deletes it and
 * allocation is a plain (sparse) ftruncate. Null for the SAF-fd wrapper:
 * failure cleanup truncates to 0 (so DocumentsContract.deleteDocument()
 * returns instantly) and allocation goes through preallocate_fd()
 * (FUSE-safe, with progress).
 *
 * Progress budget: path mode fills 0→1; SAF mode reserves allocWeight
 * (0.9 quick / 0.5 secure) for allocation and random-fills up to 0.9,
 * exactly as the two pre-dedup variants did.
 */
/* ─── FAT cluster size (issue #115) ──────────────────────────────────── */
/*
 * VeraCrypt's cluster-size ladder, transcribed from GetFatParams()
 * (src/Common/Fat.c). Returned in bytes, which is what MKFS_PARM.au_size takes.
 *
 * This has to be passed explicitly. With au_size left at 0 FatFs runs its own
 * automatic selection, and that behaves quite differently: ff.c:6210 only
 * chooses FAT32 up front when FM_FAT is absent, so passing FM_FAT|FM_FAT32
 * always starts at FAT16, and ff.c's table then picks deliberately large
 * clusters to keep the volume inside FAT16. The result was that every vault
 * from 256 MB to 2 GB came out FAT16 with 8-32 KB clusters, where VeraCrypt
 * would have written FAT32 with 2-4 KB. FAT16 caps the root directory at a
 * fixed 512 entries, so the top level of such a vault fills up after ~130 long
 * filenames while the volume is nearly empty.
 *
 * Passing the size also fixes the FAT type for free: the retry at ff.c:6437
 * that grows the cluster to stay in FAT16 is guarded by sz_au == 0, so with an
 * explicit size FatFs falls through to `fsty = FS_FAT32` as soon as the cluster
 * count passes the FAT16 limit - matching where VeraCrypt switches.
 */
static DWORD vc_fat_cluster_size(uint64_t volumeSize) {
    const uint64_t KB = 1024ULL, MB = 1024ULL * KB, GB = 1024ULL * MB, TB = 1024ULL * GB;
    DWORD clusterSize;
    if      (volumeSize >= 2   * TB) clusterSize = (DWORD)(256 * KB);
    else if (volumeSize >= 512 * GB) clusterSize = (DWORD)(128 * KB);
    else if (volumeSize >= 128 * GB) clusterSize = (DWORD)( 64 * KB);
    else if (volumeSize >=  64 * GB) clusterSize = (DWORD)( 32 * KB);
    else if (volumeSize >=  32 * GB) clusterSize = (DWORD)( 16 * KB);
    else if (volumeSize >=  16 * GB) clusterSize = (DWORD)(  8 * KB);
    else if (volumeSize >= 512 * MB) clusterSize = (DWORD)(  4 * KB);
    else if (volumeSize >= 256 * MB) clusterSize = (DWORD)(  2 * KB);
    else if (volumeSize >=   1 * MB) clusterSize = (DWORD)(  1 * KB);
    else                             clusterSize = 512;

    /* VeraCrypt caps at 256 KB (Volumes.h TC_MAX_FAT_CLUSTER_SIZE) because
       Windows XP/Vista could crash above that. FatFs additionally rejects
       anything over 128 sectors per cluster, which is 64 KB at our 512-byte
       sector size, and would silently fall back to auto-selection if we handed
       it something larger - defeating the whole point. Clamp to what FatFs
       will actually honour. */
    const DWORD maxAu = 128u * VC_SECTOR_SIZE;
    if (clusterSize > maxAu) clusterSize = maxAu;

    /* VeraCrypt drops to a single sector per cluster on very small volumes:
       `if (volumeSize <= TC_MAX_FAT_CLUSTER_SIZE * 4) ft->cluster_size = 1;`
       with TC_MAX_FAT_CLUSTER_SIZE = 256 KB (Volumes.h), so the threshold is
       1 MB. Below Arcanum's smallest preset, but kept for parity. */
    if (volumeSize <= 1024ULL * KB) clusterSize = VC_SECTOR_SIZE;

    /*
     * Keep the resulting cluster count away from the FAT16 ceiling.
     *
     * VeraCrypt computes its geometry in one pass, but FatFs iterates: it
     * starts at FAT16, and on overflow switches to FAT32 and recomputes with
     * the reserved, FAT and directory sectors subtracted. If that recomputation
     * drops the count back to MAX_FAT16 or below, ff.c:6431 aborts outright -
     * the retry that would have adjusted the cluster is guarded by sz_au == 0,
     * and we are deliberately passing a size. A volume landing a few hundred
     * clusters above the line would therefore fail to format at all.
     *
     * That band is reachable: a custom 64 MB vault gives exactly 65536 clusters
     * at VeraCrypt's 1 KB. Halving the cluster doubles the count and clears the
     * line; at the sector floor go the other way and settle firmly inside
     * FAT16 instead.
     */
    const DWORD FAT16_MAX_CLUSTERS = 0xFFF5;
    const DWORD margin = FAT16_MAX_CLUSTERS / 50;   /* 2% */
    for (int guard = 0; guard < 8; guard++) {
        uint64_t clusters = volumeSize / clusterSize;
        if (clusters <= FAT16_MAX_CLUSTERS ||
            clusters >= (uint64_t)FAT16_MAX_CLUSTERS + margin) break;
        if (clusterSize > VC_SECTOR_SIZE) clusterSize /= 2;   /* toward FAT32 */
        else if (clusterSize * 2 <= maxAu) clusterSize *= 2;  /* toward FAT16 */
        else break;
    }

    return clusterSize;
}

/*
 * `beIn` null means the volume is a file, as before. Non-null means it occupies a whole
 * device (#95): `fdIn` is -1, `deviceSizeIn` is the drive's capacity, and there is no
 * file to size - the space already exists, so the allocation phase is skipped entirely.
 */
static jint do_create_container(
        JNIEnv *env, int fdIn, const BlockBackend *beIn, uint64_t deviceSizeIn,
        const char *unlinkPathOnFail, const char *logTag,
        jlong sizeBytes, const uint8_t *pwd, int pwdLen,
        jobjectArray jKeyfileData,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat, jobject progressListener, jint pim,
        const uint8_t *entropy, size_t entropyLen)
{
    /* fd is always closed by this function (never stored beyond it), on
     * every path — success or failure — so a single UniqueFd covers the
     * whole function; no release() site is needed here (contrast
     * do_open_container, where a successful mount keeps the fd alive). */
    UniqueFd fd(fdIn);
    const BlockBackend vol = beIn ? *beIn : fd_be(fd.get());

    /* fail_cleanup() only performs the semantic action (unlink the freshly
     * created file / truncate the SAF file back to 0) — it does NOT close
     * fd anymore, since UniqueFd is the single owner of that close().
     *
     * A device can be neither unlinked nor truncated, so the best available action is
     * to zero the header. A half-written volume would otherwise be indistinguishable
     * from a good one until someone failed to mount it. */
    auto fail_cleanup = [&]() {
        if (beIn) {
            uint8_t zero[VC_HEADER_SIZE] = {};
            vol.write(vol.self, zero, sizeof(zero), 0);
            vol.sync(vol.self);
        } else if (unlinkPathOnFail) {
            unlink(unlinkPathOnFail);
        } else {
            ftruncate(fd.get(), 0);
        }
    };

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen)) return ERR_RAND;
    const int pbkdf2PwdLen = effPwdLen;

    int algId = (int)algorithm;
    int n     = ALGORITHMS[algId].n;

    uint64_t dataSize = (uint64_t)sizeBytes;
    uint64_t fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE;

    /* Resolve progress callback method ID once — reused across all chunks */
    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    /* Allocation phase — see the mode note above. */
    float allocWeight = 0.f, fillEnd = 1.f;
    if (beIn) {
        /* Nothing to allocate: the device's space exists already. Only the arithmetic
         * has to hold - the volume plus its two header areas must fit on the drive. */
        if (fileSize > deviceSizeIn) {
            LOGE("[%s] volume of %llu bytes does not fit on a %llu byte device",
                 logTag, (unsigned long long)fileSize, (unsigned long long)deviceSizeIn);
            return ERR_NO_SPACE;
        }
    } else if (unlinkPathOnFail) {
        if (ftruncate(fd.get(), (off_t)fileSize) != 0) {
            LOGE("[%s] ftruncate failed - disk full?", logTag);
            fail_cleanup(); return ERR_NO_SPACE;
        }
    } else {
        allocWeight = quickFormat ? 0.9f : 0.5f;
        fillEnd     = 0.9f;
        if (!preallocate_fd(env, progressListener, progressMid, fd.get(), fileSize, dataSize, allocWeight)) {
            return ERR_NO_SPACE;   /* preallocate_fd already truncated to 0 */
        }
        /* Cut a stale tail if the SAF file pre-existed larger than the new container —
         * later size-derived offsets (hidden-volume creation, restore) use lseek(SEEK_END)
         * and would otherwise compute from the wrong (larger) file size. fallocate() only
         * grows/reserves; it doesn't shrink, so this is needed even after a successful
         * preallocate_fd(). */
        ftruncate(fd.get(), (off_t)fileSize);
    }

    SecureBuffer<192> masterKey;
    if (!read_urandom(masterKey.data(), (size_t)(n * 64))) {
        LOGE("[%s] /dev/urandom failed for master key - aborting", logTag);
        fail_cleanup(); return ERR_RAND;
    }
    /* Random Pool Enrichment: fold user-collected entropy into the urandom
     * master key. XOR with a uniform urandom stream can only add entropy. */
    xor_fold_entropy(masterKey.data(), (size_t)(n * 64), entropy, entropyLen);

    /* Primary and backup headers must never share a salt — each gets its own
     * fresh urandom salt, with the SAME user entropy XOR'd into both (mirrors
     * wipe_and_rewrite_header's extraEntropy handling for changePassword). */
    SecureBuffer<VC_HEADER_SALT_SIZE> primarySalt, backupSalt;
    const uint8_t *primarySaltPtr = nullptr, *backupSaltPtr = nullptr;
    if (entropy && entropyLen > 0) {
        if (!read_urandom(primarySalt.data(), primarySalt.size()) ||
            !read_urandom(backupSalt.data(), backupSalt.size())) {
            LOGE("[%s] /dev/urandom failed for header salt - aborting", logTag);
            fail_cleanup(); return ERR_RAND;
        }
        xor_fold_entropy(primarySalt.data(), primarySalt.size(), entropy, entropyLen);
        xor_fold_entropy(backupSalt.data(),  backupSalt.size(),  entropy, entropyLen);
        primarySaltPtr = primarySalt.data();
        backupSaltPtr  = backupSalt.data();
    }

    if (write_vc_header(vol, 0, dataSize, VC_DATA_OFFSET,
                        masterKey.data(), algId, (int)hashAlg,
                        (const char*)effPwd.data(), pbkdf2PwdLen, (int)pim,
                        /*hiddenVolSize=*/0, primarySaltPtr) != 0) {
        LOGE("[%s] Primary header write failed", logTag);
        fail_cleanup(); return ERR_FILE;
    }

    /* Write backup header at end of file */
    uint64_t backupOff = fileSize - VC_BACKUP_AREA_SIZE;
    if (write_vc_header(vol, backupOff, dataSize, VC_DATA_OFFSET,
                        masterKey.data(), algId, (int)hashAlg,
                        (const char*)effPwd.data(), pbkdf2PwdLen, (int)pim,
                        /*hiddenVolSize=*/0, backupSaltPtr) != 0) {
        LOGE("[%s] Backup header write failed", logTag);
        fail_cleanup(); return ERR_FILE;
    }
    /* Deliberate early wipe: effPwd/primarySalt/backupSalt are not needed
     * again (masterKey still is, for alloc_drive below), and the data-fill +
     * mkfs work that follows can take a long time on a multi-GB container. */
    effPwd.wipe();
    primarySalt.wipe();
    backupSalt.wipe();

    /* Fill data area */
    if (!quickFormat) {
        const size_t CHUNK = 65536;
        auto *rnd = static_cast<uint8_t*>(malloc(CHUNK));
        if (rnd) {
            memset(rnd, 0, CHUNK);
            uint64_t remaining = dataSize, offset = VC_DATA_OFFSET;
            int rfd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            bool rng_ok = true, write_ok = true;
            uint64_t t0 = monotonic_ms();
            ProgressThrottle throttle;
            while (remaining > 0) {
                size_t sz = (remaining > CHUNK) ? CHUNK : (size_t)remaining;
                if (rfd >= 0) {
                    size_t got = 0;
                    while (got < sz) {
                        ssize_t r = read(rfd, rnd + got, sz - got);
                        if (r > 0) { got += (size_t)r; continue; }
                        if (r < 0 && errno == EINTR) continue;
                        rng_ok = false; break;
                    }
                }
                if (!rng_ok) break;
                if (!vol.write(vol.self, rnd, sz, offset)) { write_ok = false; break; }
                remaining -= sz; offset += sz;
                uint64_t written = dataSize - remaining;
                float fillFrac = (float)written / (float)dataSize;
                float frac     = allocWeight + fillFrac * (fillEnd - allocWeight);
                uint64_t elMs  = monotonic_ms() - t0;
                float speed    = elMs > 0 ? (float)(written/1048576UL)/((float)elMs/1000.f) : 10.f;
                if (throttle.should_report(remaining == 0))
                    report_progress(env, progressListener, progressMid, frac, speed,
                                    (jlong)((double)frac * (double)dataSize));
            }
            if (rfd >= 0) close(rfd);
            secure_memset(rnd, 0, CHUNK);
            free(rnd);
            if (!rng_ok) {
                LOGE("[%s] /dev/urandom failed during data fill - aborting", logTag);
                fail_cleanup(); return ERR_RAND;
            }
            if (!write_ok) {
                LOGE("[%s] write failed during data fill - disk full?", logTag);
                fail_cleanup(); return ERR_NO_SPACE;
            }
        }
    } else if (unlinkPathOnFail) {
        /* Legacy mid-point tick for the plain-file path; the SAF path already
         * reported allocWeight progress from preallocate_fd(). */
        report_progress(env, progressListener, progressMid, 0.5f, 500.f, (jlong)(dataSize/2));
    }

    /* Format filesystem. alloc_drive/free_drive touch g_drives[] slot state
     * shared with every other native call, so the whole claim→format→release
     * sequence runs under one g_fatfs_mutex critical section (diskio.cpp's
     * callbacks run synchronously inside f_mkfs on this same thread and must
     * NOT re-lock — see the comment on g_fatfs_mutex). */
    /* filesystem: 0 = FAT (FAT32), 1 = exFAT, 2 = ext4. FAT and exFAT go through
     * FatFs's f_mkfs; ext4 is formatted by the clean-room formatter over the same
     * decrypting device, so its metadata is written encrypted just like the FAT
     * BPB is. The MKFS_PARM setup below is only read on the FatFs path. */
    const bool wantExt4 = (filesystem == 2);
    char drvPath[8];
    BYTE  work[4096];
    BYTE  fmtFlag = (filesystem == 1) ? (FM_EXFAT|FM_SFD) : ((FM_FAT|FM_FAT32)|FM_SFD);
    BYTE  nFat    = (filesystem == 1) ? 1 : 2;
    /* Cluster size, in bytes, for FAT volumes (ignored by the exFAT path).
       See vc_fat_cluster_size() - leaving this at 0 lands almost every vault on
       FAT16 with a 512-entry root directory, which is not what VeraCrypt would
       have written (issue #115). */
    DWORD auSize = (filesystem == 1) ? 0 : vc_fat_cluster_size(dataSize);
    MKFS_PARM opts = { fmtFlag, nFat, 0, 0, auSize };
    FRESULT fr = FR_DISK_ERR;
    int pdrv;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        /* The drive BORROWS the backend here, unlike a mount where it takes ownership.
         * This function allocates and frees the drive itself, and the caller owns the
         * backend for the whole call - so free_drive below must not release it. Handing
         * over a copy with no close is what keeps the two from both freeing it; without
         * this the caller's release hits an already-deleted global reference and JNI
         * aborts the process. (No effect on the file path: fd_be's close is a no-op.) */
        BlockBackend forDrive = vol;
        if (beIn) forDrive.close = nullptr;
        pdrv = alloc_drive(forDrive, VC_DATA_OFFSET, dataSize / VC_SECTOR_SIZE, masterKey.data(), algId);
        /* Deliberate early wipe: alloc_drive already copied whatever it
         * needed into the per-drive key schedule (on success) or nothing at
         * all (on failure) — masterKey's plaintext is never read again. */
        masterKey.wipe();
        if (pdrv < 0) {
            LOGE("[%s] No drive slot", logTag);
            fail_cleanup(); return ERR_NO_SLOT;
        }
        if (wantExt4) {
            fr = ext4jni_format(pdrv, dataSize) ? FR_OK : FR_DISK_ERR;
        } else {
            snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
            fr = f_mkfs(drvPath, &opts, work, sizeof(work));
        }
        free_drive(pdrv);
    }

    if (fr != FR_OK) {
        LOGE("[%s] f_mkfs failed: %d", logTag, (int)fr);
        fail_cleanup(); return ERR_FS;
    }

    vol.sync(vol.self);
    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)dataSize);
    return ERR_OK;
    /* fd closed by UniqueFd's destructor here, on this and every path above. */
}

/* ─── JNI: nativeCreateContainer ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jlong sizeBytes,
        jbyteArray jPassword, jobjectArray jKeyfileData,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray jEntropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    std::string path  = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    if (path.empty() || pwdLen == 0) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { LOGE("[create] Cannot open/create: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno)); return ERR_FILE; }

    /* Copy (never pin) caller-provided entropy into a local buffer; null/empty
     * jEntropyBytes yields an empty vector, which xor_fold_entropy no-ops on.
     * SecureVector wipes the buffer in its destructor, right before the
     * underlying vector's heap allocation is freed. */
    SecureVector entropy;
    if (jEntropyBytes) {
        jsize elen = env->GetArrayLength(jEntropyBytes);
        if (elen > 0) {
            entropy.resize((size_t)elen);
            env->GetByteArrayRegion(jEntropyBytes, 0, elen, (jbyte*)entropy.data());
        }
    }

    return do_create_container(env, fd, /*beIn=*/nullptr, /*deviceSizeIn=*/0, path.c_str(), "create",
                               sizeBytes, pwdBuf.data(), pwdLen, jKeyfileData,
                               algorithm, hashAlg, filesystem, quickFormat,
                               progressListener, pim,
                               entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── JNI: nativeCreateContainerFd ──────────────────────────────────── */
/* SAF variant: receives an open file descriptor instead of a path.       */
/* The caller keeps its ParcelFileDescriptor open; we dup() to own ours.  */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateContainerFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jlong sizeBytes,
        jbyteArray jPassword, jobjectArray jKeyfileData,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray jEntropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    if (pwdLen == 0) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/create] dup failed: errno=%d", errno); return ERR_FILE; }

    SecureVector entropy;
    if (jEntropyBytes) {
        jsize elen = env->GetArrayLength(jEntropyBytes);
        if (elen > 0) {
            entropy.resize((size_t)elen);
            env->GetByteArrayRegion(jEntropyBytes, 0, elen, (jbyte*)entropy.data());
        }
    }

    return do_create_container(env, fd, /*beIn=*/nullptr, /*deviceSizeIn=*/0, nullptr, "fd/create",
                               sizeBytes, pwdBuf.data(), pwdLen, jKeyfileData,
                               algorithm, hashAlg, filesystem, quickFormat,
                               progressListener, pim,
                               entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── Open-container core ───────────────────────────────────────────── */
/*
 * Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd:
 * every failure path closes it; on success it lives in ContainerCtx until
 * nativeCloseContainer. Passwords arrive as raw bytes + length, owned by the
 * wrappers' SecureBuffer<VC_MAX_PWD_LEN> locals (see get_password_bytes());
 * keyfile data stays as JNI arrays since apply_keyfile_buffers() reads them
 * without mutation.
 */
/*
 * Releases a backend the mount owns but has not yet handed to a drive. Only armed for
 * a backend passed in from outside (a USB device): the drive takes ownership the moment
 * alloc_drive returns, and from then on free_drive is what releases it - so this is
 * disarmed there, or it would release a second time.
 */
struct OwnedBackendGuard {
    BlockBackend be{};
    bool armed = false;
    ~OwnedBackendGuard() { if (armed && be.close) be.close(be.self); }
    void disarm() { armed = false; }
};

/*
 * `beIn` null means file-backed: the descriptor is owned here, the size comes from
 * fstat, and the backend is a wrapper around the fd. Non-null means the volume lives on
 * something that is not a file (a USB device, issue #95) - `fdIn` is then -1, `sizeIn`
 * is the device's size, and the backend is owned by this call until a drive takes it.
 */
static jlong do_open_container(
        JNIEnv *env, int fdIn, const BlockBackend *beIn, uint64_t sizeIn, const char *logTag,
        const uint8_t *pwd, int pwdLen, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        const uint8_t *hiddenPwd, int hiddenPwdLen, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jint protectHiddenHash,
        jobject mountProgressListener, jboolean readOnly, jboolean allowLowMemory)
{
    /* Owns fd on every failure path (closed by the destructor). On success
     * the fd is handed to the registry via ContainerCtx — release() right
     * before that handoff, at the single spot marked below. */
    UniqueFd fd(fdIn);

    OwnedBackendGuard guard;
    if (beIn) { guard.be = *beIn; guard.armed = true; }
    const BlockBackend be = beIn ? *beIn : fd_be(fd.get());

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen)) return (jlong)ERR_RAND;

    /* Prepare hidden-volume credentials for boundary derivation */
    SecureBuffer<VC_MAX_PWD_LEN> hidEffPwd;
    int hidEffPwdLen = hiddenPwdLen;
    if (hidEffPwdLen > 0) {
        memcpy(hidEffPwd.data(), hiddenPwd, (size_t)hidEffPwdLen);
        if (!apply_keyfile_buffers(env, jProtectHiddenKeyfileData, hidEffPwd.data(), &hidEffPwdLen)) return (jlong)ERR_RAND;
    }

    uint64_t fileSize;
    if (beIn) {
        /* Not a file: the size was measured by whoever opened the device (READ CAPACITY
         * for USB) and handed in, because there is nothing here to fstat. */
        fileSize = sizeIn;
    } else {
        struct stat st{};
        if (fstat(fd.get(), &st) != 0) {
            LOGE("[%s] fstat failed: errno=%d", logTag, errno);
            return (jlong)ERR_FILE;
        }
        fileSize = (uint64_t)st.st_size;
    }

    /* Too-small / misaligned is an I/O-shaped problem, not evidence about the
     * password — ERR_READ (Kotlin maps it to IO_ERROR) is the honest category. */
    if (fileSize < VC_DATA_OFFSET || fileSize % VC_SECTOR_SIZE != 0) {
        LOGE("[%s] file too small or not sector-aligned: %llu", logTag, (unsigned long long)fileSize);
        return (jlong)ERR_READ;
    }

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0;

    /* Try primary headers only — matching VeraCrypt default mount (no backup headers) */
    uint64_t tryOffsets[2] = { 0, VC_HIDDEN_HEADER_OFFSET };
    bool tryIsHidden[2] = { false, true };

    int rc = ERR_WRONG_PASSWORD;
    bool authIsHidden = false;
    uint64_t hiddenVolSize = 0;

    MountCb mountCb{ env, mountProgressListener, resolve_mount_mid(env, mountProgressListener), 1, 75 };
    MountCb *pMountCb = mountProgressListener ? &mountCb : nullptr;

    /* Volumes created before the #112 fix carry a header derived from a 64-byte
     * keyfile pool even though their password is over 64 bytes. Try the correct
     * pool first, then fall back, so those still open. Skipped entirely when
     * the two pools cannot differ - otherwise every wrong password would cost
     * two full PBKDF2 sweeps. */
    const bool legacyPoolRetry =
        keyfile_pool_has_legacy_variant(pwdLen, jni_has_keyfiles(env, jKeyfileData));

    for (int variant = 0;
         variant <= (legacyPoolRetry ? 1 : 0) && rc != ERR_OK && rc != ERR_ARGON2_MEMORY;
         variant++) {
        if (variant == 1) {
            LOGI("[%s] auth failed with the standard keyfile pool - retrying with the "
                 "pre-#112 legacy 64-byte pool", logTag);
            effPwdLen = pwdLen;
            memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
            if (!apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen,
                                       /*forceLegacyPool=*/true))
                return (jlong)ERR_RAND;
        }
        for (int ti = 0; ti < 2 && rc != ERR_OK; ti++) {
            if (tryOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
            uint64_t hvSz = 0;
            rc = read_vc_header(be, tryOffsets[ti], (const char*)effPwd.data(), effPwdLen,
                                masterKey.data(), &mkLen, &dataSz, &dataOff, &algId, &hashId,
                                (int)pim, &hvSz, (int)algorithm, (int)hashAlgorithm, pMountCb,
                                allowLowMemory == JNI_TRUE);
            if (rc == ERR_OK) { authIsHidden = tryIsHidden[ti]; hiddenVolSize = hvSz; }
            /* A refusal for want of memory is about the device, not the credentials:
               trying the other header offset would only repeat it (#177). */
            if (rc == ERR_ARGON2_MEMORY) break;
        }
    }

    /* Deliberate early wipe: effPwd is never needed again regardless of
     * outcome (unlike masterKey/hidEffPwd, which the success path still
     * needs below). */
    effPwd.wipe();
    if (rc != ERR_OK) {
        return (jlong)rc;
    }

    /* Geometry sanity check (stage 2b): a header can be CRC-valid yet describe
     * geometry that would cause out-of-bounds I/O (e.g. a corrupted or
     * maliciously crafted header). alloc_drive()/diskio.cpp trust dataOff/dataSz
     * unconditionally, so validate before handing them off. */
    /* dataSz > fileSize - dataOff (not dataOff + dataSz > fileSize): the additive
       form wraps in uint64 for crafted header geometry, letting an out-of-range
       region slip past. Guard dataOff first so the subtraction can't underflow. */
    if (dataSz == 0 || dataSz % VC_SECTOR_SIZE != 0 ||
        dataOff % VC_SECTOR_SIZE != 0 ||
        dataOff > fileSize || dataSz > fileSize - dataOff) {
        LOGE("[%s] header geometry out of range (dataOff=%llu dataSz=%llu fileSize=%llu)",
             logTag, (unsigned long long)dataOff, (unsigned long long)dataSz, (unsigned long long)fileSize);
        return (jlong)ERR_UNSUPPORTED;
    }

    /* Only enforce boundary when protection is explicitly requested — never auto-detect from
       the outer header's hiddenVolSize, because that would reveal the hidden volume's existence
       to an adversary who forces the user to open the outer vault without protection.
     *
     * Protection either holds or the mount fails: every path out of this block that does not
     * set hiddenBoundary returns an error. It used to leave the boundary at 0 and carry on
     * mounting read-write, under a UI that states protection is active - the hidden volume was
     * then one copy away from being overwritten. VeraCrypt refuses the same cases
     * (Volume.cpp: ProtectionPasswordIncorrect / ProtectionPasswordKeyfilesIncorrect). */
    uint64_t hiddenBoundary = 0;
    if (hidEffPwdLen > 0) {
        /* The credentials opened the hidden volume itself, so there is no outer volume in
         * this mount to keep away from it. VeraCrypt treats it as an error too. */
        if (authIsHidden) {
            LOGE("[%s] protect-hidden: these credentials open the hidden volume itself", logTag);
            return (jlong)ERR_HIDDEN_IS_TARGET;
        }
        int hidRc = ERR_WRONG_PASSWORD;
        uint64_t hidDataSz = 0, hidHeaderDataOff = 0;
        if (hiddenVolSize > 0) {
            /* Legacy containers only: field28 in an outer header is 0 for VeraCrypt volumes
             * and for everything Arcanum has written since the deniability fix, so nothing
             * made after it comes through here. No hidden header is read on this path, so
             * the start has to be reconstructed - and it is measured from the end of the
             * file, where a hidden volume is placed (see do_create_hidden_volume), not from
             * the outer volume's own size. */
            hidDataSz = hiddenVolSize;
            hidHeaderDataOff = fileSize - VC_BACKUP_AREA_SIZE - hiddenVolSize;
            hidRc = ERR_OK;
        } else {
            uint64_t hidOffsets[2] = { VC_HIDDEN_HEADER_OFFSET, fileSize - VC_HIDDEN_HEADER_OFFSET };
            SecureBuffer<192> hidMasterKey;
            int hidMkLen = 0, hidAlgId = 0, hidHashId = 0;
            uint64_t hidHvSz = 0;
            /* Same legacy-pool fallback as the main credential. */
            const bool hidLegacyRetry = keyfile_pool_has_legacy_variant(
                hiddenPwdLen, jni_has_keyfiles(env, jProtectHiddenKeyfileData));
            /* ERR_ARGON2_MEMORY ends it as well: the legacy-pool variant would pay for the
             * same refused derivation a second time. */
            for (int variant = 0;
                 variant <= (hidLegacyRetry ? 1 : 0) && hidRc != ERR_OK && hidRc != ERR_ARGON2_MEMORY;
                 variant++) {
                if (variant == 1) {
                    hidEffPwdLen = hiddenPwdLen;
                    memcpy(hidEffPwd.data(), hiddenPwd, (size_t)hidEffPwdLen);
                    if (!apply_keyfile_buffers(env, jProtectHiddenKeyfileData, hidEffPwd.data(),
                                               &hidEffPwdLen, /*forceLegacyPool=*/true))
                        return (jlong)ERR_RAND;
                }
                for (int ti = 0; ti < 2; ti++) {
                    if (hidOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
                    hidRc = read_vc_header(be, hidOffsets[ti], (const char*)hidEffPwd.data(), hidEffPwdLen,
                                           hidMasterKey.data(), &hidMkLen, &hidDataSz, &hidHeaderDataOff,
                                           &hidAlgId, &hidHashId, (int)protectHiddenPim, &hidHvSz,
                                           -1, (int)protectHiddenHash, nullptr,
                                           allowLowMemory == JNI_TRUE);
                    if (hidRc == ERR_OK) break;
                    /* About the device, not the credentials: the other offset would only
                     * repeat it (#177). */
                    if (hidRc == ERR_ARGON2_MEMORY) break;
                }
            }
            /* hidMasterKey wiped by its destructor here, at the end of this
             * inner scope — same timing as the manual wipe it replaces. */
        }
        /* Geometry that could not describe a hidden volume inside this one: a header that
         * decrypted but says something impossible. Refused rather than turned into a
         * boundary that protects the wrong range. */
        if (hidRc == ERR_OK &&
            (hidDataSz == 0 || hidHeaderDataOff % VC_SECTOR_SIZE != 0 ||
             hidHeaderDataOff <= dataOff || hidHeaderDataOff >= fileSize ||
             hidDataSz > fileSize - hidHeaderDataOff)) {
            LOGE("[%s] protect-hidden: hidden geometry out of range (start=%llu size=%llu "
                 "outer=%llu+%llu file=%llu)",
                 logTag, (unsigned long long)hidHeaderDataOff, (unsigned long long)hidDataSz,
                 (unsigned long long)dataOff, (unsigned long long)dataSz,
                 (unsigned long long)fileSize);
            hidRc = ERR_UNSUPPORTED;
        }
        if (hidRc != ERR_OK) {
            LOGE("[%s] protect-hidden: could not establish the boundary (rc=%d) - refusing the mount",
                 logTag, hidRc);
            /* Out of memory for Argon2id is worth saying as itself; anything else is
             * indistinguishable from wrong hidden credentials and is reported as one. */
            return (jlong)(hidRc == ERR_ARGON2_MEMORY ? ERR_ARGON2_MEMORY : ERR_HIDDEN_PROTECTION);
        }
        /* Where the hidden volume actually begins, as its own header states it - the same
         * value VeraCrypt's driver protects from (Ntvol.c: hiddenVolumeOffset =
         * cryptoInfo->EncryptedAreaStart).
         *
         * This used to be computed as dataOff + dataSz - hidDataSz, and that is 128 KB too
         * high on every file-hosted volume: the outer header's own size runs to the end of
         * the file, so subtracting the hidden size lands one backup-header group past the
         * hidden volume's first sector. Protection was on, the boundary was wrong, and the
         * outer volume could still write over the hidden volume's boot sector and FAT -
         * measured at 0x1e00000 against a true start of 0x1de0000. */
        hiddenBoundary = hidHeaderDataOff;
        LOGI("[%s] protect-hidden: boundary set to 0x%llx (hidden volume %llu bytes)",
             logTag, (unsigned long long)hiddenBoundary, (unsigned long long)hidDataSz);
    }
    /* Deliberate early wipe: hidEffPwd's only remaining use was computing
     * hiddenBoundary above. */
    hidEffPwd.wipe();

    uint32_t iterCount = vc_get_iterations(hashId, (int)pim);
    /* alloc_drive → f_mount → g_ctxMap publish all run under one lock (stage 1c):
     * g_drives[]/g_ctxMap are shared registries and f_mount's diskio.cpp callbacks
     * run synchronously on this thread and must not attempt to re-lock. */
    char drvPath[8];
    FRESULT fr = FR_DISK_ERR;
    int pdrv;
    uint32_t gen;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        pdrv = alloc_drive(be, dataOff, dataSz / VC_SECTOR_SIZE, masterKey.data(), algId, hashId,
                           authIsHidden, hiddenBoundary, iterCount, (bool)readOnly);
        /* Deliberate early wipe: alloc_drive already consumed masterKey
         * (on success) or nothing at all (on failure). */
        masterKey.wipe();
        if (pdrv < 0) return (jlong)ERR_NO_SLOT;
        /* The drive holds the backend now; free_drive releases it on every path from
         * here, including the f_mount failure below. */
        guard.disarm();
        gen = g_drives[pdrv].generation;

        /* ContainerCtx via unique_ptr so the f_mount-failure path below just
         * lets it go out of scope instead of a manual delete; release() only
         * on the success path, where ownership transfers to g_ctxMap. */
        std::unique_ptr<ContainerCtx> ctx(new ContainerCtx{ pdrv, {}, fd.get(), (bool)readOnly });

        /* Which filesystem this is, decided by the volume's own bytes now that the
         * drive decrypts. ext4 keeps no mounted state - its ops open the reader and
         * writer fresh each time - so there is no f_mount for it; the FatFs path is
         * unchanged. */
        bool ext4NeedsCheck = false;
        if (ext4jni_probe(pdrv, &ext4NeedsCheck)) {
            ctx->isExt4 = true;
            ctx->ext4NeedsCheck = ext4NeedsCheck;
            LOGI("[%s] mounted as ext4%s", logTag,
                 ext4NeedsCheck ? " (left mid-write, a check is owed)" : "");
            fr = FR_OK;
        } else {
            snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
            fr = f_mount(&ctx->fatFs, drvPath, 1);
            if (fr != FR_OK) {
                LOGE("[%s] f_mount failed: %d", logTag, (int)fr);
                free_drive(pdrv);
                return (jlong)ERR_FS;
                /* ctx deleted by unique_ptr, fd closed by UniqueFd, both here. */
            }
        }
        g_ctxMap[pdrv] = ctx.release();  /* registry now owns ctx */
        fd.release();                   /* registry (via ctx->fd) now owns the fd */
    }
    /* Handle = generation (bits 8+) | pdrv (bits 0-7). Kotlin treats this as an
     * opaque Long (checks handle >= 0 for success); generation is capped well
     * under 2^31 so the sign bit never flips. */
    return ((jlong)gen << 8) | (jlong)pdrv;
}

/* ─── JNI: nativeOpenContainerFd ────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainerFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jbyteArray jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jbyteArray jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jint protectHiddenHash,
        jobject mountProgressListener, jboolean readOnly, jboolean allowLowMemory)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hidPwdBuf;
    int hidPwdLen = get_password_bytes(env, jProtectHiddenPassword, hidPwdBuf);

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/open] dup failed: errno=%d", errno); return (jlong)ERR_FILE; }

    return do_open_container(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0, "fd/open",
                             pwdBuf.data(), pwdLen, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hidPwdBuf.data(), hidPwdLen, jProtectHiddenKeyfileData, protectHiddenPim,
                             protectHiddenHash,
                             mountProgressListener, readOnly, allowLowMemory);
}

/* ─── JNI: nativeOpenContainer ──────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jbyteArray jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jbyteArray jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jint protectHiddenHash,
        jobject mountProgressListener, jboolean readOnly, jboolean allowLowMemory)
{
    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hidPwdBuf;
    int hidPwdLen = get_password_bytes(env, jProtectHiddenPassword, hidPwdBuf);

    /* Open read-only at the OS level for read-only mounts so the kernel itself
     * refuses any write, independent of the ctx->readOnly / disk_write guards.
     * (The SAF variant gets an already-read-only fd: Kotlin opens the PFD "r".) */
    int fd = open(path.c_str(), readOnly ? O_RDONLY : O_RDWR);
    if (fd < 0) { LOGE("[open] Cannot open: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno)); return (jlong)ERR_FILE; }

    return do_open_container(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0, "open",
                             pwdBuf.data(), pwdLen, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hidPwdBuf.data(), hidPwdLen, jProtectHiddenKeyfileData, protectHiddenPim,
                             protectHiddenHash,
                             mountProgressListener, readOnly, allowLowMemory);
}

/* ─── JNI: nativeOpenContainerUsb ───────────────────────────────────── */
/*
 * Mounts a VeraCrypt volume that occupies a whole USB device (issue #95).
 *
 * `transport` is an open Kotlin UsbBlockDevice; `deviceSize` is its capacity, measured
 * there by READ CAPACITY, because there is no fstat to ask. Everything after the backing
 * store is identical to a file-hosted volume: the same header layout, the same key
 * derivation, the same filesystem probe.
 *
 * Ownership: this call builds the backend and hands it to do_open_container, which
 * releases it on any failure and gives it to the drive on success. The transport itself
 * is NOT closed here on failure - Kotlin opened it and Kotlin closes it, the same rule
 * the file path follows with its descriptor.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainerUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize,
        jbyteArray jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jbyteArray jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jint protectHiddenHash,
        jobject mountProgressListener, jboolean readOnly, jboolean allowLowMemory)
{
    if (!transport || deviceSize <= 0) return (jlong)ERR_FILE;

    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hidPwdBuf;
    int hidPwdLen = get_password_bytes(env, jProtectHiddenPassword, hidPwdBuf);

    BlockBackend be{};
    if (!usb_backend_init(&be, env, transport, (bool)readOnly)) {
        LOGE("[usb/open] backend init failed");
        return (jlong)ERR_FILE;
    }

    return do_open_container(env, /*fdIn=*/-1, &be, (uint64_t)deviceSize, "usb/open",
                             pwdBuf.data(), pwdLen, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hidPwdBuf.data(), hidPwdLen, jProtectHiddenKeyfileData, protectHiddenPim,
                             protectHiddenHash,
                             mountProgressListener, readOnly, allowLowMemory);
}


/* ─── JNI: nativeFlushContainer ─────────────────────────────────────── */

/*
 * Pushes everything held on our side down to the medium, leaving the volume mounted.
 *
 * The USB backend holds up to a megabyte of writes back so that repeated updates to the
 * same block cost one command instead of ten. That is a good trade while the app is in
 * front of the user and a bad one the moment Android decides to kill it in the
 * background, because those bytes exist nowhere else. Flushing on the way out costs a
 * command or two and removes the whole category.
 *
 * Safe to call on any mounted volume: for a file-backed one the backend's sync is an
 * fsync, which is worth doing for the same reason.
 */
extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeFlushContainer(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    int pdrv = decode_handle(handle);
    if (pdrv < 0) return ERR_FILE;
    if (g_drives[pdrv].backend.sync)
        g_drives[pdrv].backend.sync(g_drives[pdrv].backend.self);
    return ERR_OK;
}

/* ─── JNI: nativeArgon2Cost ─────────────────────────────────────────── */
/*
 * What an Argon2id derivation at this PIM would cost, and what the device has
 * to spare, so the UI can say it in numbers before anyone commits to it:
 *   [0] passes, [1] memory in MiB, [2] memory the kernel says is available, MiB.
 *
 * Reads from the same functions the derivation itself uses, rather than the
 * formula being written a second time in Kotlin where it could drift (#177).
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeArgon2Cost(
        JNIEnv *env, jobject /*thiz*/, jint pim)
{
    uint32_t tCost = 0, mCostKiB = 0;
    vc_argon2_params((int)pim, &tCost, &mCostKiB);
    jint values[3] = {
        (jint)tCost,
        (jint)(mCostKiB / 1024u),
        (jint)(vc_memory_available_bytes() >> 20)
    };
    jintArray out = env->NewIntArray(3);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

/* ─── JNI: nativeCloseContainer ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCloseContainer(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    /* find + f_unmount + free_drive + erase run in ONE lock scope so no other
     * thread can observe the registry mid-teardown. fsync/close(fd)/delete ctx
     * happen after the lock is released — they don't touch shared state.
     * ctx and its fd are handed to a unique_ptr/UniqueFd instead of a manual
     * delete/close: this function has exactly one exit path once past the
     * lock, so it's purely a consistency win, not a forgotten-cleanup fix. */
    std::unique_ptr<ContainerCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = decode_handle(handle);
        if (pdrv < 0) return ERR_NO_SLOT;
        auto it = g_ctxMap.find(pdrv);
        if (it == g_ctxMap.end()) return ERR_NO_SLOT;
        ctx.reset(it->second);

        // Close any cached streaming read handle on this drive before unmounting -
        // f_close must run while the filesystem is still valid.
        invalidate_read_cache_for_pdrv(pdrv);

        /* ext4 was never f_mounted (it keeps no FatFs state), so there is nothing to
         * f_unmount; freeing the drive and dropping the ctx is the whole teardown. */
        if (!ctx->isExt4) {
            char drvPath[8];
            snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
            f_unmount(drvPath);
        }
        /* Flush through the backend, after f_unmount has written whatever it had and
         * before free_drive tears the backend down.
         *
         * This used to be an fsync on ctx->fd after the lock. That covered a file, but
         * not the two cases that matter now: ext4 is never f_mounted, so nothing else
         * issues CTRL_SYNC for it, and a backend that is not a file has no descriptor
         * for an fsync to reach. Asking the backend covers all three - fsync for a
         * file, SYNCHRONIZE CACHE for a USB device. */
        if (g_drives[pdrv].backend.sync)
            g_drives[pdrv].backend.sync(g_drives[pdrv].backend.self);

        free_drive(pdrv);
        g_ctxMap.erase(it);
    }
    UniqueFd fd(ctx->fd);   /* -1 when the volume was not file-backed: nothing to close */
    return ERR_OK;
    /* fd closed and ctx deleted by their destructors, here. */
}

/* ─── JNI: nativeCreateHiddenVolume ─────────────────────────────────── */
/* Adds a hidden volume inside an existing outer VeraCrypt container.
   Steps:
   1. Authenticates outer volume with outer password.
   2. Writes hidden primary + backup headers.
   3. Formats the hidden area as FAT32.
 *
 * DENIABILITY: the outer headers are deliberately NOT touched here. VeraCrypt
 * never records the hidden volume's size (field28) in the outer header —
 * doing so would let anyone who obtains the outer password decrypt the outer
 * header offline and prove a hidden volume exists (and learn its size),
 * defeating plausible deniability. Outer-write protection while the outer
 * volume is mounted is instead derived at mount time in nativeOpenContainer(Fd)
 * by decrypting the hidden header with the protection password (see
 * hiddenBoundary there). field28 on the outer header stays 0, exactly like a
 * container with no hidden volume. Reading field28 on mount is kept only for
 * backward compatibility with containers created before this fix.
 *
 * The hidden volume's own headers are the opposite case: they carry their size in
 * field28, as VeraCrypt's do, and cannot be read without the hidden password. See
 * where they are written below. */

/* Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_create_hidden_volume(
        JNIEnv *env, int fdIn, const char *logTag,
        jlong hiddenSizeBytes,
        const uint8_t *outerPwd, int outerPwdLen, jobjectArray jOuterKeyfileData, jint outerPim,
        const uint8_t *hiddenPwd, int hiddenPwdLen, jobjectArray jHiddenKeyfileData, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jobject progressListener,
        const uint8_t *entropy, size_t entropyLen)
{
    /* fd is always closed by this function on every path (never stored
     * beyond it), so a single UniqueFd covers the whole function. */
    UniqueFd fd(fdIn);
    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    off_t fileSzOff = lseek(fd.get(), 0, SEEK_END);
    if (fileSzOff < 0) return ERR_FILE;
    uint64_t fileSize = (uint64_t)fileSzOff;

    uint64_t hidSz = (uint64_t)hiddenSizeBytes;
    /* Need room for both header regions + data offset + hidden data */
    if (fileSize < VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE + hidSz) {
        LOGE("[%s] file too small (%llu) for hidden size %llu",
             logTag, (unsigned long long)fileSize, (unsigned long long)hidSz);
        return ERR_NO_SPACE;
    }

    /* ── Outer effective password ── */
    SecureBuffer<VC_MAX_PWD_LEN> outerEffPwd;
    int outerEffPwdLen = outerPwdLen;
    memcpy(outerEffPwd.data(), outerPwd, (size_t)outerEffPwdLen);
    if (!apply_keyfile_buffers(env, jOuterKeyfileData, outerEffPwd.data(), &outerEffPwdLen)) return ERR_RAND;

    /* ── Hidden effective password ── */
    SecureBuffer<VC_MAX_PWD_LEN> hiddenEffPwd;
    int hiddenEffPwdLen = hiddenPwdLen;
    memcpy(hiddenEffPwd.data(), hiddenPwd, (size_t)hiddenEffPwdLen);
    if (!apply_keyfile_buffers(env, jHiddenKeyfileData, hiddenEffPwd.data(), &hiddenEffPwdLen)) return ERR_RAND;

    /* ── Authenticate outer volume (primary header) ── */
    /* Outer headers are never rewritten (see deniability note above) — this
       call exists solely to verify the outer password before we touch the
       hidden-area headers. */
    SecureBuffer<192> outerMasterKey;
    int outerMkLen = 0, outerAlgId = 0, outerHashId = 0;
    uint64_t outerDataSz = 0, outerDataOff = 0;
    int rc = read_vc_header(fd_be(fd.get()), 0,
                            (const char*)outerEffPwd.data(), outerEffPwdLen,
                            outerMasterKey.data(), &outerMkLen,
                            &outerDataSz, &outerDataOff,
                            &outerAlgId, &outerHashId,
                            (int)outerPim, nullptr);
    /* Deliberate early wipe: neither outerMasterKey nor outerEffPwd is used
     * again — this call exists solely to validate the outer password. */
    outerMasterKey.wipe();
    outerEffPwd.wipe();
    if (rc != ERR_OK) {
        LOGE("[%s] outer auth failed (%d)", logTag, rc);
        return ERR_WRONG_PASSWORD;
    }

    /* ── Compute hidden data area geometry ── */
    /* Hidden data grows backwards from the start of the backup area */
    uint64_t hiddenDataOff = fileSize - VC_BACKUP_AREA_SIZE - hidSz;

    /* ── Generate hidden master key and write hidden headers ── */
    int hiddenAlgId = (int)hiddenAlgorithm;
    if (hiddenAlgId < 0 || hiddenAlgId >= NUM_ALGORITHMS) {
        return ERR_FS;
    }
    int hiddenN = ALGORITHMS[hiddenAlgId].n;
    SecureBuffer<192> hiddenMasterKey;
    if (!read_urandom(hiddenMasterKey.data(), (size_t)(hiddenN * 64))) {
        LOGE("[%s] /dev/urandom failed for hidden master key - aborting", logTag);
        return ERR_RAND;
    }
    /* Random Pool Enrichment for the hidden volume's master key (same
     * treatment as the outer/standard create path). */
    xor_fold_entropy(hiddenMasterKey.data(), (size_t)(hiddenN * 64), entropy, entropyLen);

    /* Hidden primary and hidden backup headers each get their own fresh salt
     * (never shared), with the same user entropy XOR'd into both. This does
     * NOT touch the outer volume's headers — see the deniability note above;
     * the outer headers are never rewritten by this function. */
    SecureBuffer<VC_HEADER_SALT_SIZE> hiddenPrimarySalt, hiddenBackupSalt;
    const uint8_t *hiddenPrimarySaltPtr = nullptr, *hiddenBackupSaltPtr = nullptr;
    if (entropy && entropyLen > 0) {
        if (!read_urandom(hiddenPrimarySalt.data(), hiddenPrimarySalt.size()) ||
            !read_urandom(hiddenBackupSalt.data(),  hiddenBackupSalt.size())) {
            LOGE("[%s] /dev/urandom failed for hidden header salt - aborting", logTag);
            return ERR_RAND;
        }
        xor_fold_entropy(hiddenPrimarySalt.data(), hiddenPrimarySalt.size(), entropy, entropyLen);
        xor_fold_entropy(hiddenBackupSalt.data(),  hiddenBackupSalt.size(),  entropy, entropyLen);
        hiddenPrimarySaltPtr = hiddenPrimarySalt.data();
        hiddenBackupSaltPtr  = hiddenBackupSalt.data();
    }

    /* field28 carries the hidden volume's own size, in the hidden headers only.
     *
     * This is not the same field as the one the outer header must never carry (see the
     * DENIABILITY note above): there it would prove a hidden volume exists to anyone
     * holding the outer password, while here it sits inside a header that nothing can
     * decrypt without the hidden password. VeraCrypt writes it (Volumes.c:1230) and reads
     * it back as the volume's identity - `hiddenVolume = (hiddenVolumeSize != 0)` - and its
     * Windows driver takes the mounted volume's length straight from it
     * (Ntvol.c:775, `DiskLength = cryptoInfoPtr->hiddenVolumeSize`), refusing the mount
     * outright when it is zero. Written as 0, as it was until now, an Arcanum hidden volume
     * could not be opened by VeraCrypt on Windows at all, and hidden-volume protection there
     * would have guarded a range of zero bytes. */
    if (write_vc_header(fd_be(fd.get()), VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey.data(), hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd.data(), hiddenEffPwdLen,
                        (int)hiddenPim, hidSz, hiddenPrimarySaltPtr) != 0) {
        return ERR_FILE;
    }
    /* Backup hidden header at fileSize - VC_HIDDEN_HEADER_OFFSET */
    if (write_vc_header(fd_be(fd.get()), fileSize - VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey.data(), hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd.data(), hiddenEffPwdLen,
                        (int)hiddenPim, hidSz, hiddenBackupSaltPtr) != 0) {
        return ERR_FILE;
    }
    /* Deliberate early wipe: hiddenEffPwd/hiddenPrimarySalt/hiddenBackupSalt
     * are not needed again (hiddenMasterKey still is, for alloc_drive
     * below), and mkfs on the hidden area follows. */
    hiddenEffPwd.wipe();
    hiddenPrimarySalt.wipe();
    hiddenBackupSalt.wipe();

    /* ── Format hidden area ──
       The comment here used to claim FAT32, but with au_size left at 0 FatFs
       started at FAT16 and stayed there for anything under ~2 GB, exactly as on
       the outer-volume path (issue #115). Same fix: pass VeraCrypt's cluster
       size and let the type follow from it. */
    char drvPath[8];
    BYTE work[4096];
    MKFS_PARM opts = { (FM_FAT | FM_FAT32) | FM_SFD, 2, 0, 0, vc_fat_cluster_size(hidSz) };
    FRESULT fr = FR_DISK_ERR;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = alloc_drive(fd_be(fd.get()), hiddenDataOff, hidSz / VC_SECTOR_SIZE,
                               hiddenMasterKey.data(), hiddenAlgId, (int)hiddenHashAlg,
                               true, 0);
        /* Deliberate early wipe: alloc_drive already consumed hiddenMasterKey
         * (on success) or nothing at all (on failure). */
        hiddenMasterKey.wipe();
        if (pdrv < 0) {
            LOGE("[%s] No free drive slot", logTag);
            return ERR_NO_SLOT;
        }
        snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
        free_drive(pdrv);
    }

    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)hidSz);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
    /* fd closed by UniqueFd's destructor here, on this and every path above. */
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateHiddenVolume(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath,
        jlong hiddenSizeBytes,
        jbyteArray jOuterPassword, jobjectArray jOuterKeyfileData, jint outerPim,
        jbyteArray jHiddenPassword, jobjectArray jHiddenKeyfileData, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jboolean /*quickFormat*/,
        jbyteArray jEntropyBytes,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> outerPwdBuf;
    int outerPwdLen = get_password_bytes(env, jOuterPassword, outerPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hiddenPwdBuf;
    int hiddenPwdLen = get_password_bytes(env, jHiddenPassword, hiddenPwdBuf);

    if (path.empty() || outerPwdLen == 0 || hiddenPwdLen == 0) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) { LOGE("[hidden] cannot open %s", path.c_str()); return ERR_FILE; }

    SecureVector entropy;
    if (jEntropyBytes) {
        jsize elen = env->GetArrayLength(jEntropyBytes);
        if (elen > 0) {
            entropy.resize((size_t)elen);
            env->GetByteArrayRegion(jEntropyBytes, 0, elen, (jbyte*)entropy.data());
        }
    }

    return do_create_hidden_volume(env, fd, "hidden", hiddenSizeBytes,
                                   outerPwdBuf.data(), outerPwdLen, jOuterKeyfileData, outerPim,
                                   hiddenPwdBuf.data(), hiddenPwdLen, jHiddenKeyfileData, hiddenPim,
                                   hiddenAlgorithm, hiddenHashAlg, progressListener,
                                   entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── JNI: nativeCreateHiddenVolumeFd ───────────────────────────────── */
/* SAF variant: receives an open fd instead of a path. Uses dup() so the  */
/* caller's ParcelFileDescriptor stays valid for the container lifetime.   */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateHiddenVolumeFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jlong hiddenSizeBytes,
        jbyteArray jOuterPassword, jobjectArray jOuterKeyfileData, jint outerPim,
        jbyteArray jHiddenPassword, jobjectArray jHiddenKeyfileData, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jboolean /*quickFormat*/,
        jbyteArray jEntropyBytes,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    SecureBuffer<VC_MAX_PWD_LEN> outerPwdBuf;
    int outerPwdLen = get_password_bytes(env, jOuterPassword, outerPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hiddenPwdBuf;
    int hiddenPwdLen = get_password_bytes(env, jHiddenPassword, hiddenPwdBuf);

    if (outerPwdLen == 0 || hiddenPwdLen == 0) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/hidden] dup failed: errno=%d", errno); return ERR_FILE; }

    SecureVector entropy;
    if (jEntropyBytes) {
        jsize elen = env->GetArrayLength(jEntropyBytes);
        if (elen > 0) {
            entropy.resize((size_t)elen);
            env->GetByteArrayRegion(jEntropyBytes, 0, elen, (jbyte*)entropy.data());
        }
    }

    return do_create_hidden_volume(env, fd, "fd/hidden", hiddenSizeBytes,
                                   outerPwdBuf.data(), outerPwdLen, jOuterKeyfileData, outerPim,
                                   hiddenPwdBuf.data(), hiddenPwdLen, jHiddenKeyfileData, hiddenPim,
                                   hiddenAlgorithm, hiddenHashAlg, progressListener,
                                   entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── Which header a change operation is about ──────────────────────── */
/*
 * A container holds up to two independent volumes, each with its own pair of headers, and
 * a password only opens one of them. Change password and change keyfile used to read
 * offset 0 and nothing else, so the hidden volume's own password was answered with "wrong
 * password" - the operation could not reach a hidden volume at all. VeraCrypt opens the
 * volume by trying every layout and then rewrites the header of the layout that opened
 * (Volume.cpp: ReEncryptHeader at Layout->GetHeaderOffset), which is what this reproduces.
 *
 * On success `outHeaderOff` / `outBackupOff` name the pair to rewrite: the volume's own
 * headers at 0 and fileSize - 0x20000, or the hidden volume's at 0x10000 and
 * fileSize - 0x10000.
 */
static int auth_volume_or_hidden(const BlockBackend &vol, uint64_t fileSize,
                                 const uint8_t *eff, int effLen, int pim, int hintHashId,
                                 uint8_t *masterKey, int *mkLen, uint64_t *dataSz,
                                 uint64_t *dataOff, int *algId, int *hashId,
                                 uint64_t *hiddenVolSize,
                                 uint64_t *outHeaderOff, uint64_t *outBackupOff)
{
    int rc = read_vc_header(vol, 0, (const char*)eff, effLen, masterKey, mkLen, dataSz, dataOff,
                            algId, hashId, pim, hiddenVolSize, -1, hintHashId);
    if (rc == ERR_OK) {
        *outHeaderOff = 0;
        *outBackupOff = fileSize - VC_BACKUP_AREA_SIZE;
        return rc;
    }
    /* A refusal for want of memory is about the device, not the credentials (#177). */
    if (rc == ERR_ARGON2_MEMORY) return rc;
    if (VC_HIDDEN_HEADER_OFFSET + VC_HEADER_SIZE > fileSize) return rc;

    int hrc = read_vc_header(vol, VC_HIDDEN_HEADER_OFFSET, (const char*)eff, effLen,
                             masterKey, mkLen, dataSz, dataOff, algId, hashId, pim,
                             hiddenVolSize, -1, hintHashId);
    if (hrc == ERR_OK) {
        *outHeaderOff = VC_HIDDEN_HEADER_OFFSET;
        *outBackupOff = fileSize - VC_HIDDEN_HEADER_OFFSET;
    }
    return hrc;
}

/* ─── Change-password core ──────────────────────────────────────────── */
/* Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
/*
 * `beIn` null means the volume is a file and `fdIn` owns it, as before. Non-null means
 * the volume is not a file (a USB device, #95): `fdIn` is -1, `sizeIn` is its size, and
 * the backend belongs to the caller - these functions borrow it and close nothing.
 */
static jint do_change_password(
        JNIEnv *env, int fdIn, const BlockBackend *beIn, uint64_t sizeIn,
        const uint8_t *oldPwd, int oldPwdLen, jobjectArray jOldKeyfileData, jint oldPim,
        const uint8_t *newPwd, int newPwdLen, jobjectArray jNewKeyfileData,
        jint newHashAlg, jint newPim, jint wipePassCount, jbyteArray jExtraEntropy,
        jint oldHashAlg)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);
    const BlockBackend vol = beIn ? *beIn : fd_be(fd.get());

    /* Build old effective password (password + keyfile pool) */
    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = oldPwdLen;
    memcpy(oldEffPwd.data(), oldPwd, (size_t)oldEffPwdLen);
    if (!apply_keyfile_buffers(env, jOldKeyfileData, oldEffPwd.data(), &oldEffPwdLen)) return ERR_RAND;

    /* Build new effective password */
    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = newPwdLen;
    memcpy(newEffPwd.data(), newPwd, (size_t)newEffPwdLen);
    if (!apply_keyfile_buffers(env, jNewKeyfileData, newEffPwd.data(), &newEffPwdLen)) return ERR_RAND;

    uint64_t fileSize;
    if (beIn) {
        fileSize = sizeIn;   /* nothing to seek on: the volume is a device, not a file */
    } else {
        off_t fileSzOff = lseek(fd.get(), 0, SEEK_END);
        if (fileSzOff < 0) {
            return ERR_FILE;
        }
        fileSize = (uint64_t)fileSzOff;
    }

    /* Authenticate primary header with old credentials */
    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0;
    uint64_t hiddenVolSize = 0;
    uint64_t headerOff = 0, backupHeaderOff = fileSize - VC_BACKUP_AREA_SIZE;
    int rc = auth_with_legacy_pool_retry(
        env, jOldKeyfileData, oldPwd, oldPwdLen, oldEffPwd.data(), &oldEffPwdLen, "chpwd",
        /*usedLegacyPool=*/nullptr,   /* writes use newEffPwd, always standard */
        [&](const uint8_t *eff, int effLen) {
            return auth_volume_or_hidden(vol, fileSize, eff, effLen, (int)oldPim,
                                         (int)oldHashAlg, masterKey.data(), &mkLen,
                                         &dataSz, &dataOff, &algId, &hashId, &hiddenVolSize,
                                         &headerOff, &backupHeaderOff);
        });
    /* Deliberate early wipe: oldEffPwd is never needed again regardless of
     * outcome. The new credential above was built with the correct pool, so a
     * volume opened via the legacy retry is rewritten correct by this change. */
    oldEffPwd.wipe();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    int passes = (int)wipePassCount;
    if (passes < 1) passes = 1;
    /* VC_NUM_PRFS, not the five PBKDF2 hashes: Argon2id is a legal choice here, and
     * clamping it away meant the app reported a changed PRF and wrote the old one (#177). */
    int newHash = (int)newHashAlg;
    if (newHash < 0 || newHash >= VC_NUM_PRFS) newHash = hashId; /* keep old hash if invalid */

    /* oldPwd/newPwd contents are now in effPwd buffers; the wrappers' own
     * SecureBuffers (holding the jbyteArray copies) are wiped by their
     * destructors at scope exit. */

    /* Acquire entropy pin only after all validation passes, so every subsequent
     * exit path releases it automatically via ScopedArrayPin's destructor —
     * no per-path bookkeeping needed. */
    ScopedArrayPin entropyPin(env, jExtraEntropy);

    /* Wipe + rewrite primary header.
     * If this fails the backup header is still intact with old credentials — container
     * is recoverable. Bail immediately so we never touch the backup. */
    int r1 = wipe_and_rewrite_header(vol, headerOff,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)newPim, hiddenVolSize, passes,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());
    if (r1 != 0) {
        return ERR_FILE;
    }

    int r2 = wipe_and_rewrite_header(vol, backupHeaderOff,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)newPim, hiddenVolSize, passes,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());

    return r2 == 0 ? ERR_OK : ERR_FILE;
    /* masterKey/newEffPwd wiped, entropyPin released, fd closed — all by
     * their destructors, here and on every path above. */
}

/* ─── JNI: nativeChangePassword ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangePassword(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath,
        jbyteArray jOldPassword, jobjectArray jOldKeyfileData, jint oldPim,
        jbyteArray jNewPassword, jobjectArray jNewKeyfileData, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy, jint oldHashAlg)
{
    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> oldPwdBuf;
    int oldPwdLen = get_password_bytes(env, jOldPassword, oldPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> newPwdBuf;
    int newPwdLen = get_password_bytes(env, jNewPassword, newPwdBuf);

    if (path.empty() || newPwdLen == 0) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0,
                              oldPwdBuf.data(), oldPwdLen, jOldKeyfileData, oldPim,
                              newPwdBuf.data(), newPwdLen, jNewKeyfileData,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy, oldHashAlg);
}

/* ─── JNI: nativeChangePasswordFd ───────────────────────────────────── */
/* SAF variant: takes an open file descriptor instead of a path.         */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangePasswordFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jbyteArray jOldPassword, jobjectArray jOldKeyfileData, jint oldPim,
        jbyteArray jNewPassword, jobjectArray jNewKeyfileData, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy, jint oldHashAlg)
{
    SecureBuffer<VC_MAX_PWD_LEN> oldPwdBuf;
    int oldPwdLen = get_password_bytes(env, jOldPassword, oldPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> newPwdBuf;
    int newPwdLen = get_password_bytes(env, jNewPassword, newPwdBuf);

    if (newPwdLen == 0) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0,
                              oldPwdBuf.data(), oldPwdLen, jOldKeyfileData, oldPim,
                              newPwdBuf.data(), newPwdLen, jNewKeyfileData,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy, oldHashAlg);
}


/* ─── Change-keyfile core ───────────────────────────────────────────── */
/* Re-encrypts the container header with a new keyfile set (password unchanged).
   extraEntropy: user-collected touch bytes XOR'd into the new salt.
   Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_change_keyfile(
        JNIEnv *env, int fdIn, const BlockBackend *beIn, uint64_t sizeIn,
        const uint8_t *pwd, int pwdLen, jobjectArray jOldKeyfileData, jint pim,
        jobjectArray jNewKeyfileData, jint newHashAlg,
        jbyteArray jExtraEntropy, jint oldHashAlg)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);
    const BlockBackend vol = beIn ? *beIn : fd_be(fd.get());
    /* Pinned after fd acquisition so every exit path below releases it
     * (ScopedArrayPin's destructor does this automatically now). */
    ScopedArrayPin entropyPin(env, jExtraEntropy);

    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = pwdLen;
    memcpy(oldEffPwd.data(), pwd, (size_t)oldEffPwdLen);
    if (!apply_keyfile_buffers(env, jOldKeyfileData, oldEffPwd.data(), &oldEffPwdLen)) return ERR_RAND;

    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = pwdLen;
    memcpy(newEffPwd.data(), pwd, (size_t)newEffPwdLen);
    if (!apply_keyfile_buffers(env, jNewKeyfileData, newEffPwd.data(), &newEffPwdLen)) return ERR_RAND;

    off_t fileSzOff = beIn ? (off_t)sizeIn : lseek(fd.get(), 0, SEEK_END);
    if (fileSzOff < 0) {
        return ERR_FILE;
    }
    uint64_t fileSize = (uint64_t)fileSzOff;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    uint64_t headerOff = 0, backupHeaderOff = fileSize - VC_BACKUP_AREA_SIZE;
    int rc = auth_with_legacy_pool_retry(
        env, jOldKeyfileData, pwd, pwdLen, oldEffPwd.data(), &oldEffPwdLen, "chkeyfile",
        /*usedLegacyPool=*/nullptr,   /* writes use newEffPwd, always standard */
        [&](const uint8_t *eff, int effLen) {
            return auth_volume_or_hidden(vol, fileSize, eff, effLen, (int)pim,
                                         (int)oldHashAlg, masterKey.data(), &mkLen,
                                         &dataSz, &dataOff, &algId, &hashId, &hiddenVolSize,
                                         &headerOff, &backupHeaderOff);
        });
    /* Deliberate early wipe: oldEffPwd is never needed again regardless of
     * outcome. The new credential above was built with the correct pool, so a
     * volume opened via the legacy retry is rewritten correct by this change. */
    oldEffPwd.wipe();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    int newHash = (int)newHashAlg;
    if (newHash < 0 || newHash >= VC_NUM_PRFS) newHash = hashId;

    int r1 = wipe_and_rewrite_header(vol, headerOff,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)pim, hiddenVolSize, /*wipePassCount=*/3,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());
    if (r1 != 0) {
        return ERR_FILE;
    }

    int r2 = wipe_and_rewrite_header(vol, backupHeaderOff,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)pim, hiddenVolSize, /*wipePassCount=*/3,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());

    return r2 == 0 ? ERR_OK : ERR_FILE;
    /* masterKey/newEffPwd wiped, entropyPin released, fd closed — all by
     * their destructors, here and on every path above. */
}

/* ─── JNI: nativeChangeKeyfile ──────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangeKeyfile(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath,
        jbyteArray jPassword, jobjectArray jOldKeyfileData, jint pim,
        jobjectArray jNewKeyfileData, jint newHashAlg,
        jbyteArray jExtraEntropy, jint oldHashAlg)
{
    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    if (path.empty()) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0, pwdBuf.data(), pwdLen, jOldKeyfileData, pim,
                             jNewKeyfileData, newHashAlg, jExtraEntropy, oldHashAlg);
}

/* ─── JNI: nativeChangeKeyfileFd ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangeKeyfileFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jbyteArray jPassword, jobjectArray jOldKeyfileData, jint pim,
        jobjectArray jNewKeyfileData, jint newHashAlg,
        jbyteArray jExtraEntropy, jint oldHashAlg)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, /*beIn=*/nullptr, /*sizeIn=*/0, pwdBuf.data(), pwdLen, jOldKeyfileData, pim,
                             jNewKeyfileData, newHashAlg, jExtraEntropy, oldHashAlg);
}

/* ─── Backup-header core ────────────────────────────────────────────── */
/* Authenticates the volume via volFd (closed after the header read), then
   writes a VeraCrypt-layout backup file: 128 KB of random data with the
   re-encrypted header (fresh salt) at offset 0. The output is acquired only
   AFTER successful authentication so a wrong password never creates or
   truncates the destination — outputPath is non-null for the path wrapper,
   otherwise safOutputFd is dup()ed. */
static jint do_backup_volume_header(
        JNIEnv *env, int volFdIn, const BlockBackend *beIn,
        const uint8_t *pwd, int pwdLen, jobjectArray jKeyfileData, jint pim,
        const char *outputPath, int safOutputFd)
{
    UniqueFd volFd(volFdIn);
    const BlockBackend vol = beIn ? *beIn : fd_be(volFd.get());

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen)) return ERR_RAND;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    bool usedLegacyPool = false;
    int rc = auth_with_legacy_pool_retry(
        env, jKeyfileData, pwd, pwdLen, effPwd.data(), &effPwdLen, "backup", &usedLegacyPool,
        [&](const uint8_t *eff, int effLen) {
            return read_vc_header(vol, 0, (const char*)eff, effLen,
                                  masterKey.data(), &mkLen, &dataSz, &dataOff,
                                  &algId, &hashId, (int)pim, &hiddenVolSize);
        });
    /* Explicit early close: volFd is never needed again regardless of
     * outcome (matches the original's unconditional close(volFd) here,
     * rather than deferring it to function exit). */
    volFd.reset();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    /* The backup below is re-encrypted with effPwd. Writing it with the legacy
     * pool would bake the #112 bug into the backup file too, so the correct
     * credential is rebuilt first: the backup opens with the same password and
     * keyfiles, and restoring it heals the volume. */
    if (usedLegacyPool &&
        !rebuild_standard_pool_password(env, jKeyfileData, pwd, pwdLen, effPwd.data(), &effPwdLen))
        return ERR_RAND;

    UniqueFd outFd(outputPath ? open(outputPath, O_WRONLY | O_CREAT | O_TRUNC, 0600)
                              : dup(safOutputFd));
    if (!outFd.ok()) {
        return ERR_FILE;
    }
    if (!outputPath) {
        ftruncate(outFd.get(), 0);
        lseek(outFd.get(), 0, SEEK_SET);
    }

    // Fill 128 KB with random data (VeraCrypt backup file layout: two 64 KB slots)
    // chunk holds random filler written verbatim to disk, not secret material,
    // so it keeps its existing plain buffer + explicit wipe (not a SecureBuffer).
    uint8_t chunk[4096];
    bool prefixOk = true;
    for (int i = 0; i < 32 && prefixOk; i++) {
        if (!read_urandom(chunk, sizeof(chunk)) ||
            !write_all_at(outFd.get(), chunk, sizeof(chunk), (long long)i * (long long)sizeof(chunk)))
            prefixOk = false;
    }
    secure_memset(chunk, 0, sizeof(chunk));
    if (!prefixOk) {
        return ERR_FILE;
    }

    // Write re-encrypted header at offset 0 with a fresh random salt
    int r = wipe_and_rewrite_header(fd_be(outFd.get()), 0,
                                    dataSz, dataOff, masterKey.data(), algId, hashId,
                                    (const char*)effPwd.data(), effPwdLen,
                                    (int)pim, hiddenVolSize, /*wipePassCount=*/1,
                                    nullptr, 0);
    return r == 0 ? ERR_OK : ERR_FILE;
    /* effPwd/masterKey wiped, outFd closed — all by their destructors. */
}

/* ─── JNI: nativeBackupVolumeHeader ─────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeBackupVolumeHeader(
        JNIEnv *env, jobject /*thiz*/,
        jstring jVolumePath, jbyteArray jPassword,
        jobjectArray jKeyfileData, jint pim, jstring jOutputPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string outputPath = jstring_to_string(env, jOutputPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    int fd = open(volumePath.c_str(), O_RDONLY);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, /*beIn=*/nullptr, pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                   outputPath.c_str(), -1);
}

/* ─── JNI: nativeBackupVolumeHeaderFd ──────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeBackupVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jbyteArray jPassword,
        jobjectArray jKeyfileData, jint pim, jint safOutputFd)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    int fd = dup((int)safVolumeFd);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, /*beIn=*/nullptr, pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                   nullptr, (int)safOutputFd);
}

/* ─── Restore-header core ───────────────────────────────────────────── */
/* Authenticates the source header (embedded backup at the file tail, or an
   external backup file), then rewrites the primary header — and, when
   restoring from external, the embedded backup too. Takes ownership of
   volFd. backupPath is non-null for the path wrapper; the fd wrapper passes
   safBackupFd instead. Both are ignored when fromExternal is false. */
/*
 * Whether this descriptor names a file some container is mounted from right now.
 *
 * Compared by device and inode rather than by path, so a second path to the same
 * file - a different mount of the same storage, a symlink, a relative name - cannot
 * slip past. A descriptor that will not stat is reported as *not* mounted: this
 * guards a recovery operation, and refusing one because a check could not be made
 * is worse than not making it.
 */
static bool file_is_mounted_now(int fd) {
    struct stat want;
    if (fd < 0 || fstat(fd, &want) != 0) return false;

    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    for (const auto &entry : g_ctxMap) {
        const ContainerCtx *ctx = entry.second;
        if (!ctx || ctx->fd < 0) continue;
        struct stat have;
        if (fstat(ctx->fd, &have) != 0) continue;
        if (have.st_dev == want.st_dev && have.st_ino == want.st_ino) return true;
    }
    return false;
}

static jint do_restore_volume_header(
        JNIEnv *env, int volFdIn, const BlockBackend *beIn, uint64_t sizeIn,
        const uint8_t *pwd, int pwdLen, jobjectArray jKeyfileData, jint pim,
        jboolean fromExternal, const char *backupPath, int safBackupFd)
{
    /* Unlike do_backup_volume_header, volFd is needed for the whole function
     * (the restore writes back into it), so it's owned for the whole scope. */
    UniqueFd volFd(volFdIn);

    /*
     * Not while it is mounted.
     *
     * A restored header can carry a different master key. The mounted drive keeps
     * the keys it derived at mount time, so everything written after the restore is
     * encrypted with the old ones while the header on disk describes the new - and
     * that only becomes visible at the next mount, as data that will not decrypt.
     * Nothing announces it in between.
     *
     * RestoreHeaderViewModel already refuses this and says "Unmount the vault
     * before restoring its header", so in the app this is the second layer rather
     * than the first. It is here because the first one is a single `if` in one
     * ViewModel, and the cost of it being bypassed is silent loss.
     *
     * Only the descriptor form is checked. A USB-hosted volume arrives as a
     * transport with no descriptor to compare, so for that one the ViewModel
     * remains the only guard - written down rather than left to be discovered.
     */
    if (!beIn && file_is_mounted_now(volFd.get())) {
        LOGE("restore refused: that volume is mounted right now");
        return ERR_BUSY;
    }
    const BlockBackend vol = beIn ? *beIn : fd_be(volFd.get());

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen)) return ERR_RAND;

    off_t fileSzOff = beIn ? (off_t)sizeIn : lseek(volFd.get(), 0, SEEK_END);
    if (fileSzOff < 0) {
        return ERR_FILE;
    }
    uint64_t fileSize = (uint64_t)fileSzOff;

    /* srcFdOwned is only actually opened when fromExternal is true; its
     * reset() below is then equivalent to the original's `if (closeSrcFd)
     * close(srcFd);` (a no-op when unset, since UniqueFd's default fd_ is -1). */
    UniqueFd srcFdOwned;
    int srcFd;
    uint64_t srcOffset;
    if ((bool)fromExternal) {
        srcFdOwned.reset(backupPath ? open(backupPath, O_RDONLY) : dup(safBackupFd));
        if (!srcFdOwned.ok()) {
            return ERR_FILE;
        }
        srcFd = srcFdOwned.get();
        srcOffset = 0;
    } else {
        srcFd     = volFd.get();
        srcOffset = fileSize - VC_BACKUP_AREA_SIZE;
    }

    /* The header comes either from a real backup file or from the volume's own backup
     * area. Only the first is a descriptor - for a volume that is not a file there is
     * nothing to wrap, so the second has to go through the volume's backend. */
    const BlockBackend srcBe = (bool)fromExternal ? fd_be(srcFd) : vol;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    bool usedLegacyPool = false;
    int rc = auth_with_legacy_pool_retry(
        env, jKeyfileData, pwd, pwdLen, effPwd.data(), &effPwdLen, "restore", &usedLegacyPool,
        [&](const uint8_t *eff, int effLen) {
            return read_vc_header(srcBe, srcOffset, (const char*)eff, effLen,
                                  masterKey.data(), &mkLen, &dataSz, &dataOff,
                                  &algId, &hashId, (int)pim, &hiddenVolSize);
        });
    /* Explicit early close, matching the original's `if (closeSrcFd)
     * close(srcFd);` right here rather than deferring to function exit. */
    srcFdOwned.reset();

    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    /* The header is about to be written back to the volume with effPwd. A
     * legacy-pool backup is readable, but what lands on the volume should be
     * the corrected format, so restoring an old backup heals it. */
    if (usedLegacyPool &&
        !rebuild_standard_pool_password(env, jKeyfileData, pwd, pwdLen, effPwd.data(), &effPwdLen))
        return ERR_RAND;

    // Restore primary header at offset 0
    int r1 = wipe_and_rewrite_header(vol, 0,
                                     dataSz, dataOff, masterKey.data(), algId, hashId,
                                     (const char*)effPwd.data(), effPwdLen,
                                     (int)pim, hiddenVolSize, /*wipePassCount=*/3,
                                     nullptr, 0);
    if (r1 != 0) {
        return ERR_FILE;
    }

    // When restoring from external, also update the embedded backup
    int r2 = 0;
    if ((bool)fromExternal) {
        uint64_t backupAreaOff = fileSize - VC_BACKUP_AREA_SIZE;
        r2 = wipe_and_rewrite_header(vol, backupAreaOff,
                                     dataSz, dataOff, masterKey.data(), algId, hashId,
                                     (const char*)effPwd.data(), effPwdLen,
                                     (int)pim, hiddenVolSize, /*wipePassCount=*/3,
                                     nullptr, 0);
    }

    return r2 == 0 ? ERR_OK : ERR_FILE;
    /* effPwd/masterKey wiped, volFd closed — all by their destructors. */
}

/* ─── JNI: nativeRestoreVolumeHeader ───────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRestoreVolumeHeader(
        JNIEnv *env, jobject /*thiz*/,
        jstring jVolumePath, jbyteArray jPassword,
        jobjectArray jKeyfileData, jint pim,
        jboolean fromExternal, jstring jBackupPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string backupPath = jstring_to_string(env, jBackupPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    int volFd = open(volumePath.c_str(), O_RDWR);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, /*beIn=*/nullptr, /*sizeIn=*/0, pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                    fromExternal, backupPath.c_str(), -1);
}

/* ─── JNI: nativeRestoreVolumeHeaderFd ─────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRestoreVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jbyteArray jPassword,
        jobjectArray jKeyfileData, jint pim,
        jboolean fromExternal, jint safBackupFd)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    int volFd = dup((int)safVolumeFd);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, /*beIn=*/nullptr, /*sizeIn=*/0, pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                    fromExternal, nullptr, (int)safBackupFd);
}


/* ─── JNI: nativeGenerateKeyfileFd ──────────────────────────────────── */
/*
 * Writes sizeBytes of CSPRNG output to safFd — the keyfile generator behind
 * VeraCrypt's Tools > Keyfile Generator (Main/Forms/KeyfileGeneratorDialog.cpp).
 *
 * Random source is the same one every salt and master key goes through:
 * /dev/urandom, XOR-folded with the caller's touch-collected entropy (Random
 * Pool Enrichment). VeraCrypt seeds its own pool from mouse motion; the fold
 * is the equivalent step, and since XOR of a uniform urandom stream with any
 * independent stream stays uniform, low-quality touch data can only add.
 *
 * The generated bytes ARE key material, so unlike the backup-header filler in
 * do_backup_volume_header they live in a SecureBuffer and are wiped per chunk.
 * Writing here rather than in Kotlin also keeps them out of the JVM heap,
 * where a ByteArray cannot be reliably zeroed once the GC has copied it.
 */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGenerateKeyfileFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jint sizeBytes, jbyteArray jExtraEntropy)
{
    if (sizeBytes < VC_KEYFILE_MIN_SIZE || sizeBytes > VC_KEYFILE_MAX_SIZE) {
        LOGE("[keyfile] size %d out of range [%d, %d]",
             (int)sizeBytes, VC_KEYFILE_MIN_SIZE, VC_KEYFILE_MAX_SIZE);
        return ERR_UNSUPPORTED;
    }

    /* Copy (never pin) caller entropy — same contract as the create path. */
    SecureVector entropy;
    if (jExtraEntropy) {
        jsize elen = env->GetArrayLength(jExtraEntropy);
        if (elen > 0) {
            entropy.resize((size_t)elen);
            env->GetByteArrayRegion(jExtraEntropy, 0, elen, (jbyte*)entropy.data());
        }
    }

    UniqueFd fd(dup((int)safFd));
    if (!fd.ok()) return ERR_FILE;

    /* If the user picked an existing document, it may already hold a longer
       file. Truncating up front is only an attempt — not every SAF provider
       implements ftruncate — so the length is verified after the write loop
       instead, where it can actually be enforced. */
    if (ftruncate(fd.get(), 0) != 0)
        LOGE("[keyfile] upfront ftruncate failed (errno=%d: %s) — verifying length after write",
             errno, strerror(errno));

    SecureBuffer<4096> chunk;
    size_t remaining = (size_t)sizeBytes;
    long long off    = 0;

    while (remaining > 0) {
        size_t n = remaining < chunk.size() ? remaining : chunk.size();
        if (!read_urandom(chunk.data(), n)) {
            LOGE("[keyfile] /dev/urandom failed — aborting");
            return ERR_RAND;
        }
        xor_fold_entropy(chunk.data(), n,
                         entropy.empty() ? nullptr : entropy.data(), entropy.size());
        if (!write_all_at(fd.get(), chunk.data(), n, off)) {
            LOGE("[keyfile] write failed at offset %lld (errno=%d: %s)", off, errno, strerror(errno));
            return ERR_FILE;
        }
        off       += (long long)n;
        remaining -= n;
    }

    /* Enforce the exact length. A trailing remnant from a pre-existing file
       would be silently folded into the keyfile pool by every VeraCrypt-
       compatible reader (the whole file is hashed, up to the 1 MB cap), so a
       keyfile longer than requested is a wrong keyfile, not a cosmetic issue.
       ftruncate is retried here because some providers only honour it once the
       fd has been written to; fstat is what actually decides. */
    ftruncate(fd.get(), (off_t)sizeBytes);

    struct stat st{};
    if (fstat(fd.get(), &st) != 0) {
        LOGE("[keyfile] fstat failed (errno=%d: %s)", errno, strerror(errno));
        return ERR_FILE;
    }
    if (st.st_size != (off_t)sizeBytes) {
        LOGE("[keyfile] length is %lld, expected %d — refusing to hand back a keyfile "
             "the destination could not be trimmed to",
             (long long)st.st_size, (int)sizeBytes);
        return ERR_FILE;
    }

    /* Best-effort durability: a keyfile the user cannot re-derive is worth an
       fsync, but some SAF providers reject it on their fds — the resolver
       still flushes on close, so a failure here is not fatal. */
    if (fsync(fd.get()) != 0)
        LOGE("[keyfile] fsync failed (errno=%d: %s) — relying on close-time flush", errno, strerror(errno));

    return ERR_OK;
    /* chunk wiped, fd closed, entropy wiped — all by their destructors. */
}

/* ─── JNI: whole-device USB variants (#95) ──────────────────────────── */
/*
 * The same four operations against a volume that occupies a USB device.
 *
 * Ownership differs from nativeOpenContainerUsb in the one way that matters: a mount
 * hands its backend to the drive and free_drive releases it later, whereas these
 * operations finish and leave nothing behind - so the backend is built here, used, and
 * released here on every path. The transport itself is not closed: Kotlin opened it and
 * Kotlin closes it, the same rule the rest of this file follows for descriptors.
 */

struct ScopedUsbBackend {
    BlockBackend be{};
    bool ok = false;
    ScopedUsbBackend(JNIEnv *env, jobject transport, bool readOnly) {
        ok = usb_backend_init(&be, env, transport, readOnly);
    }
    ~ScopedUsbBackend() { if (ok && be.close) be.close(be.self); }
    ScopedUsbBackend(const ScopedUsbBackend&) = delete;
    ScopedUsbBackend& operator=(const ScopedUsbBackend&) = delete;
};

/*
 * Formats a bare partition as FAT32.
 *
 * This is the ORDINARY partition of a partitioned USB drive (#131) - the one that keeps
 * the drive looking and behaving like a normal flash drive while the vault lives in the
 * partition beside it. Nothing here is encrypted, and nothing here is a vault: the drive
 * is claimed with a null master key, which is the only way to get a plaintext device and
 * is reachable from nowhere else. [transport] must already be a view of the partition,
 * so offset 0 here is the partition's first sector, not the drive's.
 */
extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeFormatFatPartition(
        JNIEnv *env, jobject /*thiz*/, jobject transport, jlong sizeBytes)
{
    if (!transport || sizeBytes < 1024 * 1024) return ERR_FILE;

    ScopedUsbBackend be(env, transport, /*readOnly=*/false);
    if (!be.ok) return ERR_FILE;

    FRESULT fr = FR_DISK_ERR;
    int pdrv;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        /* The drive borrows the backend: this function owns it through ScopedUsbBackend
         * and frees it on return, so free_drive must not close it as well. */
        BlockBackend forDrive = be.be;
        forDrive.close = nullptr;
        pdrv = alloc_drive(forDrive, /*dataOff=*/0,
                           (uint64_t)sizeBytes / VC_SECTOR_SIZE,
                           /*masterKey=*/nullptr, /*algId=*/0);
        if (pdrv < 0) return ERR_NO_SLOT;

        char drvPath[8];
        snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
        BYTE work[4096];
        /* FM_SFD: no partition table inside the partition. The cluster size follows the
         * same rule as a vault's - see vc_fat_cluster_size and issue #115, where leaving
         * it at 0 quietly produced FAT16 with a 512-entry root directory. */
        MKFS_PARM opts = { (BYTE)(FM_FAT | FM_FAT32 | FM_SFD), 2, 0, 0,
                           vc_fat_cluster_size((uint64_t)sizeBytes) };
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
        free_drive(pdrv);
    }
    if (fr != FR_OK) {
        LOGE("[format] f_mkfs on the plain partition failed (%d)", (int)fr);
        return ERR_FS;
    }
    if (be.be.sync) be.be.sync(be.be.self);
    return ERR_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangePasswordUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize,
        jbyteArray jOldPassword, jobjectArray jOldKeyfileData, jint oldPim,
        jbyteArray jNewPassword, jobjectArray jNewKeyfileData, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy, jint oldHashAlg)
{
    if (!transport || deviceSize <= 0) return ERR_FILE;
    SecureBuffer<VC_MAX_PWD_LEN> oldPwdBuf;
    int oldPwdLen = get_password_bytes(env, jOldPassword, oldPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> newPwdBuf;
    int newPwdLen = get_password_bytes(env, jNewPassword, newPwdBuf);
    if (newPwdLen == 0) return ERR_FILE;

    ScopedUsbBackend be(env, transport, /*readOnly=*/false);
    if (!be.ok) return ERR_FILE;

    return do_change_password(env, /*fdIn=*/-1, &be.be, (uint64_t)deviceSize,
                              oldPwdBuf.data(), oldPwdLen, jOldKeyfileData, oldPim,
                              newPwdBuf.data(), newPwdLen, jNewKeyfileData,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy, oldHashAlg);
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangeKeyfileUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize,
        jbyteArray jPassword, jobjectArray jOldKeyfileData, jint pim,
        jobjectArray jNewKeyfileData, jint newHashAlg, jbyteArray jExtraEntropy,
        jint oldHashAlg)
{
    if (!transport || deviceSize <= 0) return ERR_FILE;
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    ScopedUsbBackend be(env, transport, /*readOnly=*/false);
    if (!be.ok) return ERR_FILE;

    return do_change_keyfile(env, /*fdIn=*/-1, &be.be, (uint64_t)deviceSize,
                             pwdBuf.data(), pwdLen, jOldKeyfileData, pim,
                             jNewKeyfileData, newHashAlg, jExtraEntropy, oldHashAlg);
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeBackupVolumeHeaderUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize,
        jbyteArray jPassword, jobjectArray jKeyfileData, jint pim,
        jint safOutputFd)
{
    if (!transport || deviceSize <= 0) return ERR_FILE;
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    /* Read-only: a backup reads the volume and writes only to the output file. */
    ScopedUsbBackend be(env, transport, /*readOnly=*/true);
    if (!be.ok) return ERR_FILE;

    return do_backup_volume_header(env, /*volFdIn=*/-1, &be.be,
                                   pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                   /*outputPath=*/nullptr, (int)safOutputFd);
}

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRestoreVolumeHeaderUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize,
        jbyteArray jPassword, jobjectArray jKeyfileData, jint pim,
        jboolean fromExternal, jint safBackupFd)
{
    if (!transport || deviceSize <= 0) return ERR_FILE;
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);

    ScopedUsbBackend be(env, transport, /*readOnly=*/false);
    if (!be.ok) return ERR_FILE;

    return do_restore_volume_header(env, /*volFdIn=*/-1, &be.be, (uint64_t)deviceSize,
                                    pwdBuf.data(), pwdLen, jKeyfileData, pim,
                                    fromExternal, /*backupPath=*/nullptr, (int)safBackupFd);
}

/*
 * Creates a VeraCrypt volume occupying a whole USB device (#95).
 *
 * `sizeBytes` is the DATA size, as for every other create entry point; the caller derives
 * it from the device capacity minus the two header areas. `deviceSize` is passed as well
 * so the native side can refuse a volume that does not fit rather than writing past the
 * end of the drive.
 *
 * Everything on the device is destroyed - there is no partition table left, and Android
 * will offer to format the drive on every later connection. That warning belongs in the
 * UI; by the time this is called the decision has been made.
 */
extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateContainerUsb(
        JNIEnv *env, jobject /*thiz*/,
        jobject transport, jlong deviceSize, jlong sizeBytes,
        jbyteArray jPassword, jobjectArray jKeyfileData,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat, jbyteArray jEntropy,
        jobject progressListener, jint pim)
{
    if (!transport || deviceSize <= 0 || sizeBytes <= 0) return ERR_FILE;

    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    if (pwdLen == 0) return ERR_FILE;

    std::vector<uint8_t> entropy;
    if (jEntropy) {
        jsize n = env->GetArrayLength(jEntropy);
        entropy.resize((size_t)n);
        if (n > 0) env->GetByteArrayRegion(jEntropy, 0, n, reinterpret_cast<jbyte*>(entropy.data()));
    }

    ScopedUsbBackend be(env, transport, /*readOnly=*/false);
    if (!be.ok) return ERR_FILE;

    return do_create_container(env, /*fdIn=*/-1, &be.be, (uint64_t)deviceSize,
                               /*unlinkPathOnFail=*/nullptr, "usb/create",
                               sizeBytes, pwdBuf.data(), pwdLen, jKeyfileData,
                               algorithm, hashAlg, filesystem, quickFormat,
                               progressListener, pim,
                               entropy.empty() ? nullptr : entropy.data(), entropy.size());
}
