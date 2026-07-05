/*
 * Portions of this file are derived from the Gallery project by IacobIonut01.
 * https://github.com/IacobIonut01/Gallery
 *
 * Copyright 2023 IacobIonut01
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications: adapted for encrypted VeraCrypt containers, added OOM guards,
 * added applyColorMatrix, applyExifOrientation helpers. — Arcanum project, 2026.
 */

package zip.arcanum.arcanum.gallery.editor.adjustments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.core.graphics.createBitmap
import zip.arcanum.arcanum.gallery.editor.model.PathProperties

fun applyColorMatrix(src: Bitmap, matrix: FloatArray): Bitmap {
    val result = createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)).asAndroidColorFilter()
    }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return result
}

fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun Bitmap.flipHorizontal(): Bitmap {
    val matrix = Matrix().apply { postScale(-1f, 1f, width / 2f, height / 2f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun Bitmap.flipVertical(): Bitmap {
    val matrix = Matrix().apply { postScale(1f, -1f, width / 2f, height / 2f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun Bitmap.crop(left: Int, top: Int, width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(this, left.coerceAtLeast(0), top.coerceAtLeast(0),
        width.coerceAtMost(this.width - left.coerceAtLeast(0)),
        height.coerceAtMost(this.height - top.coerceAtLeast(0)))

fun applyMarkup(
    base: Bitmap,
    paths: List<Pair<Path, PathProperties>>
): Bitmap {
    val result = base.copy(base.config ?: Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    for ((path, props) in paths) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = props.color.hashCode()
            strokeWidth = props.strokeWidth
            alpha = (props.alpha * 255).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (props.isEraser) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        canvas.drawPath(path.asAndroidPath(), paint)
    }
    return result
}

fun applyVignette(src: Bitmap, strength: Float): Bitmap {
    if (strength <= 0f) return src
    val result = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val cx = src.width / 2f
    val cy = src.height / 2f
    val r = maxOf(cx, cy) * 1.3f
    val alpha = (strength * 200).toInt().coerceIn(0, 220)
    val shader = android.graphics.RadialGradient(
        cx, cy, r,
        intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(alpha, 0, 0, 0)),
        floatArrayOf(0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
    return result
}

fun applyPosterize(src: Bitmap, value: Float): Bitmap {
    if (value <= 0f) return src
    if (src.width.toLong() * src.height.toLong() > 16_000_000L) return src
    val levels = Math.round(2f + (1f - value) * 22f).coerceIn(2, 24)
    val step = 255f / (levels - 1)
    val lut = IntArray(256) { i -> (Math.round(i / step) * step).toInt().coerceIn(0, 255) }
    val pixels = IntArray(src.width * src.height)
    src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    for (idx in pixels.indices) {
        val c = pixels[idx]
        pixels[idx] = android.graphics.Color.argb(
            c ushr 24 and 0xFF,
            lut[c ushr 16 and 0xFF],
            lut[c ushr 8 and 0xFF],
            lut[c and 0xFF]
        )
    }
    val result = createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    return result
}

fun applyEdges(src: Bitmap, value: Float): Bitmap {
    if (value <= 0f) return src
    val w = src.width; val h = src.height
    if (w < 3 || h < 3) return src
    if (w.toLong() * h.toLong() > 16_000_000L) return src
    val srcPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)
    val lum = FloatArray(w * h) { i ->
        val c = srcPixels[i]
        0.299f * (c ushr 16 and 0xFF) + 0.587f * (c ushr 8 and 0xFF) + 0.114f * (c and 0xFF)
    }
    val out = IntArray(w * h)
    val blend = value.coerceIn(0f, 1f)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val gx = -lum[(y-1)*w+(x-1)] + lum[(y-1)*w+(x+1)] - 2*lum[y*w+(x-1)] + 2*lum[y*w+(x+1)] - lum[(y+1)*w+(x-1)] + lum[(y+1)*w+(x+1)]
            val gy = -lum[(y-1)*w+(x-1)] - 2*lum[(y-1)*w+x] - lum[(y-1)*w+(x+1)] + lum[(y+1)*w+(x-1)] + 2*lum[(y+1)*w+x] + lum[(y+1)*w+(x+1)]
            val edge = Math.sqrt((gx*gx + gy*gy).toDouble()).toFloat().coerceIn(0f, 255f)
            val src0 = srcPixels[y*w+x]
            val a = src0 ushr 24 and 0xFF
            val r0 = src0 ushr 16 and 0xFF; val g0 = src0 ushr 8 and 0xFF; val b0 = src0 and 0xFF
            out[y*w+x] = android.graphics.Color.argb(a,
                (r0 * (1-blend) + edge * blend).toInt().coerceIn(0,255),
                (g0 * (1-blend) + edge * blend).toInt().coerceIn(0,255),
                (b0 * (1-blend) + edge * blend).toInt().coerceIn(0,255))
        }
    }
    val result = createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    return result
}

fun applyBorders(src: Bitmap, value: Float, color: Int = android.graphics.Color.WHITE): Bitmap {
    if (value <= 0f) return src
    val thickness = (minOf(src.width, src.height) * value * 0.12f).toInt()
    if (thickness <= 0) return src
    val result = createBitmap(src.width + thickness * 2, src.height + thickness * 2, src.config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawColor(color)
    canvas.drawBitmap(src, thickness.toFloat(), thickness.toFloat(), null)
    return result
}
