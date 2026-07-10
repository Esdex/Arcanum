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
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

extern "C" {
#include "Crypto/Aes.h"
#include "Crypto/Serpent.h"
#include "Crypto/Twofish.h"
#include "Crypto/Camellia.h"
#include "Crypto/kuznyechik.h"
}
#include "Common/Xts.h"    /* EncryptBufferXTS, DecryptBufferXTS, UINT64_STRUCT */

/* Key schedule sizes for Serpent and Camellia (others use their structs) */
#define SERPENT_KS_SIZE    (140 * 4)   /* 560 bytes */
#define CAMELLIA_KS_SIZE   (34 * 8)    /* 272 bytes */

/* ─── Algorithm table ───────────────────────────────────────────────── */
/* See arcanum_internal.h for the field-ordering rationale (application
 * order vs. VeraCrypt cascade-key layout). */

const AlgDef ALGORITHMS[NUM_ALGORITHMS] = {
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

/* ─── GenCipherCtx: persistent per-drive key context ────────────────── */
/* Heap-allocated in alloc_drive, freed in free_drive. Kept local to this
 * translation unit — see the "Deviation" note on GenCipherCtx in
 * arcanum_internal.h. layer_xts_ks() does XTS for one cipher layer using
 * pre-built KSes. */

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

// FatFs is not thread-safe (FF_FS_REENTRANT 0); all f_* calls must hold this lock.
std::mutex g_fatfs_mutex;

/* ─── One-shot XTS (stack-local KSes) — used for header attempt loop ── */
/* key64[64] = K1(32) || K2(32).
   AES: encrypt uses separate enc/dec KSes; tweak always uses enc KS.   */
void xts_crypt_temp(int type, const uint8_t key64[64],
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

/* CALLER MUST HOLD g_fatfs_mutex — see the comment on g_fatfs_mutex in
 * arcanum_internal.h. */
int alloc_drive(int fd, uint64_t dataOff, uint64_t sectors,
                const uint8_t *masterKey, int algId, int hashId,
                bool isHidden, uint64_t hiddenBoundary,
                uint32_t iterCount, bool readOnly) {
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
void free_drive(int pdrv) {
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
int decode_handle(jlong handle) {
    if (handle < 0) return -1;
    int      pdrv = (int)(handle & 0xFF);
    uint32_t gen  = (uint32_t)((uint64_t)handle >> 8);
    if (pdrv < 0 || pdrv >= MAX_DRIVES) return -1;
    if (!g_drives[pdrv].active) return -1;
    if (g_drives[pdrv].generation != gen) return -1;
    return pdrv;
}

/* Volatile pointer prevents the compiler from eliding security-critical zeroing. */
void secure_memset(volatile uint8_t *p, uint8_t c, size_t n) {
    while (n--) *p++ = c;
}

/* VeraCrypt cascade key layout: primary keys first, then tweak keys (32 bytes each).
 * For n=1 the result is identical to a flat 64-byte block — single-cipher unaffected. */
void build_cascade_key64(const uint8_t *dk, int n, int i, uint8_t out[64]) {
    memcpy(out,    dk + i * 32,           32);
    memcpy(out+32, dk + n * 32 + i * 32, 32);
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

uint32_t crc32_step(uint32_t crc, uint8_t b) {
    return s_crc32_tab[(crc ^ b) & 0xFF] ^ (crc >> 8);
}

/* Finalised CRC-32 of a buffer — used for VeraCrypt header integrity. */
uint32_t crc32_buf(const uint8_t *data, size_t len) {
    uint32_t crc = 0xFFFFFFFFUL;
    for (size_t i = 0; i < len; i++) crc = crc32_step(crc, data[i]);
    return ~crc;
}

/* ─── Big-endian helpers ─────────────────────────────────────────────── */

void put_be32(uint8_t *b, uint32_t v) {
    b[0]=(v>>24)&0xFF; b[1]=(v>>16)&0xFF; b[2]=(v>>8)&0xFF; b[3]=v&0xFF;
}
void put_be64(uint8_t *b, uint64_t v) {
    put_be32(b,   (uint32_t)(v >> 32));
    put_be32(b+4, (uint32_t)(v & 0xFFFFFFFF));
}
uint32_t get_be32(const uint8_t *b) {
    return ((uint32_t)b[0]<<24)|((uint32_t)b[1]<<16)|((uint32_t)b[2]<<8)|b[3];
}
uint64_t get_be64(const uint8_t *b) {
    return ((uint64_t)get_be32(b)<<32)|get_be32(b+4);
}

/* ─── /dev/urandom helper ────────────────────────────────────────────── */

bool read_urandom(uint8_t *buf, size_t len) {
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
void xor_fold_entropy(uint8_t *buf, size_t bufLen,
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

/* ─── Progress throttling (monotonic clock) ─────────────────────────── */
/*
 * The create fill loops, preallocate_fd's zero-fill fallback, and
 * fill_range_xts (all in jni_volume.cpp) previously called report_progress()
 * every 64/128 KB chunk — hundreds of thousands of JNI CallVoidMethod
 * round-trips for a multi-GB container. monotonic_ms() (CLOCK_MONOTONIC,
 * immune to wall-clock jumps) backs jni_volume.cpp's ProgressThrottle, which
 * caps that to roughly 10 reports/sec.
 */
uint64_t monotonic_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000L);
}

/* ─── Mount-progress name lookups ────────────────────────────────────── */

const char* algo_name(int algId) {
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

const char* hash_name(int hashId) {
    static const char* const N[] = { "SHA-512", "SHA-256", "Whirlpool", "Streebog", "BLAKE2s-256" };
    return (hashId >= 0 && hashId <= 4) ? N[hashId] : "?";
}
