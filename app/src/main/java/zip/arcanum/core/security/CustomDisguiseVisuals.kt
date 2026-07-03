package zip.arcanum.core.security

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

object CustomDisguiseVisuals {
    fun createIconBitmap(
        icon: CustomDisguiseIcon,
        backgroundColor: Int,
        sizePx: Int = 144
    ): Bitmap {
        val size = sizePx.coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
        val radius = size * 0.24f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, bgPaint)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = size * 0.065f
            style = Paint.Style.STROKE
        }
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        val c = size / 2f

        when (icon) {
            CustomDisguiseIcon.WIFI -> drawWifi(canvas, paint, fill, c, size)
            CustomDisguiseIcon.EMAIL -> drawEnvelope(canvas, paint, size)
            CustomDisguiseIcon.CAMERA -> drawCamera(canvas, paint, fill, size)
            CustomDisguiseIcon.SETTINGS, CustomDisguiseIcon.BUILD -> drawGear(canvas, paint, fill, c, size)
            CustomDisguiseIcon.TIMER, CustomDisguiseIcon.ALARM -> drawClock(canvas, paint, c, size)
            CustomDisguiseIcon.CALCULATOR -> drawCalculator(canvas, paint, fill, size)
            CustomDisguiseIcon.LIGHT -> drawBulb(canvas, paint, fill, c, size)
            CustomDisguiseIcon.PHONE -> drawPhone(canvas, paint, size)
            CustomDisguiseIcon.CLOUD -> drawCloud(canvas, paint, size)
            CustomDisguiseIcon.APPS, CustomDisguiseIcon.DASHBOARD -> drawGrid(canvas, fill, size)
            else -> drawFallbackText(canvas, icon.name.take(2), size)
        }
        return bitmap
    }

    private fun drawWifi(canvas: Canvas, paint: Paint, fill: Paint, c: Float, size: Int) {
        val baseY = size * 0.66f
        listOf(0.56f, 0.38f, 0.20f).forEach { radius ->
            val r = size * radius
            canvas.drawArc(RectF(c - r, baseY - r, c + r, baseY + r), 220f, 100f, false, paint)
        }
        canvas.drawCircle(c, size * 0.72f, size * 0.045f, fill)
    }

    private fun drawEnvelope(canvas: Canvas, paint: Paint, size: Int) {
        val rect = RectF(size * 0.22f, size * 0.32f, size * 0.78f, size * 0.68f)
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, paint)
        canvas.drawLine(rect.left, rect.top, size * 0.5f, size * 0.54f, paint)
        canvas.drawLine(rect.right, rect.top, size * 0.5f, size * 0.54f, paint)
    }

    private fun drawCamera(canvas: Canvas, paint: Paint, fill: Paint, size: Int) {
        val rect = RectF(size * 0.24f, size * 0.34f, size * 0.76f, size * 0.70f)
        canvas.drawRoundRect(rect, size * 0.07f, size * 0.07f, paint)
        canvas.drawCircle(size * 0.5f, size * 0.52f, size * 0.105f, paint)
        canvas.drawCircle(size * 0.69f, size * 0.43f, size * 0.025f, fill)
        canvas.drawLine(size * 0.34f, size * 0.34f, size * 0.40f, size * 0.25f, paint)
        canvas.drawLine(size * 0.40f, size * 0.25f, size * 0.56f, size * 0.25f, paint)
        canvas.drawLine(size * 0.56f, size * 0.25f, size * 0.62f, size * 0.34f, paint)
    }

    private fun drawGear(canvas: Canvas, paint: Paint, fill: Paint, c: Float, size: Int) {
        val inner = size * 0.13f
        val outer = size * 0.29f
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45).toDouble())
            val x1 = c + cos(angle).toFloat() * (outer * 0.82f)
            val y1 = c + sin(angle).toFloat() * (outer * 0.82f)
            val x2 = c + cos(angle).toFloat() * outer
            val y2 = c + sin(angle).toFloat() * outer
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        canvas.drawCircle(c, c, outer * 0.72f, paint)
        canvas.drawCircle(c, c, inner, fill)
    }

    private fun drawClock(canvas: Canvas, paint: Paint, c: Float, size: Int) {
        canvas.drawCircle(c, c, size * 0.28f, paint)
        canvas.drawLine(c, c, c, size * 0.34f, paint)
        canvas.drawLine(c, c, size * 0.62f, size * 0.56f, paint)
    }

    private fun drawCalculator(canvas: Canvas, paint: Paint, fill: Paint, size: Int) {
        val rect = RectF(size * 0.30f, size * 0.22f, size * 0.70f, size * 0.78f)
        canvas.drawRoundRect(rect, size * 0.05f, size * 0.05f, paint)
        canvas.drawRoundRect(RectF(size * 0.37f, size * 0.30f, size * 0.63f, size * 0.39f), size * 0.02f, size * 0.02f, fill)
        val dot = size * 0.035f
        for (row in 0..2) for (col in 0..2) {
            canvas.drawCircle(size * (0.39f + col * 0.11f), size * (0.50f + row * 0.09f), dot, fill)
        }
    }

    private fun drawBulb(canvas: Canvas, paint: Paint, fill: Paint, c: Float, size: Int) {
        canvas.drawCircle(c, size * 0.43f, size * 0.17f, paint)
        canvas.drawLine(size * 0.40f, size * 0.59f, size * 0.60f, size * 0.59f, paint)
        canvas.drawLine(size * 0.42f, size * 0.68f, size * 0.58f, size * 0.68f, paint)
        canvas.drawCircle(c, size * 0.43f, size * 0.035f, fill)
    }

    private fun drawPhone(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawRoundRect(RectF(size * 0.34f, size * 0.18f, size * 0.66f, size * 0.82f), size * 0.06f, size * 0.06f, paint)
        canvas.drawLine(size * 0.44f, size * 0.72f, size * 0.56f, size * 0.72f, paint)
    }

    private fun drawCloud(canvas: Canvas, paint: Paint, size: Int) {
        canvas.drawArc(RectF(size * 0.20f, size * 0.43f, size * 0.42f, size * 0.66f), 180f, 180f, false, paint)
        canvas.drawArc(RectF(size * 0.35f, size * 0.31f, size * 0.62f, size * 0.64f), 190f, 210f, false, paint)
        canvas.drawArc(RectF(size * 0.55f, size * 0.42f, size * 0.80f, size * 0.67f), 200f, 170f, false, paint)
        canvas.drawLine(size * 0.31f, size * 0.66f, size * 0.70f, size * 0.66f, paint)
    }

    private fun drawGrid(canvas: Canvas, fill: Paint, size: Int) {
        val cell = size * 0.14f
        val gap = size * 0.08f
        val start = (size - cell * 2 - gap) / 2f
        for (row in 0..1) for (col in 0..1) {
            val left = start + col * (cell + gap)
            val top = start + row * (cell + gap)
            canvas.drawRoundRect(RectF(left, top, left + cell, top + cell), size * 0.025f, size * 0.025f, fill)
        }
    }

    private fun drawFallbackText(canvas: Canvas, text: String, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.30f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text.uppercase(), size / 2f, baseline, paint)
    }

}
