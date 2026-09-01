package zip.arcanum.arcanum.files.domain

import zip.arcanum.crypto.NativeFileInfo
import javax.inject.Inject
import javax.inject.Singleton

data class ClipboardItem(
    val sourceContainerId: String,
    val sourceHandle: Long,
    val sourcePath: String,
    val fileName: String,
    val isDirectory: Boolean,
    val isCut: Boolean = false,
    /*
     * What the item is, carried alongside isDirectory because a paste has to know
     * before it reads anything: a link cannot be copied by reading it, and a special
     * file cannot be copied at all (#168). The values are NativeFileInfo.KIND_*, and
     * the three link fields mean what they mean there. Everything defaults to an
     * ordinary file, so a clipboard filled from a filesystem without links - FAT,
     * exFAT - needs to say nothing.
     */
    val kind: Int = NativeFileInfo.KIND_REGULAR,
    val linkTarget: String? = null,
    val linkTargetIsDirectory: Boolean = false,
    val linkBroken: Boolean = false
) {
    val isSymlink: Boolean get() = kind == NativeFileInfo.KIND_SYMLINK
    val isSpecial: Boolean get() = kind == NativeFileInfo.KIND_SPECIAL

    /** Follows the link, exactly as NativeFileInfo.opensAsDirectory does. */
    val opensAsDirectory: Boolean
        get() = isDirectory || (isSymlink && linkTargetIsDirectory && !linkBroken)
}

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
