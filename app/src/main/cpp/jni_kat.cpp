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

/*
 * Known-answer test hooks for NativeKatTest (app/src/androidTest).
 *
 * DEBUG BUILDS ONLY. CMakeLists.txt adds this file to the library only when
 * CMAKE_BUILD_TYPE is Debug, so none of these symbols exist in a release .so.
 * Instrumented tests run against the debug build, so nothing is lost.
 *
 * WHY THIS EXISTS AT ALL, rather than a host-side harness compiled with the
 * system compiler: the one real cryptographic bug this project has had
 * (issue #62) was ABI-specific. VeraCrypt's 64-bit XTS path miscompiled on
 * armeabi-v7a under -O2 through strict-aliasing UB, and produced correct
 * output everywhere else. A host x86-64 test would have stayed green through
 * the entire bug. Running the vectors against the real .so, built with the
 * real per-ABI flags (including the TC_NO_COMPILER_INT64 workaround CMake
 * applies to Xts.c on armeabi-v7a), is the only way that class of failure
 * shows up. Note that the arm64 emulator cannot exercise the arm32 path -
 * that needs a physical 32-bit device.
 *
 * These are thin wrappers over the production functions wherever one exists:
 * xts_crypt_temp(), pbkdf2_generic() and hash_traits_for() are what the mount
 * and creation paths actually call. The raw block ciphers and the bare hashes
 * have no one-shot entry point in production, so those call the VeraCrypt
 * primitives the same way crypto_core.cpp and kdf.cpp do.
 *
 * Nothing here touches app state, and every buffer is caller-supplied, so
 * these hooks expose no secrets - the reason they are still debug-only is to
 * keep the release library's symbol surface to exactly what the app uses.
 */

#include "arcanum_internal.h"

#include <cstring>

extern "C" {
#include "Crypto/Aes.h"
#include "Crypto/Twofish.h"
#include "Crypto/Serpent.h"
#include "Crypto/Sha2.h"
#include "Crypto/Whirlpool.h"
#include "Crypto/Streebog.h"
#include "Crypto/blake2.h"
}

/* Debug-only HMAC bridge, defined in kdf.cpp under the same guard - the real
 * hmac_generic() is static there, and re-implementing it here would test a
 * copy instead of the function PBKDF2 runs. */
void kat_hmac(int hashId, const uint8_t *key, int klen,
              const uint8_t *msg, size_t mlen, uint8_t *out);

namespace {

/* Copies a jbyteArray into a vector. Test-only, so a failed allocation just
   yields an empty vector and the caller returns null - the test then fails,
   which is the correct outcome. */
std::vector<uint8_t> to_vec(JNIEnv *env, jbyteArray arr) {
    std::vector<uint8_t> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    if (n <= 0) return out;
    out.resize((size_t)n);
    env->GetByteArrayRegion(arr, 0, n, (jbyte*)out.data());
    return out;
}

jbyteArray to_jarray(JNIEnv *env, const uint8_t *data, size_t len) {
    jbyteArray out = env->NewByteArray((jsize)len);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, (jsize)len, (const jbyte*)data);
    return out;
}

/* One-shot hash over the same primitives kdf.cpp wires into HASH_TRAITS.
   IDs match: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256. */
int hash_oneshot(int hashId, const uint8_t *data, size_t len, uint8_t *out) {
    switch (hashId) {
        case 0: { sha512_ctx c; sha512_begin(&c); sha512_hash(data, (uint_64t)len, &c); sha512_end(out, &c); return 64; }
        case 1: { sha256_ctx c; sha256_begin(&c); sha256_hash(data, (uint_32t)len, &c); sha256_end(out, &c); return 32; }
        case 2: { WHIRLPOOL_CTX c; WHIRLPOOL_init(&c); WHIRLPOOL_add(data, (unsigned)len, &c); WHIRLPOOL_finalize(&c, out); return 64; }
        case 3: { STREEBOG_CTX c; STREEBOG_init(&c); STREEBOG_add(&c, data, len); STREEBOG_finalize(&c, out); return 64; }
        case 4: { blake2s_state c; blake2s_init(&c, BLAKE2S_OUTBYTES); blake2s_update(&c, data, len); blake2s_final(&c, out, BLAKE2S_OUTBYTES); return 32; }
        default: return 0;
    }
}

} // namespace

/* ─── Block ciphers: single-block ECB encrypt ────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatAesEncryptBlock(
        JNIEnv *env, jobject /*thiz*/, jbyteArray jKey, jbyteArray jBlock)
{
    auto key = to_vec(env, jKey), in = to_vec(env, jBlock);
    if (key.size() != 32 || in.size() != 16) return nullptr;

    aes_encrypt_ctx ks;
    if (aes_encrypt_key256(key.data(), &ks) != EXIT_SUCCESS) return nullptr;
    uint8_t out[16];
    aes_encrypt(in.data(), out, &ks);
    return to_jarray(env, out, sizeof(out));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatTwofishEncryptBlock(
        JNIEnv *env, jobject /*thiz*/, jbyteArray jKey, jbyteArray jBlock)
{
    auto key = to_vec(env, jKey), in = to_vec(env, jBlock);
    if (key.size() != 32 || in.size() != 16) return nullptr;

    /* twofish_set_key and twofish_encrypt take u4byte*, not uint8* - copy
       through aligned u4byte buffers exactly as crypto_core.cpp does rather
       than casting the vector's storage. */
    TwofishInstance ks;
    u4byte keyWords[8], inWords[4], outWords[4];
    memcpy(keyWords, key.data(), 32);
    memcpy(inWords,  in.data(),  16);
    twofish_set_key(&ks, keyWords);
    twofish_encrypt(&ks, inWords, outWords);

    uint8_t out[16];
    memcpy(out, outWords, sizeof(out));
    return to_jarray(env, out, sizeof(out));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatSerpentEncryptBlock(
        JNIEnv *env, jobject /*thiz*/, jbyteArray jKey, jbyteArray jBlock)
{
    auto key = to_vec(env, jKey), in = to_vec(env, jBlock);
    if (key.size() != 32 || in.size() != 16) return nullptr;

    uint8_t ks[140 * 4];   /* SERPENT_KS_SIZE, as in crypto_core.cpp */
    serpent_set_key(key.data(), ks);
    uint8_t out[16];
    serpent_encrypt(in.data(), out, ks);
    return to_jarray(env, out, sizeof(out));
}

/* ─── Hashes ─────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatHash(
        JNIEnv *env, jobject /*thiz*/, jint hashId, jbyteArray jData)
{
    auto data = to_vec(env, jData);
    uint8_t out[64] = {};
    int n = hash_oneshot((int)hashId, data.empty() ? (const uint8_t*)"" : data.data(),
                         data.size(), out);
    if (n == 0) return nullptr;
    return to_jarray(env, out, (size_t)n);
}

/* ─── HMAC (the one PBKDF2 uses) ─────────────────────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatHmac(
        JNIEnv *env, jobject /*thiz*/, jint hashId, jbyteArray jKey, jbyteArray jData)
{
    const HashTraits *t = hash_traits_for((int)hashId);
    if (!t) return nullptr;

    auto key = to_vec(env, jKey), data = to_vec(env, jData);
    uint8_t out[64] = {};
    kat_hmac((int)hashId, key.data(), (int)key.size(),
             data.empty() ? (const uint8_t*)"" : data.data(), data.size(), out);

    /* Output length is the hash's digest size: 64 for SHA-512/Whirlpool/
       Streebog, 32 for SHA-256/BLAKE2s. */
    int n = ((int)hashId == 1 || (int)hashId == 4) ? 32 : 64;
    return to_jarray(env, out, (size_t)n);
}

/* ─── PBKDF2 (production function, unmodified) ───────────────────────── */

extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatPbkdf2(
        JNIEnv *env, jobject /*thiz*/, jint hashId, jbyteArray jPwd, jbyteArray jSalt,
        jint iterations, jint dkLen)
{
    const HashTraits *t = hash_traits_for((int)hashId);
    if (!t || dkLen <= 0 || dkLen > 256 || iterations <= 0) return nullptr;

    auto pwd = to_vec(env, jPwd), salt = to_vec(env, jSalt);
    std::vector<uint8_t> dk((size_t)dkLen);
    pbkdf2_generic(t, pwd.data(), (int)pwd.size(), salt.data(), (int)salt.size(),
                   (uint32_t)iterations, dk.data(), (int)dkLen);
    return to_jarray(env, dk.data(), dk.size());
}

/* ─── XTS (production function - the #62 regression surface) ─────────── */

/*
 * type is the cipher id used by xts_crypt_temp (ALGORITHMS[] index in
 * crypto_core.cpp), key64 is K1(32) || K2(32), and buf is encrypted in place
 * as data unit sn. This is the exact call the header and sector paths make.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_crypto_NativeKatBridge_nativeKatXtsCrypt(
        JNIEnv *env, jobject /*thiz*/, jint type, jbyteArray jKey64, jbyteArray jBuf,
        jlong sectorNumber, jboolean encrypt)
{
    auto key = to_vec(env, jKey64), buf = to_vec(env, jBuf);
    if (key.size() != 64 || buf.empty() || buf.size() % 16 != 0) return nullptr;

    xts_crypt_temp((int)type, key.data(), buf.data(), buf.size(),
                   (uint64_t)sectorNumber, (bool)encrypt);
    return to_jarray(env, buf.data(), buf.size());
}
