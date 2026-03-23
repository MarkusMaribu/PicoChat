package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.util.Constants
import kotlin.math.abs

enum class Tool { PENCIL, ERASER }

class PictoCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val canvasBitmap = Bitmap.createBitmap(
        Constants.CANVAS_W, Constants.CANVAS_H, Bitmap.Config.ARGB_8888
    ).apply { eraseColor(Color.TRANSPARENT) }

    private val bitmapCanvas = Canvas(canvasBitmap)

    private val renderPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    var tool: Tool = Tool.PENCIL
        set(value) {
            field = value
            onToolChanged?.invoke(value)
        }

    var onToolChanged: ((Tool) -> Unit)? = null

    var penSize: Int = 1
        set(value) {
            field = value
            onPenSizeChanged?.invoke(value)
        }

    var onPenSizeChanged: ((Int) -> Unit)? = null

    var onDrawStart: ((Tool) -> Unit)? = null
    var onDrawEnd: (() -> Unit)? = null

    private var prevLx = -1
    private var prevLy = -1
    private var hasDrawContent = false

    private val dstRect = RectF()

    // Clip path to prevent white corners from bleeding into the rounded outline
    private val clipPath = Path()
    private var clipRadius = 0f

    // Text rendering at canvas resolution (256x88)
    val textBuffer = StringBuilder()
    private var customTextStartX: Float = Float.NaN
    private var customTextStartY: Float = Float.NaN

    var usernameForLayout: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val textBitmap = Bitmap.createBitmap(
        Constants.CANVAS_W, Constants.CANVAS_H, Bitmap.Config.ARGB_8888
    ).apply { eraseColor(Color.TRANSPARENT) }

    private val textBitmapCanvas = Canvas(textBitmap)

    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
        typeface = ResourcesCompat.getFont(context, R.font.cozette_vector)
        textSize = TEXT_SIZE
    }

    private val emojiCellWidth: Float
    private val emojiCodepoints: Set<Int>

    init {
        val emojiChars = "😊😐😎💀✨☽🚀🎁✉🖩🕒🅐🅑🅧🅨🅻🆁✙♠♦♥♣❕❔+-✩◯⬦⬜△▽◉⭢⭠⭡⭣✬🌑⬥⬛▲▼⨉"
        val cpSet = mutableSetOf<Int>()
        var maxW = 0f
        var i = 0
        while (i < emojiChars.length) {
            val cp = emojiChars.codePointAt(i)
            cpSet.add(cp)
            val w = textPaint.measureText(String(Character.toChars(cp)) + VS15)
            if (w > maxW) maxW = w
            i += Character.charCount(cp)
        }
        emojiCodepoints = cpSet
        emojiCellWidth = maxW
    }

    private fun widthFor(str: String): Float {
        val cp = str.codePointAt(0)
        return if (cp in emojiCodepoints) emojiCellWidth else textPaint.measureText(str + VS15)
    }

    private val ruledLinePaint = Paint().apply {
        color = 0xFFE0E8E0.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    var ruledLineColor: Int
        get() = ruledLinePaint.color
        set(value) {
            ruledLinePaint.color = value
            invalidate()
        }

    private val lineHeight get() = Constants.CANVAS_H.toFloat() / LINE_COUNT

    private fun nameTagWidth(): Float {
        if (usernameForLayout.isEmpty()) return 0f
        return textPaint.measureText(usernameForLayout) + NAMETAG_H_PADDING * 2
    }

    private data class CharPos(val ch: String, val x: Float, val baseline: Float)

    private fun layoutText(): List<CharPos> {
        if (textBuffer.isEmpty()) return emptyList()
        val result = mutableListOf<CharPos>()
        val lh = lineHeight
        val ntw = nameTagWidth()
        val rightEdge = Constants.CANVAS_W.toFloat() - TEXT_MARGIN

        var lineIndex = 0
        var x: Float
        val firstBaseline: Float

        if (!customTextStartX.isNaN()) {
            x = customTextStartX
            firstBaseline = customTextStartY
        } else {
            x = ntw + TEXT_MARGIN
            firstBaseline = lh - textPaint.descent() - 1f
        }

        var i = 0
        while (i < textBuffer.length) {
            val cp = textBuffer.codePointAt(i)
            val charCount = Character.charCount(cp)
            val str = textBuffer.substring(i, i + charCount)
            i += charCount

            if (str == "\n") {
                lineIndex++
                x = TEXT_MARGIN
                continue
            }
            val cw = widthFor(str)
            if (x + cw > rightEdge) {
                lineIndex++
                x = TEXT_MARGIN
            }
            val baseline = firstBaseline + lineIndex * lh
            if (baseline > Constants.CANVAS_H) break
            result.add(CharPos(str, x, baseline))
            x += cw
        }
        return result
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipRadius = 4f * resources.displayMetrics.density
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            clipRadius, clipRadius, Path.Direction.CW
        )
    }

    private fun renderTextBitmap() {
        textBitmap.eraseColor(Color.TRANSPARENT)
        for (cp in layoutText()) {
            val codepoint = cp.ch.codePointAt(0)
            val drawX = if (codepoint in emojiCodepoints) {
                val glyphW = textPaint.measureText(cp.ch + VS15)
                cp.x + (emojiCellWidth - glyphW) / 2f
            } else {
                cp.x
            }
            textBitmapCanvas.drawText(cp.ch + VS15, drawX, cp.baseline, textPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clipPath)

        dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(Color.WHITE)

        val scaleY = height.toFloat() / Constants.CANVAS_H
        val lh = lineHeight

        for (i in 1 until LINE_COUNT) {
            val y = i * lh * scaleY
            canvas.drawLine(0f, y, width.toFloat(), y, ruledLinePaint)
        }

        canvas.drawBitmap(canvasBitmap, null, dstRect, renderPaint)

        renderTextBitmap()
        canvas.drawBitmap(textBitmap, null, dstRect, renderPaint)

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val lx = (event.x * Constants.CANVAS_W / width).toInt().coerceIn(0, Constants.CANVAS_W - 1)
        val ly = (event.y * Constants.CANVAS_H / height).toInt().coerceIn(0, Constants.CANVAS_H - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                prevLx = lx
                prevLy = ly
                applyTool(lx, ly)
                onDrawStart?.invoke(tool)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                bresenhamLine(prevLx, prevLy, lx, ly)
                prevLx = lx
                prevLy = ly
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                prevLx = -1
                prevLy = -1
                onDrawEnd?.invoke()
            }
        }
        return true
    }

    fun appendText(ch: String): Boolean {
        val prevLen = textBuffer.length
        textBuffer.append(ch)
        if (!textFitsCanvas()) {
            textBuffer.delete(prevLen, textBuffer.length)
            return false
        }
        invalidate()
        return true
    }

    private fun textFitsCanvas(): Boolean {
        if (textBuffer.isEmpty()) return true
        val lh = lineHeight
        val ntw = nameTagWidth()
        val rightEdge = Constants.CANVAS_W.toFloat() - TEXT_MARGIN

        var lineIndex = 0
        var x: Float
        val firstBaseline: Float

        if (!customTextStartX.isNaN()) {
            x = customTextStartX
            firstBaseline = customTextStartY
        } else {
            x = ntw + TEXT_MARGIN
            firstBaseline = lh - textPaint.descent() - 1f
        }

        var i = 0
        while (i < textBuffer.length) {
            val cp = textBuffer.codePointAt(i)
            val charCount = Character.charCount(cp)
            val str = textBuffer.substring(i, i + charCount)
            i += charCount

            if (str == "\n") {
                lineIndex++
                x = TEXT_MARGIN
                if (firstBaseline + lineIndex * lh > Constants.CANVAS_H) return false
                continue
            }
            val cw = widthFor(str)
            if (x + cw > rightEdge) {
                lineIndex++
                x = TEXT_MARGIN
            }
            if (firstBaseline + lineIndex * lh > Constants.CANVAS_H) return false
            x += cw
        }
        return true
    }

    fun placeSymbolAt(symbol: String, bitmapX: Int, bitmapY: Int) {
        if (textBuffer.isNotEmpty()) {
            renderTextBitmap()
            bitmapCanvas.drawBitmap(textBitmap, 0f, 0f, null)
            textBuffer.clear()
            hasDrawContent = true
        }

        val metrics = textPaint.fontMetrics
        customTextStartX = bitmapX - widthFor(symbol) / 2f
        customTextStartY = bitmapY - (metrics.ascent + metrics.descent) / 2f

        textBuffer.append(symbol)
        invalidate()
    }

    fun deleteLastChar() {
        if (textBuffer.isNotEmpty()) {
            val lastCp = textBuffer.codePointBefore(textBuffer.length)
            textBuffer.delete(textBuffer.length - Character.charCount(lastCp), textBuffer.length)
            invalidate()
        }
    }

    private fun applyTool(x: Int, y: Int) {
        when (tool) {
            Tool.PENCIL -> {
                val half = penSize / 2
                for (py in (y - half)..(y + half)) {
                    for (px in (x - half)..(x + half)) {
                        if (px in 0 until Constants.CANVAS_W && py in 0 until Constants.CANVAS_H) {
                            canvasBitmap.setPixel(px, py, Color.BLACK)
                        }
                    }
                }
                hasDrawContent = true
            }
            Tool.ERASER -> {
                val half = if (penSize == 3) 4 else 1
                for (ey in (y - half)..(y + half - 1)) {
                    for (ex in (x - half)..(x + half - 1)) {
                        if (ex in 0 until Constants.CANVAS_W && ey in 0 until Constants.CANVAS_H) {
                            canvasBitmap.setPixel(ex, ey, Color.TRANSPARENT)
                        }
                    }
                }
            }
        }
    }

    private fun bresenhamLine(x0: Int, y0: Int, x1: Int, y1: Int) {
        var cx = x0
        var cy = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy

        while (true) {
            applyTool(cx, cy)
            if (cx == x1 && cy == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                cx += sx
            }
            if (e2 <= dx) {
                err += dx
                cy += sy
            }
        }
    }

    fun clear() {
        canvasBitmap.eraseColor(Color.TRANSPARENT)
        textBuffer.clear()
        customTextStartX = Float.NaN
        customTextStartY = Float.NaN
        hasDrawContent = false
        invalidate()
    }

    fun hasDrawing(): Boolean = hasDrawContent || textBuffer.isNotEmpty()

    private fun getCompositeBitmap(): Bitmap {
        val composite = Bitmap.createBitmap(
            Constants.CANVAS_W, Constants.CANVAS_H, Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.TRANSPARENT) }
        val c = Canvas(composite)
        c.drawBitmap(canvasBitmap, 0f, 0f, null)
        renderTextBitmap()
        c.drawBitmap(textBitmap, 0f, 0f, null)
        return composite
    }

    fun getBitmap(): Bitmap = getCompositeBitmap()

    fun exportBits(): ByteArray {
        val composite = getCompositeBitmap()
        val bytes = ByteArray(Constants.DRAWING_BYTES)
        var byteIndex = 0
        var bitIndex = 0
        var current = 0

        for (y in 0 until Constants.CANVAS_H) {
            for (x in 0 until Constants.CANVAS_W) {
                val pixel = composite.getPixel(x, y)
                val isBlack = (pixel != Color.TRANSPARENT && Color.alpha(pixel) > 128)
                if (isBlack) {
                    current = current or (1 shl (7 - bitIndex))
                }
                bitIndex++
                if (bitIndex == 8) {
                    bytes[byteIndex] = current.toByte()
                    byteIndex++
                    bitIndex = 0
                    current = 0
                }
            }
        }
        composite.recycle()
        return bytes
    }

    fun importBits(data: ByteArray) {
        if (data.size < Constants.DRAWING_BYTES) return
        canvasBitmap.eraseColor(Color.TRANSPARENT)
        textBuffer.clear()
        customTextStartX = Float.NaN
        customTextStartY = Float.NaN
        var byteIndex = 0
        var bitIndex = 0

        for (y in 0 until Constants.CANVAS_H) {
            for (x in 0 until Constants.CANVAS_W) {
                val bit = (data[byteIndex].toInt() shr (7 - bitIndex)) and 1
                if (bit == 1) {
                    canvasBitmap.setPixel(x, y, Color.BLACK)
                }
                bitIndex++
                if (bitIndex == 8) {
                    byteIndex++
                    bitIndex = 0
                }
            }
        }
        hasDrawContent = true
        invalidate()
    }

    companion object {
        private const val VS15 = "\uFE0E"
        const val LINE_COUNT = 5
        const val TEXT_SIZE = 13f
        private const val TEXT_MARGIN = 4f
        const val NAMETAG_H_PADDING = 6f

        fun bitmapFromBits(data: ByteArray): Bitmap {
            val bmp = Bitmap.createBitmap(
                Constants.CANVAS_W, Constants.CANVAS_H, Bitmap.Config.ARGB_8888
            )
            if (data.size < Constants.DRAWING_BYTES) return bmp
            var byteIndex = 0
            var bitIndex = 0
            for (y in 0 until Constants.CANVAS_H) {
                for (x in 0 until Constants.CANVAS_W) {
                    val bit = (data[byteIndex].toInt() shr (7 - bitIndex)) and 1
                    if (bit == 1) {
                        bmp.setPixel(x, y, Color.BLACK)
                    }
                    bitIndex++
                    if (bitIndex == 8) {
                        byteIndex++
                        bitIndex = 0
                    }
                }
            }
            return bmp
        }
    }
}
