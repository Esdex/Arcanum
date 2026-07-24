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

#pragma once

/*
 * Internal shared header for the native module split (stage: JNI file split).
 * Everything here crosses translation-unit boundaries between crypto_core.cpp,
 * kdf.cpp, vc_header.cpp, jni_volume.cpp and jni_files.cpp — constants, the
 * RAII helpers (templates/trivial inlines only, no logic), and declarations
 * for formerly-`static` functions that a sibling .cpp now needs to call.
 *
 * Function DEFINITIONS with real logic live in exactly one .cpp; only the
 * declaration lives here. See CLAUDE.md / the split commit message for the
 * per-file layout.
 */

#include "arcanum_errors.h"
#include "arcanum_impl.h"

#include <jni.h>
#include <cstdint>
#include <cstddef>
#include <mutex>
#include <string>
#include <unistd.h>       /* close() — used by UniqueFd::reset() below */
#include <unordered_map>
#include <vector>
#include <android/log.h>

extern "C" {
#include "fatfs/ff.h"
}

#define LOG_TAG "ArcanumNative"
#ifdef NDEBUG
#  define LOGE(...) ((void)0)
#  define LOGI(...) ((void)0)
#else
#  define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#  define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#endif

/* ─── VeraCrypt volume constants ────────────────────────────────────── */

#define VC_HEADER_VERSION        0x0005
#define VC_MIN_REQUIRED_VERSION  0x011F
#define VC_HEADER_SIZE           512
#define VC_DATA_OFFSET           131072ULL
#define VC_BACKUP_AREA_SIZE      131072ULL
#define VC_HIDDEN_HEADER_OFFSET  65536ULL   /* hidden primary header offset within first VC_BACKUP_AREA_SIZE block */
#define VC_HEADER_SALT_SIZE      64
#define VC_HEADER_BODY_OFFSET    64
#define VC_HEADER_BODY_SIZE      448
#define VC_MASTER_KEY_OFFSET     192

/* VeraCrypt MAX_PASSWORD = 128 for non-boot builds (see veracrypt/Common/Password.h).
   Keyfile pool (64 bytes, see vc_header.cpp) applies to Text[0..63]; bytes beyond
   pwd_len remain zero. PBKDF2 always receives exactly max(original_len, 64) bytes
   when a keyfile is used. Used well beyond keyfile handling (every password buffer
   in the JNI layer is a SecureBuffer<VC_MAX_PWD_LEN>), so it lives here rather than
   with the keyfile-pool-only constants in vc_header.cpp. */
#define VC_MAX_PWD_LEN        128

/* Keyfile generator size bounds, matching VeraCrypt's generator dialog
   (Main/Forms/Forms.cpp: wxSpinCtrl min 64, max 1048576, default 64). The
   upper bound is VeraCrypt's 1 MB keyfile read cap (VC_KEYFILE_MAX_READ in
   vc_header.cpp) — bytes past it never reach the keyfile pool, here or in
   VeraCrypt, so generating a larger file would add nothing. Enforced in
   nativeGenerateKeyfileFd; the Kotlin side clamps to the same range. */
#define VC_KEYFILE_MIN_SIZE   64
#define VC_KEYFILE_MAX_SIZE   (1 * 1024 * 1024)

/* ─── Algorithm table ───────────────────────────────────────────────── */
/* Algorithm IDs = Kotlin CipherAlgorithm.ordinal.
 *
 * Cipher arrays are stored in APPLICATION ORDER: c[0] is applied FIRST on
 * encrypt (innermost), c[n-1] is applied LAST (outermost). Encrypt loops
 * forward 0..n-1; decrypt loops reverse n-1..0. This matches VeraCrypt's
 * EncryptionModeXTS::Encrypt (forward) / Decrypt (reverse) with Ciphers[]
 * ordered innermost-first.
 *
 * "AES-Twofish": Twofish encrypts first → c[0]=TWOFISH, c[1]=AES.
 *
 * Key layout (build_cascade_key64): [ primary_0..primary_{n-1} | tweak_0..tweak_{n-1} ]
 * 32 bytes per slot, matching VeraCrypt EAInit + XTS EAInitMode.
 *
 * Defined (non-static) in crypto_core.cpp; read from vc_header.cpp and
 * jni_volume.cpp, hence the extern declaration here. */
struct AlgDef { int n; int c[3]; };
#define NUM_ALGORITHMS 15
extern const AlgDef ALGORITHMS[NUM_ALGORITHMS];

/* ─── GenCipherCtx ───────────────────────────────────────────────────── */
/* Deviation from the suggested grouping: GenCipherCtx's full definition
 * (XtsLayerKS union of per-cipher key-schedule structs) is kept OPAQUE here,
 * matching the forward declaration already in arcanum_impl.h. Only
 * crypto_core.cpp (alloc_drive/free_drive/vc_crypt_sector/init_layer_ks/
 * layer_xts_ks) ever touches its internals; every other module only ever
 * holds/passes a `GenCipherCtx*`. Giving the full struct external linkage
 * here would force every translation unit in the split (kdf.cpp, jni_files.cpp,
 * ...) to pull in Aes.h/Serpent.h/Twofish.h/Camellia.h/kuznyechik.h for no
 * benefit, so the definition stays local to crypto_core.cpp instead. */

/* ─── RAII helpers (stage 7 — full RAII) ────────────────────────────── */
/*
 * Placed here (rather than scattered at first use) so every module in the
 * split can rely on them. All that need wiping only need secure_memset's
 * PROTOTYPE (declared just below) — its actual definition lives in
 * crypto_core.cpp, which is fine: C++ only needs the declaration visible at
 * the point of call, not the definition.
 */
void secure_memset(volatile uint8_t *p, uint8_t c, size_t n);

/* Owns a POSIX fd: closes it in the destructor unless release()d or moved
 * from. Non-copyable, movable. Use for every open()/dup() so an error path
 * can't forget to close() — the single exception is a handoff where the fd
 * outlives the function (e.g. stored into the drive registry / ContainerCtx
 * on a successful mount): call release() at that ONE spot; every path
 * before it still closes normally via the destructor. */
class UniqueFd {
public:
    UniqueFd() = default;
    explicit UniqueFd(int fd) : fd_(fd) {}
    ~UniqueFd() { reset(); }

    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    UniqueFd(UniqueFd &&other) noexcept : fd_(other.fd_) { other.fd_ = -1; }
    UniqueFd& operator=(UniqueFd &&other) noexcept {
        if (this != &other) { reset(); fd_ = other.fd_; other.fd_ = -1; }
        return *this;
    }

    int  get() const { return fd_; }
    bool ok()  const { return fd_ >= 0; }

    /* Transfers ownership to the caller; this no longer closes fd_. */
    int release() { int f = fd_; fd_ = -1; return f; }

    void reset(int newFd = -1) {
        if (fd_ >= 0) close(fd_);
        fd_ = newFd;
    }

private:
    int fd_ = -1;
};

/* Fixed-size stack buffer for secret material (passwords, keys, salts,
 * header bodies): zero-initialized on construction, secure_memset in the
 * destructor. Replaces the old pattern of a raw uint8_t[N] plus a manual
 * secure_memset() copy-pasted onto every exit path. Call wipe() for a
 * deliberate EARLY wipe (the secret is no longer needed, before slow work
 * follows) — the destructor then wipes an already-zeroed buffer a second
 * time on the remaining paths, which is harmless. */
template <size_t N>
struct SecureBuffer {
    uint8_t buf[N] = {};

    SecureBuffer() = default;
    SecureBuffer(const SecureBuffer&) = delete;
    SecureBuffer& operator=(const SecureBuffer&) = delete;

    ~SecureBuffer() { wipe(); }

    uint8_t*       data()       { return buf; }
    const uint8_t* data() const { return buf; }
    constexpr size_t size() const { return N; }
    uint8_t&       operator[](size_t i)       { return buf[i]; }
    const uint8_t& operator[](size_t i) const { return buf[i]; }

    void wipe() { secure_memset((volatile uint8_t*)buf, 0, N); }
};

/* Same idea as SecureBuffer but for a typed key-schedule object (e.g.
 * aes_encrypt_ctx, TwofishInstance, kuznyechik_kds, or even a raw array type
 * like u4byte[8]) where a uint8_t[N] buffer doesn't fit the type. Wipes
 * sizeof(T) bytes of the referenced object at scope exit. */
template <typename T>
struct SecureWipe {
    T &ref;
    explicit SecureWipe(T &r) : ref(r) {}
    ~SecureWipe() { secure_memset((volatile uint8_t*)&ref, 0, sizeof(T)); }
};

/* RAII for a GetByteArrayElements/ReleaseByteArrayElements(JNI_ABORT) pin.
 * Used for caller-supplied entropy bytes that are read but never mutated —
 * JNI_ABORT means "never write back", matching that read-only contract.
 * A null jbyteArray is handled transparently: data() is nullptr, len() is 0,
 * exactly like the pre-existing no-entropy case. */
class ScopedArrayPin {
public:
    ScopedArrayPin(JNIEnv *env, jbyteArray arr) : env_(env), arr_(arr) {
        if (arr_) {
            data_ = env_->GetByteArrayElements(arr_, nullptr);
            len_  = env_->GetArrayLength(arr_);
        }
    }
    ~ScopedArrayPin() {
        if (arr_ && data_) env_->ReleaseByteArrayElements(arr_, data_, JNI_ABORT);
    }

    ScopedArrayPin(const ScopedArrayPin&) = delete;
    ScopedArrayPin& operator=(const ScopedArrayPin&) = delete;

    const jbyte* data() const { return data_; }
    jsize        len()  const { return len_; }

private:
    JNIEnv    *env_  = nullptr;
    jbyteArray arr_  = nullptr;
    jbyte     *data_ = nullptr;
    jsize      len_  = 0;
};

/* Heap byte buffer for entropy copies that today live in a plain
 * std::vector<uint8_t> followed by a manual secure_memset() before the
 * vector goes out of scope. Thin wrapper: secure_memset()s the buffer in the
 * destructor, right before the underlying vector (and its heap allocation)
 * is destroyed. */
struct SecureVector {
    std::vector<uint8_t> v;

    ~SecureVector() {
        if (!v.empty()) secure_memset((volatile uint8_t*)v.data(), 0, v.size());
    }

    void resize(size_t n) { v.resize(n); }
    bool empty() const { return v.empty(); }
    size_t size() const { return v.size(); }
    uint8_t*       data()       { return v.data(); }
    const uint8_t* data() const { return v.data(); }
};

/* ─── crypto_core.cpp exports ───────────────────────────────────────── */

/* One-shot XTS (stack-local KSes) — used for header attempt loop in
 * vc_header.cpp (write_vc_header / try_decrypt_header). key64[64] =
 * K1(32) || K2(32). AES: encrypt uses separate enc/dec KSes; tweak always
 * uses enc KS. */
void xts_crypt_temp(int type, const uint8_t key64[64],
                     uint8_t *buf, size_t len, uint64_t sn, bool enc);

/* VeraCrypt cascade key layout: primary keys first, then tweak keys (32 bytes each).
 * For n=1 the result is identical to a flat 64-byte block — single-cipher unaffected. */
void build_cascade_key64(const uint8_t *dk, int n, int i, uint8_t out[64]);

/* CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320). crc32_step() is the
 * exact equivalent of VeraCrypt's CRCFUNC macro:
 *   #define CRCFUNC(crc,b)  (crc_32_tab[((int)(crc)^(b))&0xff]^((crc)>>8))
 * crc32_buf() is the finalised CRC-32 of a whole buffer (header integrity). */
uint32_t crc32_step(uint32_t crc, uint8_t b);
uint32_t crc32_buf(const uint8_t *data, size_t len);

/* Big-endian helpers used by the VeraCrypt header (de)serialization. */
void     put_be32(uint8_t *b, uint32_t v);
void     put_be64(uint8_t *b, uint64_t v);
uint32_t get_be32(const uint8_t *b);
uint64_t get_be64(const uint8_t *b);

/* /dev/urandom helper. */
bool read_urandom(uint8_t *buf, size_t len);

/* Random Pool Enrichment: XOR-folds caller-supplied entropy (e.g.
 * touch/accelerometer samples collected by the UI during container
 * creation) across a urandom-filled buffer. XOR of a uniform urandom stream
 * with ANY independent byte stream stays uniform, so this can only add
 * entropy, never subtract from it. No-op when entropy is null/empty. */
void xor_fold_entropy(uint8_t *buf, size_t bufLen,
                       const uint8_t *entropy, size_t entropyLen);

/* Monotonic milliseconds (CLOCK_MONOTONIC, immune to wall-clock jumps) —
 * used by jni_volume.cpp's ProgressThrottle/preallocate_fd/fill_range_xts. */
uint64_t monotonic_ms();

/* Drive registry (definition + g_drives itself live in arcanum_impl.h /
 * crypto_core.cpp). FatFs is not thread-safe (FF_FS_REENTRANT 0); all f_*
 * calls anywhere in the split must hold this lock. */
extern std::mutex g_fatfs_mutex;

/* CALLER MUST HOLD g_fatfs_mutex. See the comment on g_fatfs_mutex — both
 * only touch the shared g_drives[] registry (no FatFs calls), so they don't
 * lock internally: several call sites need alloc_drive/free_drive to run in
 * the same critical section as an adjacent f_mkfs/f_mount/f_unmount or
 * g_ctxMap operation. */
int  alloc_drive(int fd, uint64_t dataOff, uint64_t sectors,
                 const uint8_t *masterKey, int algId, int hashId = 0,
                 bool isHidden = false, uint64_t hiddenBoundary = 0,
                 uint32_t iterCount = 0, bool readOnly = false);
void free_drive(int pdrv);

/* Decodes a jlong handle into a validated pdrv, or -1 if the handle is
 * malformed, out of range, or stale (slot freed/reused since the handle was
 * issued). CALLER MUST HOLD g_fatfs_mutex (or accept a benign pre-check race
 * followed by re-validation under the lock — see call sites). */
int decode_handle(jlong handle);

/* Mount-progress name lookups (used by vc_header.cpp's report_trying). */
const char* algo_name(int algId);
const char* hash_name(int hashId);

/* ─── kdf.cpp exports ────────────────────────────────────────────────── */

/* Opaque here — full definition (init/update/final callbacks + block/output
 * sizes) lives in kdf.cpp; every cross-module use only ever takes/returns a
 * `const HashTraits*` pointer. */
struct HashTraits;

/* hashId out of [0,4] falls back to SHA-512 (matches the original per-hash
 * switch's default behavior in write_vc_header). */
const HashTraits* hash_traits_for(int hashId);

/* Every caller passes slen == VC_HEADER_SALT_SIZE. */
void pbkdf2_generic(const HashTraits *t, const uint8_t *pwd, int plen,
                     const uint8_t *salt, int slen,
                     uint32_t iters, uint8_t *dk, int dklen);

/* PIM iteration formulas (VeraCrypt spec). pim == 0 → use the per-hash
 * default iteration count table. */
uint32_t vc_get_iterations(int hashId, int pim);

/* hi outside [0,4] leaves `out` untouched (matches the original per-hash
 * switch's default: break — callers zero-initialize `out` before calling). */
void derive_header_key(int hi, const uint8_t *password, int pwd_len,
                        const uint8_t *salt, int pim, uint8_t out[192]);

/* ─── vc_header.cpp exports ──────────────────────────────────────────── */

/* Mount-progress callback state, shared between vc_header.cpp's
 * read_vc_header (which drives it) and jni_volume.cpp's do_open_container
 * (which constructs it and passes MountProgressListener through). */
struct MountCb {
    JNIEnv    *env;
    jobject    listener;
    jmethodID  mid;
    int        attempt;
    int        total;
};
jmethodID resolve_mount_mid(JNIEnv *env, jobject listener);

/* Writes a 512-byte VeraCrypt-compatible header.
 * masterKey : n*64 bytes (one 64-byte slot per cipher in the cascade)
 * algId     : algorithm index into ALGORITHMS[]
 * hashAlg   : 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256 */
int write_vc_header(int fd, uint64_t fileOff,
                     uint64_t dataSz, uint64_t dataOff,
                     const uint8_t *masterKey,
                     int algId, int hashAlg,
                     const char *password, int pwd_len,
                     int pim = 0,
                     uint64_t hiddenVolSize = 0,
                     const uint8_t *existingSalt = nullptr);

/*
 * Tries all 5 hashes × 15 cipher algorithms (75 combinations max).
 * hintAlgId / hintHashId (-1 = unknown): if both are valid, the matching
 * combination is tried first so re-mounting a known container is fast.
 * On success fills masterKey (n*64 bytes), outMkLen, outAlgId, dataSz, dataOff.
 */
int read_vc_header(int fd, uint64_t fileOff,
                    const char *password, int pwd_len,
                    uint8_t *masterKey, int *outMkLen,
                    uint64_t *dataSz, uint64_t *dataOff,
                    int *outAlgId, int *outHashId = nullptr,
                    int pim = 0,
                    uint64_t *outHiddenVolSize = nullptr,
                    int hintAlgId = -1, int hintHashId = -1,
                    MountCb *mountCb = nullptr);

/* Overwrites the 512-byte header at fileOff with random data for wipeCount
 * passes, then writes the new header encrypted with the new credentials.
 * extraEntropy/extraEntropyLen: optional user-collected bytes XOR'd into the
 * new salt before writing (Random Pool Enrichment). Pass nullptr/0 to skip. */
int wipe_and_rewrite_header(int fd, uint64_t fileOff,
                             uint64_t dataSz, uint64_t dataOff,
                             const uint8_t *masterKey, int algId, int newHashAlg,
                             const char *newPwd, int newPwdLen, int newPim,
                             uint64_t hiddenVolSize, int wipePassCount,
                             const uint8_t* extraEntropy = nullptr,
                             size_t extraEntropyLen = 0);

/* ─── Keyfile pool (vc_header.cpp) ───────────────────────────────────── */
/*
 * The pool is 64 bytes for passwords up to 64 bytes and 128 bytes beyond that,
 * exactly as VeraCrypt picks it (Keyfiles.c:239). Arcanum hardcoded 64 before
 * issue #112; volumes written by that version with a keyfile AND a password
 * over 64 bytes carry a 64-byte-pool header that the corrected code cannot
 * reproduce.
 *
 * forceLegacyPool exists solely to open those. Rules:
 *   - Authentication paths (mount, and the credential check in change/backup/
 *     restore/expand) MAY retry with it after a normal attempt fails.
 *   - Paths that WRITE a header must never use it, so every header written
 *     from now on is correct and a re-key silently heals the volume.
 * Use keyfile_pool_has_legacy_variant() to skip the retry when it cannot
 * possibly differ - otherwise a wrong password costs two full PBKDF2 runs.
 */

/* True when the standard and legacy pools would differ for this input, i.e.
 * the retry is worth attempting. Keyfiles must be present and the password
 * longer than the legacy pool; otherwise both paths produce identical bytes. */
inline bool keyfile_pool_has_legacy_variant(int origPwdLen, bool haveKeyfiles) {
    return haveKeyfiles && origPwdLen > 64;
}

/* Apply one or more keyfiles to the password buffer, read from JNI byte arrays.
 * Matches VeraCrypt KeyFilesApply() exactly. jKeyfileData is an
 * Array<ByteArray>? — null or empty means no-op.
 *
 * There is deliberately no path-taking variant. One existed until issue #116,
 * and it forced every caller outside the mount path to copy the user's keyfile
 * into cacheDir in plaintext first. Keyfile bytes now reach the native layer
 * only through this function, so no caller can put them on disk on the way in.
 *
 * Returns false on OOM — caller must propagate as a hard error. */
bool apply_keyfile_buffers(JNIEnv *env, jobjectArray jKeyfileData,
                            uint8_t *pwd_buf, int *pwd_len,
                            bool forceLegacyPool = false);

/* ─── jni_volume.cpp / jni_files.cpp shared JNI utilities ───────────── */
/*
 * Defined (non-static) in jni_volume.cpp, which also owns JNI_OnLoad and
 * the JniCache it populates; declared here so jni_files.cpp (nativeListFiles,
 * nativeReadFile/WriteFile/Delete/Rename/Mkdir/Rmdir) can reuse them without
 * a duplicate definition.
 */

/* jstring → UTF-8 std::string, manually converting UTF-16 (see jni_volume.cpp
 * for the full rationale re: GetStringUTFChars' Modified-UTF-8 mismatch).
 * Unpaired surrogates are replaced with U+FFFD. No wiping here — paths are
 * not secret. */
std::string jstring_to_string(JNIEnv *env, jstring js);

/* Constructs a jstring from a genuine UTF-8 C string, correctly handling
 * non-BMP characters (4-byte sequences, e.g. emoji) that NewStringUTF cannot. */
jstring utf8_to_jstring(JNIEnv *env, const char *s);

/* Returns true iff every byte in s forms a valid UTF-8 sequence. */
bool is_valid_utf8(const char *s);

/* Resolved once in JNI_OnLoad (jni_volume.cpp) and held as GlobalRefs for the
 * life of the process; call sites elsewhere (jni_files.cpp's nativeListFiles)
 * fall back to a per-call lookup if the cache failed to populate. */
struct JniCache {
    jclass    fileInfoCls  = nullptr;
    jmethodID fileInfoCtor = nullptr;   /* NativeFileInfo(String,String,J,Z,J) */
    jclass    stringCls    = nullptr;
    jmethodID stringCtor   = nullptr;   /* String(byte[], String) */
    jstring   utf8Name     = nullptr;   /* "UTF-8", interned as a GlobalRef */
};
extern JniCache g_jniCache;

/* ─── ContainerCtx / g_ctxMap ────────────────────────────────────────── */
/*
 * Defined (the map) in jni_files.cpp; jni_volume.cpp's do_open_container
 * (publish) and nativeCloseContainer (erase) also touch it directly, hence
 * the extern declaration here rather than a jni_files.cpp-local static.
 */
struct ContainerCtx {
    int   pdrv;
    FATFS fatFs;      /* zeroed and unused when isExt4 - ext4 keeps no mounted state */
    int   fd;
    bool  readOnly;
    bool  isExt4 = false;  /* true when the volume is ext4, not FAT: the file ops in
                              jni_files.cpp dispatch to jni_ext4.cpp, and this open
                              skipped f_mount. */
};
extern std::unordered_map<int, ContainerCtx*> g_ctxMap;

/* ─── ext4 file surface (jni_ext4.cpp) ───────────────────────────────── */
/*
 * The ext4 half of the file operations. jni_files.cpp's JNI entry points dispatch
 * here when ext4jni_is_container(handle) is true, so the one nativeListFiles /
 * nativeReadFile / ... serves both filesystems and Kotlin never learns which is
 * underneath. Each of these takes g_fatfs_mutex itself, exactly like the FatFs
 * ops, so the dispatch check must NOT hold it. jni_volume.cpp calls ext4jni_probe
 * at mount time (under the lock) to set ContainerCtx.isExt4.
 */
bool         ext4jni_is_container(jlong handle);
bool         ext4jni_probe(int pdrv);                    /* caller holds g_fatfs_mutex */
bool         ext4jni_format(int pdrv, uint64_t dataSize); /* caller holds g_fatfs_mutex */
jint         ext4jni_get_filesystem();
jobjectArray ext4jni_list_files(JNIEnv *env, jlong handle, jstring jDirPath);
jbyteArray   ext4jni_read_file(JNIEnv *env, jlong handle, jstring jFilePath,
                               jlong offset, jint length);
jint         ext4jni_write_file(JNIEnv *env, jlong handle, jstring jFilePath,
                                jbyteArray jData, jlong offset);
jint         ext4jni_write_at(JNIEnv *env, jlong handle, jstring jFilePath,
                              jbyteArray jData, jlong offset);
jint         ext4jni_create_directory(JNIEnv *env, jlong handle, jstring jDirPath);
jint         ext4jni_delete_file(JNIEnv *env, jlong handle, jstring jFilePath);
jint         ext4jni_delete_directory(JNIEnv *env, jlong handle, jstring jDirPath);
jint         ext4jni_rename(JNIEnv *env, jlong handle, jstring jOld, jstring jNew);
jlongArray   ext4jni_fs_usage(JNIEnv *env, jlong handle);

/* Drops the streaming read cache (jni_files.cpp) if it holds an open handle on
 * this pdrv. Called from every drive mutation and from nativeCloseContainer so
 * a delete/rename/unmount can never leave a dangling cached FIL (FF_FS_LOCK 0).
 * CALLER MUST HOLD g_fatfs_mutex. */
void invalidate_read_cache_for_pdrv(int pdrv);
