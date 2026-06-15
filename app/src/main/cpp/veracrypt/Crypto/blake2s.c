/*
 * BLAKE2s reference implementation — public domain (CC0).
 * Adapted from https://github.com/BLAKE2/BLAKE2/blob/master/ref/blake2s-ref.c
 *
 * Output length is fixed at 32 bytes (BLAKE2s-256).
 * No keyed-hash mode — HMAC is handled externally in arcanum_jni.cpp.
 */

#include "blake2.h"
#include <string.h>

/* ── Constants ─────────────────────────────────────────────────────────── */

static const uint32_t blake2s_IV[8] = {
    0x6A09E667UL, 0xBB67AE85UL, 0x3C6EF372UL, 0xA54FF53AUL,
    0x510E527FUL, 0x9B05688CUL, 0x1F83D9ABUL, 0x5BE0CD19UL
};

static const uint8_t blake2s_sigma[10][16] = {
    {  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15 },
    { 14, 10,  4,  8,  9, 15, 13,  6,  1, 12,  0,  2, 11,  7,  5,  3 },
    { 11,  8, 12,  0,  5,  2, 15, 13, 10, 14,  3,  6,  7,  1,  9,  4 },
    {  7,  9,  3,  1, 13, 12, 11, 14,  2,  6,  5, 10,  4,  0, 15,  8 },
    {  9,  0,  5,  7,  2,  4, 10, 15, 14,  1, 11, 12,  6,  8,  3, 13 },
    {  2, 12,  6, 10,  0, 11,  8,  3,  4, 13,  7,  5, 15, 14,  1,  9 },
    { 12,  5,  1, 15, 14, 13,  4, 10,  0,  7,  6,  3,  9,  2,  8, 11 },
    { 13, 11,  7, 14, 12,  1,  3,  9,  5,  0, 15,  4,  8,  6,  2, 10 },
    {  6, 15, 14,  9, 11,  3,  0,  8, 12,  2, 13,  7,  1,  4, 10,  5 },
    { 10,  2,  8,  4,  7,  6,  1,  5, 15, 11,  9, 14,  3, 12, 13,  0 },
};

/* ── Helpers ────────────────────────────────────────────────────────────── */

#define ROTR32(x, n)  (((x) >> (n)) | ((x) << (32 - (n))))

static uint32_t load32(const void *p) {
    const uint8_t *b = (const uint8_t *)p;
    return (uint32_t)b[0]
         | ((uint32_t)b[1] <<  8)
         | ((uint32_t)b[2] << 16)
         | ((uint32_t)b[3] << 24);
}

static void store32(void *p, uint32_t v) {
    uint8_t *b = (uint8_t *)p;
    b[0] = (uint8_t)(v      );
    b[1] = (uint8_t)(v >>  8);
    b[2] = (uint8_t)(v >> 16);
    b[3] = (uint8_t)(v >> 24);
}

/* ── Compression function ───────────────────────────────────────────────── */

static void blake2s_compress(blake2s_state *S, const uint8_t block[BLAKE2S_BLOCKBYTES])
{
    uint32_t m[16];
    uint32_t v[16];
    size_t i;

    for (i = 0; i < 16; i++)
        m[i] = load32(block + i * 4);

    for (i = 0; i < 8; i++) v[i] = S->h[i];
    v[ 8] = blake2s_IV[0];
    v[ 9] = blake2s_IV[1];
    v[10] = blake2s_IV[2];
    v[11] = blake2s_IV[3];
    v[12] = S->t[0] ^ blake2s_IV[4];
    v[13] = S->t[1] ^ blake2s_IV[5];
    v[14] = S->f[0] ^ blake2s_IV[6];
    v[15] = S->f[1] ^ blake2s_IV[7];

#define G(r, i, a, b, c, d)                                    \
    do {                                                        \
        a = a + b + m[blake2s_sigma[r][2*(i)+0]];              \
        d = ROTR32(d ^ a, 16);                                  \
        c = c + d;                                              \
        b = ROTR32(b ^ c, 12);                                  \
        a = a + b + m[blake2s_sigma[r][2*(i)+1]];              \
        d = ROTR32(d ^ a,  8);                                  \
        c = c + d;                                              \
        b = ROTR32(b ^ c,  7);                                  \
    } while (0)

#define ROUND(r)                                     \
    do {                                             \
        G(r, 0, v[ 0], v[ 4], v[ 8], v[12]);        \
        G(r, 1, v[ 1], v[ 5], v[ 9], v[13]);        \
        G(r, 2, v[ 2], v[ 6], v[10], v[14]);        \
        G(r, 3, v[ 3], v[ 7], v[11], v[15]);        \
        G(r, 4, v[ 0], v[ 5], v[10], v[15]);        \
        G(r, 5, v[ 1], v[ 6], v[11], v[12]);        \
        G(r, 6, v[ 2], v[ 7], v[ 8], v[13]);        \
        G(r, 7, v[ 3], v[ 4], v[ 9], v[14]);        \
    } while (0)

    ROUND(0); ROUND(1); ROUND(2); ROUND(3); ROUND(4);
    ROUND(5); ROUND(6); ROUND(7); ROUND(8); ROUND(9);

#undef G
#undef ROUND

    for (i = 0; i < 8; i++)
        S->h[i] ^= v[i] ^ v[i + 8];
}

/* ── Public API ─────────────────────────────────────────────────────────── */

int blake2s_init(blake2s_state *S, size_t outlen)
{
    /* Parameter block p[0..31]: all zero except p[0]=outlen, p[2]=fanout=1, p[3]=depth=1 */
    uint8_t p[32];
    size_t i;

    if (!outlen || outlen > BLAKE2S_OUTBYTES) return -1;

    memset(p, 0, sizeof(p));
    p[0] = (uint8_t)outlen;   /* digest length */
    p[1] = 0;                  /* key length (no key) */
    p[2] = 1;                  /* fanout */
    p[3] = 1;                  /* depth */

    memset(S, 0, sizeof(blake2s_state));
    S->outlen = outlen;

    for (i = 0; i < 8; i++)
        S->h[i] = blake2s_IV[i] ^ load32(p + i * 4);

    return 0;
}

int blake2s_update(blake2s_state *S, const void *pin, size_t inlen)
{
    const uint8_t *in = (const uint8_t *)pin;
    size_t left, fill;

    if (!inlen) return 0;

    left = S->buflen;
    fill = BLAKE2S_BLOCKBYTES - left;

    if (inlen > fill) {
        S->buflen = 0;
        memcpy(S->buf + left, in, fill);
        S->t[0] += BLAKE2S_BLOCKBYTES;
        S->t[1] += (S->t[0] < BLAKE2S_BLOCKBYTES) ? 1 : 0;
        blake2s_compress(S, S->buf);
        in    += fill;
        inlen -= fill;

        while (inlen > BLAKE2S_BLOCKBYTES) {
            S->t[0] += BLAKE2S_BLOCKBYTES;
            S->t[1] += (S->t[0] < BLAKE2S_BLOCKBYTES) ? 1 : 0;
            blake2s_compress(S, in);
            in    += BLAKE2S_BLOCKBYTES;
            inlen -= BLAKE2S_BLOCKBYTES;
        }
    }

    memcpy(S->buf + S->buflen, in, inlen);
    S->buflen += inlen;
    return 0;
}

int blake2s_final(blake2s_state *S, void *out, size_t outlen)
{
    uint8_t buffer[BLAKE2S_OUTBYTES];
    size_t i;

    if (!out || outlen < S->outlen) return -1;

    /* Set finalization flag and pad remaining buffer with zeros */
    S->f[0] = (uint32_t)-1;
    S->t[0] += (uint32_t)S->buflen;
    S->t[1] += (S->t[0] < (uint32_t)S->buflen) ? 1 : 0;
    memset(S->buf + S->buflen, 0, BLAKE2S_BLOCKBYTES - S->buflen);
    blake2s_compress(S, S->buf);

    for (i = 0; i < 8; i++)
        store32(buffer + i * 4, S->h[i]);

    memcpy(out, buffer, S->outlen);
    memset(buffer, 0, sizeof(buffer));
    memset(S, 0, sizeof(blake2s_state));
    return 0;
}

int blake2s(void *out, size_t outlen, const void *in, size_t inlen)
{
    blake2s_state S;
    int ret;
    if ((ret = blake2s_init(&S, outlen))    != 0) return ret;
    if ((ret = blake2s_update(&S, in, inlen)) != 0) return ret;
    return blake2s_final(&S, out, outlen);
}
