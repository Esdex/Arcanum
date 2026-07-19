package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import java.security.SecureRandom
import javax.inject.Inject

private const val ENTROPY_REQUIRED = 500

/** Upper bound on files per run. VeraCrypt's dialog allows 9,999,999, which is
 *  meaningless on a phone with a SAF round-trip per file. */
private const val MAX_KEYFILE_COUNT = 10

data class GenerateKeyfileState(
    val currentStep: Int = 1,
    val totalSteps: Int = 3,
    // Step 1 - parameters
    val baseName: String = "keyfile",
    val count: Int = 1,
    val sizeBytes: Int = VeraCryptEngine.KEYFILE_DEFAULT_SIZE,
    val randomSize: Boolean = false,
    // Step 2 - entropy
    val entropyProgress: Float = 0f,
    // Step 3 - result
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    /** Display names actually assigned by the storage provider, which may
     *  differ from what was requested (extension appended, conflict renamed). */
    val generatedNames: List<String> = emptyList()
) {
    val baseNameValid: Boolean
        get() = baseName.isNotBlank() && baseName.none { it in "/\\:*?\"<>|" }

    val sizeValid: Boolean
        get() = randomSize ||
            sizeBytes in VeraCryptEngine.KEYFILE_MIN_SIZE..VeraCryptEngine.KEYFILE_MAX_SIZE
}

@HiltViewModel
class GenerateKeyfileViewModel @Inject constructor(
    private val engine: VeraCryptEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(GenerateKeyfileState())
    val state = _state.asStateFlow()

    private val collectedEntropy: ByteArray = ByteArray(ENTROPY_REQUIRED * 2)
    private var entropyIndex: Int = 0

    fun update(block: GenerateKeyfileState.() -> GenerateKeyfileState) =
        _state.update { it.block() }

    fun setCount(value: Int) =
        _state.update { it.copy(count = value.coerceIn(1, MAX_KEYFILE_COUNT)) }

    /** Stores the raw value rather than clamping it: silently rewriting what the
     *  user typed hides the range instead of teaching it. [GenerateKeyfileState.sizeValid]
     *  gates the Next button and the field shows the bounds. */
    fun setSize(value: Int) = _state.update { it.copy(sizeBytes = value) }

    fun addEntropyPoint(x: Int, y: Int) {
        if (entropyIndex >= ENTROPY_REQUIRED * 2) return
        collectedEntropy[entropyIndex++] = x.toByte()
        collectedEntropy[entropyIndex++] = y.toByte()
        val progress = (entropyIndex / 2f / ENTROPY_REQUIRED).coerceAtMost(1f)
        _state.update { it.copy(entropyProgress = progress) }
    }

    /**
     * Generates every requested keyfile into [treeUri], a directory the user
     * picked via OpenDocumentTree.
     *
     * Random size mirrors VeraCrypt's generator: a fresh draw per file across
     * the full 64 B..1 MB range, so the sizes themselves leak nothing about
     * each other.
     */
    fun generate(treeUri: Uri) {
        if (_state.value.isRunning) return
        val s = _state.value
        _state.update {
            it.copy(isRunning = true, error = null, generatedNames = emptyList(), currentStep = 3)
        }

        viewModelScope.launch {
            val entropy = collectedEntropy.copyOf(entropyIndex)
            val names = mutableListOf<String>()
            var failure: String? = null

            withContext(Dispatchers.IO) {
                val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                val rng = SecureRandom()

                for (i in 0 until s.count) {
                    val size = if (s.randomSize) {
                        rng.nextInt(
                            VeraCryptEngine.KEYFILE_MAX_SIZE - VeraCryptEngine.KEYFILE_MIN_SIZE + 1
                        ) + VeraCryptEngine.KEYFILE_MIN_SIZE
                    } else s.sizeBytes

                    val name = fileNameFor(s.baseName, i)

                    // createDocument never overwrites: a name clash yields
                    // "name (1)", so an existing keyfile cannot be destroyed here.
                    val created = runCatching {
                        DocumentsContract.createDocument(
                            context.contentResolver, parentUri, "application/octet-stream", name
                        )
                    }
                    val fileUri = created.getOrNull()
                    if (fileUri == null) {
                        failure = created.exceptionOrNull()?.message ?: "Failed to create $name"
                        break
                    }

                    // Null result means either the provider refused the fd or the
                    // write threw; both are reported with whatever detail exists.
                    val written = runCatching {
                        context.contentResolver.openFileDescriptor(fileUri, "w").use { pfd ->
                            if (pfd == null) null
                            else engine.generateKeyfileFd(pfd.fd, size, entropy)
                        }
                    }
                    val result = written.getOrNull()

                    if (result == null) {
                        failure = written.exceptionOrNull()?.message ?: "Failed to write $name"
                    } else if (result is CryptoResult.Failure) {
                        failure = result.error.name
                    }

                    if (failure != null) {
                        // The document exists but holds no usable keyfile; leaving
                        // it behind would look like a successful generation.
                        runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, fileUri)
                        }
                        break
                    }

                    names += resolveDisplayName(fileUri, name)
                }
            }

            entropy.fill(0)
            _state.update {
                it.copy(
                    isRunning = false,
                    isSuccess = failure == null,
                    error = failure,
                    generatedNames = names
                )
            }
        }
    }

    /** Matches VeraCrypt's KeyfileGeneratorDialog naming: the first file keeps
     *  the base name, later ones get a "_N" suffix ahead of the extension. */
    private fun fileNameFor(baseName: String, index: Int): String {
        if (index == 0) return baseName
        val dot = baseName.lastIndexOf('.')
        return if (dot > 0) {
            baseName.substring(0, dot) + "_" + index + baseName.substring(dot)
        } else {
            baseName + "_" + index
        }
    }

    /** Providers may rename on create (appended extension, conflict suffix), so
     *  the name shown to the user is read back rather than assumed. */
    private fun resolveDisplayName(uri: Uri, fallback: String): String = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fallback
    } catch (_: Exception) { fallback }

    fun nextStep() = _state.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(it.totalSteps)) }
    fun prevStep() = _state.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(1)) }

    override fun onCleared() {
        super.onCleared()
        collectedEntropy.fill(0)
    }
}
