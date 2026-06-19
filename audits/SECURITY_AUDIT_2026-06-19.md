# Arcanum Security Audit

**Date:** 19 June 2026
**Auditor:** Claude Opus 4.8
**Type:** Targeted follow-up audit (fix verification)
**Scope:** Verification of fixes applied in response to previous audit findings

## Goal

This audit verifies the correctness and completeness of the fixes applied in the commits between the 16 June 2026 pre-release audit (`audits/SECURITY_AUDIT_2026-06-16.md`) and HEAD (`2c2cb98`). It is **not** a fresh full-codebase review. For each prior finding (Critical, High, Medium, Low, Informational) the audit confirms whether the fix is present, whether it actually closes the vulnerability, whether it is complete (callers updated, edge cases, no race conditions), and whether it introduced any new problem in adjacent code.

## Threat Model

Attacker with physical access to a locked device, technically skilled, in possession of the decompiled APK and the on-disk container/preference files.

## Fixes Reviewed

| ID | Commit | File(s) | Status |
|---|---|---|---|
| CRIT-1 | `336af5d` | `arcanum_jni.cpp` | Confirmed Fixed |
| CRIT-2 | `744c3df` | `PinManager.kt`, `CalculatorViewModel.kt`, `libs.versions.toml` | Confirmed Fixed (one residual: lockout resets on app-data clear) |
| CRIT-3 | `336af5d` | `FileManagerViewModel.kt`, `FileUtils.kt` | Confirmed Fixed (flash wear-levelling caveat) |
| HIGH-1 | `744c3df` | `PinManager.kt` | Confirmed Fixed |
| HIGH-2 | `ebd5bd3` | `PinManager.kt`, `PanicManager.kt` | **Partially Fixed** (KDF step equalized; post-verify panic work still runs synchronously before navigation without the normal-path delay) |
| HIGH-3 | `8b7a15b` | `ContainerCreationParams.kt`, `ContainerCreationService.kt`, `CreateContainerViewModel.kt` | Confirmed Fixed |
| HIGH-4 | `403537f` | `FileManagerViewModel.kt`, `arcanum_jni.cpp` | Confirmed Fixed (host sink closed; native FatFs paths unchanged but bounded by `FF_FS_RPATH 0`) |
| HIGH-5 | `403537f` | `arcanum_jni.cpp` | Confirmed Fixed |
| MED-1 | `a961c1e` | `arcanum_jni.cpp` | Confirmed Fixed (targeted hot paths; not exhaustive) |
| MED-2 | `a961c1e` | `aes_openssl_wrap.c` removed | Confirmed Fixed (dead file removed; table-based AES timing limitation remains, as before) |
| MED-3 | `a961c1e` | `backup_rules.xml`, `data_extraction_rules.xml` | **Partially Fixed** (`arcanum_bio_prefs.xml` not excluded) |
| MED-4 | `a961c1e` | `strings.xml` | Confirmed Fixed (warning text only; toggle remains user-disable-able by design) |
| MED-5 | `a961c1e` | `arcanum_jni.cpp` | Confirmed Fixed |
| MED-6 | `a961c1e` | `AndroidManifest.xml` | Confirmed Fixed (documentation only) |
| MED-7 | `a961c1e` | `CreateContainerViewModel.kt`, `ContainerCreationService.kt`, `VaultScreen.kt` | Confirmed Fixed |
| LOW-1 | `a860ba6` | `arcanum_jni.cpp` | Confirmed Fixed |
| LOW-2 | `7924290` | `arcanum_jni.cpp` | Confirmed Fixed |
| LOW-3 | `ffa84a4` | `arcanum_jni.cpp` | Confirmed Fixed |
| LOW-4 | `a860ba6` | `arcanum_jni.cpp` | Confirmed Fixed |
| LOW-5 | `dd9bac0` | `VaultViewModel.kt`, `libs.versions.toml` | Confirmed Fixed |
| LOW-6 | `a860ba6` | `arcanum_jni.cpp` | Confirmed Fixed |
| INFO-4 | `20a380c` | `arcanum_jni.cpp`, `CMakeLists.txt` | Confirmed Fixed |
| INFO-1 | `a961c1e` | (same as MED-2) | Confirmed Fixed |

INFO-2 (crypto KAT tests), INFO-3 (R8/minification), INFO-5 (alpha deps), INFO-6 (exported components — was already clean) were not addressed in this fix cycle and remain open as previously reported.

## Findings

### Critical
None found.

### High

#### H-1: Panic unlock is still timing-distinguishable from a normal unlock (HIGH-2 only partially fixed)
- **File:** `app/src/main/java/zip/arcanum/calculator/ui/CalculatorViewModel.kt:128-138`; `app/src/main/java/zip/arcanum/core/security/PanicManager.kt:91-133`
- **Related to:** HIGH-2
- **Description:** The fix correctly equalized the **KDF step** — `PinManager.verifyPin` now always runs two Argon2id derivations (substituting `DUMMY_HASH` when a slot is absent), so an attacker can no longer infer whether a panic PIN is configured from the verification time. It also replaced the multi-pass `secureDeleteFile` overwrite with a single `File.delete()`, cutting the per-container cost. However, the caller does **not** equalize the post-verification path:
  - `PinResult.NORMAL` runs `delay(1_000L)` and then navigates — a fixed ~1 s wall time after Argon2.
  - `PinResult.PANIC` calls `panicManager.executePanic()` **synchronously before navigation** (the code comment states "Wipe completes before navigation — spinner stays visible during wipe") and applies **no** fixed delay.
  `executePanic()` still performs a variable amount of work: `getAllContainersOnce()`, per-container `File.delete()`, per-container `biometricCryptoManager.deleteCredentials()` (AndroidKeyStore + EncryptedSharedPreferences writes), `containerDao` deletes/wipes, calculator-history wipe, and two DataStore `edit{}` writes. For a vault with many containers this duration is both non-trivial and proportional to vault contents, and it is structurally different from the normal path's constant 1 s.
- **Impact:** An observer who can time the unlock (shoulder-surfing the spinner, or a coercive adversary who forces an unlock) can still distinguish "panic PIN entered" from "real PIN entered" by the visible delay / disk activity, and may interrupt the wipe (pull power) or infer that a hidden real PIN exists. This is the exact plausible-deniability gap HIGH-2 was meant to close; only the KDF portion was equalized.
- **Suggested fix:** Transition to the clean home **immediately** on `PinResult.PANIC` (apply the same fixed `delay(1_000L)` as the normal path) and run `executePanic()` in a detached background coroutine (e.g. `GlobalScope`/`WorkManager`/an app-scoped supervisor) so its variable duration is not on the unlock-visible critical path. Ensure the destructive work is durable enough to survive the navigation.

### Medium

#### M-1: Biometric-wrapped container passwords are not excluded from backup / device transfer (MED-3 incomplete)
- **File:** `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`; sourced from `app/src/main/java/zip/arcanum/core/security/BiometricCryptoManager.kt:26` (`PREFS_FILE = "arcanum_bio_prefs"`)
- **Related to:** MED-3
- **Description:** The rewritten backup/extraction rules explicitly exclude `arcanum_pin_prefs.xml`, the Room DB, and the three DataStore files, but they do **not** exclude `arcanum_bio_prefs.xml` — the EncryptedSharedPreferences file in which `BiometricCryptoManager` stores the AES/CBC-wrapped container passwords (the "unlock with biometric" blobs). The original MED-3 finding explicitly called out "biometric blobs" as needing exclusion. The rules also reference a `datastore/file_manager_prefs.preferences_pb` that no longer corresponds to any DataStore in the code (only `app_preferences`, `vault_display_prefs`, `panic_settings` exist) — harmless but indicates the list was written from assumption rather than enumeration.
- **Impact:** Defence-in-depth gap, currently low because `android:allowBackup="false"` is still set and because the wrapping key lives in the non-exportable AndroidKeyStore (so a transferred blob is undecryptable on a different device). But the stated MED-3 goal — exclude all sensitive prefs from both cloud backup and device transfer — is not fully met; a future flip of `allowBackup` or a keystore-export path would expose the biometric credential store.
- **Suggested fix:** Add `<exclude domain="sharedpref" path="arcanum_bio_prefs.xml"/>` to both `<full-backup-content>` and to `<cloud-backup>` and `<device-transfer>` in `data_extraction_rules.xml`. Remove the stale `file_manager_prefs` entry, or add the corresponding DataStore if it was intended to exist.

### Low

#### L-1: Persistent lockout counter is reset by clearing app data (CRIT-2 residual)
- **File:** `app/src/main/java/zip/arcanum/core/security/PinManager.kt:124-130, 246-247`
- **Related to:** CRIT-2
- **Description:** The Argon2id migration, per-install salt, constant-time compare, and progressive lockout (5→30 s, 8→5 min, 12→30 min, 15+→2 h) are all implemented correctly and resolve the core of CRIT-2. However, the fail counter and lockout timestamp (`KEY_FAIL_COUNT`, `KEY_LOCK_UNTIL_MS`) live in the same EncryptedSharedPreferences as the PIN hash. The original CRIT-2 suggested fix asked for a counter "stored so it cannot be reset by clearing app data alone." An attacker who can clear app data resets both the counter and the PIN — but clearing app data also discards the PIN hash, so the practical online-guessing benefit is nil (no PIN to guess against afterward). This is therefore a minor residual, not a real bypass.
- **Impact:** Negligible in the stated threat model; noted only for completeness against the original suggested fix wording.
- **Suggested fix:** Optional — no action needed for release. If hardening is desired later, anchor the lockout state to a keystore-backed monotonic counter independent of app-data clears.

#### L-2: Non-`quickFormat` data fill silently writes zeros on `/dev/urandom` failure (CRIT-1 adjacent)
- **File:** `app/src/main/cpp/arcanum_jni.cpp:1162-1193`
- **Related to:** CRIT-1
- **Description:** The CRIT-1 fix correctly makes all **key-material** RNG paths (`read_urandom` for salts and master keys, in `write_vc_header`, `nativeCreateContainer`, `nativeCreateHiddenVolume`) hard-fail with `ERR_RAND` on any open/short-read error, looping with `EINTR` handling and `O_CLOEXEC`. This fully closes the key-generation flaw. The adjacent free-space **data-fill** loop, however, now `memset`s the chunk to zero and continues writing when `read()` fails, rather than aborting. This region is plausible-deniability padding (overwritten by the FS format and not key material), so the confidentiality impact is nil, but on RNG failure the free-space area becomes all-zero instead of random, which slightly weakens the "free space looks like random data" property used to hide the existence/size of stored data and hidden volumes.
- **Impact:** Minor plausible-deniability degradation only on a (rare) RNG failure during creation; no key-material or confidentiality impact.
- **Suggested fix:** On `read()` failure inside the data-fill loop, fall back to a keyed CSPRNG stream (e.g. encrypt a counter with the freshly generated master key) rather than zeros, or abort creation consistently with the key paths.

### Informational

#### I-1: Native FatFs path components are still not `..`-validated (HIGH-4 host sink closed)
- **File:** `app/src/main/cpp/arcanum_jni.cpp` (path building in `nativeListFiles:1388`, `nativeReadFile:1335`, etc.)
- **Related to:** HIGH-4
- **Description:** The concrete exploitable host-side sink is fixed: `prepareOpenWithExternalApp` now does `File(file.name).name` (FileManagerViewModel.kt:619), stripping any directory components so a crafted entry name like `../../x` collapses to `x` and cannot escape `arcanum_temp/`. The native side still `snprintf`s caller-influenced names into `"%d:%s"` FatFs paths without explicit `..` rejection, but as the original audit noted this is bounded by `FF_FS_RPATH 0` (no `.`/`..` resolution inside the volume) and stays within the encrypted volume. No change in risk; documented for completeness.

#### I-2: Container-creation password `String` is not zeroed (HIGH-3 residual)
- **File:** `app/src/main/java/zip/arcanum/arcanum/containers/service/ContainerCreationParams.kt`, `CreateContainerViewModel.kt`
- **Related to:** HIGH-3
- **Description:** The password no longer crosses the Binder/Intent boundary — it is held in an in-process `@Singleton AtomicReference` and atomically taken (`getAndSet(null)`) by the service, which fully resolves the IPC-leak in HIGH-3. The password is still a Kotlin `String` and is not (cannot easily be) zeroed after use; it lingers on the heap until GC. This was acknowledged in the original suggested fix and is a low-concern Kotlin-immutability limitation, not a regression.

## Conclusion

The fix cycle is solid and the app is materially more secure than at the 16 June audit. All three Criticals are properly closed: RNG failures now hard-abort key generation, the PIN moved to salted Argon2id with constant-time comparison and progressive lockout, and decrypted temp files are zero-overwritten (with fd sync) and deleted deterministically. Among the Highs, HIGH-1, HIGH-3, HIGH-4, and HIGH-5 are confirmed fixed with correct, complete implementations (the HIGH-5 JNI bounds/exception/local-ref handling is notably clean). The Medium, Low, and Informational native fixes (JNI null/exception checks, `secure_memset` of PBKDF2 intermediates, UTF-8 OOB, recursion depth cap, algorithm-index bounds, LOGE gated behind `NDEBUG`, honoured algorithm hints, lifecycle-based unmount) are all present and correct, with no regressions introduced in adjacent code (key buffers are still zeroed on every brute-force and hint path).

Two items should be addressed before release. **HIGH-2 is only partially fixed (finding H-1):** the Argon2 step is equalized, but the panic path still performs variable-length destructive work synchronously before navigation with no compensating delay, leaving the panic-vs-normal timing distinguishable — the deniability gap the fix targeted. **MED-3 is incomplete (finding M-1):** the biometric credential store `arcanum_bio_prefs.xml` is not excluded from backup/device-transfer. Neither is exploitable under current defaults (`allowBackup=false`, non-exportable keystore key), but both fall short of their own stated fix goals. The remaining residuals (lockout reset on app-data clear, free-space zero-fill on RNG failure, un-zeroed password `String`, native FatFs `..` validation) are low/informational and do not block release. Pre-existing open items from the prior audit (KAT crypto tests INFO-2, R8/minification INFO-3, alpha security-crypto dependency INFO-5, table-based AES cache-timing MED-2) remain outstanding and unchanged.
