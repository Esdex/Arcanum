package zip.arcanum.core.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class IntruderCapture(
    val file: File,
    val timestampMillis: Long
)

@Singleton
class IntruderCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {
    private val captureDir: File
        get() = File(context.noBackupFilesDir, "intruder_captures").also { it.mkdirs() }

    suspend fun captureBurstIfEnabled(photoCount: Int = 1) {
        if (!prefs.intruderDetectionEnabled.first()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        withContext(Dispatchers.Main) {
            runCatching {
                val provider = cameraProvider()
                val imageCapture = ImageCapture.Builder()
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageCapture
                )
                repeat(photoCount.coerceAtLeast(1)) {
                    captureOne(imageCapture)
                }
                provider.unbind(imageCapture)
            }
        }
    }

    fun listCaptures(): List<IntruderCapture> =
        captureDir.listFiles { file -> file.isFile && file.extension.equals("jpg", ignoreCase = true) }
            ?.map { file -> IntruderCapture(file, timestampFromName(file.name) ?: file.lastModified()) }
            ?.sortedByDescending { it.timestampMillis }
            .orEmpty()

    fun deleteCapture(file: File): Boolean =
        file.parentFile?.canonicalPath == captureDir.canonicalPath && file.delete()

    fun deleteAllCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                continuation.resume(future.get())
            }, ContextCompat.getMainExecutor(context))
        }

    private suspend fun captureOne(imageCapture: ImageCapture): Boolean =
        suspendCancellableCoroutine { continuation ->
            val file = nextCaptureFile()
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        continuation.resume(false)
                    }
                }
            )
        }

    private fun nextCaptureFile(): File {
        val stamp = SimpleDateFormat(FILE_STAMP, Locale.US).format(Date())
        return File(captureDir, "intruder_${stamp}_${UUID.randomUUID().toString().take(8)}.jpg")
    }

    private fun timestampFromName(name: String): Long? {
        val stamp = name.removePrefix("intruder_").substringBeforeLast("_")
        return runCatching { SimpleDateFormat(FILE_STAMP, Locale.US).parse(stamp)?.time }.getOrNull()
    }

    companion object {
        private const val FILE_STAMP = "yyyyMMdd_HHmmss_SSS"
    }
}
