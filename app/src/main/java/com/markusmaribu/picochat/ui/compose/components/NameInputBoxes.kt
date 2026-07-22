package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.util.Constants

/**
 * The 10-character name entry boxes (port of the NameInputBoxes view):
 * dark boxes with the active cursor box highlighted orange, characters
 * drawn in Cozette.
 */
@Composable
fun NameInputBoxes(
    text: String,
    modifier: Modifier = Modifier
) {
    val maxChars = Constants.USERNAME_MAX_LENGTH
    val textMeasurer = rememberTextMeasurer()

    // Cursor sits after the last character, clamped to the final box.
    val cpCount = text.codePointCount(0, text.length)
    val cursorIndex = cpCount.coerceAtMost(maxChars - 1)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val boxSize = (w / maxChars).coerceAtMost(h * 0.85f)
        val totalWidth = boxSize * maxChars
        val startX = (w - totalWidth) / 2f
        val startY = (h - boxSize) / 2f

        val textStyle = TextStyle(
            fontFamily = PicoFonts.cozette,
            fontSize = (boxSize * 0.55f).toSp(),
            color = Color.White
        )

        for (i in 0 until maxChars) {
            val x = startX + i * boxSize
            val topLeft = Offset(x, startY)
            val boxSz = Size(boxSize, boxSize)

            drawRect(
                color = if (i == cursorIndex) Color(0xFFDD8800) else Color(0xFF202020),
                topLeft = topLeft,
                size = boxSz
            )
            drawRect(
                color = Color(0xFF606060),
                topLeft = topLeft,
                size = boxSz,
                style = Stroke(width = 2f)
            )

            if (i < cpCount) {
                val cpStart = text.offsetByCodePoints(0, i)
                val cp = text.codePointAt(cpStart)
                val ch = String(Character.toChars(cp))
                val layout = textMeasurer.measure(ch, textStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        x + (boxSize - layout.size.width) / 2f,
                        startY + (boxSize - layout.size.height) / 2f
                    )
                )
            }
        }
    }
}
