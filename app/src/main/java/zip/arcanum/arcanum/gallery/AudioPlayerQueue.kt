package zip.arcanum.arcanum.gallery

import zip.arcanum.crypto.NativeFileInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerQueue @Inject constructor() {
    @Volatile var containerId: String = ""; private set
    @Volatile var playlist: List<NativeFileInfo> = emptyList(); private set
    @Volatile var currentIndex: Int = 0; private set

    fun set(containerId: String, files: List<NativeFileInfo>, startIndex: Int) {
        this.containerId = containerId
        this.playlist = files
        this.currentIndex = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    }

    fun clearForContainer(containerId: String) {
        if (this.containerId != containerId) return
        this.containerId = ""
        this.playlist = emptyList()
        this.currentIndex = 0
    }
}
