package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Known-answer tests for the native crypto primitives, run against the real
 * `libarcanum-native.so` on a device or emulator.
 *
 * These exist rather than a host-side harness because the one real
 * cryptographic bug this project has had (issue #62) was ABI-specific:
 * VeraCrypt's 64-bit XTS path miscompiled on armeabi-v7a under -O2 via
 * strict-aliasing UB and was correct everywhere else. A test compiled with the
 * host compiler would have stayed green throughout. Running the vectors
 * against the shipped library, built with the real per-ABI flags, is what
 * catches that class of failure.
 *
 * Note the arm64 emulator cannot exercise the arm32 path at all — no
 * armeabi-v7a system image exists for API 29+. For that, run this suite on a
 * physical 32-bit device.
 *
 * The hooks these call live in `app/src/main/cpp/jni_kat.cpp`, which CMake
 * compiles only for debug builds. Against a release library the whole suite
 * skips rather than fails.
 *
 * ## Where the vectors come from
 *
 * Every expected value is either from the primitive's own specification or
 * recomputed with an independent implementation:
 *
 *  - AES-256, Serpent, Twofish and XTS use VeraCrypt's own vectors
 *    (`src/Common/Tests.c`), which are authoritative for the implementation
 *    Arcanum ports verbatim.
 *  - SHA-512, BLAKE2s-256, HMAC-SHA-512 and PBKDF2-HMAC-SHA-512 were
 *    recomputed with Python's `hashlib`/`hmac` rather than copied from a
 *    reference table.
 *  - Whirlpool is the published empty-string vector from the Whirlpool 3.0
 *    specification. It is the only value here not independently recomputed,
 *    as no second implementation was available.
 *
 * This matters: the placeholder versions of these tests carried expected
 * values in their comments, and three of them were wrong — the Twofish
 * "ciphertext" was actually the second half of VeraCrypt's key, the Serpent
 * vector was for a different input, and the 251-byte BLAKE2s value was the
 * keyed variant. Filling in the stubs on trust would have produced failures
 * that look like broken crypto. Do not add a vector here without checking it
 * against something other than this file's history.
 */
@RunWith(AndroidJUnit4::class)
class NativeKatTest {

    companion object {
        private var nativeAvailable = false
        private var katHooksAvailable = false

        /** Hash ids, matching HASH_TRAITS in app/src/main/cpp/kdf.cpp. */
        private const val SHA512 = 0
        private const val SHA256 = 1
        private const val WHIRLPOOL = 2
        private const val STREEBOG = 3
        private const val BLAKE2S = 4

        /** Cipher ids, matching CIPHER_* in veracrypt/Common/Crypto.h. */
        private const val CIPHER_AES = 0

        @BeforeClass
        @JvmStatic
        fun loadNative() {
            nativeAvailable = try {
                System.loadLibrary("arcanum-native")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
            // The KAT hooks are compiled into debug builds only, so probe for
            // them rather than assuming the library exports them.
            katHooksAvailable = nativeAvailable && try {
                NativeKatBridge.nativeKatHash(SHA512, ByteArray(0)) != null
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filterNot { it.isWhitespace() }
            require(clean.length % 2 == 0)
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }

    private fun requireHooks() {
        assumeTrue("libarcanum-native.so not available — skipping native KAT", nativeAvailable)
        assumeTrue(
            "KAT hooks absent — this is a release build, they are compiled into debug only",
            katHooksAvailable
        )
    }

    /** Compares as hex so a failure shows the actual bytes, not "arrays differed". */
    private fun assertHex(label: String, expectedHex: String, actual: ByteArray?) {
        assertNotNull("$label: native call returned null", actual)
        assertEquals(label, hexToBytes(expectedHex).hex(), actual!!.hex())
    }

    // ── AES-256 ──────────────────────────────────────────────────────────────

    /**
     * NIST FIPS 197 Appendix C.3, and VeraCrypt's own `aes_ecb_vectors[0]`.
     */
    @Test
    fun aes256_fips197_ecbVector() {
        requireHooks()
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val pt = hexToBytes("00112233445566778899aabbccddeeff")
        assertHex("AES-256 ECB", "8ea2b7ca516745bfeafc49904b496089",
            NativeKatBridge.nativeKatAesEncryptBlock(key, pt))
    }

    // ── Serpent-256 ──────────────────────────────────────────────────────────

    /**
     * VeraCrypt `serpent_vectors[0]` (src/Common/Tests.c). Serpent has several
     * incompatible byte-order conventions in circulation; this is the one that
     * matches the implementation Arcanum ports.
     */
    @Test
    fun serpent256_veracryptVector() {
        requireHooks()
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val pt = hexToBytes("000102030405060708090a0b0c0d0e0f")
        assertHex("Serpent-256 ECB", "de269ff833e432b85b2e88d2701ce75c",
            NativeKatBridge.nativeKatSerpentEncryptBlock(key, pt))
    }

    // ── Twofish-256 ──────────────────────────────────────────────────────────

    /** VeraCrypt `twofish_vectors[0]` (src/Common/Tests.c). */
    @Test
    fun twofish256_veracryptVector() {
        requireHooks()
        val key = hexToBytes("d43bb7556ea32e46f2a282b7d45b4e0d57ff739d4dc92c1bd7fc01700cc8216f")
        val pt = hexToBytes("90afe91bb288544f2c32dc239b2635e6")
        assertHex("Twofish-256 ECB", "6cb4561c40bf0a9705931cb6d408e7fa",
            NativeKatBridge.nativeKatTwofishEncryptBlock(key, pt))
    }

    // ── XTS-AES-256 ──────────────────────────────────────────────────────────

    /**
     * IEEE 1619 vector 10, as shipped in VeraCrypt's `XTS_vectors[0]`, run
     * through the production `xts_crypt_temp()` — the same call the header and
     * sector paths make.
     *
     * This is the regression surface for issue #62. A full 512-byte data unit
     * is used deliberately: the arm32 miscompile only showed up across the
     * 64-bit whitening loop, so a single-block check would have missed it.
     */
    @Test
    fun xtsAes256_ieee1619Vector10_encrypt() {
        requireHooks()
        val key64 = hexToBytes(
            "2718281828459045235360287471352662497757247093699959574966967627" +
            "3141592653589793238462643383279502884197169399375105820974944592"
        )
        val plaintext = ByteArray(512) { (it % 256).toByte() }
        val actual = NativeKatBridge.nativeKatXtsCrypt(CIPHER_AES, key64, plaintext, 0xffL, true)
        assertHex("XTS-AES-256 encrypt", IEEE1619_VECTOR10_CIPHERTEXT, actual)
    }

    /** The same vector backwards — decrypt must return the original plaintext. */
    @Test
    fun xtsAes256_ieee1619Vector10_decryptRoundTrip() {
        requireHooks()
        val key64 = hexToBytes(
            "2718281828459045235360287471352662497757247093699959574966967627" +
            "3141592653589793238462643383279502884197169399375105820974944592"
        )
        val ciphertext = hexToBytes(IEEE1619_VECTOR10_CIPHERTEXT)
        val actual = NativeKatBridge.nativeKatXtsCrypt(CIPHER_AES, key64, ciphertext, 0xffL, false)
        assertHex("XTS-AES-256 decrypt", ByteArray(512) { (it % 256).toByte() }.hex(), actual)
    }

    // ── Hashes ───────────────────────────────────────────────────────────────

    /** NIST FIPS 180-4 — SHA-512 of "abc". Recomputed with Python hashlib. */
    @Test
    fun sha512_abc() {
        requireHooks()
        assertHex("SHA-512",
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            NativeKatBridge.nativeKatHash(SHA512, "abc".toByteArray()))
    }

    /** RFC 7693 — BLAKE2s-256 of the empty input. Recomputed with Python hashlib. */
    @Test
    fun blake2s256_emptyInput() {
        requireHooks()
        assertHex("BLAKE2s-256 empty",
            "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            NativeKatBridge.nativeKatHash(BLAKE2S, ByteArray(0)))
    }

    /**
     * BLAKE2s-256 over 0x00..0xfa, unkeyed. Recomputed with Python hashlib —
     * the value this test previously documented was the KEYED variant from
     * blake2s-kat.txt and does not apply here.
     */
    @Test
    fun blake2s256_251ByteInput() {
        requireHooks()
        assertHex("BLAKE2s-256 251 bytes",
            "53e7b27ea59c2f6dbb50769e43554df35af89f4822d0466b007dd6f6deafff02",
            NativeKatBridge.nativeKatHash(BLAKE2S, ByteArray(251) { it.toByte() }))
    }

    /**
     * Whirlpool 3.0 specification — hash of the empty string.
     *
     * The one vector here not independently recomputed: no second Whirlpool
     * implementation was available (Python's hashlib does not ship it). Treat a
     * failure as "verify the vector first", not immediately as a code bug.
     */
    @Test
    fun whirlpool_emptyInput() {
        requireHooks()
        assertHex("Whirlpool empty",
            "19fa61d75522a4669b44e39c1d2e1726c530232130d407f89afee0964997f7a7" +
            "3e83be698b288febcf88e3e03c4f0757ea8964e59b63d93708b138cc42a66eb3",
            NativeKatBridge.nativeKatHash(WHIRLPOOL, ByteArray(0)))
    }

    /** Sanity: the remaining PRFs must at least produce their declared digest sizes. */
    @Test
    fun hashes_produceDeclaredDigestSizes() {
        requireHooks()
        assertEquals("SHA-512 size", 64, NativeKatBridge.nativeKatHash(SHA512, ByteArray(0))!!.size)
        assertEquals("SHA-256 size", 32, NativeKatBridge.nativeKatHash(SHA256, ByteArray(0))!!.size)
        assertEquals("Whirlpool size", 64, NativeKatBridge.nativeKatHash(WHIRLPOOL, ByteArray(0))!!.size)
        assertEquals("Streebog size", 64, NativeKatBridge.nativeKatHash(STREEBOG, ByteArray(0))!!.size)
        assertEquals("BLAKE2s size", 32, NativeKatBridge.nativeKatHash(BLAKE2S, ByteArray(0))!!.size)
    }

    // ── HMAC and PBKDF2 (the PRF chain behind every mount) ────────────────────

    /**
     * RFC 4231 test case 1. Goes through the same `hmac_generic()` that
     * `pbkdf2_generic()` runs, via the debug-only bridge in kdf.cpp, so this
     * verifies the real function rather than a copy of it.
     */
    @Test
    fun hmacSha512_rfc4231_case1() {
        requireHooks()
        val key = ByteArray(20) { 0x0b }
        assertHex("HMAC-SHA-512",
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
            "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            NativeKatBridge.nativeKatHmac(SHA512, key, "Hi There".toByteArray()))
    }

    /**
     * PBKDF2-HMAC-SHA-512, 1000 iterations — the production `pbkdf2_generic()`
     * with the production hash traits. Recomputed with Python hashlib.
     *
     * The iteration count is deliberately low: this checks correctness of the
     * construction, not the 500,000-iteration cost the app actually uses, which
     * would make the suite needlessly slow.
     */
    @Test
    fun pbkdf2HmacSha512_referenceVector() {
        requireHooks()
        assertHex("PBKDF2-HMAC-SHA-512",
            "afe6c5530785b6cc6b1c6453384731bd5ee432ee549fd42fb6695779ad8a1c5b" +
            "f59de69c48f774efc4007d5298f9033c0241d5ab69305e7b64eceeb8d834cfec",
            NativeKatBridge.nativeKatPbkdf2(
                SHA512, "password".toByteArray(), "salt".toByteArray(), 1000, 64))
    }
}

/**
 * IEEE 1619 vector 10 ciphertext for XTS-AES-256, extracted from VeraCrypt's
 * `XTS_vectors[0]` (src/Common/Tests.c). Plaintext is 0x00..0xff twice.
 */
private const val IEEE1619_VECTOR10_CIPHERTEXT =
    "1c3b3a102f770386e4836c99e370cf9bea00803f5e482357a4ae12d414a3e63b" +
    "5d31e276f8fe4a8d66b317f9ac683f44680a86ac35adfc3345befecb4bb188fd" +
    "5776926c49a3095eb108fd1098baec70aaa66999a72a82f27d848b21d4a741b0" +
    "c5cd4d5fff9dac89aeba122961d03a757123e9870f8acf1000020887891429ca" +
    "2a3e7a7d7df7b10355165c8b9a6d0a7de8b062c4500dc4cd120c0f7418dae3d0" +
    "b5781c34803fa75421c790dfe1de1834f280d7667b327f6c8cd7557e12ac3a0f" +
    "93ec05c52e0493ef31a12d3d9260f79a289d6a379bc70c50841473d1a8cc81ec" +
    "583e9645e07b8d9670655ba5bbcfecc6dc3966380ad8fecb17b6ba02469a020a" +
    "84e18e8f84252070c13e9f1f289be54fbc481457778f616015e1327a02b140f1" +
    "505eb309326d68378f8374595c849d84f4c333ec4423885143cb47bd71c5edae" +
    "9be69a2ffeceb1bec9de244fbe15992b11b77c040f12bd8f6a975a44a0f90c29" +
    "a9abc3d4d893927284c58754cce294529f8614dcd2aba991925fedc4ae74ffac" +
    "6e333b93eb4aff0479da9a410e4450e0dd7ae4c6e2910900575da401fc07059f" +
    "645e8b7e9bfdef33943054ff84011493c27b3429eaedb4ed5376441a77ed4385" +
    "1ad77f16f541dfd269d50d6a5f14fb0aab1cbb4c1550be97f7ab4066193c4caa" +
    "773dad38014bd2092fa755c824bb5e54c4f36ffda9fcea70b9c6e693e148c151"
