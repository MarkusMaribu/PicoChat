package com.markusmaribu.picochat.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.markusmaribu.picochat.util.Constants

class RainbowBitmapDrawable(
    private val baseBitmap: Bitmap,
    private val rainbowBits: ByteArray
) : Drawable() {

    private val w = Constants.CANVAS_W
    private val h = Constants.CANVAS_H
    private val pixels = IntArray(w * h)
    private val compositeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val drawPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val hsv = floatArrayOf(0f, 1f, 0.85f)
    private var running = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (callback == null) { running = false; return }
            updateComposite()
            invalidateSelf()
            if (running) scheduleSelf(this, SystemClock.uptimeMillis() + 33)
        }
    }

    init {
        updateComposite()
    }

    private fun updateComposite() {
        val phase = (SystemClock.uptimeMillis() % 3000L) / 3000f * 360f
        baseBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var byteIdx = 0
        var bitIdx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val bit = (rainbowBits[byteIdx].toInt() shr (7 - bitIdx)) and 1
                if (bit == 1) {
                    hsv[0] = ((x.toFloat() / w * 360f) + phase) % 360f
                    pixels[y * w + x] = Color.HSVToColor(hsv)
                }
                bitIdx++
                if (bitIdx == 8) { byteIdx++; bitIdx = 0 }
            }
        }
        compositeBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    override fun draw(canvas: Canvas) {
        if (!running && callback != null) {
            running = true
            scheduleSelf(frameRunnable, SystemClock.uptimeMillis() + 33)
        }
        canvas.drawBitmap(compositeBitmap, null, bounds, drawPaint)
    }

    override fun setAlpha(alpha: Int) { drawPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { drawPaint.colorFilter = cf }
    @Deprecated("Deprecated")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            running = false
            unscheduleSelf(frameRunnable)
        }
        return changed
    }
}
