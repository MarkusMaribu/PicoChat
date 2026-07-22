package com.markusmaribu.picochat.ui.compose.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.ui.compose.canvas.CanvasEngine
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.ThemeColors
import kotlinx.coroutines.delay

/** Nametag text size in canvas-space pixels (same as PictoCanvasView.TEXT_SIZE). */
private const val NAMETAG_TEXT_SIZE = 13f
private const val NAMETAG_H_PADDING = 6f

/**
 * Chat history list (port of ChatHistoryAdapter + ClampedLayoutManager):
 * a LazyColumn whose first item is the PictoChat banner preceded by a
 * viewport-height spacer, so scrolling clamps exactly where the old layout
 * manager stopped (banner bottom at the viewport bottom) and short histories
 * stay anchored to the bottom.
 */
@Composable
fun ChatHistoryList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        var bannerHeightPx by remember { mutableIntStateOf(0) }
        val spacerHeight = with(density) {
            (viewportHeightPx - bannerHeightPx).coerceAtLeast(0f).toDp()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(spacerHeight))
                    PictoChatBanner(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { bannerHeightPx = it.height }
                            .padding(bottom = 2.dp)
                    )
                }
            }
            items(count = messages.size) { index ->
                val msg = messages[index]
                Box(Modifier.padding(bottom = 2.dp)) {
                    when (msg) {
                        is ChatMessage.SystemMessage -> SystemMessageRow(msg)
                        is ChatMessage.TextMessage -> UserMessageFrame(
                            username = msg.username,
                            colorIndex = msg.colorIndex
                        ) {
                            BasicText(
                                text = msg.text,
                                style = TextStyle(
                                    fontFamily = PicoFonts.cozette,
                                    fontSize = 12.sp,
                                    color = DsColors.black
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 4.dp, end = 4.dp, top = 22.dp)
                            )
                        }
                        is ChatMessage.DrawingMessage -> UserMessageFrame(
                            username = msg.username,
                            colorIndex = msg.colorIndex
                        ) {
                            DrawingImage(
                                bitmap = msg.bitmap,
                                rainbowBits = msg.rainbowBits,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Visible item range of the chat list (adapter positions: 0 = banner). */
@Composable
fun rememberChatVisibleRange(listState: LazyListState): State<Pair<Int, Int>> {
    return remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) -1 to -1
            else visible.first().index to visible.last().index
        }
    }
}

@Composable
fun PictoChatBanner(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DsColors.bannerGray)
            .border(2.dp, DsColors.white, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        BasicText(
            text = "PICTOCHAT",
            style = TextStyle(
                fontFamily = PicoFonts.orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = DsColors.white
            )
        )
    }
}

@Composable
private fun SystemMessageRow(msg: ChatMessage.SystemMessage) {
    val text = msg.text
    val annotated: AnnotatedString = remember(text) {
        val colonIdx = text.indexOf(": ")
        if (colonIdx >= 0) {
            buildAnnotatedString {
                append(text.substring(0, colonIdx + 2))
                withStyle(SpanStyle(color = Color.White)) {
                    append(text.substring(colonIdx + 2))
                }
            }
        } else {
            AnnotatedString(text)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(DsColors.black)
            .border(2.dp, DsColors.white, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        BasicText(
            text = annotated,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 14.sp,
                color = DsColors.yellowEnter
            )
        )
    }
}

/**
 * Canvas-style message frame (256:88, white with theme-colored border) with
 * the scaled username nametag overlapping the top-left corner.
 */
@Composable
fun UserMessageFrame(
    username: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val color = Color(ThemeColors.PALETTE[colorIndex.coerceIn(0, ThemeColors.PALETTE.size - 1)])
    val nametagBg = Color(
        ThemeColors.brighten(
            ThemeColors.PALETTE[colorIndex.coerceIn(0, ThemeColors.PALETTE.size - 1)], 0.85f
        )
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(Constants.CANVAS_W.toFloat() / Constants.CANVAS_H)
            .clip(RoundedCornerShape(6.dp))
            .background(DsColors.canvasWhite)
            .border(2.dp, color, RoundedCornerShape(6.dp))
    ) {
        val density = LocalDensity.current
        // Same scale factor the old scaleNametag() computed: frame content
        // height (inside 2dp padding) relative to the 88px canvas.
        val scale = with(density) { (maxHeight - 4.dp).toPx() } / Constants.CANVAS_H

        Box(Modifier.fillMaxSize().padding(2.dp)) {
            content()
        }

        val nametagShape = RoundedCornerShape(
            topStart = 4.dp, topEnd = 0.dp, bottomEnd = 4.dp, bottomStart = 0.dp
        )
        // The tag spans from the frame top down to the canvas's first ruled
        // line (one line height into the 2dp-inset drawing area), matching
        // the drawing canvas proportionally.
        val lineHeightPx = Constants.CANVAS_H.toFloat() / CanvasEngine.LINE_COUNT * scale
        Box(
            Modifier
                .align(Alignment.TopStart)
                .height(with(density) { (2.dp.toPx() + lineHeightPx).toDp() })
                .clip(nametagShape)
                .background(nametagBg)
                .border(2.dp, color, nametagShape)
                .padding(horizontal = with(density) { (NAMETAG_H_PADDING * scale).toDp() }),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicText(
                text = username,
                style = TextStyle(
                    fontFamily = PicoFonts.cozette,
                    fontSize = with(density) { (NAMETAG_TEXT_SIZE * scale).toSp() },
                    color = color
                )
            )
        }
    }
}

/**
 * Renders a drawing message bitmap pixel-perfectly (no filtering). When
 * [rainbowBits] is present, the flagged pixels cycle through hues at ~30fps
 * (port of RainbowBitmapDrawable, driven by the Compose frame clock).
 */
@Composable
fun DrawingImage(
    bitmap: Bitmap,
    rainbowBits: ByteArray?,
    modifier: Modifier = Modifier
) {
    if (rainbowBits == null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Canvas(modifier) {
            drawImage(
                image,
                dstSize = androidx.compose.ui.unit.IntSize(
                    size.width.toInt(), size.height.toInt()
                ),
                filterQuality = FilterQuality.None
            )
        }
        return
    }

    val w = Constants.CANVAS_W
    val h = Constants.CANVAS_H
    val composite = remember(bitmap) {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
    val pixels = remember(bitmap) { IntArray(w * h) }
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(bitmap, rainbowBits) {
        val hsv = floatArrayOf(0f, 1f, 0.85f)
        while (true) {
            withFrameMillis { now ->
                val phase = (now % 3000L) / 3000f * 360f
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                var byteIdx = 0
                var bitIdx = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val bit = (rainbowBits[byteIdx].toInt() shr (7 - bitIdx)) and 1
                        if (bit == 1) {
                            hsv[0] = ((x.toFloat() / w * 360f) + phase) % 360f
                            pixels[y * w + x] = android.graphics.Color.HSVToColor(hsv)
                        }
                        bitIdx++
                        if (bitIdx == 8) { byteIdx++; bitIdx = 0 }
                    }
                }
                composite.setPixels(pixels, 0, w, 0, 0, w, h)
                frameTick++
            }
            delay(33)
        }
    }

    val compositeImage = remember(composite) { composite.asImageBitmap() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        drawImage(
            compositeImage,
            dstSize = androidx.compose.ui.unit.IntSize(
                size.width.toInt(), size.height.toInt()
            ),
            filterQuality = FilterQuality.None
        )
    }
}
