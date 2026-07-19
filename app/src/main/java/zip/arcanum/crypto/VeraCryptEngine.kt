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

    // ── Password-bytes helpers ─────────────────────────────────────────
    // The JNI boundary takes passwords as ByteArray (not String) so the
    // transient UTF-8 copy can be zeroed once the native call returns —
    // a Kotlin/Java String is immutable and cannot be wiped, so it stays on
    // the JVM heap until GC. Converting at the boundary and wiping the copy
    // in `finally` narrows the exposure window; it does NOT eliminate it —
    // the original `password: String` parameter above still lives in the
    // JVM heap, unwipeable, until garbage collected.

    private inline fun <T> usePasswordBytes(password: String, block: (ByteArray) -> T): T {
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            return block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Two-password variant (old/new, outer/hidden) — nests [usePasswordBytes] so both
     *  transient copies are wiped in `finally`, innermost-first, regardless of outcome. */
    private inline fun <T> usePasswordBytes(
        password1: String,
        password2: String,
        block: (ByteArray, ByteArray) -> T
    ): T = usePasswordBytes(password1) { bytes1 ->
        usePasswordBytes(password2) { bytes2 ->
            block(bytes1, bytes2)
        }
    }

    /** Nullable variant for `protectHiddenPassword: String?` — null in, null out, no allocation. */
    private inline fun <T> usePasswordBytesOrNull(password: String?, block: (ByteArray?) -> T): T {
        if (password == null) return block(null)
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            return block(bytes)
        } finally {
            bytes.fill(0)
        }
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
        val rc = usePasswordBytes(password) { passwordBytes ->
            nativeCreateContainer(
                path, sizeBytes, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null },
                algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
                progressListener, pim
            )
        }
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
        val handle = usePasswordBytes(password) { passwordBytes ->
            usePasswordBytesOrNull(protectHiddenPassword) { hiddenBytes ->
                nativeOpenContainer(
                    path, passwordBytes,
                    keyfileData.toTypedArray().ifEmpty { null },
                    pim, algorithm, hashAlgorithm,
                    hiddenBytes,
                    protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
                    protectHiddenPim,
                    mountProgressListener,
                    readOnly
                )
            }
        }
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
        val rc = usePasswordBytes(password) { passwordBytes ->
            nativeCreateContainerFd(
                fd, sizeBytes, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null },
                algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
                progressListener, pim
            )
        }
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
        val handle = usePasswordBytes(password) { passwordBytes ->
            usePasswordBytesOrNull(protectHiddenPassword) { hiddenBytes ->
                nativeOpenContainerFd(
                    fd, passwordBytes,
                    keyfileData.toTypedArray().ifEmpty { null },
                    pim, algorithm, hashAlgorithm,
                    hiddenBytes,
                    protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
                    protectHiddenPim,
                    mountProgressListener,
                    readOnly
                )
            }
        }
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
        val rc = usePasswordBytes(outerPassword, hiddenPassword) { outerBytes, hiddenBytes ->
            nativeCreateHiddenVolumeFd(
                fd, hiddenSizeBytes,
                outerBytes, outerKeyfilePaths.toTypedArray().ifEmpty { null }, outerPim,
                hiddenBytes, hiddenKeyfilePaths.toTypedArray().ifEmpty { null }, hiddenPim,
                hiddenAlgorithm, hiddenHashAlgorithm,
                quickFormat, entropyBytes, progressListener
            )
        }
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
        val rc = usePasswordBytes(outerPassword, hiddenPassword) { outerBytes, hiddenBytes ->
            nativeCreateHiddenVolume(
                path, hiddenSizeBytes,
                outerBytes, outerKeyfilePaths.toTypedArray().ifEmpty { null }, outerPim,
                hiddenBytes, hiddenKeyfilePaths.toTypedArray().ifEmpty { null }, hiddenPim,
                hiddenAlgorithm, hiddenHashAlgorithm,
                quickFormat, entropyBytes, progressListener
            )
        }
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
        usePasswordBytes(oldPassword, newPassword) { oldBytes, newBytes ->
            nativeChangePassword(
                path, oldBytes,
                oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
                newBytes,
                newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
                wipePassCount, extraEntropy
            )
        }.toResult()
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
        usePasswordBytes(oldPassword, newPassword) { oldBytes, newBytes ->
            nativeChangePasswordFd(
                fd, oldBytes,
                oldKeyfilePaths.toTypedArray().ifEmpty { null }, oldPim,
                newBytes,
                newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
                wipePassCount, extraEntropy
            )
        }.toResult()
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
        usePasswordBytes(password) { passwordBytes ->
            nativeChangeKeyfile(
                path, passwordBytes,
                oldKeyfilePaths.toTypedArray().ifEmpty { null }, pim,
                newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm,
                extraEntropy
            )
        }.toResult()
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
        usePasswordBytes(password) { passwordBytes ->
            nativeChangeKeyfileFd(
                fd, passwordBytes,
                oldKeyfilePaths.toTypedArray().ifEmpty { null }, pim,
                newKeyfilePaths.toTypedArray().ifEmpty { null }, newHashAlgorithm,
                extraEntropy
            )
        }.toResult()
    }

    suspend fun backupVolumeHeader(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        outputPath: String
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeBackupVolumeHeader(
                path, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                outputPath
            )
        }.toResult()
    }

    suspend fun backupVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        outputFd: Int
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeBackupVolumeHeaderFd(
                volumeFd, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                outputFd
            )
        }.toResult()
    }

    /**
     * Fills [outputFd] with [sizeBytes] of CSPRNG output, producing a keyfile
     * interchangeable with VeraCrypt's Tools > Keyfile Generator.
     *
     * [sizeBytes] must be within [KEYFILE_MIN_SIZE]..[KEYFILE_MAX_SIZE]; the
     * native side rejects anything else with [ERR_UNSUPPORTED] rather than
     * silently clamping, so callers should validate before offering the size.
     *
     * [entropyBytes] is optional user-collected touch entropy, XOR-folded into
     * the urandom stream exactly as during container creation. Omitting it
     * leaves plain urandom, which is already a CSPRNG.
     *
     * The generated bytes never cross back into the JVM heap — they are written
     * straight to the descriptor natively and wiped there.
     */
    suspend fun generateKeyfileFd(
        outputFd: Int,
        sizeBytes: Int = KEYFILE_DEFAULT_SIZE,
        entropyBytes: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        nativeGenerateKeyfileFd(outputFd, sizeBytes, entropyBytes).toResult()
    }

    suspend fun restoreVolumeHeader(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupPath: String = ""
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeRestoreVolumeHeader(
                path, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                fromExternal, backupPath
            )
        }.toResult()
    }

    suspend fun restoreVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupFd: Int = -1
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeRestoreVolumeHeaderFd(
                volumeFd, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                fromExternal, backupFd
            )
        }.toResult()
    }

    suspend fun expandVolume(
        path: String,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newSizeBytes: Long,
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeExpandVolume(
                path, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                newSizeBytes, progressListener
            )
        }.toResult()
    }

    suspend fun expandVolumeFd(
        fd: Int,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        newSizeBytes: Long,
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = withContext(Dispatchers.IO) {
        usePasswordBytes(password) { passwordBytes ->
            nativeExpandVolumeFd(
                fd, passwordBytes,
                keyfilePaths.toTypedArray().ifEmpty { null }, pim,
                newSizeBytes, progressListener
            )
        }.toResult()
    }

    fun getVolumeType(handle: Long): Int = nativeGetVolumeType(handle)
    fun hasHiddenVolume(handle: Long): Boolean = nativeHasHiddenVolume(handle)

    // ── Thin non-suspend wrappers ────────────────────────────────────────
    // The `external fun native*` declarations below are private; callers outside
    // this file (gallery/files packages, MainActivity, etc.) go through these.
    // Non-suspend because every existing caller already runs on its own
    // background dispatcher (ViewModel coroutine scope, MediaDataSource thread, …)
    // and controlled its own threading before this wrapping was added.

    /** Returns null on native read error (mid-listing disk failure), empty array for a genuinely
     *  empty directory.  Callers that need to distinguish the two should use this overload. */
    fun listFilesOrNull(handle: Long, dirPath: String): Array<NativeFileInfo>? =
        nativeListFiles(handle, dirPath)

    fun listFiles(handle: Long, dirPath: String): Array<NativeFileInfo> =
        nativeListFiles(handle, dirPath) ?: emptyArray()

    fun readFile(handle: Long, filePath: String, offset: Long, length: Int): ByteArray? =
        nativeReadFile(handle, filePath, offset, length)

    fun writeFile(handle: Long, filePath: String, data: ByteArray, offset: Long): Int =
        nativeWriteFile(handle, filePath, data, offset)

    /** Non-truncating positional write (creates the file if absent). Safe for random-access
     *  writes from the SAF provider - a write at offset 0 does not discard the rest of the file. */
    fun writeAt(handle: Long, filePath: String, data: ByteArray, offset: Long): Int =
        nativeWriteAt(handle, filePath, data, offset)

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
        password: ByteArray,
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
        password: ByteArray,
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
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: ByteArray?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?,
        readOnly: Boolean
    ): Long

    private external fun nativeOpenContainerFd(
        fd: Int,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: ByteArray?,
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

    private external fun nativeWriteAt(
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
        outerPassword: ByteArray,
        outerKeyfilePaths: Array<String>?,
        outerPim: Int,
        hiddenPassword: ByteArray,
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
        outerPassword: ByteArray,
        outerKeyfilePaths: Array<String>?,
        outerPim: Int,
        hiddenPassword: ByteArray,
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
        oldPassword: ByteArray,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: ByteArray,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangePasswordFd(
        fd: Int,
        oldPassword: ByteArray,
        oldKeyfilePaths: Array<String>?,
        oldPim: Int,
        newPassword: ByteArray,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfile(
        path: String,
        password: ByteArray,
        oldKeyfilePaths: Array<String>?,
        pim: Int,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfileFd(
        fd: Int,
        password: ByteArray,
        oldKeyfilePaths: Array<String>?,
        pim: Int,
        newKeyfilePaths: Array<String>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeBackupVolumeHeader(
        volumePath: String,
        password: ByteArray,
        keyfilePaths: Array<String>?,
        pim: Int,
        outputPath: String
    ): Int

    private external fun nativeBackupVolumeHeaderFd(
        volumeFd: Int,
        password: ByteArray,
        keyfilePaths: Array<String>?,
        pim: Int,
        outputFd: Int
    ): Int

    private external fun nativeRestoreVolumeHeader(
        volumePath: String,
        password: ByteArray,
        keyfilePaths: Array<String>?,
        pim: Int,
        fromExternal: Boolean,
        backupPath: String
    ): Int

    private external fun nativeRestoreVolumeHeaderFd(
        volumeFd: Int,
        password: ByteArray,
        keyfilePaths: Array<String>?,
        pim: Int,
        fromExternal: Boolean,
        backupFd: Int
    ): Int

    private external fun nativeGenerateKeyfileFd(
        outputFd: Int,
        sizeBytes: Int,
        entropyBytes: ByteArray?
    ): Int

    private external fun nativeExpandVolume(
        path: String,
        password: ByteArray,
        keyfilePaths: Array<String>?,
        pim: Int,
        newSizeBytes: Long,
        progressListener: CreationProgressListener?
    ): Int

    private external fun nativeExpandVolumeFd(
        fd: Int,
        password: ByteArray,
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

        /**
         * Keyfile generator size bounds — must match VC_KEYFILE_MIN_SIZE /
         * VC_KEYFILE_MAX_SIZE in `app/src/main/cpp/arcanum_internal.h`, which
         * rejects anything outside this range with [ERR_UNSUPPORTED].
         *
         * Same range as VeraCrypt's generator dialog. The 1 MB ceiling is
         * VeraCrypt's keyfile read cap: bytes past it are never read at all.
         *
         * The 64-byte default is not just VeraCrypt's. Every byte below the cap
         * IS mixed in — but it is CRC-folded into a pool only 64 bytes wide
         * (128 once the password passes 64 bytes, see issue #112), and that
         * pool is the ceiling on what a keyfile can contribute. 64 bytes of
         * real random data already saturates it, so a larger file costs storage
         * without buying strength.
         */
        const val KEYFILE_MIN_SIZE     = 64
        const val KEYFILE_MAX_SIZE     = 1024 * 1024
        const val KEYFILE_DEFAULT_SIZE = 64

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
