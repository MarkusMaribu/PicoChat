package com.markusmaribu.picochat.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class StripedDrawable(
    private val bgColor: Int,
    private val lineColor: Int,
    private val lineSpacingPx: Float = 4f,
    private val lineWidthPx: Float = 1f
) : Drawable() {

    private val bgPaint = Paint().apply { color = bgColor; style = Paint.Style.FILL }
    private val linePaint = Paint().apply { color = lineColor; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, bgPaint)

        val step = lineSpacingPx + lineWidthPx
        var y = b.top.toFloat()
        while (y < b.bottom) {
            canvas.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + lineWidthPx, linePaint)
            y += step
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        linePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
