package zip.arcanum.arcanum.containers.data

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.arcanum.saf.VaultDocumentsProvider
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContainerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ContainerDao,
    private val thumbnailManager: ThumbnailManager
) {
    private val _mountedContainerIds = MutableStateFlow<Set<String>>(emptySet())
    val mountedContainerIds: StateFlow<Set<String>> = _mountedContainerIds.asStateFlow()

    private data class MountState(
        val handle: Long,
        val pim: Int,
        val isHidden: Boolean,
        val hasHidden: Boolean,
        val dataSize: Long,
        val parcelFd: ParcelFileDescriptor?,
        val isReadOnly: Boolean
    )
    private val mounted = ConcurrentHashMap<String, MountState>()

    // There is deliberately no record here of which containers hold a hidden volume.
    //
    // A session-only set of those ids used to live here, feeding the guard that stopped
    // Expand Volume from running on an outer container. Expand Volume is gone, and with
    // it the only reader — so the set was removed rather than left filling up. Holding
    // that fact anywhere is a deniability cost: it is the same fact the code refuses to
    // put in the database, and refuses to write into the outer header as field28. Keeping
    // it in process memory for no consumer only exposed it to anyone who can inspect a
    // running process. If a future feature needs the same guard, weigh that cost again
    // rather than assuming it is free.

    fun getAllContainers(): Flow<List<Container>> =
        dao.getAllContainers().map { list -> list.map { it.toDomain() } }

    fun getAllContainersRaw(): Flow<List<ContainerEntity>> = dao.getAllContainers()

    suspend fun getContainerById(id: String): Container? =
        dao.getContainerById(id)?.toDomain()

    suspend fun saveContainer(container: Container) =
        dao.insertContainer(container.toEntity())

    suspend fun deleteContainer(container: Container) {
        thumbnailManager.clearCache(container.id)
        dao.deleteContainerById(container.id)
    }

    suspend fun setMounted(id: String, mounted: Boolean) =
        dao.setMounted(id, mounted)

    suspend fun mountContainer(
        id: String, handle: Long, pim: Int = 0,
        isHidden: Boolean = false, hasHidden: Boolean = false,
        dataSize: Long = 0L,
        parcelFd: ParcelFileDescriptor? = null,
        isReadOnly: Boolean = false
    ) {
        mounted[id] = MountState(
            handle = handle,
            pim = pim,
            isHidden = isHidden,
            hasHidden = hasHidden,
            dataSize = dataSize,
            parcelFd = parcelFd,
            isReadOnly = isReadOnly
        )
        _mountedContainerIds.update { it + id }
        dao.setMounted(id, true)
        dao.updateLastAccessed(id, System.currentTimeMillis())
    }

    fun getPimForContainer(id: String): Int = mounted[id]?.pim ?: 0

    suspend fun unmountContainer(id: String) {
        mounted.remove(id)?.parcelFd?.close()
        _mountedContainerIds.update { it - id }
        dao.setMounted(id, false)
        // The SAF root vanishes once the container is no longer mounted; refresh the picker and
        // drop any URI grants. Reads already fail on their own - the JNI handle is gone above.
        revokeExternalAccess(id)
        notifyExternalRootsChanged()
    }

    // Synchronous — safe to call from onDestroy without a coroutine.
    // Returns all active JNI handles so the caller can close them via VeraCryptEngine.closeContainer.
    fun closeAllHandlesSync(): List<Long> {
        val snapshot = mounted.values.toList()
        mounted.clear()
        _mountedContainerIds.value = emptySet()
        snapshot.forEach { runCatching { it.parcelFd?.close() } }
        return snapshot.map { it.handle }
    }

    // Reconcile the DB isMounted flags against the live in-memory handles. A container is really
    // mounted only while we hold its JNI handle in THIS process, so [mounted] is the ground truth.
    // Clear only flags the DB still has set for containers we do NOT hold - those are stale (left
    // after a crash/kill, or from a fresh MainActivity created while the process and its mounts are
    // still alive, e.g. a share intent that spawns a new activity). Never clear a live mount, or a
    // still-mounted vault would wrongly show as unmounted while its handle stays open.
    suspend fun resetMountedState() {
        dao.getAllContainersOnce()
            .filter { it.isMounted && !mounted.containsKey(it.id) }
            .forEach { dao.setMounted(it.id, false) }
    }

    fun getContainerHandle(id: String): Long? = mounted[id]?.handle

    fun isContainerReadOnly(id: String): Boolean = mounted[id]?.isReadOnly ?: false

    suspend fun containsPath(path: String): Boolean = dao.countByPath(path) > 0

    suspend fun containsSafUri(safUri: String): Boolean = dao.countBySafUri(safUri) > 0

    suspend fun containsDocumentId(authority: String, docId: String): Boolean {
        return dao.getAllContainersOnce().any { entity ->
            if (entity.safUri.isEmpty()) return@any false
            val stored = android.net.Uri.parse(entity.safUri)
            if (stored.authority != authority) return@any false
            zip.arcanum.core.utils.FileUtils.safUriDocumentId(stored) == docId
        }
    }

    suspend fun updateAlgorithm(id: String, algorithm: String) =
        dao.updateAlgorithm(id, algorithm)

    suspend fun updatePrf(id: String, prf: String) =
        dao.updatePrf(id, prf)

    suspend fun updateFilesystem(id: String, filesystem: String) =
        dao.updateFilesystem(id, filesystem)

    suspend fun updateBiometric(id: String, hasBiometric: Boolean) =
        dao.updateBiometric(id, hasBiometric)

    suspend fun updateUnmountOnLock(id: String, value: Boolean) =
        dao.updateUnmountOnLock(id, value)

    suspend fun updateUnmountOnBackground(id: String, value: Boolean) =
        dao.updateUnmountOnBackground(id, value)

    suspend fun isExternalAccessEnabled(id: String): Boolean =
        dao.getContainerById(id)?.externalAccessEnabled == true

    suspend fun updateExternalAccessEnabled(id: String, value: Boolean) {
        dao.updateExternalAccessEnabled(id, value)
        // Turning access off must immediately drop existing grants and hide the root.
        if (!value) revokeExternalAccess(id)
        notifyExternalRootsChanged()
    }

    // ── SAF DocumentsProvider coordination ─────────────────────────────────────
    // Tell the system file picker that the set of exposed roots changed (mount/unmount,
    // opt-in toggled, panic wipe), so a stale Arcanum root can't linger in other apps' pickers.
    fun notifyExternalRootsChanged() {
        runCatching {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(VaultDocumentsProvider.authority(context)),
                null
            )
        }
    }

    // Best-effort revoke of the container's tree grant. The hard guarantee is the missing JNI
    // handle (openDocument then fails); this just cleans up the picker's persisted permission.
    fun revokeExternalAccess(id: String) {
        runCatching {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                VaultDocumentsProvider.authority(context),
                VaultDocumentsProvider.rootDocumentId(id)
            )
            context.revokeUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun updateContainerPath(id: String, newPath: String) =
        dao.updatePath(id, newPath)

    suspend fun updateSafUri(id: String, safUri: String) =
        dao.updateSafUri(id, safUri)

    suspend fun updateName(id: String, name: String) =
        dao.updateName(id, name)

    suspend fun updateKeySize(id: String, keySize: Int) =
        dao.updateKeySize(id, keySize)

    suspend fun updateEncryptionMode(id: String, mode: String) =
        dao.updateEncryptionMode(id, mode)

    suspend fun updateBlockSize(id: String, blockSize: Int) =
        dao.updateBlockSize(id, blockSize)

    suspend fun updateFormatVersion(id: String, version: Int) =
        dao.updateFormatVersion(id, version)

    suspend fun updateHasBackupHeader(id: String, has: Boolean) =
        dao.updateHasBackupHeader(id, has)

    suspend fun updatePkcs5Iterations(id: String, iterations: Int) =
        dao.updatePkcs5Iterations(id, iterations)

    suspend fun updateHeaderModifiedAt(id: String, time: Long) =
        dao.updateHeaderModifiedAt(id, time)

    suspend fun updateSize(id: String, size: Long) =
        dao.updateSize(id, size)

    suspend fun deleteContainersById(ids: Set<String>) {
        ids.forEach { id ->
            mounted.remove(id)?.parcelFd?.close()
            thumbnailManager.clearCache(id)
            revokeExternalAccess(id)
            dao.deleteContainerById(id)
        }
        notifyExternalRootsChanged()
    }

    suspend fun addContainerFromPath(path: String, algorithm: String = "AES-256-XTS", safUri: String = "", name: String? = null, size: Long? = null): String {
        val id   = UUID.randomUUID().toString()
        val file = java.io.File(path)
        dao.insertContainer(ContainerEntity(
            id             = id,
            name           = name ?: file.name,
            path           = path,
            size           = size ?: file.length(),
            algorithm      = algorithm,
            safUri         = safUri,
            createdAt      = System.currentTimeMillis(),
            lastAccessedAt = 0L
        ))
        return id
    }

    suspend fun addContainerFromUri(safUri: String, displayName: String, size: Long): String {
        val id = UUID.randomUUID().toString()
        dao.insertContainer(ContainerEntity(
            id             = id,
            name           = displayName,
            path           = "",
            size           = size,
            algorithm      = "AES-256-XTS",
            safUri         = safUri,
            createdAt      = System.currentTimeMillis(),
            lastAccessedAt = 0L
        ))
        return id
    }

    private fun ContainerEntity.toDomain(): Container {
        val ms = mounted[id]
        return Container(
            id              = id,
            name            = name,
            path            = path,
            size            = ms?.dataSize ?: size,
            algorithm       = algorithm,
            prf             = prf,
            filesystem      = filesystem,
            pim             = ms?.pim ?: 0,
            createdAt       = createdAt,
            lastAccessedAt  = lastAccessedAt,
            isFavorite      = isFavorite,
            isMounted       = isMounted,
            isHiddenVolume  = ms?.isHidden ?: false,
            hasHiddenVolume = ms?.hasHidden ?: false,
            safUri          = safUri,
            keySize         = keySize,
            encryptionMode  = encryptionMode,
            blockSize       = blockSize,
            formatVersion   = formatVersion,
            hasBackupHeader = hasBackupHeader,
            pkcs5Iterations = pkcs5Iterations,
            headerModifiedAt = headerModifiedAt,
            isReadOnly      = ms?.isReadOnly ?: false
        )
    }

    private fun Container.toEntity() = ContainerEntity(
        id               = id,
        name             = name,
        path             = path,
        size             = size,
        algorithm        = algorithm,
        prf              = prf,
        filesystem       = filesystem,
        safUri           = safUri,
        createdAt        = createdAt,
        lastAccessedAt   = lastAccessedAt,
        isFavorite       = isFavorite,
        isMounted        = isMounted,
        keySize          = keySize,
        encryptionMode   = encryptionMode,
        blockSize        = blockSize,
        formatVersion    = formatVersion,
        hasBackupHeader  = hasBackupHeader,
        pkcs5Iterations  = pkcs5Iterations,
        headerModifiedAt = headerModifiedAt
    )
}
