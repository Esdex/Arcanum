package zip.arcanum.arcanum.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

data class DecodedBitmap(
    val bitmap: Bitmap,
    val orientationAppliedByDecoder: Boolean
)

object ImageBitmapDecoder {
    fun isHeif(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in setOf("heic", "heif")

    fun decode(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        preferImageDecoder: Boolean = false
    ): DecodedBitmap? {
        if (preferImageDecoder) {
            decodeWithImageDecoder(bytes, maxWidth, maxHeight)?.let {
                return DecodedBitmap(it, orientationAppliedByDecoder = true)
            }
        }

        decodeWithBitmapFactory(bytes, maxWidth, maxHeight)?.let {
            return DecodedBitmap(it, orientationAppliedByDecoder = false)
        }

        return decodeWithImageDecoder(bytes, maxWidth, maxHeight)?.let {
            DecodedBitmap(it, orientationAppliedByDecoder = true)
        }
    }

    private fun decodeWithImageDecoder(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            decoder.setTargetSampleSize(
                calculateInSampleSize(info.size.width, info.size.height, maxWidth, maxHeight)
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun decodeWithBitmapFactory(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            opts.inJustDecodeBounds = false
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxWidth, maxHeight)
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var size = 1
        while (width / size > reqWidth || height / size > reqHeight) size *= 2
        return size.coerceAtLeast(1)
    }
}
