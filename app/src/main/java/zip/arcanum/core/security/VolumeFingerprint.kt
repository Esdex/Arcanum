package zip.arcanum.core.security

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What identifies a volume when its path does not (#63).
 *
 * The first 64 bytes of a VeraCrypt volume are the header's salt: random, unique to the
 * volume, and plaintext - readable without the password, because the password is what turns
 * them into a key rather than what hides them. Hashing them gives a name for the volume that
 * survives being moved, renamed or carried to another phone.
 *
 * This is the same fingerprint a vault on a USB drive has always been found by
 * (`UsbBlockDevice.volumeFingerprint`, same 64 bytes, same SHA-256). File-hosted vaults never
 * needed one while they had a path; a vault whose file has been lost, or whose list arrived
 * from another phone, has no path worth trusting, and this is what lets the app say "yes,
 * this is the volume you were looking for" before anybody types a password.
 *
 * **It changes when the header is rewritten** - a new password, new keyfiles, a restored
 * header - because VeraCrypt writes a fresh salt each time. Every operation that does that
 * has to record the new one, or the app will start disowning volumes it owns.
 */
@Singleton
class VolumeFingerprint @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** The salt is the first 64 bytes of the volume header, at offset 0. */
        const val SALT_BYTES = 64
    }

    /** The fingerprint of the volume behind this row, or null if it cannot be read. */
    suspend fun read(path: String, safUri: String): String? = withContext(Dispatchers.IO) {
        val salt = runCatching {
            when {
                safUri.isNotEmpty() -> context.contentResolver.openInputStream(Uri.parse(safUri))?.use {
                    val buffer = ByteArray(SALT_BYTES)
                    if (it.readNBytes(buffer, 0, SALT_BYTES) == SALT_BYTES) buffer else null
                }
                path.isNotEmpty() -> RandomAccessFile(path, "r").use {
                    val buffer = ByteArray(SALT_BYTES)
                    it.readFully(buffer)
                    buffer
                }
                else -> null
            }
        }.getOrNull() ?: return@withContext null
        MessageDigest.getInstance("SHA-256").digest(salt).joinToString("") { "%02x".format(it) }
    }

    /** The fingerprint of a file the user has just pointed at. */
    suspend fun readUri(uri: Uri): String? = read(path = "", safUri = uri.toString())
}
