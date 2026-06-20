package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Known-Answer Tests (KAT) for the VeraCrypt native crypto layer.
 *
 * These tests require a compiled libarcanum.so and must run on a device or
 * emulator via `./gradlew connectedAndroidTest`.
 *
 * Each test verifies a specific algorithm against official test vectors:
 *  - AES-256    — NIST FIPS 197
 *  - Twofish    — Twofish specification
 *  - Serpent    — Serpent AES submission
 *  - BLAKE2s-256 — RFC 7693
 *  - SHA-512    — NIST FIPS 180-4
 *  - Whirlpool  — Whirlpool specification v3.0
 *  - HMAC-SHA-512 and HMAC-Whirlpool — VeraCrypt PBKDF2 PRFs
 *
 * All tests are skipped automatically if libarcanum.so is not yet available
 * (NATIVE_LIBRARY_MISSING). Fill in test bodies as each JNI function is
 * implemented in the C++ layer.
 */
@RunWith(AndroidJUnit4::class)
class NativeKatTest {

    companion object {
        private var nativeAvailable = false

        @BeforeClass
        @JvmStatic
        fun loadNative() {
            nativeAvailable = try {
                System.loadLibrary("arcanum-native")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    // ── AES-256 — NIST FIPS 197 ───────────────────────────────────────────────

    /**
     * NIST FIPS 197, Appendix B — AES-256 known-answer test.
     * Key:       000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
     * Plaintext: 00112233445566778899aabbccddeeff
     * Cipher:    8ea2b7ca516745bfeafc49904b496089
     *
     * TODO: Implement via a JNI function that exposes a raw single-block
     *       AES-256-ECB encrypt (for test purposes only, not exposed in prod).
     */
    @Test
    fun aes256_nistFips197_testVector() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeAes256EcbEncrypt(key, plaintext) once implemented
    }

    // ── Twofish-256 ──────────────────────────────────────────────────────────

    /**
     * Twofish specification, Section B.2 — 256-bit key test vector.
     * Key:       0000000000000000000000000000000000000000000000000000000000000000
     * Plaintext: 00000000000000000000000000000000
     * Cipher:    57ff739d4dc92c1bd7fc01700cc8216f
     *
     * TODO: Implement via a JNI function exposing raw Twofish-256-ECB encrypt.
     */
    @Test
    fun twofish256_specVector() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeTwofishEncrypt(key, plaintext) once implemented
    }

    // ── Serpent-256 ──────────────────────────────────────────────────────────

    /**
     * Serpent AES submission, set 1, vector 0 — 256-bit key.
     * Key:       0000000000000000000000000000000000000000000000000000000000000000
     * Plaintext: 00000000000000000000000000000000
     * Cipher:    3620b17ae6a993d09618b8768266bae9
     *
     * TODO: Implement via a JNI function exposing raw Serpent-256-ECB encrypt.
     */
    @Test
    fun serpent256_specVector() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeSerpentEncrypt(key, plaintext) once implemented
    }

    // ── BLAKE2s-256 — RFC 7693 ────────────────────────────────────────────────

    /**
     * RFC 7693, Appendix A — BLAKE2s test vector.
     * Input:    "" (empty)
     * Expected: 69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9
     *
     * TODO: Implement via a JNI function exposing BLAKE2s-256 hash.
     */
    @Test
    fun blake2s256_rfc7693_emptyInput() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeBlake2s256(input) once implemented
    }

    /**
     * RFC 7693, Appendix A — BLAKE2s test vector.
     * Input:    0x00 0x01 … 0xfa (251 bytes)
     * Expected: 1ee4e51ecab5210a518f26150e882627ec839967f19d763e1508b966d77d716a
     *
     * TODO: Implement via a JNI function exposing BLAKE2s-256 hash.
     */
    @Test
    fun blake2s256_rfc7693_251ByteInput() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeBlake2s256(ByteArray(251) { it.toByte() }) once implemented
    }

    // ── SHA-512 — NIST FIPS 180-4 ────────────────────────────────────────────

    /**
     * NIST FIPS 180-4 — SHA-512 of "abc".
     * Expected: ddaf35a193617aba cc417349ae204131
     *           12e6fa4e89a97ea2 0a9eeee64b55d39a
     *           2192992a274fc1a8 36ba3c23a3feebbd
     *           454d4423643ce80e 2a9ac94fa54ca49f
     *
     * TODO: Implement via a JNI function exposing SHA-512 hash.
     */
    @Test
    fun sha512_nistFips_abc() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeSha512("abc".toByteArray()) once implemented
    }

    // ── Whirlpool ────────────────────────────────────────────────────────────

    /**
     * Whirlpool v3.0 specification — hash of "" (empty string).
     * Expected: 19fa61d75522a4669b44e39c1d2e1726
     *           c530232130d407f89afee0964997f7a7
     *           3e83be698b288febcf88e3e03c4f0757
     *           ea8964e59b63d93708b138cc42a66eb3
     *
     * TODO: Implement via a JNI function exposing Whirlpool hash.
     */
    @Test
    fun whirlpool_specVector_emptyInput() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeWhirlpool(ByteArray(0)) once implemented
    }

    // ── HMAC-SHA-512 — VeraCrypt PBKDF2 PRF ──────────────────────────────────

    /**
     * RFC 4231, Test Case 1 — HMAC-SHA-512.
     * Key:      0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b (20 bytes)
     * Data:     "Hi There"
     * Expected: 87aa7cdea5ef619d4ff0b4241a1d6cb0
     *           2379f4e2ce4ec2787ad0b30545e17cde
     *           daa833b7d6b8a702038b274eaea3f4e4
     *           be9d914eeb61f1702e696c203a126854
     *
     * TODO: Implement via a JNI function exposing HMAC-SHA-512.
     */
    @Test
    fun hmacSha512_rfc4231_testCase1() {
        assumeTrue("libarcanum.so not available — skipping native KAT", nativeAvailable)
        // TODO: call nativeHmacSha512(key, data) once implemented
    }
}
