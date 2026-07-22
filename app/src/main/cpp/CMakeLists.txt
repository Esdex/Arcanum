cmake_minimum_required(VERSION 3.22.1)
project(arcanum-native LANGUAGES C CXX)

# ── VeraCrypt crypto sources ──────────────────────────────────────────
set(VERACRYPT_SOURCES
    veracrypt/Crypto/Aescrypt.c
    veracrypt/Crypto/Aeskey.c
    veracrypt/Crypto/Aestab.c
    veracrypt/Crypto/Sha2.c
    veracrypt/Crypto/Whirlpool.c
    veracrypt/Crypto/Streebog.c
    veracrypt/Crypto/Serpent.c
    veracrypt/Crypto/Twofish.c
    veracrypt/Crypto/Camellia.c
    veracrypt/Crypto/kuznyechik.c
    veracrypt/Crypto/cpu.c
    veracrypt/Crypto/blake2s.c
    veracrypt/Common/Crc.c
    veracrypt/Common/Xts.c
)

# ── FatFS sources ─────────────────────────────────────────────────────
set(FATFS_SOURCES
    fatfs/ff.c
    fatfs/ffunicode.c
    fatfs/ffsystem.c
    fatfs/diskio.cpp
)

# ── ext4 sources ──────────────────────────────────────────────────────
# Clean-room, from the published on-disk format (issue #7): no GPL ext4 source
# was consulted. The same files are mutation-tested on the host by the harness in
# tools/ext4/ - this is the one copy, and that harness compiles it directly, so
# what ships is what is tested. ext4_device.cpp is the container block backend,
# the counterpart of fatfs/diskio.cpp. The .c files are -Wall clean and take no
# suppressions, unlike the VeraCrypt sources below.
set(EXT4_SOURCES
    ext4/ext4_csum.c
    ext4/ext4_io.c
    ext4/ext4_extents.c
    ext4/ext4_alloc.c
    ext4/ext4_ialloc.c
    ext4/ext4_extwrite.c
    ext4/ext4_dir.c
    ext4/ext4_dirwrite.c
    ext4/ext4_create.c
    ext4/ext4_device.cpp
)

# ── Arcanum native module sources (split from the former monolithic
# arcanum_jni.cpp — see arcanum_internal.h for the cross-module contract) ──
set(ARCANUM_SOURCES
    crypto_core.cpp
    kdf.cpp
    vc_header.cpp
    jni_volume.cpp
    jni_files.cpp
    crash_handler.cpp
)

# ── Known-answer test hooks (debug builds only) ───────────────────────
# jni_kat.cpp exposes the crypto primitives to NativeKatTest
# (app/src/androidTest). Instrumented tests run against the debug build, so
# gating on CMAKE_BUILD_TYPE costs no coverage and keeps the release library's
# exported surface to exactly what the app itself calls. ARCANUM_KAT_HOOKS
# additionally enables the kat_hmac() bridge in kdf.cpp, which reaches the
# static hmac_generic() so the test verifies the real HMAC rather than a copy.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    list(APPEND ARCANUM_SOURCES jni_kat.cpp)
    add_compile_definitions(ARCANUM_KAT_HOOKS)
    message(STATUS "Arcanum: KAT hooks enabled (debug build)")
endif()

# ── Main native library ───────────────────────────────────────────────
add_library(
    arcanum-native
    SHARED
    ${ARCANUM_SOURCES}
    ${VERACRYPT_SOURCES}
    ${FATFS_SOURCES}
    ${EXT4_SOURCES}
)

# ── Include paths ─────────────────────────────────────────────────────
target_include_directories(arcanum-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}                # arcanum_impl.h
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt      # Common/Tcdefs.h etc.
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt/Crypto  # Aes.h, Sha2.h, config.h
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt/Common  # Xts.h, Crypto.h, cpu.h, misc.h
    ${CMAKE_CURRENT_SOURCE_DIR}/fatfs          # ff.h, diskio.h, ffconf.h
    ${CMAKE_CURRENT_SOURCE_DIR}/ext4           # ext4_io.h, ext4_device.h, ...
)

# ── Compiler flags ────────────────────────────────────────────────────
# -Wall applies project-wide, including to ${ARCANUM_SOURCES}/diskio.cpp (our
# code); the VeraCrypt-era warning suppressions below are scoped ONLY to
# ${VERACRYPT_SOURCES} (third-party, older C) via set_source_files_properties
# so real warnings in our own sources stay visible.
target_compile_options(arcanum-native PRIVATE
    $<$<COMPILE_LANGUAGE:C>:-std=c11>
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>
    -O2
    -Wall
    -fvisibility=hidden
    -fexceptions -frtti
)

# Silence warnings from VeraCrypt's older C code — scoped to those sources
# only (follows the same per-source-file pattern as the armeabi-v7a Xts.c
# case below), not applied globally.
set_source_files_properties(${VERACRYPT_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-Wno-implicit-function-declaration;-Wno-unused-variable;-Wno-sign-compare;-Wno-macro-redefined;-Wno-deprecated-declarations"
)

# crypto_core.cpp/jni_volume.cpp #include veracrypt/Common/Xts.h, whose header
# declares EncryptBufferXTS{Parallel,NonParallel}/DecryptBufferXTS{Parallel,
# NonParallel} as `static` but each of these TUs only calls the non-suffixed
# wrappers it actually needs — under -Wall that's a handful of harmless
# -Wunused-function warnings from third-party header content, not a real issue
# in our code. This was scoped to arcanum_jni.cpp alone before the module
# split; every .cpp that includes Xts.h directly needs the same suppression
# now that the header's static wrappers are pulled in per-TU (vc_header.cpp
# only calls xts_crypt_temp(), declared in arcanum_internal.h, and does not
# include Xts.h itself, so it doesn't need this). Scoped narrowly
# (unused-function only) so -Wall still surfaces genuine warnings in these
# files.
set_source_files_properties(
    crypto_core.cpp
    jni_volume.cpp
    PROPERTIES
    COMPILE_OPTIONS "-Wno-unused-function"
)

target_compile_definitions(arcanum-native PRIVATE
    CRYPTOPP_DISABLE_ASM=1
    _FILE_OFFSET_BITS=64
    SAFE_IO=1
)

# ── arm32: use 32-bit XTS variant to avoid broken 64-bit pointer arithmetic ──
# On armeabi-v7a, the 64-bit XTS path (unsigned __int64 *bufPtr) produces
# incorrect results even with __int64=long long, likely due to strict-aliasing
# UB under -O2.  TC_NO_COMPILER_INT64 switches Xts.c to the uint32_t-based
# EncryptDecryptBufferXTS32 which works correctly.  Applied per-source so
# crypto_core.cpp/vc_header.cpp/jni_volume.cpp retain UINT64_STRUCT::Value
# (guarded by the same #ifndef).
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set_source_files_properties(veracrypt/Common/Xts.c PROPERTIES
        COMPILE_DEFINITIONS "TC_NO_COMPILER_INT64"
    )
endif()

# ── Reproducible build: disable non-deterministic Build ID ───────────
target_link_options(arcanum-native PRIVATE "-Wl,--build-id=none")

# ── Link Android log library ──────────────────────────────────────────
find_library(log-lib log)
target_link_libraries(arcanum-native ${log-lib})
