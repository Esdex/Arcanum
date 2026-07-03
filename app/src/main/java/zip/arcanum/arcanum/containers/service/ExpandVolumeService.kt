package zip.arcanum.arcanum.containers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.security.VaultPasswordPolicy
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoError
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

@AndroidEntryPoint
class ExpandVolumeService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var paramsStore: ExpandVolumeParams
    @Inject lateinit var repo: ContainerRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class Progress(
        val fraction: Float = 0f,
        val message: String = "",
        val isComplete: Boolean = false,
        val error: String? = null
    )

    private data class SnapshotEntry(
        val path: String,
        val size: Long,
        val isDirectory: Boolean
    )

    private data class TraversalBudget(
        val startedAtMs: Long = SystemClock.elapsedRealtime(),
        var items: Int = 0
    )

    companion object {
        const val CHANNEL_ID = "expand_volume"
        const val NOTIFICATION_ID = 1005
        private const val MAX_REBUILD_TREE_DEPTH = 64
        private const val MAX_REBUILD_ITEMS = 100_000
        private const val MAX_REBUILD_DURATION_MS = 24L * 60L * 60L * 1000L
        private const val COPY_CHUNK_BYTES = 8L * 1024L * 1024L

        private val _progress = MutableStateFlow<Progress?>(null)
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        fun resetProgress() { _progress.value = null }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = paramsStore.take() ?: return START_NOT_STICKY
        updateProgress(Progress(message = getString(R.string.expand_progress_starting)))
        startForeground(NOTIFICATION_ID, buildNotification(0f, getString(R.string.expand_progress_starting)))

        serviceScope.launch {
            try {
                val result = when (p.strategy) {
                    ExpandVolumeStrategy.SAFE_REBUILD -> safeRebuild(p)
                    ExpandVolumeStrategy.IN_PLACE     -> expandInPlace(p)
                }
                when (result) {
                    is CryptoResult.Success -> updateProgress(
                        Progress(
                            fraction = 1f,
                            message = getString(R.string.expand_progress_complete),
                            isComplete = true
                        )
                    )
                    is CryptoResult.Failure -> updateProgress(
                        Progress(
                            fraction = 1f,
                            message = getString(R.string.expand_progress_failed),
                            isComplete = true,
                            error = result.error.toUserMessage()
                        )
                    )
                }
            } catch (t: Throwable) {
                updateProgress(
                    Progress(
                        fraction = 1f,
                        message = getString(R.string.expand_progress_failed),
                        isComplete = true,
                        error = t.message ?: getString(R.string.expand_error_unknown)
                    )
                )
            } finally {
                p.entropyBytes.fill(0)
                p.safPfd?.close()
                p.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
                p.hiddenKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun expandInPlace(p: ExpandVolumeParams.Params): CryptoResult<Unit> {
        validatePasswords(p)
        updateProgress(Progress(0.1f, getString(R.string.expand_progress_inspecting)))
        val keyData = readKeyfiles(p.keyfilePaths)
        val result = if (p.safFd >= 0) {
            cryptoEngine.expandVolumeInPlaceFd(
                fd = p.safFd,
                targetDataSizeBytes = p.targetDataSizeBytes,
                password = p.password,
                keyfileData = keyData,
                pim = p.pim
            )
        } else {
            cryptoEngine.expandVolumeInPlace(
                path = p.path,
                targetDataSizeBytes = p.targetDataSizeBytes,
                password = p.password,
                keyfileData = keyData,
                pim = p.pim
            )
        }
        if (result is CryptoResult.Success) {
            updateRepositoryAfterSuccess(p)
        }
        return result
    }

    private suspend fun safeRebuild(p: ExpandVolumeParams.Params): CryptoResult<Unit> {
        validatePasswords(p)
        updateProgress(Progress(0.05f, getString(R.string.expand_progress_inspecting)))
        val keyData = readKeyfiles(p.keyfilePaths)
        val hiddenKeyData = readKeyfiles(p.hiddenKeyfilePaths)

        val outerGeometry = inspectOriginal(
            p = p,
            password = p.password,
            keyData = keyData,
            pim = p.pim,
            hiddenPassword = p.hiddenPassword.takeIf { p.includeHidden },
            hiddenKeyData = hiddenKeyData,
            hiddenPim = p.hiddenPim
        ) ?: return CryptoResult.Failure(CryptoError.WRONG_PASSWORD)

        if (p.targetDataSizeBytes <= outerGeometry.dataSizeBytes) {
            return CryptoResult.Failure(CryptoError.NO_SPACE)
        }
        if (outerGeometry.hasHiddenVolume && !p.includeHidden) {
            return CryptoResult.Failure(CryptoError.HIDDEN_CREDENTIALS_REQUIRED)
        }
        if (p.includeHidden && p.hiddenTargetDataSizeBytes < 4L * 1024L * 1024L) {
            return CryptoResult.Failure(CryptoError.NO_SPACE)
        }

        val hiddenGeometry = if (p.includeHidden) {
            inspectOriginal(
                p = p,
                password = p.hiddenPassword,
                keyData = hiddenKeyData,
                pim = p.hiddenPim
            ) ?: return CryptoResult.Failure(CryptoError.WRONG_PASSWORD)
        } else null

        preflightFreeSpace(p, outerGeometry.fileSizeBytes)?.let { message ->
            throw IllegalStateException(message)
        }

        val temp = createTempTarget(p)
        try {
            updateProgress(Progress(0.15f, getString(R.string.expand_progress_creating_temp)))
            val fsId = if (outerGeometry.filesystemType == 4) 1 else 0
            val createOuter = cryptoEngine.createContainer(
                path = temp.absolutePath,
                sizeBytes = p.targetDataSizeBytes,
                password = p.password,
                algorithm = outerGeometry.algorithmId.coerceAtLeast(0),
                hashAlgorithm = outerGeometry.hashId.coerceAtLeast(0),
                filesystem = fsId,
                quickFormat = false,
                keyfilePaths = p.keyfilePaths,
                pim = p.pim,
                entropyBytes = p.entropyBytes
            )
            if (createOuter is CryptoResult.Failure) return createOuter

            if (p.includeHidden && hiddenGeometry != null) {
                val createHidden = cryptoEngine.createHiddenVolume(
                    path = temp.absolutePath,
                    hiddenSizeBytes = p.hiddenTargetDataSizeBytes,
                    outerPassword = p.password,
                    outerKeyfilePaths = p.keyfilePaths,
                    outerPim = p.pim,
                    hiddenPassword = p.hiddenPassword,
                    hiddenKeyfilePaths = p.hiddenKeyfilePaths,
                    hiddenPim = p.hiddenPim,
                    hiddenAlgorithm = hiddenGeometry.algorithmId.coerceAtLeast(0),
                    hiddenHashAlgorithm = hiddenGeometry.hashId.coerceAtLeast(0),
                    quickFormat = false,
                    entropyBytes = p.entropyBytes
                )
                if (createHidden is CryptoResult.Failure) return createHidden
            }

            updateProgress(Progress(0.30f, getString(R.string.expand_progress_copying_outer)))
            val outerCopy = copyVolumeTree(
                source = MountRequest.Original(p, p.password, keyData, p.pim),
                target = MountRequest.Path(temp.absolutePath, p.password, keyData, p.pim, p.hiddenPassword.takeIf { p.includeHidden }, hiddenKeyData, p.hiddenPim),
                startFraction = 0.30f,
                endFraction = if (p.includeHidden) 0.58f else 0.82f
            )
            if (outerCopy is CryptoResult.Failure) return outerCopy

            if (p.includeHidden) {
                updateProgress(Progress(0.60f, getString(R.string.expand_progress_copying_hidden)))
                val hiddenCopy = copyVolumeTree(
                    source = MountRequest.Original(p, p.hiddenPassword, hiddenKeyData, p.hiddenPim),
                    target = MountRequest.Path(temp.absolutePath, p.hiddenPassword, hiddenKeyData, p.hiddenPim, null, emptyList(), 0),
                    startFraction = 0.60f,
                    endFraction = 0.84f
                )
                if (hiddenCopy is CryptoResult.Failure) return hiddenCopy
            }

            updateProgress(Progress(0.88f, getString(R.string.expand_progress_replacing)))
            replaceOriginal(p, temp)
            updateRepositoryAfterSuccess(p)
            return CryptoResult.Success(Unit)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun createTempTarget(p: ExpandVolumeParams.Params): File {
        if (p.path.isNotBlank()) {
            val original = File(p.path)
            val parent = original.parentFile ?: filesDir
            return File(parent, ".${original.name}.expand.${System.currentTimeMillis()}.tmp")
        }
        return File(cacheDir, "arcanum_expand_${p.containerId}_${System.currentTimeMillis()}.hc")
    }

    private fun preflightFreeSpace(p: ExpandVolumeParams.Params, originalFileSizeBytes: Long): String? {
        val requiredScratch = if (p.path.isBlank()) {
            ExpandVolumeSpaceGuard.addSaturating(
                ExpandVolumeSpaceGuard.requiredScratchBytes(p.targetDataSizeBytes),
                originalFileSizeBytes
            )
        } else {
            ExpandVolumeSpaceGuard.requiredScratchBytes(p.targetDataSizeBytes)
        }
        if (p.path.isNotBlank()) {
            val free = ExpandVolumeSpaceGuard.usableSpaceForFileTarget(p.path, filesDir)
            return if (free < requiredScratch) formatSpaceError(requiredScratch, free) else null
        }

        val cacheFree = cacheDir.usableSpace
        if (cacheFree < requiredScratch) return formatSpaceError(requiredScratch, cacheFree)

        val providerPfd = p.safPfd ?: return getString(R.string.expand_error_free_space_unknown)
        val providerFree = ExpandVolumeSpaceGuard.usableSpaceForFd(providerPfd)
            ?: return getString(R.string.expand_error_free_space_unknown)
        val availableAfterReplacing = ExpandVolumeSpaceGuard.addSaturating(providerFree, originalFileSizeBytes)
        val requiredFinal = ExpandVolumeSpaceGuard.requiredFinalBytes(p.targetDataSizeBytes)
        return if (availableAfterReplacing < requiredFinal) {
            formatSpaceError(requiredFinal, availableAfterReplacing)
        } else null
    }

    private fun formatSpaceError(required: Long, available: Long): String =
        getString(
            R.string.expand_error_free_space_required,
            FileUtils.getHumanReadableSize(required),
            FileUtils.getHumanReadableSize(available)
        )

    private fun validatePasswords(p: ExpandVolumeParams.Params) {
        val outerOk = VaultPasswordPolicy.isWithinVeraCryptLimit(p.password)
        val hiddenOk = !p.includeHidden || VaultPasswordPolicy.isWithinVeraCryptLimit(p.hiddenPassword)
        if (!outerOk || !hiddenOk) {
            throw IllegalStateException(VaultPasswordPolicy.violationMessage())
        }
    }

    private suspend fun inspectOriginal(
        p: ExpandVolumeParams.Params,
        password: String,
        keyData: List<ByteArray>,
        pim: Int,
        hiddenPassword: String? = null,
        hiddenKeyData: List<ByteArray> = emptyList(),
        hiddenPim: Int = 0
    ): VeraCryptEngine.VolumeGeometry? {
        val result = if (p.safFd >= 0) {
            cryptoEngine.inspectVolumeFd(
                fd = p.safFd,
                password = password,
                keyfileData = keyData,
                pim = pim,
                hiddenPassword = hiddenPassword,
                hiddenKeyfileData = hiddenKeyData,
                hiddenPim = hiddenPim
            )
        } else {
            cryptoEngine.inspectVolume(
                path = p.path,
                password = password,
                keyfileData = keyData,
                pim = pim,
                hiddenPassword = hiddenPassword,
                hiddenKeyfileData = hiddenKeyData,
                hiddenPim = hiddenPim
            )
        }
        return (result as? CryptoResult.Success)?.value
    }

    private sealed class MountRequest {
        data class Original(
            val p: ExpandVolumeParams.Params,
            val password: String,
            val keyData: List<ByteArray>,
            val pim: Int
        ) : MountRequest()

        data class Path(
            val path: String,
            val password: String,
            val keyData: List<ByteArray>,
            val pim: Int,
            val protectHiddenPassword: String?,
            val protectHiddenKeyData: List<ByteArray>,
            val protectHiddenPim: Int
        ) : MountRequest()
    }

    private suspend fun copyVolumeTree(
        source: MountRequest,
        target: MountRequest,
        startFraction: Float,
        endFraction: Float
    ): CryptoResult<Unit> {
        val sourceHandle = mount(source)
        if (sourceHandle is CryptoResult.Failure) return sourceHandle
        val targetHandle = mount(target)
        if (targetHandle is CryptoResult.Failure) {
            cryptoEngine.unmountContainer((sourceHandle as CryptoResult.Success).value)
            return targetHandle
        }

        val src = (sourceHandle as CryptoResult.Success).value
        val dst = (targetHandle as CryptoResult.Success).value
        return try {
            val expected = snapshot(src, budget = TraversalBudget())
            val total = expected.filter { !it.isDirectory }.sumOf { it.size }.coerceAtLeast(1L)
            var copied = 0L
            val rc = copyDirectory(src, dst, "/", total, budget = TraversalBudget()) { bytes ->
                copied += bytes
                val fraction = startFraction + ((endFraction - startFraction) * (copied.toDouble() / total.toDouble())).toFloat()
                updateProgress(Progress(fraction.coerceIn(startFraction, endFraction), getString(R.string.expand_progress_copying_files)))
            }
            if (rc != VeraCryptEngine.ERR_OK) return CryptoResult.Failure(rc.toCryptoError())
            val actual = snapshot(dst, budget = TraversalBudget())
            if (expected != actual) return CryptoResult.Failure(CryptoError.FILESYSTEM_ERROR)
            CryptoResult.Success(Unit)
        } finally {
            cryptoEngine.unmountContainer(dst)
            cryptoEngine.unmountContainer(src)
        }
    }

    private suspend fun mount(request: MountRequest): CryptoResult<Long> = when (request) {
        is MountRequest.Original -> {
            val p = request.p
            if (p.safFd >= 0) {
                cryptoEngine.mountContainerFd(
                    fd = p.safFd,
                    password = request.password,
                    keyfileData = request.keyData,
                    pim = request.pim
                )
            } else {
                cryptoEngine.mountContainer(
                    path = p.path,
                    password = request.password,
                    keyfileData = request.keyData,
                    pim = request.pim
                )
            }
        }
        is MountRequest.Path -> cryptoEngine.mountContainer(
            path = request.path,
            password = request.password,
            keyfileData = request.keyData,
            pim = request.pim,
            protectHiddenPassword = request.protectHiddenPassword,
            protectHiddenKeyfileData = request.protectHiddenKeyData,
            protectHiddenPim = request.protectHiddenPim
        )
    }

    private suspend fun copyDirectory(
        sourceHandle: Long,
        targetHandle: Long,
        path: String,
        totalBytes: Long,
        depth: Int = 0,
        budget: TraversalBudget,
        onCopied: (Long) -> Unit
    ): Int {
        coroutineContext.ensureActive()
        checkTraversalBudget(depth, budget)
        val entries = cryptoEngine.nativeListFiles(sourceHandle, path).sortedWith(
            compareByDescending<NativeFileInfo> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
        for (entry in entries) {
            coroutineContext.ensureActive()
            checkTraversalBudget(depth + 1, budget)
            if (entry.isDirectory) {
                val mkdir = cryptoEngine.nativeCreateDirectory(targetHandle, entry.path)
                if (mkdir != VeraCryptEngine.ERR_OK && mkdir != VeraCryptEngine.ERR_FILE) return mkdir
                val nested = copyDirectory(sourceHandle, targetHandle, entry.path, totalBytes, depth + 1, budget, onCopied)
                if (nested != VeraCryptEngine.ERR_OK) return nested
                cryptoEngine.nativeSetModifiedTime(targetHandle, entry.path, entry.lastModified)
            } else {
                val rc = copyFile(sourceHandle, targetHandle, entry, onCopied)
                if (rc != VeraCryptEngine.ERR_OK) return rc
            }
        }
        return VeraCryptEngine.ERR_OK
    }

    private fun copyFile(
        sourceHandle: Long,
        targetHandle: Long,
        file: NativeFileInfo,
        onCopied: (Long) -> Unit
    ): Int {
        val chunkSize = 1024 * 1024
        var offset = 0L
        while (offset < file.size) {
            val length = minOf(chunkSize.toLong(), file.size - offset).toInt()
            val chunk = cryptoEngine.nativeReadFile(sourceHandle, file.path, offset, length)
                ?: return VeraCryptEngine.ERR_READ
            if (chunk.size != length) return VeraCryptEngine.ERR_READ
            val rc = cryptoEngine.nativeWriteFile(targetHandle, file.path, chunk, offset)
            chunk.fill(0)
            if (rc != VeraCryptEngine.ERR_OK) return rc
            offset += chunk.size
            onCopied(chunk.size.toLong())
        }
        if (file.size == 0L) {
            val rc = cryptoEngine.nativeWriteFile(targetHandle, file.path, ByteArray(0), 0)
            if (rc != VeraCryptEngine.ERR_OK) return rc
        }
        cryptoEngine.nativeSetModifiedTime(targetHandle, file.path, file.lastModified)
        return VeraCryptEngine.ERR_OK
    }

    private suspend fun snapshot(
        handle: Long,
        path: String = "/",
        depth: Int = 0,
        budget: TraversalBudget
    ): List<SnapshotEntry> {
        coroutineContext.ensureActive()
        checkTraversalBudget(depth, budget)
        val out = mutableListOf<SnapshotEntry>()
        cryptoEngine.nativeListFiles(handle, path).sortedBy { it.path }.forEach { entry ->
            checkTraversalBudget(depth + 1, budget)
            out += SnapshotEntry(entry.path, entry.size, entry.isDirectory)
            if (entry.isDirectory) out += snapshot(handle, entry.path, depth + 1, budget)
        }
        return out
    }

    private fun checkTraversalBudget(depth: Int, budget: TraversalBudget) {
        if (depth > MAX_REBUILD_TREE_DEPTH) throw IllegalStateException(getString(R.string.expand_error_filesystem))
        budget.items++
        if (budget.items > MAX_REBUILD_ITEMS) throw IllegalStateException(getString(R.string.expand_error_filesystem))
        if (SystemClock.elapsedRealtime() - budget.startedAtMs > MAX_REBUILD_DURATION_MS) {
            throw IllegalStateException(getString(R.string.expand_error_filesystem))
        }
    }

    private fun replaceOriginal(p: ExpandVolumeParams.Params, temp: File) {
        val expectedSize = temp.length()
        if (p.path.isNotBlank()) {
            val original = File(p.path)
            val backup = File(original.parentFile, "${original.name}.expand.bak")
            if (backup.exists() && !backup.delete()) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
            if (!original.renameTo(backup)) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
            if (!temp.renameTo(original)) {
                backup.renameTo(original)
                throw IllegalStateException(getString(R.string.expand_error_replace_failed))
            }
            if (original.length() != expectedSize) {
                original.delete()
                backup.renameTo(original)
                throw IllegalStateException(getString(R.string.expand_error_replace_failed))
            }
            backup.delete()
            return
        }

        val uri = Uri.parse(p.safUri)
        val backup = File(cacheDir, "arcanum_expand_backup_${p.containerId}_${System.currentTimeMillis()}.hc")
        try {
            copySafToFile(uri, backup)
            try {
                copyFileToSaf(temp, uri)
                if (safLength(uri) != expectedSize) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
            } catch (t: Throwable) {
                runCatching { copyFileToSaf(backup, uri) }
                throw t
            }
        } finally {
            FileUtils.secureZeroAndDelete(backup)
        }
    }

    private fun copySafToFile(uri: Uri, target: File) {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException(getString(R.string.expand_error_saf_resize_unsupported))
        pfd.use { inPfd ->
            val expected = inPfd.statSize
            if (expected < 0L) throw IllegalStateException(getString(R.string.expand_error_saf_resize_unsupported))
            FileInputStream(inPfd.fileDescriptor).channel.use { input ->
                FileOutputStream(target).channel.use { output ->
                    var position = 0L
                    while (position < expected) {
                        val copied = input.transferTo(position, COPY_CHUNK_BYTES, output)
                        if (copied <= 0L) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
                        position += copied
                    }
                    output.force(true)
                }
            }
            if (target.length() != expected) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
        }
    }

    private fun copyFileToSaf(source: File, uri: Uri) {
        val expected = source.length()
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException(getString(R.string.expand_error_saf_resize_unsupported))
        pfd.use { outPfd ->
            FileInputStream(source).channel.use { input ->
                FileOutputStream(outPfd.fileDescriptor).channel.use { output ->
                    var position = 0L
                    while (position < expected) {
                        val written = input.transferTo(position, COPY_CHUNK_BYTES, output)
                        if (written <= 0L) throw IllegalStateException(getString(R.string.expand_error_replace_failed))
                        position += written
                    }
                    output.truncate(expected)
                    output.force(true)
                }
            }
        }
    }

    private fun safLength(uri: Uri): Long =
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

    private suspend fun updateRepositoryAfterSuccess(p: ExpandVolumeParams.Params) {
        val size = if (p.path.isNotBlank()) {
            File(p.path).length()
        } else {
            contentResolver.openFileDescriptor(Uri.parse(p.safUri), "r")?.use { it.statSize } ?: p.targetDataSizeBytes
        }
        repo.updateSize(p.containerId, size)
        repo.updateHeaderModifiedAt(p.containerId, System.currentTimeMillis())
    }

    private fun readKeyfiles(paths: List<String>): List<ByteArray> =
        paths.map { path ->
            FileUtils.readKeyfileFileBytes(File(path))
                ?: throw IllegalStateException(getString(R.string.expand_error_io))
        }

    private fun updateProgress(progress: Progress) {
        _progress.value = progress
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_expand_volume),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_expand_volume_desc)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun buildNotification(fraction: Float, message: String): Notification {
        val pct = (fraction * 100).roundToInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_expanding_volume))
            .setContentText(message.ifBlank { getString(R.string.notif_expanding_volume_desc) })
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun Int.toCryptoError(): CryptoError = when (this) {
        VeraCryptEngine.ERR_WRONG_PASSWORD -> CryptoError.WRONG_PASSWORD
        VeraCryptEngine.ERR_NO_SPACE -> CryptoError.NO_SPACE
        VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> CryptoError.HIDDEN_BOUNDARY
        VeraCryptEngine.ERR_FS -> CryptoError.FILESYSTEM_ERROR
        VeraCryptEngine.ERR_UNSUPPORTED -> CryptoError.UNSUPPORTED_OPERATION
        VeraCryptEngine.ERR_FILE,
        VeraCryptEngine.ERR_READ -> CryptoError.IO_ERROR
        else -> CryptoError.UNKNOWN
    }

    private fun CryptoError.toUserMessage(): String = when (this) {
        CryptoError.WRONG_PASSWORD -> getString(R.string.expand_error_wrong_password)
        CryptoError.NO_SPACE -> getString(R.string.expand_error_no_space)
        CryptoError.HIDDEN_BOUNDARY -> getString(R.string.expand_error_hidden_boundary)
        CryptoError.HIDDEN_CREDENTIALS_REQUIRED -> getString(R.string.expand_error_hidden_required)
        CryptoError.UNSUPPORTED_OPERATION -> getString(R.string.expand_error_in_place_unavailable)
        CryptoError.FILESYSTEM_ERROR -> getString(R.string.expand_error_filesystem)
        CryptoError.IO_ERROR -> getString(R.string.expand_error_io)
        else -> getString(R.string.expand_error_unknown)
    }
}
