// Native crash capture for Arcanum.
//
// Installs signal handlers for the fatal signals (SIGSEGV/SIGABRT/…) and, on a crash,
// writes a small text report (signal, fault address, and a backtrace) to a file the app
// can show and let the user share. The system's own crash path still runs afterwards:
// the handler restores the previous disposition and re-raises, so the normal tombstone is
// produced and the process dies exactly as it otherwise would.
//
// This exists because on Android 10 (no ApplicationExitInfo, which is API 30+) a user with
// no PC and no root has no other way to hand us a native backtrace - see issue #92.
//
// Signal-handler code must stay async-signal-safe: it uses only open/write/close and small
// stack-only integer formatting. dladdr and _Unwind_Backtrace are not strictly
// async-signal-safe, but they are the standard tools for this and the process is already
// dying, so a worst-case hang is no worse than losing the report.

#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdint.h>
#include <unwind.h>
#include <dlfcn.h>
#include <sys/syscall.h>

#define LOG_TAG "ArcanumCrash"

namespace {

// Full path of the crash report file and a metadata line (version/abi/device), both set once
// at install time so the signal handler needs no allocation.
char g_crash_path[1024] = {0};
char g_meta[512]        = {0};

constexpr int kMaxFrames = 64;

const int kSignals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP };
constexpr size_t kNumSignals = sizeof(kSignals) / sizeof(kSignals[0]);

bool g_installed = false;

// ── async-signal-safe writers ───────────────────────────────────────
void w_str(int fd, const char* s) {
    if (!s) s = "(null)";
    size_t len = 0;
    while (s[len]) len++;
    ssize_t r = write(fd, s, len);
    (void)r;
}

void w_hex(int fd, uintptr_t v) {
    static const char* kDigits = "0123456789abcdef";
    char tmp[2 * sizeof(uintptr_t)];
    int n = 0;
    if (v == 0) {
        tmp[n++] = '0';
    } else {
        while (v && n < (int)sizeof(tmp)) { tmp[n++] = kDigits[v & 0xf]; v >>= 4; }
    }
    char out[2 + sizeof(tmp)];
    int k = 0;
    out[k++] = '0';
    out[k++] = 'x';
    for (int j = n - 1; j >= 0; j--) out[k++] = tmp[j];
    ssize_t r = write(fd, out, k);
    (void)r;
}

void w_dec(int fd, long v) {
    char tmp[24];
    int n = 0;
    bool neg = v < 0;
    unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
    if (u == 0) tmp[n++] = '0';
    while (u) { tmp[n++] = char('0' + (u % 10)); u /= 10; }
    char out[26];
    int k = 0;
    if (neg) out[k++] = '-';
    for (int j = n - 1; j >= 0; j--) out[k++] = tmp[j];
    ssize_t r = write(fd, out, k);
    (void)r;
}

const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default:      return "SIGNAL";
    }
}

struct BacktraceState {
    uintptr_t frames[kMaxFrames];
    int count;
};

_Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (st->count >= kMaxFrames) return _URC_END_OF_STACK;
        st->frames[st->count++] = pc;
    }
    return _URC_NO_REASON;
}

void write_backtrace(int fd) {
    BacktraceState st;
    st.count = 0;
    _Unwind_Backtrace(unwind_cb, &st);
    if (st.count == 0) {
        w_str(fd, "  (no frames)\n");
        return;
    }
    for (int i = 0; i < st.count; i++) {
        uintptr_t pc = st.frames[i];
        w_str(fd, "  #");
        w_dec(fd, i);
        w_str(fd, " pc ");
        Dl_info info;
        if (dladdr((void*)pc, &info) && info.dli_fbase) {
            w_hex(fd, pc - (uintptr_t)info.dli_fbase);   // offset in module (ndk-stack / addr2line input)
            w_str(fd, "  ");
            w_str(fd, info.dli_fname ? info.dli_fname : "?");
            if (info.dli_sname) {
                w_str(fd, " (");
                w_str(fd, info.dli_sname);
                w_str(fd, "+");
                w_hex(fd, pc - (uintptr_t)info.dli_saddr);
                w_str(fd, ")");
            }
        } else {
            w_hex(fd, pc);
        }
        w_str(fd, "\n");
    }
}

void crash_handler(int sig, siginfo_t* info, void* /*uctx*/) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        w_str(fd, "=== Arcanum native crash ===\n");
        w_str(fd, g_meta);
        w_str(fd, "\nsignal: ");
        w_str(fd, signal_name(sig));
        w_str(fd, " (");
        w_dec(fd, sig);
        w_str(fd, ")\ncode: ");
        w_dec(fd, info ? info->si_code : 0);
        w_str(fd, "\nfault addr: ");
        w_hex(fd, info ? (uintptr_t)info->si_addr : 0);
        w_str(fd, "\ntid: ");
        w_dec(fd, (long)syscall(__NR_gettid));
        w_str(fd, "\nbacktrace:\n");
        write_backtrace(fd);
        fsync(fd);
        close(fd);
    }

    // Restore the default disposition and re-raise so the process terminates with its usual
    // crash (debuggerd tombstone). ART's own fatal-signal handling sits in front of ours via
    // libsigchain, so ours only runs for a genuine crash - benign runtime faults never reach here.
    signal(sig, SIG_DFL);
    raise(sig);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_zip_arcanum_crypto_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject /*thiz*/, jstring crashPath, jstring meta) {
    if (g_installed) return;

    if (crashPath) {
        const char* p = env->GetStringUTFChars(crashPath, nullptr);
        if (p) {
            strncpy(g_crash_path, p, sizeof(g_crash_path) - 1);
            env->ReleaseStringUTFChars(crashPath, p);
        }
    }
    if (meta) {
        const char* m = env->GetStringUTFChars(meta, nullptr);
        if (m) {
            strncpy(g_meta, m, sizeof(g_meta) - 1);
            env->ReleaseStringUTFChars(meta, m);
        }
    }
    if (g_crash_path[0] == '\0') return;

    // Alternate signal stack so a stack-overflow SIGSEGV can still be handled.
    static char alt_stack_mem[65536];
    stack_t alt;
    alt.ss_sp    = alt_stack_mem;
    alt.ss_size  = sizeof(alt_stack_mem);
    alt.ss_flags = 0;
    sigaltstack(&alt, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags     = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    for (size_t i = 0; i < kNumSignals; i++) {
        sigaction(kSignals[i], &sa, nullptr);
    }
    g_installed = true;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "native crash handler installed");
}
