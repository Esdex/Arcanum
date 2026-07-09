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
        mountProgressListener: MountProgressListener? = null,
        readOnly: Boolean = false
    ): CryptoResult<Long> = withContext(Dispatchers.IO) {
        val handle = nativeOpenContainer(
            path, password,
            keyfileData.toTypedArray().ifEmpty { null },
            pim, algorithm, hashAlgorithm,
            protectHiddenPassword,
            protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
            protectHiddenPim,
            mountProgressListener,
            readOnly
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
        mountProgressListener: MountProgressListener? = null,
        readOnly: Boolean = false
    ): CryptoResult<Long> = withContext(Dispatchers.IO) {
        val handle = nativeOpenContainerFd(
            fd, password,
            keyfileData.toTypedArray().ifEmpty { null },
            pim, algorithm, hashAlgorithm,
            protectHiddenPassword,
            protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
            protectHiddenPim,
            mountProgressListener,
            readOnly
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
        wipePassCount: Int = 3,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangePassword(
            path, oldPassword,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
            newPassword,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
            wipePassCount, extraEntropy
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
        wipePassCount: Int = 3,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangePasswordFd(
            fd, oldPassword,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
            newPassword,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
            wipePassCount, extraEntropy
        ).toResult()
    }

    suspend fun changeKeyfile(
        path: String,
        password: String,
        oldKeyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newKeyfilePaths: List<String> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangeKeyfile(
            path, password,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, pim,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm,
            extraEntropy
        ).toResult()
    }

    suspend fun changeKeyfileFd(
        fd: Int,
        password: String,
        oldKeyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newKeyfilePaths: List<String> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeChangeKeyfileFd(
            fd, password,
            oldKeyfilePaths.toTypedArray().ifEmpty { null }, pim,
            newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm,
            extraEntropy
        ).toResult()
    }

    suspend fun backupVolumeHeader(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        outputPath: String
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeBackupVolumeHeader(
            path, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            outputPath
        ).toResult()
    }

    suspend fun backupVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        outputFd: Int
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeBackupVolumeHeaderFd(
            volumeFd, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            outputFd
        ).toResult()
    }

    suspend fun restoreVolumeHeader(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupPath: String = ""
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeRestoreVolumeHeader(
            path, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            fromExternal, backupPath
        ).toResult()
    }

    suspend fun restoreVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupFd: Int = -1
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeRestoreVolumeHeaderFd(
            volumeFd, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            fromExternal, backupFd
        ).toResult()
    }

    suspend fun expandVolume(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newSizeBytes: Long,
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeExpandVolume(
            path, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            newSizeBytes, progressListener
        ).toResult()
    }

    suspend fun expandVolumeFd(
        fd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newSizeBytes: Long,
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeExpandVolumeFd(
            fd, password,
            keyfilePaths.toTypedArray().ifEmpty { null }, pim,
            newSizeBytes, progressListener
        ).toResult()
    }

    fun getVolumeType(handle: Long): Int = nativeGetVolumeType(handle)
    fun hasHiddenVolume(handle: Long): Boolean = nativeHasHiddenVolume(handle)

    // ── Thin non-suspend wrappers ────────────────────────────────────────
    // The `external fun native*` declarations below are private; callers outside
    // this file (gallery/files packages, MainActivity, etc.) go through these.
    // Non-suspend because every existing caller already runs on its own
    // background dispatcher (ViewModel coroutine scope, MediaDataSource thread, …)
    // and controlled its own threading before this wrapping was added.

    fun listFiles(handle: Long, dirPath: String): Array<NativeFileInfo> =
        nativeListFiles(handle, dirPath) ?: emptyArray()

    fun readFile(handle: Long, filePath: String, offset: Long, length: Int): ByteArray? =
        nativeReadFile(handle, filePath, offset, length)

    fun writeFile(handle: Long, filePath: String, data: ByteArray, offset: Long): Int =
        nativeWriteFile(handle, filePath, data, offset)

    fun deleteFile(handle: Long, filePath: String): Int =
        nativeDeleteFile(handle, filePath)

    fun deleteDirectory(handle: Long, dirPath: String): Int =
        nativeDeleteDirectory(handle, dirPath)

    fun createDirectory(handle: Long, dirPath: String): Int =
        nativeCreateDirectory(handle, dirPath)

    fun renameFile(handle: Long, oldPath: String, newPath: String): Int =
        nativeRenameFile(handle, oldPath, newPath)

    /** Non-suspend close, for call sites that can't use the suspend [unmountContainer]
     *  (e.g. MainActivity.onDestroy, which isn't a coroutine). */
    fun closeContainer(handle: Long): Int = nativeCloseContainer(handle)

    fun getDataSize(handle: Long): Long = nativeGetDataSize(handle)
    fun getAlgorithmId(handle: Long): Int = nativeGetAlgorithmId(handle)
    fun getHashId(handle: Long): Int = nativeGetHashId(handle)
    fun getFilesystem(handle: Long): Int = nativeGetFilesystem(handle)
    fun getKeySize(handle: Long): Int = nativeGetKeySize(handle)
    fun getIterationCount(handle: Long): Int = nativeGetIterationCount(handle)

    // ── JNI external declarations ──────────────────────────────────────
    // Private: nothing outside this class should call these directly — go
    // through the suspend wrappers above (create/mount/change/etc.) or the
    // thin non-suspend wrappers just above this block (readFile, listFiles, …).

    private external fun nativeCreateContainer(
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

    private external fun nativeCreateContainerFd(
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

    private external fun nativeOpenContainer(
        path: String,
        password: String,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: String?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?,
        readOnly: Boolean
    ): Long

    private external fun nativeOpenContainerFd(
        fd: Int,
        password: String,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: String?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?,
        readOnly: Boolean
    ): Long

    private external fun nativeListFiles(
        handle: Long,
        dirPath: String
    ): Array<NativeFileInfo>?

    private external fun nativeReadFile(
        handle: Long,
        filePath: String,
        offset: Long,
        length: Int
    ): ByteArray?

    private external fun nativeWriteFile(
        handle: Long,
        filePath: String,
        data: ByteArray,
        offset: Long
    ): Int

    private external fun nativeDeleteFile(handle: Long, filePath: String): Int

    private external fun nativeDeleteDirectory(handle: Long, dirPath: String): Int

    private external fun nativeCreateDirectory(handle: Long, dirPath: String): Int

    private external fun nativeRenameFile(handle: Long, oldPath: String, newPath: String): Int

    private external fun nativeCloseContainer(handle: Long): Int

    private external fun nativeCreateHiddenVolume(
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

    private external fun nativeCreateHiddenVolumeFd(
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

    private external fun nativeChangePassword(
        path: String,
        oldPassword: String,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: String,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangePasswordFd(
        fd: Int,
        oldPassword: String,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: String,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfile(
        path: String,
        password: String,
        oldKeyfilePaths: Array<String>?,
        pim: Int,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfileFd(
        fd: Int,
        password: String,
        oldKeyfilePaths: Array<String>?,
        pim: Int,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeBackupVolumeHeader(
        volumePath: String,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        outputPath: String
    ): Int

    private external fun nativeBackupVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        outputFd: Int
    ): Int

    private external fun nativeRestoreVolumeHeader(
        volumePath: String,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        fromExternal: Boolean,
        backupPath: String
    ): Int

    private external fun nativeRestoreVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        fromExternal: Boolean,
        backupFd: Int
    ): Int

    private external fun nativeExpandVolume(
        path: String,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        newSizeBytes: Long,
        progressListener: CreationProgressListener?
    ): Int

    private external fun nativeExpandVolumeFd(
        fd: Int,
        password: String,
        keyfilePaths: Array<String>?,
        pim: Int,
        newSizeBytes: Long,
        progressListener: CreationProgressListener?
    ): Int

    private external fun nativeGetVolumeType(handle: Long): Int

    private external fun nativeHasHiddenVolume(handle: Long): Boolean

    private external fun nativeGetAlgorithmId(handle: Long): Int

    private external fun nativeGetHashId(handle: Long): Int

    private external fun nativeGetFilesystem(handle: Long): Int

    private external fun nativeGetDataSize(handle: Long): Long

    private external fun nativeGetKeySize(handle: Long): Int

    private external fun nativeGetIterationCount(handle: Long): Int

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
        const val ERR_READ_ONLY        = -10

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
    VeraCryptEngine.ERR_WRONG_PASSWORD  -> CryptoError.WRONG_PASSWORD
    VeraCryptEngine.ERR_FILE,
    VeraCryptEngine.ERR_READ            -> CryptoError.IO_ERROR
    VeraCryptEngine.ERR_RAND            -> CryptoError.RNG_FAILURE
    VeraCryptEngine.ERR_UNSUPPORTED     -> CryptoError.UNSUPPORTED_ALGORITHM
    VeraCryptEngine.ERR_FS              -> CryptoError.CORRUPTED_CONTAINER
    VeraCryptEngine.ERR_NO_SPACE        -> CryptoError.NO_SPACE
    VeraCryptEngine.ERR_READ_ONLY       -> CryptoError.READ_ONLY
    VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> CryptoError.HIDDEN_BOUNDARY_PROTECTED
    VeraCryptEngine.ERR_NO_SLOT         -> CryptoError.TOO_MANY_MOUNTED
    else                                -> CryptoError.UNKNOWN
}

private fun Int.toResult(): CryptoResult<Unit> = when (this) {
    VeraCryptEngine.ERR_OK -> CryptoResult.Success(Unit)
    else                   -> CryptoResult.Failure(this.toError())
}
