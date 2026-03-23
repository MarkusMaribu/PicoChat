package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.util.ThemeColors

class ScrollBarVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val activeColors = mutableListOf<Int>()
    private var visibleFirst = -1
    private var visibleLast = -1
    private var stickyWinStart = -1
    var includeBanner = false

    private val lineHeightPx: Float
    private val lineGapPx: Float

    init {
        val density = resources.displayMetrics.density
        lineHeightPx = 4f * density
        lineGapPx = 4f * density
    }

    fun setMessages(messages: List<ChatMessage>) {
        activeColors.clear()
        if (includeBanner) activeColors.add(COLOR_SYSTEM)
        for (msg in messages) {
            activeColors.add(colorForMessage(msg))
        }
        stickyWinStart = -1
        invalidate()
    }

    fun addMessage(message: ChatMessage) {
        activeColors.add(colorForMessage(message))
        invalidate()
    }

    fun setVisibleRange(firstVisible: Int, lastVisible: Int) {
        if (firstVisible == visibleFirst && lastVisible == visibleLast) return
        visibleFirst = firstVisible
        visibleLast = lastVisible
        invalidate()
    }

    private fun colorForMessage(msg: ChatMessage): Int = when (msg) {
        is ChatMessage.SystemMessage -> COLOR_SYSTEM
        else -> ThemeColors.PALETTE[msg.colorIndex.coerceIn(0, ThemeColors.PALETTE.size - 1)]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeColors.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val step = lineHeightPx + lineGapPx
        val maxSlots = ((h + lineGapPx) / step).toInt()
        val count = activeColors.size
        val contentSlots = (maxSlots - 2 * TAPER_SLOTS).coerceAtLeast(1)

        if (count <= contentSlots) {
            stickyWinStart = -1
            for (i in 0 until count) {
                val k = TAPER_SLOTS + (count - 1 - i)
                val y = h - lineHeightPx - k * step
                paint.color = if (i in visibleFirst..visibleLast) activeColors[i] else COLOR_INACTIVE
                canvas.drawRect(0f, y, w, y + lineHeightPx, paint)
            }
            return
        }

        // --- Overflow: sticky-edge windowing ---

        var winStart = if (stickyWinStart < 0) count - contentSlots else stickyWinStart
        winStart = winStart.coerceIn(0, count - contentSlots)

        if (visibleFirst >= 0) {
            if (visibleFirst < winStart) {
                winStart = visibleFirst
            }
            if (visibleLast > winStart + contentSlots - 1) {
                winStart = visibleLast - contentSlots + 1
            }
            winStart = winStart.coerceIn(0, count - contentSlots)
        }

        stickyWinStart = winStart
        val winEnd = winStart + contentSlots - 1

        val aboveWindow = winStart
        val belowWindow = count - 1 - winEnd

        // --- Content area ---
        for (c in 0 until contentSlots) {
            val msgIdx = winEnd - c
            val k = TAPER_SLOTS + c
            val y = h - lineHeightPx - k * step
            paint.color = if (msgIdx in visibleFirst..visibleLast) activeColors[msgIdx] else COLOR_INACTIVE
            canvas.drawRect(0f, y, w, y + lineHeightPx, paint)
        }

        // --- Bottom taper zone ---
        if (belowWindow > 0) {
            for (s in 0 until TAPER_SLOTS) {
                val msgIdx = winEnd + TAPER_SLOTS - s
                if (msgIdx >= count) continue
                val y = h - lineHeightPx - s * step
                val fraction = (s + 1).toFloat() / (TAPER_SLOTS + 1)
                val lineW = w * fraction
                val xStart = (w - lineW) / 2f
                paint.color = COLOR_INACTIVE
                canvas.drawRect(xStart, y, xStart + lineW, y + lineHeightPx, paint)
            }
        }

        // --- Top taper zone ---
        if (aboveWindow > 0) {
            for (s in 0 until TAPER_SLOTS) {
                val msgIdx = winStart - TAPER_SLOTS + s
                if (msgIdx < 0) continue
                val k = maxSlots - 1 - s
                val y = h - lineHeightPx - k * step
                val fraction = (s + 1).toFloat() / (TAPER_SLOTS + 1)
                val lineW = w * fraction
                val xStart = (w - lineW) / 2f
                paint.color = COLOR_INACTIVE
                canvas.drawRect(xStart, y, xStart + lineW, y + lineHeightPx, paint)
            }
        }
    }

    companion object {
        private const val COLOR_SYSTEM = 0xFF505050.toInt()
        private const val COLOR_INACTIVE = 0xFFCCCCCC.toInt()
        private const val TAPER_SLOTS = 3
    }
}
