package zip.arcanum.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The settings backup file itself: an envelope around a payload, optionally encrypted (#63).
 *
 * Separate from [SettingsBackup] because this half is where a mistake is expensive and where
 * a test can actually reach: it takes a string and gives back bytes, with no preferences, no
 * database and no Android in the way. `java.util.Base64` rather than `android.util.Base64`
 * for the same reason.
 *
 * The envelope is deliberately thin - the format, the version that wrote it, when, and the
 * KDF's own parameters. Everything else is inside the encryption, so a file found on a disk
 * says only that it is an Arcanum backup, which its name says anyway.
 */
object BackupCodec {

    /** The format this build writes. A file claiming a newer one is refused, not guessed at. */
    const val FORMAT = 1
    const val FILE_EXTENSION = "arcbak"

    private const val KDF_ITERATIONS = 600_000
    private const val SALT_BYTES     = 16
    private const val NONCE_BYTES    = 12
    private const val TAG_BITS       = 128

    @Serializable
    data class Envelope(
        val format: Int,
        val app: Int,
        val createdAt: Long,
        val encrypted: Boolean,
        val iterations: Int? = null,
        val salt: String? = null,
        val nonce: String? = null,
        val data: String
    )

    sealed interface Opened {
        data class Payload(val json: String, val writtenBy: Int, val createdAt: Long) : Opened
        /** Wrong password, or a file whose contents do not match their own tag. */
        data object WrongPassword : Opened
        data class TooNew(val format: Int) : Opened
        data object Malformed : Opened
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payloadJson: String, appVersionCode: Int, password: CharArray?): ByteArray {
        val payload = payloadJson.toByteArray()
        val envelope = if (password == null || password.isEmpty()) {
            Envelope(FORMAT, appVersionCode, System.currentTimeMillis(), false, data = payload.b64())
        } else {
            val salt  = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, KDF_ITERATIONS),
                     GCMParameterSpec(TAG_BITS, nonce))
            }
            Envelope(
                format = FORMAT, app = appVersionCode, createdAt = System.currentTimeMillis(),
                encrypted = true, iterations = KDF_ITERATIONS,
                salt = salt.b64(), nonce = nonce.b64(), data = cipher.doFinal(payload).b64()
            )
        }
        return json.encodeToString(envelope).toByteArray()
    }

    /** Whether this file will ask for a password, so a screen can ask before it reads. */
    fun isEncrypted(bytes: ByteArray): Boolean? =
        runCatching { json.decodeFromString<Envelope>(String(bytes)).encrypted }.getOrNull()

    fun decode(bytes: ByteArray, password: CharArray?): Opened {
        val envelope = runCatching { json.decodeFromString<Envelope>(String(bytes)) }.getOrNull()
            ?: return Opened.Malformed
        if (envelope.format > FORMAT) return Opened.TooNew(envelope.format)

        val payload = if (!envelope.encrypted) {
            runCatching { envelope.data.unB64() }.getOrNull() ?: return Opened.Malformed
        } else {
            val salt  = runCatching { envelope.salt?.unB64() }.getOrNull() ?: return Opened.Malformed
            val nonce = runCatching { envelope.nonce?.unB64() }.getOrNull() ?: return Opened.Malformed
            if (password == null || password.isEmpty()) return Opened.WrongPassword
            /* A wrong password fails the GCM tag, and so does a file somebody has edited -
               which is why nothing here checks a password separately, and why a tampered
               backup cannot be restored half way. */
            runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE,
                         deriveKey(password, salt, envelope.iterations ?: KDF_ITERATIONS),
                         GCMParameterSpec(TAG_BITS, nonce))
                }.doFinal(envelope.data.unB64())
            }.getOrNull() ?: return Opened.WrongPassword
        }
        return Opened.Payload(String(payload), envelope.app, envelope.createdAt)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded,
            "AES"
        )

    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.unB64(): ByteArray = Base64.getDecoder().decode(this)
}
