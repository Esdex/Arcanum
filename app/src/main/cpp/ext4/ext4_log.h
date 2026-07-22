/*
 * Logging for the ext4 layer.
 *
 * On Android every call goes to logcat under one tag, so a container operation
 * can be followed end to end from `adb logcat -s Arcanum-ext4`. That is the only
 * window onto this code running on a real device - it is never executed on the
 * host except by the test harness, where these macros produce no output at all.
 *
 * The host form is `if (0) fprintf(...)`: it emits nothing, so the harness stays
 * quiet and behaviour is byte-for-byte unchanged, but the compiler still checks
 * every format string against its arguments. A wrong %-specifier is then a build
 * error here, on a machine that builds, rather than a surprise in the field.
 */
#ifndef ARCANUM_EXT4_LOG_H
#define ARCANUM_EXT4_LOG_H

#ifdef __ANDROID__

#include <android/log.h>

#define EXT4_LOG_TAG "Arcanum-ext4"
#define EXT4_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  EXT4_LOG_TAG, __VA_ARGS__))
#define EXT4_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, EXT4_LOG_TAG, __VA_ARGS__))
#define EXT4_LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, EXT4_LOG_TAG, __VA_ARGS__))

#else

#include <stdio.h>

/* No output, but the format string is still type-checked at compile time. */
#define EXT4_LOGI(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define EXT4_LOGE(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define EXT4_LOGD(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)

#endif

#endif
