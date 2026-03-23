package com.markusmaribu.picochat.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

class CornerBracketDrawable(
    bracketColor: Int,
    private val strokeWidth: Float,
    private val armFraction: Float = 0.14f,
    private val outlineColor: Int = Color.WHITE,
    private val outlineWidth: Float = 0f,
    private val expandH: Float = 0f,
    private val expandV: Float = 0f
) : Drawable() {

    private val paint = Paint().apply {
        color = bracketColor
        this.strokeWidth = this@CornerBracketDrawable.strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = false
    }

    private val outlinePaint = Paint().apply {
        color = outlineColor
        this.strokeWidth = this@CornerBracketDrawable.strokeWidth + outlineWidth * 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = false
    }

    private val hasOutline = outlineWidth > 0f

    override fun draw(canvas: Canvas) {
        val r = bounds
        val w = r.width().toFloat() + expandH * 2f
        val h = r.height().toFloat() + expandV * 2f
        val armLen = min(w, h) * armFraction

        val outerInset = if (hasOutline) outlinePaint.strokeWidth / 2f else strokeWidth / 2f

        val l = r.left - expandH + outerInset
        val t = r.top - expandV + outerInset
        val ri = r.right + expandH - outerInset
        val b = r.bottom + expandV - outerInset

        if (hasOutline) {
            drawCorners(canvas, l, t, ri, b, armLen, outlinePaint)
        }
        drawCorners(canvas, l, t, ri, b, armLen, paint)
    }

    private fun drawCorners(
        canvas: Canvas, l: Float, t: Float, ri: Float, b: Float,
        armLen: Float, p: Paint
    ) {
        canvas.drawLine(l, t, l + armLen, t, p)
        canvas.drawLine(l, t, l, t + armLen, p)

        canvas.drawLine(ri - armLen, t, ri, t, p)
        canvas.drawLine(ri, t, ri, t + armLen, p)

        canvas.drawLine(l, b, l + armLen, b, p)
        canvas.drawLine(l, b - armLen, l, b, p)

        canvas.drawLine(ri - armLen, b, ri, b, p)
        canvas.drawLine(ri, b - armLen, ri, b, p)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        outlinePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        outlinePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
