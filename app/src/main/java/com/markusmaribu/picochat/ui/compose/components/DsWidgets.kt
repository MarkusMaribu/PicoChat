package com.markusmaribu.picochat.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.util.ThemeColors

/** Body text style used across the DS menus (cozette, 16sp, black). */
val DsBodyTextStyle = TextStyle(
    fontFamily = PicoFonts.cozette,
    fontSize = 16.sp,
    color = DsColors.black
)

/**
 * The standard DS menu button (port of bg_quit_button): vertical
 * E8E8E8 -> CCCCCC gradient with a 1dp black border.
 */
@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = DsColors.black,
    borderColor: Color = DsColors.black,
    background: Brush = remember {
        Brush.verticalGradient(listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom))
    }
) {
    Box(
        modifier
            .background(background)
            .border(1.dp, borderColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = DsBodyTextStyle.copy(color = textColor, textAlign = TextAlign.Center)
        )
    }
}

/**
 * The 4:3 menu page scaffold shared by every room-selection submenu:
 * themed gradient header (weight 11), 1dp divider, striped middle area
 * (weight 76), 1dp divider, themed gradient bottom bar (weight 13).
 */
@Composable
fun DsMenuScaffold(
    title: String,
    themeColorIndex: Int,
    modifier: Modifier = Modifier,
    bottomBar: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val color = Color(ThemeColors.PALETTE[themeColorIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)])
    val topBright = Color(ThemeColors.brighten(color.toArgb(), 0.70f))
    val bottomBright = Color(ThemeColors.brighten(color.toArgb(), 0.60f))

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(11f)
                .background(Brush.verticalGradient(listOf(topBright, color))),
            contentAlignment = Alignment.Center
        ) {
            BasicText(text = title, style = DsBodyTextStyle)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DsColors.darkHeader))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(76f)
                .stripedBackground(),
            content = content
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(DsColors.darkHeader))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(13f)
                .background(Brush.verticalGradient(listOf(color, bottomBright))),
            content = bottomBar
        )
    }
}
