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

# ── Main native library ───────────────────────────────────────────────
add_library(
    arcanum-native
    SHARED
    arcanum_jni.cpp
    ${VERACRYPT_SOURCES}
    ${FATFS_SOURCES}
)

# ── Include paths ─────────────────────────────────────────────────────
target_include_directories(arcanum-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}                # arcanum_impl.h
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt      # Common/Tcdefs.h etc.
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt/Crypto  # Aes.h, Sha2.h, config.h
    ${CMAKE_CURRENT_SOURCE_DIR}/veracrypt/Common  # Xts.h, Crypto.h, cpu.h, misc.h
    ${CMAKE_CURRENT_SOURCE_DIR}/fatfs          # ff.h, diskio.h, ffconf.h
)

# ── Compiler flags ────────────────────────────────────────────────────
target_compile_options(arcanum-native PRIVATE
    $<$<COMPILE_LANGUAGE:C>:-std=c11>
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>
    -O2
    -fexceptions -frtti
    # Silence warnings from VeraCrypt's older C code
    -Wno-implicit-function-declaration
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-macro-redefined
    -Wno-deprecated-declarations
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
# arcanum_jni.cpp retains UINT64_STRUCT::Value (guarded by the same #ifndef).
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
