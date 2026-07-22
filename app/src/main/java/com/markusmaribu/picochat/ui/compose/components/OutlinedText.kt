package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle

/**
 * Text with a stroked outline behind the fill (port of OutlinedTextView).
 */
@Composable
fun OutlinedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    outlineColor: Color = Color(0xFF303030),
    outlineWidth: Float = 4f
) {
    Box(modifier) {
        BasicText(
            text = text,
            style = style.copy(
                color = outlineColor,
                drawStyle = Stroke(width = outlineWidth, join = StrokeJoin.Round)
            )
        )
        BasicText(
            text = text,
            style = style
        )
    }
}
