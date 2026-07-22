package com.markusmaribu.picochat.ui.compose.screens.roomselection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.state.MenuScreen
import com.markusmaribu.picochat.state.TopScreenAlignment
import com.markusmaribu.picochat.state.TopScreenSize
import com.markusmaribu.picochat.ui.compose.components.ColorGrid
import com.markusmaribu.picochat.ui.compose.components.DrawingImage
import com.markusmaribu.picochat.ui.compose.components.DsBodyTextStyle
import com.markusmaribu.picochat.ui.compose.components.DsButton
import com.markusmaribu.picochat.ui.compose.components.DsMenuScaffold
import com.markusmaribu.picochat.ui.compose.components.NameInputBoxes
import com.markusmaribu.picochat.ui.compose.components.OutlinedText
import com.markusmaribu.picochat.ui.compose.core.AccelerateDecelerateEasing
import com.markusmaribu.picochat.ui.compose.core.drawCornerBrackets
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardMode
import com.markusmaribu.picochat.ui.compose.keyboard.SoftKeyboard
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.ThemeColors

/** Options menu button count; each row uses [Modifier.weight] 0.18f (four rows total). */
private const val OPTIONS_MENU_BUTTON_ROWS = 4
private const val OPTIONS_MENU_BUTTON_WEIGHT = 0.18f

private val RoomRowBackground = Brush.verticalGradient(
    listOf(DsColors.roomRowTop, DsColors.roomRowBottom)
)

/**
 * The room-selection "bottom screen" with its whole submenu tree
 * (port of RoomSelectionActivity's bottom-screen content).
 */
@Composable
fun RoomSelectionBottomScreen(
    vm: MainViewModel,
    menu: MenuScreen,
    modifier: Modifier = Modifier
) {
    val controller = vm.roomSelection
    val colorDraft by vm.colorDraft.collectAsState()
    val savedColor by vm.settings.colorIndex.collectAsState()
    val colorIndex = if (colorDraft >= 0) colorDraft else savedColor

    // Screen-scoped effects: input routing + room-count BLE scanning.
    DisposableEffect(controller) {
        controller.startScanning()
        onDispose {
            controller.stopScanning()
        }
    }

    Box(modifier.fillMaxSize()) {
        when (menu) {
            MenuScreen.RoomList -> RoomListMenu(vm, controller, colorIndex)
            MenuScreen.Options -> OptionsMenu(vm, controller, colorIndex)
            MenuScreen.NameInput -> NameInputMenu(vm, controller, colorIndex)
            MenuScreen.Color -> ColorMenu(vm, controller, colorIndex)
            MenuScreen.Credits -> CreditsMenu(controller, colorIndex)
            MenuScreen.DisplaySetup -> DisplaySetupMenu(vm, controller, colorIndex)
            MenuScreen.ExportChat -> ExportChatMenu(vm, controller, colorIndex)
            MenuScreen.Connecting -> ConnectingMenu(controller, colorIndex)
        }

        val overlayAlpha = maxOf(controller.overlayAlpha.value, vm.navOverlayAlpha.value)
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
// Animated corner-bracket selection highlight
// =========================================================================

/**
 * Overlays animated DS corner brackets around the bounds registered for the
 * selected index (replaces the ConstraintSet + ValueAnimator highlight).
 */
@Composable
internal fun BoxWithHighlight(
    selectedIndex: Int,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable (registerBounds: (Int, LayoutCoordinates) -> Unit, containerModifier: Modifier) -> Unit
) {
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val animatedRect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }

    val register: (Int, LayoutCoordinates) -> Unit = { index, coords ->
        containerCoords?.let { container ->
            if (coords.isAttached && container.isAttached) {
                bounds[index] = container.localBoundingBoxOf(coords, clipBounds = false)
            }
        }
    }

    val target = bounds[selectedIndex]
    LaunchedEffect(target) {
        if (target != null && target.width > 0f) {
            if (!initialized) {
                animatedRect.snapTo(target)
                initialized = true
            } else if (animatedRect.targetValue != target) {
                animatedRect.animateTo(target, tween(150, easing = AccelerateDecelerateEasing))
            }
        }
    }

    Box(
        modifier
            .onGloballyPositioned { containerCoords = it }
            .drawWithContent {
                drawContent()
                if (initialized) {
                    drawIntoBrackets(animatedRect.value, 5.dp.toPx(), highlightColor)
                }
            }
    ) {
        content(register, Modifier)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoBrackets(
    r: Rect,
    vertPad: Float,
    color: Color
) {
    val padded = Rect(r.left, r.top - vertPad, r.right, r.bottom + vertPad)
    translate(padded.left, padded.top) {
        withSize(padded.width, padded.height) {
            drawCornerBrackets(
                color = color,
                strokeWidth = 4.dp.toPx(),
                outlineColor = Color.White,
                outlineWidth = 1.5.dp.toPx(),
                expandH = 7.dp.toPx(),
                expandV = 2.dp.toPx()
            )
        }
    }
}

/** Runs [block] in a DrawScope whose `size` is the given dimensions. */
private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.withSize(
    w: Float,
    h: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    val original = drawContext.size
    drawContext.size = androidx.compose.ui.geometry.Size(w, h)
    try {
        block()
    } finally {
        drawContext.size = original
    }
}

// =========================================================================
// Room list
// =========================================================================

@Composable
private fun RoomListMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    val selectedIndex by vm.roomSelectedIndex.collectAsState()
    val counts by controller.roomCounts.collectAsState()
    val highlightColor = Color(ThemeColors.PALETTE[colorIndex])

    DsMenuScaffold(
        title = stringResource(R.string.choose_room),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarTwoButtons(
                left = stringResource(R.string.btn_options),
                onLeft = { controller.showOptions() },
                right = stringResource(R.string.btn_join),
                onRight = { controller.joinSelected() }
            )
        }
    ) {
        BoxWithHighlight(
            selectedIndex = selectedIndex,
            highlightColor = highlightColor,
            modifier = Modifier.fillMaxSize()
        ) { register, _ ->
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(0.15f))
                Column(
                    Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                        .padding(vertical = 12.dp)
                ) {
                    Room.entries.forEachIndexed { index, room ->
                        if (index < 3) {
                            RoomRow(
                                letter = room.letter,
                                label = room.label,
                                countText = roomCountText(counts[room] ?: 0),
                                onClick = {
                                    controller.selectRoom(index)
                                    controller.joinSelected()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                    .onGloballyPositioned { register(index, it) }
                            )
                        } else {
                            // Online globe row
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                GlobeButton(
                                    onClick = {
                                        controller.selectRoom(3)
                                        controller.joinSelected()
                                    },
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(vertical = 4.dp)
                                        .aspectRatio(1f)
                                        .onGloballyPositioned { register(3, it) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(0.15f))
            }
        }
    }
}

@Composable
private fun roomCountText(count: Int): String =
    if (count >= Constants.MAX_ROOM_USERS) stringResource(R.string.room_full)
    else stringResource(R.string.room_count_format, count)

@Composable
internal fun RoomRow(
    letter: String,
    label: String,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .background(RoomRowBackground)
            .border(1.dp, DsColors.roomRowBorder)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Room letter box
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .background(DsColors.white)
                .border(1.dp, DsColors.grayStripe),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val fontSize = with(density) { (constraints.maxHeight * 0.5f).toSp() }
            OutlinedText(
                text = letter,
                style = TextStyle(
                    fontFamily = PicoFonts.pressStart,
                    fontSize = fontSize,
                    color = DsColors.canvasWhite
                ),
                outlineColor = Color(0xFF303030),
                outlineWidth = with(density) { 3.dp.toPx() }
            )
        }
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 15.sp,
                color = DsColors.black
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 6.dp)
        )
        Image(
            painter = painterResource(R.drawable.ic_user_count),
            contentDescription = null,
            modifier = Modifier
                .fillMaxHeight(0.45f)
                .aspectRatio(1f)
                .padding(end = 3.dp)
        )
        Box(
            Modifier
                .padding(end = 2.dp)
                .background(DsColors.white)
                .border(1.dp, DsColors.grayStripe)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = countText,
                maxLines = 1,
                style = TextStyle(
                    fontFamily = PicoFonts.cozette,
                    fontSize = 13.sp,
                    color = DsColors.black
                )
            )
        }
    }
}

@Composable
private fun GlobeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .background(RoomRowBackground)
            .border(1.dp, DsColors.roomRowBorder)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val fontSize = with(density) { (constraints.maxHeight * 0.8f).toSp() }
        BasicText(
            text = "\uF0AC",
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = fontSize,
                color = DsColors.black
            )
        )
    }
}

// =========================================================================
// Options
// =========================================================================

@Composable
private fun OptionsMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    val selectedIndex by vm.optionsSelectedIndex.collectAsState()
    val highlightColor = Color(ThemeColors.PALETTE[colorIndex])
    val hasDrawings = controller.hasExportableDrawings()

    DsMenuScaffold(
        title = stringResource(R.string.options_title),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarTwoButtons(
                left = stringResource(R.string.btn_back),
                onLeft = { controller.showRoomSelection() },
                right = stringResource(R.string.btn_credits),
                onRight = { controller.showCredits() }
            )
        }
    ) {
        BoxWithHighlight(
            selectedIndex = selectedIndex,
            highlightColor = highlightColor,
            modifier = Modifier.fillMaxSize()
        ) { register, _ ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val labels = listOf(
                    stringResource(R.string.btn_name),
                    stringResource(R.string.btn_color),
                    stringResource(R.string.btn_display_setup),
                    stringResource(R.string.btn_export_chat)
                )
                val actions = listOf<() -> Unit>(
                    { controller.showNameInput() },
                    { controller.showColorPicker() },
                    { controller.showDisplaySetup() },
                    { controller.showExportChat() }
                )
                labels.forEachIndexed { i, label ->
                    val greyed = i == 3 && !hasDrawings
                    DsButton(
                        text = label,
                        onClick = actions[i],
                        textColor = if (greyed) Color.Gray else DsColors.black,
                        borderColor = if (greyed) Color.Gray else DsColors.black,
                        background = if (greyed) {
                            Brush.verticalGradient(
                                listOf(DsColors.grayStripe, DsColors.grayStripe)
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .weight(OPTIONS_MENU_BUTTON_WEIGHT)
                            .padding(vertical = 8.dp)
                            .onGloballyPositioned { register(i, it) }
                    )
                }
            }
        }
    }
}

// =========================================================================
// Name input
// =========================================================================

@Composable
private fun NameInputMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    val nameDraft by vm.nameDraft.collectAsState()
    val username by vm.settings.username.collectAsState()
    val themeColor = Color(ThemeColors.PALETTE[colorIndex])

    DsMenuScaffold(
        title = stringResource(R.string.enter_name_title),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarTwoButtons(
                left = stringResource(R.string.btn_name_quit),
                onLeft = { controller.eraseNameChar() },
                right = stringResource(R.string.btn_name_confirm),
                onRight = { controller.activateNameFocus() }
            )
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            NameInputBoxes(
                text = nameDraft ?: username,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
                    .weight(0.2f)
            )

            Row(
                Modifier
                    .fillMaxWidth(0.82f)
                    .weight(0.62f)
                    .padding(6.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                // Keyboard mode column
                Column(
                    Modifier
                        .width(23.dp)
                        .fillMaxHeight()
                        .padding(end = 3.dp, top = 2.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val modes = listOf(
                        KeyboardMode.LATIN to "A",
                        KeyboardMode.ACCENTED to "À",
                        KeyboardMode.KATAKANA to "あ",
                        KeyboardMode.SYMBOLS to "@",
                        KeyboardMode.EMOTICONS to "😊"
                    )
                    modes.forEachIndexed { i, (mode, label) ->
                        val selected = controller.nameKeyboard.mode == mode
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(top = if (i == 0) 0.dp else 1.dp)
                                .background(
                                    if (selected) themeColor
                                    else DsColors.keyBg
                                )
                                .border(
                                    1.dp,
                                    if (selected) {
                                        Color(
                                            ThemeColors.darken(ThemeColors.PALETTE[colorIndex])
                                        )
                                    } else DsColors.keyBorder
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { controller.setNameKeyboardMode(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedText(
                                text = label,
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
                }

                // Keyboard in white rounded container
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            Color.White,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            DsColors.black,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .padding(3.dp)
                ) {
                    SoftKeyboard(
                        state = controller.nameKeyboard,
                        accentColor = themeColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Cancel / Confirm row
            Row(
                Modifier
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterHorizontally)
                    .padding(start = 26.dp)
            ) {
                KbActionButton(
                    text = "Cancel",
                    focused = controller.nameButtonFocused && controller.nameButtonIndex == 0,
                    leftSide = true,
                    onClick = { controller.cancelNameInput() },
                    modifier = Modifier.weight(1f).height(28.dp)
                )
                KbActionButton(
                    text = "Confirm",
                    focused = controller.nameButtonFocused && controller.nameButtonIndex == 1,
                    leftSide = false,
                    onClick = { controller.confirmNameInput() },
                    modifier = Modifier.weight(1f).height(28.dp)
                )
            }
            Spacer(Modifier.weight(0.10f))
        }
    }
}

@Composable
private fun KbActionButton(
    text: String,
    focused: Boolean,
    leftSide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = if (leftSide) {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 6.dp, bottomStart = 6.dp, topEnd = 0.dp, bottomEnd = 0.dp
        )
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 0.dp, bottomStart = 0.dp, topEnd = 6.dp, bottomEnd = 6.dp
        )
    }
    Box(
        modifier
            .background(Color(0xFFD0D0D0), shape)
            .border(1.dp, DsColors.black, shape)
            .then(
                if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color.Red,
                        shape = shape
                    )
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DsColors.black
            )
        )
    }
}

// =========================================================================
// Color picker
// =========================================================================

@Composable
private fun ColorMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    val draft by vm.colorDraft.collectAsState()
    val effective = if (draft >= 0) draft else colorIndex
    val swatchColor = Color(ThemeColors.PALETTE[effective])

    DsMenuScaffold(
        title = stringResource(R.string.choose_color_title),
        themeColorIndex = effective,
        bottomBar = {
            MenuBottomBarTwoButtons(
                left = stringResource(R.string.btn_color_cancel),
                onLeft = { controller.cancelColor() },
                right = stringResource(R.string.btn_color_confirm),
                onRight = { controller.confirmColor() }
            )
        }
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.48f)
                    .aspectRatio(1f)
                    .background(DsColors.canvasWhite)
                    .border(1.5.dp, Color(0xFF606060))
                    .padding(6.dp)
            ) {
                ColorGrid(
                    selectedIndex = effective,
                    onColorSelected = { controller.previewColor(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .fillMaxHeight(0.5f)
                    .padding(start = 8.dp)
                    .background(DsColors.canvasWhite)
                    .border(1.5.dp, Color(0xFF606060))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x40000000), Color(0x00000000))
                            )
                        )
                )
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BasicText(
                        text = stringResource(R.string.choose_color_title),
                        style = TextStyle(
                            fontFamily = PicoFonts.cozette,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DsColors.black,
                            textAlign = TextAlign.Center
                        )
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .aspectRatio(1f)
                            .background(swatchColor)
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// =========================================================================
// Credits
// =========================================================================

private const val CREDITS_TEXT =
    "Cozette Font\nCopyright (c) the-moonwitch\nLicensed under MIT\n\n" +
        "Press Start 2P Font\nCopyright 2012 Cody Boisclair\nLicensed under SIL OFL 1.1\n\n" +
        "This app is not affiliated with\nor endorsed by Nintendo."

@Composable
private fun CreditsMenu(controller: RoomSelectionController, colorIndex: Int) {
    DsMenuScaffold(
        title = stringResource(R.string.credits_title),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarSingleButton(
                text = stringResource(R.string.btn_credits_back),
                onClick = { controller.handleBack() }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .fillMaxHeight()
                    .background(DsColors.canvasWhite)
                    .border(1.5.dp, Color(0xFF606060))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = CREDITS_TEXT,
                    style = TextStyle(
                        fontFamily = PicoFonts.cozette,
                        fontSize = 11.sp,
                        color = DsColors.black,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

// =========================================================================
// Display setup
// =========================================================================

@Composable
private fun DisplaySetupMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    val selectedIndex by vm.displaySetupSelectedIndex.collectAsState()
    val rotationLocked by vm.settings.rotationLocked.collectAsState()
    val dualScreenActive by vm.dualScreenActive.collectAsState()
    val topSizeIndex by vm.settings.topScreenSizeIndex.collectAsState()
    val topAlignIndex by vm.settings.topScreenAlignmentIndex.collectAsState()
    val highlightColor = Color(ThemeColors.PALETTE[colorIndex])
    val dualScreenOptionGreyed = !dualScreenActive
    val topSizeLabel =
        if (dualScreenOptionGreyed) TopScreenSize.labelForIndex(0)
        else TopScreenSize.labelForIndex(topSizeIndex)
    val topAlignLabel =
        if (dualScreenOptionGreyed) TopScreenAlignment.labelForIndex(TopScreenAlignment.DEFAULT_INDEX)
        else TopScreenAlignment.labelForIndex(topAlignIndex)

    DsMenuScaffold(
        title = stringResource(R.string.display_setup_title),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarSingleButton(
                text = stringResource(R.string.btn_back),
                onClick = { controller.handleBack() }
            )
        }
    ) {
        BoxWithHighlight(
            selectedIndex = selectedIndex,
            highlightColor = highlightColor,
            modifier = Modifier.fillMaxSize()
        ) { register, _ ->
            // Same row height as Options (four weighted rows).
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp)
            ) {
                val buttonHeight = maxHeight / OPTIONS_MENU_BUTTON_ROWS
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    DsButton(
                        text = stringResource(R.string.btn_swap_views),
                        onClick = { controller.swapViews() },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(buttonHeight)
                            .padding(vertical = 8.dp)
                            .onGloballyPositioned { register(0, it) }
                    )
                    DsButton(
                        text = stringResource(
                            if (rotationLocked) R.string.btn_unlock_rotation
                            else R.string.btn_lock_rotation
                        ),
                        onClick = { controller.toggleRotationLock() },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(buttonHeight)
                            .padding(vertical = 8.dp)
                            .onGloballyPositioned { register(1, it) }
                    )
                    DsButton(
                        text = stringResource(R.string.btn_top_size_format, topSizeLabel),
                        onClick = { controller.cycleTopScreenSize() },
                        enabled = !dualScreenOptionGreyed,
                        textColor = if (dualScreenOptionGreyed) Color.Gray else DsColors.black,
                        borderColor = if (dualScreenOptionGreyed) Color.Gray else DsColors.black,
                        background = if (dualScreenOptionGreyed) {
                            Brush.verticalGradient(
                                listOf(DsColors.grayStripe, DsColors.grayStripe)
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(buttonHeight)
                            .padding(vertical = 8.dp)
                            .onGloballyPositioned { register(2, it) }
                    )
                    DsButton(
                        text = stringResource(R.string.btn_top_alignment_format, topAlignLabel),
                        onClick = { controller.cycleTopScreenAlignment() },
                        enabled = !dualScreenOptionGreyed,
                        textColor = if (dualScreenOptionGreyed) Color.Gray else DsColors.black,
                        borderColor = if (dualScreenOptionGreyed) Color.Gray else DsColors.black,
                        background = if (dualScreenOptionGreyed) {
                            Brush.verticalGradient(
                                listOf(DsColors.grayStripe, DsColors.grayStripe)
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(buttonHeight)
                            .padding(vertical = 8.dp)
                            .onGloballyPositioned { register(3, it) }
                    )
                }
            }
        }
    }
}

// =========================================================================
// Export chat
// =========================================================================

@Composable
private fun ExportChatMenu(
    vm: MainViewModel,
    controller: RoomSelectionController,
    colorIndex: Int
) {
    // Keep the preview in sync with the top-screen scroll position.
    LaunchedEffect(Unit) {
        controller.updateExportPreview()
        snapshotFlow { vm.chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { controller.updateExportPreview() }
    }

    DsMenuScaffold(
        title = stringResource(R.string.export_chat_title),
        themeColorIndex = colorIndex,
        bottomBar = {
            MenuBottomBarTwoButtons(
                left = stringResource(R.string.btn_export_no),
                onLeft = { controller.cancelExport() },
                right = stringResource(R.string.btn_export_yes),
                onRight = { controller.startExport() }
            )
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.export_chat_confirm),
                    style = DsBodyTextStyle.copy(textAlign = TextAlign.Center)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DsButton(
                    text = stringResource(R.string.btn_export_scroll_down),
                    onClick = { controller.scrollChatDown() },
                    modifier = Modifier.weight(1f).height(32.dp)
                )
                Box(
                    Modifier
                        .weight(3f)
                        .padding(horizontal = 8.dp)
                        .aspectRatio(Constants.CANVAS_W.toFloat() / Constants.CANVAS_H)
                        .background(DsColors.white)
                ) {
                    controller.currentExportDrawing?.let { drawing ->
                        DrawingImage(
                            bitmap = drawing.bitmap,
                            rainbowBits = drawing.rainbowBits,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                DsButton(
                    text = stringResource(R.string.btn_export_scroll_up),
                    onClick = { controller.scrollChatUp() },
                    modifier = Modifier.weight(1f).height(32.dp)
                )
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.export_scroll_hint),
                    style = DsBodyTextStyle.copy(textAlign = TextAlign.Center)
                )
            }
        }
    }
}

// =========================================================================
// Connecting
// =========================================================================

@Composable
private fun ConnectingMenu(controller: RoomSelectionController, colorIndex: Int) {
    DsMenuScaffold(
        title = stringResource(R.string.connecting_title),
        themeColorIndex = colorIndex
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            controller.connectingFrame?.let { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.5f
                        scaleY = 0.5f
                    }
                )
            }
        }
    }
}

// =========================================================================
// Shared bottom bars
// =========================================================================

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.MenuBottomBarTwoButtons(
    left: String,
    onLeft: () -> Unit,
    right: String,
    onRight: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.7f)
        ) {
            DsButton(
                text = left,
                onClick = onLeft,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp)
            )
            DsButton(
                text = right,
                onClick = onRight,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)
            )
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.MenuBottomBarSingleButton(
    text: String,
    onClick: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DsButton(
            text = text,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.7f)
        )
    }
}
