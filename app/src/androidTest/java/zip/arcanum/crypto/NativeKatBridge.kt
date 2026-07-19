package zip.arcanum.crypto

/**
 * Test-only bridge to the crypto primitives in `app/src/main/cpp/jni_kat.cpp`.
 *
 * Declared as a Kotlin `object` with plain `external fun`s, matching
 * [NativeCrashHandler] — that is the shape the native symbols are mangled for
 * (`Java_zip_arcanum_crypto_NativeKatBridge_<name>`, taking a `jobject`).
 * Putting these in a `companion object` instead would move the native method
 * onto the generated Companion class and break the binding at call time.
 *
 * The functions exist only in debug builds; against a release library the first
 * call throws [UnsatisfiedLinkError], which [NativeKatTest] probes for and turns
 * into a skip.
 *
 * Each returns null when the native side rejects its arguments (wrong key or
 * block length, unknown hash id), so a mistake in a test shows up as a null
 * rather than as a silently wrong comparison.
 */
object NativeKatBridge {

    external fun nativeKatAesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray?

    external fun nativeKatTwofishEncryptBlock(key: ByteArray, block: ByteArray): ByteArray?

    external fun nativeKatSerpentEncryptBlock(key: ByteArray, block: ByteArray): ByteArray?

    /** hashId matches HASH_TRAITS in kdf.cpp: 0=SHA-512, 1=SHA-256, 2=Whirlpool, 3=Streebog, 4=BLAKE2s-256. */
    external fun nativeKatHash(hashId: Int, data: ByteArray): ByteArray?

    /** Routes through the production `hmac_generic()` via the debug-only bridge in kdf.cpp. */
    external fun nativeKatHmac(hashId: Int, key: ByteArray, data: ByteArray): ByteArray?

    /** Calls the production `pbkdf2_generic()` with the production hash traits. */
    external fun nativeKatPbkdf2(
        hashId: Int,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray?

    /**
     * Calls the production `xts_crypt_temp()`. [type] is a CIPHER_* id from
     * veracrypt/Common/Crypto.h, [key64] is K1(32) || K2(32), and [buf] must be
     * a whole number of 16-byte blocks.
     */
    external fun nativeKatXtsCrypt(
        type: Int,
        key64: ByteArray,
        buf: ByteArray,
        sectorNumber: Long,
        encrypt: Boolean
    ): ByteArray?
}
