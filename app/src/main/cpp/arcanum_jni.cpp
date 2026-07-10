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

#include "arcanum_impl.h"

#include <jni.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <ctime>
#include <unordered_map>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <android/log.h>
#include <mutex>
#include <thread>
#include <memory>

extern "C" {
#include "Crypto/Aes.h"
#include "Crypto/Sha2.h"
#include "Crypto/Whirlpool.h"
#include "Crypto/Streebog.h"
#include "Crypto/Serpent.h"
#include "Crypto/Twofish.h"
#include "Crypto/Camellia.h"
#include "Crypto/kuznyechik.h"
#include "Crypto/blake2.h"
#include "fatfs/ff.h"
}
#include "Common/Xts.h"    /* EncryptBufferXTS, DecryptBufferXTS, UINT64_STRUCT */

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
/* Per-hash iteration counts for non-system VeraCrypt volumes.
   IDs: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256. */
static const uint32_t VC_PBKDF2_ITERS_BY_HASH[] = { 500000, 500000, 500000, 500000, 500000 };

/* PIM iteration formulas (VeraCrypt spec).
   pim == 0 → use default table above. */
static uint32_t vc_get_iterations(int hashId, int pim) {
    if (pim <= 0) {
        return (hashId >= 0 && hashId <= 4) ? VC_PBKDF2_ITERS_BY_HASH[hashId] : 500000U;
    }
    if (pim > 2147468) pim = 2147468; /* clamp to MAX_PIM_VALUE */
    switch (hashId) {
        case 0: return 15000U + (uint32_t)pim * 1000U; /* SHA-512      */
        case 1: return 15000U + (uint32_t)pim * 1000U; /* SHA-256      */
        case 2: return 15000U + (uint32_t)pim * 1000U; /* Whirlpool    */
        case 3: return 15000U + (uint32_t)pim * 1000U; /* Streebog     */
        case 4: return 15000U + (uint32_t)pim * 1000U; /* BLAKE2s-256  */
        default: return 500000U;
    }
}

/* Error codes (match Kotlin companion object) */
#define ERR_OK               0
#define ERR_FILE             -1
#define ERR_READ             -2
#define ERR_WRONG_PASSWORD   -3
#define ERR_UNSUPPORTED      -4
#define ERR_NO_SPACE         -5
#define ERR_NO_SLOT          -6
#define ERR_FS               -7
#define ERR_RAND             -8
#define ERR_HIDDEN_BOUNDARY  -9  /* write blocked by hidden-volume protection */
#define ERR_READ_ONLY       -10  /* write blocked: container mounted read-only */

/* Key schedule sizes for Serpent and Camellia (others use their structs) */
#define SERPENT_KS_SIZE    (140 * 4)   /* 560 bytes */
#define CAMELLIA_KS_SIZE   (34 * 8)    /* 272 bytes */

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
 * 32 bytes per slot, matching VeraCrypt EAInit + XTS EAInitMode.            */

struct AlgDef { int n; int c[3]; };

static const AlgDef ALGORITHMS[15] = {
    /* 0  AES               */ {1, {CIPHER_AES,        0,                 0              }},
    /* 1  Serpent           */ {1, {CIPHER_SERPENT,    0,                 0              }},
    /* 2  Twofish           */ {1, {CIPHER_TWOFISH,    0,                 0              }},
    /* 3  Camellia          */ {1, {CIPHER_CAMELLIA,   0,                 0              }},
    /* 4  Kuznyechik        */ {1, {CIPHER_KUZNYECHIK, 0,                 0              }},
    /* 5  AES→Twofish       */ {2, {CIPHER_TWOFISH,    CIPHER_AES,        0              }},
    /* 6  AES→Twofish→Serp  */ {3, {CIPHER_SERPENT,    CIPHER_TWOFISH,    CIPHER_AES     }},
    /* 7  Serpent→AES       */ {2, {CIPHER_AES,        CIPHER_SERPENT,    0              }},
    /* 8  Serp→Twofish→AES  */ {3, {CIPHER_AES,        CIPHER_TWOFISH,    CIPHER_SERPENT }},
    /* 9  Twofish→Serpent   */ {2, {CIPHER_SERPENT,    CIPHER_TWOFISH,    0              }},
    /* 10 Camellia→Kuz      */ {2, {CIPHER_KUZNYECHIK, CIPHER_CAMELLIA,   0              }},
    /* 11 Camellia→Serpent  */ {2, {CIPHER_SERPENT,    CIPHER_CAMELLIA,   0              }},
    /* 12 Kuz→AES           */ {2, {CIPHER_AES,        CIPHER_KUZNYECHIK, 0              }},
    /* 13 Kuz→Serp→Camellia */ {3, {CIPHER_CAMELLIA,   CIPHER_SERPENT,    CIPHER_KUZNYECHIK}},
    /* 14 Kuz→Twofish       */ {2, {CIPHER_TWOFISH,    CIPHER_KUZNYECHIK, 0              }},
};
#define NUM_ALGORITHMS 15

/* ─── GenCipherCtx: persistent per-drive key context ────────────────── */
/* Heap-allocated in alloc_drive, freed in free_drive.
   layer_xts_ks() does XTS for one cipher layer using pre-built KSes.  */

struct XtsLayerKS {
    int type;
    union {
        struct {
            aes_encrypt_ctx k1enc;
            aes_decrypt_ctx k1dec;
            aes_encrypt_ctx k2enc;
        } aes;
        struct {
            uint8_t k1[SERPENT_KS_SIZE];
            uint8_t k2[SERPENT_KS_SIZE];
        } serpent;
        struct {
            TwofishInstance k1;
            TwofishInstance k2;
        } twofish;
        struct {
            uint8_t k1[CAMELLIA_KS_SIZE];
            uint8_t k2[CAMELLIA_KS_SIZE];
        } camellia;
        struct {
            kuznyechik_kds k1;
            kuznyechik_kds k2;
        } kuznyechik;
    };
};

struct GenCipherCtx {
    int        num;
    XtsLayerKS layers[MAX_CASCADE];
};

/* ─── Drive registry ────────────────────────────────────────────────── */

DriveContext g_drives[MAX_DRIVES] = {};

struct ContainerCtx {
    int   pdrv;
    FATFS fatFs;
    int   fd;
    bool  readOnly;
};

static std::unordered_map<int, ContainerCtx*> g_ctxMap;
// FatFs is not thread-safe (FF_FS_REENTRANT 0); all f_* calls must hold this lock.
static std::mutex g_fatfs_mutex;

static void secure_memset(volatile uint8_t *p, uint8_t c, size_t n);

/* ─── RAII helpers (stage 7 — full RAII) ────────────────────────────── */
/*
 * Placed here (rather than scattered at first use) so every function below,
 * including the very first one (xts_crypt_temp), can rely on them. All five
 * only need secure_memset's PROTOTYPE (forward-declared just above) — its
 * actual definition comes later in the file, which is fine: C++ only needs
 * the declaration visible at the point of call, not the definition.
 */

/* RAII wiper for password std::strings (stage 3a). std::string's small-buffer
 * optimization means password bytes commonly live inline in the string
 * object itself (no heap allocation to leak/free), so this must be wiped
 * explicitly — the destructor alone does not zero memory. Instantiate right
 * after constructing EVERY password-holding std::string (password,
 * hiddenPassword, oldPassword, newPassword, outerPassword, ...) so it's wiped
 * on every exit path (early return, exception) without per-path bookkeeping.
 * Do NOT wrap non-secret strings (paths). */
struct StringWiper {
    std::string &s;
    explicit StringWiper(std::string &v) : s(v) {}
    ~StringWiper() {
        if (!s.empty()) secure_memset((volatile uint8_t*)&s[0], 0, s.size());
    }
};

/* Owns a POSIX fd: closes it in the destructor unless release()d or moved
 * from. Non-copyable, movable. Use for every open()/dup() in this file so an
 * error path can't forget to close() — the single exception is a handoff
 * where the fd outlives the function (e.g. stored into the drive registry /
 * ContainerCtx on a successful mount): call release() at that ONE spot; every
 * path before it still closes normally via the destructor. */
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

/* ─── One-shot XTS (stack-local KSes) — used for header attempt loop ── */
/* key64[64] = K1(32) || K2(32).
   AES: encrypt uses separate enc/dec KSes; tweak always uses enc KS.   */
static void xts_crypt_temp(int type, const uint8_t key64[64],
                           uint8_t *buf, size_t len, uint64_t sn, bool enc) {
    UINT64_STRUCT dataUnit;
    dataUnit.Value = (TC_LARGEST_COMPILER_UINT)sn;

    switch (type) {
        case CIPHER_AES: {
            aes_encrypt_ctx k1enc, k2enc;
            aes_decrypt_ctx k1dec;
            SecureWipe<aes_encrypt_ctx> _w1(k1enc), _w2(k2enc);
            SecureWipe<aes_decrypt_ctx> _w3(k1dec);
            aes_encrypt_key256(key64,    &k1enc);
            aes_decrypt_key256(key64,    &k1dec);
            aes_encrypt_key256(key64+32, &k2enc);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1enc, (uint8_t*)&k2enc, CIPHER_AES);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1dec, (uint8_t*)&k2enc, CIPHER_AES);
            break;
        }
        case CIPHER_SERPENT: {
            SecureBuffer<SERPENT_KS_SIZE> ks1, ks2;
            serpent_set_key(key64,    ks1.data());
            serpent_set_key(key64+32, ks2.data());
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1.data(), ks2.data(), CIPHER_SERPENT);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1.data(), ks2.data(), CIPHER_SERPENT);
            break;
        }
        case CIPHER_TWOFISH: {
            TwofishInstance k1, k2;
            u4byte k1b[8], k2b[8];
            SecureWipe<TwofishInstance> _w1(k1), _w2(k2);
            SecureWipe<u4byte[8]> _w3(k1b), _w4(k2b);
            memcpy(k1b, key64,    32);
            memcpy(k2b, key64+32, 32);
            twofish_set_key(&k1, k1b);
            twofish_set_key(&k2, k2b);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1, (uint8_t*)&k2, CIPHER_TWOFISH);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1, (uint8_t*)&k2, CIPHER_TWOFISH);
            break;
        }
        case CIPHER_CAMELLIA: {
            SecureBuffer<CAMELLIA_KS_SIZE> ks1, ks2;
            camellia_set_key(key64,    ks1.data());
            camellia_set_key(key64+32, ks2.data());
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1.data(), ks2.data(), CIPHER_CAMELLIA);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1.data(), ks2.data(), CIPHER_CAMELLIA);
            break;
        }
        case CIPHER_KUZNYECHIK: {
            kuznyechik_kds ks1, ks2;
            SecureWipe<kuznyechik_kds> _w1(ks1), _w2(ks2);
            kuznyechik_set_key(key64,    &ks1);
            kuznyechik_set_key(key64+32, &ks2);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks1, (uint8_t*)&ks2, CIPHER_KUZNYECHIK);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks1, (uint8_t*)&ks2, CIPHER_KUZNYECHIK);
            break;
        }
    }
}

/* ─── GenCipherCtx ops (for persistent drive I/O) ───────────────────── */

static void secure_memset(volatile uint8_t *p, uint8_t c, size_t n);
static void build_cascade_key64(const uint8_t *dk, int n, int i, uint8_t out[64]);

static void init_layer_ks(XtsLayerKS *ks, int type, const uint8_t key64[64]) {
    ks->type = type;
    switch (type) {
        case CIPHER_AES:
            aes_encrypt_key256(key64,    &ks->aes.k1enc);
            aes_decrypt_key256(key64,    &ks->aes.k1dec);
            aes_encrypt_key256(key64+32, &ks->aes.k2enc);
            break;
        case CIPHER_SERPENT:
            serpent_set_key(key64,    ks->serpent.k1);
            serpent_set_key(key64+32, ks->serpent.k2);
            break;
        case CIPHER_TWOFISH: {
            u4byte k1b[8], k2b[8];
            memcpy(k1b, key64,    32);
            memcpy(k2b, key64+32, 32);
            twofish_set_key(&ks->twofish.k1, k1b);
            twofish_set_key(&ks->twofish.k2, k2b);
            break;
        }
        case CIPHER_CAMELLIA:
            camellia_set_key(key64,    ks->camellia.k1);
            camellia_set_key(key64+32, ks->camellia.k2);
            break;
        case CIPHER_KUZNYECHIK:
            kuznyechik_set_key(key64,    &ks->kuznyechik.k1);
            kuznyechik_set_key(key64+32, &ks->kuznyechik.k2);
            break;
    }
}

static void layer_xts_ks(const XtsLayerKS *ks, uint8_t *buf, size_t len,
                         uint64_t sn, bool enc) {
    UINT64_STRUCT dataUnit;
    dataUnit.Value = (TC_LARGEST_COMPILER_UINT)sn;

    switch (ks->type) {
        case CIPHER_AES:
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->aes.k1enc, (uint8_t*)&ks->aes.k2enc,
                                 CIPHER_AES);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->aes.k1dec, (uint8_t*)&ks->aes.k2enc,
                                 CIPHER_AES);
            break;
        case CIPHER_SERPENT:
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 const_cast<uint8_t*>(ks->serpent.k1),
                                 const_cast<uint8_t*>(ks->serpent.k2),
                                 CIPHER_SERPENT);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 const_cast<uint8_t*>(ks->serpent.k1),
                                 const_cast<uint8_t*>(ks->serpent.k2),
                                 CIPHER_SERPENT);
            break;
        case CIPHER_TWOFISH:
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->twofish.k1, (uint8_t*)&ks->twofish.k2,
                                 CIPHER_TWOFISH);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->twofish.k1, (uint8_t*)&ks->twofish.k2,
                                 CIPHER_TWOFISH);
            break;
        case CIPHER_CAMELLIA:
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 const_cast<uint8_t*>(ks->camellia.k1),
                                 const_cast<uint8_t*>(ks->camellia.k2),
                                 CIPHER_CAMELLIA);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 const_cast<uint8_t*>(ks->camellia.k1),
                                 const_cast<uint8_t*>(ks->camellia.k2),
                                 CIPHER_CAMELLIA);
            break;
        case CIPHER_KUZNYECHIK:
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->kuznyechik.k1, (uint8_t*)&ks->kuznyechik.k2,
                                 CIPHER_KUZNYECHIK);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks->kuznyechik.k1, (uint8_t*)&ks->kuznyechik.k2,
                                 CIPHER_KUZNYECHIK);
            break;
    }
}

/* Public — cascade XTS for one 512-byte sector.  Called by diskio.cpp. */
void vc_crypt_sector(GenCipherCtx *ctx, uint8_t *buf, uint64_t sn, bool enc) {
    if (enc) {
        for (int i = 0; i < ctx->num; i++)
            layer_xts_ks(&ctx->layers[i], buf, VC_SECTOR_SIZE, sn, true);
    } else {
        for (int i = ctx->num - 1; i >= 0; i--)
            layer_xts_ks(&ctx->layers[i], buf, VC_SECTOR_SIZE, sn, false);
    }
}

/* ─── Drive alloc / free ─────────────────────────────────────────────── */

/* CALLER MUST HOLD g_fatfs_mutex — see the comment on g_fatfs_mutex. Both
 * functions only touch the shared g_drives[] registry (no FatFs calls), so
 * they don't lock internally: several call sites need alloc_drive/free_drive
 * to run in the same critical section as an adjacent f_mkfs/f_mount/f_unmount
 * or g_ctxMap operation (self-locking here would either deadlock on the
 * non-recursive mutex or leave a window where the two operations aren't
 * atomic together). */
static int alloc_drive(int fd, uint64_t dataOff, uint64_t sectors,
                       const uint8_t *masterKey, int algId, int hashId = 0,
                       bool isHidden = false, uint64_t hiddenBoundary = 0,
                       uint32_t iterCount = 0, bool readOnly = false) {
    if (algId < 0 || algId >= NUM_ALGORITHMS) return -1;
    for (int i = 0; i < MAX_DRIVES; i++) {
        if (!g_drives[i].active) {
            g_drives[i].fd               = fd;
            g_drives[i].dataOffset       = dataOff;
            g_drives[i].sectorCount      = sectors;
            g_drives[i].active           = true;
            g_drives[i].algId            = algId;
            g_drives[i].hashId           = hashId;
            g_drives[i].pkcs5Iterations  = iterCount;
            g_drives[i].isHidden         = isHidden;
            g_drives[i].readOnly         = readOnly;
            g_drives[i].hiddenBoundary   = hiddenBoundary;
            /* generation was preserved (not zeroed) by the previous free_drive();
             * bump it now so a stale handle from that earlier occupant of this
             * slot no longer decodes successfully. Starts at 1 on first use
             * (g_drives[] is zero-initialized), skips 0 on wraparound so 0
             * never denotes a "valid" generation. */
            g_drives[i].generation++;
            if (g_drives[i].generation == 0) g_drives[i].generation = 1;

            auto *ctx = static_cast<GenCipherCtx*>(malloc(sizeof(GenCipherCtx)));
            if (!ctx) { g_drives[i].active = false; return -1; }
            mlock(ctx, sizeof(GenCipherCtx)); /* best-effort: keep key schedule out of swap */
            ctx->num = ALGORITHMS[algId].n;
            for (int j = 0; j < ctx->num; j++) {
                SecureBuffer<64> ck;
                build_cascade_key64(masterKey, ctx->num, j, ck.data());
                init_layer_ks(&ctx->layers[j], ALGORITHMS[algId].c[j], ck.data());
            }
            g_drives[i].cipherCtx = ctx;
            return i;
        }
    }
    return -1;
}

/* CALLER MUST HOLD g_fatfs_mutex — see alloc_drive. */
static void free_drive(int pdrv) {
    if (pdrv < 0 || pdrv >= MAX_DRIVES) return;
    if (g_drives[pdrv].cipherCtx) {
        secure_memset((volatile uint8_t*)g_drives[pdrv].cipherCtx, 0, sizeof(GenCipherCtx));
        munlock(g_drives[pdrv].cipherCtx, sizeof(GenCipherCtx));
        free(g_drives[pdrv].cipherCtx);
    }
    uint32_t gen = g_drives[pdrv].generation; /* preserved across the memset below */
    memset(&g_drives[pdrv], 0, sizeof(DriveContext));
    g_drives[pdrv].active     = false;
    g_drives[pdrv].generation = gen;
}

/* Decodes a jlong handle into a validated pdrv, or -1 if the handle is
 * malformed, out of range, or stale (slot freed/reused since the handle was
 * issued). CALLER MUST HOLD g_fatfs_mutex (or accept a benign pre-check race
 * followed by re-validation under the lock — see call sites). */
static int decode_handle(jlong handle) {
    if (handle < 0) return -1;
    int      pdrv = (int)(handle & 0xFF);
    uint32_t gen  = (uint32_t)((uint64_t)handle >> 8);
    if (pdrv < 0 || pdrv >= MAX_DRIVES) return -1;
    if (!g_drives[pdrv].active) return -1;
    if (g_drives[pdrv].generation != gen) return -1;
    return pdrv;
}

/* Volatile pointer prevents the compiler from eliding security-critical zeroing. */
static void secure_memset(volatile uint8_t *p, uint8_t c, size_t n) {
    while (n--) *p++ = c;
}

/* VeraCrypt cascade key layout: primary keys first, then tweak keys (32 bytes each).
 * For n=1 the result is identical to a flat 64-byte block — single-cipher unaffected. */
static void build_cascade_key64(const uint8_t *dk, int n, int i, uint8_t out[64]) {
    memcpy(out,    dk + i * 32,           32);
    memcpy(out+32, dk + n * 32 + i * 32, 32);
}

/* ─── Generic HMAC / PBKDF2 (hash-traits dispatch) ──────────────────── */
/*
 * Single implementation shared by all 5 PBKDF2 PRFs (SHA-512, SHA-256,
 * Whirlpool, Streebog, BLAKE2s-256). Each hash exposes normalized
 * init/update/final callbacks + block/output sizes via HashTraits so
 * hmac_generic()/pbkdf2_generic() are written once instead of five times.
 * Block sizes: SHA-512=128/64out, SHA-256=64/32, Whirlpool=64/64,
 * Streebog=64/64, BLAKE2s=64/32.
 */
union HashCtx {
    sha512_ctx     sha512;
    sha256_ctx     sha256;
    WHIRLPOOL_CTX  whirlpool;
    STREEBOG_CTX   streebog;
    blake2s_state  blake2s;
};

struct HashTraits {
    int blockSize;
    int outSize;
    void (*init)(HashCtx *ctx);
    void (*update)(HashCtx *ctx, const uint8_t *data, size_t len);
    void (*final_)(HashCtx *ctx, uint8_t *out);
};

static void hctx_init_sha512  (HashCtx *c) { sha512_begin(&c->sha512); }
static void hctx_update_sha512(HashCtx *c, const uint8_t *d, size_t n) { sha512_hash(d, (uint_64t)n, &c->sha512); }
static void hctx_final_sha512 (HashCtx *c, uint8_t *out) { sha512_end(out, &c->sha512); }

static void hctx_init_sha256  (HashCtx *c) { sha256_begin(&c->sha256); }
static void hctx_update_sha256(HashCtx *c, const uint8_t *d, size_t n) { sha256_hash(d, (uint_32t)n, &c->sha256); }
static void hctx_final_sha256 (HashCtx *c, uint8_t *out) { sha256_end(out, &c->sha256); }

static void hctx_init_whirlpool  (HashCtx *c) { WHIRLPOOL_init(&c->whirlpool); }
static void hctx_update_whirlpool(HashCtx *c, const uint8_t *d, size_t n) { WHIRLPOOL_add(d, (unsigned)n, &c->whirlpool); }
static void hctx_final_whirlpool (HashCtx *c, uint8_t *out) { WHIRLPOOL_finalize(&c->whirlpool, out); }

static void hctx_init_streebog  (HashCtx *c) { STREEBOG_init(&c->streebog); }
static void hctx_update_streebog(HashCtx *c, const uint8_t *d, size_t n) { STREEBOG_add(&c->streebog, d, n); }
static void hctx_final_streebog (HashCtx *c, uint8_t *out) { STREEBOG_finalize(&c->streebog, out); }

static void hctx_init_blake2s  (HashCtx *c) { blake2s_init(&c->blake2s, BLAKE2S_OUTBYTES); }
static void hctx_update_blake2s(HashCtx *c, const uint8_t *d, size_t n) { blake2s_update(&c->blake2s, d, n); }
static void hctx_final_blake2s (HashCtx *c, uint8_t *out) { blake2s_final(&c->blake2s, out, BLAKE2S_OUTBYTES); }

/* Indexed by PBKDF2 hash ID: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256 */
static const HashTraits HASH_TRAITS[5] = {
    { 128, 64, hctx_init_sha512,     hctx_update_sha512,     hctx_final_sha512    },
    {  64, 32, hctx_init_sha256,     hctx_update_sha256,     hctx_final_sha256    },
    {  64, 64, hctx_init_whirlpool,  hctx_update_whirlpool,  hctx_final_whirlpool },
    {  64, 64, hctx_init_streebog,   hctx_update_streebog,   hctx_final_streebog  },
    {  64, 32, hctx_init_blake2s,    hctx_update_blake2s,    hctx_final_blake2s   },
};

/* Standard HMAC construction (ipad/opad XOR with 0x36/0x5C). out must hold
 * at least t->outSize bytes. Max block size across all hashes is 128 (SHA-512). */
static void hmac_generic(const HashTraits *t, const uint8_t *key, int klen,
                         const uint8_t *msg, size_t mlen, uint8_t *out) {
    uint8_t k[128] = {};
    uint8_t ipad[128], opad[128];
    HashCtx ctx;
    if (klen > t->blockSize) {
        t->init(&ctx);
        t->update(&ctx, key, (size_t)klen);
        t->final_(&ctx, k);
    } else {
        memcpy(k, key, (size_t)klen);
    }
    for (int i = 0; i < t->blockSize; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    t->init(&ctx);
    t->update(&ctx, ipad, (size_t)t->blockSize);
    t->update(&ctx, msg, mlen);
    t->final_(&ctx, out);
    t->init(&ctx);
    t->update(&ctx, opad, (size_t)t->blockSize);
    t->update(&ctx, out, (size_t)t->outSize);
    t->final_(&ctx, out);
}

/* Every caller passes slen == VC_HEADER_SALT_SIZE; the stack buffer below is
 * sized accordingly (stage 2f — replaces a per-block malloc that could
 * silently fail and leave the derived key all-zero). */
static void pbkdf2_generic(const HashTraits *t, const uint8_t *pwd, int plen,
                           const uint8_t *salt, int slen,
                           uint32_t iters, uint8_t *dk, int dklen) {
    if (slen > VC_HEADER_SALT_SIZE) return; /* defensive: never true in practice */
    uint8_t saltb[VC_HEADER_SALT_SIZE + 4];
    int blocks = (dklen + t->outSize - 1) / t->outSize;
    for (int b = 1; b <= blocks; b++) {
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[64], T[64]; /* max out size across all hashes is 64 */
        hmac_generic(t, pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, (size_t)t->outSize);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_generic(t, pwd, plen, U, (size_t)t->outSize, U);
            for (int j = 0; j < t->outSize; j++) T[j] ^= U[j];
        }
        int cp = (b == blocks && dklen % t->outSize != 0) ? (dklen % t->outSize) : t->outSize;
        memcpy(dk + (b-1)*t->outSize, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
    secure_memset((volatile uint8_t *)saltb, 0, sizeof(saltb));
}

/* hashId out of [0,4] falls back to SHA-512 (HASH_TRAITS[0]) — matches the
 * original per-function switch/default behavior in write_vc_header. */
static const HashTraits* hash_traits_for(int hashId) {
    return (hashId >= 0 && hashId <= 4) ? &HASH_TRAITS[hashId] : &HASH_TRAITS[0];
}

/* ─── CRC-32 table (IEEE 802.3, reflected polynomial 0xEDB88320) ─────── */
/*
 * Initialised once when the .so is loaded (before any JNI call).
 * crc32_step() is the exact equivalent of VeraCrypt's CRCFUNC macro:
 *   #define CRCFUNC(crc,b)  (crc_32_tab[((int)(crc)^(b))&0xff]^((crc)>>8))
 */

static uint32_t s_crc32_tab[256];

__attribute__((constructor))
static void init_crc32_tab() {
    for (int i = 0; i < 256; i++) {
        uint32_t c = (uint32_t)i;
        for (int k = 0; k < 8; k++)
            c = (c >> 1) ^ ((c & 1u) ? 0xEDB88320UL : 0u);
        s_crc32_tab[i] = c;
    }
}

static inline uint32_t crc32_step(uint32_t crc, uint8_t b) {
    return s_crc32_tab[(crc ^ b) & 0xFF] ^ (crc >> 8);
}

/* Finalised CRC-32 of a buffer — used for VeraCrypt header integrity. */
static uint32_t crc32_buf(const uint8_t *data, size_t len) {
    uint32_t crc = 0xFFFFFFFFUL;
    for (size_t i = 0; i < len; i++) crc = crc32_step(crc, data[i]);
    return ~crc;
}

/* ─── VeraCrypt keyfile pool ─────────────────────────────────────────── */
/*
 * Exact match to VeraCrypt Keyfiles.cpp :: KeyFileProcess() + KeyFilesApply()
 *
 *  KeyFileProcess:
 *    crc = 0xFFFFFFFF (running, never finalised)
 *    for each byte b in keyfile (up to 1 MB):
 *        crc = CRCFUNC(crc, b)               ← table step, same as crc32_step
 *        pool[j % POOL_SIZE] += (crc >> 24)  ← high byte, addition not XOR
 *
 *  KeyFilesApply:
 *    if pwd_len < POOL_SIZE: pad password with zeros to POOL_SIZE
 *    for i in [0, POOL_SIZE): password[i] += pool[i]  ← byte addition
 *    pwd_len = max(pwd_len, POOL_SIZE)
 */
#define VC_KEYFILE_POOL_SIZE  64
#define VC_KEYFILE_MAX_READ   (1 * 1024 * 1024)   /* 1 MB cap (VeraCrypt MAX_KEY_FILE_SIZE) */
/* VeraCrypt MAX_PASSWORD = 128 for non-boot builds (see veracrypt/Common/Password.h).
   Keyfile pool (64 bytes) applies to Text[0..63]; bytes beyond pwd_len remain zero.
   PBKDF2 always receives exactly max(original_len, 64) bytes when a keyfile is used. */
#define VC_MAX_PWD_LEN        128

/* Process raw keyfile bytes into pool — separated for unit-testability.
   Matches VeraCrypt KeyFileProcess() exactly. */
static void vc_process_keyfile_buf(const uint8_t *data, size_t size,
                                    uint8_t pool[VC_KEYFILE_POOL_SIZE]) {
    uint32_t crc   = 0xFFFFFFFFUL;
    size_t   j     = 0;
    size_t   limit = (size > (size_t)VC_KEYFILE_MAX_READ) ? (size_t)VC_KEYFILE_MAX_READ : size;
    for (size_t i = 0; i < limit; i++) {
        crc          = crc32_step(crc, data[i]);
        pool[j    ] += (uint8_t)(crc >> 24);
        pool[j + 1] += (uint8_t)(crc >> 16);
        pool[j + 2] += (uint8_t)(crc >>  8);
        pool[j + 3] += (uint8_t)(crc      );
        j += 4;
        if (j >= VC_KEYFILE_POOL_SIZE) j = 0;
    }
}

/* Apply one or more keyfiles to the password buffer.
 * Matches VeraCrypt KeyFilesApply() exactly:
 *   - Each keyfile is processed independently (crc reset per file) into the shared pool.
 *   - The combined pool is applied to the password via byte addition once after all files.
 *   - Password is zero-extended to POOL_SIZE before pool application.
 */
static void apply_keyfiles_to_password(const std::vector<std::string>& paths,
                                        uint8_t *pwd_buf, int *pwd_len) {
    if (paths.empty()) return;

    uint8_t pool[VC_KEYFILE_POOL_SIZE] = {};   /* accumulates across all keyfiles */

    for (const auto& path : paths) {
        FILE *f = fopen(path.c_str(), "rb");
        if (!f) { LOGE("Keyfile: cannot open '%s'", path.c_str()); continue; }

        auto *kfData = static_cast<uint8_t*>(malloc(VC_KEYFILE_MAX_READ));
        if (!kfData) { fclose(f); LOGE("Keyfile: OOM"); continue; }
        size_t total = fread(kfData, 1, (size_t)VC_KEYFILE_MAX_READ, f);
        fclose(f);

        vc_process_keyfile_buf(kfData, total, pool);   /* crc restarts at 0xFFFFFFFF per call */
        memset(kfData, 0, total);
        free(kfData);
    }

    /* Zero-extend password to POOL_SIZE — VeraCrypt sets Length = MAX(original, POOL_SIZE) */
    if (*pwd_len < VC_KEYFILE_POOL_SIZE) {
        memset(pwd_buf + *pwd_len, 0, (size_t)(VC_KEYFILE_POOL_SIZE - *pwd_len));
        *pwd_len = VC_KEYFILE_POOL_SIZE;
    }

    /* Apply pool via byte addition (mod 256) */
    for (int i = 0; i < VC_KEYFILE_POOL_SIZE; i++)
        pwd_buf[i] = (uint8_t)((uint8_t)pwd_buf[i] + pool[i]);

    secure_memset(pool, 0, sizeof(pool));
}

/* Same as apply_keyfiles_to_password but reads from JNI byte arrays (no disk access).
 * jKeyfileData is an Array<ByteArray>? — null or empty means no-op. */
static void apply_keyfile_buffers(JNIEnv *env, jobjectArray jKeyfileData,
                                   uint8_t *pwd_buf, int *pwd_len) {
    if (!jKeyfileData) return;
    jsize count = env->GetArrayLength(jKeyfileData);
    if (count == 0) return;

    uint8_t pool[VC_KEYFILE_POOL_SIZE] = {};

    /* Never mutate the caller's Java array: GetByteArrayElements may or may
     * not hand back a pinned pointer into the live array (JVM-dependent), so
     * memset()-ing it in place either corrupts the caller's live ByteArray
     * (breaking mount retry with the same keyfiles — MountScreen.kt reuses
     * UI-state arrays) or silently fails to wipe, depending on the runtime.
     * Instead copy into a local heap buffer, process that, and wipe the copy.
     * Kotlin owns zeroing its own copies (see KeyfileEntry.zero()). */
    auto *local = static_cast<uint8_t*>(malloc((size_t)VC_KEYFILE_MAX_READ));
    if (!local) LOGE("apply_keyfile_buffers: OOM, keyfiles not applied");
    for (jsize i = 0; i < count && local; i++) {
        auto jBuf = (jbyteArray)env->GetObjectArrayElement(jKeyfileData, i);
        if (!jBuf) continue;
        jsize len = env->GetArrayLength(jBuf);
        size_t lim = (size_t)len < (size_t)VC_KEYFILE_MAX_READ
                     ? (size_t)len : (size_t)VC_KEYFILE_MAX_READ;
        env->GetByteArrayRegion(jBuf, 0, (jsize)lim, (jbyte*)local);
        if (!env->ExceptionCheck()) {
            vc_process_keyfile_buf(local, lim, pool);
        } else {
            env->ExceptionClear();
        }
        secure_memset((volatile uint8_t*)local, 0, lim);
        env->DeleteLocalRef(jBuf);
    }
    if (local) free(local);

    if (*pwd_len < VC_KEYFILE_POOL_SIZE) {
        memset(pwd_buf + *pwd_len, 0, (size_t)(VC_KEYFILE_POOL_SIZE - *pwd_len));
        *pwd_len = VC_KEYFILE_POOL_SIZE;
    }
    for (int i = 0; i < VC_KEYFILE_POOL_SIZE; i++)
        pwd_buf[i] = (uint8_t)((uint8_t)pwd_buf[i] + pool[i]);

    secure_memset(pool, 0, sizeof(pool));
}

/* ─── Big-endian helpers ─────────────────────────────────────────────── */

static void put_be32(uint8_t *b, uint32_t v) {
    b[0]=(v>>24)&0xFF; b[1]=(v>>16)&0xFF; b[2]=(v>>8)&0xFF; b[3]=v&0xFF;
}
static void put_be64(uint8_t *b, uint64_t v) {
    put_be32(b,   (uint32_t)(v >> 32));
    put_be32(b+4, (uint32_t)(v & 0xFFFFFFFF));
}
static uint32_t get_be32(const uint8_t *b) {
    return ((uint32_t)b[0]<<24)|((uint32_t)b[1]<<16)|((uint32_t)b[2]<<8)|b[3];
}
static uint64_t get_be64(const uint8_t *b) {
    return ((uint64_t)get_be32(b)<<32)|get_be32(b+4);
}

/* ─── /dev/urandom helper ────────────────────────────────────────────── */

static bool read_urandom(uint8_t *buf, size_t len) {
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    size_t done = 0;
    bool ok = true;
    while (done < len) {
        ssize_t n = read(fd, buf + done, len - done);
        if (n > 0) { done += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        ok = false; break;
    }
    close(fd);
    return ok;
}

/* ─── Random Pool Enrichment (user-collected entropy) ───────────────── */
/*
 * XOR-folds caller-supplied entropy (e.g. touch/accelerometer samples
 * collected by the UI during container creation) across a urandom-filled
 * buffer. XOR of a uniform urandom stream with ANY independent byte stream
 * stays uniform, so this can only add entropy, never subtract from it — even
 * if the caller's entropy turns out to be low-quality or attacker-influenced.
 * No-op when entropy is null/empty, matching the pre-existing behavior of
 * silently ignoring the (until now unused) entropy parameter. */
static void xor_fold_entropy(uint8_t *buf, size_t bufLen,
                              const uint8_t *entropy, size_t entropyLen) {
    if (!entropy || entropyLen == 0) return;
    for (size_t i = 0; i < bufLen; i++) buf[i] ^= entropy[i % entropyLen];
}

/* ─── Bulk I/O helpers ───────────────────────────────────────────────── */
/*
 * Loop over partial transfers, retry EINTR, false on error/EOF-short. Used
 * wherever a pwrite()/pread() return value was previously ignored or only
 * partially checked (random-fill loops, backup-header writes). Declared
 * (non-static, C linkage) in arcanum_impl.h so diskio.cpp's batched sector
 * I/O (stage 4) can reuse them too — kept in one place rather than
 * duplicated per translation unit.
 */
bool pread_all(int fd, void *buf, size_t len, long long off) {
    auto *p = static_cast<uint8_t*>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = pread(fd, p + done, len - done, (off_t)(off + (long long)done));
        if (n > 0) { done += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return false; /* error or short EOF */
    }
    return true;
}

bool write_all_at(int fd, const void *buf, size_t len, long long off) {
    const auto *p = static_cast<const uint8_t*>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = pwrite(fd, p + done, len - done, (off_t)(off + (long long)done));
        if (n > 0) { done += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

/* ─── Progress throttling ────────────────────────────────────────────── */
/*
 * The create fill loops, preallocate_fd's zero-fill fallback, and
 * fill_range_xts previously called report_progress() every 64/128 KB chunk —
 * hundreds of thousands of JNI CallVoidMethod round-trips for a multi-GB
 * container. monotonic_ms() (CLOCK_MONOTONIC, immune to wall-clock jumps)
 * plus ProgressThrottle cap that to roughly 10 reports/sec while still
 * letting the last chunk of each loop through unthrottled so the phase's
 * progress fraction visibly reaches its target instead of stalling a few
 * percent short. This does not touch the standalone frac=1.0 completion
 * reports fired after these loops return — those remain unconditional.
 */
static uint64_t monotonic_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000L);
}

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

/* ─── VeraCrypt header write ─────────────────────────────────────────── */
/*
 * Writes a 512-byte VeraCrypt-compatible header.
 * masterKey : n*64 bytes (one 64-byte slot per cipher in the cascade)
 * algId     : algorithm index into ALGORITHMS[]
 * hashAlg   : 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog
 */
static int write_vc_header(int fd, uint64_t fileOff,
                            uint64_t dataSz, uint64_t dataOff,
                            const uint8_t *masterKey,
                            int algId, int hashAlg,
                            const char *password, int pwd_len,
                            int pim = 0,
                            uint64_t hiddenVolSize = 0,
                            const uint8_t *existingSalt = nullptr) {
    if (algId < 0 || algId >= NUM_ALGORITHMS) return ERR_FS;

    uint8_t salt[VC_HEADER_SALT_SIZE];
    if (existingSalt)
        memcpy(salt, existingSalt, VC_HEADER_SALT_SIZE);
    else if (!read_urandom(salt, sizeof(salt))) {
        LOGE("[header] /dev/urandom failed — aborting header write");
        return ERR_RAND;
    }

    int n    = ALGORITHMS[algId].n;
    int mkLen = n * 64;

    /* Derive header key (mkLen bytes) using the chosen hash */
    uint32_t iters = vc_get_iterations(hashAlg, pim);
    SecureBuffer<192> derivedKey;
    pbkdf2_generic(hash_traits_for(hashAlg), (const uint8_t*)password, pwd_len,
                   salt, VC_HEADER_SALT_SIZE, iters, derivedKey.data(), mkLen);

    /* Build decrypted header body (448 bytes) */
    SecureBuffer<VC_HEADER_BODY_SIZE> body;
    body[0]='V'; body[1]='E'; body[2]='R'; body[3]='A';
    body[4] = (VC_HEADER_VERSION >> 8) & 0xFF;
    body[5] =  VC_HEADER_VERSION       & 0xFF;
    body[6] = (VC_MIN_REQUIRED_VERSION >> 8) & 0xFF;
    body[7] =  VC_MIN_REQUIRED_VERSION       & 0xFF;
    /* body[8..11]: CRC of master key area — written after key copy */
    /* body[12..27]: reserved zero */
    put_be64(body.data() + 28, hiddenVolSize);    /* hidden volume size (0 = none / this IS the hidden vol) */
    put_be64(body.data() + 36, dataSz);           /* volume size              */
    put_be64(body.data() + 44, dataOff);          /* data area offset         */
    put_be64(body.data() + 52, dataSz);           /* encrypted area length    */
    put_be32(body.data() + 64, VC_SECTOR_SIZE);   /* sector size              */
    /* body[68..191]: reserved */

    memcpy(body.data() + VC_MASTER_KEY_OFFSET, masterKey, (size_t)mkLen);

    uint32_t crc1 = crc32_buf(body.data() + 192, 256);
    put_be32(body.data() + 8, crc1);
    uint32_t crc2 = crc32_buf(body.data(), 188);
    put_be32(body.data() + 188, crc2);

    /* Encrypt body: forward 0..n-1 (c[0]=innermost first), VeraCrypt cascade key layout. */
    for (int i = 0; i < n; i++) {
        SecureBuffer<64> ck;
        build_cascade_key64(derivedKey.data(), n, i, ck.data());
        xts_crypt_temp(ALGORITHMS[algId].c[i], ck.data(), body.data(), VC_HEADER_BODY_SIZE, 0, true);
    }

    uint8_t rawHeader[VC_HEADER_SIZE] = {};
    memcpy(rawHeader, salt, VC_HEADER_SALT_SIZE);
    memcpy(rawHeader + VC_HEADER_BODY_OFFSET, body.data(), VC_HEADER_BODY_SIZE);

    ssize_t w = pwrite(fd, rawHeader, VC_HEADER_SIZE, (off_t)fileOff);
    return (w == VC_HEADER_SIZE) ? 0 : -1;
}

/* ─── VeraCrypt header authenticate ─────────────────────────────────── */

/* hi outside [0,4] leaves `out` untouched (matches the original per-hash
 * switch's default: break — callers zero-initialize `out` before calling). */
static void derive_header_key(int hi, const uint8_t *password, int pwd_len,
                               const uint8_t *salt, int pim, uint8_t out[192]) {
    if (hi < 0 || hi > 4) return;
    uint32_t iters = vc_get_iterations(hi, pim);
    pbkdf2_generic(&HASH_TRAITS[hi], password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192);
}

/* ─── Mount-progress callback helpers ───────────────────────────────── */

static const char* algo_name(int algId) {
    static const char* const N[] = {
        "AES-256-XTS", "Serpent-256-XTS", "Twofish-256-XTS",
        "Camellia-256-XTS", "Kuznyechik-256-XTS",
        "AES-Twofish", "AES-Twofish-Serpent", "Serpent-AES",
        "Serpent-Twofish-AES", "Twofish-Serpent",
        "Camellia-Kuznyechik", "Camellia-Serpent", "Kuznyechik-AES",
        "Kuznyechik-Serpent-Camellia", "Kuznyechik-Twofish"
    };
    return (algId >= 0 && algId < NUM_ALGORITHMS) ? N[algId] : "?";
}

static const char* hash_name(int hashId) {
    static const char* const N[] = { "SHA-512", "SHA-256", "Whirlpool", "Streebog", "BLAKE2s-256" };
    return (hashId >= 0 && hashId <= 4) ? N[hashId] : "?";
}

struct MountCb {
    JNIEnv    *env;
    jobject    listener;
    jmethodID  mid;
    int        attempt;
    int        total;
};

static jmethodID resolve_mount_mid(JNIEnv *env, jobject listener) {
    if (!listener) return nullptr;
    jclass cls = env->GetObjectClass(listener);
    if (!cls) return nullptr;
    jmethodID mid = env->GetMethodID(cls, "onTrying", "(Ljava/lang/String;Ljava/lang/String;II)V");
    env->DeleteLocalRef(cls);
    if (!mid && env->ExceptionCheck()) env->ExceptionClear();
    return mid;
}

static void report_trying(MountCb *cb, int algId, int hashId) {
    if (!cb || !cb->listener || !cb->mid) return;
    jstring jCipher = cb->env->NewStringUTF(algo_name(algId));
    jstring jPrf    = cb->env->NewStringUTF(hash_name(hashId));
    if (jCipher && jPrf)
        cb->env->CallVoidMethod(cb->listener, cb->mid, jCipher, jPrf, (jint)cb->attempt, (jint)cb->total);
    if (jCipher) cb->env->DeleteLocalRef(jCipher);
    if (jPrf)    cb->env->DeleteLocalRef(jPrf);
    if (cb->env->ExceptionCheck()) cb->env->ExceptionClear();
    cb->attempt++;
}

/*
 * Tries all 5 hashes × 15 cipher algorithms (75 combinations max).
 * hintAlgId / hintHashId (-1 = unknown): if both are valid, the matching
 * combination is tried first so re-mounting a known container is fast.
 * On success fills masterKey (n*64 bytes), outMkLen, outAlgId, dataSz, dataOff.
 */
/* Decrypts rawBody with the given derived key + cipher cascade into bodyOut,
 * then checks the "VERA" magic and both header CRCs. Shared by the hint
 * fast-path and the full-scan loop in read_vc_header (stage 0c) — previously
 * duplicated inline in both places. Returns false (wrong cipher/PRF guess —
 * caller should keep scanning) without wiping bodyOut; the caller wipes on
 * both outcomes since bodyOut always holds sensitive decrypted material. */
static bool try_decrypt_header(const uint8_t *rawBody, const uint8_t *derivedKey,
                               int algId, uint8_t bodyOut[VC_HEADER_BODY_SIZE]) {
    memcpy(bodyOut, rawBody, VC_HEADER_BODY_SIZE);
    int n = ALGORITHMS[algId].n;
    /* Decrypt: reverse n-1..0 (outermost c[n-1] first). */
    for (int ci = n - 1; ci >= 0; ci--) {
        SecureBuffer<64> ck;
        build_cascade_key64(derivedKey, n, ci, ck.data());
        xts_crypt_temp(ALGORITHMS[algId].c[ci], ck.data(), bodyOut, VC_HEADER_BODY_SIZE, 0, false);
    }
    if (bodyOut[0]!='V'||bodyOut[1]!='E'||bodyOut[2]!='R'||bodyOut[3]!='A') return false;
    if (get_be32(bodyOut + 188) != crc32_buf(bodyOut, 188))                return false;
    if (get_be32(bodyOut + 8)   != crc32_buf(bodyOut + 192, 256))          return false;
    return true;
}

/* Extracts geometry fields from a body that already passed try_decrypt_header.
 * Returns false if the sector-size field isn't VC_SECTOR_SIZE (stage 2b) —
 * unlike a magic/CRC failure this does NOT mean "wrong cipher guess": the
 * header is authentic (CRC-valid), just describes a geometry this build
 * doesn't support, so the caller must stop scanning and report unsupported
 * rather than trying more ciphers. */
static bool extract_header_geometry(const uint8_t *body, uint64_t *hiddenVolSize,
                                    uint64_t *dataSz, uint64_t *dataOff) {
    if (get_be32(body + 64) != VC_SECTOR_SIZE) return false;
    if (hiddenVolSize) *hiddenVolSize = get_be64(body + 28);
    if (dataSz)        *dataSz        = get_be64(body + 36);
    if (dataOff)       *dataOff       = get_be64(body + 44);
    return true;
}

static int read_vc_header(int fd, uint64_t fileOff,
                          const char *password, int pwd_len,
                          uint8_t *masterKey, int *outMkLen,
                          uint64_t *dataSz, uint64_t *dataOff,
                          int *outAlgId, int *outHashId = nullptr,
                          int pim = 0,
                          uint64_t *outHiddenVolSize = nullptr,
                          int hintAlgId = -1, int hintHashId = -1,
                          MountCb *mountCb = nullptr) {
    uint8_t rawHeader[VC_HEADER_SIZE];
    if (pread(fd, rawHeader, VC_HEADER_SIZE, (off_t)fileOff) != VC_HEADER_SIZE) return ERR_READ;

    const uint8_t *salt = rawHeader;          /* first 64 bytes */
    const uint8_t *rawBody = rawHeader + VC_HEADER_BODY_OFFSET;

    static const int NUM_HASHES = 5;

    /* Progress denominator: a hash hint restricts the scan to one hash's ciphers. */
    bool hashHinted = (hintHashId >= 0 && hintHashId < NUM_HASHES);
    if (mountCb) { mountCb->attempt = 1; mountCb->total = hashHinted ? NUM_ALGORITHMS : NUM_HASHES * NUM_ALGORITHMS; }

    /* allDerivedKeys is declared up front (rather than after the hint attempt,
     * as before) so the hint fast-path below can derive straight into its
     * slot instead of into a separate stack buffer — the full scan that
     * follows a failed hint then reuses that derivation instead of redoing
     * the same 500k-iteration PBKDF2 a second time. derivedFlags tracks which
     * hash slots are already populated. */
    uint8_t allDerivedKeys[NUM_HASHES][192];
    memset(allDerivedKeys, 0, sizeof(allDerivedKeys));
    /* Wipes the whole 2D array at every return point below (hint-path,
     * full-scan success/unsupported, and the final wrong-password fallback)
     * — replaces the four manual secure_memset(allDerivedKeys, ...) calls
     * that used to be repeated on each of those exit paths. */
    SecureWipe<uint8_t[NUM_HASHES][192]> _wipeAllKeys(allDerivedKeys);
    bool derivedFlags[NUM_HASHES] = { false, false, false, false, false };

    /* ── Fast path: try hinted (hash, algorithm) combination first ── */
    bool hintTried = false;
    if (hintHashId >= 0 && hintHashId < NUM_HASHES &&
        hintAlgId  >= 0 && hintAlgId  < NUM_ALGORITHMS) {
        hintTried = true;
        report_trying(mountCb, hintAlgId, hintHashId);
        derive_header_key(hintHashId, (const uint8_t*)password, pwd_len, salt, pim,
                          allDerivedKeys[hintHashId]);
        derivedFlags[hintHashId] = true;

        SecureBuffer<VC_HEADER_BODY_SIZE> body;
        bool ok = try_decrypt_header(rawBody, allDerivedKeys[hintHashId], hintAlgId, body.data());
        if (ok) {
            int n = ALGORITHMS[hintAlgId].n;
            int mkLen = n * 64;
            if (!extract_header_geometry(body.data(), outHiddenVolSize, dataSz, dataOff)) {
                return ERR_UNSUPPORTED;
            }
            memcpy(masterKey, body.data() + VC_MASTER_KEY_OFFSET, (size_t)mkLen);
            if (outMkLen)  *outMkLen  = mkLen;
            if (outAlgId)  *outAlgId  = hintAlgId;
            if (outHashId) *outHashId = hintHashId;
            return ERR_OK;
        }
        /* Wrong cipher guess — allDerivedKeys[hintHashId] stays populated
         * (and NOT wiped here) so the full scan below can reuse it. `body`
         * is still wiped (by SecureBuffer's destructor) at the end of this
         * `if` block, same timing as the manual wipe it replaces. */
    }

    /* ── Full scan: derive remaining keys (1 if hash hinted, up to 5 in
     * parallel otherwise), skipping any hash already derived by the hint
     * fast-path above, then check ciphers. ── */
    bool singleHashHint = hashHinted;
    if (singleHashHint) {
        /* Hash hint: one derivation instead of five — mirrors VeraCrypt selected_pkcs5_prf.
         * Skip if the hint fast-path already derived this exact hash (hintAlgId
         * was valid too, so derivedFlags[hintHashId] is already true). */
        if (!derivedFlags[hintHashId]) {
            derive_header_key(hintHashId, (const uint8_t*)password, pwd_len,
                              salt, pim, allDerivedKeys[hintHashId]);
            derivedFlags[hintHashId] = true;
        }
    } else {
        /* No hash hint: run all NOT-YET-derived hashes concurrently (matches
         * VeraCrypt thread pool); a hash already derived by the hint fast-path
         * is skipped entirely — no thread spawned, no redundant PBKDF2.
         * Exception safety: if thread creation fails (EAGAIN) partway through,
         * the threads that did start are still joined, and every hash that
         * ended up neither pre-derived nor thread-started falls back to
         * sequential derivation, so allDerivedKeys is always fully populated. */
        std::thread kdfThreads[NUM_HASHES];
        bool threadStarted[NUM_HASHES] = { false, false, false, false, false };
        try {
            for (int hi = 0; hi < NUM_HASHES; hi++) {
                if (derivedFlags[hi]) continue;
                kdfThreads[hi] = std::thread([hi, password, pwd_len, salt, pim, &allDerivedKeys]() {
                    derive_header_key(hi, (const uint8_t*)password, pwd_len,
                                      salt, pim, allDerivedKeys[hi]);
                });
                threadStarted[hi] = true;
            }
        } catch (...) {}
        for (int hi = 0; hi < NUM_HASHES; hi++)
            if (threadStarted[hi]) kdfThreads[hi].join();
        for (int hi = 0; hi < NUM_HASHES; hi++) {
            if (!derivedFlags[hi] && !threadStarted[hi])
                derive_header_key(hi, (const uint8_t*)password, pwd_len, salt, pim, allDerivedKeys[hi]);
        }
    }

    /* Check cipher combinations — restrict to hinted hash if one was given */
    int hiStart = singleHashHint ? hintHashId : 0;
    int hiEnd   = singleHashHint ? hintHashId + 1 : NUM_HASHES;
    for (int hi = hiStart; hi < hiEnd; hi++) {
        for (int ai = 0; ai < NUM_ALGORITHMS; ai++) {
            if (hintTried && hi == hintHashId && ai == hintAlgId) continue;

            report_trying(mountCb, ai, hi);

            SecureBuffer<VC_HEADER_BODY_SIZE> body;
            bool ok = try_decrypt_header(rawBody, allDerivedKeys[hi], ai, body.data());
            if (!ok) {
                continue; /* body wiped by its destructor at the end of this iteration */
            }

            /* CRC-valid header: authentic, so a bad sector size stops the scan
             * outright (ERR_UNSUPPORTED) rather than being treated as a wrong
             * cipher/PRF guess. */
            if (!extract_header_geometry(body.data(), outHiddenVolSize, dataSz, dataOff)) {
                return ERR_UNSUPPORTED;
            }

            /* Extract master key */
            int n = ALGORITHMS[ai].n;
            int mkLen = n * 64;
            memcpy(masterKey, body.data() + VC_MASTER_KEY_OFFSET, (size_t)mkLen);
            if (outMkLen)  *outMkLen  = mkLen;
            if (outAlgId)  *outAlgId  = ai;
            if (outHashId) *outHashId = hi;

            return ERR_OK;
        }
    }

    return ERR_WRONG_PASSWORD;
}

/* ─── JNI_OnLoad: cached classes / method IDs ───────────────────────── */
/*
 * nativeListFiles previously did FindClass("zip/arcanum/crypto/NativeFileInfo")
 * + GetMethodID on every call, and utf8_to_jstring's non-BMP fallback path
 * looked up java/lang/String the same way (via a function-local static, so
 * only the FIRST call paid for it, but that lookup pattern is redundant with
 * this one). Both classes are resolved once here, at load time, and held as
 * GlobalRefs for the life of the process. Call sites fall back to a per-call
 * lookup if the cache failed to populate (e.g. a JNI_OnLoad edge case) so
 * behavior is identical either way, just slower on the fallback path. */
struct JniCache {
    jclass    fileInfoCls  = nullptr;
    jmethodID fileInfoCtor = nullptr;   /* NativeFileInfo(String,String,J,Z,J) */
    jclass    stringCls    = nullptr;
    jmethodID stringCtor   = nullptr;   /* String(byte[], String) */
    jstring   utf8Name     = nullptr;   /* "UTF-8", interned as a GlobalRef */
};
static JniCache g_jniCache;

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

/* Returns true iff every byte in s forms a valid UTF-8 sequence.
   NewStringUTF aborts the process on invalid Modified UTF-8, so we must
   validate before calling it. */
static bool is_valid_utf8(const char *s) {
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
 *  - Passwords containing non-BMP characters (e.g. emoji) would otherwise
 *    derive a different key than desktop VeraCrypt, which hashes real UTF-8.
 *  - File/directory paths with such characters wouldn't round-trip through
 *    FatFs, which expects standard UTF-8 (FF_LFN_UNICODE=2).
 * Unpaired surrogates are replaced with U+FFFD. No wiping here — callers that
 * hold password contents are responsible for that (see StringWiper). */
static std::string jstring_to_string(JNIEnv *env, jstring js) {
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

static jstring utf8_to_jstring(JNIEnv *env, const char *s) {
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
    if (!bytes) return nullptr;
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
        jlong sizeBytes, const std::string &password,
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
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen);
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
        jstring jPassword, jobjectArray jKeyfilePaths,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray jEntropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);
    if (path.empty() || password.empty()) return ERR_FILE;

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
                               sizeBytes, password, keyfilePaths,
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
        jstring jPassword, jobjectArray jKeyfilePaths,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray jEntropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);
    if (password.empty()) return ERR_FILE;

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
                               sizeBytes, password, keyfilePaths,
                               algorithm, hashAlg, filesystem, quickFormat,
                               progressListener, pim,
                               entropy.empty() ? nullptr : entropy.data(), entropy.size());
}

/* ─── Open-container core ───────────────────────────────────────────── */
/*
 * Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd:
 * every failure path closes it; on success it lives in ContainerCtx until
 * nativeCloseContainer. Passwords arrive as std::strings owned (and wiped
 * via StringWiper) by the wrappers; keyfile data stays as JNI arrays since
 * apply_keyfile_buffers() reads them without mutation.
 */
static jlong do_open_container(
        JNIEnv *env, int fdIn, const char *logTag,
        const std::string &password, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        const std::string &hiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener, jboolean readOnly)
{
    /* Owns fd on every failure path (closed by the destructor). On success
     * the fd is handed to the registry via ContainerCtx — release() right
     * before that handoff, at the single spot marked below. */
    UniqueFd fd(fdIn);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfile_buffers(env, jKeyfileData, effPwd.data(), &effPwdLen);

    /* Prepare hidden-volume credentials for boundary derivation */
    SecureBuffer<VC_MAX_PWD_LEN> hidEffPwd;
    int hidEffPwdLen = (int)hiddenPassword.size();
    if (hidEffPwdLen > VC_MAX_PWD_LEN) hidEffPwdLen = VC_MAX_PWD_LEN;
    if (hidEffPwdLen > 0) {
        memcpy(hidEffPwd.data(), hiddenPassword.c_str(), (size_t)hidEffPwdLen);
        apply_keyfile_buffers(env, jProtectHiddenKeyfileData, hidEffPwd.data(), &hidEffPwdLen);
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
    if (dataSz == 0 || dataSz % VC_SECTOR_SIZE != 0 ||
        dataOff % VC_SECTOR_SIZE != 0 || dataOff + dataSz > fileSize) {
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
        jint safFd, jstring jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jstring jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener, jboolean readOnly)
{
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    std::string hiddenPassword = jProtectHiddenPassword ? jstring_to_string(env, jProtectHiddenPassword) : "";
    StringWiper _wipe_hiddenPassword(hiddenPassword);

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/open] dup failed: errno=%d", errno); return (jlong)ERR_FILE; }

    return do_open_container(env, fd, "fd/open",
                             password, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hiddenPassword, jProtectHiddenKeyfileData, protectHiddenPim,
                             mountProgressListener, readOnly);
}

/* ─── JNI: nativeOpenContainer ──────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jstring jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jstring jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener, jboolean readOnly)
{
    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    std::string hiddenPassword = jProtectHiddenPassword ? jstring_to_string(env, jProtectHiddenPassword) : "";
    StringWiper _wipe_hiddenPassword(hiddenPassword);

    /* Open read-only at the OS level for read-only mounts so the kernel itself
     * refuses any write, independent of the ctx->readOnly / disk_write guards.
     * (The SAF variant gets an already-read-only fd: Kotlin opens the PFD "r".) */
    int fd = open(path.c_str(), readOnly ? O_RDONLY : O_RDWR);
    if (fd < 0) { LOGE("[open] Cannot open: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno)); return (jlong)ERR_FILE; }

    return do_open_container(env, fd, "open",
                             password, jKeyfileData, pim, algorithm, hashAlgorithm,
                             hiddenPassword, jProtectHiddenKeyfileData, protectHiddenPim,
                             mountProgressListener, readOnly);
}

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
        const std::string &outerPassword, const std::vector<std::string> &outerKeyfilePaths, jint outerPim,
        const std::string &hiddenPassword, const std::vector<std::string> &hiddenKeyfilePaths, jint hiddenPim,
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
    int outerEffPwdLen = (int)outerPassword.size();
    if (outerEffPwdLen > VC_MAX_PWD_LEN) outerEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(outerEffPwd.data(), outerPassword.c_str(), (size_t)outerEffPwdLen);
    apply_keyfiles_to_password(outerKeyfilePaths, outerEffPwd.data(), &outerEffPwdLen);

    /* ── Hidden effective password ── */
    SecureBuffer<VC_MAX_PWD_LEN> hiddenEffPwd;
    int hiddenEffPwdLen = (int)hiddenPassword.size();
    if (hiddenEffPwdLen > VC_MAX_PWD_LEN) hiddenEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(hiddenEffPwd.data(), hiddenPassword.c_str(), (size_t)hiddenEffPwdLen);
    apply_keyfiles_to_password(hiddenKeyfilePaths, hiddenEffPwd.data(), &hiddenEffPwdLen);

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
        jstring jOuterPassword, jobjectArray jOuterKeyfilePaths, jint outerPim,
        jstring jHiddenPassword, jobjectArray jHiddenKeyfilePaths, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jboolean /*quickFormat*/,
        jbyteArray jEntropyBytes,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    std::string path           = jstring_to_string(env, jPath);
    std::string outerPassword  = jstring_to_string(env, jOuterPassword);
    std::string hiddenPassword = jstring_to_string(env, jHiddenPassword);
    StringWiper _wipe_outerPassword(outerPassword);
    StringWiper _wipe_hiddenPassword(hiddenPassword);
    auto outerKeyfilePaths     = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths    = jstringArray_to_vector(env, jHiddenKeyfilePaths);

    if (path.empty() || outerPassword.empty() || hiddenPassword.empty()) return ERR_FILE;

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
                                   outerPassword, outerKeyfilePaths, outerPim,
                                   hiddenPassword, hiddenKeyfilePaths, hiddenPim,
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
        jstring jOuterPassword, jobjectArray jOuterKeyfilePaths, jint outerPim,
        jstring jHiddenPassword, jobjectArray jHiddenKeyfilePaths, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jboolean /*quickFormat*/,
        jbyteArray jEntropyBytes,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    std::string outerPassword  = jstring_to_string(env, jOuterPassword);
    std::string hiddenPassword = jstring_to_string(env, jHiddenPassword);
    StringWiper _wipe_outerPassword(outerPassword);
    StringWiper _wipe_hiddenPassword(hiddenPassword);
    auto outerKeyfilePaths     = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths    = jstringArray_to_vector(env, jHiddenKeyfilePaths);

    if (outerPassword.empty() || hiddenPassword.empty()) return ERR_FILE;

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
                                   outerPassword, outerKeyfilePaths, outerPim,
                                   hiddenPassword, hiddenKeyfilePaths, hiddenPim,
                                   hiddenAlgorithm, hiddenHashAlg, progressListener,
                                   entropy.empty() ? nullptr : entropy.data(), entropy.size());
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

/* ─── Header wipe helper ─────────────────────────────────────────────── */
/* Overwrites the 512-byte header at fileOff with random data for wipeCount
   passes, then writes the new header encrypted with the new credentials.
   extraEntropy/extraEntropyLen: optional user-collected bytes XOR'd into the
   new salt before writing (Random Pool Enrichment). Pass nullptr/0 to skip. */
static int wipe_and_rewrite_header(int fd, uint64_t fileOff,
                                    uint64_t dataSz, uint64_t dataOff,
                                    const uint8_t *masterKey, int algId, int newHashAlg,
                                    const char *newPwd, int newPwdLen, int newPim,
                                    uint64_t hiddenVolSize, int wipePassCount,
                                    const uint8_t* extraEntropy = nullptr,
                                    size_t extraEntropyLen = 0) {
    uint8_t wipeBuf[VC_HEADER_SIZE];
    /* Random-fill filler for the wipe passes, not secret material (nor is the
     * 0xAA no-urandom fallback), so this stays a plain buffer with the
     * existing plain memset — not a SecureBuffer candidate. */
    UniqueFd rfd(open("/dev/urandom", O_RDONLY | O_CLOEXEC));
    for (int pass = 0; pass < wipePassCount; pass++) {
        if (rfd.ok()) {
            size_t got = 0;
            while (got < VC_HEADER_SIZE) {
                ssize_t r = read(rfd.get(), wipeBuf + got, VC_HEADER_SIZE - got);
                if (r > 0) { got += (size_t)r; continue; }
                if (r < 0 && errno == EINTR) continue;
                break;
            }
        } else {
            memset(wipeBuf, 0xAA, VC_HEADER_SIZE);
        }
        /* Check write/sync so a failing pass doesn't silently defeat wipe guarantee */
        ssize_t written = pwrite(fd, wipeBuf, VC_HEADER_SIZE, (off_t)fileOff);
        if (written != VC_HEADER_SIZE) {
            memset(wipeBuf, 0, sizeof(wipeBuf));
            return ERR_FILE;   /* rfd closed by UniqueFd's destructor */
        }
        fdatasync(fd);
    }
    memset(wipeBuf, 0, sizeof(wipeBuf));
    rfd.reset();   /* explicit: done with /dev/urandom before the (possibly slow) header write below */

    /* Generate new salt; XOR in user entropy if provided */
    SecureBuffer<VC_HEADER_SALT_SIZE> newSalt;
    if (!read_urandom(newSalt.data(), newSalt.size())) return ERR_RAND;
    xor_fold_entropy(newSalt.data(), newSalt.size(), extraEntropy, extraEntropyLen);
    int ret = write_vc_header(fd, fileOff, dataSz, dataOff,
                              masterKey, algId, newHashAlg,
                              newPwd, newPwdLen, newPim, hiddenVolSize, newSalt.data());
    /* Stage 2d: fdatasync the replacement header too (the wipe passes above
     * already do). Without this, a power cut between the write() returning
     * and the data actually reaching disk could leave the header wiped
     * (random garbage) with no valid replacement — the container becomes
     * unrecoverable even though write_vc_header() reported success. */
    if (ret == 0) fdatasync(fd);
    return ret;
}

/* ─── Change-password core ──────────────────────────────────────────── */
/* Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_change_password(
        JNIEnv *env, int fdIn,
        const std::string &oldPassword, const std::vector<std::string> &oldKeyfilePaths, jint oldPim,
        const std::string &newPassword, const std::vector<std::string> &newKeyfilePaths,
        jint newHashAlg, jint newPim, jint wipePassCount, jbyteArray jExtraEntropy)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);

    /* Build old effective password (password + keyfile pool) */
    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = (int)oldPassword.size();
    if (oldEffPwdLen > VC_MAX_PWD_LEN) oldEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(oldEffPwd.data(), oldPassword.c_str(), (size_t)oldEffPwdLen);
    apply_keyfiles_to_password(oldKeyfilePaths, oldEffPwd.data(), &oldEffPwdLen);

    /* Build new effective password */
    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = (int)newPassword.size();
    if (newEffPwdLen > VC_MAX_PWD_LEN) newEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(newEffPwd.data(), newPassword.c_str(), (size_t)newEffPwdLen);
    apply_keyfiles_to_password(newKeyfilePaths, newEffPwd.data(), &newEffPwdLen);

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

    /* oldPassword/newPassword contents are now in effPwd buffers; the wrappers'
     * StringWipers wipe the std::string storage at scope exit. */

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
        jstring jOldPassword, jobjectArray jOldKeyfilePaths, jint oldPim,
        jstring jNewPassword, jobjectArray jNewKeyfilePaths, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy)
{
    std::string path        = jstring_to_string(env, jPath);
    std::string oldPassword = jstring_to_string(env, jOldPassword);
    std::string newPassword = jstring_to_string(env, jNewPassword);
    StringWiper _wipe_oldPassword(oldPassword);
    StringWiper _wipe_newPassword(newPassword);
    auto oldKeyfilePaths    = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths    = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (path.empty() || newPassword.empty()) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd,
                              oldPassword, oldKeyfilePaths, oldPim,
                              newPassword, newKeyfilePaths,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy);
}

/* ─── JNI: nativeChangePasswordFd ───────────────────────────────────── */
/* SAF variant: takes an open file descriptor instead of a path.         */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangePasswordFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jstring jOldPassword, jobjectArray jOldKeyfilePaths, jint oldPim,
        jstring jNewPassword, jobjectArray jNewKeyfilePaths, jint newHashAlg, jint newPim,
        jint wipePassCount, jbyteArray jExtraEntropy)
{
    std::string oldPassword = jstring_to_string(env, jOldPassword);
    std::string newPassword = jstring_to_string(env, jNewPassword);
    StringWiper _wipe_oldPassword(oldPassword);
    StringWiper _wipe_newPassword(newPassword);
    auto oldKeyfilePaths    = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths    = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (newPassword.empty()) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_password(env, fd,
                              oldPassword, oldKeyfilePaths, oldPim,
                              newPassword, newKeyfilePaths,
                              newHashAlg, newPim, wipePassCount, jExtraEntropy);
}


/* ─── Change-keyfile core ───────────────────────────────────────────── */
/* Re-encrypts the container header with a new keyfile set (password unchanged).
   extraEntropy: user-collected touch bytes XOR'd into the new salt.
   Shared by the path and SAF-fd JNI wrappers below. Takes ownership of fd. */
static jint do_change_keyfile(
        JNIEnv *env, int fdIn,
        const std::string &password, const std::vector<std::string> &oldKeyfilePaths, jint pim,
        const std::vector<std::string> &newKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    /* fd is always closed by this function on every path. */
    UniqueFd fd(fdIn);
    /* Pinned after fd acquisition so every exit path below releases it
     * (ScopedArrayPin's destructor does this automatically now). */
    ScopedArrayPin entropyPin(env, jExtraEntropy);

    SecureBuffer<VC_MAX_PWD_LEN> oldEffPwd;
    int oldEffPwdLen = (int)password.size();
    if (oldEffPwdLen > VC_MAX_PWD_LEN) oldEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(oldEffPwd.data(), password.c_str(), (size_t)oldEffPwdLen);
    apply_keyfiles_to_password(oldKeyfilePaths, oldEffPwd.data(), &oldEffPwdLen);

    SecureBuffer<VC_MAX_PWD_LEN> newEffPwd;
    int newEffPwdLen = (int)password.size();
    if (newEffPwdLen > VC_MAX_PWD_LEN) newEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(newEffPwd.data(), password.c_str(), (size_t)newEffPwdLen);
    apply_keyfiles_to_password(newKeyfilePaths, newEffPwd.data(), &newEffPwdLen);

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
        jstring jPassword, jobjectArray jOldKeyfilePaths, jint pim,
        jobjectArray jNewKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    if (path.empty()) return ERR_FILE;

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, password, oldKeyfilePaths, pim,
                             newKeyfilePaths, newHashAlg, jExtraEntropy);
}

/* ─── JNI: nativeChangeKeyfileFd ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeChangeKeyfileFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd,
        jstring jPassword, jobjectArray jOldKeyfilePaths, jint pim,
        jobjectArray jNewKeyfilePaths, jint newHashAlg,
        jbyteArray jExtraEntropy)
{
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto oldKeyfilePaths = jstringArray_to_vector(env, jOldKeyfilePaths);
    auto newKeyfilePaths = jstringArray_to_vector(env, jNewKeyfilePaths);

    int fd = dup((int)safFd);
    if (fd < 0) return ERR_FILE;

    return do_change_keyfile(env, fd, password, oldKeyfilePaths, pim,
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
        const std::string &password, const std::vector<std::string> &keyfilePaths, jint pim,
        const char *outputPath, int safOutputFd)
{
    UniqueFd volFd(volFdIn);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen);

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
        jstring jVolumePath, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim, jstring jOutputPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string outputPath = jstring_to_string(env, jOutputPath);
    std::string password   = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int fd = open(volumePath.c_str(), O_RDONLY);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, password, keyfilePaths, pim,
                                   outputPath.c_str(), -1);
}

/* ─── JNI: nativeBackupVolumeHeaderFd ──────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeBackupVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim, jint safOutputFd)
{
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int fd = dup((int)safVolumeFd);
    if (fd < 0) return ERR_FILE;

    return do_backup_volume_header(env, fd, password, keyfilePaths, pim,
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
        const std::string &password, const std::vector<std::string> &keyfilePaths, jint pim,
        jboolean fromExternal, const char *backupPath, int safBackupFd)
{
    /* Unlike do_backup_volume_header, volFd is needed for the whole function
     * (the restore writes back into it), so it's owned for the whole scope. */
    UniqueFd volFd(volFdIn);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen);

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
        jstring jVolumePath, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jboolean fromExternal, jstring jBackupPath)
{
    std::string volumePath = jstring_to_string(env, jVolumePath);
    std::string backupPath = jstring_to_string(env, jBackupPath);
    std::string password   = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int volFd = open(volumePath.c_str(), O_RDWR);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, password, keyfilePaths, pim,
                                    fromExternal, backupPath.c_str(), -1);
}

/* ─── JNI: nativeRestoreVolumeHeaderFd ─────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRestoreVolumeHeaderFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safVolumeFd, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jboolean fromExternal, jint safBackupFd)
{
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths = jstringArray_to_vector(env, jKeyfilePaths);

    int volFd = dup((int)safVolumeFd);
    if (volFd < 0) return ERR_FILE;

    return do_restore_volume_header(env, volFd, password, keyfilePaths, pim,
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
        jstring jPath, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jlong newSizeBytes, jobject progressListener)
{
    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);
    if (path.empty()) return ERR_FILE;

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen);

    UniqueFd fd(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (!fd.ok()) return ERR_FILE;

    return do_expand_volume(fd.get(), effPwd.data(), effPwdLen, (int)pim, (uint64_t)newSizeBytes, env, progressListener);
    /* effPwd wiped, fd closed — by their destructors. */
}

/* ─── JNI: nativeExpandVolumeFd ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeExpandVolumeFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jstring jPassword,
        jobjectArray jKeyfilePaths, jint pim,
        jlong newSizeBytes, jobject progressListener)
{
    std::string password = jstring_to_string(env, jPassword);
    StringWiper _wipe_password(password);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);

    SecureBuffer<VC_MAX_PWD_LEN> effPwd;
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd.data(), password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd.data(), &effPwdLen);

    UniqueFd fd(dup((int)safFd));
    if (!fd.ok()) return ERR_FILE;

    return do_expand_volume(fd.get(), effPwd.data(), effPwdLen, (int)pim, (uint64_t)newSizeBytes, env, progressListener);
    /* effPwd wiped, fd closed — by their destructors. */
}
