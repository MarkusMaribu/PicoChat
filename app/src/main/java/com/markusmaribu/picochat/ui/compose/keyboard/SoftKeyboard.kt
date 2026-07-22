package com.markusmaribu.picochat.ui.compose.keyboard

import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.ui.compose.theme.DsColors

/**
 * DS soft keyboard (port of SoftKeyboardView): key grid rendered with the
 * native canvas + Cozette typeface for pixel parity, touch handling with
 * press feedback and drag-to-place symbols, and the D-pad focus ring.
 * All state and callbacks live in [state] so both windows can render the
 * same keyboard.
 */
@Composable
fun SoftKeyboard(
    state: KeyboardState,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color = DsColors.tealBorder,
    interactive: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val paints = remember(context) {
        val cozette = ResourcesCompat.getFont(context, R.font.cozette_vector)
        KeyboardPaints(
            keyFill = Paint().apply {
                color = 0xFFDADADA.toInt(); style = Paint.Style.FILL; isAntiAlias = false
            },
            specialKeyFill = Paint().apply {
                color = 0xFFC0C0C0.toInt(); style = Paint.Style.FILL; isAntiAlias = false
            },
            activeFill = Paint().apply {
                style = Paint.Style.FILL; isAntiAlias = false
            },
            pressedFill = Paint().apply {
                color = 0xFFB0B0B0.toInt(); style = Paint.Style.FILL; isAntiAlias = false
            },
            keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.BLACK
                textAlign = Paint.Align.CENTER
                typeface = cozette
            },
            smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.BLACK
                textAlign = Paint.Align.CENTER
                typeface = cozette
            },
            focus = Paint().apply {
                color = AndroidColor.RED
                style = Paint.Style.STROKE
                strokeWidth = with(density) { 2.dp.toPx() }
                pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                isAntiAlias = false
            }
        )
    }
    paints.activeFill.color = accentColor.toArgb()

    val dragThresholdPx = with(density) { 15.dp.toPx() }

    var drawModifier = modifier.onSizeChanged {
        state.updateLayoutSize(it.width.toFloat(), it.height.toFloat())
    }

    if (interactive) {
        drawModifier = drawModifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var isDragging = false
                var dragSymbol: String? = null
                val dragStartX = down.position.x
                val dragStartY = down.position.y

                val hit = state.keyRects.find {
                    it.rect.contains(down.position) && it.def.output != "BLANK"
                }
                state.pressedKey = hit
                if (hit != null && hit.def.output !in KeyboardState.NON_DRAGGABLE_OUTPUTS) {
                    dragSymbol = state.getEffectiveOutput(hit.def)
                }
                if (hit != null) state.onTouchDown?.invoke()

                var cancelled = false
                var lastX = down.position.x
                var lastY = down.position.y
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                    if (change == null) { cancelled = true; break }
                    lastX = change.position.x
                    lastY = change.position.y
                    if (!change.pressed) break

                    if (!isDragging && dragSymbol != null) {
                        val dx = change.position.x - dragStartX
                        val dy = change.position.y - dragStartY
                        if (dx * dx + dy * dy > dragThresholdPx * dragThresholdPx) {
                            isDragging = true
                            state.pressedKey = null
                            state.onDragStart?.invoke(dragSymbol, change.position.x, change.position.y)
                        }
                    }
                    if (isDragging) {
                        state.onDragMove?.invoke(change.position.x, change.position.y)
                    } else {
                        val moveHit = state.keyRects.find {
                            it.rect.contains(change.position) && it.def.output != "BLANK"
                        }
                        if (moveHit != state.pressedKey) {
                            state.pressedKey = moveHit
                        }
                    }
                    change.consume()
                }

                if (cancelled) {
                    if (isDragging) state.onDragCancel?.invoke()
                } else if (isDragging) {
                    state.onDragEnd?.invoke(dragSymbol!!, lastX, lastY)
                } else {
                    state.pressedKey?.let {
                        state.onTouchUp?.invoke()
                        state.handleKeyPress(it.def)
                    }
                }
                state.pressedKey = null
            }
        }
    }

    Canvas(drawModifier) {
        // Snapshot reads that must trigger redraws.
        state.layoutVersion
        val pressed = state.pressedKey
        val capsLock = state.capsLock
        val shiftActive = state.shiftActive
        val showFocus = state.showFocus
        val focusedRow = state.focusedRow
        val focusedCol = state.focusedCol

        paints.keyText.textSize = state.keyTextSizePx
        paints.smallText.textSize = state.smallTextSizePx

        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            for (kr in state.keyRects) {
                if (kr.def.output == "BLANK") continue
                val isPressed = (pressed == kr)
                val isCapsActive = kr.def.output == "CAPS" && capsLock
                val isShiftActive = kr.def.output == "SHIFT" && shiftActive
                val isSpecial = kr.def.output in KeyboardState.SPECIAL_OUTPUTS
                val r = kr.rect

                val fill = when {
                    isPressed -> paints.pressedFill
                    isCapsActive || isShiftActive -> paints.activeFill
                    isSpecial -> paints.specialKeyFill
                    else -> paints.keyFill
                }
                c.drawRect(r.left, r.top, r.right, r.bottom, fill)

                val label = state.effectiveLabel(kr.def)
                val paint = if (kr.def.isWide && label.length > 3) paints.smallText else paints.keyText
                val textY = r.center.y - (paint.descent() + paint.ascent()) / 2
                val drawLabel = if (!isSpecial && label.length in 1..2 &&
                    label.codePointCount(0, label.length) == 1
                ) label + "\uFE0E" else label
                c.drawText(drawLabel, r.center.x, textY, paint)
            }

            if (showFocus && focusedRow in state.keyGrid.indices) {
                val row = state.keyGrid[focusedRow]
                if (focusedCol in row.indices) {
                    val r = row[focusedCol].rect
                    c.drawRect(r.left, r.top, r.right, r.bottom, paints.focus)
                }
            }
        }
    }
}

private class KeyboardPaints(
    val keyFill: Paint,
    val specialKeyFill: Paint,
    val activeFill: Paint,
    val pressedFill: Paint,
    val keyText: Paint,
    val smallText: Paint,
    val focus: Paint
)
