package zip.arcanum.core.utils

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree(private val context: Context) : Timber.Tree() {

    private val logFile: File get() = File(context.filesDir, LOG_FILE_NAME)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG   -> "D"
            Log.INFO    -> "I"
            Log.WARN    -> "W"
            Log.ERROR   -> "E"
            Log.ASSERT  -> "A"
            else        -> "?"
        }
        val sb = StringBuilder()
        sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n')
        t?.let { sb.append(Log.getStackTraceString(it)).append('\n') }
        try {
            val file = logFile
            file.appendText(sb.toString(), Charsets.UTF_8)
            if (file.length() > MAX_BYTES) trimFile(file)
        } catch (_: Exception) {}
    }

    private fun trimFile(file: File) {
        try {
            val text = file.readText(Charsets.UTF_8)
            file.writeText(text.takeLast(TRIM_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun clear() { try { logFile.delete() } catch (_: Exception) {} }

    companion object {
        const val LOG_FILE_NAME = "arcanum_debug.log"
        private const val MAX_BYTES  = 2 * 1024 * 1024  // 2 MB
        private const val TRIM_BYTES = 1 * 1024 * 1024  // trim to last 1 MB
    }
}
