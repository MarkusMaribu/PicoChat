package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.util.ThemeColors

private val COLOR_SYSTEM = Color(0xFF505050)
private val COLOR_INACTIVE = Color(0xFFCCCCCC)
private const val TAPER_SLOTS = 3

private fun colorForMessage(msg: ChatMessage): Color = when (msg) {
    is ChatMessage.SystemMessage -> COLOR_SYSTEM
    else -> Color(ThemeColors.PALETTE[msg.colorIndex.coerceIn(0, ThemeColors.PALETTE.size - 1)])
}

/** Sticky-edge window memory carried across draws (matches the old view's
 *  `stickyWinStart` field). */
class ScrollBarWindowState {
    var stickyWinStart: Int = -1
}

/**
 * Colored-line scroll visualizer (port of ScrollBarVisualizerView): one line
 * per message from the bottom up, colored when within the visible range,
 * with tapered overflow indicators at both ends.
 *
 * [visibleFirst]/[visibleLast] are indices into the same list the lines are
 * built from (banner at index 0 when [includeBanner] is true, matching the
 * old adapter positions).
 */
@Composable
fun ScrollBarVisualizer(
    messages: List<ChatMessage>,
    visibleFirst: Int,
    visibleLast: Int,
    modifier: Modifier = Modifier,
    includeBanner: Boolean = false
) {
    val windowState = remember { ScrollBarWindowState() }

    val activeColors = remember(messages, includeBanner) {
        buildList {
            if (includeBanner) add(COLOR_SYSTEM)
            messages.forEach { add(colorForMessage(it)) }
        }
    }

    Canvas(modifier) {
        if (activeColors.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val lineHeightPx = 4.dp.toPx()
        val lineGapPx = 4.dp.toPx()
        val step = lineHeightPx + lineGapPx
        val maxSlots = ((h + lineGapPx) / step).toInt()
        val count = activeColors.size
        val contentSlots = (maxSlots - 2 * TAPER_SLOTS).coerceAtLeast(1)

        fun drawLine(y: Float, color: Color, xStart: Float = 0f, lineW: Float = w) {
            drawRect(color, Offset(xStart, y), Size(lineW, lineHeightPx))
        }

        if (count <= contentSlots) {
            windowState.stickyWinStart = -1
            for (i in 0 until count) {
                val k = TAPER_SLOTS + (count - 1 - i)
                val y = h - lineHeightPx - k * step
                val color =
                    if (i in visibleFirst..visibleLast) activeColors[i] else COLOR_INACTIVE
                drawLine(y, color)
            }
            return@Canvas
        }

        // --- Overflow: sticky-edge windowing ---
        var winStart =
            if (windowState.stickyWinStart < 0) count - contentSlots
            else windowState.stickyWinStart
        winStart = winStart.coerceIn(0, count - contentSlots)

        if (visibleFirst >= 0) {
            if (visibleFirst < winStart) winStart = visibleFirst
            if (visibleLast > winStart + contentSlots - 1) {
                winStart = visibleLast - contentSlots + 1
            }
            winStart = winStart.coerceIn(0, count - contentSlots)
        }

        windowState.stickyWinStart = winStart
        val winEnd = winStart + contentSlots - 1

        val aboveWindow = winStart
        val belowWindow = count - 1 - winEnd

        for (c in 0 until contentSlots) {
            val msgIdx = winEnd - c
            val k = TAPER_SLOTS + c
            val y = h - lineHeightPx - k * step
            val color =
                if (msgIdx in visibleFirst..visibleLast) activeColors[msgIdx] else COLOR_INACTIVE
            drawLine(y, color)
        }

        if (belowWindow > 0) {
            for (s in 0 until TAPER_SLOTS) {
                val msgIdx = winEnd + TAPER_SLOTS - s
                if (msgIdx >= count) continue
                val y = h - lineHeightPx - s * step
                val fraction = (s + 1).toFloat() / (TAPER_SLOTS + 1)
                val lineW = w * fraction
                drawLine(y, COLOR_INACTIVE, (w - lineW) / 2f, lineW)
            }
        }

        if (aboveWindow > 0) {
            for (s in 0 until TAPER_SLOTS) {
                val msgIdx = winStart - TAPER_SLOTS + s
                if (msgIdx < 0) continue
                val k = maxSlots - 1 - s
                val y = h - lineHeightPx - k * step
                val fraction = (s + 1).toFloat() / (TAPER_SLOTS + 1)
                val lineW = w * fraction
                drawLine(y, COLOR_INACTIVE, (w - lineW) / 2f, lineW)
            }
        }
    }
}
