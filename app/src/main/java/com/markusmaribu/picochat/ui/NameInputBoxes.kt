package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.markusmaribu.picochat.R

class NameInputBoxes @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxChars = 10

    var text: String = ""
        set(value) {
            val cpCount = value.codePointCount(0, value.length)
            field = if (cpCount <= maxChars) value
                    else value.substring(0, value.offsetByCodePoints(0, maxChars))
            val fieldCps = field.codePointCount(0, field.length)
            cursorIndex = fieldCps.coerceAtMost(maxChars - 1)
            invalidate()
        }

    var cursorIndex: Int = 0
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202020.toInt()
        style = Paint.Style.FILL
    }
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDD8800.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF606060.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.cozette_vector)
    }

    private val boxRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0 || w <= 0) return

        val boxSize = (w / maxChars).coerceAtMost(h * 0.85f)
        val totalWidth = boxSize * maxChars
        val startX = (w - totalWidth) / 2f
        val startY = (h - boxSize) / 2f

        textPaint.textSize = boxSize * 0.55f

        val cpCount = text.codePointCount(0, text.length)

        for (i in 0 until maxChars) {
            val x = startX + i * boxSize
            boxRect.set(x, startY, x + boxSize, startY + boxSize)

            val fill = if (i == cursorIndex) activeFillPaint else fillPaint
            canvas.drawRect(boxRect, fill)
            canvas.drawRect(boxRect, strokePaint)

            if (i < cpCount) {
                val cpStart = text.offsetByCodePoints(0, i)
                val cp = text.codePointAt(cpStart)
                val ch = String(Character.toChars(cp))
                val textY = boxRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(ch, boxRect.centerX(), textY, textPaint)
            }
        }
    }

    fun appendChar(ch: String): Boolean {
        if (text.codePointCount(0, text.length) < maxChars) {
            text = text + ch
            return true
        }
        return false
    }

    fun deleteChar(): Boolean {
        if (text.isNotEmpty()) {
            val cpCount = text.codePointCount(0, text.length)
            text = if (cpCount <= 1) ""
                   else text.substring(0, text.offsetByCodePoints(0, cpCount - 1))
            return true
        }
        return false
    }

    fun clear() {
        text = ""
    }
}
