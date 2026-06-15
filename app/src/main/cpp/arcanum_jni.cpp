/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 *
 * This file incorporates code from VeraCrypt
 * Copyright (C) 2013-2025 AM Crypto
 * Licensed under Apache License 2.0
 */

#include "arcanum_impl.h"

#include <jni.h>
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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
   IDs: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256, 5=RIPEMD-160. */
static const uint32_t VC_PBKDF2_ITERS_BY_HASH[] = { 500000, 500000, 500000, 500000, 500000, 655331 };

/* PIM iteration formulas (VeraCrypt spec).
   pim == 0 → use default table above. */
static uint32_t vc_get_iterations(int hashId, int pim) {
    if (pim <= 0) {
        return (hashId >= 0 && hashId <= 5) ? VC_PBKDF2_ITERS_BY_HASH[hashId] : 500000U;
    }
    switch (hashId) {
        case 0: return 15000U + (uint32_t)pim * 1000U; /* SHA-512      */
        case 1: return  2048U + (uint32_t)pim * 2048U; /* SHA-256      */
        case 2: return 15000U + (uint32_t)pim * 1000U; /* Whirlpool    */
        case 3: return 15000U + (uint32_t)pim * 1000U; /* Streebog     */
        case 4: return 15000U + (uint32_t)pim * 1000U; /* BLAKE2s-256  */
        case 5: return  1000U + (uint32_t)pim * 2000U; /* RIPEMD-160   */
        default: return 500000U;
    }
}

/* Error codes (match Kotlin companion object) */
#define ERR_OK              0
#define ERR_FILE            -1
#define ERR_READ            -2
#define ERR_WRONG_PASSWORD  -3
#define ERR_UNSUPPORTED     -4
#define ERR_NO_SPACE        -5
#define ERR_NO_SLOT         -6
#define ERR_FS              -7

/* Key schedule sizes for Serpent and Camellia (others use their structs) */
#define SERPENT_KS_SIZE    (140 * 4)   /* 560 bytes */
#define CAMELLIA_KS_SIZE   (34 * 8)    /* 272 bytes */

/* ─── Algorithm table ───────────────────────────────────────────────── */
/* Algorithm IDs = Kotlin CipherAlgorithm.ordinal.
   Cipher list is in ENCRYPTION order (first cipher applied first).    */

struct AlgDef { int n; int c[3]; };

static const AlgDef ALGORITHMS[15] = {
    /* 0  AES               */ {1, {CIPHER_AES,        0,                 0              }},
    /* 1  Serpent           */ {1, {CIPHER_SERPENT,    0,                 0              }},
    /* 2  Twofish           */ {1, {CIPHER_TWOFISH,    0,                 0              }},
    /* 3  Camellia          */ {1, {CIPHER_CAMELLIA,   0,                 0              }},
    /* 4  Kuznyechik        */ {1, {CIPHER_KUZNYECHIK, 0,                 0              }},
    /* 5  AES→Twofish       */ {2, {CIPHER_AES,        CIPHER_TWOFISH,    0              }},
    /* 6  AES→Twofish→Serp  */ {3, {CIPHER_AES,        CIPHER_TWOFISH,    CIPHER_SERPENT }},
    /* 7  Serpent→AES       */ {2, {CIPHER_SERPENT,    CIPHER_AES,        0              }},
    /* 8  Serp→Twofish→AES  */ {3, {CIPHER_SERPENT,    CIPHER_TWOFISH,    CIPHER_AES     }},
    /* 9  Twofish→Serpent   */ {2, {CIPHER_TWOFISH,    CIPHER_SERPENT,    0              }},
    /* 10 Camellia→Kuz      */ {2, {CIPHER_CAMELLIA,   CIPHER_KUZNYECHIK, 0              }},
    /* 11 Camellia→Serpent  */ {2, {CIPHER_CAMELLIA,   CIPHER_SERPENT,    0              }},
    /* 12 Kuz→AES           */ {2, {CIPHER_KUZNYECHIK, CIPHER_AES,        0              }},
    /* 13 Kuz→Serp→Camellia */ {3, {CIPHER_KUZNYECHIK, CIPHER_SERPENT,    CIPHER_CAMELLIA}},
    /* 14 Kuz→Twofish       */ {2, {CIPHER_KUZNYECHIK, CIPHER_TWOFISH,    0              }},
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
            for (int j = 0; j < ctx->num; j++)
                init_layer_ks(&ctx->layers[j], ALGORITHMS[algId].c[j], masterKey + j*64);
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
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
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
        free(saltb);
        int cp = (b == blocks && dklen % 32 != 0) ? (dklen % 32) : 32;
        memcpy(dk + (b-1)*32, T, (size_t)cp);
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
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
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
        free(saltb);
        int cp = (b == blocks && dklen % 64 != 0) ? (dklen % 64) : 64;
        memcpy(dk + (b-1)*64, T, (size_t)cp);
    }
}

/* ─── RIPEMD-160 ────────────────────────────────────────────────────── */
/*
 * Self-contained RIPEMD-160 implementation for HMAC/PBKDF2.
 * VeraCrypt uses RIPEMD-160 with 655,331 iterations for non-system volumes
 * (removed from the UI in 1.24, but containers created with older versions
 * still need it for compatibility).
 */

#define RMD_ROL(x, n) (((x) << (n)) | ((x) >> (32 - (n))))

static const uint32_t rmd_KL[5] = { 0x00000000UL, 0x5A827999UL, 0x6ED9EBA1UL, 0x8F1BBCDCUL, 0xA953FD4EUL };
static const uint32_t rmd_KR[5] = { 0x50A28BE6UL, 0x5C4DD124UL, 0x6D703EF3UL, 0x7A6D76E9UL, 0x00000000UL };
static const int rmd_RL[5][16] = {
    { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,15 },
    { 7, 4,13, 1,10, 6,15, 3,12, 0, 9, 5, 2,14,11, 8 },
    { 3,10,14, 4, 9,15, 8, 1, 2, 7, 0, 6,13,11, 5,12 },
    { 1, 9,11,10, 0, 8,12, 4,13, 3, 7,15,14, 5, 6, 2 },
    { 4, 0, 5, 9, 7,12, 2,10,14, 1, 3, 8,11, 6,15,13 }
};
static const int rmd_RR[5][16] = {
    { 5,14, 7, 0, 9, 2,11, 4,13, 6,15, 8, 1,10, 3,12 },
    { 6,11, 3, 7, 0,13, 5,10,14,15, 8,12, 4, 9, 1, 2 },
    {15, 5, 1, 3, 7,14, 6, 9,11, 8,12, 2,10, 0, 4,13 },
    { 8, 6, 4, 1, 3,11,15, 0, 5,12, 2,13, 9, 7,10,14 },
    {12,15,10, 4, 1, 5, 8, 7, 6, 2,13,14, 0, 3, 9,11 }
};
static const int rmd_SL[5][16] = {
    {11,14,15,12, 5, 8, 7, 9,11,13,14,15, 6, 7, 9, 8 },
    { 7, 6, 8,13,11, 9, 7,15, 7,12,15, 9,11, 7,13,12 },
    {11,13, 6, 7,14, 9,13,15,14, 8,13, 6, 5,12, 7, 5 },
    {11,12,14,15,14,15, 9, 8, 9,14, 5, 6, 8, 6, 5,12 },
    { 9,15, 5,11, 6, 8,13,12, 5,12,13,14,11, 8, 5, 6 }
};
static const int rmd_SR[5][16] = {
    { 8, 9, 9,11,13,15,15, 5, 7, 7, 8,11,14,14,12, 6 },
    { 9,13,15, 7,12, 8, 9,11, 7, 7,12, 7, 6,15,13,11 },
    { 9, 7,15,11, 8, 6, 6,14,12,13, 5,14,13,13, 7, 5 },
    {15, 5, 8,11,14,14, 6,14, 6, 9,12, 9,12, 5,15, 8 },
    { 8, 5,12, 9,12, 5,14, 6, 8,13, 6, 5,15,13,11,11 }
};

static inline uint32_t rmd_f(int j, uint32_t x, uint32_t y, uint32_t z) {
    if (j <  16) return x ^ y ^ z;
    if (j <  32) return (x & y) | (~x & z);
    if (j <  48) return (x | ~y) ^ z;
    if (j <  64) return (x & z) | (y & ~z);
    return x ^ (y | ~z);
}

static void rmd160_compress(uint32_t h[5], const uint8_t block[64]) {
    uint32_t w[16];
    for (int i = 0; i < 16; i++)
        w[i] = ((uint32_t)block[i*4])|(((uint32_t)block[i*4+1])<<8)
              |(((uint32_t)block[i*4+2])<<16)|(((uint32_t)block[i*4+3])<<24);

    uint32_t al=h[0],bl=h[1],cl=h[2],dl=h[3],el=h[4];
    uint32_t ar=h[0],br=h[1],cr=h[2],dr=h[3],er=h[4];

    for (int r = 0; r < 80; r++) {
        int round = r / 16;
        uint32_t tl = RMD_ROL(al + rmd_f(r,bl,cl,dl) + w[rmd_RL[round][r%16]] + rmd_KL[round], rmd_SL[round][r%16]) + el;
        al=el; el=dl; dl=RMD_ROL(cl,10); cl=bl; bl=tl;
        uint32_t tr = RMD_ROL(ar + rmd_f(79-r,br,cr,dr) + w[rmd_RR[round][r%16]] + rmd_KR[round], rmd_SR[round][r%16]) + er;
        ar=er; er=dr; dr=RMD_ROL(cr,10); cr=br; br=tr;
    }
    uint32_t t = h[1]+cl+dr; h[1]=h[2]+dl+er; h[2]=h[3]+el+ar;
    h[3]=h[4]+al+br; h[4]=h[0]+bl+cr; h[0]=t;
}

typedef struct { uint32_t h[5]; uint8_t buf[64]; uint64_t len; int buflen; } rmd160_ctx;

static void rmd160_init(rmd160_ctx *c) {
    c->h[0]=0x67452301UL; c->h[1]=0xEFCDAB89UL; c->h[2]=0x98BADCFEUL;
    c->h[3]=0x10325476UL; c->h[4]=0xC3D2E1F0UL;
    c->len=0; c->buflen=0;
}

static void rmd160_update(rmd160_ctx *c, const uint8_t *in, size_t len) {
    c->len += len;
    while (len > 0) {
        size_t cp = (size_t)(64 - c->buflen); if (cp > len) cp = len;
        memcpy(c->buf + c->buflen, in, cp);
        c->buflen += (int)cp; in += cp; len -= cp;
        if (c->buflen == 64) { rmd160_compress(c->h, c->buf); c->buflen = 0; }
    }
}

static void rmd160_final(rmd160_ctx *c, uint8_t out[20]) {
    uint64_t bits = c->len * 8;
    uint8_t pad = 0x80;
    rmd160_update(c, &pad, 1);
    pad = 0;
    while (c->buflen != 56) rmd160_update(c, &pad, 1);
    uint8_t lb[8];
    for (int i=0;i<8;i++) lb[i]=(uint8_t)(bits>>(i*8));
    rmd160_update(c, lb, 8);
    for (int i=0;i<5;i++) {
        out[i*4]=(uint8_t)c->h[i]; out[i*4+1]=(uint8_t)(c->h[i]>>8);
        out[i*4+2]=(uint8_t)(c->h[i]>>16); out[i*4+3]=(uint8_t)(c->h[i]>>24);
    }
}

static void hmac_rmd160(const uint8_t *key, int klen,
                        const uint8_t *msg, size_t mlen,
                        uint8_t out[20]) {
    uint8_t k[64] = {};
    if (klen > 64) { rmd160_ctx c; rmd160_init(&c); rmd160_update(&c,(uint8_t*)key,(size_t)klen); rmd160_final(&c,k); }
    else memcpy(k, key, (size_t)klen);
    uint8_t ipad[64], opad[64];
    for (int i=0;i<64;i++) { ipad[i]=k[i]^0x36; opad[i]=k[i]^0x5C; }
    rmd160_ctx c;
    rmd160_init(&c); rmd160_update(&c,ipad,64); rmd160_update(&c,msg,mlen); rmd160_final(&c,out);
    rmd160_init(&c); rmd160_update(&c,opad,64); rmd160_update(&c,out,20); rmd160_final(&c,out);
}

/* PBKDF2-RIPEMD-160: VeraCrypt uses 655331 iterations for non-system volumes */
static void pbkdf2_rmd160(const uint8_t *pwd, int plen,
                          const uint8_t *salt, int slen,
                          uint32_t iters, uint8_t *dk, int dklen) {
    int blocks = (dklen + 19) / 20;
    for (int b = 1; b <= blocks; b++) {
        auto *saltb = static_cast<uint8_t*>(malloc((size_t)(slen + 4)));
        if (!saltb) return;
        memcpy(saltb, salt, (size_t)slen);
        saltb[slen]   = (uint8_t)((b >> 24) & 0xFF);
        saltb[slen+1] = (uint8_t)((b >> 16) & 0xFF);
        saltb[slen+2] = (uint8_t)((b >>  8) & 0xFF);
        saltb[slen+3] = (uint8_t)(b & 0xFF);
        uint8_t U[20], T[20];
        hmac_rmd160(pwd, plen, saltb, (size_t)(slen + 4), U);
        memcpy(T, U, 20);
        for (uint32_t i = 1; i < iters; i++) {
            hmac_rmd160(pwd, plen, U, 20, U);
            for (int j = 0; j < 20; j++) T[j] ^= U[j];
        }
        free(saltb);
        int cp = (b == blocks && dklen % 20 != 0) ? (dklen % 20) : 20;
        memcpy(dk + (b-1)*20, T, (size_t)cp);
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
        free(saltb);
        int cp = (b == blocks && dklen % 32 != 0) ? (dklen % 32) : 32;
        memcpy(dk + (b-1)*32, T, (size_t)cp);
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

static void read_urandom(uint8_t *buf, size_t len) {
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) { read(fd, buf, len); close(fd); }
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
    uint8_t salt[VC_HEADER_SALT_SIZE];
    if (existingSalt)
        memcpy(salt, existingSalt, VC_HEADER_SALT_SIZE);
    else
        read_urandom(salt, sizeof(salt));

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

    /* Encrypt body using the cascade (in order: first cipher first) */
    for (int i = 0; i < n; i++)
        xts_crypt_temp(ALGORITHMS[algId].c[i], derivedKey + i*64, body, VC_HEADER_BODY_SIZE, 0, true);

    uint8_t rawHeader[VC_HEADER_SIZE] = {};
    memcpy(rawHeader, salt, VC_HEADER_SALT_SIZE);
    memcpy(rawHeader + VC_HEADER_BODY_OFFSET, body, VC_HEADER_BODY_SIZE);

    ssize_t w = pwrite(fd, rawHeader, VC_HEADER_SIZE, (off_t)fileOff);
    memset(body,       0, sizeof(body));
    memset(derivedKey, 0, sizeof(derivedKey));
    return (w == VC_HEADER_SIZE) ? 0 : -1;
}

/* ─── VeraCrypt header authenticate ─────────────────────────────────── */
/*
 * Tries all 4 hashes × 15 cipher algorithms (60 combinations max).
 * On success fills masterKey (n*64 bytes), outMkLen, outAlgId, dataSz, dataOff.
 */
static int read_vc_header(int fd, uint64_t fileOff,
                          const char *password, int pwd_len,
                          uint8_t *masterKey, int *outMkLen,
                          uint64_t *dataSz, uint64_t *dataOff,
                          int *outAlgId, int *outHashId = nullptr,
                          int pim = 0,
                          uint64_t *outHiddenVolSize = nullptr) {
    uint8_t rawHeader[VC_HEADER_SIZE];
    if (pread(fd, rawHeader, VC_HEADER_SIZE, (off_t)fileOff) != VC_HEADER_SIZE) return ERR_READ;

    const uint8_t *salt = rawHeader;          /* first 64 bytes */

    static const int NUM_HASHES = 6;

    uint8_t allDerivedKeys[NUM_HASHES][192];
    memset(allDerivedKeys, 0, sizeof(allDerivedKeys));

    for (int hi = 0; hi < NUM_HASHES; hi++) {

        uint32_t iters = vc_get_iterations(hi, pim);
        switch (hi) {
            case 0: pbkdf2_sha512   ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
            case 1: pbkdf2_sha256   ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
            case 2: pbkdf2_whirlpool((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
            case 3: pbkdf2_streebog ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
            case 4: pbkdf2_blake2s  ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
            case 5: pbkdf2_rmd160   ((uint8_t*)password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, allDerivedKeys[hi], 192); break;
        }

        uint8_t *derivedKey = allDerivedKeys[hi];

        for (int ai = 0; ai < NUM_ALGORITHMS; ai++) {
            uint8_t body[VC_HEADER_BODY_SIZE];
            memcpy(body, rawHeader + VC_HEADER_BODY_OFFSET, VC_HEADER_BODY_SIZE);

            int n = ALGORITHMS[ai].n;
            /* Decrypt in reverse cipher order */
            for (int ci = n - 1; ci >= 0; ci--)
                xts_crypt_temp(ALGORITHMS[ai].c[ci], derivedKey + ci*64, body, VC_HEADER_BODY_SIZE, 0, false);

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
            if ((p[1] & 0xC0) != 0x80) return false;
            p += 2;
        } else if ((*p & 0xF0) == 0xE0) {
            if ((p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80) return false;
            p += 3;
        } else if ((*p & 0xF8) == 0xF0) {
            if ((p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80 || (p[3] & 0xC0) != 0x80) return false;
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

static void report_progress(JNIEnv *env, jobject listener,
                             float frac, float speedMbps, jlong written) {
    if (!listener) return;
    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(FFJ)V");
    if (mid) env->CallVoidMethod(listener, mid, frac, speedMbps, written);
    env->DeleteLocalRef(cls);
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
    int n     = ALGORITHMS[algId].n;

    uint64_t dataSize = (uint64_t)sizeBytes;
    uint64_t fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE;

    int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { LOGE("[2/6] Cannot open/create: %s", path.c_str()); return ERR_FILE; }

    if (ftruncate(fd, (off_t)fileSize) != 0) {
        LOGE("[create] ftruncate failed — disk full?");
        close(fd); unlink(path.c_str()); return ERR_NO_SPACE;
    }

    uint8_t masterKey[192] = {};
    read_urandom(masterKey, (size_t)(n * 64));

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

    /* Fill data area */
    if (!quickFormat) {
        const size_t CHUNK = 65536;
        auto *rnd = static_cast<uint8_t*>(malloc(CHUNK));
        if (rnd) {
            uint64_t remaining = dataSize, offset = VC_DATA_OFFSET;
            int rfd = open("/dev/urandom", O_RDONLY);
            auto t0 = (uint64_t)time(nullptr);
            while (remaining > 0) {
                size_t sz = (remaining > CHUNK) ? CHUNK : (size_t)remaining;
                if (rfd >= 0) read(rfd, rnd, sz);
                pwrite(fd, rnd, sz, (off_t)offset);
                remaining -= sz; offset += sz;
                uint64_t written = dataSize - remaining;
                float frac = (float)written / (float)dataSize;
                uint64_t elapsed = (uint64_t)time(nullptr) - t0;
                float speed = elapsed > 0 ? (float)(written/1048576UL)/(float)elapsed : 10.f;
                report_progress(env, progressListener, frac, speed, (jlong)written);
            }
            if (rfd >= 0) close(rfd);
            free(rnd);
        }
    } else {
        report_progress(env, progressListener, 0.5f, 500.f, (jlong)(dataSize/2));
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

    report_progress(env, progressListener, 1.0f, 0.f, (jlong)dataSize);
    close(fd);
    return ERR_OK;
}

/* ─── JNI: nativeOpenContainer ──────────────────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_zip_arcanum_crypto_VeraCryptEngine_nativeOpenContainer(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPath, jstring jPassword, jobjectArray jKeyfilePaths,
        jint pim)
{
    std::string path     = jstring_to_string(env, jPath);
    std::string password = jstring_to_string(env, jPassword);
    auto keyfilePaths    = jstringArray_to_vector(env, jKeyfilePaths);

    uint8_t effPwd[VC_MAX_PWD_LEN] = {};
    int effPwdLen = (int)password.size();
    if (effPwdLen > VC_MAX_PWD_LEN) effPwdLen = VC_MAX_PWD_LEN;
    memcpy(effPwd, password.c_str(), (size_t)effPwdLen);
    apply_keyfiles_to_password(keyfilePaths, effPwd, &effPwdLen);

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) { LOGE("Cannot open: %s", path.c_str()); return (jlong)ERR_FILE; }

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

    for (int ti = 0; ti < 4 && rc != ERR_OK; ti++) {
        if (tryOffsets[ti] + VC_HEADER_SIZE > fileSize) continue;
        uint64_t hvSz = 0;
        rc = read_vc_header(fd, tryOffsets[ti], (const char*)effPwd, effPwdLen,
                            masterKey, &mkLen, &dataSz, &dataOff, &algId, &hashId, (int)pim, &hvSz);
        if (rc == ERR_OK) {
            authIsHidden = tryIsHidden[ti];
            hiddenVolSize = hvSz;
        }
    }

    memset(effPwd, 0, sizeof(effPwd));

    if (rc != ERR_OK) {
        memset(masterKey, 0, sizeof(masterKey));
        close(fd); return (jlong)rc;
    }

    uint64_t hiddenBoundary = 0;
    if (!authIsHidden && hiddenVolSize > 0)
        hiddenBoundary = dataOff + dataSz - hiddenVolSize;

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
    jmethodID ctor = env->GetMethodID(infoCls, "<init>",
                         "(Ljava/lang/String;Ljava/lang/String;JZJ)V");

    int pdrv = (int)handle;
    if (pdrv < 0 || pdrv >= MAX_DRIVES || !g_drives[pdrv].active)
        return env->NewObjectArray(0, infoCls, nullptr);

    std::string dirPath = jstring_to_string(env, jDirPath);
    char fullPath[512];
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, dirPath.c_str());

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

    std::string path = jstring_to_string(env, jFilePath);
    char fullPath[512];
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());

    FIL fil;
    std::lock_guard<std::mutex> lock(g_fatfs_mutex);
    if (f_open(&fil, fullPath, FA_READ) != FR_OK) return env->NewByteArray(0);
    if (f_lseek(&fil, (FSIZE_t)offset) != FR_OK) { f_close(&fil); return env->NewByteArray(0); }

    jbyteArray result = env->NewByteArray(length);
    jbyte *buf = env->GetByteArrayElements(result, nullptr);
    UINT br = 0;
    f_read(&fil, buf, (UINT)length, &br);
    env->ReleaseByteArrayElements(result, buf, 0);
    f_close(&fil);

    if ((jint)br < length) {
        jbyteArray trimmed = env->NewByteArray((jsize)br);
        env->SetByteArrayRegion(trimmed, 0, (jsize)br,
                                env->GetByteArrayElements(result, nullptr));
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
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());

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
    UINT   bw   = 0;
    FRESULT fr  = f_write(&fil, data, (UINT)len, &bw);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    f_close(&fil);
    return (fr == FR_OK && (jint)bw == len) ? ERR_OK : ERR_FS;
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
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());

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
    snprintf(fullOldPath, sizeof(fullOldPath), "%d:%s", pdrv, oldPath.c_str());
    snprintf(fullNewPath, sizeof(fullNewPath), "%d:%s", pdrv, newPath.c_str());

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
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());

    FRESULT fr;
    {
        std::lock_guard<std::mutex> lock(g_fatfs_mutex);
        fr = f_mkdir(fullPath);
    }
    return (fr == FR_OK) ? ERR_OK : ERR_FS;
}

/* ─── JNI: nativeDeleteDirectory ────────────────────────────────────── */
/* Recursive delete — caller must NOT hold g_fatfs_mutex. */

static FRESULT unlink_recursive_locked(const char *fullPath) {
    FRESULT fr = f_unlink(fullPath);
    if (fr != FR_DENIED) return fr; /* FR_DENIED = directory not empty */

    DIR dir;
    FILINFO fno;
    fr = f_opendir(&dir, fullPath);
    if (fr != FR_OK) return fr;

    char entryPath[512];
    while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
        snprintf(entryPath, sizeof(entryPath), "%s/%s", fullPath, fno.fname);
        unlink_recursive_locked(entryPath);
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
    snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, path.c_str());

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
    int hiddenN     = ALGORITHMS[hiddenAlgId].n;
    uint8_t hiddenMasterKey[192] = {};
    read_urandom(hiddenMasterKey, (size_t)(hiddenN * 64));

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

    report_progress(env, progressListener, 1.0f, 0.f, (jlong)hidSz);
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

