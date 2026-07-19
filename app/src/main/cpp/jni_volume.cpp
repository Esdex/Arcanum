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

    jclass localFileInfo = env->FindClass("zip/arcanum/crypto/NativeFileInfo");
    if (localFileInfo) {
        g_jniCache.fileInfoCls = (jclass)env->NewGlobalRef(localFileInfo);
        g_jniCache.fileInfoCtor = env->GetMethodID(g_jniCache.fileInfoCls, "<init>",
                                       "(Ljava/lang/String;Ljava/lang/String;JZJ)V");
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

static std::vector<std::string> jstringArray_to_vector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> result;
    if (!arr) return result;
    jsize count = env->GetArrayLength(arr);
    for (jsize i = 0; i < count; i++) {
        auto jstr = (jstring)env->GetObjectArrayElement(arr, i);
        if (jstr) {
            result.push_back(jstring_to_string(env, jstr));
            env->DeleteLocalRef(jstr);
        }
    }
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
 * The create fill loops, preallocate_fd's zero-fill fallback, and
 * fill_range_xts previously called report_progress() every 64/128 KB chunk —
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
static jint do_create_container(
        JNIEnv *env, int fdIn, const char *unlinkPathOnFail, const char *logTag,
        jlong sizeBytes, const uint8_t *pwd, int pwdLen,
        const std::vector<std::string> &keyfilePaths,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat, jobject progressListener, jint pim,
        const uint8_t *entropy, size_t entropyLen)
{
    /* fd is always closed by this function (never stored beyond it), on
     * every path — success or failure — so a single UniqueFd covers the
     * whole function; no release() site is needed here (contrast
     * do_open_container, where a successful mount keeps the fd alive). */
    UniqueFd fd(fdIn);

    /* fail_cleanup() only performs the semantic action (unlink the freshly
     * created file / truncate the SAF file back to 0) — it does NOT close
     * fd anymore, since UniqueFd is the single owner of that close(). */
    auto fail_cleanup = [&]() {
        if (unlinkPathOnFail) unlink(unlinkPathOnFail);
        else                  ftruncate(fd.get(), 0);
    };

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen)) return ERR_RAND;
    const int pbkdf2PwdLen = effPwdLen;

    int algId = (int)algorithm;
    int n     = ALGORITHMS[algId].n;

    uint64_t dataSize = (uint64_t)sizeBytes;
    uint64_t fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE;

    /* Resolve progress callback method ID once — reused across all chunks */
    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    /* Allocation phase — see the mode note above. */
    float allocWeight = 0.f, fillEnd = 1.f;
    if (unlinkPathOnFail) {
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

    if (write_vc_header(fd.get(), 0, dataSize, VC_DATA_OFFSET,
                        masterKey.data(), algId, (int)hashAlg,
                        (const char*)effPwd.data(), pbkdf2PwdLen, (int)pim,
                        /*hiddenVolSize=*/0, primarySaltPtr) != 0) {
        LOGE("[%s] Primary header write failed", logTag);
        fail_cleanup(); return ERR_FILE;
    }

    /* Write backup header at end of file */
    uint64_t backupOff = fileSize - VC_BACKUP_AREA_SIZE;
    if (write_vc_header(fd.get(), backupOff, dataSize, VC_DATA_OFFSET,
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
                if (!write_all_at(fd.get(), rnd, sz, (long long)offset)) { write_ok = false; break; }
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
    char drvPath[8];
    BYTE  work[4096];
    BYTE  fmtFlag = (filesystem == 1) ? (FM_EXFAT|FM_SFD) : ((FM_FAT|FM_FAT32)|FM_SFD);
    BYTE  nFat    = (filesystem == 1) ? 1 : 2;
    MKFS_PARM opts = { fmtFlag, nFat, 0, 0, 0 };
    FRESULT fr = FR_DISK_ERR;
    int pdrv;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        pdrv = alloc_drive(fd.get(), VC_DATA_OFFSET, dataSize / VC_SECTOR_SIZE, masterKey.data(), algId);
        /* Deliberate early wipe: alloc_drive already copied whatever it
         * needed into the per-drive key schedule (on success) or nothing at
         * all (on failure) — masterKey's plaintext is never read again. */
        masterKey.wipe();
        if (pdrv < 0) {
            LOGE("[%s] No drive slot", logTag);
            fail_cleanup(); return ERR_NO_SLOT;
        }
        snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
        free_drive(pdrv);
    }

    if (fr != FR_OK) {
        LOGE("[%s] f_mkfs failed: %d", logTag, (int)fr);
        fail_cleanup(); return ERR_FS;
    }

    fsync(fd.get());
    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)dataSize);
    return ERR_OK;
    /* fd closed by UniqueFd's destructor here, on this and every path above. */
}

/* ─── JNI: nativeCreateContainer ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jlong sizeBytes,
        jbyteArray jPassword, jobjectArray jKeyfilePaths,
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
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);
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

    return do_create_container(env, fd, path.c_str(), "create",
                               sizeBytes, pwdBuf.data(), pwdLen, keyfilePaths,
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
        jbyteArray jPassword, jobjectArray jKeyfilePaths,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray jEntropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);
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

    return do_create_container(env, fd, nullptr, "fd/create",
                               sizeBytes, pwdBuf.data(), pwdLen, keyfilePaths,
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
static jlong do_open_container(
        JNIEnv *env, int fdIn, const char *logTag,
        const uint8_t *pwd, int pwdLen, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        const uint8_t *hiddenPwd, int hiddenPwdLen, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener, jboolean readOnly)
{
    /* Owns fd on every failure path (closed by the destructor). On success
     * the fd is handed to the registry via ContainerCtx — release() right
     * before that handoff, at the single spot marked below. */
    UniqueFd fd(fdIn);

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

    struct stat st{};
    if (fstat(fd.get(), &st) != 0) {
        LOGE("[%s] fstat failed: errno=%d", logTag, errno);
        return (jlong)ERR_FILE;
    }
    uint64_t fileSize = (uint64_t)st.st_size;

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

    for (int ti = 0; ti < 2 && rc != ERR_OK; ti++) {
        if (tryOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
        uint64_t hvSz = 0;
        rc = read_vc_header(fd.get(), tryOffsets[ti], (const char*)effPwd.data(), effPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff, &algId, &hashId,
                            (int)pim, &hvSz, (int)algorithm, (int)hashAlgorithm, pMountCb);
        if (rc == ERR_OK) { authIsHidden = tryIsHidden[ti]; hiddenVolSize = hvSz; }
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
       to an adversary who forces the user to open the outer vault without protection. */
    uint64_t hiddenBoundary = 0;
    if (!authIsHidden && hidEffPwdLen > 0) {
        if (hiddenVolSize > 0) {
            hiddenBoundary = dataOff + dataSz - hiddenVolSize;
        } else {
            uint64_t hidOffsets[2] = { VC_HIDDEN_HEADER_OFFSET, fileSize - VC_HIDDEN_HEADER_OFFSET };
            SecureBuffer<192> hidMasterKey;
            int hidMkLen = 0, hidAlgId = 0, hidHashId = 0;
            uint64_t hidDataSz = 0, hidDataOff = 0, hidHvSz = 0;
            for (int ti = 0; ti < 2; ti++) {
                if (hidOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
                int hrc = read_vc_header(fd.get(), hidOffsets[ti], (const char*)hidEffPwd.data(), hidEffPwdLen,
                                         hidMasterKey.data(), &hidMkLen, &hidDataSz, &hidDataOff,
                                         &hidAlgId, &hidHashId, (int)protectHiddenPim, &hidHvSz, -1, -1);
                if (hrc == ERR_OK && hidDataSz > 0) {
                    hiddenBoundary = dataOff + dataSz - hidDataSz;
                    LOGE("[%s] protect-hidden: boundary set to 0x%llx from hidden header",
                         logTag, (unsigned long long)hiddenBoundary);
                    break;
                }
            }
            /* hidMasterKey wiped by its destructor here, at the end of this
             * inner scope — same timing as the manual wipe it replaces. */
        }
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
        pdrv = alloc_drive(fd.get(), dataOff, dataSz / VC_SECTOR_SIZE, masterKey.data(), algId, hashId,
                           authIsHidden, hiddenBoundary, iterCount, (bool)readOnly);
        /* Deliberate early wipe: alloc_drive already consumed masterKey
         * (on success) or nothing at all (on failure). */
        masterKey.wipe();
        if (pdrv < 0) return (jlong)ERR_NO_SLOT;
        gen = g_drives[pdrv].generation;

        /* ContainerCtx via unique_ptr so the f_mount-failure path below just
         * lets it go out of scope instead of a manual delete; release() only
         * on the success path, where ownership transfers to g_ctxMap. */
        std::unique_ptr<ContainerCtx> ctx(new ContainerCtx{ pdrv, {}, fd.get(), (bool)readOnly });
        snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
        fr = f_mount(&ctx->fatFs, drvPath, 1);
        if (fr != FR_OK) {
            LOGE("[%s] f_mount failed: %d", logTag, (int)fr);
            free_drive(pdrv);
            return (jlong)ERR_FS;
            /* ctx deleted by unique_ptr, fd closed by UniqueFd, both here. */
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
        jobject mountProgressListener, jboolean readOnly)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> hidPwdBuf;
    int hidPwdLen = get_password_bytes(env, jProtectHiddenPassword, hidPwdBuf);

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/open] dup failed: errno=%d", errno); return (jlong)ERR_FILE; }

    return do_open_container(env, fd, "fd/open",
                             pwdBuf.data(), pwdLen, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hidPwdBuf.data(), hidPwdLen, jProtectHiddenKeyfileData, protectHiddenPim,
                             mountProgressListener, readOnly);
}

/* ─── JNI: nativeOpenContainer ──────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jbyteArray jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jbyteArray jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener, jboolean readOnly)
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

    return do_open_container(env, fd, "open",
                             pwdBuf.data(), pwdLen, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hidPwdBuf.data(), hidPwdLen, jProtectHiddenKeyfileData, protectHiddenPim,
                             mountProgressListener, readOnly);
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

        char drvPath[8];
        snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
        f_unmount(drvPath);
        free_drive(pdrv);
        g_ctxMap.erase(it);
    }
    UniqueFd fd(ctx->fd);
    fsync(fd.get());
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
 * backward compatibility with containers created before this fix. */

/* Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_create_hidden_volume(
        JNIEnv *env, int fdIn, const char *logTag,
        jlong hiddenSizeBytes,
        const uint8_t *outerPwd, int outerPwdLen, const std::vector<std::string> &outerKeyfilePaths, jint outerPim,
        const uint8_t *hiddenPwd, int hiddenPwdLen, const std::vector<std::string> &hiddenKeyfilePaths, jint hiddenPim,
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
    if (!apply_keyfiles_to_password(outerKeyfilePaths, outerEffPwd.data(), &outerEffPwdLen)) return ERR_RAND;

    /* ── Hidden effective password ── */
    SecureBuffer<VC_MAX_PWD_LEN> hiddenEffPwd;
    int hiddenEffPwdLen = hiddenPwdLen;
    memcpy(hiddenEffPwd.data(), hiddenPwd, (size_t)hiddenEffPwdLen);
    if (!apply_keyfiles_to_password(hiddenKeyfilePaths, hiddenEffPwd.data(), &hiddenEffPwdLen)) return ERR_RAND;

    /* ── Authenticate outer volume (primary header) ── */
    /* Outer headers are never rewritten (see deniability note above) — this
       call exists solely to verify the outer password before we touch the
       hidden-area headers. */
    SecureBuffer<192> outerMasterKey;
    int outerMkLen = 0, outerAlgId = 0, outerHashId = 0;
    uint64_t outerDataSz = 0, outerDataOff = 0;
    int rc = read_vc_header(fd.get(), 0,
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

    /* Primary hidden header at VC_HIDDEN_HEADER_OFFSET; field28 = 0 in hidden headers */
    if (write_vc_header(fd.get(), VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey.data(), hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd.data(), hiddenEffPwdLen,
                        (int)hiddenPim, 0, hiddenPrimarySaltPtr) != 0) {
        return ERR_FILE;
    }
    /* Backup hidden header at fileSize - VC_HIDDEN_HEADER_OFFSET */
    if (write_vc_header(fd.get(), fileSize - VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey.data(), hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd.data(), hiddenEffPwdLen,
                        (int)hiddenPim, 0, hiddenBackupSaltPtr) != 0) {
        return ERR_FILE;
    }
    /* Deliberate early wipe: hiddenEffPwd/hiddenPrimarySalt/hiddenBackupSalt
     * are not needed again (hiddenMasterKey still is, for alloc_drive
     * below), and mkfs on the hidden area follows. */
    hiddenEffPwd.wipe();
    hiddenPrimarySalt.wipe();
    hiddenBackupSalt.wipe();

    /* ── Format hidden area as FAT32 ── */
    char drvPath[8];
    BYTE work[4096];
    MKFS_PARM opts = { (FM_FAT | FM_FAT32) | FM_SFD, 2, 0, 0, 0 };
    FRESULT fr = FR_DISK_ERR;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        int pdrv = alloc_drive(fd.get(), hiddenDataOff, hidSz / VC_SECTOR_SIZE,
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
        jbyteArray jOuterPassword, jobjectArray jOuterKeyfilePaths, jint outerPim,
        jbyteArray jHiddenPassword, jobjectArray jHiddenKeyfilePaths, jint hiddenPim,
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
    auto outerKeyfilePaths  = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths = jstringArray_to_vector(env, jHiddenKeyfilePaths);

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
                                   outerPwdBuf.data(), outerPwdLen, outerKeyfilePaths, outerPim,
                                   hiddenPwdBuf.data(), hiddenPwdLen, hiddenKeyfilePaths, hiddenPim,
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
        jbyteArray jOuterPassword, jobjectArray jOuterKeyfilePaths, jint outerPim,
        jbyteArray jHiddenPassword, jobjectArray jHiddenKeyfilePaths, jint hiddenPim,
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
    auto outerKeyfilePaths  = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths = jstringArray_to_vector(env, jHiddenKeyfilePaths);

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
                                   outerPwdBuf.data(), outerPwdLen, outerKeyfilePaths, outerPim,
                                   hiddenPwdBuf.data(), hiddenPwdLen, hiddenKeyfilePaths, hiddenPim,
                                   hiddenAlgorithm, hiddenHashAlg, progressListener,
                                   entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── Change-password core ──────────────────────────────────────────── */
/* Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_change_password(
        JNIEnv *env, int fdIn,
        const uint8_t *oldPwd, int oldPwdLen, const std::vector<std::string> &oldKeyfilePaths, jint oldPim,
        const uint8_t *newPwd, int newPwdLen, const std::vector<std::string> &newKeyfilePaths,
        jint newHashAlg, jint newPim, jint wipePassCount, jbyteArray jExtraEntropy)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);

    /* Build old effective password (password + keyfile pool) */
    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = oldPwdLen;
    memcpy(oldEffPwd.data(), oldPwd, (size_t)oldEffPwdLen);
    if (!apply_keyfiles_to_password(oldKeyfilePaths, oldEffPwd.data(), &oldEffPwdLen)) return ERR_RAND;

    /* Build new effective password */
    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = newPwdLen;
    memcpy(newEffPwd.data(), newPwd, (size_t)newEffPwdLen);
    if (!apply_keyfiles_to_password(newKeyfilePaths, newEffPwd.data(), &newEffPwdLen)) return ERR_RAND;

    off_t fileSzOff = lseek(fd.get(), 0, SEEK_END);
    if (fileSzOff < 0) {
        return ERR_FILE;
    }
    uint64_t fileSize = (uint64_t)fileSzOff;

    /* Authenticate primary header with old credentials */
    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0;
    uint64_t hiddenVolSize = 0;
    int rc = read_vc_header(fd.get(), 0,
                            (const char*)oldEffPwd.data(), oldEffPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff,
                            &algId, &hashId, (int)oldPim, &hiddenVolSize);
    /* Deliberate early wipe: oldEffPwd is never needed again regardless of
     * outcome. */
    oldEffPwd.wipe();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    int passes = (int)wipePassCount;
    if (passes < 1) passes = 1;
    int newHash = (int)newHashAlg;
    if (newHash < 0 || newHash > 4) newHash = hashId; /* keep old hash if invalid */

    uint64_t backupAreaOff = fileSize - VC_BACKUP_AREA_SIZE;

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
    int r1 = wipe_and_rewrite_header(fd.get(), 0,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)newPim, hiddenVolSize, passes,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());
    if (r1 != 0) {
        return ERR_FILE;
    }

    int r2 = wipe_and_rewrite_header(fd.get(), backupAreaOff,
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
        jbyteArray jOldPassword, jobjectArray jOldKeyfilePaths, jint oldPim,
        jbyteArray jNewPassword, jobjectArray jNewKeyfilePaths, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy)
{
    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> oldPwdBuf;
    int oldPwdLen = get_password_bytes(env, jOldPassword, oldPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> newPwdBuf;
    int newPwdLen = get_password_bytes(env, jNewPassword, newPwdBuf);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (path.empty() || newPwdLen == 0) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd,
                              oldPwdBuf.data(), oldPwdLen, oldKeyfilePaths, oldPim,
                              newPwdBuf.data(), newPwdLen, newKeyfilePaths,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy);
}

/* ─── JNI: nativeChangePasswordFd ───────────────────────────────────── */
/* SAF variant: takes an open file descriptor instead of a path.         */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangePasswordFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jbyteArray jOldPassword, jobjectArray jOldKeyfilePaths, jint oldPim,
        jbyteArray jNewPassword, jobjectArray jNewKeyfilePaths, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy)
{
    SecureBuffer<VC_MAX_PWD_LEN> oldPwdBuf;
    int oldPwdLen = get_password_bytes(env, jOldPassword, oldPwdBuf);
    SecureBuffer<VC_MAX_PWD_LEN> newPwdBuf;
    int newPwdLen = get_password_bytes(env, jNewPassword, newPwdBuf);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (newPwdLen == 0) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd,
                              oldPwdBuf.data(), oldPwdLen, oldKeyfilePaths, oldPim,
                              newPwdBuf.data(), newPwdLen, newKeyfilePaths,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy);
}


/* ─── Change-keyfile core ───────────────────────────────────────────── */
/* Re-encrypts the container header with a new keyfile set (password unchanged).
   extraEntropy: user-collected touch bytes XOR'd into the new salt.
   Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_change_keyfile(
        JNIEnv *env, int fdIn,
        const uint8_t *pwd, int pwdLen, const std::vector<std::string> &oldKeyfilePaths, jint pim,
        const std::vector<std::string> &newKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);
    /* Pinned after fd acquisition so every exit path below releases it
     * (ScopedArrayPin's destructor does this automatically now). */
    ScopedArrayPin entropyPin(env, jExtraEntropy);

    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = pwdLen;
    memcpy(oldEffPwd.data(), pwd, (size_t)oldEffPwdLen);
    if (!apply_keyfiles_to_password(oldKeyfilePaths, oldEffPwd.data(), &oldEffPwdLen)) return ERR_RAND;

    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = pwdLen;
    memcpy(newEffPwd.data(), pwd, (size_t)newEffPwdLen);
    if (!apply_keyfiles_to_password(newKeyfilePaths, newEffPwd.data(), &newEffPwdLen)) return ERR_RAND;

    off_t fileSzOff = lseek(fd.get(), 0, SEEK_END);
    if (fileSzOff < 0) {
        return ERR_FILE;
    }
    uint64_t fileSize = (uint64_t)fileSzOff;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    int rc = read_vc_header(fd.get(), 0,
                            (const char*)oldEffPwd.data(), oldEffPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff,
                            &algId, &hashId, (int)pim, &hiddenVolSize);
    /* Deliberate early wipe: oldEffPwd is never needed again regardless of
     * outcome. */
    oldEffPwd.wipe();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    int newHash = (int)newHashAlg; if (newHash < 0 || newHash > 4) newHash = hashId;
    uint64_t backupAreaOff = fileSize - VC_BACKUP_AREA_SIZE;

    int r1 = wipe_and_rewrite_header(fd.get(), 0,
                                      dataSz, dataOff, masterKey.data(), algId, newHash,
                                      (const char*)newEffPwd.data(), newEffPwdLen,
                                      (int)pim, hiddenVolSize, /*wipePassCount=*/3,
                                      (const uint8_t*)entropyPin.data(), (size_t)entropyPin.len());
    if (r1 != 0) {
        return ERR_FILE;
    }

    int r2 = wipe_and_rewrite_header(fd.get(), backupAreaOff,
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
        jbyteArray jPassword, jobjectArray jOldKeyfilePaths, jint pim,
        jobjectArray jNewKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    std::string path = jstring_to_string(env, jPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (path.empty()) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, pwdBuf.data(), pwdLen, oldKeyfilePaths, pim,
                             newKeyfilePaths, newHashAlg, jExtraEntropy);
}

/* ─── JNI: nativeChangeKeyfileFd ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangeKeyfileFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jbyteArray jPassword, jobjectArray jOldKeyfilePaths, jint pim,
        jobjectArray jNewKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, pwdBuf.data(), pwdLen, oldKeyfilePaths, pim,
                             newKeyfilePaths, newHashAlg, jExtraEntropy);
}

/* ─── Backup-header core ────────────────────────────────────────────── */
/* Authenticates the volume via volFd (closed after the header read), then
   writes a VeraCrypt-layout backup file: 128 KB of random data with the
   re-encrypted header (fresh salt) at offset 0. The output is acquired only
   AFTER successful authentication so a wrong password never creates or
   truncates the destination — outputPath is non-null for the path wrapper,
   otherwise safOutputFd is dup()ed. */
static jint do_backup_volume_header(
        JNIEnv * /*env*/, int volFdIn,
        const uint8_t *pwd, int pwdLen, const std::vector<std::string> &keyfilePaths, jint pim,
        const char *outputPath, int safOutputFd)
{
    UniqueFd volFd(volFdIn);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen)) return ERR_RAND;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    int rc = read_vc_header(volFd.get(), 0, (const char*)effPwd.data(), effPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff,
                            &algId, &hashId, (int)pim, &hiddenVolSize);
    /* Explicit early close: volFd is never needed again regardless of
     * outcome (matches the original's unconditional close(volFd) here,
     * rather than deferring it to function exit). */
    volFd.reset();
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

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
    int r = wipe_and_rewrite_header(outFd.get(), 0,
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
        jobjectArray jKeyfilePaths, jint pim, jstring jOutputPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string outputPath = jstring_to_string(env, jOutputPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int fd = open(volumePath.c_str(), O_RDONLY);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, pwdBuf.data(), pwdLen, keyfilePaths, pim,
                                   outputPath.c_str(), -1);
}

/* ─── JNI: nativeBackupVolumeHeaderFd ──────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeBackupVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jbyteArray jPassword,
        jobjectArray jKeyfilePaths, jint pim, jint safOutputFd)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int fd = dup((int)safVolumeFd);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, pwdBuf.data(), pwdLen, keyfilePaths, pim,
                                   nullptr, (int)safOutputFd);
}

/* ─── Restore-header core ───────────────────────────────────────────── */
/* Authenticates the source header (embedded backup at the file tail, or an
   external backup file), then rewrites the primary header — and, when
   restoring from external, the embedded backup too. Takes ownership of
   volFd. backupPath is non-null for the path wrapper; the fd wrapper passes
   safBackupFd instead. Both are ignored when fromExternal is false. */
static jint do_restore_volume_header(
        JNIEnv * /*env*/, int volFdIn,
        const uint8_t *pwd, int pwdLen, const std::vector<std::string> &keyfilePaths, jint pim,
        jboolean fromExternal, const char *backupPath, int safBackupFd)
{
    /* Unlike do_backup_volume_header, volFd is needed for the whole function
     * (the restore writes back into it), so it's owned for the whole scope. */
    UniqueFd volFd(volFdIn);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = pwdLen;
    memcpy(effPwd.data(), pwd, (size_t)effPwdLen);
    if (!apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen)) return ERR_RAND;

    off_t fileSzOff = lseek(volFd.get(), 0, SEEK_END);
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

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    int rc = read_vc_header(srcFd, srcOffset, (const char*)effPwd.data(), effPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff,
                            &algId, &hashId, (int)pim, &hiddenVolSize);
    /* Explicit early close, matching the original's `if (closeSrcFd)
     * close(srcFd);` right here rather than deferring to function exit. */
    srcFdOwned.reset();

    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }

    // Restore primary header at offset 0
    int r1 = wipe_and_rewrite_header(volFd.get(), 0,
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
        r2 = wipe_and_rewrite_header(volFd.get(), backupAreaOff,
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
        jobjectArray jKeyfilePaths, jint pim,
        jboolean fromExternal, jstring jBackupPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string backupPath = jstring_to_string(env, jBackupPath);
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int volFd = open(volumePath.c_str(), O_RDWR);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, pwdBuf.data(), pwdLen, keyfilePaths, pim,
                                    fromExternal, backupPath.c_str(), -1);
}

/* ─── JNI: nativeRestoreVolumeHeaderFd ─────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRestoreVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jbyteArray jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jboolean fromExternal, jint safBackupFd)
{
    SecureBuffer<VC_MAX_PWD_LEN> pwdBuf;
    int pwdLen = get_password_bytes(env, jPassword, pwdBuf);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int volFd = dup((int)safVolumeFd);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, pwdBuf.data(), pwdLen, keyfilePaths, pim,
                                    fromExternal, nullptr, (int)safBackupFd);
}


/* ─── Expand volume helpers ──────────────────────────────────────────── */

/* Fill [startByte, endByte) on fd with AES-256-XTS encrypted zeros.
   Uses a one-shot temp 64-byte random key — produces plausible ciphertext
   that no one can decrypt. Reports progress every chunk. */
static int fill_range_xts(int fd, uint64_t startByte, uint64_t endByte,
                           JNIEnv *env, jobject listener, jmethodID progressMid,
                           float progressBase, float progressRange, uint64_t totalFillBytes) {
    if (startByte >= endByte) return ERR_OK;

    SecureBuffer<64> tempKey;
    if (!read_urandom(tempKey.data(), tempKey.size())) return ERR_RAND;

    aes_encrypt_ctx k1enc, k2enc;
    SecureWipe<aes_encrypt_ctx> _wipeK1(k1enc), _wipeK2(k2enc);
    aes_encrypt_key256(tempKey.data(),      &k1enc);
    aes_encrypt_key256(tempKey.data() + 32, &k2enc);
    /* Deliberate early wipe: the raw key is no longer needed once the two
     * key schedules are built, and the fill loop below can be slow on a
     * large expansion. */
    tempKey.wipe();

    const size_t SECTOR = 512;
    const size_t CHUNK  = 256 * SECTOR; /* 128 KiB */
    auto *buf = static_cast<uint8_t*>(malloc(CHUNK));
    if (!buf) {
        return ERR_NO_SPACE;   /* k1enc/k2enc wiped by SecureWipe's destructor */
    }

    uint64_t remaining = endByte - startByte;
    uint64_t offset    = startByte;
    uint64_t t0 = monotonic_ms();
    ProgressThrottle throttle;

    while (remaining > 0) {
        size_t sz = (remaining > CHUNK) ? CHUNK : (size_t)remaining;
        memset(buf, 0, sz);

        uint64_t firstSector = offset / SECTOR;
        size_t   nSectors    = sz / SECTOR;
        for (size_t i = 0; i < nSectors; i++) {
            UINT64_STRUCT dataUnit;
            dataUnit.Value = (TC_LARGEST_COMPILER_UINT)(firstSector + i);
            EncryptBufferXTS(buf + i * SECTOR, (TC_LARGEST_COMPILER_UINT)SECTOR,
                             &dataUnit, 0,
                             (uint8_t*)&k1enc, (uint8_t*)&k2enc, CIPHER_AES);
        }

        ssize_t wr = pwrite(fd, buf, sz, (off_t)offset);
        if (wr != (ssize_t)sz) {
            free(buf);
            return ERR_NO_SPACE;   /* k1enc/k2enc wiped by SecureWipe's destructor */
        }
        remaining -= sz;
        offset    += sz;

        uint64_t done     = offset - startByte;
        uint64_t elapsdMs = monotonic_ms() - t0;
        float    speed    = elapsdMs > 0 ? (float)(done >> 20) / ((float)elapsdMs / 1000.f) : 50.f;
        float    lFrac    = totalFillBytes > 0 ? (float)done / (float)totalFillBytes : 1.f;
        float    frac     = progressBase + lFrac * progressRange;
        if (throttle.should_report(remaining == 0))
            report_progress(env, listener, progressMid, frac, speed, (jlong)done);
    }

    free(buf);
    return ERR_OK;   /* k1enc/k2enc wiped by SecureWipe's destructor */
}

static int do_expand_volume(int fd, const uint8_t *effPwd, int effPwdLen, int pim,
                             uint64_t newFileSize,
                             JNIEnv *env, jobject progressListener) {
    off_t oldSzOff = lseek(fd, 0, SEEK_END);
    if (oldSzOff < 0) return ERR_FILE;
    uint64_t oldFileSize = (uint64_t)oldSzOff;

    if (newFileSize < oldFileSize + 65536ULL) return ERR_NO_SPACE;
    if (newFileSize % 512 != 0)               return ERR_UNSUPPORTED;

    SecureBuffer<192> masterKey;
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0, hiddenVolSize = 0;
    int rc = read_vc_header(fd, 0, (const char*)effPwd, effPwdLen,
                            masterKey.data(), &mkLen, &dataSz, &dataOff,
                            &algId, &hashId, pim, &hiddenVolSize);
    if (rc != ERR_OK) {
        return ERR_WRONG_PASSWORD;
    }
    /* Outer volume containing a hidden volume — cannot expand safely.
     *
     * LEGACY-ONLY GUARD: since the deniability fix (see nativeCreateHiddenVolume),
     * new containers never write hiddenVolSize into the outer header, so this
     * check only fires for containers created before that fix. There is no
     * reliable way to detect "this outer container has a hidden volume" for
     * new-format containers without the hidden-volume protection password —
     * the Room `ContainerEntity` has no persisted flag for it either (the only
     * related field, `Container.hasHiddenVolume` in the UI layer, is a
     * per-mount-session value derived from nativeHasHiddenVolume(), i.e. only
     * known when the user supplied a protection password for *that* mount —
     * not a durable property of the file). Expanding a new-format container
     * that does have a hidden volume will silently corrupt the hidden data if
     * the caller doesn't know to avoid it; the header-based check above is the
     * best available guard and is intentionally kept for legacy containers. */
    if (hiddenVolSize != 0) {
        return ERR_UNSUPPORTED;
    }

    if (ftruncate(fd, (off_t)newFileSize) != 0) {
        return ERR_NO_SPACE;
    }

    uint64_t oldBackupOff = oldFileSize - VC_BACKUP_AREA_SIZE;
    uint64_t newBackupOff = newFileSize - VC_BACKUP_AREA_SIZE;
    uint64_t fillSize     = newBackupOff - oldBackupOff;

    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    rc = fill_range_xts(fd, oldBackupOff, newBackupOff,
                        env, progressListener, progressMid,
                        0.0f, 0.9f, fillSize);
    if (rc != ERR_OK) {
        ftruncate(fd, (off_t)oldFileSize);
        return rc;
    }

    uint64_t newDataSz = newFileSize - VC_DATA_OFFSET - VC_BACKUP_AREA_SIZE;

    int r1 = wipe_and_rewrite_header(fd, newBackupOff,
                                     newDataSz, dataOff, masterKey.data(), algId, hashId,
                                     (const char*)effPwd, effPwdLen,
                                     pim, hiddenVolSize, /*wipePassCount=*/1);
    int r2 = wipe_and_rewrite_header(fd, 0,
                                     newDataSz, dataOff, masterKey.data(), algId, hashId,
                                     (const char*)effPwd, effPwdLen,
                                     pim, hiddenVolSize, /*wipePassCount=*/1);

    if (r1 != 0 || r2 != 0) return ERR_FILE;   /* masterKey wiped by its destructor */

    fdatasync(fd);
    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)fillSize);
    return ERR_OK;
}

/* ─── JNI: nativeExpandVolume ────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeExpandVolume(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jbyteArray jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jlong newSizeBytes, jobject progressListener)
{
    std::string path  = jstring_to_string(env, jPath);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);
    if (path.empty()) return ERR_FILE;

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = get_password_bytes(env, jPassword, effPwd);
    if (!apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen)) return ERR_RAND;

    UniqueFd fd(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (!fd.ok()) return ERR_FILE;

    return do_expand_volume(fd.get(), effPwd.data(), effPwdLen, (int)pim, (uint64_t)newSizeBytes, env, progressListener);
    /* effPwd wiped, fd closed — by their destructors. */
}

/* ─── JNI: nativeExpandVolumeFd ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeExpandVolumeFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jbyteArray jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jlong newSizeBytes, jobject progressListener)
{
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = get_password_bytes(env, jPassword, effPwd);
    if (!apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen)) return ERR_RAND;

    UniqueFd fd(dup((int)safFd));
    if (!fd.ok()) return ERR_FILE;

    return do_expand_volume(fd.get(), effPwd.data(), effPwdLen, (int)pim, (uint64_t)newSizeBytes, env, progressListener);
    /* effPwd wiped, fd closed — by their destructors. */
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
