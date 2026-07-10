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

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <thread>
#include <unistd.h>

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
void apply_keyfiles_to_password(const std::vector<std::string>& paths,
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
void apply_keyfile_buffers(JNIEnv *env, jobjectArray jKeyfileData,
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

/* ─── VeraCrypt header write ─────────────────────────────────────────── */
/*
 * Writes a 512-byte VeraCrypt-compatible header.
 * masterKey : n*64 bytes (one 64-byte slot per cipher in the cascade)
 * algId     : algorithm index into ALGORITHMS[]
 * hashAlg   : 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog
 */
int write_vc_header(int fd, uint64_t fileOff,
                     uint64_t dataSz, uint64_t dataOff,
                     const uint8_t *masterKey,
                     int algId, int hashAlg,
                     const char *password, int pwd_len,
                     int pim,
                     uint64_t hiddenVolSize,
                     const uint8_t *existingSalt) {
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

/* ─── Mount-progress callback helpers ───────────────────────────────── */

jmethodID resolve_mount_mid(JNIEnv *env, jobject listener) {
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
int read_vc_header(int fd, uint64_t fileOff,
                    const char *password, int pwd_len,
                    uint8_t *masterKey, int *outMkLen,
                    uint64_t *dataSz, uint64_t *dataOff,
                    int *outAlgId, int *outHashId,
                    int pim,
                    uint64_t *outHiddenVolSize,
                    int hintAlgId, int hintHashId,
                    MountCb *mountCb) {
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

/* ─── Header wipe helper ─────────────────────────────────────────────── */
/* Overwrites the 512-byte header at fileOff with random data for wipeCount
   passes, then writes the new header encrypted with the new credentials.
   extraEntropy/extraEntropyLen: optional user-collected bytes XOR'd into the
   new salt before writing (Random Pool Enrichment). Pass nullptr/0 to skip. */
int wipe_and_rewrite_header(int fd, uint64_t fileOff,
                             uint64_t dataSz, uint64_t dataOff,
                             const uint8_t *masterKey, int algId, int newHashAlg,
                             const char *newPwd, int newPwdLen, int newPim,
                             uint64_t hiddenVolSize, int wipePassCount,
                             const uint8_t* extraEntropy,
                             size_t extraEntropyLen) {
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
