/*
 * Crypto.h — cipher dispatch for VeraCrypt's Xts.c (Arcanum embedded build).
 *
 * Provides EncipherBlock, DecipherBlock, EncipherBlocks, DecipherBlocks,
 * CipherSupportsIntraDataUnitParallelization, and the XTS size constants.
 *
 * Key schedule pointer convention (matches callers in arcanum_jni.cpp):
 *   AES:         EncipherBlock  → (aes_encrypt_ctx*)ks
 *                DecipherBlock  → (aes_decrypt_ctx*)ks
 *                Tweak (ks2)    → always aes_encrypt_ctx* (always encrypts)
 *   Serpent:     uint8_t ks[]  — same schedule for both directions
 *   Twofish:     TwofishInstance* — same instance for both directions
 *   Camellia:    uint8_t ks[]  — same schedule for both directions
 *   Kuznyechik:  kuznyechik_kds* — same schedule for both directions
 */
#ifndef ARCANUM_CRYPTO_H
#define ARCANUM_CRYPTO_H

#include <string.h>

/* XTS algorithm constants */
#define BYTES_PER_XTS_BLOCK         16
#define BLOCKS_PER_XTS_DATA_UNIT    32
#define ENCRYPTION_DATA_UNIT_SIZE   512

/* Cipher IDs — must match CIPHER_* in arcanum_impl.h */
#define CIPHER_AES        0
#define CIPHER_SERPENT    1
#define CIPHER_TWOFISH    2
#define CIPHER_CAMELLIA   3
#define CIPHER_KUZNYECHIK 4

/* Pull in each cipher's key-schedule type and function declarations.
   Paths are relative to this file (veracrypt/Common/). */
#include "../Crypto/Aes.h"
#include "../Crypto/Serpent.h"
#include "../Crypto/Twofish.h"
#include "../Crypto/Camellia.h"
#include "../Crypto/kuznyechik.h"

/* Always use the non-parallel code path — simpler and correct on ARM. */
static inline int CipherSupportsIntraDataUnitParallelization(int cipher)
{
    (void)cipher;
    return 0;
}

/* Encipher one 16-byte block in-place.
   Uses a separate output buffer for all ciphers so in == out is always safe. */
static inline void EncipherBlock(int cipher, void *buf, void *ks)
{
    unsigned char tmp[16];
    switch (cipher) {
    case CIPHER_AES:
        aes_encrypt((const unsigned char *)buf, tmp,
                    (const aes_encrypt_ctx *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_SERPENT:
        serpent_encrypt((const unsigned __int8 *)buf, tmp,
                        (unsigned __int8 *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_TWOFISH:
        twofish_encrypt((TwofishInstance *)ks,
                        (const u4byte *)buf, (u4byte *)tmp);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_CAMELLIA:
        camellia_encrypt((const unsigned __int8 *)buf, tmp,
                         (unsigned __int8 *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_KUZNYECHIK:
        kuznyechik_encrypt_block(tmp, (const unsigned char *)buf,
                                 (kuznyechik_kds *)ks);
        memcpy(buf, tmp, 16);
        break;
    default:
        break;
    }
    memset(tmp, 0, 16);
}

/* Decipher one 16-byte block in-place. */
static inline void DecipherBlock(int cipher, void *buf, void *ks)
{
    unsigned char tmp[16];
    switch (cipher) {
    case CIPHER_AES:
        aes_decrypt((const unsigned char *)buf, tmp,
                    (const aes_decrypt_ctx *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_SERPENT:
        serpent_decrypt((const unsigned __int8 *)buf, tmp,
                        (unsigned __int8 *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_TWOFISH:
        twofish_decrypt((TwofishInstance *)ks,
                        (const u4byte *)buf, (u4byte *)tmp);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_CAMELLIA:
        camellia_decrypt((const unsigned __int8 *)buf, tmp,
                         (unsigned __int8 *)ks);
        memcpy(buf, tmp, 16);
        break;
    case CIPHER_KUZNYECHIK:
        kuznyechik_decrypt_block(tmp, (const unsigned char *)buf,
                                 (kuznyechik_kds *)ks);
        memcpy(buf, tmp, 16);
        break;
    default:
        break;
    }
    memset(tmp, 0, 16);
}

/* Encipher count consecutive 16-byte blocks in-place. */
static inline void EncipherBlocks(int cipher, void *buf, void *ks, int count)
{
    unsigned char *p = (unsigned char *)buf;
    int i;
    for (i = 0; i < count; i++, p += BYTES_PER_XTS_BLOCK)
        EncipherBlock(cipher, p, ks);
}

/* Decipher count consecutive 16-byte blocks in-place. */
static inline void DecipherBlocks(int cipher, void *buf, void *ks, int count)
{
    unsigned char *p = (unsigned char *)buf;
    int i;
    for (i = 0; i < count; i++, p += BYTES_PER_XTS_BLOCK)
        DecipherBlock(cipher, p, ks);
}

#endif /* ARCANUM_CRYPTO_H */
