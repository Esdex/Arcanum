/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 */

/*
 * Equivalence check for the VeraCrypt keyfile pool (issue #112).
 *
 * Host-only, deliberately NOT part of the CMake build: app/src/main/cpp/
 * CMakeLists.txt lists its sources explicitly, so nothing here is compiled
 * into libarcanum-native.so.
 *
 *   Build and run:
 *     g++ -O2 -std=c++17 -o /tmp/kf_pool app/src/test/cpp/keyfile_pool_equivalence.cpp
 *     /tmp/kf_pool
 *
 *   Exit code 0 means every case matched.
 *
 * WHAT IT GUARDS
 *
 * The keyfile pool is 64 bytes for passwords up to 64 bytes and 128 bytes
 * beyond that (VeraCrypt Common/Keyfiles.c:239). Arcanum hardcoded 64 until
 * issue #112, which made any volume created here with a keyfile AND a password
 * over 64 bytes unreadable by desktop VeraCrypt, and vice versa. Note "bytes",
 * not characters: passwords are UTF-8, so a Cyrillic password crosses 64 bytes
 * at 33 characters.
 *
 * Getting this wrong is invisible - no crash, no error, just a volume that
 * silently will not open in the other implementation - so the two algorithms
 * are compared directly here rather than trusted to review.
 *
 * The check covers three claims:
 *   1. Arcanum's pool matches VeraCrypt's for every password length 0..128.
 *   2. The legacy 64-byte fallback genuinely differs above 64 bytes - i.e. the
 *      retry in auth_with_legacy_pool_retry() can actually rescue a volume
 *      written by the pre-fix version.
 *   3. It is identical at or below 64 bytes - i.e. skipping the retry there
 *      costs nothing, which is why the retry is gated on password length.
 *
 * MAINTENANCE
 *
 * Both implementations below are transcriptions, kept deliberately verbatim:
 *   - veracrypt_apply()  from VeraCrypt Common/Keyfiles.c (KeyFilesApply)
 *   - arcanum_apply()    from app/src/main/cpp/vc_header.cpp
 * If vc_header.cpp's pool code changes, update arcanum_apply() to match and
 * re-run. A divergence here is a cross-compatibility break, not a style issue.
 *
 * The CRC-32 step is shared rather than transcribed twice: VeraCrypt's CRCFUNC
 * macro and Arcanum's crc32_step() are already known-identical (both are the
 * reflected 0xEDB88320 table), and this file is about the pool, not the CRC.
 */

#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>
#include <random>

static uint32_t tab[256];
static void init_tab() {
    for (int i = 0; i < 256; i++) {
        uint32_t c = (uint32_t)i;
        for (int k = 0; k < 8; k++) c = (c >> 1) ^ ((c & 1u) ? 0xEDB88320UL : 0u);
        tab[i] = c;
    }
}
static inline uint32_t crc_step(uint32_t crc, uint8_t b) {
    return tab[(crc ^ b) & 0xFF] ^ (crc >> 8);
}

#define MAX_LEGACY_PASSWORD      64
#define KEYFILE_POOL_LEGACY_SIZE 64
#define KEYFILE_POOL_SIZE       128
#define KEYFILE_MAX_READ_LEN    (1024 * 1024)

/* ── VeraCrypt reference, from Common/Keyfiles.c ─────────────────────── */

static void veracrypt_apply(const std::vector<std::vector<uint8_t>> &keyfiles,
                            uint8_t *text, int *length) {
    uint8_t keyPool[KEYFILE_POOL_SIZE];
    uint32_t keyPoolSize = (*length <= MAX_LEGACY_PASSWORD)
                           ? KEYFILE_POOL_LEGACY_SIZE : KEYFILE_POOL_SIZE;
    memset(keyPool, 0, sizeof(keyPool));

    for (const auto &kf : keyfiles) {
        uint32_t crc = 0xffffffff;
        uint32_t writePos = 0;
        size_t totalRead = 0;
        for (size_t i = 0; i < kf.size(); i++) {
            crc = crc_step(crc, kf[i]);
            keyPool[writePos++] += (uint8_t)(crc >> 24);
            keyPool[writePos++] += (uint8_t)(crc >> 16);
            keyPool[writePos++] += (uint8_t)(crc >> 8);
            keyPool[writePos++] += (uint8_t)crc;
            if (writePos >= keyPoolSize) writePos = 0;
            if (++totalRead >= KEYFILE_MAX_READ_LEN) break;
        }
    }
    for (uint32_t i = 0; i < keyPoolSize; i++) {
        if ((int)i < *length) text[i] += keyPool[i];
        else                  text[i]  = keyPool[i];
    }
    if (*length < (int)keyPoolSize) *length = (int)keyPoolSize;
}

/* ── Arcanum, from app/src/main/cpp/vc_header.cpp ────────────────────── */

static int vc_keyfile_pool_size(int origPwdLen, bool forceLegacy) {
    if (forceLegacy) return KEYFILE_POOL_LEGACY_SIZE;
    return origPwdLen <= KEYFILE_POOL_LEGACY_SIZE
           ? KEYFILE_POOL_LEGACY_SIZE : KEYFILE_POOL_SIZE;
}

static void vc_process_keyfile_buf(const uint8_t *data, size_t size,
                                    uint8_t *pool, int poolSize) {
    uint32_t crc = 0xFFFFFFFFUL;
    size_t j = 0;
    size_t limit = (size > (size_t)KEYFILE_MAX_READ_LEN) ? (size_t)KEYFILE_MAX_READ_LEN : size;
    for (size_t i = 0; i < limit; i++) {
        crc          = crc_step(crc, data[i]);
        pool[j    ] += (uint8_t)(crc >> 24);
        pool[j + 1] += (uint8_t)(crc >> 16);
        pool[j + 2] += (uint8_t)(crc >>  8);
        pool[j + 3] += (uint8_t)(crc      );
        j += 4;
        if (j >= (size_t)poolSize) j = 0;
    }
}

static void vc_apply_pool_to_password(const uint8_t *pool, int poolSize,
                                       uint8_t *pwd_buf, int *pwd_len) {
    if (*pwd_len < poolSize) {
        memset(pwd_buf + *pwd_len, 0, (size_t)(poolSize - *pwd_len));
        *pwd_len = poolSize;
    }
    for (int i = 0; i < poolSize; i++)
        pwd_buf[i] = (uint8_t)((uint8_t)pwd_buf[i] + pool[i]);
}

static void arcanum_apply(const std::vector<std::vector<uint8_t>> &keyfiles,
                          uint8_t *pwd, int *len, bool forceLegacy) {
    if (keyfiles.empty()) return;
    const int poolSize = vc_keyfile_pool_size(*len, forceLegacy);
    uint8_t pool[KEYFILE_POOL_SIZE] = {};
    for (const auto &kf : keyfiles)
        vc_process_keyfile_buf(kf.data(), kf.size(), pool, poolSize);
    vc_apply_pool_to_password(pool, poolSize, pwd, len);
}

/* ── Comparison ──────────────────────────────────────────────────────── */

int main() {
    init_tab();
    /* Fixed seed: a failure must be reproducible from the exit code alone. */
    std::mt19937 rng(20260719);
    int checked = 0, failures = 0, legacyDiffers = 0;

    for (int trial = 0; trial < 300; trial++) {
        int nKeyfiles = 1 + (int)(rng() % 3);
        std::vector<std::vector<uint8_t>> keyfiles;
        for (int k = 0; k < nKeyfiles; k++) {
            /* Sizes are deliberately not multiples of the pool, so a wrong
               wraparound shows up rather than cancelling out. */
            size_t sz = 1 + rng() % 3000;
            std::vector<uint8_t> kf(sz);
            for (auto &b : kf) b = (uint8_t)(rng() & 0xFF);
            keyfiles.push_back(std::move(kf));
        }

        for (int pwdLen = 0; pwdLen <= 128; pwdLen++) {
            std::vector<uint8_t> base(128, 0);
            for (int i = 0; i < pwdLen; i++) base[i] = (uint8_t)(rng() & 0xFF);

            uint8_t a[128]; memcpy(a, base.data(), 128); int aLen = pwdLen;
            uint8_t v[128]; memcpy(v, base.data(), 128); int vLen = pwdLen;

            arcanum_apply(keyfiles, a, &aLen, /*forceLegacy=*/false);
            veracrypt_apply(keyfiles, v, &vLen);

            checked++;

            /* Claim 1: byte-for-byte agreement with VeraCrypt. */
            int cmpLen = aLen > vLen ? aLen : vLen;
            if (aLen != vLen || memcmp(a, v, (size_t)cmpLen) != 0) {
                failures++;
                if (failures <= 5)
                    printf("MISMATCH vs VeraCrypt at pwdLen=%d (arcanum len=%d, veracrypt len=%d)\n",
                           pwdLen, aLen, vLen);
                continue;
            }

            /* Claims 2 and 3: the legacy fallback differs exactly where the
               retry is worth running. */
            uint8_t l[128]; memcpy(l, base.data(), 128); int lLen = pwdLen;
            arcanum_apply(keyfiles, l, &lLen, /*forceLegacy=*/true);
            bool differs = (lLen != aLen) || memcmp(l, a, 128) != 0;

            if (pwdLen > MAX_LEGACY_PASSWORD) {
                if (differs) {
                    legacyDiffers++;
                } else {
                    printf("legacy pool identical at pwdLen=%d - the #112 retry "
                           "would be a no-op and pre-fix volumes stay unreadable\n", pwdLen);
                    failures++;
                }
            } else if (differs) {
                printf("legacy pool differs at pwdLen=%d - the #112 retry is gated on "
                       "length and would be skipped, so this case must be identical\n", pwdLen);
                failures++;
            }
        }
    }

    printf("\nchecked %d (password length, keyfile set) pairs\n", checked);
    printf("failures: %d\n", failures);
    printf("cases where the legacy pool genuinely differs: %d\n", legacyDiffers);
    return failures == 0 ? 0 : 1;
}
