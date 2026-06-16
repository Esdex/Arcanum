# Arcanum Security Audit

**Date:** June 2026
**Auditor:** Internal pre-release audit
**Threat model:** Attacker with physical access to a locked device, technically skilled, in possession of the decompiled APK and the on-disk container/preference files.

## Summary Table
| Severity | Count |
|---|---|
| Critical | 3 |
| High | 5 |
| Medium | 7 |
| Low | 6 |
| Informational | 6 |

---

## Critical (must fix before release)

### CRIT-1: `/dev/urandom` read failures are silently ignored when generating master keys and salts
**File:** `app/src/main/cpp/arcanum_jni.cpp:776-779` (and call sites `:800`, `:1038`, `:1061-1065`, `:1661`)
**Description:** `read_urandom()` ignores the return value of both `open()` and `read()`:
```c
static void read_urandom(uint8_t *buf, size_t len) {
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) { read(fd, buf, len); close(fd); }
}
```
If `/dev/urandom` cannot be opened (SELinux denial, fd exhaustion, sandbox), the master key / salt buffer is left holding whatever it was initialised to. `masterKey[192] = {}` and `salt[VC_HEADER_SALT_SIZE]` (uninitialised) mean the key is either all-zero or stack garbage. `read()` can also return a short read and the missing tail is never filled. The non-`quickFormat` data fill (`:1061-1065`) has the same flaw — a failed `read` writes the same buffer repeatedly.
**Impact:** A container could be created with an all-zero or low-entropy master key while appearing to succeed. An attacker who knows this failure mode (it is in the open-source code) can attempt zero-key/low-entropy decryption of every container. This is a catastrophic, silent key-generation failure — the single most important property of an encryption tool.
**Suggested fix:** Loop `read()` until `len` bytes are obtained; treat any open/read failure as a hard error and abort container creation (return `ERR_FS`/new error). Prefer `getrandom(buf, len, 0)` with `EINTR` retry, falling back to `/dev/urandom` only if `getrandom` is unavailable. Never proceed with key material that was not fully populated by the CSPRNG.

### CRIT-2: PIN is stored as unsalted single-pass SHA-256 with no rate limiting or lockout
**File:** `app/src/main/java/zip/arcanum/core/security/PinManager.kt:106-109`, `:72-80`
**Description:** PINs are hashed with one round of SHA-256 and no salt:
```kotlin
val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
```
The hash is stored in `EncryptedSharedPreferences`, but the threat model assumes the attacker can extract files and (on a rooted/compromised device, or via the keystore on some devices) recover the encrypted-prefs plaintext. A typical numeric PIN (4-8 digits) has at most 10^8 candidates; unsalted single-SHA-256 is brute-forceable in well under a second on commodity hardware. There is also **no failed-attempt counter, no lockout, and no backoff** anywhere in `verifyPin()` or its callers, so even online guessing against the live app is unthrottled.
**Impact:** The PIN — the primary gate to the entire authenticated Arcanum surface and the panic mechanism — provides essentially no resistance to an attacker who can read the prefs file, and weak resistance to live guessing. Note: the PIN does **not** derive container keys (those use VeraCrypt PBKDF2), so containers themselves are not directly exposed, but the disguise/panic gate is.
**Suggested fix:** Derive the PIN verifier with a memory-hard KDF (Argon2id) or at minimum PBKDF2-HMAC-SHA256 with a per-install random salt and a high iteration count. Add a persistent failed-attempt counter with exponential backoff / lockout, stored so it cannot be reset by clearing app data alone.

### CRIT-3: Decrypted file contents are written to `cacheDir` in plaintext and only `delete()`-d
**File:** `app/src/main/java/zip/arcanum/arcanum/files/ui/FileManagerViewModel.kt:602-625` (extraction), `:633-637`, `:823` (cleanup); `app/src/main/java/zip/arcanum/arcanum/files/ui/FileManagerScreen.kt:216-219` (FileProvider share)
**Description:** "Open with external app" reads the entire file out of the encrypted container, buffers it fully in a `ByteArrayOutputStream`, and writes the **plaintext** to `cacheDir/arcanum_temp/<file.name>`, then exposes it to arbitrary external apps via `FileProvider`. Cleanup is a plain `File.delete()`, which only unlinks the inode — the plaintext blocks remain recoverable on flash until overwritten. The file persists across the handoff to another (untrusted) app, and if the process is killed before `clearTempFiles()` runs (`:823` is best-effort), it is never deleted at all.
**Impact:** The whole point of the app is that decrypted data never lands on persistent storage. This writes confiscatable plaintext copies of vault contents to a world-of-other-apps-reachable cache, defeating the threat model. Forensic recovery of `cacheDir` yields the very files the container was meant to protect.
**Suggested fix:** Avoid materialising plaintext on disk where possible (stream via a `ContentProvider` backed by the in-memory/container read path). Where a temp file is unavoidable, write to a no-backup, app-private path, overwrite with random data before deleting (as `PanicManager.secureDeleteFile` does), delete deterministically in a `finally`/lifecycle-tied hook, and scope the FileProvider grant tightly with revocation after use. Reconsider whether "open with external app" is compatible with the security promise at all.

---

## High (should fix before release)

### HIGH-1: PIN comparison is not constant-time
**File:** `app/src/main/java/zip/arcanum/core/security/PinManager.kt:75-79`
**Description:** `verifyPin()` compares hashes with Kotlin `==` (`String.equals`), which short-circuits on the first differing character. The normal PIN is checked first, then the panic PIN.
**Impact:** Timing leakage on the hex-string comparison is low-value on its own (the input is already hashed), but combined with CRIT-2 it is another data point. More importantly, the ordering means an observer measuring response time could in principle distinguish "panic-PIN path" from "wrong-PIN path," weakening panic-PIN deniability (see HIGH-2).
**Suggested fix:** Use `MessageDigest.isEqual()` (constant-time) on the raw digest bytes and structure normal/panic/wrong checks so they take indistinguishable time.

### HIGH-2: Panic PIN executes destructive work synchronously, making it timing-distinguishable from a normal unlock
**File:** `app/src/main/java/zip/arcanum/core/security/PanicManager.kt:93-135`; caller path from `verifyPin()` returning `PinResult.PANIC`
**Description:** On a panic PIN, `executePanic()` does potentially long operations (multi-pass `secureDeleteFile` over every container `:137-158`, DB wipes, keystore credential deletion) before/while the UI transitions. A normal unlock does none of this. An attacker watching the device (or one who forces the user to unlock) can observe the visible delay / disk activity and infer that the panic PIN was used — and may then prevent completion (e.g. yank power) or know to look for a hidden real PIN.
**Impact:** Undermines the plausible-deniability guarantee that the panic PIN is supposed to provide. Also, `secureDeleteFile` on flash/SSD with wear-levelling does not reliably overwrite the original blocks, so "secure" deletion is weaker than implied.
**Suggested fix:** Make the panic path indistinguishable in timing from a normal unlock (transition to the clean home immediately, perform destructive work in the background). Document the flash wear-levelling limitation of overwrite-based deletion; prefer crypto-erase (discarding key material) where feasible.

### HIGH-3: Container password is passed to a Service via an Intent extra in cleartext
**File:** `app/src/main/java/zip/arcanum/arcanum/containers/ui/CreateContainerViewModel.kt:280`; `app/src/main/java/zip/arcanum/arcanum/containers/service/ContainerCreationService.kt:47,70`
**Description:** The new-container password is placed in an `Intent` extra (`EXTRA_PASSWORD`) and handed to `ContainerCreationService` via `startService`. Intent extras transit the Binder/ActivityManager, can appear in system dumpstate/bugreports, and live in the parcel longer than a direct in-process call.
**Impact:** The container password — which derives the master key — is exposed to system-level logging surfaces and to anyone able to capture a bugreport. The service is `exported="false"` so cross-app injection is not the risk; the risk is the secret leaving the process boundary in cleartext.
**Suggested fix:** Pass secrets in-process (shared singleton / repository) rather than through Intent extras, or hand off a short-lived opaque token and keep the password in a process-local store. Zero the `String`/`CharArray` after use.

### HIGH-4: Path traversal — caller-controlled file names are concatenated into host `cacheDir` and into JNI/FatFs paths
**File:** `app/src/main/java/zip/arcanum/arcanum/files/ui/FileManagerViewModel.kt:603-604` (`File(tempDir, file.name)`); `app/src/main/cpp/arcanum_jni.cpp` path building at `:1244`, `:1304`, `:1340`, `:1433-1434`, `:1459-1460`, `:1482`, `:1506`, `:1522-1523`
**Description:** `file.name` comes from the container's own directory listing and is joined to a host path with no `..`/separator sanitisation; a container crafted to contain an entry named `../../<something>` could cause the plaintext temp write to escape `arcanum_temp/`. On the native side, directory/file path components are `snprintf`-formatted into `"%d:%s"` and passed to FatFs without traversal checks; `ep`/`entryPath` are built by string concatenation. FatFs `FF_FS_RPATH 0` mitigates `.`/`..` resolution inside the volume, but the host-side join (FileManager) is the concrete exploitable sink.
**Impact:** A malicious or corrupted container could write attacker-named plaintext outside the intended temp directory, and recursive delete (`unlink_recursive_locked`, `:1495-1511`) operating on attacker-influenced names is risky.
**Suggested fix:** Reject or sanitise any name containing path separators or `..` before host-side joins; use `File(tempDir, sanitizedBaseName)` and verify the canonical path stays within `tempDir`. Validate path components on the native boundary as well.

### HIGH-5: `nativeReadFile` allocates a Java array of an unchecked, caller-controlled `length`
**File:** `app/src/main/cpp/arcanum_jni.cpp:1293-1325`
**Description:** `length` (a `jint`) is used directly in `env->NewByteArray(length)` and `f_read(..., (UINT)length, ...)` with no bounds/negative check. A negative `length` makes `NewByteArray` throw (then unchecked — see HIGH/MED on exception handling) and the subsequent `GetByteArrayElements` on a null array dereferences null. A very large `length` causes a large allocation (DoS). The trailing "trimmed" branch (`:1318-1324`) calls `GetByteArrayElements` again and passes it where a pointer/size is expected, leaking the array elements and mishandling the copy.
**Impact:** Native crash / OOM DoS and a JNI local-reference/elements leak per short read; potential null-deref. Reachable from normal file-reading flows with adversarial sizes.
**Suggested fix:** Validate `0 <= length <= sane_cap` and `offset >= 0` before allocating; check for pending exceptions after each JNI call; fix the trimmed-array path to copy from the original buffer correctly and release elements.

---

## Medium (fix soon after release)

### MED-1: No JNI exception checking after JNI calls; some `New*`/`FindClass` results used unchecked
**File:** `app/src/main/cpp/arcanum_jni.cpp` throughout — e.g. `:1234-1236` (`FindClass`/`GetMethodID` unchecked), `:990-992` (`GetObjectClass`/`GetMethodID`), `:1311-1315`, `:1354-1358`
**Description:** Return values of `FindClass`, `GetMethodID`, `NewByteArray`, `NewObjectArray`, `GetByteArrayElements` are largely not null-checked, and `ExceptionCheck`/`ExceptionClear` is never called. A thrown JNI exception (e.g. OOM) leaves an exception pending while native code keeps running, leading to undefined behaviour or abort.
**Impact:** Crashes / abort under memory pressure or with malformed inputs; harder-to-reason-about native state. Not directly a confidentiality break but reduces robustness of the security boundary.
**Suggested fix:** Null-check every JNI lookup/allocation, call `env->ExceptionCheck()` after calls that can throw, and bail cleanly (freeing native resources and zeroing key buffers) on pending exceptions.

### MED-2: AES uses table-based (T-table/S-box lookup) implementation — cache-timing side channel
**File:** `app/src/main/cpp/veracrypt/Crypto/Aescrypt.c:81-94,203-212` (four_tables/one_table/no_table), with `CRYPTOPP_DISABLE_ASM=1` in `CMakeLists.txt:63`
**Description:** The compiled AES (VeraCrypt's `Aescrypt.c`) is the classic table-lookup implementation; with ASM disabled there is no AES-NI / bit-sliced constant-time path. Table lookups indexed by key/state bytes are the textbook source of cache-timing key recovery. (Note: the unused `aes_openssl_wrap.c` — not in `CMakeLists.txt` and therefore dead code — is *also* table-based and its `gmul`/`sbox` are likewise non-constant-time; its comment claiming `Aescrypt.c` is "broken on ARM64" is unverified and concerning.)
**Impact:** On a shared device an attacker with a co-resident measurement primitive could attempt cache-timing recovery of the XTS keys. Practical exploitation on a phone is difficult but in-scope for a "technically skilled" adversary; this is an accepted limitation in upstream VeraCrypt, so it should at least be documented.
**Suggested fix:** Prefer a constant-time/hardware AES (ARMv8 Crypto Extensions) at runtime where available; remove the dead, unreviewed `aes_openssl_wrap.c` to avoid confusion and the risk of it being wired in later.

### MED-3: Backup/data-extraction rule files are empty templates
**File:** `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
**Description:** Both files are the unedited Android Studio sample stubs. `android:allowBackup="false"` (Manifest `:27`) does disable auto-backup, which is the primary protection, but the empty `<cloud-backup>`/`<device-transfer>` sections mean that if `allowBackup` is ever flipped, or for the device-to-device transfer path on Android 12+, nothing is explicitly excluded.
**Impact:** Defence-in-depth gap. With `allowBackup=false` the present risk is low, but a future regression would silently start backing up `EncryptedSharedPreferences` (PIN hash, biometric blobs) and DataStore (panic config).
**Suggested fix:** Explicitly `<exclude>` all sensitive prefs/DataStore/DB from both cloud backup and device transfer, even though backup is currently disabled.

### MED-4: FLAG_SECURE is user-disable-able and toggled at runtime
**File:** `app/src/main/java/zip/arcanum/MainActivity.kt:37-64`; default `true` in `AppPreferences.kt:72-73`
**Description:** FLAG_SECURE is set in `onCreate` and then a `LaunchedEffect` clears it whenever the user disables "screen capture protection." Default is on (good), but a user can turn it off, and there is a brief window during `setContent` composition before the effect runs.
**Impact:** If disabled, screenshots/recorders and the recents thumbnail can capture vault contents. User-controlled, so lower severity, but the setting weakens a stated OS-level guarantee from CLAUDE.md.
**Suggested fix:** Consider making FLAG_SECURE non-optional, or at minimum warn the user about the consequence. Ensure it is applied before any sensitive content can render.

### MED-5: PBKDF2 inner buffers (`U`, `T`, `derivedKey`, header `body`) — most are zeroed, but intermediate PBKDF2 state is not
**File:** `app/src/main/cpp/arcanum_jni.cpp:432-442,478-488,529-539,580-590,636-646`
**Description:** The per-block PBKDF2 stack buffers `U[64]/T[64]` (and 32-byte variants) holding key-derivation intermediates are never `memset`-zeroed before the function returns; `saltb` is freed without zeroing. The header `body` and `derivedKey` in `read_vc_header`/`write_vc_header` *are* zeroed (good), as are most `effPwd`/`masterKey` buffers.
**Impact:** Residual key-derivation material lingers on the stack/heap; combined with a later memory disclosure it could aid key recovery. Lower severity because these are intermediates, not the final key, and stack is reused quickly.
**Suggested fix:** `memset(U,0,..)`, `memset(T,0,..)` before return and zero `saltb` before `free`. Consider a `secure_zero` that the compiler cannot elide.

### MED-6: `MANAGE_EXTERNAL_STORAGE` (All Files Access) is requested
**File:** `app/src/main/AndroidManifest.xml:10-11`
**Description:** The app requests the broad "All files access" permission. While containers can live anywhere on shared storage (justifying it functionally), this is a high-privilege, high-scrutiny permission that also widens the attack surface and is incompatible with Play Store policy for many categories.
**Impact:** Greater blast radius if the app is compromised; the disguise is also weakened (a "calculator" requesting All Files Access is suspicious to a technical adversary inspecting granted permissions).
**Suggested fix:** Prefer SAF/scoped storage where possible; only request All Files Access when the user actually places a container outside app-scoped locations, and document the rationale.

### MED-7: Keyfiles are copied to plaintext cache and best-effort deleted
**File:** `app/src/main/java/zip/arcanum/core/utils/FileUtils.kt:15-27`; deletions scattered (`VaultScreen.kt`, `CreateContainerViewModel.kt`, `ContainerCreationService.kt:110`)
**Description:** `copyUriToCache` writes the SAF-selected keyfile into `cacheDir/arcanum_keyfile_<ts>` in plaintext; cleanup is the caller's responsibility via plain `delete()` calls spread across many UI paths. If any path is missed or the process dies, the keyfile (a secret equivalent to part of the password) persists in cache.
**Impact:** Keyfile material recoverable from cache, undermining the second auth factor. The native side does zero its in-memory keyfile buffer (`:741`), but the host-side cache copy is the weak link.
**Suggested fix:** Centralise keyfile temp lifecycle, overwrite-then-delete, use a no-backup dir, and delete in `finally` blocks rather than ad hoc UI callbacks.

---

## Low (nice to have)

### LOW-1: `is_valid_utf8` reads past buffer on truncated multibyte sequence
**File:** `app/src/main/cpp/arcanum_jni.cpp:944-963`
**Description:** For a lead byte near the NUL terminator, the validator dereferences `p[1]/p[2]/p[3]` before confirming they are within the string; a name ending in a truncated multibyte lead byte reads up to 3 bytes past the terminator. FatFs LFN buffers bound this, so impact is minimal, but it is an out-of-bounds read.
**Suggested fix:** Check for `\0` at each continuation byte before dereferencing the next.

### LOW-2: `report_progress` reflects untrusted `frac/speed` and recomputes class lookup every chunk
**File:** `app/src/main/cpp/arcanum_jni.cpp:987-994`
**Description:** No exception check after `CallVoidMethod`; minor inefficiency. Not security-relevant beyond MED-1's exception theme.
**Suggested fix:** Cache the method ID and check for exceptions.

### LOW-3: `algorithm`/`hashAlgorithm` params to `nativeOpenContainer` are ignored
**File:** `app/src/main/cpp/arcanum_jni.cpp:1119`; `VeraCryptEngine.kt:107-114`
**Description:** The open path brute-forces all 5 hashes × 15 algorithms regardless of the supplied hints, increasing unlock time and giving a small timing signal about which combination matched (early-return on success). Functionally correct, marginally information-leaking via timing.
**Suggested fix:** Honour the hints when provided; consider not early-returning if uniform timing is desired.

### LOW-4: Recursive directory delete has no depth guard distinct from FatFs limits
**File:** `app/src/main/cpp/arcanum_jni.cpp:1495-1511`
**Description:** `unlink_recursive_locked` recurses per directory level; a deeply nested attacker-crafted container could exhaust the native stack (`FF_PATH_DEPTH 10` and `entryPath[512]` bound this somewhat).
**Suggested fix:** Add an explicit depth cap and `snprintf` truncation handling.

### LOW-5: `MainActivity.onDestroy` only closes handles when `isFinishing`
**File:** `app/src/main/java/zip/arcanum/MainActivity.kt:94-102`
**Description:** On process kill without `isFinishing`, native handles/mounts aren't explicitly closed (the comment notes JNI handles don't survive process death, and `resetMountedState()` fixes DB flags on next launch). Acceptable, but mounted plaintext views are torn down only by process death timing.
**Suggested fix:** Also unmount on `onStop`/lifecycle background for defence in depth (the per-container `unmountOnBackground` exists but is off by default).

### LOW-6: `ALGORITHMS`/`vc_get_iterations` bounds rely on header-supplied `algId`/`hashId` being in range
**File:** `app/src/main/cpp/arcanum_jni.cpp:63-75,888-919`
**Description:** `read_vc_header` only accepts `ai`/`hi` it iterated over (0..14 / 0..4), so the stored `algId` is in range by construction — but `vc_get_iterations` and `ALGORITHMS[]` indexing assume that invariant holds everywhere. Defensive bounds checks at every index would harden against future refactors.
**Suggested fix:** Add explicit range asserts before indexing.

---

## Informational

### INFO-1: `aes_openssl_wrap.c` is unreferenced dead code
**File:** `app/src/main/cpp/aes_openssl_wrap.c` (not in `CMakeLists.txt`)
The file is not compiled. Its header comment asserts `Aescrypt.c` is "broken on Android ARM64," which, if true, would be alarming — but the project ships `Aescrypt.c` as the real AES. Remove the dead file or document why it exists; if `Aescrypt.c` genuinely has an ARM64 defect, that would be a separate critical issue requiring a known-answer-test (KAT) suite.

### INFO-2: No cryptographic known-answer / cross-compatibility tests are wired into the build
**Files:** build config; `./gradlew test`
Given the from-scratch HMAC/PBKDF2/keyfile implementations, the absence of KATs (NIST AES/XTS vectors, RFC PBKDF2 vectors, VeraCrypt round-trip) is a notable gap. Add them so a regression in key derivation is caught before shipping.

### INFO-3: Release build does not enable minification/obfuscation
**File:** `app/build.gradle.kts:60-68`
`isMinifyEnabled = false` for `release`. ProGuard/R8 is configured but inactive, and `proguard-rules.pro` is empty. This does not weaken crypto, but it leaves all symbol names and the disguise logic in the clear for the decompiling adversary and forgoes dead-code stripping.
**Note:** there is no separate hardened `release` signing/debuggable concern visible; no `debuggable true` was found. No hardcoded secrets/API keys were found in the build config or sources.

### INFO-4: CMake compiles with `-O2 -DNDEBUG` (good) but also `-fexceptions -frtti` on crypto C
**File:** `app/src/main/cpp/CMakeLists.txt:48-60`, `app/build.gradle.kts:33-47`
Release flags are appropriate (`NDEBUG`, `-O2`). No debug logging is compiled out by `NDEBUG` because `LOGE` uses `__android_log_print` directly — error logs (including file paths and errno, never passwords) remain in release. Consider gating `LOGE` behind a debug macro; confirmed **no password/PIN/key values are logged** anywhere (Kotlin or native).

### INFO-5: Dependency versions — several alpha/older releases
**File:** `gradle/libs.versions.toml`
Security-relevant:
- `androidx.security:security-crypto = 1.1.0-alpha06` — the entire EncryptedSharedPreferences/MasterKey foundation runs on an **alpha** library (and Jetpack Security is effectively deprecated/unmaintained upstream). This underpins PIN and biometric credential storage.
- `androidx.biometric = 1.2.0-alpha05` — alpha.
- `coil = 2.5.0`, `media3 = 1.2.0`, `navigation-compose = 2.7.6` — noticeably behind current; no specific CVE confirmed here but they are stale.
- `kotlinx-serialization-json = 1.6.2`, `metadata-extractor = 2.19.0` (parses untrusted EXIF from imported media — keep current).
No dependency with a *confirmed* CVE was identified in this pass, but the reliance on alpha security-crypto and an EXIF parser on attacker-supplied images warrants tracking. Pin to stable releases and re-scan with a dependency-vulnerability tool before release.

### INFO-6: Exported components are minimal and correct
**File:** `app/src/main/AndroidManifest.xml`
`MainActivity` is `exported="false"`; the only exported components are the two launcher `activity-alias` entries (required for the launcher/disguise toggle) which target the non-exported activity and carry only the standard MAIN/LAUNCHER filter. The `ContainerCreationService` and `FileProvider` are `exported="false"` with `grantUriPermissions` scoped to a single `cache-path`. No `BroadcastReceiver` is exported (the screen-off receiver in `VaultViewModel` is registered at runtime, context-registered, not manifest-exported). No WebView is used anywhere. No deep-link/`VIEW` intent-filters import untrusted data into the app. This area is clean.

---

## Sections explicitly reviewed and clean
- **Exported components / intent hijacking:** clean (INFO-6).
- **WebView:** none present.
- **SQL injection:** Room DAOs use parameterised bind args exclusively; no string-concatenated SQL. Clean. (Note: the Room DB itself is **not** encrypted — container metadata, paths, names are stored in plaintext SQLite; this is acceptable since it holds no key material, but an attacker reading the DB learns container locations/names. Consider SQLCipher if metadata confidentiality matters — tracked as a design note, not a finding.)
- **EncryptedSharedPreferences usage:** PIN hash and biometric blobs both use EncryptedSharedPreferences (AES256-SIV/GCM); no sensitive value was found in plain `SharedPreferences`. Panic config in DataStore is non-secret. Clean, modulo the alpha-library caveat (INFO-5).
- **Biometric `CryptoObject` / key invalidation:** `BiometricCryptoManager` correctly uses an AndroidKeyStore key with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`, and binds a `CryptoObject` for encrypt/decrypt. This is done well. (Minor: CBC/PKCS7 rather than GCM, and the decrypted password `String` from JSON cannot be zeroed — the `decrypted` byte array is zeroed but the `String` lingers; low concern.)
- **Hidden-volume write protection:** `disk_write` enforces `hiddenBoundary` and returns `RES_WRPRT` past it; logic appears correct.
