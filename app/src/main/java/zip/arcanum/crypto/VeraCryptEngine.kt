package zip.arcanum.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VeraCryptEngine @Inject constructor() {

    // ── Progress callback interfaces ───────────────────────────────────

    interface CreationProgressListener {
        /** Called from a background thread during container creation. */
        fun onProgress(progressFraction: Float, speedMbps: Float, bytesWritten: Long)
    }

    interface MountProgressListener {
        /**
         * Called from a background thread for each cipher/PRF combination tried during
         * auto-detect mount. [attempt] is 1-based; [total] is the total number of
         * combinations the engine will try for the given parameters.
         */
        fun onTrying(cipher: String, prf: String, attempt: Int, total: Int)
    }

    // ── High-level suspend API ─────────────────────────────────────────

    suspend fun createContainer(
        path: String,
        sizeBytes: Long,
        password: String,
        algorithm: Int  = 0,
        hashAlgorithm: Int = 0,
        filesystem: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        keyfilePaths: List<String> = emptyList(),
        progressListener: CreationProgressListener? = null,
        pim: Int = 0
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        val rc = nativeCreateContainer(
            path, sizeBytes, password,
            keyfilePaths.toTypedArray().ifEmpty { null },
            algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
            progressListener, pim
        )
        rc.toResult()
    }

    suspend fun mountContainer(
        path: String,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        algorithm: Int = ALGO_AUTO,
        hashAlgorithm: Int = HASH_AUTO,
        protectHiddenPassword: String? = null,
        protectHiddenKeyfileData: List<ByteArray> = emptyList(),
        protectHiddenPim: Int = 0,
        mountProgressListener: MountProgressListener? = null
    ): CryptoResult<Long> = withContext(Dispatchers.IO) {
        val handle = nativeOpenContainer(
            path, password,
            keyfileData.toTypedArray().ifEmpty { null },
            pim, algorithm, hashAlgorithm,
            protectHiddenPassword,
            protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
            protectHiddenPim,
            mountProgressListener
        )
        if (handle >= 0) CryptoResult.Success(handle)
        else CryptoResult.Failure(handle.toInt().toError())
    }

    suspend fun createContainerFd(
        fd: Int,
        sizeBytes: Long,
        password: String,
        algorithm: Int  = 0,
        hashAlgorithm: Int = 0,
        filesystem: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        keyfilePaths: List<String> = emptyList(),
        progressListener: CreationProgressListener? = null,
        pim: Int = 0
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        val rc = nativeCreateContainerFd(
            fd, sizeBytes, password,
            keyfilePaths.toTypedArray().ifEmpty { null },
            algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
            progressListener, pim
        )
        rc.toResult()
    }

    suspend fun mountContainerFd(
        fd: Int,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        algorithm: Int = ALGO_AUTO,
        hashAlgorithm: Int = HASH_AUTO,
        protectHiddenPassword: String? = null,
        protectHiddenKeyfileData: List<ByteArray> = emptyList(),
        protectHiddenPim: Int = 0,
        mountProgressListener: MountProgressListener? = null
    ): CryptoResult<Long> = withContext(Dispatchers.IO) {
        val handle = nativeOpenContainerFd(
            fd, password,
            keyfileData.toTypedArray().ifEmpty { null },
            pim, algorithm, hashAlgorithm,
            protectHiddenPassword,
            protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
            protectHiddenPim,
            mountProgressListener
        )
        if (handle >= 0) CryptoResult.Success(handle)
        else CryptoResult.Failure(handle.toInt().toError())
    }

    suspend fun createHiddenVolumeFd(
        fd: Int,
        hiddenSizeBytes: Long,
        outerPassword: String,
        outerKeyfilePaths: List<String> = emptyList(),
        outerPim: Int = 0,
        hiddenPassword: String,
        hiddenKeyfilePaths: List<String> = emptyList(),
        hiddenPim: Int = 0,
        hiddenAlgorithm: Int = 0,
        hiddenHashAlgorithm: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        val rc = nativeCreateHiddenVolumeFd(
            fd, hiddenSizeBytes,
            outerPassword, outerKeyfilePaths.toTypedArray().ifEmpty { null }, outerPim,
            hiddenPassword, hiddenKeyfilePaths.toTypedArray().ifEmpty { null }, hiddenPim,
            hiddenAlgorithm, hiddenHashAlgorithm,
            quickFormat, entropyBytes, progressListener
        )
        rc.toResult()
    }

    suspend fun unmountContainer(handle: Long): CryptoResult<Unit> =
        withContext(Dispatchers.IO) {
            nativeCloseContainer(handle).toResult()
        }

    suspend fun createHiddenVolume(
        path: String,
        hiddenSizeBytes: Long,
        outerPassword: String,
        outerKeyfilePaths: List<String> = emptyList(),
        outerPim: Int = 0,
        hiddenPassword: String,
        hiddenKeyfilePaths: List<String> = emptyList(),
        hiddenPim: Int = 0,
        hiddenAlgorithm: Int = 0,
        hiddenHashAlgorithm: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        val rc = nativeCreateHiddenVolume(
            path, hiddenSizeBytes,
            outerPassword, outerKeyfilePaths.toTypedArray().ifEmpty { null }, outerPim,
            hiddenPassword, hiddenKeyfilePaths.toTypedArray().ifEmpty { null }, hiddenPim,
            hiddenAlgorithm, hiddenHashAlgorithm,
            quickFormat, entropyBytes, progressListener
        )
        rc.toResult()
    }

    suspend fun changePassword(
        path: String,
        oldPassword: String,
        oldKeyfilePaths: List<String> = emptyList(),
        oldPim: Int = 0,
        newPassword: String,
        newKeyfilePaths: List<String> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        newPim: Int = 0,
        wipePassCount: Int = 3
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangePassword(
            path, oldPassword,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
            newPassword,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
            wipePassCount
        ).toResult()
    }

    suspend fun changePasswordFd(
        fd: Int,
        oldPassword: String,
        oldKeyfilePaths: List<String> = emptyList(),
        oldPim: Int = 0,
        newPassword: String,
        newKeyfilePaths: List<String> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        newPim: Int = 0,
        wipePassCount: Int = 3
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangePasswordFd(
            fd, oldPassword,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
            newPassword,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
            wipePassCount
        ).toResult()
    }

    fun getVolumeType(handle: Long): Int = nativeGetVolumeType(handle)
    fun hasHiddenVolume(handle: Long): Boolean = nativeHasHiddenVolume(handle)

    // ── JNI external declarations ──────────────────────────────────────

    external fun nativeCreateContainer(
        path: String,
        sizeBytes: Long,
        password: String,
        keyfilePaths: Array<String>?,
        algorithm: Int,
        hashAlgorithm: Int,
        filesystem: Int,
        quickFormat: Boolean,
        entropyBytes: ByteArray,
        progressListener: CreationProgressListener?,
        pim: Int
    ): Int

    external fun nativeCreateContainerFd(
        fd: Int,
        sizeBytes: Long,
        password: String,
        keyfilePaths: Array<String>?,
        algorithm: Int,
        hashAlgorithm: Int,
        filesystem: Int,
        quickFormat: Boolean,
        entropyBytes: ByteArray,
        progressListener: CreationProgressListener?,
        pim: Int
    ): Int

    external fun nativeOpenContainer(
        path: String,
        password: String,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: String?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?
    ): Long

    external fun nativeOpenContainerFd(
        fd: Int,
        password: String,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: String?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?
    ): Long

    external fun nativeListFiles(
        handle: Long,
        dirPath: String
    ): Array<NativeFileInfo>

    external fun nativeReadFile(
        handle: Long,
        filePath: String,
        offset: Long,
        length: Int
    ): ByteArray?

    external fun nativeWriteFile(
        handle: Long,
        filePath: String,
        data: ByteArray,
        offset: Long
    ): Int

    external fun nativeDeleteFile(handle: Long, filePath: String): Int

    external fun nativeDeleteDirectory(handle: Long, dirPath: String): Int

    external fun nativeCreateDirectory(handle: Long, dirPath: String): Int

    external fun nativeRenameFile(handle: Long, oldPath: String, newPath: String): Int

    external fun nativeCloseContainer(handle: Long): Int

    external fun nativeCreateHiddenVolume(
        path: String,
        hiddenSizeBytes: Long,
        outerPassword: String,
        outerKeyfilePaths: Array<String>?,
        outerPim: Int,
        hiddenPassword: String,
        hiddenKeyfilePaths: Array<String>?,
        hiddenPim: Int,
        hiddenAlgorithm: Int,
        hiddenHashAlgorithm: Int,
        quickFormat: Boolean,
        entropyBytes: ByteArray,
        progressListener: CreationProgressListener?
    ): Int

    external fun nativeCreateHiddenVolumeFd(
        fd: Int,
        hiddenSizeBytes: Long,
        outerPassword: String,
        outerKeyfilePaths: Array<String>?,
        outerPim: Int,
        hiddenPassword: String,
        hiddenKeyfilePaths: Array<String>?,
        hiddenPim: Int,
        hiddenAlgorithm: Int,
        hiddenHashAlgorithm: Int,
        quickFormat: Boolean,
        entropyBytes: ByteArray,
        progressListener: CreationProgressListener?
    ): Int

    external fun nativeChangePassword(
        path: String,
        oldPassword: String,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: String,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int
    ): Int

    external fun nativeChangePasswordFd(
        fd: Int,
        oldPassword: String,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: String,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int
    ): Int

    external fun nativeGetVolumeType(handle: Long): Int

    external fun nativeHasHiddenVolume(handle: Long): Boolean

    external fun nativeGetAlgorithmId(handle: Long): Int

    external fun nativeGetHashId(handle: Long): Int

    external fun nativeGetFilesystem(handle: Long): Int

    external fun nativeGetDataSize(handle: Long): Long

    // ── Companion ──────────────────────────────────────────────────────
    companion object {
        const val ALGO_AUTO = -1
        const val HASH_AUTO = -1

        const val ERR_OK               = 0
        const val ERR_FILE             = -1
        const val ERR_READ             = -2
        const val ERR_WRONG_PASSWORD   = -3
        const val ERR_UNSUPPORTED      = -4
        const val ERR_NO_SPACE         = -5
        const val ERR_NO_SLOT          = -6
        const val ERR_FS               = -7
        const val ERR_RAND             = -8
        const val ERR_HIDDEN_BOUNDARY  = -9

        fun filesystemIdToString(fsType: Int): String = when (fsType) {
            1 -> "FAT12"
            2 -> "FAT16"
            3 -> "FAT32"
            4 -> "exFAT"
            else -> "—"
        }

        const val HASH_BLAKE2S = 4

        fun hashIdToString(hashId: Int): String = when (hashId) {
            0 -> "SHA-512"
            1 -> "SHA-256"
            2 -> "Whirlpool"
            3 -> "Streebog"
            4 -> "BLAKE2s-256"
            else -> "SHA-512"
        }

        fun algorithmIdToString(algId: Int): String = when (algId) {
            0  -> "AES-256-XTS"
            1  -> "Serpent-256-XTS"
            2  -> "Twofish-256-XTS"
            3  -> "Camellia-256-XTS"
            4  -> "Kuznyechik-256-XTS"
            5  -> "AES-Twofish"
            6  -> "AES-Twofish-Serpent"
            7  -> "Serpent-AES"
            8  -> "Serpent-Twofish-AES"
            9  -> "Twofish-Serpent"
            10 -> "Camellia-Kuznyechik"
            11 -> "Camellia-Serpent"
            12 -> "Kuznyechik-AES"
            13 -> "Kuznyechik-Serpent-Camellia"
            14 -> "Kuznyechik-Twofish"
            else -> "AES-256-XTS"
        }

        init {
            try {
                System.loadLibrary("arcanum-native")
            } catch (_: UnsatisfiedLinkError) {
                // Native library not yet compiled — stub mode active
            }
        }
    }
}

// ── Result mapping helpers ─────────────────────────────────────────────

private fun Int.toError(): CryptoError = when (this) {
    VeraCryptEngine.ERR_WRONG_PASSWORD -> CryptoError.WRONG_PASSWORD
    VeraCryptEngine.ERR_FILE,
    VeraCryptEngine.ERR_READ           -> CryptoError.IO_ERROR
    VeraCryptEngine.ERR_RAND           -> CryptoError.RNG_FAILURE
    VeraCryptEngine.ERR_UNSUPPORTED    -> CryptoError.UNSUPPORTED_ALGORITHM
    else                               -> CryptoError.UNKNOWN
}

private fun Int.toResult(): CryptoResult<Unit> = when (this) {
    VeraCryptEngine.ERR_OK -> CryptoResult.Success(Unit)
    else                   -> CryptoResult.Failure(this.toError())
}
