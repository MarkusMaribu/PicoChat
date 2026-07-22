package com.markusmaribu.picochat.ui.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.util.ThemeColors

/** DS-style color constants, mirroring res/values/colors.xml and drawable XMLs. */
object DsColors {
    val mintBg = Color(0xFFC8E8C0)
    val tealBorder = Color(0xFF00C8C8)
    val darkHeader = Color(0xFF303030)
    val greenText = Color(0xFF18A818)
    val white = Color(0xFFF8F8F8)
    val keyBg = Color(0xFFD8D8D8)
    val keyBorder = Color(0xFF989898)
    val black = Color(0xFF000000)
    val canvasWhite = Color(0xFFFFFFFF)
    val grayStripe = Color(0xFFD0D0D0)
    val yellowEnter = Color(0xFFFFF040)
    val roomIconBg = Color(0xFFE0E0E0)

    val stripeLine = Color(0xFFDDDDDD)
    val roomHeaderGreen = Color(0xFF58C858)
    val quitButtonTop = Color(0xFFE8E8E8)
    val quitButtonBottom = Color(0xFFCCCCCC)
    val roomRowTop = Color(0xFFFFFFFF)
    val roomRowBottom = Color(0xFFCCCCCC)
    val roomRowBorder = Color(0xFF606060)
    val bannerGray = Color(0xFF808080)
    val toolSelectedGreen = Color(0xFF38C838)
    val toolSelectedGreenDark = Color(0xFF189818)
}

object PicoFonts {
    val cozette = FontFamily(Font(R.font.cozette_vector))
    val pressStart = FontFamily(Font(R.font.press_start_2p))
    val orbitron = FontFamily(Font(R.font.orbitron_variable))
}

/** Palette helpers bridging the int-based [ThemeColors] to Compose colors. */
fun paletteColor(index: Int): Color =
    Color(ThemeColors.PALETTE[index.coerceIn(0, ThemeColors.PALETTE.lastIndex)])

fun brighten(color: Color, factor: Float = 0.45f): Color =
    Color(ThemeColors.brighten(color.toArgb(), factor))

fun darken(color: Color, factor: Float = 0.3f): Color =
    Color(ThemeColors.darken(color.toArgb(), factor))

@Composable
fun rememberPaletteColor(index: Int): Color = remember(index) { paletteColor(index) }
