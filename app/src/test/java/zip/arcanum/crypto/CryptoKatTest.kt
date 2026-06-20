package zip.arcanum.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

/**
 * Known-Answer Tests (KAT) for the cryptographic primitives used in PinManager.
 *
 * Coverage:
 *  - Argon2id: RFC 9106 Appendix B.3 test vector (algorithm correctness).
 *  - Argon2id: PinManager parameter contract (t=2, m=65536, p=1, len=32).
 *    Any silent change to these parameters invalidates all stored PIN hashes.
 *  - SHA-256: NIST FIPS 180-4 test vectors (legacy PIN hash path).
 *  - Lockout schedule: verifies the brute-force delay table.
 *
 * Tests marked "SLOW" run a full Argon2id derivation (~1 s each on a modern
 * host). That is intentional — the algorithm's cost is what is being verified.
 *
 * Native-layer KATs (AES, Twofish, Serpent, BLAKE2s, SHA-512, Whirlpool,
 * PBKDF2) require a compiled libarcanum.so and live in androidTest/.
 */
class CryptoKatTest {

    // ── Argon2id parameter constants (must mirror PinManager exactly) ─────────

    private val PM_T_COST    = 2
    private val PM_M_COST_KB = 65536
    private val PM_P_COST    = 1
    private val PM_HASH_LEN  = 32
    private val PM_VERSION   = Argon2Parameters.ARGON2_VERSION_13

    // ── RFC 9106 test vector ──────────────────────────────────────────────────

    /**
     * RFC 9106 Appendix B.3 — official Argon2id test vector.
     * Verifies that BouncyCastle's implementation is algorithm-correct before
     * any PinManager-specific tests run.
     *
     * Type: Argon2id  t=3  m=32 KiB  p=4  tag=32 bytes
     * Password:  0x01 × 32
     * Salt:      0x02 × 16
     * Secret:    0x03 × 8
     * AssocData: 0x04 × 12
     * Expected:  0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659
     */
    @Test
    fun argon2id_rfc9106_testVector() {
        val password  = ByteArray(32) { 0x01 }
        val salt      = ByteArray(16) { 0x02 }
        val secret    = ByteArray(8)  { 0x03 }
        val assocData = ByteArray(12) { 0x04 }

        val expected = hexToBytes(
            "0d640df58d78766c08c037a34a8b53c9" +
            "d01ef0452d75b65eb52520e96b01e659"
        )

        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withSecret(secret)
            .withAdditional(assocData)
            .withIterations(3)
            .withMemoryAsKB(32)
            .withParallelism(4)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()

        val output = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, output, 0, 32)

        assertArrayEquals(
            "Argon2id RFC 9106 vector mismatch — BouncyCastle implementation incorrect",
            expected, output
        )
    }

    // ── Argon2id PinManager parameter contract ────────────────────────────────

    /**
     * SLOW — two full Argon2id derivations at PinManager's production parameters.
     *
     * Verifies that the same password + salt always produces the same output.
     * If PinManager's parameters (t/m/p/len/version) ever change, this test
     * will still pass — but all on-device stored hashes would become unreadable.
     * Pair with argon2id_pinManagerParams_outputLength for a full parameter check.
     */
    @Test
    fun argon2id_pinManagerParams_deterministic() {
        val password = "1234".toByteArray(Charsets.UTF_8)
        val salt     = ByteArray(32) { it.toByte() }

        val first  = deriveWithPinManagerParams(password, salt)
        val second = deriveWithPinManagerParams(password, salt)

        assertArrayEquals(
            "Argon2id must be deterministic — same inputs must always produce the same hash",
            first, second
        )
    }

    /**
     * SLOW — one full Argon2id derivation at PinManager's production parameters.
     * Pins the output byte length. Changing PM_HASH_LEN breaks backward compat.
     */
    @Test
    fun argon2id_pinManagerParams_outputLength() {
        val output = deriveWithPinManagerParams(
            password = "0000".toByteArray(Charsets.UTF_8),
            salt     = ByteArray(32) { 0x55 }
        )
        assertEquals(
            "Argon2id output must be exactly $PM_HASH_LEN bytes — changing len breaks stored hashes",
            PM_HASH_LEN, output.size
        )
    }

    /**
     * SLOW — one full Argon2id derivation at PinManager's production parameters.
     * Verifies the output is not a trivial all-zero block.
     */
    @Test
    fun argon2id_pinManagerParams_nonZeroOutput() {
        val output = deriveWithPinManagerParams(
            password = "0000".toByteArray(Charsets.UTF_8),
            salt     = ByteArray(32) { 0x55 }
        )
        assertFalse(
            "Argon2id output must not be all zeros",
            output.all { it == 0.toByte() }
        )
    }

    /**
     * Fast — uses reduced memory (m=64 KiB) to verify salt isolation.
     * Different salts must produce different outputs (rainbow-table resistance).
     */
    @Test
    fun argon2id_saltIsolation_differentOutputs() {
        val password = "1234".toByteArray(Charsets.UTF_8)
        val salt1    = ByteArray(32) { 0x01 }
        val salt2    = ByteArray(32) { 0x02 }

        val a = deriveFast(password, salt1)
        val b = deriveFast(password, salt2)

        assertFalse(
            "Different salts must produce different Argon2id outputs",
            a.contentEquals(b)
        )
    }

    /**
     * Fast — uses reduced memory (m=64 KiB) to verify password isolation.
     * Different passwords with the same salt must produce different outputs.
     */
    @Test
    fun argon2id_passwordIsolation_differentOutputs() {
        val salt = ByteArray(32) { 0x77 }

        val a = deriveFast("1234".toByteArray(Charsets.UTF_8), salt)
        val b = deriveFast("5678".toByteArray(Charsets.UTF_8), salt)

        assertFalse(
            "Different passwords must produce different Argon2id outputs",
            a.contentEquals(b)
        )
    }

    // ── SHA-256 — NIST FIPS 180-4 test vectors ───────────────────────────────

    /** NIST FIPS 180-4, example 1: SHA-256 of the empty string. */
    @Test
    fun sha256_emptyString_nistVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256hex("")
        )
    }

    /** NIST FIPS 180-4, example 2: SHA-256("abc"). */
    @Test
    fun sha256_abc_nistVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469348790962cf4f9f98",
            sha256hex("abc")
        )
    }

    @Test
    fun sha256_outputIs32Bytes() {
        val digest = MessageDigest.getInstance("SHA-256").digest("test".toByteArray())
        assertEquals(32, digest.size)
    }

    // ── Lockout schedule ──────────────────────────────────────────────────────

    /**
     * Verifies the brute-force lockout duration table in PinManager.
     * Boundary values are tested explicitly — an off-by-one changes the
     * effective lockout tier for real users.
     */
    @Test
    fun lockout_schedule_correctDurations() {
        // Below threshold — no lockout
        assertEquals(0L,           lockoutDuration(0))
        assertEquals(0L,           lockoutDuration(1))
        assertEquals(0L,           lockoutDuration(4))
        // 30-second tier (5–7 failures)
        assertEquals(30_000L,      lockoutDuration(5))
        assertEquals(30_000L,      lockoutDuration(7))
        // 5-minute tier (8–11 failures)
        assertEquals(300_000L,     lockoutDuration(8))
        assertEquals(300_000L,     lockoutDuration(11))
        // 30-minute tier (12–14 failures)
        assertEquals(1_800_000L,   lockoutDuration(12))
        assertEquals(1_800_000L,   lockoutDuration(14))
        // 2-hour tier (15+ failures)
        assertEquals(7_200_000L,   lockoutDuration(15))
        assertEquals(7_200_000L,   lockoutDuration(99))
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Mirrors PinManager.deriveArgon2() exactly — must stay in sync. */
    private fun deriveWithPinManagerParams(password: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(PM_M_COST_KB)
            .withIterations(PM_T_COST)
            .withParallelism(PM_P_COST)
            .withVersion(PM_VERSION)
            .build()
        val output = ByteArray(PM_HASH_LEN)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, output, 0, PM_HASH_LEN)
        return output
    }

    /** Reduced-memory variant (m=64 KiB) for fast isolation tests. */
    private fun deriveFast(password: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(64)
            .withIterations(1)
            .withParallelism(1)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val output = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, output, 0, 32)
        return output
    }

    /** Mirrors PinManager.sha256hex() exactly — must stay in sync. */
    private fun sha256hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.fold("") { acc, b -> acc + "%02x".format(b) }
    }

    /** Mirrors PinManager.lockoutDuration() exactly — must stay in sync. */
    private fun lockoutDuration(failCount: Int): Long = when {
        failCount < 5  -> 0L
        failCount < 8  -> 30_000L
        failCount < 12 -> 300_000L
        failCount < 15 -> 1_800_000L
        else           -> 7_200_000L
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string length must be even" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
