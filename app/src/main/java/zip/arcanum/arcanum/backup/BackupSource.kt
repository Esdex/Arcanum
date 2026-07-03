package zip.arcanum.arcanum.backup

import android.content.Context
import android.net.Uri
import zip.arcanum.core.database.entities.ContainerEntity
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class BackupSource(
    private val context: Context,
    val container: ContainerEntity
) {
    val sizeBytes: Long = container.size

    val baseFileName: String = sanitizeFileName(
        when {
            container.name.isNotBlank() -> container.name
            container.path.isNotBlank() -> File(container.path).name
            else                        -> "container"
        }
    )

    fun open(offset: Long = 0L): InputStream {
        val stream = if (container.safUri.isNotBlank()) {
            context.contentResolver.openInputStream(Uri.parse(container.safUri))
                ?: error("Cannot open container URI")
        } else {
            FileInputStream(File(container.path))
        }
        if (offset > 0L) stream.skipFully(offset)
        return stream
    }

    companion object {
        fun sanitizeFileName(value: String): String {
            val sanitized = value
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .trim('.')
            return sanitized.ifBlank { "container" }
        }
    }
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else {
            if (read() == -1) error("Cannot seek to requested upload offset")
            remaining--
        }
    }
}

