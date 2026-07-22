package com.markusmaribu.picochat.ui.compose.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Draws DS-style corner brackets (port of CornerBracketDrawable): four
 * L-shaped corner marks, optionally outlined in white, expanded beyond the
 * bounds by [expandH]/[expandV].
 */
fun DrawScope.drawCornerBrackets(
    color: Color,
    strokeWidth: Float,
    armFraction: Float = 0.14f,
    outlineColor: Color = Color.White,
    outlineWidth: Float = 0f,
    expandH: Float = 0f,
    expandV: Float = 0f
) {
    val w = size.width + expandH * 2f
    val h = size.height + expandV * 2f
    val armLen = min(w, h) * armFraction
    val hasOutline = outlineWidth > 0f
    val outlineStroke = strokeWidth + outlineWidth * 2f

    val outerInset = if (hasOutline) outlineStroke / 2f else strokeWidth / 2f
    val l = -expandH + outerInset
    val t = -expandV + outerInset
    val r = size.width + expandH - outerInset
    val b = size.height + expandV - outerInset

    fun corners(c: Color, sw: Float) {
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = sw, cap = StrokeCap.Square)
        line(l, t, l + armLen, t); line(l, t, l, t + armLen)
        line(r - armLen, t, r, t); line(r, t, r, t + armLen)
        line(l, b, l + armLen, b); line(l, b - armLen, l, b)
        line(r - armLen, b, r, b); line(r, b - armLen, r, b)
    }

    if (hasOutline) corners(outlineColor, outlineStroke)
    corners(color, strokeWidth)
}

/**
 * Modifier that draws the DS selection corner brackets around the element
 * (matching createHighlightDrawable in the old activities: 4dp stroke,
 * 1.5dp white outline, expanded 7dp horizontally / 2dp vertically).
 */
fun Modifier.selectionBrackets(
    color: Color,
    strokeWidth: Dp = 4.dp,
    outlineWidth: Dp = 1.5.dp,
    expandH: Dp = 7.dp,
    expandV: Dp = 2.dp,
    armFraction: Float = 0.14f
): Modifier = drawBehind {
    drawCornerBrackets(
        color = color,
        strokeWidth = strokeWidth.toPx(),
        armFraction = armFraction,
        outlineColor = Color.White,
        outlineWidth = outlineWidth.toPx(),
        expandH = expandH.toPx(),
        expandV = expandV.toPx()
    )
}

/**
 * Standalone corner-bracket highlight box, for overlaying on top of a
 * selected row when it can't be attached to the row itself (e.g. when its
 * position/size is animated between targets).
 */
@Composable
fun SelectionHighlight(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    outlineWidth: Dp = 1.5.dp,
    expandH: Dp = 7.dp,
    expandV: Dp = 2.dp,
    armFraction: Float = 0.14f
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawCornerBrackets(
                color = color,
                strokeWidth = strokeWidth.toPx(),
                armFraction = armFraction,
                outlineColor = Color.White,
                outlineWidth = outlineWidth.toPx(),
                expandH = expandH.toPx(),
                expandV = expandV.toPx()
            )
        }
    }
}
