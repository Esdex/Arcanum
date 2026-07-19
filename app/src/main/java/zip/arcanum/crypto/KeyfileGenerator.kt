package zip.arcanum.crypto

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes keyfiles full of CSPRNG output into a user-picked SAF directory.
 *
 * Shared by the standalone generator wizard and the inline "generate new
 * keyfile" actions in the create / change-keyfile flows, so the SAF document
 * dance and the failure cleanup live in one place.
 */
@Singleton
class KeyfileGenerator @Inject constructor(
    private val engine: VeraCryptEngine,
    @ApplicationContext private val context: Context
) {

    data class Generated(val uri: Uri, val displayName: String)

    /**
     * Resolves the document URI of the directory behind an OpenDocumentTree
     * result. Returns null if [treeUri] is not a usable tree.
     */
    fun parentOf(treeUri: Uri): Uri? = try {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
    } catch (_: Exception) { null }

    /**
     * Creates [name] under [parentUri] and fills it with [sizeBytes] of random
     * data, optionally XOR-folding [entropy] into the stream.
     *
     * A name clash is resolved by the provider ("name (1)"), never by
     * overwriting, so an existing keyfile cannot be destroyed here. On any
     * failure the partially written document is deleted: leaving it behind
     * would let an unusable stub pass for a generated keyfile.
     */
    suspend fun generate(
        parentUri: Uri,
        name: String,
        sizeBytes: Int = VeraCryptEngine.KEYFILE_DEFAULT_SIZE,
        entropy: ByteArray = ByteArray(0)
    ): Result<Generated> = withContext(Dispatchers.IO) {
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, parentUri, "application/octet-stream", name
            )
        }
        val fileUri = created.getOrNull()
            ?: return@withContext Result.failure(
                created.exceptionOrNull() ?: IllegalStateException("Failed to create $name")
            )

        val written = runCatching {
            context.contentResolver.openFileDescriptor(fileUri, "w").use { pfd ->
                if (pfd == null) null
                else engine.generateKeyfileFd(pfd.fd, sizeBytes, entropy)
            }
        }
        val result = written.getOrNull()

        val error: Throwable? = when {
            result == null -> written.exceptionOrNull()
                ?: IllegalStateException("Failed to write $name")
            result is CryptoResult.Failure -> IllegalStateException(result.error.name)
            else -> null
        }

        if (error != null) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, fileUri) }
            return@withContext Result.failure(error)
        }

        Result.success(Generated(fileUri, displayNameOf(fileUri, name)))
    }

    /** One keyfile into a freshly picked tree - the inline "generate new keyfile" path. */
    suspend fun generateOne(
        treeUri: Uri,
        name: String,
        sizeBytes: Int = VeraCryptEngine.KEYFILE_DEFAULT_SIZE
    ): Result<Generated> {
        val parent = parentOf(treeUri)
            ?: return Result.failure(IllegalArgumentException("Not a writable folder"))
        return generate(parent, name, sizeBytes)
    }

    /** Providers may rename on create (appended extension, conflict suffix), so
     *  the name shown to the user is read back rather than assumed. */
    private fun displayNameOf(uri: Uri, fallback: String): String = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fallback
    } catch (_: Exception) { fallback }
}
