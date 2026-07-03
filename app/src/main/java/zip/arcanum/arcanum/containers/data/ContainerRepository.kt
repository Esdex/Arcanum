package zip.arcanum.arcanum.containers.data

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContainerRepository @Inject constructor(
    private val dao: ContainerDao
) {
    // In-memory handle map: containerId → JNI handle
    private val mountedHandles      = mutableMapOf<String, Long>()
    // In-memory PIM map: containerId → PIM (0 = default)
    private val mountedPims         = mutableMapOf<String, Int>()
    // In-memory volume type: containerId → isHidden (null = unknown/not mounted)
    private val mountedIsHidden     = mutableMapOf<String, Boolean>()
    // In-memory hidden-present flag: containerId → hasHiddenVolume
    private val mountedHasHidden    = mutableMapOf<String, Boolean>()
    // In-memory data-area size: containerId → bytes (overrides DB size when mounted)
    private val mountedDataSize     = mutableMapOf<String, Long>()
    // SAF file descriptor kept open for the duration the container is mounted
    private val mountedParcelFds    = mutableMapOf<String, ParcelFileDescriptor>()
    // In-memory read-only flag: containerId → isReadOnly
    private val mountedIsReadOnly   = mutableMapOf<String, Boolean>()

    fun getAllContainers(): Flow<List<Container>> =
        dao.getAllContainers().map { list -> list.map { it.toDomain() } }

    fun getAllContainersRaw(): Flow<List<ContainerEntity>> = dao.getAllContainers()

    suspend fun getContainerById(id: String): Container? =
        dao.getContainerById(id)?.toDomain()

    suspend fun saveContainer(container: Container) =
        dao.insertContainer(container.toEntity())

    suspend fun deleteContainer(container: Container) =
        dao.deleteContainerById(container.id)

    suspend fun setMounted(id: String, mounted: Boolean) =
        dao.setMounted(id, mounted)

    suspend fun mountContainer(
        id: String, handle: Long, pim: Int = 0,
        isHidden: Boolean = false, hasHidden: Boolean = false,
        dataSize: Long = 0L,
        parcelFd: ParcelFileDescriptor? = null,
        isReadOnly: Boolean = false
    ) {
        mountedHandles[id] = handle
        if (pim > 0) mountedPims[id] = pim else mountedPims.remove(id)
        mountedIsHidden[id]   = isHidden
        mountedHasHidden[id]  = hasHidden
        mountedIsReadOnly[id] = isReadOnly
        if (dataSize > 0L) mountedDataSize[id] = dataSize else mountedDataSize.remove(id)
        parcelFd?.let { mountedParcelFds[id] = it }
        dao.setMounted(id, true)
        dao.updateLastAccessed(id, System.currentTimeMillis())
    }

    fun getPimForContainer(id: String): Int = mountedPims[id] ?: 0

    suspend fun unmountContainer(id: String) {
        mountedHandles.remove(id)
        mountedPims.remove(id)
        mountedIsHidden.remove(id)
        mountedHasHidden.remove(id)
        mountedIsReadOnly.remove(id)
        mountedDataSize.remove(id)
        mountedParcelFds.remove(id)?.close()
        dao.setMounted(id, false)
    }

    // Synchronous — safe to call from onDestroy without a coroutine.
    // Returns all active JNI handles so the caller can close them via nativeCloseContainer.
    fun closeAllHandlesSync(): List<Long> {
        val handles = mountedHandles.values.toList()
        mountedHandles.clear()
        mountedPims.clear()
        mountedIsHidden.clear()
        mountedHasHidden.clear()
        mountedIsReadOnly.clear()
        mountedDataSize.clear()
        mountedParcelFds.values.forEach { runCatching { it.close() } }
        mountedParcelFds.clear()
        return handles
    }

    // Reset isMounted flags that may have been left true after a crash or force-kill.
    suspend fun resetMountedState() = dao.setAllUnmounted()

    fun getContainerHandle(id: String): Long? = mountedHandles[id]

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
            mountedHandles.remove(id)
            mountedPims.remove(id)
            mountedIsHidden.remove(id)
            mountedHasHidden.remove(id)
            mountedIsReadOnly.remove(id)
            mountedDataSize.remove(id)
            mountedParcelFds.remove(id)?.close()
            dao.deleteContainerById(id)
        }
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

    private fun ContainerEntity.toDomain() = Container(
        id              = id,
        name            = name,
        path            = path,
        size            = mountedDataSize[id] ?: size,
        algorithm       = algorithm,
        prf             = prf,
        filesystem      = filesystem,
        pim             = mountedPims[id] ?: 0,
        createdAt       = createdAt,
        lastAccessedAt  = lastAccessedAt,
        isFavorite      = isFavorite,
        isMounted       = isMounted,
        isHiddenVolume  = mountedIsHidden[id] ?: false,
        hasHiddenVolume = mountedHasHidden[id] ?: false,
        safUri          = safUri,
        keySize         = keySize,
        encryptionMode  = encryptionMode,
        blockSize       = blockSize,
        formatVersion   = formatVersion,
        hasBackupHeader = hasBackupHeader,
        pkcs5Iterations = pkcs5Iterations,
        headerModifiedAt = headerModifiedAt,
        isReadOnly      = mountedIsReadOnly[id] ?: false
    )

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
