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

/* Mirrors UsbBlockDevice.MAX_TRANSFER_BYTES. The transport splits requests at that size
 * anyway; this is the scratch array a request is copied through, so the two matching
 * keeps one JNI round trip per SCSI command rather than several. */
#define USB_SCRATCH_BYTES (512 * 1024)

namespace {

struct UsbBackend {
    jobject    transport;   /* global ref to a Kotlin UsbBlockDevice */
    jbyteArray scratch;     /* global ref, USB_SCRATCH_BYTES, reused every call */
    jmethodID  readMid;
    jmethodID  writeMid;
    jmethodID  syncMid;
    bool       readOnly;
};

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

bool usb_read(void *self, void *buf, size_t len, uint64_t off) {
    auto *be = static_cast<UsbBackend *>(self);
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) {
        LOGE("[usb] read: no JNIEnv");
        return false;
    }

    bool ok = true;
    size_t done = 0;
    while (done < len) {
        jint chunk = (jint)((len - done > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (len - done));
        jboolean r = env->CallBooleanMethod(be->transport, be->readMid,
                                            (jlong)(off + done), chunk, be->scratch, (jint)0);
        if (check_exception(env, "read") || r == JNI_FALSE) {
            LOGE("[usb] read failed at offset %llu (%d bytes)",
                 (unsigned long long)(off + done), (int)chunk);
            ok = false;
            break;
        }
        env->GetByteArrayRegion(be->scratch, 0, chunk,
                                reinterpret_cast<jbyte *>(static_cast<uint8_t *>(buf) + done));
        if (check_exception(env, "GetByteArrayRegion")) { ok = false; break; }
        done += (size_t)chunk;
    }

    release_env(attached);
    return ok;
}

bool usb_write(void *self, const void *buf, size_t len, uint64_t off) {
    auto *be = static_cast<UsbBackend *>(self);
    /* The guard that stands in for O_RDONLY, which this backend has no equivalent of.
     * The transport refuses too; both are kept, because losing one silently is how a
     * read-only mount quietly stops being read-only. */
    if (be->readOnly) {
        LOGE("[usb] write refused: backend is read-only");
        return false;
    }

    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (!env) {
        LOGE("[usb] write: no JNIEnv");
        return false;
    }

    bool ok = true;
    size_t done = 0;
    while (done < len) {
        jint chunk = (jint)((len - done > USB_SCRATCH_BYTES) ? USB_SCRATCH_BYTES : (len - done));
        env->SetByteArrayRegion(be->scratch, 0, chunk,
                                reinterpret_cast<const jbyte *>(
                                    static_cast<const uint8_t *>(buf) + done));
        if (check_exception(env, "SetByteArrayRegion")) { ok = false; break; }
        jboolean r = env->CallBooleanMethod(be->transport, be->writeMid,
                                            (jlong)(off + done), chunk, be->scratch, (jint)0);
        if (check_exception(env, "write") || r == JNI_FALSE) {
            LOGE("[usb] write failed at offset %llu (%d bytes)",
                 (unsigned long long)(off + done), (int)chunk);
            ok = false;
            break;
        }
        done += (size_t)chunk;
    }

    release_env(attached);
    return ok;
}

void usb_sync(void *self) {
    auto *be = static_cast<UsbBackend *>(self);
    if (be->readOnly) return;
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
    bool attached = false;
    JNIEnv *env = acquire_env(&attached);
    if (env) {
        env->DeleteGlobalRef(be->transport);
        env->DeleteGlobalRef(be->scratch);
        release_env(attached);
    } else {
        LOGE("[usb] close: no JNIEnv, two global refs leak for the life of the process");
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

    out->read  = usb_read;
    out->write = usb_write;
    out->sync  = usb_sync;
    out->close = usb_close;
    out->self  = be;
    return true;
}

#ifdef ARCANUM_KAT_HOOKS
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
