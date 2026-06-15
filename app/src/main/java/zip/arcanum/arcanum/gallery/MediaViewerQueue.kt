package zip.arcanum.arcanum.gallery

import zip.arcanum.crypto.NativeFileInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaViewerQueue @Inject constructor() {
    @Volatile var containerId: String = ""; private set
    @Volatile var files: List<NativeFileInfo> = emptyList(); private set
    @Volatile var currentIndex: Int = 0; private set

    fun set(containerId: String, files: List<NativeFileInfo>, startIndex: Int) {
        this.containerId = containerId
        this.files = files
        this.currentIndex = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    }
}
