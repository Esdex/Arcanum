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

#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <ctime>

extern "C" {
#include "Crypto/Sha2.h"
#include "Crypto/Whirlpool.h"
#include "Crypto/Streebog.h"
#include "Crypto/blake2.h"
#include "argon2.h"
}

/* Per-hash iteration counts for non-system VeraCrypt volumes.
   IDs: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256. */
static const uint32_t VC_PBKDF2_ITERS_BY_HASH[] = { 500000, 500000, 500000, 500000, 500000 };

/* PIM iteration formulas (VeraCrypt spec).
   pim == 0 → use default table above. */
uint32_t vc_get_iterations(int hashId, int pim) {
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

/* ─── Generic HMAC / PBKDF2 (hash-traits dispatch) ──────────────────── */
/*
 * Single implementation shared by all 5 PBKDF2 PRFs (SHA-512, SHA-256,
 * Whirlpool, Streebog, BLAKE2s-256). Each hash exposes normalized
 * init/update/final callbacks + block/output sizes via HashTraits so
 * hmac_generic()/pbkdf2_generic() are written once instead of five times.
 * Block sizes: SHA-512=128/64out, SHA-256=64/32, Whirlpool=64/64,
 * Streebog=64/64, BLAKE2s=64/32.
 *
 * Note: there are no separate named "pbkdf2_sha512()"-style entry points —
 * pbkdf2_generic() dispatching on HashTraits IS the entry point for all five
 * PRFs; write_vc_header (vc_header.cpp) and derive_header_key (below) are
 * its only two call sites.
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
void pbkdf2_generic(const HashTraits *t, const uint8_t *pwd, int plen,
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
const HashTraits* hash_traits_for(int hashId) {
    return (hashId >= 0 && hashId <= 4) ? &HASH_TRAITS[hashId] : &HASH_TRAITS[0];
}

#ifdef ARCANUM_KAT_HOOKS
/* Debug-only bridge for NativeKatTest (see jni_kat.cpp, added to the build
   only when CMAKE_BUILD_TYPE is Debug). hmac_generic() is static here because
   nothing in production needs it directly; the test calls it through this
   wrapper rather than re-implementing HMAC, so what gets verified against
   RFC 4231 is the exact function PBKDF2 runs. */
void kat_hmac(int hashId, const uint8_t *key, int klen,
              const uint8_t *msg, size_t mlen, uint8_t *out) {
    hmac_generic(hash_traits_for(hashId), key, klen, msg, mlen, out);
}
#endif

/* ─── VeraCrypt header authenticate ─────────────────────────────────── */

/* ─── Argon2id (#177) ────────────────────────────────────────────────── */

/*
 * VeraCrypt's get_argon2_params, formula for formula: the PIM is the cost dial
 * and nothing about it is stored in the volume, so the same PIM has to be given
 * again to open it. A pim of 0 means 12, which is 416 MiB and 6 passes - a
 * desktop's number, and the reason the PRF is never tried on a guess here.
 *
 *   memory = min(64 + (pim - 1) * 32, 1024) MiB
 *   passes = pim <= 31 ? 3 + (pim - 1) / 3 : 13 + (pim - 31)
 */
void vc_argon2_params(int pim, uint32_t *tCost, uint32_t *mCostKiB) {
    if (pim < 0) pim = 0;
    if (pim == 0) pim = 12;
    int mib = 64 + (pim - 1) * 32;
    if (mib > 1024) mib = 1024;
    if (mCostKiB) *mCostKiB = (uint32_t)mib * 1024u;
    if (tCost)    *tCost    = (pim <= 31) ? (uint32_t)(3 + (pim - 1) / 3)
                                          : (uint32_t)(13 + (pim - 31));
}

uint64_t vc_argon2_memory_bytes(int hashId, int pim) {
    if (hashId != VC_HASH_ARGON2ID) return 0;
    uint32_t mCostKiB = 0;
    vc_argon2_params(pim, nullptr, &mCostKiB);
    return (uint64_t)mCostKiB * 1024ull;
}

uint64_t vc_memory_available_bytes(void) {
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[256];
    unsigned long long kib = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "MemAvailable:", 13) == 0) {
            kib = strtoull(line + 13, nullptr, 10);
            break;
        }
    }
    fclose(f);
    return (uint64_t)kib * 1024ull;
}

/*
 * Room left for the rest of the phone once the derivation has taken its share.
 * Without it the allocation can succeed and the system then kills something -
 * possibly this app, mid-mount - to pay for it. Refusing up front with a number
 * the user can read beats being killed.
 */
static const uint64_t ARGON2_HEADROOM_BYTES = 256ull * 1024 * 1024;

static int derive_header_key_argon2(const uint8_t *password, int pwd_len,
                                    const uint8_t *salt, int pim, uint8_t out[192],
                                    bool allowLowMemory) {
    uint32_t tCost = 0, mCostKiB = 0;
    vc_argon2_params(pim, &tCost, &mCostKiB);
    const uint64_t need = (uint64_t)mCostKiB * 1024ull;
    const uint64_t have = vc_memory_available_bytes();

    /* Two different situations, and only one of them is arguable. Below `need`
     * the allocation cannot succeed at all and there is nothing to insist on.
     * Between `need` and `need + headroom` it can succeed, at the risk of the
     * system killing something to pay for it - which is the user's call to make,
     * so it is refused by default and can be repeated with [allowLowMemory]. */
    if (have != 0 && (have < need || (!allowLowMemory && have < need + ARGON2_HEADROOM_BYTES))) {
        LOGE("[argon2] not attempted: needs %llu MiB, the device has %llu MiB to spare%s",
             (unsigned long long)(need >> 20), (unsigned long long)(have >> 20),
             (have < need) ? "" : " (can be insisted on)");
        return ERR_ARGON2_MEMORY;
    }

    /* 192 bytes exactly, whatever the cipher cascade needs of them: unlike
     * PBKDF2, the output length is an input to Argon2, so deriving fewer would
     * derive something else entirely. */
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = argon2id_hash_raw(tCost, mCostKiB, 1,
                               password, (size_t)pwd_len,
                               salt, (size_t)VC_HEADER_SALT_SIZE,
                               out, 192, nullptr);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    /* What it actually cost on this device. Names no path and no secret, and is
     * debug-only like every other line here (#174). */
    LOGI("[argon2] %u MiB, %u passes: %ld ms",
         mCostKiB / 1024u, tCost,
         (long)((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000));
    if (rc != ARGON2_OK) {
        secure_memset((volatile uint8_t *)out, 0, 192);
        LOGE("[argon2] derivation failed: %d", rc);
        return (rc == ARGON2_MEMORY_ALLOCATION_ERROR) ? ERR_ARGON2_MEMORY : ERR_UNSUPPORTED;
    }
    return ERR_OK;
}

/* A PRF id outside [0,5] leaves `out` untouched (callers zero-initialize it). */
int derive_header_key(int hi, const uint8_t *password, int pwd_len,
                       const uint8_t *salt, int pim, uint8_t out[192],
                       bool allowLowMemory) {
    if (hi == VC_HASH_ARGON2ID)
        return derive_header_key_argon2(password, pwd_len, salt, pim, out, allowLowMemory);
    if (hi < 0 || hi > 4) return ERR_UNSUPPORTED;
    uint32_t iters = vc_get_iterations(hi, pim);
    pbkdf2_generic(&HASH_TRAITS[hi], password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192);
    return ERR_OK;
}
