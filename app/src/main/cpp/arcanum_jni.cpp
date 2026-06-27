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
#include <android/log.h>
#include <mutex>

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
#else
#  define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
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
    if (pim > 9999) pim = 9999; /* clamp: prevents uint32_t overflow in iteration formula */
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
};

static std::unordered_map<int, ContainerCtx*> g_ctxMap;
// FatFs is not thread-safe (FF_FS_REENTRANT 0); all f_* calls must hold this lock.
static std::mutex g_fatfs_mutex;

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
            aes_encrypt_key256(key64,    &k1enc);
            aes_decrypt_key256(key64,    &k1dec);
            aes_encrypt_key256(key64+32, &k2enc);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1enc, (uint8_t*)&k2enc, CIPHER_AES);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&k1dec, (uint8_t*)&k2enc, CIPHER_AES);
            memset(&k1enc, 0, sizeof(k1enc));
            memset(&k1dec, 0, sizeof(k1dec));
            memset(&k2enc, 0, sizeof(k2enc));
            break;
        }
        case CIPHER_SERPENT: {
            uint8_t ks1[SERPENT_KS_SIZE], ks2[SERPENT_KS_SIZE];
            serpent_set_key(key64,    ks1);
            serpent_set_key(key64+32, ks2);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1, ks2, CIPHER_SERPENT);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1, ks2, CIPHER_SERPENT);
            memset(ks1, 0, sizeof(ks1));
            memset(ks2, 0, sizeof(ks2));
            break;
        }
        case CIPHER_TWOFISH: {
            TwofishInstance k1, k2;
            u4byte k1b[8], k2b[8];
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
            memset(&k1, 0, sizeof(k1));
            memset(&k2, 0, sizeof(k2));
            break;
        }
        case CIPHER_CAMELLIA: {
            uint8_t ks1[CAMELLIA_KS_SIZE], ks2[CAMELLIA_KS_SIZE];
            camellia_set_key(key64,    ks1);
            camellia_set_key(key64+32, ks2);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1, ks2, CIPHER_CAMELLIA);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 ks1, ks2, CIPHER_CAMELLIA);
            memset(ks1, 0, sizeof(ks1));
            memset(ks2, 0, sizeof(ks2));
            break;
        }
        case CIPHER_KUZNYECHIK: {
            kuznyechik_kds ks1, ks2;
            kuznyechik_set_key(key64,    &ks1);
            kuznyechik_set_key(key64+32, &ks2);
            if (enc)
                EncryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks1, (uint8_t*)&ks2, CIPHER_KUZNYECHIK);
            else
                DecryptBufferXTS(buf, (TC_LARGEST_COMPILER_UINT)len, &dataUnit, 0,
                                 (uint8_t*)&ks1, (uint8_t*)&ks2, CIPHER_KUZNYECHIK);
            memset(&ks1, 0, sizeof(ks1));
            memset(&ks2, 0, sizeof(ks2));
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

static int alloc_drive(int fd, uint64_t dataOff, uint64_t sectors,
                       const uint8_t *masterKey, int algId, int hashId = 0,
                       bool isHidden = false, uint64_t hiddenBoundary = 0) {
    if (algId < 0 || algId >= NUM_ALGORITHMS) return -1;
    for (int i = 0; i < MAX_DRIVES; i++) {
        if (!g_drives[i].active) {
            g_drives[i].fd              = fd;
            g_drives[i].dataOffset      = dataOff;
            g_drives[i].sectorCount     = sectors;
            g_drives[i].active          = true;
            g_drives[i].algId           = algId;
            g_drives[i].hashId          = hashId;
            g_drives[i].isHidden        = isHidden;
            g_drives[i].hiddenBoundary  = hiddenBoundary;

            auto *ctx = static_cast<GenCipherCtx*>(malloc(sizeof(GenCipherCtx)));
            if (!ctx) { g_drives[i].active = false; return -1; }
            ctx->num = ALGORITHMS[algId].n;
            for (int j = 0; j < ctx->num; j++) {
                uint8_t ck[64];
                build_cascade_key64(masterKey, ctx->num, j, ck);
                init_layer_ks(&ctx->layers[j], ALGORITHMS[algId].c[j], ck);
                secure_memset(ck, 0, sizeof(ck));
            }
            g_drives[i].cipherCtx = ctx;
            return i;
        }
    }
    return -1;
}

static void free_drive(int pdrv) {
    if (pdrv < 0 || pdrv >= MAX_DRIVES) return;
    if (g_drives[pdrv].cipherCtx) {
        memset(g_drives[pdrv].cipherCtx, 0, sizeof(GenCipherCtx));
        free(g_drives[pdrv].cipherCtx);
    }
    memset(&g_drives[pdrv], 0, sizeof(DriveContext));
    g_drives[pdrv].active = false;
}

/* ─── HMAC-SHA512 / PBKDF2-SHA512 ───────────────────────────────────── */

static void hmac_sha512(const uint8_t *key, int klen,
                        const uint8_t *msg, size_t mlen,
                        uint8_t out[64]) {
    uint8_t k[128] = {};
    uint8_t ipad[128], opad[128];
    sha512_ctx ctx;
    if (klen > 128) sha512(k, key, (uint_64t)klen);
    else            memcpy(k, key, (size_t)klen);
    for (int i = 0; i < 128; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    sha512_begin(&ctx);
    sha512_hash(ipad, 128, &ctx);
    sha512_hash(msg, (uint_64t)mlen, &ctx);
    sha512_end(out, &ctx);
    sha512_begin(&ctx);
    sha512_hash(opad, 128, &ctx);
    sha512_hash(out, 64, &ctx);
    sha512_end(out, &ctx);
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

static void pbkdf2_sha512(const uint8_t *pwd, int plen,
                          const uint8_t *salt, int slen,
                          uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 63) / 64;
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[64], T[64];
        hmac_sha512(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 64);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_sha512(pwd, plen, U, 64, U);
            for (int j = 0; j < 64; j++) T[j] ^= U[j];
        }
        secure_memset((volatile uint8_t *)saltb, 0, (size_t)(slen + 4));
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
}

/* ─── HMAC-SHA256 / PBKDF2-SHA256 ───────────────────────────────────── */

static void hmac_sha256(const uint8_t *key, int klen,
                        const uint8_t *msg, size_t mlen,
                        uint8_t out[32]) {
    uint8_t k[64] = {};
    uint8_t ipad[64], opad[64];
    sha256_ctx ctx;
    if (klen > 64) sha256(k, key, (uint_32t)klen);
    else           memcpy(k, key, (size_t)klen);
    for (int i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    sha256_begin(&ctx);
    sha256_hash(ipad, 64, &ctx);
    sha256_hash(msg, (uint_32t)mlen, &ctx);
    sha256_end(out, &ctx);
    sha256_begin(&ctx);
    sha256_hash(opad, 64, &ctx);
    sha256_hash(out, 32, &ctx);
    sha256_end(out, &ctx);
}

static void pbkdf2_sha256(const uint8_t *pwd, int plen,
                          const uint8_t *salt, int slen,
                          uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 31) / 32;
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[32], T[32];
        hmac_sha256(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 32);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_sha256(pwd, plen, U, 32, U);
            for (int j = 0; j < 32; j++) T[j] ^= U[j];
        }
        secure_memset((volatile uint8_t *)saltb, 0, (size_t)(slen + 4));
        free(saltb);
        int cp = (b == blocks && dklen % 32 != 0) ? (dklen % 32) : 32;
        memcpy(dk + (b-1)*32, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
}

/* ─── HMAC-Whirlpool / PBKDF2-Whirlpool ─────────────────────────────── */

static void hmac_whirlpool(const uint8_t *key, int klen,
                           const uint8_t *msg, size_t mlen,
                           uint8_t out[64]) {
    uint8_t k[64] = {};
    uint8_t ipad[64], opad[64];
    WHIRLPOOL_CTX ctx;
    if (klen > 64) {
        WHIRLPOOL_init(&ctx);
        WHIRLPOOL_add(key, (unsigned)klen, &ctx);
        WHIRLPOOL_finalize(&ctx, k);
    } else {
        memcpy(k, key, (size_t)klen);
    }
    for (int i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    WHIRLPOOL_init(&ctx);
    WHIRLPOOL_add(ipad, 64,           &ctx);
    WHIRLPOOL_add(msg,  (unsigned)mlen, &ctx);
    WHIRLPOOL_finalize(&ctx, out);
    WHIRLPOOL_init(&ctx);
    WHIRLPOOL_add(opad, 64, &ctx);
    WHIRLPOOL_add(out,  64, &ctx);
    WHIRLPOOL_finalize(&ctx, out);
}

static void pbkdf2_whirlpool(const uint8_t *pwd, int plen,
                             const uint8_t *salt, int slen,
                             uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 63) / 64;
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[64], T[64];
        hmac_whirlpool(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 64);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_whirlpool(pwd, plen, U, 64, U);
            for (int j = 0; j < 64; j++) T[j] ^= U[j];
        }
        secure_memset((volatile uint8_t *)saltb, 0, (size_t)(slen + 4));
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
}

/* ─── HMAC-Streebog / PBKDF2-Streebog ───────────────────────────────── */

static void hmac_streebog(const uint8_t *key, int klen,
                          const uint8_t *msg, size_t mlen,
                          uint8_t out[64]) {
    uint8_t k[64] = {};
    uint8_t ipad[64], opad[64];
    STREEBOG_CTX ctx;
    if (klen > 64) {
        STREEBOG_init(&ctx);
        STREEBOG_add(&ctx, key, (size_t)klen);
        STREEBOG_finalize(&ctx, k);
    } else {
        memcpy(k, key, (size_t)klen);
    }
    for (int i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    STREEBOG_init(&ctx);
    STREEBOG_add(&ctx, ipad, 64);
    STREEBOG_add(&ctx, msg,  mlen);
    STREEBOG_finalize(&ctx, out);
    STREEBOG_init(&ctx);
    STREEBOG_add(&ctx, opad, 64);
    STREEBOG_add(&ctx, out,  64);
    STREEBOG_finalize(&ctx, out);
}

static void pbkdf2_streebog(const uint8_t *pwd, int plen,
                            const uint8_t *salt, int slen,
                            uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 63) / 64;
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[64], T[64];
        hmac_streebog(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 64);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_streebog(pwd, plen, U, 64, U);
            for (int j = 0; j < 64; j++) T[j] ^= U[j];
        }
        secure_memset((volatile uint8_t *)saltb, 0, (size_t)(slen + 4));
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
}

/* ─── HMAC-BLAKE2s / PBKDF2-BLAKE2s ─────────────────────────────────── */
/*
 * BLAKE2s block size = 64 bytes; output = 32 bytes.
 * Standard HMAC construction (ipad/opad XOR with 0x36/0x5C), same as SHA-256.
 * VeraCrypt 1.26+ uses this for new containers with 500,000 iterations.
 * PIM formula: 15,000 + pim * 1,000 (same as SHA-512/Whirlpool/Streebog).
 */
static void hmac_blake2s(const uint8_t *key, int klen,
                         const uint8_t *msg, size_t mlen,
                         uint8_t out[32]) {
    uint8_t k[BLAKE2S_BLOCKBYTES] = {};
    uint8_t ipad[BLAKE2S_BLOCKBYTES], opad[BLAKE2S_BLOCKBYTES];
    blake2s_state ctx;
    if (klen > BLAKE2S_BLOCKBYTES) {
        blake2s_init(&ctx, BLAKE2S_OUTBYTES);
        blake2s_update(&ctx, key, (size_t)klen);
        blake2s_final(&ctx, k, BLAKE2S_OUTBYTES);
    } else {
        memcpy(k, key, (size_t)klen);
    }
    for (int i = 0; i < BLAKE2S_BLOCKBYTES; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5C; }
    blake2s_init(&ctx, BLAKE2S_OUTBYTES);
    blake2s_update(&ctx, ipad, BLAKE2S_BLOCKBYTES);
    blake2s_update(&ctx, msg,  mlen);
    blake2s_final(&ctx, out, BLAKE2S_OUTBYTES);
    blake2s_init(&ctx, BLAKE2S_OUTBYTES);
    blake2s_update(&ctx, opad, BLAKE2S_BLOCKBYTES);
    blake2s_update(&ctx, out,  BLAKE2S_OUTBYTES);
    blake2s_final(&ctx, out, BLAKE2S_OUTBYTES);
}

static void pbkdf2_blake2s(const uint8_t *pwd, int plen,
                           const uint8_t *salt, int slen,
                           uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 31) / 32;   /* 32 bytes output per block */
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[32], T[32];
        hmac_blake2s(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 32);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_blake2s(pwd, plen, U, 32, U);
            for (int j = 0; j < 32; j++) T[j] ^= U[j];
        }
        secure_memset((volatile uint8_t *)saltb, 0, (size_t)(slen + 4));
        free(saltb);
        int cp = (b == blocks && dklen % 32 != 0) ? (dklen % 32) : 32;
        memcpy(dk + (b-1)*32, T, (size_t)cp);
        secure_memset((volatile uint8_t *)U, 0, sizeof(U));
        secure_memset((volatile uint8_t *)T, 0, sizeof(T));
    }
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

    memset(pool, 0, sizeof(pool));
}

/* Same as apply_keyfiles_to_password but reads from JNI byte arrays (no disk access).
 * jKeyfileData is an Array<ByteArray>? — null or empty means no-op. */
static void apply_keyfile_buffers(JNIEnv *env, jobjectArray jKeyfileData,
                                   uint8_t *pwd_buf, int *pwd_len) {
    if (!jKeyfileData) return;
    jsize count = env->GetArrayLength(jKeyfileData);
    if (count == 0) return;

    uint8_t pool[VC_KEYFILE_POOL_SIZE] = {};

    for (jsize i = 0; i < count; i++) {
        auto jBuf = (jbyteArray)env->GetObjectArrayElement(jKeyfileData, i);
        if (!jBuf) continue;
        jsize  len   = env->GetArrayLength(jBuf);
        jbyte *bytes = env->GetByteArrayElements(jBuf, nullptr);
        if (bytes) {
            size_t lim = (size_t)len < (size_t)VC_KEYFILE_MAX_READ
                         ? (size_t)len : (size_t)VC_KEYFILE_MAX_READ;
            vc_process_keyfile_buf((uint8_t*)bytes, lim, pool);
            memset(bytes, 0, (size_t)len);
            env->ReleaseByteArrayElements(jBuf, bytes, JNI_ABORT);
        }
        env->DeleteLocalRef(jBuf);
    }

    if (*pwd_len < VC_KEYFILE_POOL_SIZE) {
        memset(pwd_buf + *pwd_len, 0, (size_t)(VC_KEYFILE_POOL_SIZE - *pwd_len));
        *pwd_len = VC_KEYFILE_POOL_SIZE;
    }
    for (int i = 0; i < VC_KEYFILE_POOL_SIZE; i++)
        pwd_buf[i] = (uint8_t)((uint8_t)pwd_buf[i] + pool[i]);

    memset(pool, 0, sizeof(pool));
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
    uint8_t derivedKey[192] = {};
    switch (hashAlg) {
        case 1:  pbkdf2_sha256   ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, derivedKey, mkLen); break;
        case 2:  pbkdf2_whirlpool((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, derivedKey, mkLen); break;
        case 3:  pbkdf2_streebog ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, derivedKey, mkLen); break;
        case 4:  pbkdf2_blake2s  ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, derivedKey, mkLen); break;
        default: pbkdf2_sha512   ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, derivedKey, mkLen); break;
    }

    /* Build decrypted header body (448 bytes) */
    uint8_t body[VC_HEADER_BODY_SIZE] = {};
    body[0]='V'; body[1]='E'; body[2]='R'; body[3]='A';
    body[4] = (VC_HEADER_VERSION >> 8) & 0xFF;
    body[5] =  VC_HEADER_VERSION       & 0xFF;
    body[6] = (VC_MIN_REQUIRED_VERSION >> 8) & 0xFF;
    body[7] =  VC_MIN_REQUIRED_VERSION       & 0xFF;
    /* body[8..11]: CRC of master key area — written after key copy */
    /* body[12..27]: reserved zero */
    put_be64(body + 28, hiddenVolSize);    /* hidden volume size (0 = none / this IS the hidden vol) */
    put_be64(body + 36, dataSz);           /* volume size              */
    put_be64(body + 44, dataOff);          /* data area offset         */
    put_be64(body + 52, dataSz);           /* encrypted area length    */
    put_be32(body + 64, VC_SECTOR_SIZE);   /* sector size              */
    /* body[68..191]: reserved */

    memcpy(body + VC_MASTER_KEY_OFFSET, masterKey, (size_t)mkLen);

    uint32_t crc1 = crc32_buf(body + 192, 256);
    put_be32(body + 8, crc1);
    uint32_t crc2 = crc32_buf(body, 188);
    put_be32(body + 188, crc2);

    /* Encrypt body: forward 0..n-1 (c[0]=innermost first), VeraCrypt cascade key layout. */
    for (int i = 0; i < n; i++) {
        uint8_t ck[64]; build_cascade_key64(derivedKey, n, i, ck);
        xts_crypt_temp(ALGORITHMS[algId].c[i], ck, body, VC_HEADER_BODY_SIZE, 0, true);
        secure_memset(ck, 0, sizeof(ck));
    }

    uint8_t rawHeader[VC_HEADER_SIZE] = {};
    memcpy(rawHeader, salt, VC_HEADER_SALT_SIZE);
    memcpy(rawHeader + VC_HEADER_BODY_OFFSET, body, VC_HEADER_BODY_SIZE);

    ssize_t w = pwrite(fd, rawHeader, VC_HEADER_SIZE, (off_t)fileOff);
    memset(body,       0, sizeof(body));
    memset(derivedKey, 0, sizeof(derivedKey));
    return (w == VC_HEADER_SIZE) ? 0 : -1;
}

/* ─── VeraCrypt header authenticate ─────────────────────────────────── */

static void derive_header_key(int hi, const uint8_t *password, int pwd_len,
                               const uint8_t *salt, int pim, uint8_t out[192]) {
    uint32_t iters = vc_get_iterations(hi, pim);
    switch (hi) {
        case 0: pbkdf2_sha512   (password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192); break;
        case 1: pbkdf2_sha256   (password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192); break;
        case 2: pbkdf2_whirlpool(password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192); break;
        case 3: pbkdf2_streebog (password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192); break;
        case 4: pbkdf2_blake2s  (password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192); break;
        default: break;
    }
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

    if (mountCb) { mountCb->attempt = 1; mountCb->total = NUM_HASHES * NUM_ALGORITHMS; }

    /* ── Fast path: try hinted (hash, algorithm) combination first ── */
    bool hintTried = false;
    if (hintHashId >= 0 && hintHashId < NUM_HASHES &&
        hintAlgId  >= 0 && hintAlgId  < NUM_ALGORITHMS) {
        hintTried = true;
        report_trying(mountCb, hintAlgId, hintHashId);
        uint8_t hintKey[192] = {};
        derive_header_key(hintHashId, (const uint8_t*)password, pwd_len, salt, pim, hintKey);

        uint8_t body[VC_HEADER_BODY_SIZE];
        memcpy(body, rawBody, VC_HEADER_BODY_SIZE);
        int n = ALGORITHMS[hintAlgId].n;
        /* Decrypt: reverse n-1..0 (outermost c[n-1] first). */
        for (int ci = n - 1; ci >= 0; ci--) {
            uint8_t ck[64]; build_cascade_key64(hintKey, n, ci, ck);
            xts_crypt_temp(ALGORITHMS[hintAlgId].c[ci], ck, body, VC_HEADER_BODY_SIZE, 0, false);
            secure_memset(ck, 0, sizeof(ck));
        }

        if (body[0]=='V' && body[1]=='E' && body[2]=='R' && body[3]=='A' &&
            get_be32(body + 188) == crc32_buf(body, 188) &&
            get_be32(body + 8)   == crc32_buf(body + 192, 256)) {
            int mkLen = n * 64;
            memcpy(masterKey, body + VC_MASTER_KEY_OFFSET, (size_t)mkLen);
            if (outMkLen)  *outMkLen  = mkLen;
            if (outAlgId)  *outAlgId  = hintAlgId;
            if (outHashId) *outHashId = hintHashId;
            uint64_t field28 = get_be64(body + 28);
            if (outHiddenVolSize) *outHiddenVolSize = field28;
            if (dataSz)  *dataSz  = get_be64(body + 36);
            if (dataOff) *dataOff = get_be64(body + 44);
            secure_memset((volatile uint8_t *)body,    0, sizeof(body));
            secure_memset((volatile uint8_t *)hintKey, 0, sizeof(hintKey));
            return ERR_OK;
        }
        secure_memset((volatile uint8_t *)body,    0, sizeof(body));
        secure_memset((volatile uint8_t *)hintKey, 0, sizeof(hintKey));
    }

    /* ── Full brute-force (skips the hint combo if already tried) ── */
    uint8_t allDerivedKeys[NUM_HASHES][192];
    memset(allDerivedKeys, 0, sizeof(allDerivedKeys));

    for (int hi = 0; hi < NUM_HASHES; hi++) {
        derive_header_key(hi, (const uint8_t*)password, pwd_len, salt, pim, allDerivedKeys[hi]);

        uint8_t *derivedKey = allDerivedKeys[hi];

        for (int ai = 0; ai < NUM_ALGORITHMS; ai++) {
            if (hintTried && hi == hintHashId && ai == hintAlgId) continue;

            report_trying(mountCb, ai, hi);

            uint8_t body[VC_HEADER_BODY_SIZE];
            memcpy(body, rawBody, VC_HEADER_BODY_SIZE);

            int n = ALGORITHMS[ai].n;
            /* Decrypt: reverse n-1..0 (outermost c[n-1] first). */
            for (int ci = n - 1; ci >= 0; ci--) {
                uint8_t ck[64]; build_cascade_key64(allDerivedKeys[hi], n, ci, ck);
                xts_crypt_temp(ALGORITHMS[ai].c[ci], ck, body, VC_HEADER_BODY_SIZE, 0, false);
                secure_memset(ck, 0, sizeof(ck));
            }

            /* Check "VERA" magic */
            if (body[0]!='V'||body[1]!='E'||body[2]!='R'||body[3]!='A') {
                memset(body, 0, sizeof(body));
                continue;
            }

            /* Verify CRC of header fields [0..187] */
            if (get_be32(body + 188) != crc32_buf(body, 188)) {
                memset(body, 0, sizeof(body));
                continue;
            }

            /* Verify CRC of master key area [192..447] */
            if (get_be32(body + 8) != crc32_buf(body + 192, 256)) {
                memset(body, 0, sizeof(body));
                continue;
            }

            /* Extract master key and volume geometry */
            int mkLen = n * 64;
            memcpy(masterKey, body + VC_MASTER_KEY_OFFSET, (size_t)mkLen);
            if (outMkLen)  *outMkLen  = mkLen;
            if (outAlgId)  *outAlgId  = ai;
            if (outHashId) *outHashId = hi;

            /* field28 = hidden volume size (0 for standard or hidden-volume headers) */
            uint64_t field28 = get_be64(body + 28);
            if (outHiddenVolSize) *outHiddenVolSize = field28;
            /* body+36 = usable data size, body+44 = absolute data area offset */
            if (dataSz)  *dataSz  = get_be64(body + 36);
            if (dataOff) *dataOff = get_be64(body + 44);

            memset(body, 0, sizeof(body));
            memset(allDerivedKeys, 0, sizeof(allDerivedKeys));
            return ERR_OK;
        }
        memset(allDerivedKeys[hi], 0, 192);
    }

    return ERR_WRONG_PASSWORD;
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

static std::string jstring_to_string(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
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

/* ─── JNI: nativeCreateContainer ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jlong sizeBytes,
        jstring jPassword, jobjectArray jKeyfilePaths,
        jint algorithm, jint hashAlg, jint filesystem,
        jboolean quickFormat,
        jbyteArray entropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);
    if (path.empty() || password.empty()) return ERR_FILE;

    uint8_t effPwd[VC_MAX_PWD_LEN] = {};
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd, password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd, &effPwdLen);
    const int pbkdf2PwdLen = effPwdLen;

    int algId = (int)algorithm;
    if (algId < 0 || algId >= NUM_ALGORITHMS) return ERR_FS;
    int n     = ALGORITHMS[algId].n;

    uint64_t dataSize = (uint64_t)sizeBytes;
    uint64_t fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE;

    int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { LOGE("[2/6] Cannot open/create: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno)); return ERR_FILE; }

    if (ftruncate(fd, (off_t)fileSize) != 0) {
        LOGE("[create] ftruncate failed — disk full?");
        close(fd); unlink(path.c_str()); return ERR_NO_SPACE;
    }

    uint8_t masterKey[192] = {};
    if (!read_urandom(masterKey, (size_t)(n * 64))) {
        LOGE("[create] /dev/urandom failed for master key — aborting");
        close(fd); unlink(path.c_str());
        return ERR_RAND;
    }

    if (write_vc_header(fd, 0, dataSize, VC_DATA_OFFSET,
                        masterKey, algId, (int)hashAlg,
                        (const char*)effPwd, pbkdf2PwdLen, (int)pim) != 0) {
        LOGE("[create] Primary header write failed");
        memset(effPwd, 0, sizeof(effPwd));
        close(fd); unlink(path.c_str()); return ERR_FILE;
    }

    /* Write backup header at end of file */
    uint64_t backupOff = fileSize - VC_BACKUP_AREA_SIZE;
    write_vc_header(fd, backupOff, dataSize, VC_DATA_OFFSET,
                    masterKey, algId, (int)hashAlg,
                    (const char*)effPwd, pbkdf2PwdLen, (int)pim);
    memset(effPwd, 0, sizeof(effPwd));

    /* Resolve progress callback method ID once — reused across all chunks */
    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    /* Fill data area */
    if (!quickFormat) {
        const size_t CHUNK = 65536;
        auto *rnd = static_cast<uint8_t*>(malloc(CHUNK));
        if (rnd) {
            memset(rnd, 0, CHUNK);
            uint64_t remaining = dataSize, offset = VC_DATA_OFFSET;
            int rfd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            bool rng_ok = true;
            auto t0 = (uint64_t)time(nullptr);
            while (remaining > 0) {
                size_t sz = (remaining > CHUNK) ? CHUNK : (size_t)remaining;
                if (rfd >= 0) {
                    size_t got = 0;
                    while (got < sz) {
                        ssize_t r = read(rfd, rnd + got, sz - got);
                        if (r > 0) { got += (size_t)r; continue; }
                        if (r < 0 && errno == EINTR) continue;
                        rng_ok = false;
                        break;
                    }
                }
                if (!rng_ok) break;
                pwrite(fd, rnd, sz, (off_t)offset);
                remaining -= sz; offset += sz;
                uint64_t written = dataSize - remaining;
                float frac = (float)written / (float)dataSize;
                uint64_t elapsed = (uint64_t)time(nullptr) - t0;
                float speed = elapsed > 0 ? (float)(written/1048576UL)/(float)elapsed : 10.f;
                report_progress(env, progressListener, progressMid, frac, speed, (jlong)written);
            }
            if (rfd >= 0) close(rfd);
            memset(rnd, 0, CHUNK);
            free(rnd);
            if (!rng_ok) {
                LOGE("[create] /dev/urandom failed during data fill — aborting");
                secure_memset((volatile uint8_t *)masterKey, 0, sizeof(masterKey));
                close(fd);
                unlink(path.c_str());
                return ERR_RAND;
            }
        }
    } else {
        report_progress(env, progressListener, progressMid, 0.5f, 500.f, (jlong)(dataSize/2));
    }

    /* Format filesystem */
    int pdrv = alloc_drive(fd, VC_DATA_OFFSET, dataSize / VC_SECTOR_SIZE, masterKey, algId);
    memset(masterKey, 0, sizeof(masterKey));

    if (pdrv < 0) {
        LOGE("[create] No drive slot");
        close(fd); unlink(path.c_str()); return ERR_NO_SLOT;
    }

    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    BYTE  work[4096];
    BYTE  fmtFlag = (filesystem == 1) ? (FM_EXFAT|FM_SFD) : ((FM_FAT|FM_FAT32)|FM_SFD);
    BYTE  nFat    = (filesystem == 1) ? 1 : 2;
    MKFS_PARM opts = { fmtFlag, nFat, 0, 0, 0 };
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
    }
    free_drive(pdrv);

    if (fr != FR_OK) {
        LOGE("[create] f_mkfs failed: %d", (int)fr);
        close(fd); unlink(path.c_str()); return ERR_FS;
    }

    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)dataSize);
    close(fd);
    return ERR_OK;
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
        jbyteArray entropyBytes,
        jobject progressListener,
        jint pim)
{
    if (algorithm < 0 || algorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;

    std::string password = jstring_to_string(env, jPassword);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);
    if (password.empty()) return ERR_FILE;

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/create] dup failed: errno=%d", errno); return ERR_FILE; }

    uint8_t effPwd[VC_MAX_PWD_LEN] = {};
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd, password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd, &effPwdLen);
    const int pbkdf2PwdLen = effPwdLen;

    int algId = (int)algorithm;
    int n     = ALGORITHMS[algId].n;

    uint64_t dataSize = (uint64_t)sizeBytes;
    uint64_t fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE;

    if (ftruncate(fd, (off_t)fileSize) != 0) {
        LOGE("[fd/create] ftruncate failed");
        close(fd); return ERR_NO_SPACE;
    }
    lseek(fd, 0, SEEK_SET);

    uint8_t masterKey[192] = {};
    if (!read_urandom(masterKey, (size_t)(n * 64))) {
        LOGE("[fd/create] /dev/urandom failed for master key");
        close(fd); return ERR_RAND;
    }

    if (write_vc_header(fd, 0, dataSize, VC_DATA_OFFSET,
                        masterKey, algId, (int)hashAlg,
                        (const char*)effPwd, pbkdf2PwdLen, (int)pim) != 0) {
        LOGE("[fd/create] Primary header write failed");
        memset(effPwd, 0, sizeof(effPwd));
        close(fd); return ERR_FILE;
    }

    uint64_t backupOff = fileSize - VC_BACKUP_AREA_SIZE;
    write_vc_header(fd, backupOff, dataSize, VC_DATA_OFFSET,
                    masterKey, algId, (int)hashAlg,
                    (const char*)effPwd, pbkdf2PwdLen, (int)pim);
    memset(effPwd, 0, sizeof(effPwd));

    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    if (!quickFormat) {
        const size_t CHUNK = 65536;
        auto *rnd = static_cast<uint8_t*>(malloc(CHUNK));
        if (rnd) {
            memset(rnd, 0, CHUNK);
            uint64_t remaining = dataSize, offset = VC_DATA_OFFSET;
            int rfd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            bool rng_ok = true;
            auto t0 = (uint64_t)time(nullptr);
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
                pwrite(fd, rnd, sz, (off_t)offset);
                remaining -= sz; offset += sz;
                uint64_t written = dataSize - remaining;
                float frac  = (float)written / (float)dataSize;
                uint64_t el = (uint64_t)time(nullptr) - t0;
                float speed = el > 0 ? (float)(written/1048576UL)/(float)el : 10.f;
                report_progress(env, progressListener, progressMid, frac, speed, (jlong)written);
            }
            if (rfd >= 0) close(rfd);
            memset(rnd, 0, CHUNK);
            free(rnd);
            if (!rng_ok) {
                secure_memset((volatile uint8_t*)masterKey, 0, sizeof(masterKey));
                close(fd); return ERR_RAND;
            }
        }
    } else {
        report_progress(env, progressListener, progressMid, 0.5f, 500.f, (jlong)(dataSize/2));
    }

    int pdrv = alloc_drive(fd, VC_DATA_OFFSET, dataSize / VC_SECTOR_SIZE, masterKey, algId);
    memset(masterKey, 0, sizeof(masterKey));

    if (pdrv < 0) { LOGE("[fd/create] No drive slot"); close(fd); return ERR_NO_SLOT; }

    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    BYTE work[4096];
    BYTE fmtFlag = (filesystem == 1) ? (FM_EXFAT|FM_SFD) : ((FM_FAT|FM_FAT32)|FM_SFD);
    BYTE nFat    = (filesystem == 1) ? 1 : 2;
    MKFS_PARM opts = { fmtFlag, nFat, 0, 0, 0 };
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
    }
    free_drive(pdrv);

    if (fr != FR_OK) { LOGE("[fd/create] f_mkfs failed: %d", (int)fr); close(fd); return ERR_FS; }

    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)dataSize);
    close(fd);
    return ERR_OK;
}

/* ─── JNI: nativeOpenContainerFd ────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainerFd(
        JNIEnv *env, jobject /*thiz*/,
        jint safFd, jstring jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jstring jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener)
{
    std::string password = jstring_to_string(env, jPassword);

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/open] dup failed: errno=%d", errno); return (jlong)ERR_FILE; }

    uint8_t effPwd[VC_MAX_PWD_LEN] = {};
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd, password.c_str(), (size_t)effPwdLen);
    apply_keyfile_buffers(env, jKeyfileData, effPwd, &effPwdLen);

    /* Prepare hidden-volume credentials for boundary derivation */
    std::string hiddenPassword = jProtectHiddenPassword ? jstring_to_string(env, jProtectHiddenPassword) : "";
    uint8_t hidEffPwd[VC_MAX_PWD_LEN] = {};
    int hidEffPwdLen = (int)hiddenPassword.size();
    if (hidEffPwdLen > VC_MAX_PWD_LEN) hidEffPwdLen = VC_MAX_PWD_LEN;
    if (hidEffPwdLen > 0) {
        memcpy(hidEffPwd, hiddenPassword.c_str(), (size_t)hidEffPwdLen);
        apply_keyfile_buffers(env, jProtectHiddenKeyfileData, hidEffPwd, &hidEffPwdLen);
    }

    struct stat st{};
    fstat(fd, &st);
    uint64_t fileSize = (uint64_t)st.st_size;

    if (fileSize < VC_DATA_OFFSET) { memset(hidEffPwd, 0, sizeof(hidEffPwd)); close(fd); return (jlong)ERR_WRONG_PASSWORD; }
    if (fileSize % VC_SECTOR_SIZE != 0) { memset(hidEffPwd, 0, sizeof(hidEffPwd)); close(fd); return (jlong)ERR_WRONG_PASSWORD; }

    uint8_t masterKey[192] = {};
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0;

    uint64_t tryOffsets[4] = {
        0,
        VC_HIDDEN_HEADER_OFFSET,
        fileSize - VC_BACKUP_AREA_SIZE,
        fileSize - VC_HIDDEN_HEADER_OFFSET
    };
    bool tryIsHidden[4] = { false, true, false, true };

    int rc = ERR_WRONG_PASSWORD;
    bool authIsHidden = false;
    uint64_t hiddenVolSize = 0;

    MountCb mountCb{ env, mountProgressListener, resolve_mount_mid(env, mountProgressListener), 1, 75 };
    MountCb *pMountCb = mountProgressListener ? &mountCb : nullptr;

    for (int ti = 0; ti < 4 && rc != ERR_OK; ti++) {
        if (tryOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
        uint64_t hvSz = 0;
        rc = read_vc_header(fd, tryOffsets[ti], (const char*)effPwd, effPwdLen,
                            masterKey, &mkLen, &dataSz, &dataOff, &algId, &hashId,
                            (int)pim, &hvSz, (int)algorithm, (int)hashAlgorithm, pMountCb);
        if (rc == ERR_OK) { authIsHidden = tryIsHidden[ti]; hiddenVolSize = hvSz; }
    }

    memset(effPwd, 0, sizeof(effPwd));
    if (rc != ERR_OK) { memset(masterKey, 0, sizeof(masterKey)); memset(hidEffPwd, 0, sizeof(hidEffPwd)); close(fd); return (jlong)rc; }

    uint64_t hiddenBoundary = 0;
    if (!authIsHidden && hiddenVolSize > 0)
        hiddenBoundary = dataOff + dataSz - hiddenVolSize;

    /* If outer volume mounted but boundary unknown, derive it from hidden header */
    if (!authIsHidden && hiddenBoundary == 0 && hidEffPwdLen > 0) {
        uint64_t hidOffsets[2] = { VC_HIDDEN_HEADER_OFFSET, fileSize - VC_HIDDEN_HEADER_OFFSET };
        uint8_t hidMasterKey[192] = {};
        int hidMkLen = 0, hidAlgId = 0, hidHashId = 0;
        uint64_t hidDataSz = 0, hidDataOff = 0, hidHvSz = 0;
        for (int ti = 0; ti < 2; ti++) {
            if (hidOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
            int hrc = read_vc_header(fd, hidOffsets[ti], (const char*)hidEffPwd, hidEffPwdLen,
                                     hidMasterKey, &hidMkLen, &hidDataSz, &hidDataOff,
                                     &hidAlgId, &hidHashId, (int)protectHiddenPim, &hidHvSz, -1, -1);
            if (hrc == ERR_OK && hidDataSz > 0) {
                hiddenBoundary = dataOff + dataSz - hidDataSz;
                LOGE("[fd/open] protect-hidden: boundary set to 0x%llx from hidden header",
                     (unsigned long long)hiddenBoundary);
                break;
            }
        }
        memset(hidMasterKey, 0, sizeof(hidMasterKey));
    }
    memset(hidEffPwd, 0, sizeof(hidEffPwd));

    int pdrv = alloc_drive(fd, dataOff, dataSz / VC_SECTOR_SIZE, masterKey, algId, hashId,
                           authIsHidden, hiddenBoundary);
    memset(masterKey, 0, sizeof(masterKey));
    if (pdrv < 0) { close(fd); return (jlong)ERR_NO_SLOT; }

    auto *ctx = new ContainerCtx{ pdrv, {}, fd };
    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mount(&ctx->fatFs, drvPath, 1);
    }
    if (fr != FR_OK) {
        LOGE("[fd/open] f_mount failed: %d", (int)fr);
        free_drive(pdrv); close(fd); delete ctx;
        return (jlong)ERR_FS;
    }

    g_ctxMap[pdrv] = ctx;
    return (jlong)pdrv;
}

/* ─── JNI: nativeOpenContainer ──────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jstring jPassword, jobjectArray jKeyfileData,
        jint pim, jint algorithm, jint hashAlgorithm,
        jstring jProtectHiddenPassword, jobjectArray jProtectHiddenKeyfileData, jint protectHiddenPim,
        jobject mountProgressListener)
{
    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);

    uint8_t effPwd[VC_MAX_PWD_LEN] = {};
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd, password.c_str(), (size_t)effPwdLen);
    apply_keyfile_buffers(env, jKeyfileData, effPwd, &effPwdLen);

    /* Prepare hidden-volume credentials for boundary derivation */
    std::string hiddenPassword = jProtectHiddenPassword ? jstring_to_string(env, jProtectHiddenPassword) : "";
    uint8_t hidEffPwd[VC_MAX_PWD_LEN] = {};
    int hidEffPwdLen = (int)hiddenPassword.size();
    if (hidEffPwdLen > VC_MAX_PWD_LEN) hidEffPwdLen = VC_MAX_PWD_LEN;
    if (hidEffPwdLen > 0) {
        memcpy(hidEffPwd, hiddenPassword.c_str(), (size_t)hidEffPwdLen);
        apply_keyfile_buffers(env, jProtectHiddenKeyfileData, hidEffPwd, &hidEffPwdLen);
    }

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) { LOGE("Cannot open: %s (errno=%d: %s)", path.c_str(), errno, strerror(errno)); memset(hidEffPwd, 0, sizeof(hidEffPwd)); return (jlong)ERR_FILE; }

    struct stat st{};
    fstat(fd, &st);
    uint64_t fileSize = (uint64_t)st.st_size;

    if (fileSize < VC_DATA_OFFSET) {
        LOGE("File too small: %llu", (unsigned long long)fileSize);
        close(fd); return (jlong)ERR_WRONG_PASSWORD;
    }
    if (fileSize % VC_SECTOR_SIZE != 0) {
        LOGE("Not sector-aligned: %llu", (unsigned long long)fileSize);
        close(fd); return (jlong)ERR_WRONG_PASSWORD;
    }

    uint8_t masterKey[192] = {};
    int mkLen = 0, algId = 0, hashId = 0;
    uint64_t dataSz = 0, dataOff = 0;

    /* Try all 4 header locations: outer primary, hidden primary, outer backup, hidden backup */
    uint64_t tryOffsets[4] = {
        0,
        VC_HIDDEN_HEADER_OFFSET,
        fileSize - VC_BACKUP_AREA_SIZE,
        fileSize - VC_HIDDEN_HEADER_OFFSET
    };
    bool tryIsHidden[4] = { false, true, false, true };

    int rc = ERR_WRONG_PASSWORD;
    bool authIsHidden = false;
    uint64_t hiddenVolSize = 0;

    MountCb mountCb{ env, mountProgressListener, resolve_mount_mid(env, mountProgressListener), 1, 75 };
    MountCb *pMountCb = mountProgressListener ? &mountCb : nullptr;

    for (int ti = 0; ti < 4 && rc != ERR_OK; ti++) {
        if (tryOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
        uint64_t hvSz = 0;
        rc = read_vc_header(fd, tryOffsets[ti], (const char*)effPwd, effPwdLen,
                            masterKey, &mkLen, &dataSz, &dataOff, &algId, &hashId, (int)pim, &hvSz,
                            (int)algorithm, (int)hashAlgorithm, pMountCb);
        if (rc == ERR_OK) {
            authIsHidden = tryIsHidden[ti];
            hiddenVolSize = hvSz;
        }
    }

    memset(effPwd, 0, sizeof(effPwd));

    if (rc != ERR_OK) {
        memset(masterKey, 0, sizeof(masterKey));
        memset(hidEffPwd, 0, sizeof(hidEffPwd));
        close(fd); return (jlong)rc;
    }

    uint64_t hiddenBoundary = 0;
    if (!authIsHidden && hiddenVolSize > 0)
        hiddenBoundary = dataOff + dataSz - hiddenVolSize;

    /* If outer volume mounted but boundary unknown, derive it from hidden header */
    if (!authIsHidden && hiddenBoundary == 0 && hidEffPwdLen > 0) {
        uint64_t hidOffsets[2] = { VC_HIDDEN_HEADER_OFFSET, fileSize - VC_HIDDEN_HEADER_OFFSET };
        uint8_t hidMasterKey[192] = {};
        int hidMkLen = 0, hidAlgId = 0, hidHashId = 0;
        uint64_t hidDataSz = 0, hidDataOff = 0, hidHvSz = 0;
        for (int ti = 0; ti < 2; ti++) {
            if (hidOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
            int hrc = read_vc_header(fd, hidOffsets[ti], (const char*)hidEffPwd, hidEffPwdLen,
                                     hidMasterKey, &hidMkLen, &hidDataSz, &hidDataOff,
                                     &hidAlgId, &hidHashId, (int)protectHiddenPim, &hidHvSz, -1, -1);
            if (hrc == ERR_OK && hidDataSz > 0) {
                hiddenBoundary = dataOff + dataSz - hidDataSz;
                LOGE("[open] protect-hidden: boundary set to 0x%llx from hidden header",
                     (unsigned long long)hiddenBoundary);
                break;
            }
        }
        memset(hidMasterKey, 0, sizeof(hidMasterKey));
    }
    memset(hidEffPwd, 0, sizeof(hidEffPwd));

    int pdrv = alloc_drive(fd, dataOff, dataSz / VC_SECTOR_SIZE, masterKey, algId, hashId,
                           authIsHidden, hiddenBoundary);
    memset(masterKey, 0, sizeof(masterKey));
    if (pdrv < 0) { close(fd); return (jlong)ERR_NO_SLOT; }

    auto *ctx = new ContainerCtx{ pdrv, {}, fd };
    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mount(&ctx->fatFs, drvPath, 1);
    }
    if (fr != FR_OK) {
        LOGE("f_mount failed: %d", (int)fr);
        free_drive(pdrv); close(fd); delete ctx;
        return (jlong)ERR_FS;
    }

    g_ctxMap[pdrv] = ctx;
    return (jlong)pdrv;
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
    jclass infoCls = env->FindClass("zip/arcanum/crypto/NativeFileInfo");
    if (!infoCls) return nullptr;
    jmethodID ctor = env->GetMethodID(infoCls, "<init>",
                         "(Ljava/lang/String;Ljava/lang/String;JZJ)V");
    if (!ctor) return env->NewObjectArray(0, infoCls, nullptr);

    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active)
        return env->NewObjectArray(0, infoCls, nullptr);

    std::string dirPath = jstring_to_string(env, jDirPath);
    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, dirPath.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return env->NewObjectArray(0, infoCls, nullptr);

    DIR  dir;
    FILINFO fno;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    if (f_opendir(&dir, fullPath) != FR_OK)
        return env->NewObjectArray(0, infoCls, nullptr);

    int count = 0;
    while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0])
        count++;
    f_closedir(&dir);

    jobjectArray result = env->NewObjectArray(count, infoCls, nullptr);
    if (f_opendir(&dir, fullPath) != FR_OK) return result;

    int idx = 0;
    while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0] && idx < count) {
        bool isDir = (fno.fattrib & AM_DIR) != 0;

        char ep[512];
        snprintf(ep, sizeof(ep), "%s/%s",
                 dirPath.empty() ? "/" : dirPath.c_str(), fno.fname);

        // Skip entries whose names are not valid UTF-8 — NewStringUTF aborts on invalid bytes.
        // FF_LFN_UNICODE 2 makes FatFs produce UTF-8; this guard catches any residual garbage.
        if (!is_valid_utf8(fno.fname) || !is_valid_utf8(ep)) {
            LOGE("nativeListFiles: skipping entry with non-UTF-8 name (binary bytes in fname)");
            continue;  // don't advance idx — next valid entry fills this slot
        }

        jstring jName = env->NewStringUTF(fno.fname);
        jstring jPath = env->NewStringUTF(ep);
        jobject fi    = env->NewObject(infoCls, ctor,
                                       jName, jPath,
                                       (jlong)fno.fsize,
                                       (jboolean)(isDir ? 1 : 0),
                                       fatfs_to_epoch_ms(fno.fdate, fno.ftime));
        env->SetObjectArrayElement(result, idx++, fi);
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jPath);
        env->DeleteLocalRef(fi);
    }
    f_closedir(&dir);
    return result;
}

/* ─── JNI: nativeReadFile ────────────────────────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeReadFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath, jlong offset, jint length)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active)
        return env->NewByteArray(0);

    // Reject non-positive or unreasonably large requests.
    // A negative length would wrap to ~4 GB when cast to UINT, causing a buffer overflow.
    if (length <= 0 || length > 16 * 1024 * 1024)
        return env->NewByteArray(0);

    std::string path = jstring_to_string(env, jFilePath);
    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return env->NewByteArray(0);

    FIL fil;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    if (f_open(&fil, fullPath, FA_READ) != FR_OK) return env->NewByteArray(0);
    if (f_lseek(&fil, (FSIZE_t)offset) != FR_OK) { f_close(&fil); return env->NewByteArray(0); }

    jbyteArray result = env->NewByteArray(length);
    jbyte *buf = env->GetByteArrayElements(result, nullptr);
    if (!buf) { f_close(&fil); return env->NewByteArray(0); }

    UINT br = 0;
    f_read(&fil, buf, (UINT)length, &br);
    env->ReleaseByteArrayElements(result, buf, 0);
    f_close(&fil);

    if ((jint)br < length) {
        // Return a trimmed array sized to actual bytes read.
        // Re-pin result to copy, then release and delete the original local ref.
        jbyteArray trimmed = env->NewByteArray((jsize)br);
        if (trimmed && br > 0) {
            jbyte *src = env->GetByteArrayElements(result, nullptr);
            if (src) {
                env->SetByteArrayRegion(trimmed, 0, (jsize)br, src);
                env->ReleaseByteArrayElements(result, src, JNI_ABORT);
            }
        }
        env->DeleteLocalRef(result);
        return trimmed;
    }
    return result;
}

/* ─── JNI: nativeWriteFile ───────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeWriteFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath,
        jbyteArray jData, jlong offset)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return ERR_NO_SLOT;

    std::string path = jstring_to_string(env, jFilePath);
    char fullPath[512];
    int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
    if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;

    FIL fil;
    // For in-place writes (offset > 0) open with read+write so f_lseek works correctly
    // over the full cluster chain. FA_CREATE_ALWAYS is only safe when writing from byte 0.
    BYTE omode = (offset == 0) ? (FA_WRITE | FA_CREATE_ALWAYS)
                               : (FA_READ | FA_WRITE | FA_OPEN_EXISTING);
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
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
    int pdrv = (int)handle;
    auto it  = g_ctxMap.find(pdrv);
    if (it == g_ctxMap.end()) return ERR_NO_SLOT;

    ContainerCtx *ctx = it->second;
    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        f_unmount(drvPath);
    }
    fsync(ctx->fd);
    close(ctx->fd);
    free_drive(pdrv);
    g_ctxMap.erase(it);
    delete ctx;
    return ERR_OK;
}

/* ─── JNI: nativeGetAlgorithmId ─────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetAlgorithmId(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return -1;
    return (jint)g_drives[pdrv].algId;
}

/* ─── JNI: nativeGetHashId ───────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetHashId(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return -1;
    return (jint)g_drives[pdrv].hashId;
}

/* ─── JNI: nativeGetFilesystem ───────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetFilesystem(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    auto it  = g_ctxMap.find(pdrv);
    if (it == g_ctxMap.end()) return -1;
    return (jint)it->second->fatFs.fs_type;
}

/* ─── JNI: nativeGetDataSize ─────────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetDataSize(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return -1;
    return (jlong)(g_drives[pdrv].sectorCount * (uint64_t)VC_SECTOR_SIZE);
}

/* ─── JNI: nativeDeleteFile ──────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeDeleteFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jFilePath)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return ERR_NO_SLOT;

    std::string path = jstring_to_string(env, jFilePath);
    char fullPath[512];
    { int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
      if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE; }

    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_unlink(fullPath);
    }
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeRenameFile ─────────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeRenameFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jOldPath, jstring jNewPath)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return ERR_NO_SLOT;

    std::string oldPath = jstring_to_string(env, jOldPath);
    std::string newPath = jstring_to_string(env, jNewPath);

    char fullOldPath[512];
    char fullNewPath[512];
    { int n1 = snprintf(fullOldPath, sizeof(fullOldPath), "%d:%s", pdrv, oldPath.c_str());
      int n2 = snprintf(fullNewPath, sizeof(fullNewPath), "%d:%s", pdrv, newPath.c_str());
      if (n1 < 0 || n1 >= (int)sizeof(fullOldPath) ||
          n2 < 0 || n2 >= (int)sizeof(fullNewPath)) return ERR_FILE; }

    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_rename(fullOldPath, fullNewPath);
    }
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeCreateDirectory ────────────────────────────────────── */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateDirectory(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jDirPath)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return ERR_NO_SLOT;

    std::string path = jstring_to_string(env, jDirPath);
    char fullPath[512];
    { int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
      if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE; }

    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkdir(fullPath);
    }
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
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return ERR_NO_SLOT;

    std::string path = jstring_to_string(env, jDirPath);
    char fullPath[512];
    { int n = snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());
      if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE; }

    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = unlink_recursive_locked(fullPath);
    }
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeCreateHiddenVolume ─────────────────────────────────── */
/* Adds a hidden volume inside an existing outer VeraCrypt container.
   Steps:
   1. Authenticates outer volume with outer password.
   2. Re-writes both outer headers embedding the hidden size in field28 so
      that outer-volume writes are blocked from reaching the hidden area.
   3. Writes hidden primary + backup headers.
   4. Formats the hidden area as FAT32.                                   */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeCreateHiddenVolume(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath,
        jlong hiddenSizeBytes,
        jstring jOuterPassword, jobjectArray jOuterKeyfilePaths, jint outerPim,
        jstring jHiddenPassword, jobjectArray jHiddenKeyfilePaths, jint hiddenPim,
        jint hiddenAlgorithm, jint hiddenHashAlg,
        jboolean /*quickFormat*/,
        jbyteArray /*entropyBytes*/,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    std::string path           = jstring_to_string(env, jPath);
    std::string outerPassword  = jstring_to_string(env, jOuterPassword);
    std::string hiddenPassword = jstring_to_string(env, jHiddenPassword);
    auto outerKeyfilePaths     = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths    = jstringArray_to_vector(env, jHiddenKeyfilePaths);

    if (path.empty() || outerPassword.empty() || hiddenPassword.empty()) return ERR_FILE;

    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) { LOGE("nativeCreateHiddenVolume: cannot open %s", path.c_str()); return ERR_FILE; }

    off_t fileSzOff = lseek(fd, 0, SEEK_END);
    if (fileSzOff < 0) { close(fd); return ERR_FILE; }
    uint64_t fileSize = (uint64_t)fileSzOff;

    uint64_t hidSz = (uint64_t)hiddenSizeBytes;
    /* Need room for both header regions + data offset + hidden data */
    if (fileSize < VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE + hidSz) {
        LOGE("nativeCreateHiddenVolume: file too small (%llu) for hidden size %llu",
             (unsigned long long)fileSize, (unsigned long long)hidSz);
        close(fd);
        return ERR_NO_SPACE;
    }

    /* ── Outer effective password ── */
    uint8_t outerEffPwd[VC_MAX_PWD_LEN] = {};
    int outerEffPwdLen = (int)outerPassword.size();
    if (outerEffPwdLen > VC_MAX_PWD_LEN) outerEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(outerEffPwd, outerPassword.c_str(), (size_t)outerEffPwdLen);
    apply_keyfiles_to_password(outerKeyfilePaths, outerEffPwd, &outerEffPwdLen);

    /* ── Hidden effective password ── */
    uint8_t hiddenEffPwd[VC_MAX_PWD_LEN] = {};
    int hiddenEffPwdLen = (int)hiddenPassword.size();
    if (hiddenEffPwdLen > VC_MAX_PWD_LEN) hiddenEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(hiddenEffPwd, hiddenPassword.c_str(), (size_t)hiddenEffPwdLen);
    apply_keyfiles_to_password(hiddenKeyfilePaths, hiddenEffPwd, &hiddenEffPwdLen);

    /* ── Read outer primary salt before authenticating ── */
    uint8_t outerPrimSalt[VC_HEADER_SALT_SIZE];
    if (pread(fd, outerPrimSalt, VC_HEADER_SALT_SIZE, 0) != VC_HEADER_SALT_SIZE) {
        memset(outerEffPwd,  0, sizeof(outerEffPwd));
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_READ;
    }

    /* ── Authenticate outer volume (primary header) ── */
    uint8_t outerMasterKey[192] = {};
    int outerMkLen = 0, outerAlgId = 0, outerHashId = 0;
    uint64_t outerDataSz = 0, outerDataOff = 0;
    int rc = read_vc_header(fd, 0,
                            (const char*)outerEffPwd, outerEffPwdLen,
                            outerMasterKey, &outerMkLen,
                            &outerDataSz, &outerDataOff,
                            &outerAlgId, &outerHashId,
                            (int)outerPim, nullptr);
    if (rc != ERR_OK) {
        LOGE("nativeCreateHiddenVolume: outer auth failed (%d)", rc);
        memset(outerEffPwd,  0, sizeof(outerEffPwd));
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_WRONG_PASSWORD;
    }

    /* ── Read outer backup salt ── */
    uint64_t backupAreaOff = fileSize - VC_BACKUP_AREA_SIZE;
    uint8_t outerBackSalt[VC_HEADER_SALT_SIZE];
    if (pread(fd, outerBackSalt, VC_HEADER_SALT_SIZE, (off_t)backupAreaOff) != VC_HEADER_SALT_SIZE) {
        memset(outerMasterKey, 0, sizeof(outerMasterKey));
        memset(outerEffPwd,    0, sizeof(outerEffPwd));
        memset(hiddenEffPwd,   0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_READ;
    }

    /* ── Re-write outer headers with field28 = hiddenVolSize ── */
    if (write_vc_header(fd, 0,
                        outerDataSz, outerDataOff,
                        outerMasterKey, outerAlgId, outerHashId,
                        (const char*)outerEffPwd, outerEffPwdLen,
                        (int)outerPim, hidSz, outerPrimSalt) != 0) {
        memset(outerMasterKey, 0, sizeof(outerMasterKey));
        memset(outerEffPwd,    0, sizeof(outerEffPwd));
        memset(hiddenEffPwd,   0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_FILE;
    }
    write_vc_header(fd, backupAreaOff,
                    outerDataSz, outerDataOff,
                    outerMasterKey, outerAlgId, outerHashId,
                    (const char*)outerEffPwd, outerEffPwdLen,
                    (int)outerPim, hidSz, outerBackSalt);
    memset(outerMasterKey, 0, sizeof(outerMasterKey));
    memset(outerEffPwd,    0, sizeof(outerEffPwd));

    /* ── Compute hidden data area geometry ── */
    /* Hidden data grows backwards from the start of the backup area */
    uint64_t hiddenDataOff = fileSize - VC_BACKUP_AREA_SIZE - hidSz;

    /* ── Generate hidden master key and write hidden headers ── */
    int hiddenAlgId = (int)hiddenAlgorithm;
    if (hiddenAlgId < 0 || hiddenAlgId >= NUM_ALGORITHMS) { close(fd); return ERR_FS; }
    int hiddenN     = ALGORITHMS[hiddenAlgId].n;
    uint8_t hiddenMasterKey[192] = {};
    if (!read_urandom(hiddenMasterKey, (size_t)(hiddenN * 64))) {
        LOGE("[hidden] /dev/urandom failed for hidden master key — aborting");
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_RAND;
    }

    /* Primary hidden header at VC_HIDDEN_HEADER_OFFSET; field28 = 0 in hidden headers */
    if (write_vc_header(fd, VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd, hiddenEffPwdLen,
                        (int)hiddenPim, 0, nullptr) != 0) {
        memset(hiddenMasterKey, 0, sizeof(hiddenMasterKey));
        memset(hiddenEffPwd,    0, sizeof(hiddenEffPwd));
        close(fd);
        return ERR_FILE;
    }
    /* Backup hidden header at fileSize - VC_HIDDEN_HEADER_OFFSET */
    write_vc_header(fd, fileSize - VC_HIDDEN_HEADER_OFFSET,
                    hidSz, hiddenDataOff,
                    hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                    (const char*)hiddenEffPwd, hiddenEffPwdLen,
                    (int)hiddenPim, 0, nullptr);
    memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));

    /* ── Format hidden area as FAT32 ── */
    int pdrv = alloc_drive(fd, hiddenDataOff, hidSz / VC_SECTOR_SIZE,
                           hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                           true, 0);
    memset(hiddenMasterKey, 0, sizeof(hiddenMasterKey));

    if (pdrv < 0) {
        LOGE("[5/5] No free drive slot");
        close(fd);
        return ERR_NO_SLOT;
    }

    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    BYTE work[4096];
    MKFS_PARM opts = { (FM_FAT | FM_FAT32) | FM_SFD, 2, 0, 0, 0 };
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
    }
    free_drive(pdrv);

    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)hidSz);
    close(fd);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
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
        jbyteArray /*entropyBytes*/,
        jobject progressListener)
{
    if (hiddenAlgorithm < 0 || hiddenAlgorithm >= NUM_ALGORITHMS) return ERR_UNSUPPORTED;
    if (hiddenSizeBytes < (jlong)(4 * 1024 * 1024)) return ERR_NO_SPACE;

    std::string outerPassword  = jstring_to_string(env, jOuterPassword);
    std::string hiddenPassword = jstring_to_string(env, jHiddenPassword);
    auto outerKeyfilePaths     = jstringArray_to_vector(env, jOuterKeyfilePaths);
    auto hiddenKeyfilePaths    = jstringArray_to_vector(env, jHiddenKeyfilePaths);

    if (outerPassword.empty() || hiddenPassword.empty()) return ERR_FILE;

    jmethodID progressMid = resolve_progress_mid(env, progressListener);

    int fd = dup((int)safFd);
    if (fd < 0) { LOGE("[fd/hidden] dup failed: errno=%d", errno); return ERR_FILE; }

    off_t fileSzOff = lseek(fd, 0, SEEK_END);
    if (fileSzOff < 0) { close(fd); return ERR_FILE; }
    uint64_t fileSize = (uint64_t)fileSzOff;

    uint64_t hidSz = (uint64_t)hiddenSizeBytes;
    if (fileSize < VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE + hidSz) {
        close(fd); return ERR_NO_SPACE;
    }

    uint8_t outerEffPwd[VC_MAX_PWD_LEN] = {};
    int outerEffPwdLen = (int)outerPassword.size();
    if (outerEffPwdLen > VC_MAX_PWD_LEN) outerEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(outerEffPwd, outerPassword.c_str(), (size_t)outerEffPwdLen);
    apply_keyfiles_to_password(outerKeyfilePaths, outerEffPwd, &outerEffPwdLen);

    uint8_t hiddenEffPwd[VC_MAX_PWD_LEN] = {};
    int hiddenEffPwdLen = (int)hiddenPassword.size();
    if (hiddenEffPwdLen > VC_MAX_PWD_LEN) hiddenEffPwdLen = VC_MAX_PWD_LEN;
    memcpy(hiddenEffPwd, hiddenPassword.c_str(), (size_t)hiddenEffPwdLen);
    apply_keyfiles_to_password(hiddenKeyfilePaths, hiddenEffPwd, &hiddenEffPwdLen);

    uint8_t outerPrimSalt[VC_HEADER_SALT_SIZE];
    if (pread(fd, outerPrimSalt, VC_HEADER_SALT_SIZE, 0) != VC_HEADER_SALT_SIZE) {
        memset(outerEffPwd, 0, sizeof(outerEffPwd));
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd); return ERR_READ;
    }

    uint8_t outerMasterKey[192] = {};
    int outerMkLen = 0, outerAlgId = 0, outerHashId = 0;
    uint64_t outerDataSz = 0, outerDataOff = 0;
    int rc = read_vc_header(fd, 0,
                            (const char*)outerEffPwd, outerEffPwdLen,
                            outerMasterKey, &outerMkLen,
                            &outerDataSz, &outerDataOff,
                            &outerAlgId, &outerHashId,
                            (int)outerPim, nullptr);
    if (rc != ERR_OK) {
        memset(outerEffPwd, 0, sizeof(outerEffPwd));
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd); return ERR_WRONG_PASSWORD;
    }

    uint64_t backupAreaOff = fileSize - VC_BACKUP_AREA_SIZE;
    uint8_t outerBackSalt[VC_HEADER_SALT_SIZE];
    if (pread(fd, outerBackSalt, VC_HEADER_SALT_SIZE, (off_t)backupAreaOff) != VC_HEADER_SALT_SIZE) {
        memset(outerMasterKey, 0, sizeof(outerMasterKey));
        memset(outerEffPwd,    0, sizeof(outerEffPwd));
        memset(hiddenEffPwd,   0, sizeof(hiddenEffPwd));
        close(fd); return ERR_READ;
    }

    if (write_vc_header(fd, 0,
                        outerDataSz, outerDataOff,
                        outerMasterKey, outerAlgId, outerHashId,
                        (const char*)outerEffPwd, outerEffPwdLen,
                        (int)outerPim, hidSz, outerPrimSalt) != 0) {
        memset(outerMasterKey, 0, sizeof(outerMasterKey));
        memset(outerEffPwd,    0, sizeof(outerEffPwd));
        memset(hiddenEffPwd,   0, sizeof(hiddenEffPwd));
        close(fd); return ERR_FILE;
    }
    write_vc_header(fd, backupAreaOff,
                    outerDataSz, outerDataOff,
                    outerMasterKey, outerAlgId, outerHashId,
                    (const char*)outerEffPwd, outerEffPwdLen,
                    (int)outerPim, hidSz, outerBackSalt);
    memset(outerMasterKey, 0, sizeof(outerMasterKey));
    memset(outerEffPwd,    0, sizeof(outerEffPwd));

    uint64_t hiddenDataOff = fileSize - VC_BACKUP_AREA_SIZE - hidSz;

    int hiddenAlgId = (int)hiddenAlgorithm;
    if (hiddenAlgId < 0 || hiddenAlgId >= NUM_ALGORITHMS) { close(fd); return ERR_FS; }
    int hiddenN     = ALGORITHMS[hiddenAlgId].n;
    uint8_t hiddenMasterKey[192] = {};
    if (!read_urandom(hiddenMasterKey, (size_t)(hiddenN * 64))) {
        memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));
        close(fd); return ERR_RAND;
    }

    if (write_vc_header(fd, VC_HIDDEN_HEADER_OFFSET,
                        hidSz, hiddenDataOff,
                        hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                        (const char*)hiddenEffPwd, hiddenEffPwdLen,
                        (int)hiddenPim, 0, nullptr) != 0) {
        memset(hiddenMasterKey, 0, sizeof(hiddenMasterKey));
        memset(hiddenEffPwd,    0, sizeof(hiddenEffPwd));
        close(fd); return ERR_FILE;
    }
    write_vc_header(fd, fileSize - VC_HIDDEN_HEADER_OFFSET,
                    hidSz, hiddenDataOff,
                    hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                    (const char*)hiddenEffPwd, hiddenEffPwdLen,
                    (int)hiddenPim, 0, nullptr);
    memset(hiddenEffPwd, 0, sizeof(hiddenEffPwd));

    int pdrv = alloc_drive(fd, hiddenDataOff, hidSz / VC_SECTOR_SIZE,
                           hiddenMasterKey, hiddenAlgId, (int)hiddenHashAlg,
                           true, 0);
    memset(hiddenMasterKey, 0, sizeof(hiddenMasterKey));

    if (pdrv < 0) { close(fd); return ERR_NO_SLOT; }

    char drvPath[8];
    snprintf(drvPath, sizeof(drvPath), "%d:", pdrv);
    BYTE work[4096];
    MKFS_PARM opts = { (FM_FAT | FM_FAT32) | FM_SFD, 2, 0, 0, 0 };
    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkfs(drvPath, &opts, work, sizeof(work));
    }
    free_drive(pdrv);

    report_progress(env, progressListener, progressMid, 1.0f, 0.f, (jlong)hidSz);
    close(fd);
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeGetVolumeType ──────────────────────────────────────── */
/* Returns 1 if the mounted volume is hidden, 0 if standard, -1 if invalid. */

extern "C" JNIEXPORT jint JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeGetVolumeType(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return -1;
    return g_drives[pdrv].isHidden ? 1 : 0;
}

/* ─── JNI: nativeHasHiddenVolume ────────────────────────────────────── */
/* Returns true if this outer volume was created with a hidden volume inside
   (i.e., field28 in the outer header was non-zero → hiddenBoundary > 0).  */

extern "C" JNIEXPORT jboolean JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeHasHiddenVolume(
        JNIEnv */*env*/, jobject /*thiz*/, jlong handle)
{
    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active) return JNI_FALSE;
    return (g_drives[pdrv].hiddenBoundary > 0) ? JNI_TRUE : JNI_FALSE;
}

