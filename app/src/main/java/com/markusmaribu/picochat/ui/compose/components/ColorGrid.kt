package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.markusmaribu.picochat.ui.compose.core.AccelerateDecelerateEasing
import com.markusmaribu.picochat.util.ThemeColors

private const val COLS = 4

private fun cellRect(index: Int, size: Size, gapPx: Float): Rect {
    val cellW = (size.width - gapPx * (COLS + 1)) / COLS
    val cellH = (size.height - gapPx * (COLS + 1)) / COLS
    val cellSize = minOf(cellW, cellH)
    val totalW = cellSize * COLS + gapPx * (COLS + 1)
    val totalH = cellSize * COLS + gapPx * (COLS + 1)
    val offsetX = (size.width - totalW) / 2f
    val offsetY = (size.height - totalH) / 2f
    val col = index % COLS
    val row = index / COLS
    val cx = offsetX + gapPx + col * (cellSize + gapPx)
    val cy = offsetY + gapPx + row * (cellSize + gapPx)
    val haloInset = -gapPx * 0.4f
    return Rect(
        cx + haloInset, cy + haloInset,
        cx + cellSize - haloInset, cy + cellSize - haloInset
    )
}

private fun hitTest(x: Float, y: Float, size: Size, gapPx: Float): Int {
    val cellW = (size.width - gapPx * (COLS + 1)) / COLS
    val cellH = (size.height - gapPx * (COLS + 1)) / COLS
    val cellSize = minOf(cellW, cellH)
    val totalW = cellSize * COLS + gapPx * (COLS + 1)
    val totalH = cellSize * COLS + gapPx * (COLS + 1)
    val offsetX = (size.width - totalW) / 2f
    val offsetY = (size.height - totalH) / 2f
    for (i in ThemeColors.PALETTE.indices) {
        val col = i % COLS
        val row = i / COLS
        val cx = offsetX + gapPx + col * (cellSize + gapPx)
        val cy = offsetY + gapPx + row * (cellSize + gapPx)
        if (x in cx..(cx + cellSize) && y in cy..(cy + cellSize)) return i
    }
    return -1
}

/**
 * 4x4 theme color picker (port of ColorGridView): color squares with a
 * dashed halo around the selection that animates between cells (150ms
 * accelerate-decelerate, like the old ValueAnimator).
 */
@Composable
fun ColorGrid(
    selectedIndex: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val onSelected by rememberUpdatedState(onColorSelected)
    val gapPx = with(LocalDensity.current) { 4.dp.toPx() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val haloRect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var haloInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex, canvasSize, gapPx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val target = cellRect(
            selectedIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex),
            Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
            gapPx
        )
        if (!haloInitialized) {
            haloRect.snapTo(target)
            haloInitialized = true
        } else {
            haloRect.animateTo(target, tween(150, easing = AccelerateDecelerateEasing))
        }
    }

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(gapPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sizeF = Size(size.width.toFloat(), size.height.toFloat())
                    var idx = hitTest(down.position.x, down.position.y, sizeF, gapPx)
                    if (idx >= 0) onSelected(idx)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val moved = hitTest(change.position.x, change.position.y, sizeF, gapPx)
                        if (moved >= 0 && moved != idx) {
                            idx = moved
                            onSelected(idx)
                        }
                    }
                }
            }
    ) {
        val cellW = (size.width - gapPx * (COLS + 1)) / COLS
        val cellH = (size.height - gapPx * (COLS + 1)) / COLS
        val cellSize = minOf(cellW, cellH)
        val totalW = cellSize * COLS + gapPx * (COLS + 1)
        val totalH = cellSize * COLS + gapPx * (COLS + 1)
        val offsetX = (size.width - totalW) / 2f
        val offsetY = (size.height - totalH) / 2f

        val squareScale = 0.60f
        val squareSize = cellSize * squareScale
        val squareInset = (cellSize - squareSize) / 2f

        for (i in ThemeColors.PALETTE.indices) {
            val col = i % COLS
            val row = i / COLS
            val cx = offsetX + gapPx + col * (cellSize + gapPx)
            val cy = offsetY + gapPx + row * (cellSize + gapPx)
            drawRect(
                color = Color(ThemeColors.PALETTE[i]),
                topLeft = Offset(cx + squareInset, cy + squareInset),
                size = Size(squareSize, squareSize)
            )
        }

        if (haloInitialized) {
            val halo = haloRect.value
            val dashLen = 4.dp.toPx()
            drawRect(
                color = Color(
                    ThemeColors.PALETTE[selectedIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)]
                ),
                topLeft = halo.topLeft,
                size = halo.size,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, dashLen), 0f)
                )
            )
        }
    }
}
