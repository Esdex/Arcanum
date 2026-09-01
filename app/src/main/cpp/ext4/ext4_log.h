/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room ext4: no GPL ext4 source (lwext4's src/ext4_extent.c or
 * src/ext4_xattr.c) was opened or consulted. The on-disk format is implemented
 * from its published description - a data structure, not anyone's expression of
 * it - which is what keeps this code free of lwext4's copyleft. See issue #7.
 */

/*
 * Logging for the ext4 layer.
 *
 * In a DEBUG build on Android every call goes to logcat under one tag, so a
 * container operation can be followed end to end from `adb logcat -s
 * Arcanum-ext4`. That is the only window onto this code running on a real device
 * - it is never executed on the host except by the test harness.
 *
 * A RELEASE build says nothing at all, and that is the point (#174). These
 * messages name what they are working on: `resolve '/photos/holiday.jpg' ->
 * inode 15`, `unlink 'tax-2025.pdf' from dir inode 21`. Logcat is not world
 * readable on a current Android, but `adb logcat` reads it and a bug report
 * carries it - and a bug report is exactly what someone is asked to send when
 * something has gone wrong with their files. An app built so that nobody can
 * tell what is in a vault must not narrate it into the system log.
 *
 * Nothing was gained by keeping them either. To read a native log from a release
 * build you need adb, and anyone with adb can install the debug build, which has
 * all of this. If diagnostics from the field are ever wanted, the way is a code
 * returned to Kotlin and logged there - never a name out of C.
 *
 * The silent form is `if (0) fprintf(...)`: it emits nothing and costs nothing,
 * but the compiler still checks every format string against its arguments. A
 * wrong %-specifier is then a build error on a machine that builds, in every
 * configuration, rather than a surprise in the field. It is what the host
 * harness has always used, and now what a release build uses too.
 *
 * NDEBUG is what tells them apart: CMake defines it in the release
 * configurations and not in the debug one, which is the same switch the JNI
 * layer's own LOGI/LOGE hang on.
 */
#ifndef ARCANUM_EXT4_LOG_H
#define ARCANUM_EXT4_LOG_H

#if defined(__ANDROID__) && !defined(NDEBUG)

#include <android/log.h>

#define EXT4_LOG_TAG "Arcanum-ext4"
#define EXT4_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  EXT4_LOG_TAG, __VA_ARGS__))
#define EXT4_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, EXT4_LOG_TAG, __VA_ARGS__))
#define EXT4_LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, EXT4_LOG_TAG, __VA_ARGS__))

#else

#include <stdio.h>

/* The host harness, and any release build. No output, but the format string is
 * still type-checked at compile time. */
#define EXT4_LOGI(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define EXT4_LOGE(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define EXT4_LOGD(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)

#endif

#endif
