# Arcanum Security Audit

**Date:** 9 September 2026
**Auditor:** Claude Opus 5
**Type:** Leak audit of everything built since v1.5.0 (fourth in series; follows 16, 19 and 20 June 2026)
**Scope:** 194 commits, 306 files, ~46,000 inserted lines between `v1.5.0` and `b9a7db8` — opened as the first step of preparing the 1.6 release

---

## Goal

Answer one question about the work of the last three weeks: **can anything that belongs inside a vault get out of it?** Not the cipher, which the June audits covered and which has not moved, but the edges - files written outside the volume, text handed to the system log, state carried between phones, surfaces exposed to other apps, and anything on disk that names what a vault holds.

The June audits reviewed the whole codebase against an attacker holding the phone. This one is narrower on purpose: it re-examines only what was added or changed since the last release, because that is where a regression can hide behind three audits that all passed.

---

## Threat Model

**Primary attacker:** someone who has the phone, or a copy of what is on it - app-private storage pulled with a backup tool or root, a settings backup file found on a PC, a bug report, a `adb logcat` capture. They can read anything not encrypted, and they are looking for two things: what the vaults contain, and whether a hidden volume exists.

**Deniability is treated as a security property, not a feature.** A file that says "this vault protects a hidden volume" is a finding here, even though it exposes no data.

**Out of scope:** the cryptography itself (audited in June, unchanged since apart from Argon2id, which is upstream VeraCrypt's construction), rooted-device attacks against the Keystore, and anything that predates v1.5.0 unless it was touched.

---

## Method

Every check below was made against the code as it stands at `b9a7db8`, and the claims marked **measured** were verified by running something rather than by reading:

- `git diff v1.5.0..HEAD` for the file set, then a pass over each new or changed subsystem.
- Every `Log.*` call in the changed Kotlin files read with its guard.
- The **release** native library built (`externalNativeBuildFdroidRelease`) and its strings compared with the debug one - the only honest way to answer "does the release log file names", since the answer lives in the compiler's output, not in the header.
- `adb shell dumpsys activity permissions` before and after a vault relocation, counting persisted URI grants.
- A device pass on a Nothing Phone (1) for each fix.

---

## Summary of Findings

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| M-1 | Saved mount log outlives both switches that write it, and names the PIM and hidden-volume protection | Medium | FIXED (`b4063d9`), device-verified |
| M-2 | Relocating a vault strands the persisted URI grant on the file it left | Medium | FIXED (`b4063d9`), measured |
| M-3 | A restored vault inherits external-app access that was consented to on another phone | Medium | FIXED (`b4063d9`), device-verified |
| M-4 | A settings backup written without a password states that a hidden volume exists | Medium | MITIGATED (`b4063d9`) by an explicit warning; field retained by decision |
| INFO-1..5 | Hygiene and documentation items, listed below | Informational | Open, none blocking |

No Critical or High findings.

---

## Findings

### Medium

#### M-1: The saved mount log outlives the switches that write it, and says too much

- **Files:** `arcanum/containers/ui/VaultViewModel.kt` (`persistMountLog`, the `mountLogger.log` calls), `settings/SettingsViewModel.kt` (`setSaveMountLog`, `setDebugMode`), `core/security/VaultTraceCleaner.kt` (`clearMountLog`)
- **Description:** With **Settings > Debug > Save mount log** on, the last mount's log is written in plain text to `filesDir/last_mount.log`. Its lines carry the vault's name, its full path (or the last 40 characters of its SAF URI, which is normally the file name), the cipher and PRF, whether a hidden volume was protected - and the **PIM's numeric value**. Turning the switch off, or turning debug mode off entirely, stopped further writes but left the existing file in place; and with debug mode off, the Debug screen that holds the only "clear" button is no longer reachable. The file was then removed by nothing short of a panic wipe or forgetting the vault.
- **Impact:** app-private storage is not world-readable and `allowBackup="false"` keeps it out of Android's own backup, so this is not a remote leak. It is a forensic one: a plain file naming the vault, its location and the existence of a hidden volume is precisely what this app exists to avoid leaving behind, and the PIM is half of a credential - knowing it removes the search space it was meant to add.
- **Fix applied:** the log records `PIM: custom` and never the value; `setSaveMountLog(false)` and `setDebugMode(false)` both delete the file through the existing `VaultTraceCleaner.clearMountLog()`.
- **Verification:** on device - log written, switch off, the Debug screen's mount-log card empty; repeated for the debug-mode path.

#### M-2: Relocating a vault strands the grant on the file it moved off

- **File:** `arcanum/containers/ui/VaultViewModel.kt` (`commitRelocate`), `core/security/VaultTraceCleaner.kt`
- **Description:** "Choose the file", added on 8 September so a vault whose file has moved can be pointed at it again, overwrote the row's `safUri` without releasing the persisted URI permission on the previous one. Nothing else reclaims it: `releaseSafPermission` releases a vault's *current* URI when the vault is removed, and `purgeOrphans` deliberately does not sweep grants (releasing an unclaimed-looking tree grant could lock a live vault out). The grant therefore persisted for the life of the install.
- **Impact:** the app retains read/write permission on a file it no longer tracks, and the system keeps a record naming that file - visible to anything that can read `dumpsys activity permissions`, and to the user in system settings. This is the same class as the stranded grants fixed in #134; it was reintroduced by a new code path.
- **Fix applied:** `commitRelocate` reads the previous URI before the update and calls the new `VaultTraceCleaner.releaseReplacedSafUri`, which releases it unless another vault or a keyfile still uses it.
- **Verification:** **measured.** Persisted grants before the relocation: 3, including `primary:Vera/ext4test`. After relocating that vault to `Vera/Relocate/ext4test`: still 3, with the old entry gone and the new one in its place. Without the fix the count would have been 4.

#### M-3: A restored vault inherits external-app access consented to on another phone

- **File:** `core/backup/SettingsBackup.kt` (`Vault.toEntity`)
- **Description:** `externalAccessEnabled` - the per-vault opt-in that exposes a mounted vault to the system file picker through `VaultDocumentsProvider` - travelled in the backup and was restored as written. The same mapping already resets `isMounted` and `hasBiometric` with the reasoning that a restored row must not claim something that is only true of the phone it came from; this flag was missed.
- **Impact:** a consent given on one device, about the apps installed on that device, silently applied on another. The provider still requires the vault to be mounted, so nothing is exposed while it is closed - but the first mount on the new phone opens it to every app that can pick a document, without the explanation the feature normally insists on.
- **Fix applied:** `externalAccessEnabled = false` on import, alongside the other two, with the reasoning recorded in the comment.
- **Verification:** on device - a vault with external access on, backed up and restored, comes back with it off.

#### M-4: A backup with no password states that a hidden volume exists

- **File:** `core/backup/SettingsBackup.kt` (the `Vault` record), `settings/BackupSaveSettings.kt`
- **Description:** the password on a backup file is the user's choice (agreed when the feature was designed). Without one the file is plain JSON, and it carries `mountProtectHidden` for every vault - which is set precisely when the outer volume of a hidden pair is mounted with protection. Anyone reading such a file learns not only where the vaults are, which the UI already warns about, but that a hidden volume exists.
- **Impact:** the deniability the hidden-volume feature provides is defeated by a file sitting outside the vault. The volume itself remains indistinguishable from random data; the backup names it anyway.
- **Decision and mitigation:** Esdex's call was to keep the field and warn rather than drop it, so that a restored vault still remembers to protect its hidden volume. Before writing a backup with no password, if any included vault has the flag, a full-screen warning now says what the file would reveal and offers to cancel; with a password, or with the vault list excluded, it does not appear. The docs say the same under **The password on the file**.
- **Residual risk:** accepted and explicit. A user who reads the warning and continues has a plain file that names a hidden volume.

---

### Informational

- **INFO-1: `PBEKeySpec` is never cleared in `BackupCodec`.** `deriveKey` builds a `PBEKeySpec` and never calls `clearPassword()`, and the derived key bytes are not zeroed. The caller's `CharArray` *is* wiped (`BackupViewModel` calls `fill(BLANK)` on both paths). In-process memory only; worth closing for consistency with the rest of the credential handling.
- **INFO-2: the manifest calls `VaultDocumentsProvider` read-only.** The comment above it says "Read-only SAF access to opted-in, mounted vaults"; the provider implements write, create, delete and rename for a vault that is not mounted read-only. The code is correct and gated (`requireExposedAndMounted`); the comment understates the surface, which is the wrong direction for a security comment.
- **INFO-3: `ACCESS_NETWORK_STATE` is declared and unused.** No `ConnectivityManager` use anywhere in the app; it most likely arrives from a library's manifest and is declared explicitly here as well. An unused permission in an app whose selling point is that it has no INTERNET permission is worth removing or annotating.
- **INFO-4: `file_provider_paths.xml` still exposes `arcanum_temp`.** Nothing writes that directory any more - the pre-#103 "Open with" did, and `FileUtils.purgeLegacyTempFiles` only deletes what old versions left. The declaration can go once the purge is retired.
- **INFO-5: `receive_shares` restored `true` shows the switch on until the next start.** `MainActivity` reconciles the preference to the component's real state at every launch, so the share alias is never actually enabled by a restore; between the restore and the next start the switch reads on while the alias is off. Cosmetic, and the safe direction.

**Out of scope, noted in passing:** the photo viewer's "Open in Maps" hands a picture's EXIF coordinates to an external app. It predates v1.5.0 and is user-initiated, but it is the one place where data derived from a vault's contents leaves the app silently.

---

## Part-by-Part Notes

### Part 1 — The system log

**Clean, measured.** The ext4 driver's messages name what they work on (`resolve '/photos/holiday.jpg' -> inode 15`, `unlink 'tax-2025.pdf' ...`), and they are compiled out of release builds through `NDEBUG`; the JNI layer's `LOGI`/`LOGE` hang on the same switch. Verified rather than assumed: the built release library contains **0** occurrences of those format strings and **0** of the `Arcanum-ext4` tag, against 24 and 1 in the debug library.

Kotlin logging in the changed files: every call is either inside `if (BuildConfig.DEBUG)` or carries nothing from a vault (USB byte offsets and transfer sizes). No path, name or credential is logged unguarded.

### Part 2 — What is written outside a vault

**Clean.** The media index lives in the SQLCipher-encrypted database. Thumbnails are `.enc` files under a Keystore master key, in a per-vault directory. Audio waveforms are encrypted, their file names carry a hashed vault key so they can be found and deleted with the vault. The text editor and the PDF viewer write nothing outside the volume - both read through the mounted handle. Cross-vault copy and move read a megabyte at a time from one handle and write it into the other, with no staging file (confirmed on device the same day). The settings backup is written straight into the SAF target the user picked.

The two plaintext files that do exist are the crash reports (`filesDir/crash`, which can carry a path inside an exception message, and are cleared by panic and by vault purge) and the mount log (M-1).

### Part 3 — The settings backup format

The envelope is PBKDF2-SHA256 at 600,000 rounds and AES-256-GCM with a random salt and nonce; the tag means a wrong password and an edited file fail identically, so a tampered file cannot be half-restored. The PIN, the panic configuration and biometric credentials never travel, by construction rather than by filter. Passwords are `CharArray` and are wiped after use. What the file does carry - names, paths, per-vault mount settings, the volume fingerprint - is stated in the UI and in the docs. See M-3 and M-4 for what changed.

### Part 4 — Surfaces other apps can reach

**Clean.** `MainActivity` is not exported. The launcher aliases are the only unconditionally exported entries. The share receiver is a disabled alias, enabled only by its setting, and the preference is reconciled *from* the system state at every launch rather than trusted. `VaultDocumentsProvider` is exported because DocumentsUI must bind to it, is guarded by `MANAGE_DOCUMENTS`, and refuses anything that is not both opted-in and mounted right now. The media session's metadata is neutral unless the user has turned on the setting that publishes it. There is no INTERNET permission.

### Part 5 — The keep-alive service (new in this cycle)

The notification carries no vault name and has a disguised face when the calculator is on. The undisguisable trace - the app's own label in the shade's "active apps" panel - is documented rather than hidden, and was measured when the feature was built. `foregroundServiceType` is `specialUse` with an honest declaration.

### Part 6 — Regression check against the June audits

The backup exclusion rules still list every sensitive file, and `allowBackup="false"` makes them belt-and-braces. `FLAG_SECURE` is still set at Activity level, with the user-facing switch as the only way to clear it. The panic path still clears crash logs, mount logs, waveforms, thumbnails and the media index. None of the June findings have regressed.

---

## Release Readiness Assessment

**Verdict: the 1.6 changes are safe to ship.**

No Critical or High findings. All four Medium findings were fixed or explicitly mitigated in `b4063d9`, on the same day they were found, and each was verified on the device rather than by inspection alone. The Informational items are hygiene and documentation; none of them blocks a release, and INFO-1 and INFO-2 are worth closing in the next cycle.

The two leaks that mattered - a plaintext file naming a hidden volume, and a permission the app kept on a file it had forgotten - were both introduced by features built in this cycle, by code that passed its own device pass. That is the argument for auditing the diff of every release rather than only the whole codebase before the first one.

---

## Conclusion

The security posture of the work since v1.5.0 holds. The encrypted-at-rest story is consistent - database, thumbnails, waveforms and backups all encrypt what they hold, and the new features that touch files (the editor, the PDF viewer, the folder filter, cross-vault transfer) write nothing outside the volume they came from. The release build says nothing in the system log about what a vault contains, and that claim is now backed by a measurement rather than by a comment in a header.

What this audit found was not a weakness in the cryptography but four places where state escaped its intended lifetime: a debug log that outlived its switch, a permission that outlived its file, a consent that outlived its phone, and a fact about a hidden volume that outlived the volume's own deniability. All four are closed.
