package com.markusmaribu.picochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.markusmaribu.picochat.util.ThemeColors

class ColorGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var selectedIndex: Int = ThemeColors.DEFAULT_INDEX
        set(value) {
            val old = field
            field = value.coerceIn(0, ThemeColors.PALETTE.size - 1)
            if (old != field) animateHalo()
            else invalidate()
        }

    var onColorSelected: ((Int) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val rect = RectF()
    private val haloRect = RectF()
    private val haloTargetRect = RectF()
    private val haloStartRect = RectF()
    private var haloAnimator: ValueAnimator? = null
    private var haloInitialized = false

    private fun cellRect(index: Int): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val cols = 4
        val gap = 4f * resources.displayMetrics.density
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - gap * (cols + 1)) / cols
        val cellSize = minOf(cellW, cellH)
        val totalW = cellSize * cols + gap * (cols + 1)
        val totalH = cellSize * cols + gap * (cols + 1)
        val offsetX = (w - totalW) / 2f
        val offsetY = (h - totalH) / 2f
        val col = index % cols
        val row = index / cols
        val cx = offsetX + gap + col * (cellSize + gap)
        val cy = offsetY + gap + row * (cellSize + gap)
        val haloInset = -gap * 0.4f
        return RectF(cx + haloInset, cy + haloInset, cx + cellSize - haloInset, cy + cellSize - haloInset)
    }

    private fun animateHalo() {
        val target = cellRect(selectedIndex)
        if (!haloInitialized || width <= 0) {
            haloRect.set(target)
            haloTargetRect.set(target)
            haloInitialized = true
            invalidate()
            return
        }
        haloAnimator?.cancel()
        haloStartRect.set(haloRect)
        haloTargetRect.set(target)
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                haloRect.left = haloStartRect.left + (haloTargetRect.left - haloStartRect.left) * t
                haloRect.top = haloStartRect.top + (haloTargetRect.top - haloStartRect.top) * t
                haloRect.right = haloStartRect.right + (haloTargetRect.right - haloStartRect.right) * t
                haloRect.bottom = haloStartRect.bottom + (haloTargetRect.bottom - haloStartRect.bottom) * t
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val target = cellRect(selectedIndex)
            haloRect.set(target)
            haloTargetRect.set(target)
            haloInitialized = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cols = 4
        val gap = 4f * resources.displayMetrics.density
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - gap * (cols + 1)) / cols
        val cellSize = minOf(cellW, cellH)

        val totalW = cellSize * cols + gap * (cols + 1)
        val totalH = cellSize * cols + gap * (cols + 1)
        val offsetX = (w - totalW) / 2f
        val offsetY = (h - totalH) / 2f

        val squareScale = 0.60f
        val squareSize = cellSize * squareScale
        val squareInset = (cellSize - squareSize) / 2f

        for (i in ThemeColors.PALETTE.indices) {
            val col = i % cols
            val row = i / cols
            val cx = offsetX + gap + col * (cellSize + gap)
            val cy = offsetY + gap + row * (cellSize + gap)

            rect.set(cx + squareInset, cy + squareInset, cx + squareInset + squareSize, cy + squareInset + squareSize)
            fillPaint.color = ThemeColors.PALETTE[i]
            canvas.drawRect(rect, fillPaint)
        }

        if (haloInitialized) {
            haloPaint.color = ThemeColors.PALETTE[selectedIndex]
            val dashLen = 4f * resources.displayMetrics.density
            haloPaint.pathEffect = DashPathEffect(floatArrayOf(dashLen, dashLen), 0f)
            haloPaint.strokeWidth = 2.5f * resources.displayMetrics.density
            canvas.drawRect(haloRect, haloPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val idx = hitTest(event.x, event.y)
            if (idx >= 0 && idx != selectedIndex) {
                selectedIndex = idx
                onColorSelected?.invoke(idx)
            }
        }
        return true
    }

    private fun hitTest(touchX: Float, touchY: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return -1

        val cols = 4
        val gap = 4f * resources.displayMetrics.density
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - gap * (cols + 1)) / cols
        val cellSize = minOf(cellW, cellH)

        val totalW = cellSize * cols + gap * (cols + 1)
        val totalH = cellSize * cols + gap * (cols + 1)
        val offsetX = (w - totalW) / 2f
        val offsetY = (h - totalH) / 2f

        for (i in ThemeColors.PALETTE.indices) {
            val col = i % cols
            val row = i / cols
            val x = offsetX + gap + col * (cellSize + gap)
            val y = offsetY + gap + row * (cellSize + gap)
            if (touchX in x..(x + cellSize) && touchY in y..(y + cellSize)) {
                return i
            }
        }
        return -1
    }
}
