package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.markusmaribu.picochat.ui.compose.theme.DsColors

/**
 * DS-style striped background (port of StripedDrawable): solid [bgColor]
 * with thin horizontal [lineColor] stripes.
 */
fun Modifier.stripedBackground(
    bgColor: Color = DsColors.grayStripe,
    lineColor: Color = DsColors.stripeLine,
    lineSpacing: Dp = 3.dp,
    lineWidth: Dp = 1.dp
): Modifier = drawBehind {
    drawRect(bgColor)
    val spacingPx = lineSpacing.toPx()
    val widthPx = lineWidth.toPx()
    val step = spacingPx + widthPx
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = lineColor,
            topLeft = Offset(0f, y),
            size = Size(size.width, widthPx)
        )
        y += step
    }
}
