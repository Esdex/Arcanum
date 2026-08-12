/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * BlockBackend over a USB mass-storage device (issue #95).
 *
 * The device is reached through UsbBlockDevice on the Kotlin side, because raw block
 * access on unrooted Android only exists through the USB host API. Measured before this
 * file was written: the transport tops out at the USB link speed, not at our overhead,
 * so there is nothing to gain from reimplementing bulk transfers natively over usbfs.
 *
 * Three rules govern everything here, and breaking any of them is a hang or a crash
 * rather than a wrong answer:
 *
 *   1. THE THREAD IS USUALLY ALREADY ATTACHED. These calls run underneath f_read/f_write,
 *      on the thread that entered JNI in the first place, so GetEnv succeeds and costs
 *      almost nothing. AttachCurrentThread is the fallback for a thread we did not come
 *      in on, and it detaches again so it cannot leak a JVM reference to that thread.
 *
 *   2. THE KOTLIN SIDE MUST BE A LEAF. The calling thread holds g_fatfs_mutex, which is
 *      a plain non-recursive std::mutex. If UsbBlockDevice ever called back into native
 *      code that takes that lock, the thread would deadlock against itself. It does
 *      nothing but bulkTransfer, and it must stay that way.
 *
 *   3. NO LOCAL REFERENCE MAY ESCAPE. These run in a loop for the life of a mount, not
 *      inside a JNI entry point that cleans up after itself, so a leaked local ref
 *      accumulates until the reference table overflows and aborts the process. Nothing
 *      below creates one: the scratch array is a global ref made once, and the calls
 *      return primitives.
 *
 * Readahead is NOT here yet. FatFs issues many small reads, and at 8 KB the transport
 * does 15 MB/s against 36 at 512 KB, so a caching layer belongs in this file - but it is
 * a separate change, deliberately, so this one can be proven correct on its own.
 */
#include "arcanum_internal.h"

#include <cstdlib>
#include <cstring>

#ifdef ARCANUM_KAT_HOOKS
#include <cstdio>
#include <ctime>
#include <unordered_map>
#endif

/* Mirrors UsbBlockDevice.MAX_TRANSFER_BYTES - keep the two equal, or a combined write is
 * assembled at one size and then split again at another, which wastes a JNI round trip
 * per command. Lowered from 512 KB with the transport: writes that large stopped
 * returning a status on real hardware. See the note on MAX_TRANSFER_BYTES. */
#define USB_SCRATCH_BYTES (128 * 1024)

namespace {

#ifdef ARCANUM_KAT_HOOKS
/*
 * Debug-only I/O census, to size a cache against what FatFs actually does rather than a
 * guess. A whole-device write measured 5.1 MB/s where the raw transport does 33, so
 * something is issuing far more commands than the data needs - this says what.
 *
 * `repeatReads` is the number that matters: a read of an offset already read is exactly
 * what a cache would have served, so it is the upper bound on what caching can win.
 * Safe without locking - every call arrives serialised behind g_fatfs_mutex.
 */
struct IoStats {
    uint64_t reads = 0, readBytes = 0, repeatReads = 0, repeatBytes = 0;
    uint64_t writes = 0, writeBytes = 0, rewrites = 0;
    /* Wall time spent inside the backend - the transport, the JNI hop and the array
     * copy. Subtracting it from the caller's own measurement leaves everything above:
     * XTS and FatFs. That split is the whole point of this counter. */
    uint64_t readNanos = 0, writeNanos = 0;
    /* How many writes began exactly where the previous one ended. This is the entire
     * case for coalescing: only a contiguous run can be merged into one command, and a
     * metadata update landing between two data chunks breaks the run. */
    uint64_t contigWrites = 0, longestRunBytes = 0, deviceWrites = 0;
    uint64_t runBytes = 0, nextOff = UINT64_MAX;
    uint64_t readBuckets[5] = {};    /* <=512, <=4K, <=32K, <=128K, bigger */
    uint64_t writeBuckets[5] = {};
    std::unordered_map<uint64_t, uint32_t> readSeen;
    std::unordered_map<uint64_t, uint32_t> writeSeen;
};
IoStats g_stats;

/*
 * The two maps above are the only unbounded thing here: one entry per distinct offset
 * ever touched, never released. That is affordable for a diagnostic run and not for
 * ordinary use, where a long session on a large volume would grow them without limit -
 * so detail is off unless something asks for it. The counters, buckets and timings cost
 * a few adds and stay on, which is what a report after an ordinary mount needs.
 */
bool g_statsDetail = false;

uint64_t now_nanos() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

int size_bucket(size_t len) {
    if (len <= 512)          return 0;
    if (len <= 4 * 1024)     return 1;
    if (len <= 32 * 1024)    return 2;
    if (len <= 128 * 1024)   return 3;
    return 4;
}

void stat_read(uint64_t off, size_t len) {
    g_stats.reads++;
    g_stats.readBytes += len;
    g_stats.readBuckets[size_bucket(len)]++;
    if (g_statsDetail && ++g_stats.readSeen[off] > 1) { g_stats.repeatReads++; g_stats.repeatBytes += len; }
}

void stat_write(uint64_t off, size_t len) {
    if (off == g_stats.nextOff) {
        g_stats.contigWrites++;
        g_stats.runBytes += len;
    } else {
        if (g_stats.runBytes > g_stats.longestRunBytes) g_stats.longestRunBytes = g_stats.runBytes;
        g_stats.runBytes = len;
    }
    g_stats.nextOff = off + len;
    if (g_stats.runBytes > g_stats.longestRunBytes) g_stats.longestRunBytes = g_stats.runBytes;

    g_stats.writes++;
    g_stats.writeBytes += len;
    g_stats.writeBuckets[size_bucket(len)]++;
    if (g_statsDetail && ++g_stats.writeSeen[off] > 1) g_stats.rewrites++;
}
#define STAT_READ(off, len)  stat_read((off), (len))
#define STAT_WRITE(off, len) stat_write((off), (len))
#define STAT_CLOCK_START()   uint64_t _t0 = now_nanos()
#define STAT_READ_DONE()     g_stats.readNanos  += now_nanos() - _t0
#define STAT_WRITE_DONE()    g_stats.writeNanos += now_nanos() - _t0
#define STAT_DEVICE_WRITE()  g_stats.deviceWrites++
#else
#define STAT_READ(off, len)  ((void)0)
#define STAT_WRITE(off, len) ((void)0)
#define STAT_CLOCK_START()   ((void)0)
#define STAT_READ_DONE()     ((void)0)
#define STAT_WRITE_DONE()    ((void)0)
#define STAT_DEVICE_WRITE()  ((void)0)
#endif

/*
 * Read cache, sized from the census rather than from taste.
 *
 * ext4 reads in 4 KB units and repeats itself constantly: measured over one mount that
 * imported a 50 MB file, 78078 reads of which 82.3% were offsets already read, 234 MB
 * that a cache would have served without touching the drive. At about a millisecond of
 * round trip per command that is where the minute went. FAT never showed this because
 * FatFs asks for long contiguous runs.
 *
 * A miss fetches a whole aligned chunk, so this is also the readahead the transport's
 * header says it wants: one 64 KB command in place of sixteen 4 KB ones.
 */
#define USB_CACHE_CHUNK  (64 * 1024)
#define USB_CACHE_CHUNKS 128              /* 8 MB, one mounted volume at a time */
#define CHUNK_EMPTY      UINT64_MAX
#define USB_DIRTY_MAX    (1024 * 1024)    /* ceiling on writes held back from the drive */

struct CacheChunk {
    uint64_t off = CHUNK_EMPTY;   /* chunk-aligned device offset */
    uint32_t len = 0;             /* valid bytes; short only at the end of the device */
    uint64_t stamp = 0;           /* last use, for eviction */
    uint8_t *data = nullptr;
    /* Bytes written but not yet on the device, as one span within the chunk. dhi == 0
     * means clean. A span rather than a bitmap because it flushes as ONE command, which
     * is the entire point: 96520 scattered 4 KB writes cost 93914 commands and 116
     * seconds, and 67.9% of them rewrote an offset already written. */
    uint32_t dlo = 0, dhi = 0;
};

struct UsbBackend {
    jobject    transport;   /* global ref to a Kotlin UsbBlockDevice */
    jbyteArray scratch;     /* global ref, USB_SCRATCH_BYTES, reused every call */
    jmethodID  readMid;
    jmethodID  writeMid;
    jmethodID  syncMid;
    bool       readOnly;

    /* There is deliberately no separate write buffer any more. There used to be one,
     * merging strictly contiguous runs, which suited FAT (87% of its writes continued
     * the previous one) and did nothing for ext4 (2.7%). The chunk cache below replaces
     * it and merges by locality instead of by adjacency. Two buffers holding the same
     * bytes is how data goes missing, so there is only this one. */
    bool       failed;      /* a deferred write failed; see the note in chunk_flush */

    CacheChunk *cache;      /* USB_CACHE_CHUNKS entries, or null if it could not be had */
    uint64_t    cacheClock;
    size_t      dirtyBytes; /* held back from the device; bounded by USB_DIRTY_MAX */
    /* A whole chunk read past the end of the volume is refused, which is correct and
     * would otherwise be attempted, and logged, on every read of the last chunk. The
     * first refusal records where that starts and the cache stays out of it. */
    uint64_t    noCacheFrom;
};

/* True when [off, off+len) overlaps whatever the write buffer is still holding. */

struct CacheChunk;
bool chunk_flush(UsbBackend *be, JNIEnv *env, CacheChunk *c);

CacheChunk *cache_find(UsbBackend *be, uint64_t aligned) {
    if (!be->cache) return nullptr;
    /* A linear scan of 128 entries is a few hundred nanoseconds against a millisecond
     * of USB round trip, so the simplest structure that cannot be got wrong wins. */
    for (int i = 0; i < USB_CACHE_CHUNKS; i++)
        if (be->cache[i].off == aligned) return &be->cache[i];
    return nullptr;
}

/* An empty slot if there is one, otherwise the least recently used. */
/* An empty slot if there is one, else the least recently used - written back first. */
CacheChunk *cache_victim(UsbBackend *be, JNIEnv *env) {
    CacheChunk *lru = &be->cache[0];
    for (int i = 0; i < USB_CACHE_CHUNKS; i++) {
        CacheChunk *c = &be->cache[i];
        if (c->off == CHUNK_EMPTY) return c;
        if (c->stamp < lru->stamp) lru = c;
    }
    if (lru->dhi != 0 && !chunk_flush(be, env, lru)) return nullptr;
    return lru;
}

/*
 * Keeps resident chunks current with a write that has been accepted but may still be
 * sitting in the write buffer.
 *
 * Without this a read could hit a chunk cached before the write and hand back bytes the
 * filesystem has already replaced. Chunks that are not resident need nothing: a later
 * read of that range misses, and the miss path flushes before going to the device.
 */
void cache_absorb_write(UsbBackend *be, uint64_t off, const uint8_t *src, size_t len) {
    if (!be->cache || len == 0) return;
    uint64_t end = off + len;
    for (int i = 0; i < USB_CACHE_CHUNKS; i++) {
        CacheChunk *c = &be->cache[i];
        if (c->off == CHUNK_EMPTY || c->len == 0) continue;
        uint64_t cend = c->off + c->len;
        if (end <= c->off || off >= cend) continue;
        uint64_t s = (off > c->off) ? off : c->off;
        uint64_t e = (end < cend) ? end : cend;
        memcpy(c->data + (s - c->off), src + (s - off), (size_t)(e - s));
    }
}

/* Returns an env for this thread, setting *attached when it had to attach one. */
JNIEnv *acquire_env(bool *attached) {
    *attached = false;
    if (!g_jniCache.vm) return nullptr;
    JNIEnv *env = nullptr;
    jint rc = g_jniCache.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc == JNI_EDETACHED &&
        g_jniCache.vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

void release_env(bool attached) {
    if (attached && g_jniCache.vm) g_jniCache.vm->DetachCurrentThread();
}

/* A pending exception left in flight would fire at the next JNI call, far from its
 * cause. Cleared here and turned into a plain false. */
bool check_exception(JNIEnv *env, const char *what) {
    if (!env->ExceptionCheck()) return false;
    LOGE("[usb] %s threw", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

/*
 * Sends whatever is buffered as a single command.
 *
 * On failure the backend is poisoned rather than just returning false. A combined write
 * is reported complete to the filesystem before it reaches the device, so the error
 * surfaces on some later call - and a filesystem that saw one write fail and the next
 * succeed would carry on with a hole in what it believes it wrote. Refusing everything
 * afterwards turns that into a mount that fails loudly instead.
 */
/*
 * Sends one chunk's dirty span as a single command.
 *
 * On failure the backend is poisoned rather than just returning false. A deferred write
 * was reported complete to the filesystem long before it reached the device, so the
 * error surfaces on some later call - and a filesystem that saw one write fail and the
 * next succeed would carry on with a hole in what it believes it wrote. Refusing
 * everything afterwards turns that into a mount that fails loudly instead.
 */
bool chunk_flush(UsbBackend *be, JNIEnv *env, CacheChunk *c) {
    if (c->dhi == 0) return !be->failed;
    if (be->failed) return false;

    uint32_t lo = c->dlo;
    size_t span = c->dhi - c->dlo;
    /* Cleared first: a failed flush must not be retried from stale bookkeeping. */
    c->dlo = c->dhi = 0;
    be->dirtyBytes -= span;

    size_t done = 0;
    while (done < span) {
        jint piece = (jint)((span - done > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (span - done));
        env->SetByteArrayRegion(be->scratch, 0, piece,
                                reinterpret_cast<const jbyte *>(c->data + lo + done));
        if (check_exception(env, "SetByteArrayRegion")) { be->failed = true; return false; }
        jboolean r = env->CallBooleanMethod(be->transport, be->writeMid,
                                            (jlong)(c->off + lo + done), piece, be->scratch, (jint)0);
        if (check_exception(env, "write") || r == JNI_FALSE) {
            LOGE("[usb] deferred write of %d bytes at %llu failed", (int)piece,
                 (unsigned long long)(c->off + lo + done));
            be->failed = true;
            return false;
        }
        STAT_DEVICE_WRITE();
        done += (size_t)piece;
    }
    return true;
}

bool flush_all_dirty(UsbBackend *be, JNIEnv *env) {
    if (!be->cache) return !be->failed;
    bool ok = true;
    for (int i = 0; i < USB_CACHE_CHUNKS; i++)
        if (be->cache[i].dhi != 0 && !chunk_flush(be, env, &be->cache[i])) ok = false;
    return ok;
}

/* Makes the device current across [off, off+len), for the paths that bypass the cache. */
bool flush_overlapping(UsbBackend *be, JNIEnv *env, uint64_t off, size_t len) {
    if (!be->cache) return !be->failed;
    uint64_t end = off + len;
    bool ok = true;
    for (int i = 0; i < USB_CACHE_CHUNKS; i++) {
        CacheChunk *c = &be->cache[i];
        if (c->off == CHUNK_EMPTY || c->dhi == 0) continue;
        if (end <= c->off || off >= c->off + c->len) continue;
        if (!chunk_flush(be, env, c)) ok = false;
    }
    return ok;
}

/*
 * Caps what is held back from the device, oldest first.
 *
 * This is the safety dial for a drive that can be pulled at any moment. The whole win
 * comes from repeated writes to the same metadata blocks collapsing into one command,
 * and those repeats happen within seconds of each other, so a small budget buys nearly
 * all of it. It used to be 128 KB when a single contiguous run was all that was held.
 */
bool enforce_dirty_bound(UsbBackend *be, JNIEnv *env) {
    while (be->dirtyBytes > USB_DIRTY_MAX) {
        CacheChunk *oldest = nullptr;
        for (int i = 0; i < USB_CACHE_CHUNKS; i++) {
            CacheChunk *c = &be->cache[i];
            if (c->dhi == 0) continue;
            if (!oldest || c->stamp < oldest->stamp) oldest = c;
        }
        if (!oldest) break;
        if (!chunk_flush(be, env, oldest)) return false;
    }
    return true;
}

/* Flushes with an env of its own. For the paths that have no env in hand. */
/* Flushes with an env of its own, for the paths that have none in hand. */
bool flush_owned_env(UsbBackend *be) {
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) { be->failed = true; return false; }
    bool ok = flush_all_dirty(be, env);
    release_env(attached);
    return ok;
}

/* Straight to the transport, in command-sized pieces. No cache, no flushing. */
bool device_read(UsbBackend *be, JNIEnv *env, uint64_t off, size_t len, uint8_t *dst) {
    size_t done = 0;
    while (done < len) {
        jint chunk = (jint)((len - done > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (len - done));
        jboolean r = env->CallBooleanMethod(be->transport, be->readMid,
                                            (jlong)(off + done), chunk, be->scratch, (jint)0);
        if (check_exception(env, "read") || r == JNI_FALSE) {
            LOGE("[usb] read failed at offset %llu (%d bytes)",
                 (unsigned long long)(off + done), (int)chunk);
            return false;
        }
        env->GetByteArrayRegion(be->scratch, 0, chunk, reinterpret_cast<jbyte *>(dst + done));
        if (check_exception(env, "GetByteArrayRegion")) return false;
        done += (size_t)chunk;
    }
    return true;
}

/* Brings one aligned chunk in. Null means the caller should read directly instead. */
CacheChunk *cache_fill(UsbBackend *be, JNIEnv *env, uint64_t aligned) {
    if (!be->cache) return nullptr;
    /* No test against pending writes is needed: dirty bytes exist only inside resident
     * chunks, so a chunk that is not resident is one the device is already current for. */
    CacheChunk *c = cache_victim(be, env);
    if (!c) return nullptr;
    if (!c->data) {
        c->data = static_cast<uint8_t *>(malloc(USB_CACHE_CHUNK));
        if (!c->data) return nullptr;
    }
    c->off = CHUNK_EMPTY;   /* a failed read must not leave this slot looking valid */
    c->len = 0;
    if (!device_read(be, env, aligned, USB_CACHE_CHUNK, c->data)) {
        be->noCacheFrom = aligned;
        return nullptr;
    }
    c->off = aligned;
    c->len = USB_CACHE_CHUNK;
    c->stamp = ++be->cacheClock;
    return c;
}

/* A slot for a write that covers the whole chunk, so there is nothing worth reading. */
CacheChunk *cache_claim(UsbBackend *be, JNIEnv *env, uint64_t aligned) {
    if (!be->cache) return nullptr;
    CacheChunk *c = cache_victim(be, env);
    if (!c) return nullptr;
    if (!c->data) {
        c->data = static_cast<uint8_t *>(malloc(USB_CACHE_CHUNK));
        if (!c->data) return nullptr;
    }
    c->off = aligned;
    c->len = USB_CACHE_CHUNK;
    c->dlo = c->dhi = 0;
    c->stamp = ++be->cacheClock;
    return c;
}

bool usb_read(void *self, void *buf, size_t len, uint64_t off) {
    auto *be = static_cast<UsbBackend *>(self);
    STAT_READ(off, len);
    STAT_CLOCK_START();
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) {
        LOGE("[usb] read: no JNIEnv");
        return false;
    }

    /*
     * Buffered writes have not reached the device, so anything read from it that
     * overlaps them would come back stale. This used to flush unconditionally, which was
     * right for FAT - 9 reads against 143 writes while writing a 2 MB file - and wrong
     * for ext4, whose reads are interleaved with writes constantly: every one of them
     * ended the run and left write combining with 4% of writes contiguous. The overlap
     * test costs two comparisons and flushes only when it must.
     */
    bool ok = true;
    uint8_t *out = static_cast<uint8_t *>(buf);

    if (len >= USB_CACHE_CHUNK || !be->cache) {
        /* Already at or above the size the cache would fetch, so going through it would
         * only split one command into several. */
        ok = flush_overlapping(be, env, off, len);
        if (ok) ok = device_read(be, env, off, len, out);
    } else {
        size_t done = 0;
        while (ok && done < len) {
            uint64_t cur = off + done;
            uint64_t aligned = cur - (cur % USB_CACHE_CHUNK);
            CacheChunk *c = nullptr;
            if (aligned < be->noCacheFrom) {
                c = cache_find(be, aligned);
                if (!c) c = cache_fill(be, env, aligned);
            }
            if (!c) {
                /* The tail of the volume, or no memory: ask for exactly what was wanted. */
                size_t rest = len - done;
                ok = flush_overlapping(be, env, cur, rest);
                if (ok) ok = device_read(be, env, cur, rest, out + done);
                break;
            }
            size_t inChunk = (size_t)(cur - aligned);
            size_t avail = (c->len > inChunk) ? (c->len - inChunk) : 0;
            if (avail == 0) { ok = false; break; }
            size_t n = (len - done < avail) ? (len - done) : avail;
            memcpy(out + done, c->data + inChunk, n);
            c->stamp = ++be->cacheClock;
            done += n;
        }
    }

    release_env(attached);
    STAT_READ_DONE();
    return ok;
}

bool usb_write(void *self, const void *buf, size_t len, uint64_t off) {
    auto *be = static_cast<UsbBackend *>(self);
    STAT_WRITE(off, len);
    STAT_CLOCK_START();
    /* The guard that stands in for O_RDONLY, which this backend has no equivalent of.
     * The transport refuses too; both are kept, because losing one silently is how a
     * read-only mount quietly stops being read-only. */
    if (be->readOnly) {
        LOGE("[usb] write refused: backend is read-only");
        return false;
    }
    if (be->failed) return false;

    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) {
        LOGE("[usb] write: no JNIEnv");
        return false;
    }

    bool ok = true;
    const uint8_t *src = static_cast<const uint8_t *>(buf);

    if (!be->cache || len >= USB_CACHE_CHUNK) {
        /* Already worth a command of its own, or nowhere to hold it. Older bytes for the
         * same span may still be sitting dirty in a chunk, and must reach the device
         * before this does - otherwise the flush that follows would undo this write. */
        ok = flush_overlapping(be, env, off, len);
        size_t done = 0;
        while (ok && done < len) {
            jint piece = (jint)((len - done > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (len - done));
            env->SetByteArrayRegion(be->scratch, 0, piece,
                                    reinterpret_cast<const jbyte *>(src + done));
            if (check_exception(env, "SetByteArrayRegion")) { ok = false; break; }
            jboolean r = env->CallBooleanMethod(be->transport, be->writeMid,
                                                (jlong)(off + done), piece, be->scratch, (jint)0);
            if (check_exception(env, "write") || r == JNI_FALSE) {
                LOGE("[usb] write failed at offset %llu (%d bytes)",
                     (unsigned long long)(off + done), (int)piece);
                ok = false;
                break;
            }
            STAT_DEVICE_WRITE();
            done += (size_t)piece;
        }
        if (!ok) be->failed = true;
        else cache_absorb_write(be, off, src, len);
    } else {
        size_t done = 0;
        while (ok && done < len) {
            uint64_t cur = off + done;
            uint64_t aligned = cur - (cur % USB_CACHE_CHUNK);
            size_t inChunk = (size_t)(cur - aligned);
            size_t room = USB_CACHE_CHUNK - inChunk;
            size_t n = (len - done < room) ? (len - done) : room;

            CacheChunk *c = (aligned < be->noCacheFrom) ? cache_find(be, aligned) : nullptr;
            if (!c && aligned < be->noCacheFrom) {
                /* Filling reads the chunk first so the untouched bytes around this write
                 * are the drive's own. When the write covers the chunk entirely there is
                 * nothing to preserve, so that read is skipped - which is what keeps a
                 * long sequential import from paying a read per chunk. */
                c = (inChunk == 0 && n == USB_CACHE_CHUNK)
                    ? cache_claim(be, env, aligned)
                    : cache_fill(be, env, aligned);
            }
            if (!c) {
                /* The tail of the volume, or no memory: write the rest straight out. */
                size_t rest = len - done;
                ok = flush_overlapping(be, env, cur, rest);
                size_t sent = 0;
                while (ok && sent < rest) {
                    jint piece = (jint)((rest - sent > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (rest - sent));
                    env->SetByteArrayRegion(be->scratch, 0, piece,
                                            reinterpret_cast<const jbyte *>(src + done + sent));
                    if (check_exception(env, "SetByteArrayRegion")) { ok = false; break; }
                    jboolean r = env->CallBooleanMethod(be->transport, be->writeMid,
                                                        (jlong)(cur + sent), piece, be->scratch, (jint)0);
                    if (check_exception(env, "write") || r == JNI_FALSE) { ok = false; break; }
                    STAT_DEVICE_WRITE();
                    sent += (size_t)piece;
                }
                if (!ok) be->failed = true;
                break;
            }

            memcpy(c->data + inChunk, src + done, n);
            uint32_t lo = (uint32_t)inChunk, hi = (uint32_t)(inChunk + n);
            if (c->dhi == 0) {
                c->dlo = lo; c->dhi = hi;
                be->dirtyBytes += hi - lo;
            } else {
                uint32_t nlo = (lo < c->dlo) ? lo : c->dlo;
                uint32_t nhi = (hi > c->dhi) ? hi : c->dhi;
                be->dirtyBytes += (size_t)(nhi - nlo) - (size_t)(c->dhi - c->dlo);
                c->dlo = nlo; c->dhi = nhi;
            }
            c->stamp = ++be->cacheClock;
            done += n;
            ok = enforce_dirty_bound(be, env);
        }
    }

    release_env(attached);
    STAT_WRITE_DONE();
    return ok;
}

void usb_sync(void *self) {
    auto *be = static_cast<UsbBackend *>(self);
    if (be->readOnly) return;
    /* A sync that left bytes sitting in our own cache would be a lie twice over. */
    flush_owned_env(be);
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) return;
    env->CallBooleanMethod(be->transport, be->syncMid);
    check_exception(env, "sync");
    release_env(attached);
}

/*
 * Called by free_drive before it wipes the DriveContext. Drops what this backend owns -
 * its two global references and its own allocation - and nothing else.
 *
 * It deliberately does NOT close the transport, mirroring the file backend: the backend
 * borrows the device, and whoever opened it closes it. Here that is the Kotlin code that
 * called UsbBlockDevice.open, which still holds the instance and can release the claimed
 * interface at unmount. Closing from both sides would either double-release or leave the
 * Kotlin owner holding something already dead.
 *
 * A read arriving after the owner closed the device is not a hazard: the transport
 * checks its own closed flag and returns false, which surfaces as an ordinary I/O error.
 */
void usb_close(void *self) {
    auto *be = static_cast<UsbBackend *>(self);
    if (!be) return;
    /* Last chance for anything still held. free_drive calls this on unmount, so a
     * buffered tail that never got a sync still reaches the device here. */
    if (!be->readOnly) flush_owned_env(be);
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (env) {
        env->DeleteGlobalRef(be->transport);
        env->DeleteGlobalRef(be->scratch);
        release_env(attached);
    } else {
        LOGE("[usb] close: no JNIEnv, two global refs leak for the life of the process");
    }
    if (be->cache) {
        for (int i = 0; i < USB_CACHE_CHUNKS; i++) free(be->cache[i].data);
        free(be->cache);
    }
    free(be);
}

} // namespace

bool usb_backend_init(BlockBackend *out, JNIEnv *env, jobject transport, bool readOnly) {
    jclass cls = env->GetObjectClass(transport);
    if (!cls) return false;

    auto *be = static_cast<UsbBackend *>(calloc(1, sizeof(UsbBackend)));
    if (!be) return false;
    be->readOnly = readOnly;
    /* calloc gave zeros, and zero is a valid chunk offset - so the empty marker and the
     * "cache is fine everywhere" marker both have to be written out explicitly. */
    be->noCacheFrom = CHUNK_EMPTY;
    be->cache = static_cast<CacheChunk *>(calloc(USB_CACHE_CHUNKS, sizeof(CacheChunk)));
    if (be->cache) {
        for (int i = 0; i < USB_CACHE_CHUNKS; i++) {
            be->cache[i].off = CHUNK_EMPTY;
            be->cache[i].data = nullptr;
        }
    } else {
        LOGE("[usb] no memory for the read cache - falling back to direct reads");
    }

    be->readMid  = env->GetMethodID(cls, "read",  "(JI[BI)Z");
    be->writeMid = env->GetMethodID(cls, "write", "(JI[BI)Z");
    be->syncMid  = env->GetMethodID(cls, "sync",  "()Z");
    env->DeleteLocalRef(cls);
    if (!be->readMid || !be->writeMid || !be->syncMid) {
        LOGE("[usb] a UsbBlockDevice method is missing - check the signatures");
        if (env->ExceptionCheck()) env->ExceptionClear();
        free(be);
        return false;
    }

    jbyteArray localScratch = env->NewByteArray(USB_SCRATCH_BYTES);
    if (!localScratch) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        free(be);
        return false;
    }
    be->scratch = (jbyteArray)env->NewGlobalRef(localScratch);
    env->DeleteLocalRef(localScratch);
    be->transport = env->NewGlobalRef(transport);
    if (!be->scratch || !be->transport) {
        if (be->scratch) env->DeleteGlobalRef(be->scratch);
        if (be->transport) env->DeleteGlobalRef(be->transport);
        free(be);
        return false;
    }

    /* Optional: without it every write goes straight through, which is correct, only
     * slower. Not a reason to fail a mount. */

    out->read  = usb_read;
    out->write = usb_write;
    out->sync  = usb_sync;
    out->close = usb_close;
    out->self  = be;
    return true;
}

#ifdef ARCANUM_KAT_HOOKS
/* Clears the census so one mount can be measured on its own. */
extern "C" JNIEXPORT void JNICALL
Java_zip_arcanum_usb_UsbBlockDevice_nativeResetIoStats(JNIEnv * /*env*/, jobject /*thiz*/) {
    g_stats = IoStats();
}

/* Turns per-offset tracking on for a diagnostic run. Off also frees what it collected. */
extern "C" JNIEXPORT void JNICALL
Java_zip_arcanum_usb_UsbBlockDevice_nativeSetIoStatsDetail(JNIEnv * /*env*/, jobject /*thiz*/,
                                                           jboolean on) {
    g_statsDetail = (on == JNI_TRUE);
    if (!g_statsDetail) {
        g_stats.readSeen.clear();  g_stats.readSeen.rehash(0);
        g_stats.writeSeen.clear(); g_stats.writeSeen.rehash(0);
    }
}

/* Returns the census as text. Read after unmounting, so it covers the whole session. */
extern "C" JNIEXPORT jstring JNICALL
Java_zip_arcanum_usb_UsbBlockDevice_nativeIoStats(JNIEnv *env, jobject /*thiz*/) {
    char buf[1024];
    const IoStats &s = g_stats;
    auto pct = [](uint64_t part, uint64_t whole) {
        return whole ? (double)part * 100.0 / (double)whole : 0.0;
    };
    snprintf(buf, sizeof(buf),
        "reads  %llu calls, %llu KB, sizes <=512B:%llu <=4K:%llu <=32K:%llu <=128K:%llu >128K:%llu\n"
        "  of those %llu (%.1f%%) were offsets already read - %llu KB a cache would have served%s\n"
        "writes %llu calls, %llu KB, sizes <=512B:%llu <=4K:%llu <=32K:%llu <=128K:%llu >128K:%llu\n"
        "  of those %llu (%.1f%%) rewrote an offset already written\n"
        "  %llu of them (%.1f%%) began exactly where the previous write ended;\n"
        "  longest contiguous run %llu KB; %llu commands actually reached the device\n"
        "time inside the backend (transport + JNI): reads %llu ms, writes %llu ms\n"
        "  anything the caller measured beyond that was spent above it, in XTS and FatFs",
        (unsigned long long)s.reads, (unsigned long long)(s.readBytes / 1024),
        (unsigned long long)s.readBuckets[0], (unsigned long long)s.readBuckets[1],
        (unsigned long long)s.readBuckets[2], (unsigned long long)s.readBuckets[3],
        (unsigned long long)s.readBuckets[4],
        (unsigned long long)s.repeatReads, pct(s.repeatReads, s.reads),
        (unsigned long long)(s.repeatBytes / 1024),
        g_statsDetail ? "" : "   [NOT COUNTED - per-offset detail is off]",
        (unsigned long long)s.writes, (unsigned long long)(s.writeBytes / 1024),
        (unsigned long long)s.writeBuckets[0], (unsigned long long)s.writeBuckets[1],
        (unsigned long long)s.writeBuckets[2], (unsigned long long)s.writeBuckets[3],
        (unsigned long long)s.writeBuckets[4],
        (unsigned long long)s.rewrites, pct(s.rewrites, s.writes),
        (unsigned long long)s.contigWrites, pct(s.contigWrites, s.writes),
        (unsigned long long)(s.longestRunBytes / 1024),
        (unsigned long long)s.deviceWrites,
        (unsigned long long)(s.readNanos / 1000000ull),
        (unsigned long long)(s.writeNanos / 1000000ull));
    return env->NewStringUTF(buf);
}

/*
 * Debug-only: read a span through a real BlockBackend built on `transport`, and hand the
 * bytes back to Kotlin.
 *
 * This exists so the upcall can be proven before anything is mounted on it. The caller
 * reads the same span directly through UsbBlockDevice and compares: identical bytes mean
 * the JNI path - method lookup, scratch array, chunking, region copy - moves data
 * faithfully. Reading through the backend and checking only that it "worked" would prove
 * nothing, since a wrong offset returns perfectly valid bytes from the wrong place.
 *
 * Read-only, and the transport stays open: this borrows it exactly as a mount would.
 * Gated with the KAT hooks so the release library exports nothing extra.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_zip_arcanum_usb_UsbBlockDevice_nativeReadThroughBackend(
        JNIEnv *env, jobject /*thiz*/, jobject transport, jlong offset, jint length) {
    if (length <= 0) return nullptr;

    BlockBackend be;
    memset(&be, 0, sizeof(be));
    if (!usb_backend_init(&be, env, transport, /*readOnly=*/true)) {
        LOGE("[usb] self-test: backend init failed");
        return nullptr;
    }

    auto *buf = static_cast<uint8_t *>(malloc((size_t)length));
    if (!buf) {
        be.close(be.self);
        return nullptr;
    }

    bool ok = be.read(be.self, buf, (size_t)length, (uint64_t)offset);
    be.close(be.self);   /* drops the backend's refs; the transport stays open */

    jbyteArray outArr = nullptr;
    if (ok) {
        outArr = env->NewByteArray(length);
        if (outArr) env->SetByteArrayRegion(outArr, 0, length, reinterpret_cast<jbyte *>(buf));
    }
    free(buf);
    return outArr;
}
#endif
