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

extern "C" {
#include "Crypto/Sha2.h"
#include "Crypto/Whirlpool.h"
#include "Crypto/Streebog.h"
#include "Crypto/blake2.h"
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

/* ─── VeraCrypt header authenticate ─────────────────────────────────── */

/* hi outside [0,4] leaves `out` untouched (matches the original per-hash
 * switch's default: break — callers zero-initialize `out` before calling). */
void derive_header_key(int hi, const uint8_t *password, int pwd_len,
                        const uint8_t *salt, int pim, uint8_t out[192]) {
    if (hi < 0 || hi > 4) return;
    uint32_t iters = vc_get_iterations(hi, pim);
    pbkdf2_generic(&HASH_TRAITS[hi], password, pwd_len, salt, VC_HEADER_SALT_SIZE, iters, out, 192);
}
