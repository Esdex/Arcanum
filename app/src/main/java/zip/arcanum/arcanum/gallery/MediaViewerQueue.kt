package zip.arcanum.arcanum.gallery

import zip.arcanum.crypto.NativeFileInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaViewerQueue @Inject constructor() {
    @Volatile var containerId: String = ""; private set
    @Volatile var files: List<NativeFileInfo> = emptyList(); private set
    @Volatile var currentIndex: Int = 0; private set
    @Volatile private var orderedMediaContainerId: String = ""
    @Volatile private var orderedMediaFileIds: List<String> = emptyList()
    @Volatile private var orderedMediaUpdatedAt: Long = 0L

    fun set(containerId: String, files: List<NativeFileInfo>, startIndex: Int) {
        this.containerId = containerId
        this.files = files
        this.currentIndex = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    }

    fun clearForContainer(containerId: String) {
        if (this.containerId == containerId) {
            this.containerId = ""
            this.files = emptyList()
            this.currentIndex = 0
        }
        if (orderedMediaContainerId == containerId) {
            orderedMediaContainerId = ""
            orderedMediaFileIds = emptyList()
            orderedMediaUpdatedAt = 0L
        }
    }

    fun setMediaOrder(containerId: String, fileIds: List<String>) {
        orderedMediaContainerId = containerId
        orderedMediaFileIds = fileIds
        orderedMediaUpdatedAt = System.currentTimeMillis()
    }

    fun mediaOrderFor(containerId: String, selectedFileId: String): List<String>? {
        val ids = orderedMediaFileIds
        val isFresh = System.currentTimeMillis() - orderedMediaUpdatedAt < MEDIA_ORDER_TTL_MS
        return if (isFresh && orderedMediaContainerId == containerId && selectedFileId in ids) ids else null
    }

    companion object {
        private const val MEDIA_ORDER_TTL_MS = 5 * 60 * 1000L
    }
}
