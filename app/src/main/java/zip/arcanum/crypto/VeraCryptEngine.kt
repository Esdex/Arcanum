package zip.arcanum.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zip.arcanum.core.security.IdleMonitor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VeraCryptEngine @Inject constructor(
    private val idleMonitor: IdleMonitor
) {

    // ── Idle auto-lock, kept honest about work ─────────────────────────
    // Watching a progress bar is not a touch, so before this the idle clock could not tell
    // a five-minute mount from a phone left on a table, and locked mid-operation. Every
    // long call is bracketed here, in the one place they all pass through, rather than in
    // each screen that starts one. See IdleMonitor.

    private suspend fun <T> onIo(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) {
            idleMonitor.operationStarted()
            try {
                block()
            } finally {
                idleMonitor.operationFinished()
            }
        }

    /* For the short calls: too brief to bracket, but an import is thousands of them in a
     * row and the gaps between them must not read as idleness. Stamped on completion. */
    private inline fun <T> marked(block: () -> T): T = block().also { idleMonitor.recordOperation() }

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
        keyfileData: List<ByteArray> = emptyList(),
        progressListener: CreationProgressListener? = null,
        pim: Int = 0
    ): CryptoResult<Unit> = onIo {
        val rc = usePasswordBytes(password) { passwordBytes ->
            nativeCreateContainer(
                path, sizeBytes, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null },
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
        readOnly: Boolean = false,
        /** Argon2id only: proceed even when free memory is below the guard's headroom (#177). */
        allowLowMemory: Boolean = false
    ): CryptoResult<Long> = onIo {
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
                    readOnly,
                    allowLowMemory
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
        keyfileData: List<ByteArray> = emptyList(),
        progressListener: CreationProgressListener? = null,
        pim: Int = 0
    ): CryptoResult<Unit> = onIo {
        val rc = usePasswordBytes(password) { passwordBytes ->
            nativeCreateContainerFd(
                fd, sizeBytes, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null },
                algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
                progressListener, pim
            )
        }
        rc.toResult()
    }

    /**
     * Mounts a VeraCrypt volume occupying a whole USB device (issue #95).
     *
     * [transport] must be an open `zip.arcanum.usb.UsbBlockDevice`, and [deviceSize] its
     * capacity - there is no file to measure, so the size comes from READ CAPACITY on the
     * Kotlin side. Typed as `Any` to keep the crypto layer free of a dependency on the
     * USB package; the native side only ever calls read/write/sync on it.
     *
     * The caller keeps ownership of [transport] and must close it after unmounting: the
     * native backend borrows it, exactly as the file path borrows its descriptor.
     */
    suspend fun mountContainerUsb(
        transport: Any,
        deviceSize: Long,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        algorithm: Int = ALGO_AUTO,
        hashAlgorithm: Int = HASH_AUTO,
        protectHiddenPassword: String? = null,
        protectHiddenKeyfileData: List<ByteArray> = emptyList(),
        protectHiddenPim: Int = 0,
        mountProgressListener: MountProgressListener? = null,
        readOnly: Boolean = false,
        /** Argon2id only: proceed even when free memory is below the guard's headroom (#177). */
        allowLowMemory: Boolean = false
    ): CryptoResult<Long> = onIo {
        val handle = usePasswordBytes(password) { passwordBytes ->
            usePasswordBytesOrNull(protectHiddenPassword) { hiddenBytes ->
                nativeOpenContainerUsb(
                    transport, deviceSize, passwordBytes,
                    keyfileData.toTypedArray().ifEmpty { null },
                    pim, algorithm, hashAlgorithm,
                    hiddenBytes,
                    protectHiddenKeyfileData.toTypedArray().ifEmpty { null },
                    protectHiddenPim,
                    mountProgressListener,
                    readOnly,
                    allowLowMemory
                )
            }
        }
        if (handle >= 0) CryptoResult.Success(handle)
        else CryptoResult.Failure(handle.toInt().toError())
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
        readOnly: Boolean = false,
        /** Argon2id only: proceed even when free memory is below the guard's headroom (#177). */
        allowLowMemory: Boolean = false
    ): CryptoResult<Long> = onIo {
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
                    readOnly,
                    allowLowMemory
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
        outerKeyfileData: List<ByteArray> = emptyList(),
        outerPim: Int = 0,
        hiddenPassword: String,
        hiddenKeyfileData: List<ByteArray> = emptyList(),
        hiddenPim: Int = 0,
        hiddenAlgorithm: Int = 0,
        hiddenHashAlgorithm: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = onIo {
        val rc = usePasswordBytes(outerPassword, hiddenPassword) { outerBytes, hiddenBytes ->
            nativeCreateHiddenVolumeFd(
                fd, hiddenSizeBytes,
                outerBytes, outerKeyfileData.toTypedArray().ifEmpty { null }, outerPim,
                hiddenBytes, hiddenKeyfileData.toTypedArray().ifEmpty { null }, hiddenPim,
                hiddenAlgorithm, hiddenHashAlgorithm,
                quickFormat, entropyBytes, progressListener
            )
        }
        rc.toResult()
    }

    suspend fun unmountContainer(handle: Long): CryptoResult<Unit> =
        onIo {
            nativeCloseContainer(handle).toResult()
        }

    suspend fun createHiddenVolume(
        path: String,
        hiddenSizeBytes: Long,
        outerPassword: String,
        outerKeyfileData: List<ByteArray> = emptyList(),
        outerPim: Int = 0,
        hiddenPassword: String,
        hiddenKeyfileData: List<ByteArray> = emptyList(),
        hiddenPim: Int = 0,
        hiddenAlgorithm: Int = 0,
        hiddenHashAlgorithm: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        progressListener: CreationProgressListener? = null
    ): CryptoResult<Unit> = onIo {
        val rc = usePasswordBytes(outerPassword, hiddenPassword) { outerBytes, hiddenBytes ->
            nativeCreateHiddenVolume(
                path, hiddenSizeBytes,
                outerBytes, outerKeyfileData.toTypedArray().ifEmpty { null }, outerPim,
                hiddenBytes, hiddenKeyfileData.toTypedArray().ifEmpty { null }, hiddenPim,
                hiddenAlgorithm, hiddenHashAlgorithm,
                quickFormat, entropyBytes, progressListener
            )
        }
        rc.toResult()
    }

    suspend fun changePassword(
        path: String,
        oldPassword: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        oldPim: Int = 0,
        newPassword: String,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        newPim: Int = 0,
        wipePassCount: Int = 3,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(oldPassword, newPassword) { oldBytes, newBytes ->
            nativeChangePassword(
                path, oldBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, oldPim,
                newBytes,
                newKeyfileData.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
                wipePassCount, extraEntropy
            )
        }.toResult()
    }

    suspend fun changePasswordFd(
        fd: Int,
        oldPassword: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        oldPim: Int = 0,
        newPassword: String,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        newPim: Int = 0,
        wipePassCount: Int = 3,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(oldPassword, newPassword) { oldBytes, newBytes ->
            nativeChangePasswordFd(
                fd, oldBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, oldPim,
                newBytes,
                newKeyfileData.toTypedArray().ifEmpty { null }, newHashAlgorithm, newPim,
                wipePassCount, extraEntropy
            )
        }.toResult()
    }

    suspend fun changeKeyfile(
        path: String,
        password: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeChangeKeyfile(
                path, passwordBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, pim,
                newKeyfileData.toTypedArray().ifEmpty { null }, newHashAlgorithm,
                extraEntropy
            )
        }.toResult()
    }

    suspend fun changeKeyfileFd(
        fd: Int,
        password: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeChangeKeyfileFd(
                fd, passwordBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, pim,
                newKeyfileData.toTypedArray().ifEmpty { null }, newHashAlgorithm,
                extraEntropy
            )
        }.toResult()
    }

    suspend fun backupVolumeHeader(
        path: String,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        outputPath: String
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeBackupVolumeHeader(
                path, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
                outputPath
            )
        }.toResult()
    }

    suspend fun backupVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        outputFd: Int
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeBackupVolumeHeaderFd(
                volumeFd, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
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
    ): CryptoResult<Unit> = onIo {
        nativeGenerateKeyfileFd(outputFd, sizeBytes, entropyBytes).toResult()
    }

    suspend fun restoreVolumeHeader(
        path: String,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupPath: String = ""
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeRestoreVolumeHeader(
                path, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
                fromExternal, backupPath
            )
        }.toResult()
    }

    suspend fun restoreVolumeHeaderFd(
        volumeFd: Int,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupFd: Int = -1
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeRestoreVolumeHeaderFd(
                volumeFd, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
                fromExternal, backupFd
            )
        }.toResult()
    }

    /**
     * What an Argon2id derivation at [pim] would cost, and what the phone has left.
     *
     * The numbers come from the same native code the derivation uses, so the wizard
     * and the mount screen cannot quote a formula that has drifted from the one that
     * runs (#177).
     */
    fun argon2Cost(pim: Int): Argon2Cost {
        val v = nativeArgon2Cost(pim) ?: return Argon2Cost(0, 0, 0)
        return Argon2Cost(passes = v[0], memoryMib = v[1], availableMib = v[2])
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
        marked { nativeListFiles(handle, dirPath) }

    fun listFiles(handle: Long, dirPath: String): Array<NativeFileInfo> =
        marked { nativeListFiles(handle, dirPath) } ?: emptyArray()

    fun readFile(handle: Long, filePath: String, offset: Long, length: Int): ByteArray? =
        marked { nativeReadFile(handle, filePath, offset, length) }

    /**
     * Reads what can be read and hands back exactly that, where [readFile] refuses the
     * whole file if any part of what it walks is unreadable.
     *
     * **Only export may use this.** Refusing is the right answer everywhere else: bytes
     * from a structure that failed validation are a reader's invention, and a copy or an
     * import that wrote them would put that invention in a second place and report
     * success. An export is the one operation where the opposite is wanted, since it is
     * how a damaged vault is emptied - and a short result there is marked `.part` and
     * counted rather than passed off as the whole file (#170, #173).
     *
     * A short return does not mean the file ended: compare it against the size.
     */
    fun readFilePartial(handle: Long, filePath: String, offset: Long, length: Int): ByteArray? =
        marked { nativeReadFilePartial(handle, filePath, offset, length) }

    fun writeFile(handle: Long, filePath: String, data: ByteArray, offset: Long): Int =
        marked { nativeWriteFile(handle, filePath, data, offset) }

    /** Non-truncating positional write (creates the file if absent). Safe for random-access
     *  writes from the SAF provider - a write at offset 0 does not discard the rest of the file. */
    fun writeAt(handle: Long, filePath: String, data: ByteArray, offset: Long): Int =
        marked { nativeWriteAt(handle, filePath, data, offset) }

    /**
     * Stamps a file with a modification time of the caller's choosing, in epoch
     * milliseconds. Meant for the date a file already had before it was imported: writing
     * the content moves the timestamp to now on both filesystems, so the original has to
     * be put back afterwards (#154).
     *
     * Returns [ERR_UNSUPPORTED] for an instant the filesystem cannot hold - before 1980 or
     * past 2107 on FAT, outside the 32-bit second range on ext4 - which leaves the file
     * with the time the write gave it rather than a wrong one.
     */
    fun setFileTime(handle: Long, filePath: String, epochMs: Long): Int =
        marked { nativeSetFileTime(handle, filePath, epochMs) }

    fun deleteFile(handle: Long, filePath: String): Int =
        marked { nativeDeleteFile(handle, filePath) }

    fun deleteDirectory(handle: Long, dirPath: String): Int =
        marked { nativeDeleteDirectory(handle, dirPath) }

    fun createDirectory(handle: Long, dirPath: String): Int =
        marked { nativeCreateDirectory(handle, dirPath) }

    /**
     * Gives [targetPath] a second name at [linkPath].
     *
     * Which kind of link that is follows from what is being linked and is decided
     * natively: a file gets a hard link — one inode under two names, no extra space
     * and no way for it to dangle — and a directory, which cannot have one, gets a
     * symbolic link instead. ext4 only; FAT and exFAT return ERR_UNSUPPORTED,
     * because the only thing either could offer is a copy, which is what this
     * exists to avoid.
     */
    fun createLink(handle: Long, linkPath: String, targetPath: String): Int =
        marked { nativeCreateLink(handle, linkPath, targetPath) }

    /**
     * Writes a symbolic link at [linkPath] holding [target] exactly as given, without
     * looking at what it names.
     *
     * [createLink] is the one behind Create link, and it decides between a hard and a
     * symbolic link by resolving the target. This one is for copying a link that
     * already exists: it was a symlink, it stays a symlink, and it keeps its target
     * text whether or not there is anything at the end of it - a link that leads
     * nowhere copies as a link that leads nowhere (#168). ext4 only.
     */
    fun createSymlink(handle: Long, linkPath: String, target: String): Int =
        marked { nativeCreateSymlink(handle, linkPath, target) }

    fun renameFile(handle: Long, oldPath: String, newPath: String): Int =
        marked { nativeRenameFile(handle, oldPath, newPath) }

    /** Non-suspend close, for call sites that can't use the suspend [unmountContainer]
     *  (e.g. MainActivity.onDestroy, which isn't a coroutine). */
    fun closeContainer(handle: Long): Int = nativeCloseContainer(handle)

    fun getDataSize(handle: Long): Long = nativeGetDataSize(handle)

    /**
     * Capacity and free space of the filesystem inside the volume, or null if it
     * cannot be queried.
     *
     * Not the same as [getDataSize], which is the volume size from the VeraCrypt
     * header. Expanding a container grows the volume but not the filesystem in it,
     * so the header size can count space no write will ever reach - anything showing
     * the user how much room is left must ask here instead.
     *
     * Walks the FAT, so call it per screen, not per file.
     */
    fun getFsUsage(handle: Long): FsUsage? =
        nativeGetFsUsage(handle)?.takeIf { it.size == 2 }?.let { FsUsage(it[0], it[1]) }
    fun getAlgorithmId(handle: Long): Int = nativeGetAlgorithmId(handle)
    fun getHashId(handle: Long): Int = nativeGetHashId(handle)
    fun getFilesystem(handle: Long): Int = nativeGetFilesystem(handle)

    /**
     * Whether this ext4 vault was left part way through a write - the app killed,
     * the battery gone, a drive pulled - and so has bookkeeping a check would tidy.
     *
     * Always false for FAT and exFAT, which keep no such flag, and false for a vault
     * that was put down properly. What a cut-short write leaves behind is always
     * something `e2fsck` repairs without costing anything else on the volume, so the
     * vault is mounted and usable either way; this is only worth telling the user
     * about so they can run a check on a desktop if they want one. See issue #142.
     *
     * Read at mount and remembered, so it answers about the session that was
     * interrupted rather than about this one - the first write here clears the flag.
     */
    fun ext4NeedsCheck(handle: Long): Boolean = nativeExt4NeedsCheck(handle)
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
        keyfileData: Array<ByteArray>?,
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
        keyfileData: Array<ByteArray>?,
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
        readOnly: Boolean,
        allowLowMemory: Boolean
    ): Long


    // ── Whole-device USB variants (#95) ──────────────────────────────────
    // Same four operations against a volume occupying a USB device. [transport] is an
    // open zip.arcanum.usb.UsbBlockDevice, typed Any so this layer keeps no dependency
    // on the USB package; [deviceSize] is its capacity, which for a device comes from
    // READ CAPACITY because there is no file to measure. The caller keeps ownership of
    // the transport and closes it afterwards.

    suspend fun changePasswordUsb(
        transport: Any,
        deviceSize: Long,
        oldPassword: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        oldPim: Int = 0,
        newPassword: String,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        newPim: Int = 0,
        wipePassCount: Int = 3,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(oldPassword, newPassword) { oldBytes, newBytes ->
            nativeChangePasswordUsb(
                transport, deviceSize, oldBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, oldPim,
                newBytes, newKeyfileData.toTypedArray().ifEmpty { null },
                newHashAlgorithm, newPim, wipePassCount, extraEntropy
            )
        }.toResult()
    }

    suspend fun changeKeyfileUsb(
        transport: Any,
        deviceSize: Long,
        password: String,
        oldKeyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        newKeyfileData: List<ByteArray> = emptyList(),
        newHashAlgorithm: Int = HASH_AUTO,
        extraEntropy: ByteArray = ByteArray(0)
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeChangeKeyfileUsb(
                transport, deviceSize, passwordBytes,
                oldKeyfileData.toTypedArray().ifEmpty { null }, pim,
                newKeyfileData.toTypedArray().ifEmpty { null }, newHashAlgorithm,
                extraEntropy
            )
        }.toResult()
    }

    suspend fun backupVolumeHeaderUsb(
        transport: Any,
        deviceSize: Long,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        outputFd: Int
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeBackupVolumeHeaderUsb(
                transport, deviceSize, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
                outputFd
            )
        }.toResult()
    }

    suspend fun restoreVolumeHeaderUsb(
        transport: Any,
        deviceSize: Long,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        fromExternal: Boolean,
        backupFd: Int = -1
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeRestoreVolumeHeaderUsb(
                transport, deviceSize, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null }, pim,
                fromExternal, backupFd
            )
        }.toResult()
    }

    /**
     * Creates a VeraCrypt volume occupying a whole USB device (#95).
     *
     * [sizeBytes] is the DATA size, as everywhere else here; [deviceSize] is the drive's
     * capacity, passed so the native side can refuse a volume that would not fit. Use
     * [usbDataSizeFor] to derive one from the other.
     *
     * Destroys everything on the drive, including its partition table.
     */
    suspend fun createContainerUsb(
        transport: Any,
        deviceSize: Long,
        sizeBytes: Long,
        password: String,
        algorithm: Int = 0,
        hashAlgorithm: Int = 0,
        filesystem: Int = 0,
        quickFormat: Boolean = true,
        entropyBytes: ByteArray = ByteArray(0),
        keyfileData: List<ByteArray> = emptyList(),
        progressListener: CreationProgressListener? = null,
        pim: Int = 0
    ): CryptoResult<Unit> = onIo {
        usePasswordBytes(password) { passwordBytes ->
            nativeCreateContainerUsb(
                transport, deviceSize, sizeBytes, passwordBytes,
                keyfileData.toTypedArray().ifEmpty { null },
                algorithm, hashAlgorithm, filesystem, quickFormat, entropyBytes,
                progressListener, pim
            )
        }.toResult()
    }

    private external fun nativeCreateContainerUsb(
        transport: Any, deviceSize: Long, sizeBytes: Long,
        password: ByteArray, keyfileData: Array<ByteArray>?,
        algorithm: Int, hashAlgorithm: Int, filesystem: Int,
        quickFormat: Boolean, entropyBytes: ByteArray,
        progressListener: CreationProgressListener?, pim: Int
    ): Int

    private external fun nativeChangePasswordUsb(
        transport: Any, deviceSize: Long,
        oldPassword: ByteArray, oldKeyfileData: Array<ByteArray>?, oldPim: Int,
        newPassword: ByteArray, newKeyfileData: Array<ByteArray>?,
        newHashAlgorithm: Int, newPim: Int, wipePassCount: Int, extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfileUsb(
        transport: Any, deviceSize: Long,
        password: ByteArray, oldKeyfileData: Array<ByteArray>?, pim: Int,
        newKeyfileData: Array<ByteArray>?, newHashAlgorithm: Int, extraEntropy: ByteArray
    ): Int

    /**
     * Pushes anything held back on our side down to the medium, leaving the volume
     * mounted. Cheap, and worth doing whenever the app might be killed without warning.
     */
    suspend fun flushContainer(handle: Long): Int =
        onIo { nativeFlushContainer(handle) }

    /**
     * Formats a bare partition as FAT32 - the ordinary partition of a partitioned USB
     * drive (#131), not a vault and not encrypted.
     *
     * [transport] must be a view of the partition, so its offset 0 is the partition's
     * first sector. Everything in it is destroyed.
     */
    suspend fun formatFatPartition(transport: Any, sizeBytes: Long): CryptoResult<Unit> =
        onIo { nativeFormatFatPartition(transport, sizeBytes).toResult() }

    private external fun nativeArgon2Cost(pim: Int): IntArray?

    private external fun nativeFlushContainer(handle: Long): Int

    private external fun nativeFormatFatPartition(transport: Any, sizeBytes: Long): Int

    private external fun nativeBackupVolumeHeaderUsb(
        transport: Any, deviceSize: Long,
        password: ByteArray, keyfileData: Array<ByteArray>?, pim: Int, outputFd: Int
    ): Int

    private external fun nativeRestoreVolumeHeaderUsb(
        transport: Any, deviceSize: Long,
        password: ByteArray, keyfileData: Array<ByteArray>?, pim: Int,
        fromExternal: Boolean, backupFd: Int
    ): Int

    private external fun nativeOpenContainerUsb(
        transport: Any,
        deviceSize: Long,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        algorithm: Int,
        hashAlgorithm: Int,
        protectHiddenPassword: ByteArray?,
        protectHiddenKeyfileData: Array<ByteArray>?,
        protectHiddenPim: Int,
        mountProgressListener: MountProgressListener?,
        readOnly: Boolean,
        allowLowMemory: Boolean
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
        readOnly: Boolean,
        allowLowMemory: Boolean
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

    private external fun nativeSetFileTime(
        handle: Long,
        filePath: String,
        epochMs: Long
    ): Int

    private external fun nativeDeleteFile(handle: Long, filePath: String): Int

    private external fun nativeDeleteDirectory(handle: Long, dirPath: String): Int

    private external fun nativeCreateDirectory(handle: Long, dirPath: String): Int
    private external fun nativeCreateLink(
        handle: Long, linkPath: String, targetPath: String
    ): Int

    private external fun nativeReadFilePartial(
        handle: Long, filePath: String, offset: Long, length: Int
    ): ByteArray?

    private external fun nativeCreateSymlink(
        handle: Long, linkPath: String, target: String
    ): Int

    private external fun nativeRenameFile(handle: Long, oldPath: String, newPath: String): Int

    private external fun nativeCloseContainer(handle: Long): Int

    private external fun nativeCreateHiddenVolume(
        path: String,
        hiddenSizeBytes: Long,
        outerPassword: ByteArray,
        outerKeyfileData: Array<ByteArray>?,
        outerPim: Int,
        hiddenPassword: ByteArray,
        hiddenKeyfileData: Array<ByteArray>?,
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
        outerKeyfileData: Array<ByteArray>?,
        outerPim: Int,
        hiddenPassword: ByteArray,
        hiddenKeyfileData: Array<ByteArray>?,
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
        oldKeyfileData: Array<ByteArray>?,
        oldPim: Int,
        newPassword: ByteArray,
        newKeyfileData: Array<ByteArray>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangePasswordFd(
        fd: Int,
        oldPassword: ByteArray,
        oldKeyfileData: Array<ByteArray>?,
        oldPim: Int,
        newPassword: ByteArray,
        newKeyfileData: Array<ByteArray>?,
        newHashAlgorithm: Int,
        newPim: Int,
        wipePassCount: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfile(
        path: String,
        password: ByteArray,
        oldKeyfileData: Array<ByteArray>?,
        pim: Int,
        newKeyfileData: Array<ByteArray>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeChangeKeyfileFd(
        fd: Int,
        password: ByteArray,
        oldKeyfileData: Array<ByteArray>?,
        pim: Int,
        newKeyfileData: Array<ByteArray>?,
        newHashAlgorithm: Int,
        extraEntropy: ByteArray
    ): Int

    private external fun nativeBackupVolumeHeader(
        volumePath: String,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        outputPath: String
    ): Int

    private external fun nativeBackupVolumeHeaderFd(
        volumeFd: Int,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        outputFd: Int
    ): Int

    private external fun nativeRestoreVolumeHeader(
        volumePath: String,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        fromExternal: Boolean,
        backupPath: String
    ): Int

    private external fun nativeRestoreVolumeHeaderFd(
        volumeFd: Int,
        password: ByteArray,
        keyfileData: Array<ByteArray>?,
        pim: Int,
        fromExternal: Boolean,
        backupFd: Int
    ): Int

    private external fun nativeGenerateKeyfileFd(
        outputFd: Int,
        sizeBytes: Int,
        entropyBytes: ByteArray?
    ): Int

    private external fun nativeGetVolumeType(handle: Long): Int

    private external fun nativeHasHiddenVolume(handle: Long): Boolean

    private external fun nativeGetAlgorithmId(handle: Long): Int

    private external fun nativeGetHashId(handle: Long): Int

    private external fun nativeGetFilesystem(handle: Long): Int

    private external fun nativeExt4NeedsCheck(handle: Long): Boolean

    private external fun nativeGetDataSize(handle: Long): Long
    private external fun nativeGetFsUsage(handle: Long): LongArray?

    private external fun nativeGetKeySize(handle: Long): Int

    private external fun nativeGetIterationCount(handle: Long): Int

    // ── Companion ──────────────────────────────────────────────────────
    companion object {
        /** VeraCrypt header area at the front of a volume, and the backup area at the end. */
        private const val VC_DATA_OFFSET = 131072L
        private const val VC_BACKUP_AREA = 131072L

        /**
         * The data size a whole-device volume can hold on a drive of [deviceSize] bytes:
         * the capacity minus both header areas, rounded down to a whole sector.
         *
         * Kept here rather than in the USB layer because the two constants are the volume
         * format's, not the transport's.
         */
        /**
         * Where the backup header sits: the last [VC_BACKUP_AREA] of the volume.
         *
         * Reading it back after creation is the only check on the far end of a volume.
         * It catches a drive that accepts writes it does not keep, and an addressing
         * mistake at the top of the address space - READ(10) carries a 32-bit LBA, so the
         * far end is exactly where an overflow would show. It does NOT catch a drive whose
         * writes fold back on themselves: the read folds to the same place as the write
         * and returns what was just put there. See #132 for why that one needs the drive
         * filled to be found at all.
         */
        fun usbBackupHeaderOffset(volumeBytes: Long): Long = volumeBytes - VC_BACKUP_AREA

        fun usbDataSizeFor(deviceSize: Long): Long {
            val usable = deviceSize - VC_DATA_OFFSET - VC_BACKUP_AREA
            return if (usable <= 0) 0L else (usable / 512L) * 512L
        }

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
        const val ERR_DIR_FULL         = -11

        /**
         * Native ERR_EXISTS: [renameFile] refused because the destination name is
         * already taken. No caller distinguishes it yet - every rename call site
         * reads the result as OK-or-not - but the constant has to exist here for
         * the same reason as the rest: ErrorCodeSyncTest fails the build when the
         * two sides drift, and this one drifted unnoticed from 25 Jul 2026.
         */
        const val ERR_EXISTS           = -12

        /**
         * Native ERR_TOO_FRAGMENTED: an ext4 file's extent tree has no room left to
         * describe another piece of it, so the write is refused even though the
         * vault still has free blocks (#125).
         *
         * Kept apart from [ERR_NO_SPACE], which it used to be reported as, because
         * the two contradict each other about the volume: this one arrives on a
         * vault with space to spare. Since #119 the writer grows the tree instead
         * of refusing, and what is left is the depth limit the ext4 format itself
         * sets - so this is a code that exists to identify itself in a bug report,
         * not one anyone should meet.
         */
        const val ERR_TOO_FRAGMENTED   = -13

        /**
         * Native ERR_BUSY: the operation was refused because that volume is mounted
         * right now. Only header restore raises it (#147): a restored header can
         * carry a different master key, and a mounted drive would go on writing with
         * the keys it already has, which shows up as undecryptable data at the next
         * mount and nowhere before it.
         *
         * [zip.arcanum.arcanum.containers.ui.RestoreHeaderViewModel] refuses this
         * first and says so in plain words, so this arriving means the native guard
         * caught something the UI did not.
         */
        const val ERR_BUSY             = -14

        /**
         * Native ERR_ARGON2_MEMORY: an Argon2id derivation was refused because the
         * device cannot spare what the PIM asks for, or the allocation itself failed.
         *
         * Unlike every other failure on the mount path this one is not about the
         * password: the same password on a phone with more memory free would open the
         * volume. [argon2Cost] says what it would take, so the message can name the
         * number rather than blaming the vault (#177).
         */
        const val ERR_ARGON2_MEMORY    = -15

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
            5 -> "ext4"
            else -> "—"
        }

        const val HASH_BLAKE2S = 4

        /**
         * Argon2id, VeraCrypt's sixth PRF. Never part of auto-detect: one attempt
         * allocates hundreds of megabytes and takes seconds, so it is used only when
         * it is named - by the user in the mount options, or by what the vault
         * remembered of its last successful mount (#177).
         */
        const val HASH_ARGON2ID = 5

        fun hashIdToString(hashId: Int): String = when (hashId) {
            0 -> "SHA-512"
            1 -> "SHA-256"
            2 -> "Whirlpool"
            3 -> "Streebog"
            4 -> "BLAKE2s-256"
            5 -> "Argon2id"
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
    VeraCryptEngine.ERR_DIR_FULL        -> CryptoError.DIRECTORY_FULL
    VeraCryptEngine.ERR_TOO_FRAGMENTED  -> CryptoError.TOO_FRAGMENTED
    VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> CryptoError.HIDDEN_BOUNDARY_PROTECTED
    VeraCryptEngine.ERR_NO_SLOT         -> CryptoError.TOO_MANY_MOUNTED
    VeraCryptEngine.ERR_BUSY            -> CryptoError.BUSY
    VeraCryptEngine.ERR_ARGON2_MEMORY   -> CryptoError.ARGON2_MEMORY
    else                                -> CryptoError.UNKNOWN
}

private fun Int.toResult(): CryptoResult<Unit> = when (this) {
    VeraCryptEngine.ERR_OK -> CryptoResult.Success(Unit)
    else                   -> CryptoResult.Failure(this.toError())
}
