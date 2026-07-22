package com.markusmaribu.picochat.ui.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.ui.compose.components.ChatHistoryList
import com.markusmaribu.picochat.ui.compose.components.ScrollBarVisualizer
import com.markusmaribu.picochat.ui.compose.components.rememberChatVisibleRange
import com.markusmaribu.picochat.ui.compose.components.stripedBackground
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts

enum class SignalLevel { NONE, WEAK, MODERATE, GOOD }

/**
 * The shared DS "top screen": white sidebar (scroll visualizer plus, in
 * chat, the signal indicator and room letter) and the chat history list on
 * a striped background. Rendered identically by every AppScreen.
 */
@Composable
fun TopScreen(
    messages: List<ChatMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    signalLevel: SignalLevel? = null,
    roomLetter: String? = null,
    /** 0 = badges in place, 1 = slid offscreen (chat enter/leave animation). */
    sidebarSlide: Float = 0f
) {
    val visibleRange by rememberChatVisibleRange(listState)

    Row(modifier.fillMaxSize().stripedBackground()) {
        Column(
            Modifier
                .width(36.dp)
                .fillMaxHeight()
                .background(DsColors.white),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (signalLevel != null) {
                Box(
                    Modifier
                        .height(33.dp)
                        .graphicsLayer {
                            translationY = -sidebarSlide * 40.dp.toPx()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    SignalIndicator(signalLevel)
                }
            } else {
                Spacer(Modifier.height(33.dp))
            }
            DottedSeparator(Modifier.padding(horizontal = 3.dp).padding(top = 3.dp))
            ScrollBarVisualizer(
                messages = messages,
                visibleFirst = visibleRange.first,
                visibleLast = visibleRange.second,
                includeBanner = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            DottedSeparator(Modifier.padding(horizontal = 3.dp).padding(bottom = 3.dp))
            if (roomLetter != null) {
                Box(
                    Modifier
                        .padding(bottom = 3.dp)
                        .size(30.dp)
                        .graphicsLayer {
                            translationY = sidebarSlide * 40.dp.toPx()
                        }
                        .background(DsColors.keyBg),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = roomLetter,
                        style = TextStyle(
                            fontFamily = PicoFonts.cozette,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = DsColors.black
                        )
                    )
                }
            } else {
                Spacer(Modifier.height(33.dp))
            }
        }

        ChatHistoryList(
            messages = messages,
            listState = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 6.dp, end = 6.dp, bottom = 2.dp)
        )
    }
}

@Composable
fun SignalIndicator(level: SignalLevel, modifier: Modifier = Modifier) {
    val lineColor = when (level) {
        SignalLevel.GOOD -> Color(0xFF00C800)
        SignalLevel.MODERATE -> Color(0xFFFFA500)
        SignalLevel.WEAK -> Color(0xFFC80000)
        SignalLevel.NONE -> Color(0xFF606060)
    }
    val iconRes = when (level) {
        SignalLevel.GOOD -> R.drawable.ic_signal
        SignalLevel.MODERATE -> R.drawable.ic_signal_2
        SignalLevel.WEAK -> R.drawable.ic_signal_1
        SignalLevel.NONE -> R.drawable.ic_signal
    }

    Box(modifier.size(30.dp).background(DsColors.black)) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(3.dp)
                .background(lineColor)
        )
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(18.dp),
            colorFilter = if (level == SignalLevel.NONE) {
                ColorFilter.tint(Color(0xFF606060))
            } else null
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(3.dp)
                .background(lineColor)
        )
    }
}

/** Dashed horizontal separator (port of bg_dotted_separator). */
@Composable
fun DottedSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val dash = 2.dp.toPx()
                drawLine(
                    color = Color(0xFF999999),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)
                )
            }
    )
}
