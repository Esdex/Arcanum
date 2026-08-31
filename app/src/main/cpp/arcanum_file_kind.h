/*
 * Arcanum - VeraCrypt-compatible encrypted vault manager for Android
 *
 * Copyright (C) 2026 Esdex
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * What a listing entry is, as one number crossing into Java.
 *
 * These are the single source of truth for the values, the way arcanum_errors.h is
 * for the ERR_* codes, and for the same reason: they travel as a plain jint and
 * nothing at compile time makes the Kotlin side agree with this one. FileKindSyncTest
 * parses this file and compares every value against NativeFileInfo.Companion, so a
 * value changed on one side alone fails a test instead of quietly relabelling every
 * file in the vault.
 *
 * Kept apart from the booleans a caller might otherwise be tempted to use: four
 * adjacent jbooleans in one JNI call are two transpositions away from a listing that
 * calls folders links, and nothing would say so.
 */
#ifndef ARCANUM_FILE_KIND_H
#define ARCANUM_FILE_KIND_H

#define ARC_KIND_REGULAR   0
#define ARC_KIND_DIRECTORY 1
#define ARC_KIND_SYMLINK   2
/* A FIFO, socket or device node: a name with no data behind it. Worth telling
 * apart from a regular file only so nothing offers to open or export one. */
#define ARC_KIND_SPECIAL   3

#endif /* ARCANUM_FILE_KIND_H */
