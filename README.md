<p align="center">
  <img src="assets/arcanum-logo.svg" width="96" alt="Arcanum logo">
</p>

<h1 align="center">Arcanum</h1>

<p align="center">
  VeraCrypt-compatible encrypted vault manager for Android
</p>

<p align="center">
  <a href="https://www.apache.org/licenses/LICENSE-2.0">
    <img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=for-the-badge" alt="License">
  </a>
  <img src="https://img.shields.io/badge/platform-Android-green?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/min%20SDK-29%20(Android%2010)-brightgreen?style=for-the-badge" alt="Min SDK">
  <img src="https://img.shields.io/badge/language-Kotlin-7F52FF?style=for-the-badge" alt="Kotlin">
</p>

<!-- TODO: add F-Droid badge once published -->

<p align="center">
  <a href="https://arcanum.zip">Website</a> ·
  <a href="https://arcanum.zip/docs">Docs</a> ·
  <a href="https://arcanum.zip/privacy">Privacy Policy</a>
</p>

---

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-meet-arcanum.png" width="18%" alt="Vault screen">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-hidden-volumes.png" width="18%" alt="Hidden volumes">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04-calculator-disguise.png" width="18%" alt="Calculator disguise">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-panic-mode.png" width="18%" alt="Panic mode">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06-encrypted-gallery.png" width="18%" alt="Encrypted gallery">
</p>

---

## Features

**Cryptography**
- 🔐 Full VeraCrypt container compatibility — open containers created on desktop (Windows, macOS, Linux)
- 🔒 15 cipher configurations: AES, Serpent, Twofish, Camellia, Kuznyechik and all cascade combinations
- #️⃣ 5 PRF algorithms: SHA-512, SHA-256, Whirlpool, Streebog, BLAKE2s-256
- 🗝️ Keyfile support with pool-based derivation matching VeraCrypt's implementation
- 🔢 PIM (Personal Iterations Multiplier) support

**Privacy**
- 🫥 Hidden volumes for plausible deniability
- 🧮 Calculator disguise — app appears as a plain calculator on the home screen
- 🚨 Panic PIN — instantly triggers configurable wipe (containers, files, app data)
- 🔏 Auto-lock with configurable delay
- 🌐 No network permission — 100% offline, zero telemetry

**Vault access**
- 👆 Biometric unlock per vault (hardware-backed key binding)
- ⏱️ Per-vault auto-unmount on screen lock or background

**In-vault browsing**
- 🖼️ Encrypted gallery with image and video viewer
- 🎵 Audio player with waveform and dominant-color theming
- 📂 Full file manager (create, rename, delete, move files and directories)

**UI**
- 🎨 AMOLED Glass theme — frosted-glass system bars and dialogs on pure black
- 🌙 Dynamic Color (Material You) support
- 📱 Edge-to-edge, Android 10+

---

## Why Arcanum

Arcanum is built directly on VeraCrypt's cryptographic C sources — the same AES, XTS, PBKDF2, and cascade cipher implementations used in the desktop application. Containers created in Arcanum open in VeraCrypt on desktop and vice versa, with no conversion or export needed.

The PIN is protected with Argon2id (t=2, m=64 MB, p=1) rather than a simple hash. A panic PIN and a disguise mode are included as first-class features, not afterthoughts.

---

## Installation

### F-Droid

<!-- TODO: add F-Droid badge and link once MR is merged -->

Submission is pending review. Once published, you will be able to install directly from the F-Droid client or download the APK from the [F-Droid website](https://f-droid.org).

### Google Play

<!-- TODO: add Play Store badge and link -->

Coming soon.

### Direct APK

Download `Arcanum-v1.0.0-fdroid.apk` from the [Releases page](https://github.com/Esdex/Arcanum/releases/latest) and install manually.

### Build from source

```bash
git clone https://github.com/Esdex/Arcanum.git
cd Arcanum
./gradlew assembleFdroidRelease
```

**Requirements:**
- Android Studio (with JBR — set `org.gradle.java.home` in `~/.gradle/gradle.properties`)
- Android NDK r28+
- CMake 3.22.1+
- Min SDK 29 / Target SDK 36

The `fdroid` flavor builds with all features unlocked and no billing dependency. The `playstore` flavor includes Google Play Billing for the freemium tier.

---

## Architecture

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) |
| Navigation | Navigation Compose, single-Activity |
| Crypto core | C++/NDK — VeraCrypt's cipher sources via JNI bridge |
| File system | FatFs (FAT32/exFAT inside containers) |
| Local storage | Room (container metadata), EncryptedSharedPreferences (PIN hashes) |
| DI | Hilt |
| Media | ExoPlayer / Media3 |
| Network | None — `INTERNET` permission is not declared |

The app presents itself as a calculator. Entering the correct PIN navigates to the authenticated vault home. A panic PIN triggers `PanicManager`, which executes a background wipe before navigation completes, equalizing the response time between both paths.

For a deeper dive, see the [architecture section in the docs](https://arcanum.zip/docs).

---

## Security

The codebase has been reviewed using AI-assisted security analysis across multiple passes. Reports are published in [`/audits`](audits/).

**Reporting a vulnerability:** Please use [GitHub Security Advisories](https://github.com/Esdex/Arcanum/security/advisories/new) to report security issues privately. Do not open a public issue for vulnerabilities.

---

## Contributing

Contributions are welcome for bug fixes and non-cryptographic improvements (UI, translations, documentation, gallery/file manager features). For changes touching the crypto layer, JNI bridge, PIN/panic logic, or any other security-critical path, please open an issue first to discuss the approach.

- Run `./gradlew lint` before submitting
- Native code changes must build cleanly for both `arm64-v8a` and `armeabi-v7a`
- The `fdroid` flavor must remain free of any Google Play Services dependency

---

## License

```
Copyright 2026 Esdex

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```

The cryptographic core (`app/src/main/cpp/veracrypt/`) incorporates source code from [VeraCrypt](https://veracrypt.fr), also licensed under Apache 2.0.

---

## Acknowledgments

- **[VeraCrypt](https://veracrypt.fr)** — AES, Serpent, Twofish, Camellia, Kuznyechik, SHA-2, Whirlpool, Streebog, BLAKE2s, XTS mode implementation
- **[FatFs](http://elm-chan.org/fsw/ff/)** — FAT32/exFAT file system layer for in-container access
- **[ExoPlayer / Media3](https://developer.android.com/media/media3)** — media playback inside encrypted containers
- **[Haze](https://github.com/chrisbanes/haze)** — frosted-glass UI effects
- **[BouncyCastle](https://www.bouncycastle.org)** — Argon2id PIN key derivation
- **[AboutLibraries](https://github.com/mikepenz/AboutLibraries)** — open-source license screen
