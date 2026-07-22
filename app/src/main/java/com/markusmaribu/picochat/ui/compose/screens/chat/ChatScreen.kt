package com.markusmaribu.picochat.ui.compose.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.ui.compose.canvas.CanvasEngine
import com.markusmaribu.picochat.ui.compose.canvas.CanvasTool
import com.markusmaribu.picochat.ui.compose.canvas.PictoCanvas
import com.markusmaribu.picochat.ui.compose.components.OutlinedText
import com.markusmaribu.picochat.ui.compose.components.stripedBackground
import com.markusmaribu.picochat.ui.compose.core.DecelerateEasing
import com.markusmaribu.picochat.ui.compose.core.drawCornerBrackets
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardMode
import com.markusmaribu.picochat.ui.compose.keyboard.SoftKeyboard
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.ThemeColors
import kotlin.math.roundToInt

private val ToolStripWidth = 36.dp
private val RightSidebarWidth = 52.dp

/**
 * The chat "bottom screen": tool strip, canvas with nametag, keyboard,
 * send/retrieve/clear sidebar, the leave dialog and drag-to-place ghost
 * (port of ChatActivity's bottom-screen content).
 */
@Composable
fun ChatBottomScreen(
    vm: MainViewModel,
    room: Room,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val controller = remember(room, isOnline) { vm.ensureChatController(room, isOnline) }
    val engine = controller.engine
    val colorIndex = controller.session.colorIndex
    val themeColor = Color(ThemeColors.PALETTE[colorIndex])
    val themeDark = Color(ThemeColors.darken(ThemeColors.PALETTE[colorIndex]))
    val peers by controller.session.peers.collectAsState()

    LaunchedEffect(controller) {
        engine.ruledLineColor = ThemeColors.brighten(ThemeColors.PALETTE[colorIndex], 0.85f)
        controller.playEntryAnimation()
    }

    DisposableEffect(controller) {
        controller.session.onVisible()
        onDispose {
            controller.session.onHidden()
        }
    }

    // Coordinate spaces for the drag-to-place ghost.
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var keyboardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var canvasCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var ghostSymbol by remember { mutableStateOf<String?>(null) }
    var ghostPosition by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(controller) {
        fun toRoot(localX: Float, localY: Float): Offset {
            val root = rootCoords ?: return Offset.Zero
            val kb = keyboardCoords ?: return Offset.Zero
            return root.localPositionOf(kb, Offset(localX, localY))
        }
        controller.keyboard.onDragStart = { symbol, x, y ->
            ghostSymbol = symbol
            ghostPosition = toRoot(x, y)
        }
        controller.keyboard.onDragMove = { x, y ->
            ghostPosition = toRoot(x, y)
        }
        controller.keyboard.onDragEnd = { symbol, x, y ->
            ghostSymbol = null
            val root = rootCoords
            val canvas = canvasCoords
            if (root != null && canvas != null && canvas.isAttached) {
                val pos = toRoot(x, y)
                val bounds = root.localBoundingBoxOf(canvas, clipBounds = false)
                if (bounds.contains(pos)) {
                    val bitmapX = ((pos.x - bounds.left) * Constants.CANVAS_W / bounds.width)
                        .toInt().coerceIn(0, Constants.CANVAS_W - 1)
                    val bitmapY = ((pos.y - bounds.top) * Constants.CANVAS_H / bounds.height)
                        .toInt().coerceIn(0, Constants.CANVAS_H - 1)
                    controller.placeSymbolAt(symbol, bitmapX, bitmapY)
                }
            }
        }
        controller.keyboard.onDragCancel = { ghostSymbol = null }
        onDispose {
            controller.keyboard.onDragStart = null
            controller.keyboard.onDragMove = null
            controller.keyboard.onDragEnd = null
            controller.keyboard.onDragCancel = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(DsColors.white)
            .onGloballyPositioned { rootCoords = it }
    ) {
        Row(Modifier.fillMaxSize()) {
            ChatToolStrip(controller, themeColor, themeDark)

            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Top bar: peer names + close button
                Row(
                    Modifier.fillMaxWidth().height(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PeerNamesRow(
                        peers = peers,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 2.dp)
                    )
                    CloseButton(
                        onClick = { controller.performAnimatedLeave() },
                        modifier = Modifier.padding(2.dp).size(18.dp)
                    )
                }

                // Grey container (rounded left corners, 2dp black border)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                        .background(DsColors.grayStripe)
                        .border(
                            2.dp, DsColors.black,
                            RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                        )
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Canvas frame (256:88)
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, top = 6.dp)
                                .aspectRatio(
                                    Constants.CANVAS_W.toFloat() / Constants.CANVAS_H
                                )
                                .clip(RoundedCornerShape(6.dp))
                                .background(DsColors.canvasWhite)
                                .border(2.dp, themeColor, RoundedCornerShape(6.dp))
                        ) {
                            PictoCanvas(
                                engine = engine,
                                onDrawStart = controller::onDrawStart,
                                onDrawEnd = controller::onDrawEnd,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .onGloballyPositioned { canvasCoords = it }
                            )
                            // The tag spans from the frame top down to the
                            // canvas's first ruled line (one line height into
                            // the 2dp-inset drawing area).
                            val density = LocalDensity.current
                            val canvasScale =
                                with(density) { (maxHeight - 4.dp).toPx() } / Constants.CANVAS_H
                            val lineHeightPx =
                                Constants.CANVAS_H.toFloat() / CanvasEngine.LINE_COUNT * canvasScale
                            CanvasNametag(
                                name = controller.session.username,
                                themeColor = themeColor,
                                colorIndex = colorIndex,
                                height = with(density) { (2.dp.toPx() + lineHeightPx).toDp() },
                                fontSize = with(density) {
                                    (CanvasEngine.TEXT_SIZE * canvasScale).toSp()
                                },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }

                        // Keyboard + right sidebar
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DsColors.canvasWhite)
                                    .border(1.dp, DsColors.black, RoundedCornerShape(6.dp))
                                    .padding(3.dp)
                            ) {
                                SoftKeyboard(
                                    state = controller.keyboard,
                                    accentColor = themeColor,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { keyboardCoords = it }
                                )
                            }

                            Column(
                                Modifier
                                    .width(RightSidebarWidth)
                                    .fillMaxHeight()
                                    .padding(top = 6.dp, bottom = 6.dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                                    .background(DsColors.canvasWhite)
                                    .border(
                                        1.dp, DsColors.black,
                                        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                                    )
                                    .padding(3.dp)
                            ) {
                                SidebarButton(
                                    iconRes = R.drawable.ic_arrow_up,
                                    label = stringResource(R.string.btn_send),
                                    background = DsColors.white,
                                    borderWidth = 2.dp,
                                    onClick = { controller.sendMessage() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp)
                                )
                                SidebarButton(
                                    iconRes = R.drawable.ic_retrieve,
                                    label = stringResource(R.string.btn_retrieve),
                                    background = DsColors.keyBg,
                                    borderWidth = 1.dp,
                                    onClick = { controller.retrieveDrawing() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp)
                                )
                                SidebarButton(
                                    iconRes = R.drawable.ic_clear,
                                    label = stringResource(R.string.btn_clear),
                                    background = DsColors.keyBg,
                                    borderWidth = 1.dp,
                                    onClick = { controller.clearCanvas() },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Drag-to-place ghost
        ghostSymbol?.let { symbol ->
            val density = LocalDensity.current
            val textSize = with(density) {
                controller.keyboard.keyTextSize.coerceAtLeast(24f).toSp()
            }
            BasicText(
                text = symbol,
                style = TextStyle(
                    fontFamily = PicoFonts.cozette,
                    fontSize = textSize,
                    color = DsColors.black,
                    shadow = Shadow(Color.White, blurRadius = 2f)
                ),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            ghostPosition.x.roundToInt(),
                            ghostPosition.y.roundToInt()
                        )
                    }
                    .graphicsLayer {
                        translationX = -size.width / 2f
                        translationY = -size.height / 2f
                    }
            )
        }

        LeaveDialog(
            controller = controller,
            roomLetter = room.letter,
            themeColor = themeColor
        )

        val overlayAlpha = vm.navOverlayAlpha.value
        if (overlayAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayAlpha }
                    .background(DsColors.grayStripe)
            )
        }
    }
}

// =========================================================================
// Tool strip
// =========================================================================

@Composable
private fun ChatToolStrip(
    controller: ChatController,
    themeColor: Color,
    themeDark: Color
) {
    val engine = controller.engine

    Column(
        Modifier
            .width(ToolStripWidth)
            .fillMaxHeight()
            .background(DsColors.white)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolIconButton(
            iconRes = R.drawable.ic_arrow_up,
            selected = false,
            themeColor = themeColor,
            themeDark = themeDark,
            onClick = { controller.scrollUp() },
            modifier = Modifier.weight(1f)
        )
        ToolIconButton(
            iconRes = R.drawable.ic_arrow_down,
            selected = false,
            themeColor = themeColor,
            themeDark = themeDark,
            onClick = { controller.scrollDown() },
            modifier = Modifier.weight(1f).padding(top = 1.dp)
        )

        ToolStripSeparator()

        // Pencil (rainbow-cycles while the rainbow tool is active)
        val pencilSelected = engine.tool == CanvasTool.PENCIL || engine.tool == CanvasTool.RAINBOW
        if (engine.tool == CanvasTool.RAINBOW) {
            RainbowToolButton(
                iconRes = R.drawable.ic_pencil,
                onClick = { controller.onPencilClicked() },
                modifier = Modifier.weight(1f)
            )
        } else {
            ToolIconButton(
                iconRes = R.drawable.ic_pencil,
                selected = pencilSelected,
                themeColor = themeColor,
                themeDark = themeDark,
                onClick = { controller.onPencilClicked() },
                modifier = Modifier.weight(1f)
            )
        }
        ToolIconButton(
            iconRes = R.drawable.ic_eraser,
            selected = engine.tool == CanvasTool.ERASER,
            themeColor = themeColor,
            themeDark = themeDark,
            onClick = { controller.onEraserClicked() },
            modifier = Modifier.weight(1f).padding(top = 1.dp)
        )
        ToolIconButton(
            iconRes = R.drawable.ic_pen_thick,
            selected = engine.penSize == 3,
            themeColor = themeColor,
            themeDark = themeDark,
            onClick = { controller.onPenThickClicked() },
            modifier = Modifier.weight(1f).padding(top = 1.dp)
        )
        ToolIconButton(
            iconRes = R.drawable.ic_pen_thin,
            selected = engine.penSize == 1,
            themeColor = themeColor,
            themeDark = themeDark,
            onClick = { controller.onPenThinClicked() },
            iconPadding = 6.dp,
            modifier = Modifier.weight(1f).padding(top = 1.dp)
        )

        ToolStripSeparator()

        val modes = listOf(
            KeyboardMode.LATIN to "A",
            KeyboardMode.ACCENTED to "À",
            KeyboardMode.KATAKANA to "あ",
            KeyboardMode.SYMBOLS to "@",
            KeyboardMode.EMOTICONS to "😊"
        )
        modes.forEachIndexed { i, (mode, label) ->
            ToolTextButton(
                text = label,
                selected = controller.keyboard.mode == mode,
                themeColor = themeColor,
                themeDark = themeDark,
                onClick = { controller.setKeyboardMode(mode) },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = if (i == 0) 0.dp else 1.dp)
            )
        }
    }
}

@Composable
private fun ToolStripSeparator() {
    Box(
        Modifier
            .width(20.dp)
            .padding(vertical = 2.dp)
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

@Composable
private fun ToolIconButton(
    iconRes: Int,
    selected: Boolean,
    themeColor: Color,
    themeDark: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconPadding: androidx.compose.ui.unit.Dp = 4.dp
) {
    Box(
        modifier
            .width(23.dp)
            .background(if (selected) themeColor else DsColors.keyBg)
            .border(1.dp, if (selected) themeDark else DsColors.keyBorder)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(iconPadding),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun RainbowToolButton(
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "penRainbow")
    val hue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "penRainbowHue"
    )
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 0.85f)))
    val dark = Color(ThemeColors.darken(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 0.85f))))

    Box(
        modifier
            .width(23.dp)
            .background(color)
            .border(1.dp, dark)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ToolTextButton(
    text: String,
    selected: Boolean,
    themeColor: Color,
    themeDark: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .width(23.dp)
            .background(if (selected) themeColor else DsColors.keyBg)
            .border(1.dp, if (selected) themeDark else DsColors.keyBorder)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        OutlinedText(
            text = text,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = DsColors.black
            ),
            outlineColor = Color.White,
            outlineWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
        )
    }
}

// =========================================================================
// Close button, sidebar buttons, nametag
// =========================================================================

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .drawBehind {
                // Port of bg_close_button: shadow, highlight, key face
                drawRect(Color(0xFF888888))
                drawRect(
                    Color(0xFFECECEC),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 2.dp.toPx(), size.height - 2.dp.toPx()
                    )
                )
                translate(2.dp.toPx(), 2.dp.toPx()) {
                    drawRect(
                        Color(0xFFD0D0D0),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - 4.dp.toPx(), size.height - 4.dp.toPx()
                        )
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SidebarButton(
    iconRes: Int,
    label: String,
    background: Color,
    borderWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(background)
            .border(borderWidth, DsColors.keyBorder)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = DsColors.black
            )
        )
    }
}

@Composable
private fun CanvasNametag(
    name: String,
    themeColor: Color,
    colorIndex: Int,
    height: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    val bg = Color(ThemeColors.brighten(ThemeColors.PALETTE[colorIndex], 0.85f))
    val shape = RoundedCornerShape(
        topStart = 4.dp, topEnd = 0.dp, bottomEnd = 4.dp, bottomStart = 0.dp
    )

    Box(
        modifier
            .height(height)
            .background(bg, shape)
            .border(2.dp, themeColor, shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicText(
            text = name,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = fontSize,
                color = themeColor
            )
        )
    }
}

// =========================================================================
// Peer names row
// =========================================================================

@Composable
private fun PeerNamesRow(peers: List<PeerEntry>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameStyle = TextStyle(fontFamily = PicoFonts.cozette, fontSize = 13.sp)

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        if (peers.isEmpty()) return@BoxWithConstraints

        val containerWidth = constraints.maxWidth.toFloat()
        val hPad = with(density) { 3.dp.toPx() }
        val sepW = with(density) { 3.dp.toPx() }
        val squareSize = with(density) { 8.dp.toPx() }
        val squareGap = with(density) { 2.dp.toPx() }

        val measured = peers.map { peer ->
            val w = textMeasurer.measure(peer.name, nameStyle).size.width.toFloat()
            peer to (w + 2 * hPad + squareSize + squareGap)
        }

        fun totalForCount(n: Int): Float {
            var w = 0f
            for (i in 0 until n) {
                if (i > 0) w += sepW
                w += measured[i].second
            }
            return w
        }

        val (visiblePeers, overflow) = if (totalForCount(measured.size) <= containerWidth) {
            peers to 0
        } else {
            var bestFit = 0
            for (i in measured.indices) {
                val remaining = measured.size - (i + 1)
                if (remaining == 0) break
                val overflowW = textMeasurer
                    .measure("+$remaining", nameStyle).size.width.toFloat() + 2 * hPad
                if (overflowW + sepW + totalForCount(i + 1) <= containerWidth) {
                    bestFit = i + 1
                } else break
            }
            peers.take(bestFit) to (peers.size - bestFit)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (overflow > 0) {
                BasicText(
                    text = "+$overflow",
                    style = nameStyle.copy(color = Color(0xFF808080)),
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
            visiblePeers.forEachIndexed { idx, peer ->
                if (idx > 0 || overflow > 0) PeerSeparator()
                val color = Color(
                    ThemeColors.PALETTE[
                        peer.colorIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)
                    ]
                )
                Row(
                    Modifier.padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .padding(end = 2.dp)
                            .size(8.dp)
                            .background(color)
                    )
                    BasicText(
                        text = peer.name,
                        style = nameStyle.copy(
                            color = Color(
                                ThemeColors.darken(
                                    ThemeColors.PALETTE[
                                        peer.colorIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)
                                    ]
                                )
                            )
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerSeparator() {
    Box(
        Modifier
            .width(3.dp)
            .fillMaxHeight()
            .drawBehind {
                val dash = 2.dp.toPx()
                drawLine(
                    color = Color(0xFF999999),
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)
                )
            }
    )
}

// =========================================================================
// Leave dialog
// =========================================================================

@Composable
private fun LeaveDialog(
    controller: ChatController,
    roomLetter: String,
    themeColor: Color
) {
    var rendered by remember { mutableStateOf(false) }
    val slide = remember { Animatable(1f) } // 1 = offscreen below

    LaunchedEffect(controller.isLeaveDialogShowing) {
        if (controller.isLeaveDialogShowing) {
            rendered = true
            slide.animateTo(0f, tween(200, easing = DecelerateEasing))
        } else if (rendered) {
            slide.animateTo(1f, tween(200, easing = DecelerateEasing))
            rendered = false
        }
    }

    if (!rendered) return

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }, // block touches behind the dialog
        contentAlignment = Alignment.Center
    ) {
        val panelH = maxHeight * 0.40f
        val containerH = maxHeight

        Box(
            Modifier
                .fillMaxWidth(0.70f)
                .height(panelH)
                .graphicsLayer {
                    translationY =
                        slide.value * (containerH.toPx() / 2f + panelH.toPx() / 2f)
                }
                .clip(RoundedCornerShape(8.dp))
                .border(3.dp, DsColors.black, RoundedCornerShape(8.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(3.dp, Color(0xFFFF8800), RoundedCornerShape(6.dp))
                .stripedBackground(
                    bgColor = Color(0xFF3A3A3A),
                    lineColor = Color(0xFF686868),
                    lineSpacing = 3.dp,
                    lineWidth = 1.5.dp
                )
        ) {
            Column(
                Modifier.fillMaxSize().padding(top = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                BasicText(
                    text = "Leave Chat Room $roomLetter?",
                    style = TextStyle(
                        fontFamily = PicoFonts.cozette,
                        fontSize = 18.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.weight(1.2f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                    LeaveDialogButton(
                        text = "No",
                        focused = controller.leaveDialogFocusedButton == 0,
                        themeColor = themeColor,
                        onClick = { controller.dismissLeaveDialog() },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    LeaveDialogButton(
                        text = "Yes",
                        focused = controller.leaveDialogFocusedButton == 1,
                        themeColor = themeColor,
                        onClick = { controller.performAnimatedLeave() },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaveDialogButton(
    text: String,
    focused: Boolean,
    themeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom)
                )
            )
            .border(1.dp, DsColors.black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .drawBehind {
                if (focused) {
                    drawCornerBrackets(
                        color = themeColor,
                        strokeWidth = with(density) { 4.dp.toPx() },
                        outlineColor = Color.White,
                        outlineWidth = with(density) { 1.5.dp.toPx() },
                        expandH = with(density) { 7.dp.toPx() },
                        expandV = with(density) { 5.dp.toPx() }
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 16.sp,
                color = DsColors.black
            )
        )
    }
}
