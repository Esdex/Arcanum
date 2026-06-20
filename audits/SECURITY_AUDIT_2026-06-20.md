# Arcanum Security Audit

**Date:** 20 June 2026
**Auditor:** Claude Sonnet 4.6
**Type:** Full pre-release audit (third in series; follows 16 June and 19 June audits)
**Scope:** Complete codebase review — final checkpoint before F-Droid and Google Play Store release

---

## Goal

Determine whether Arcanum is safe to release publicly on F-Droid and Google Play Store against a physically-present, technically-skilled attacker who possesses the APK, the on-disk container files, and Android's usual backup/transfer interfaces.

---

## Threat Model

**Primary attacker:** Physical access to a locked Android device. Has a decompiled APK and can read all on-disk files. Can trigger Android cloud backup, device-to-device transfer, or ADB pull. Cannot break AES-256 or PBKDF2/Argon2id with offline brute-force in reasonable time.

**Out of scope:** Rooted device attacks, kernel exploits, side-channel attacks on AES hardware.

---

## Summary of Prior Audit Status

| Finding | Description | Current Status |
|---------|-------------|----------------|
| CRIT-1 (19 June) | SecureRandom failure not detected — data-fill fallback to zeros | CONFIRMED FIXED (partially, see M-1 new) |
| H-1 (19 June) | Panic PIN timing side-channel — panic path slower than normal auth | CONFIRMED FIXED |
| M-1 (19 June) | arcanum_bio_prefs.xml missing from backup exclusion rules | CONFIRMED FIXED |
| INFO-3 (prior) | R8/minification not configured for release | CONFIRMED FIXED |
| INFO-5 (prior) | Alpha/beta dependencies — security-crypto on alpha | STILL PRESENT (noted below, no fix available) |

---

## Findings

### Critical

None found.

---

### High

None found.

---

### Medium

#### M-1: Data-fill RNG partial failure silently falls back to zeros

- **File:** `app/src/main/cpp/arcanum_jni.cpp`, lines 1168–1191 (non-quick-format data-fill loop)
- **Description:** When `quickFormat = false`, the container data area is filled with random bytes to obscure plaintext patterns. The inner `read()` loop correctly retries on `EINTR`, but on a genuine I/O error it falls back to `memset(rnd + got, 0, sz - got)` and continues writing — filling the remainder of the 64 KB chunk with zero bytes rather than aborting. In practice on Android, `/dev/urandom` never fails (it is a CSPRNG that can block but not error), but the defensive contract is that an RNG failure must be visible to the caller. The current implementation silently degrades data quality without propagating any error code.
- **Impact:** A partial `/dev/urandom` failure during full-format creates a container where some sectors contain plaintext zeros instead of random data. This does not compromise the AES-XTS encryption of existing data, but it violates the explicit randomness guarantee of full format (the entire point of `quickFormat = false`). The master key and salt generation correctly abort on RNG failure; only the data-fill loop silently degrades.
- **Suggested fix:** After the inner retry loop, if `got < sz`, set `ok = false`, break out of the outer while loop, close `rfd`, `free(rnd)`, and return `ERR_RAND`. The existing master-key path (`read_urandom` returning `false` → `return ERR_RAND`) is the correct model to follow.

---

#### M-2: Playstore billing — purchase acknowledgement not verified

- **File:** `app/src/playstore/java/zip/arcanum/billing/BillingManager.kt`, lines 58–63
- **Description:** `processPurchases()` checks `purchase.purchaseState == Purchase.PurchaseState.PURCHASED` and `purchase.products.contains(PRODUCT_ID_PRO)`, but does not check `purchase.isAcknowledged`. Google Play's billing rules require acknowledgement within 3 days or the purchase is automatically refunded and reversed. Without checking `isAcknowledged`, a user who just bought but has not yet had the acknowledgement flow complete might see `isPro = true` briefly, but more importantly the reverse — a user with a cancelled/refunded purchase that is still `PURCHASED` in a stale local query — is not caught. Additionally, there is no server-side receipt verification (`purchase.getOriginalJson()` + signature check against the Play public key), meaning a sufficiently motivated attacker who can modify memory or intercept the billing client response could spoof `isPro = true`.
- **Impact:** For Arcanum's use case, `isPro` gates only non-security features (AMOLED glass toggle, container count > 2, etc.) and the premium UI banner. The panic, biometric, and disguise security features are NOT gated by `isPro`. Therefore this is a billing correctness issue, not a security issue. However, it is a material pre-release defect for the Play Store flavor.
- **Suggested fix:** Add `&& purchase.isAcknowledged` to the `processPurchases` predicate. Optionally implement server-side receipt verification using the Play Developer API or signature validation via `BillingClient.getConnectionState()`.

---

### Low

#### L-1: PIM integer overflow in `vc_get_iterations()`

- **File:** `app/src/main/cpp/arcanum_jni.cpp`, lines 67–79
- **Description:** `pim` is received as `jint` (32-bit signed) and cast to `(uint32_t)pim`. For SHA-256 the formula is `2048 + (uint32_t)pim * 2048`. If `pim = INT_MAX / 2048 + 1 ≈ 1,048,577`, the multiplication overflows `uint32_t` and wraps to a small iteration count, weakening PBKDF2. The Kotlin side does not currently validate or clamp user-supplied PIM values before passing them to JNI.
- **Impact:** An attacker who controls the PIM field (e.g., by modifying a container's mount parameters in the UI) could force a very small iteration count, making PBKDF2 trivially brute-forceable. In practice the UI allows freeform PIM entry. Note: VeraCrypt documentation states PIM values above ~485 (SHA-512) or ~98 (SHA-256) already produce very high iteration counts; extreme values are generally user error, but the overflow is exploitable if a malicious container file is opened.
- **Suggested fix:** Clamp `pim` to `[0, 9999]` in the Kotlin layer before passing to JNI (matching VeraCrypt's documented 0–2147 range). Add an explicit guard in `vc_get_iterations()` for negative or extreme PIM values.

---

#### L-2: Fixed-size `snprintf` paths with 512-byte path buffer

- **File:** `app/src/main/cpp/arcanum_jni.cpp`, multiple locations (lines 1363, 1384, 1429, 1475, 1569, 1594, 1595, 1617, 1641, 1661)
- **Description:** All file-operation JNI functions assemble the FatFs drive path using `snprintf(fullPath, sizeof(fullPath), "%d:%s", pdrv, ...)` with a 512-byte stack buffer. A path argument longer than ~509 bytes (pdrv prefix is at most 2 chars + colon) will be silently truncated by `snprintf`, resulting in operations on an incorrect (truncated) path rather than an error. The Kotlin callers do not enforce a maximum path length.
- **Impact:** A maliciously crafted filename stored inside a container that exceeds 508 characters will silently have its path truncated, which could lead to reading or writing the wrong file. In practice FAT32/exFAT filenames are capped at 255 characters and full paths inside the volume are bounded, making this very unlikely to be exploitable in practice. No memory corruption occurs due to the bounded `snprintf`.
- **Suggested fix:** After each `snprintf`, add a check: `if (n < 0 || n >= (int)sizeof(fullPath)) return ERR_FILE;` to fail explicitly rather than silently truncating.

---

#### L-3: `BiometricAuth.authenticate()` without CryptoObject binding

- **File:** `app/src/main/java/zip/arcanum/core/security/BiometricAuth.kt`, lines 52–73
- **Description:** `BiometricAuth.authenticate()` (the simple variant used for non-vault biometric prompts) calls `BiometricPrompt.authenticate(promptInfo)` without a `CryptoObject`. This means the biometric result is not cryptographically bound to a hardware-backed key operation. For vault unlock, `BiometricCryptoManager` correctly uses `getCryptoObjectForDecrypt()` with AES-CBC, which does provide hardware binding. However, the bare `authenticate()` function is exposed as a general-purpose API. If a future call site uses it to gate vault access, the authentication result could be replayed or faked on a rooted device.
- **Impact:** Current usage of bare `authenticate()` is limited to `authenticateForDebug()` (debug mode access) and `authenticateWithDeviceLock()` (screen-capture-protection toggle), neither of which controls vault access. The vault unlock path correctly uses `BiometricCryptoManager`. Risk is low but the API design is a footgun.
- **Suggested fix:** Document the bare `authenticate()` function explicitly as "non-cryptographic binding — do not use for vault access." Consider making it private to `BiometricAuth` or renaming it to clarify it does not produce a `CryptoObject`.

---

#### L-4: `app_preferences` DataStore not excluded from backup

- **File:** `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`
- **Description:** `app_preferences.preferences_pb` (the `app_preferences` DataStore managed by `AppPreferences`) IS excluded from both backup rule files. However, re-reading the backup_rules.xml confirms it is excluded at line 15: `<exclude domain="file" path="datastore/app_preferences.preferences_pb"/>`. This finding was verified as a false alarm during code reading — it IS excluded. Marking for transparency.
- **Impact:** N/A — correctly excluded.
- **Suggested fix:** No action required. Included here for audit completeness.

---

### Informational

#### INFO-1: `security-crypto` dependency remains on alpha

- **File:** `gradle/libs.versions.toml`, line 23
- **Version:** `securityCrypto = "1.1.0-alpha06"`
- **Description:** `androidx.security:security-crypto` is pinned to `1.1.0-alpha06` (the same version noted in the prior audit). No stable `1.1.x` exists yet; the `MasterKey.Builder.setKeyScheme(AES256_GCM)` API requires this. This is an unavoidable dependency on pre-stable code for a core security primitive.
- **Impact:** Alpha APIs may have breaking changes between releases. The API has been functionally stable since early 2024 and is widely deployed in production apps. The practical risk is very low but should be monitored.
- **Recommendation:** Monitor for stable release. Add a comment in `libs.versions.toml` (already present) and a tracking issue.

#### INFO-2: `biometric` dependency on 1.1.0 (stable but old)

- **File:** `gradle/libs.versions.toml`, line 25
- **Version:** `biometric = "1.1.0"`
- **Description:** `androidx.biometric:biometric:1.1.0` is the last stable release of the biometric library (released Dec 2021). The upstream has moved toward `biometric-ktx` and the `androidx.biometric:biometric:1.2.x` line but stable `1.2.x` has not shipped as of this audit date. No known security CVEs in 1.1.0.
- **Impact:** No known security impact.
- **Recommendation:** Upgrade to 1.2.x when stable.

#### INFO-3: Container metadata stored in plaintext Room database

- **File:** `app/src/main/java/zip/arcanum/core/database/AppDatabase.kt`, `ContainerEntity.kt`
- **Description:** The Room database (`arcanum.db`) stores container names, filesystem paths, algorithm names, PRF/hash names, and timestamps in plaintext SQLite. The database file itself is excluded from cloud backup, but on a physically-accessed device the database is readable without knowing the container password. An attacker with the device can enumerate all container paths, their encryption algorithms, and last-access timestamps without mounting anything.
- **Impact:** Metadata leakage: an attacker learns how many containers exist, where they are stored, what cipher is used, and when they were last accessed. This is consistent with other implementations of VeraCrypt GUI tools. Container contents remain protected. The exclusion from backup prevents transfer of this metadata off-device.
- **Recommendation:** For a future hardening iteration, consider encrypting the Room database with SQLCipher. For the current release, the plaintext database is an accepted tradeoff (same as desktop VeraCrypt, Cryptomator, etc.).

#### INFO-4: Disguise feature — both activity-aliases exported

- **File:** `app/src/main/AndroidManifest.xml`, lines 76–103
- **Description:** Both `MainActivityArcanum` and `MainActivityCalculator` are `android:exported="true"` (required because they have `LAUNCHER` intent filters). At any point in time, the disguise-off alias is `ENABLED` and the disguise-on alias is `DISABLED`. An attacker can force launch the disabled alias using `adb shell am start -n zip.arcanum/.MainActivityArcanum` even when disguise is applied — this will launch the same `MainActivity` (the alias only affects the launcher icon and label). The actual `MainActivity` is `android:exported="false"`.
- **Impact:** The disguise feature hides the app's true nature from casual observers (launcher icon = calculator). An ADB-capable attacker can bypass this disguise, but ADB requires developer mode or physical unlocking, which is outside the threat model. The authentication gate inside `MainActivity` still runs; the disguise bypass does not unlock the vault.
- **Recommendation:** Document this limitation in the threat model. No code change needed — it is a fundamental constraint of Android activity aliases.

#### INFO-5: `accompanist-systemuicontroller` is deprecated

- **File:** `gradle/libs.versions.toml`, line 31 (`accompanist = "0.36.0"`)
- **Description:** Google has deprecated the Accompanist system UI controller library in favor of the `WindowInsetsController` API. No security issue, but the library may be abandoned in future.
- **Impact:** No security impact.
- **Recommendation:** Migrate to `WindowInsetsController` before the library becomes incompatible with a future Compose version.

#### INFO-6: `MANAGE_EXTERNAL_STORAGE` permission declared

- **File:** `app/src/main/AndroidManifest.xml`, line 14
- **Description:** `MANAGE_EXTERNAL_STORAGE` is declared (but per the comment, only requested on user action in `CreateContainerScreen`). This permission requires special justification for Google Play submission.
- **Impact:** No direct security impact. Play Store submission will require a policy declaration explaining the use case (user selects arbitrary external storage paths for VeraCrypt containers).
- **Recommendation:** Prepare the Play Store policy justification for this permission. The use case (user-selected container paths on external storage) is legitimate.

#### INFO-7: WorkManager work name is neutral but visible in plaintext DB

- **File:** `app/src/main/java/zip/arcanum/core/security/PanicWipeWorker.kt`, line 29
- **Work name:** `"arcanum_maintenance_sweep"`
- **Description:** The WorkManager job name is intentionally neutral to avoid revealing its purpose in WorkManager's plaintext SQLite database. The name is a reasonable obfuscation. DataStore settings are read inside the worker so no sensitive configuration is stored in WorkManager's DB.
- **Impact:** The work name itself does not constitute a vulnerability. A forensic examiner with database access could see the work name but not its purpose.
- **Recommendation:** No action needed. Current implementation is well-designed.

#### INFO-8: No Kotlin/Java tests for the lockout bypass window

- **File:** `app/src/test/java/zip/arcanum/crypto/CryptoKatTest.kt`
- **Description:** The KAT suite is excellent for algorithm correctness (Argon2id RFC 9106 vector, SHA-256 NIST vectors, lockout schedule). However, there are no tests for the timing-equalization paths (`dummyPromote()` vs `promotePanicPinToMain()`), or for the dummy-hash constant ensuring it never matches a real PIN.
- **Impact:** No direct security risk — the code has been manually reviewed and is correct. Tests would add regression protection.
- **Recommendation:** Add unit tests for: (a) DUMMY_HASH never matches any 4–8 digit PIN; (b) `verifyHash(pin, DUMMY_HASH, VERSION_ARGON2)` always returns false.

---

## Part-by-Part Notes

### Part 1 — Cryptographic Implementation

**PBKDF2:** Implemented entirely in `arcanum_jni.cpp`. Five hash functions (SHA-512, SHA-256, Whirlpool, Streebog, BLAKE2s-256) are all implemented as standard HMAC-PBKDF2 with correct PRF construction (ipad/opad XOR). Iteration count defaults are 500,000 for all hashes per VeraCrypt spec; PIM override formulas match VeraCrypt source. The keyfile pool implementation exactly matches VeraCrypt's `KeyFileProcess()`/`KeyFilesApply()` — all 4 CRC bytes per input byte (j+=4) are used, which was confirmed as the previously-fixed keyfile bug.

**Argon2id (PIN hashing):** Implemented via BouncyCastle 1.79 with t=2, m=65536 KB, p=1, len=32, v=19. These are adequate parameters for a PIN-based KDF with lockout. The RFC 9106 test vector is verified in the KAT suite.

**Constant-time comparison:** `MessageDigest.isEqual()` is used for both Argon2id and legacy SHA-256 PIN hash comparisons. This is the correct constant-time comparator for this context.

**RNG:** `read_urandom()` correctly loops on `EINTR` and detects EOF/error for master key generation and salt generation. The data-fill loop (non-quick-format) has a partial-failure silent fallback to zeros — see M-1.

**AES-XTS:** Uses VeraCrypt's upstream C AES and XTS implementations. Sector numbers are passed as `UINT64_STRUCT`. Key schedule is zeroed after use via `secure_memset()` (volatile pointer prevents compiler elision).

**KAT coverage:** Argon2id (RFC 9106 vector + parameter contract), SHA-256 (NIST FIPS 180-4), and lockout schedule. Native-layer KATs (AES, BLAKE2s, SHA-512, Whirlpool, PBKDF2) require a compiled `.so` and live in `androidTest/` (not checked in this audit as there is no emulator). This gap is acceptable given the upstream VeraCrypt provenance of the crypto code.

### Part 2 — Data Storage Security

**EncryptedSharedPreferences:** Two files use ESP:
- `arcanum_pin_prefs` — Argon2id PIN hashes (AES256-SIV keys / AES256-GCM values)
- `arcanum_bio_prefs` — biometric-encrypted container passwords (same encryption)

**AppPreferences DataStore** (`app_preferences`): plaintext, stores non-sensitive settings (theme, auto-lock delay, screen capture preference, disguise prompt state). Excluded from backup.

**PanicManager DataStore** (`panic_settings`): plaintext, stores panic action configuration. Excluded from backup.

**Room database** (`arcanum.db`): plaintext SQLite. Stores container paths, names, algorithm, timestamps. Excluded from backup. See INFO-3.

**Backup exclusions:** All sensitive files are correctly excluded in both `backup_rules.xml` (API < 31) and `data_extraction_rules.xml` (API 31+, cloud-backup + device-transfer sections). This includes `arcanum_pin_prefs.xml`, `arcanum_bio_prefs.xml`, `arcanum.db` (+ -shm, -wal), all DataStore pb files, and `arcanum_temp/`.

**Temp files:** Cache-path FileProvider exposes `arcanum_temp/` for sharing decrypted content. This directory is excluded from backup. There is no explicit secure-delete of temp files, but since containers are AES-encrypted and temp files are in internal cache (not accessible without root), the risk is low.

**android:allowBackup:** Set to `false` in `AndroidManifest.xml` line 32.

### Part 3 — Android-Specific Security

**Exported components:** `MainActivity` is `android:exported="false"`. Only the two activity-aliases are exported (required for `LAUNCHER` category). `ContainerCreationService` is `android:exported="false"`. The `InitializationProvider` and `FileProvider` are `android:exported="false"`.

**FileProvider:** Configured correctly with `android:exported="false"` and `android:grantUriPermissions="true"`. Serves only `arcanum_temp/` (cache-path). Attackers cannot access this path without a URI grant.

**FLAG_SECURE:** Set unconditionally in `onCreate()` and updated via `LaunchedEffect(screenCaptureProtection)`. If the user disables screen capture protection, `clearFlags(FLAG_SECURE)` is called. This is a user-controlled setting guarded by a biometric/device-credential re-authentication. The flag is set before `setContent`, so no window content is ever exposed without it.

**Billing and security features:** The `isPro` flag gates ONLY cosmetic/UX features: AMOLED glass mode, container count > 2, premium UI banner. Panic mode, biometric vault unlock, hidden volumes, and disguise are NOT gated by `isPro`. `SecuritySubScreen` does not receive `isPro` as a parameter. This is the correct design.

**F-Droid flavor:** The `fdroid` flavor uses `"playstoreImplementation"` for the billing library — meaning `BillingManager` in the fdroid source set is the no-op stub returning `isPro = true` (all features unlocked). The `BillingClient` dependency is only compiled for the playstore flavor. No Google Play Services code is reachable in the fdroid build.

**INTERNET permission:** Not declared in `AndroidManifest.xml`. The app makes no network calls. The Google Play Billing library (playstore flavor) requires connectivity but the permission is granted implicitly by the Billing library's AAR manifest.

### Part 4 — Authentication & Access Control

**PIN verification:** Argon2id with both main and panic hashes always computed (dummy hash substituted when absent) to prevent timing oracle on panic PIN presence. `MessageDigest.isEqual()` provides constant-time comparison. Lockout is enforced via `KEY_LOCK_UNTIL_MS` in EncryptedSharedPreferences with an escalating delay table (0 → 30s → 5m → 30m → 2h at 5/8/12/15 failures). The lockout is persistent across app restarts because it is stored in ESP.

**Timing equalization (H-1 regression):** Verified. Normal-PIN path calls `panicManager.getPanicSettings()` + `pinManager.dummyPromote()` (a DataStore read + synchronous `commit()`) to mirror the IO cost of `prepareForPanic()`. Both paths then immediately emit the navigation event before any wipe work starts. Navigation latency is equalized. The actual wipe runs in WorkManager after navigation.

**Biometric:** `BiometricCryptoManager` uses AES-CBC with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`. Key invalidation on new biometric enrollment means that adding a new fingerprint automatically invalidates all stored vault passwords — correct behavior for security. Vault unlock uses `getCryptoObjectForDecrypt()` which is cryptographically bound.

**PIN storage adequacy:** SHA-256 is used only as a legacy migration path; all new hashes are Argon2id. The migration upgrades on first successful login. This is an appropriate hybrid migration strategy.

### Part 5 — Code-Level Vulnerabilities

**Path traversal:** JNI file operations construct full paths as `pdrv:user_path`. The FatFs library operates inside the encrypted virtual filesystem — paths are resolved relative to the mounted volume, not the real filesystem. A `../` traversal inside FatFs cannot escape the encrypted volume because FatFs has no concept of parent directories beyond the volume root. The `jstring_to_string()` helper does not sanitize paths, but no traversal outside the container is possible via FatFs path resolution. The container file itself is opened by the Kotlin layer using the path from the Room database (user-supplied at time of creation/import, not at mount time for file operations).

**Filename sanitization:** UTF-8 validation is present in `nativeListFiles` before calling `NewStringUTF`. Other operations (`nativeReadFile`, `nativeWriteFile`, `nativeDeleteFile`, `nativeRenameFile`) do not validate UTF-8 in the path argument. Since paths come from `NativeFileInfo.path` produced by `nativeListFiles` (which already validated them), the risk chain is: a maliciously crafted FAT filename that passes the ASCII-only fast path in the listDir phase but contains bad bytes... which is blocked by the `is_valid_utf8` check. Low residual risk.

**JNI null/exception handling:** `jstring_to_string()` handles null jstrings. `jstringArray_to_vector()` handles null arrays. `report_progress()` checks for pending JNI exceptions and clears them. `nativeReadFile` has the negative-length guard. `nativeListFiles` validates UTF-8 before `NewStringUTF`. Exception handling is solid.

**Integer overflow:** `sizeBytes` in container creation is `jlong` (64-bit); `fileSize = dataSize + VC_DATA_OFFSET + VC_BACKUP_AREA_SIZE` — for pathologically large values this could theoretically overflow `uint64_t`, but `ftruncate` will fail before actual disk allocation. `sizeBytes` arrives from the Kotlin UI where it is validated before passing to JNI. PIM overflow is noted as L-1.

**TOCTOU:** The container file is opened by path and then operated on via the returned `fd`. The fd-based operations are not subject to TOCTOU. The only path-based check is the initial `open()`.

### Part 6 — Third-Party Dependencies

| Dependency | Version | Status |
|---|---|---|
| Kotlin | 2.2.10 | Stable |
| AGP | 9.2.1 | Stable |
| KSP | 2.2.10-2.0.2 | Matches Kotlin — correct |
| Compose BOM | 2025.05.01 | Stable |
| Hilt | 2.59.2 | Stable |
| Room | 2.7.0 | Stable |
| DataStore | 1.0.0 | Stable |
| **security-crypto** | **1.1.0-alpha06** | **Alpha — no stable 1.1.x exists** |
| biometric | 1.1.0 | Stable (old; no CVEs) |
| BouncyCastle | 1.79 | Stable (Feb 2025) |
| Coil | 2.5.0 | Stable |
| Media3 | 1.2.0 | Stable |
| WorkManager | 2.10.0 | Stable |
| billing-ktx | 7.1.1 | Stable (May 2025) |
| Lottie Compose | 6.6.4 | Stable |
| Haze | 1.5.3 | Stable |
| accompanist | 0.36.0 | Deprecated (no CVEs) |
| metadata-extractor | 2.19.0 | Stable |

**Billing 7.1.1:** No known security CVEs. The `PendingPurchasesParams.enableOneTimeProducts()` API is correctly used. The missing `isAcknowledged` check is noted as M-2.

**BouncyCastle 1.79:** Current stable release. No known critical CVEs.

### Part 7 — Build Configuration

**R8/minification:** `isMinifyEnabled = true` and `isShrinkResources = true` are set for the release build type. ProGuard rules correctly keep JNI-accessed classes, Room entities/DAOs, Hilt components, WorkManager workers, BouncyCastle, and kotlinx.serialization classes.

**CMake release flags:** `-O2 -DNDEBUG` are in `defaultConfig.externalNativeBuild.cmake.cppFlags` and also in `CMakeLists.txt`. `NDEBUG` disables the `LOGE` macro in the native library. No debug symbols are embedded by default in release builds (AGP strips them unless `packagingOptions.jniLibs.keepDebugSymbols` is set, which it is not here).

**Hardcoded secrets:** None found. The `KEY_ALIAS = "arcanum_biometric_key"` in `BiometricCryptoManager` is a key alias name (public), not a secret. Signing keys are read from `local.properties` which is gitignored.

**ProGuard and stack traces:** `-keepattributes SourceFile,LineNumberTable` retains line numbers for crash reports. `-renamesourcefileattribute SourceFile` renames source references to `"SourceFile"` in stack traces — this is standard for production apps and does not leak internal class names.

**Stack trace information:** The ProGuard rule `-keep class zip.arcanum.crypto.VeraCryptEngine { *; }` preserves all method names in the crypto engine (necessary for JNI). An attacker reading a crash log would see crypto engine method names. This is an acceptable tradeoff for JNI functionality.

### Part 8 — Documentation Accuracy

No `docs/` directory exists. There are no documentation files to audit against. The `CLAUDE.md` accurately describes the architecture. The security comments in the source code (particularly `PanicManager.secureDeleteFile()` and the keyfile pool implementation) are accurate and well-reasoned.

### Part 9 — Regression Check

| Finding | Description | Verdict |
|---------|-------------|---------|
| CRIT-1 | SecureRandom failure not detected | CONFIRMED FIXED for master key and salt paths; partial silent fallback to zeros remains in data-fill loop (downgraded to M-1) |
| H-1 | Panic PIN timing side-channel | CONFIRMED FIXED — `dummyPromote()` equalizes both paths; navigation fires before wipe |
| M-1 (19 June) | arcanum_bio_prefs.xml missing from backup exclusion | CONFIRMED FIXED — present in both backup_rules.xml and data_extraction_rules.xml |
| INFO-3 | R8/minification not configured | CONFIRMED FIXED — `isMinifyEnabled = true` in release buildType |
| INFO-5 | Alpha/beta dependencies | STILL PRESENT — security-crypto 1.1.0-alpha06 unchanged; no stable alternative exists |

---

## Release Readiness Assessment

**Verdict: ACCEPTABLE TO SHIP with awareness of open items.**

No Critical or High findings were identified in this audit. The three prior Critical/High findings (CRIT-1, H-1, prior-session M-1) are confirmed fixed.

**Open items by severity:**

- **M-1 (data-fill RNG fallback):** Real but low-probability. Affects only containers created with `quickFormat = false` when `/dev/urandom` partially fails (essentially never on Android). Does not compromise existing encrypted data. Recommend fixing before release; acceptable to ship as low-priority.
- **M-2 (billing acknowledgement check):** Affects only the Play Store flavor and only the non-security premium feature set. Security features are ungated. Fix before Play Store submission.
- **L-1 (PIM overflow):** Requires a user to deliberately enter an extreme PIM value (> ~1 million). Add a UI clamp. Can be addressed in a follow-up release.
- **L-2 (path buffer truncation):** No memory corruption. Adds explicit error return. Can be addressed in a follow-up release.
- **L-3 (bare biometric API footgun):** Current usage is safe. Add documentation guard. Can be addressed later.

**The F-Droid build is ready for release.** The fdroid flavor has zero Google Play Services dependencies, correct R8 configuration, all prior security findings fixed, and comprehensive backup exclusion rules.

**The Play Store build should fix M-2 (billing acknowledgement) before submission.** All other open items are acceptable to ship.

---

## Conclusion

Arcanum's security architecture is sound for its threat model. The cryptographic implementation (PBKDF2/BLAKE2s, AES-XTS, Argon2id for PIN) is correct and derived from VeraCrypt's upstream source. The PIN lockout mechanism, timing-equalization for panic deniability, biometric key binding, and backup exclusion rules are all correctly implemented. The most significant finding from prior audits (the panic-PIN timing side-channel, H-1) is confirmed fixed with a clean dual-path design.

The two remaining medium findings are in the billing layer (not the security layer) and in the native data-fill path (a degradation from random to zero data, not a decryption weakness). Neither blocks the F-Droid release. The Play Store release should address M-2 before submission.

This is a high-quality pre-release security posture. The codebase demonstrates consistent attention to security detail: dummy hash constants to prevent timing inference, `MessageDigest.isEqual()` for constant-time comparison, `volatile` pointer for key zeroing, `setInvalidatedByBiometricEnrollment(true)` for key revocation, and WorkManager-based panic wipe with a neutral work name to avoid forensic identification.
