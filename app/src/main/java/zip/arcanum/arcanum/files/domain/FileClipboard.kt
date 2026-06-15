package zip.arcanum.arcanum.files.domain

import javax.inject.Inject
import javax.inject.Singleton

data class ClipboardItem(
    val sourceContainerId: String,
    val sourceHandle: Long,
    val sourcePath: String,
    val fileName: String,
    val isDirectory: Boolean,
    val isCut: Boolean = false
)

@Singleton
class FileClipboard @Inject constructor() {
    private val _items = mutableListOf<ClipboardItem>()
    val items: List<ClipboardItem> get() = _items.toList()
    val hasItems: Boolean get() = _items.isNotEmpty()
    val isCut: Boolean get() = _items.firstOrNull()?.isCut ?: false
    val count: Int get() = _items.size

    fun copy(items: List<ClipboardItem>) {
        _items.clear()
        _items.addAll(items.map { it.copy(isCut = false) })
    }

    fun cut(items: List<ClipboardItem>) {
        _items.clear()
        _items.addAll(items.map { it.copy(isCut = true) })
    }

    fun clear() { _items.clear() }

    fun clearForContainer(containerId: String) {
        _items.removeAll { it.sourceContainerId == containerId }
    }
}
