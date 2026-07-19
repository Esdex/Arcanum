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

#pragma once

/* ─── Native error codes ─────────────────────────────────────────────── */
/*
 * MUST match VeraCryptEngine.Companion ERR_* constants
 * (app/src/main/java/zip/arcanum/crypto/VeraCryptEngine.kt) EXACTLY —
 * this is the single source of truth on the native side, and
 * ErrorCodeSyncTest (app/src/test/java/zip/arcanum/crypto/ErrorCodeSyncTest.kt)
 * parses this file and cross-checks every value against the Kotlin
 * companion object. If you add, remove, or renumber an ERR_* code here,
 * update VeraCryptEngine.kt to match or that test will fail the build.
 */
#define ERR_OK               0
#define ERR_FILE             -1
#define ERR_READ             -2
#define ERR_WRONG_PASSWORD   -3
#define ERR_UNSUPPORTED      -4
#define ERR_NO_SPACE         -5
#define ERR_NO_SLOT          -6
#define ERR_FS               -7
#define ERR_RAND             -8
#define ERR_HIDDEN_BOUNDARY  -9  /* write blocked by hidden-volume protection */
#define ERR_READ_ONLY       -10  /* write blocked: container mounted read-only */
#define ERR_DIR_FULL        -11  /* directory cannot hold another entry (FAT root limit) */
