package com.markusmaribu.picochat.ui.compose.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.markusmaribu.picochat.util.Constants

/**
 * Renders a [CanvasEngine] and feeds pointer strokes into it (port of the
 * PictoCanvasView draw/touch pipeline). When [interactive] is false the
 * canvas is display-only (used for the mirrored copy on the other window).
 */
@Composable
fun PictoCanvas(
    engine: CanvasEngine,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onDrawStart: (CanvasTool) -> Unit = {},
    onDrawEnd: () -> Unit = {}
) {
    val startCallback by rememberUpdatedState(onDrawStart)
    val endCallback by rememberUpdatedState(onDrawEnd)

    // Same 3s linear hue sweep as the old ValueAnimator; only read (and thus
    // only invalidating) while rainbow ink exists.
    val infiniteTransition = rememberInfiniteTransition(label = "rainbow")
    val animatedPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "rainbowPhase"
    )

    var inputModifier = modifier.clip(RoundedCornerShape(4.dp))
    if (interactive) {
        inputModifier = inputModifier.pointerInput(engine) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun toCanvasX(x: Float) =
                    (x * Constants.CANVAS_W / size.width).toInt()
                        .coerceIn(0, Constants.CANVAS_W - 1)
                fun toCanvasY(y: Float) =
                    (y * Constants.CANVAS_H / size.height).toInt()
                        .coerceIn(0, Constants.CANVAS_H - 1)

                engine.strokeStart(toCanvasX(down.position.x), toCanvasY(down.position.y))
                startCallback(engine.tool)
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) break
                        engine.strokeMove(
                            toCanvasX(change.position.x),
                            toCanvasY(change.position.y)
                        )
                        change.consume()
                    }
                } finally {
                    engine.strokeEnd()
                    endCallback()
                }
            }
        }
    }

    Canvas(inputModifier) {
        engine.version
        val phase = if (engine.hasRainbowContent) animatedPhase else 0f
        drawIntoCanvas { canvas ->
            engine.renderTo(canvas.nativeCanvas, size.width, size.height, phase)
        }
    }
}
