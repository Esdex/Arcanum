# Arcanum

VeraCrypt-compatible encrypted vault manager for Android.

## Features

- Full VeraCrypt container compatibility (open containers created on desktop)
- All encryption algorithms: AES, Serpent, Twofish, Camellia, Kuznyechik and all cascade combinations
- All hash algorithms: SHA-512, SHA-256, Whirlpool, Streebog, BLAKE2s-256, RIPEMD-160
- Hidden volumes
- Keyfile support
- PIM support
- Panic mode (duress PIN wipes selected data)
- Calculator disguise (app presents as a plain calculator)
- Beautiful gallery and file manager inside encrypted containers
- AMOLED Glass UI with frosted-glass effects

## Building

**Requirements:**
- Android Studio Hedgehog or newer
- Android NDK r28+
- CMake 3.22.1+
- Min SDK 29 (Android 10)

```bash
# Debug build (F-Droid flavor — all features unlocked)
./gradlew assembleFdroidDebug

# Debug build (Play Store flavor)
./gradlew assemblePlaystoreDebug
```

## Product Flavors

| Flavor | Description |
|--------|-------------|
| `fdroid` | All features unlocked, no Play Billing |
| `playstore` | Premium features gated behind Play Billing |

## Architecture

The app presents itself as a plain calculator. Entering the correct PIN navigates to the encrypted vault home. A panic PIN triggers silent data wipe via `PanicManager`.

Crypto layer: custom JNI bridge (`arcanum_jni.cpp`) implementing the full VeraCrypt header authentication and XTS encryption pipeline using VeraCrypt's own C crypto sources. File system access inside containers uses FatFs.

## License

This project is licensed under the Apache License 2.0.

## Acknowledgments

- [VeraCrypt](https://veracrypt.fr) — cryptographic code (AES, Serpent, Twofish, Camellia, Kuznyechik, SHA-2, Whirlpool, Streebog, BLAKE2s, RIPEMD-160, XTS mode)
- [FatFs](http://elm-chan.org/fsw/ff/) — FAT32/exFAT file system layer
- [ExoPlayer / Media3](https://developer.android.com/media/media3) — media playback
- [Haze](https://github.com/chrisbanes/haze) — frosted glass UI effects
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — open-source license screen
