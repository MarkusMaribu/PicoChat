package com.markusmaribu.picochat.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.state.AppScreen
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.state.TopScreenAlignment
import com.markusmaribu.picochat.state.TopScreenSize
import com.markusmaribu.picochat.ui.compose.core.DsScreen
import com.markusmaribu.picochat.ui.compose.theme.PicoFonts
import com.markusmaribu.picochat.ui.compose.core.DsTransitionState
import com.markusmaribu.picochat.ui.compose.screens.TopScreen
import com.markusmaribu.picochat.ui.compose.screens.chat.ChatBottomScreen
import com.markusmaribu.picochat.ui.compose.screens.online.OnlineRoomSelectionBottomScreen
import com.markusmaribu.picochat.ui.compose.screens.roomselection.RoomSelectionBottomScreen
import com.markusmaribu.picochat.ui.compose.theme.DsColors

/**
 * Root layout of the phone window: places the two DS screens according to
 * orientation / view-swap / secondary-display state (port of the old
 * fitScreensToParent + layoutPortrait/layoutLandscape/layoutFullscreen).
 */
@Composable
fun AppRoot(
    vm: MainViewModel,
    swap: DsTransitionState,
    secondaryDisplayActive: Boolean,
    onSwitchViews: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewsSwapped by vm.settings.viewsSwapped.collectAsState()
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier
            .fillMaxSize()
            .background(DsColors.black)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        when {
            secondaryDisplayActive ->
                DsScreenPane(vm, swap, isTop = !viewsSwapped, Modifier.fillMaxSize())

            !isLandscape -> PortraitLayout(vm, swap, viewsSwapped)

            else -> LandscapeLayout(vm, swap, viewsSwapped, onSwitchViews)
        }
    }
}

/** Two stacked 4:3 panes: top pane bottom-aligned, bottom pane top-aligned. */
@Composable
private fun PortraitLayout(vm: MainViewModel, swap: DsTransitionState, viewsSwapped: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfH = maxHeight / 2
        val paneW = minOf(maxWidth, halfH * 4f / 3f)
        val paneH = paneW * 3f / 4f

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                DsScreenPane(vm, swap, isTop = !viewsSwapped, Modifier.size(paneW, paneH))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                DsScreenPane(vm, swap, isTop = viewsSwapped, Modifier.size(paneW, paneH))
            }
        }
    }
}

/** Big pane on the right, small pane top-left, SWITCH button bottom-left. */
@Composable
private fun LandscapeLayout(
    vm: MainViewModel,
    swap: DsTransitionState,
    viewsSwapped: Boolean,
    onSwitchViews: () -> Unit
) {
    val screen by vm.screen.collectAsState()
    val effSwapped =
        if (screen is AppScreen.Chat) viewsSwapped != vm.landscapeSwapped else viewsSwapped

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bigW = minOf(maxHeight * 4f / 3f, maxWidth * 3f / 4f)
        val smallW = maxWidth - bigW
        val smallH = minOf(smallW * 3f / 4f, maxHeight)

        // Right (big): the bottom screen unless swapped; left (small): the other.
        DsScreenPane(
            vm, swap,
            isTop = effSwapped,
            Modifier.align(Alignment.TopEnd).width(bigW).fillMaxHeight()
        )
        DsScreenPane(
            vm, swap,
            isTop = !effSwapped,
            Modifier.align(Alignment.TopStart).size(smallW, smallH)
        )

        SwitchViewsButton(
            onClick = onSwitchViews,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
}

/** Port of the old landscape-only btnSwitchViews TextView. */
@Composable
private fun SwitchViewsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(DsColors.quitButtonTop, DsColors.quitButtonBottom)
                )
            )
            .border(1.dp, DsColors.black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText(
            text = stringResource(R.string.btn_switch_views),
            style = TextStyle(
                fontFamily = PicoFonts.cozette,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DsColors.black
            )
        )
    }
}

/**
 * One DS screen inside a [DsScreen] scaler, wired to the shared swap
 * transition (gray overlay + opposite vertical slides on the two screens).
 */
@Composable
fun DsScreenPane(
    vm: MainViewModel,
    swap: DsTransitionState,
    isTop: Boolean,
    modifier: Modifier = Modifier,
    contentRotation: Int = 0,
    /** When true, Top Size / Top Alignment apply to this window (phone), not the presentation. */
    isMainDisplayWindow: Boolean = true,
) {
    val dualScreenActive by vm.dualScreenActive.collectAsState()
    val topSizeIndex by vm.settings.topScreenSizeIndex.collectAsState()
    val topAlignIndex by vm.settings.topScreenAlignmentIndex.collectAsState()
    val applyCustomLayout = isMainDisplayWindow && dualScreenActive
    val scale =
        if (applyCustomLayout) TopScreenSize.fractionForIndex(topSizeIndex) else 1f
    val customVerticalAlign = TopScreenAlignment.alignmentForIndex(topAlignIndex)
    val direction = if (isTop) -1f else 1f

    BoxWithConstraints(modifier) {
        val innerModifier =
            if (scale >= 1f) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(maxWidth * scale, maxHeight * scale)
            }
        val paneAlign = when {
            applyCustomLayout -> customVerticalAlign
            !isTop -> Alignment.TopCenter
            else -> Alignment.Center
        }
        DsScreen(
            modifier = innerModifier.align(paneAlign),
            contentRotation = contentRotation,
            contentOffsetYFraction = direction * swap.contentOffsetFraction.value,
            swapBackgroundColor = if (swap.overlayActive) DsColors.grayStripe else Color.Unspecified,
            overlayAlpha = swap.overlayAlpha.value
        ) {
            if (isTop) TopScreenContent(vm) else BottomScreenContent(vm)
        }
    }
}

/** The shared top screen: chat history plus (in chat) signal/room badges. */
@Composable
fun TopScreenContent(vm: MainViewModel, modifier: Modifier = Modifier) {
    val messages by vm.chatMessages.collectAsState()
    val screen by vm.screen.collectAsState()

    val chat = if (screen is AppScreen.Chat) vm.chat else null
    val signalLevel = chat?.session?.signalLevel?.collectAsState()?.value
    val roomLetter = (screen as? AppScreen.Chat)?.room?.name

    // Old behavior: jump to the newest message whenever one arrives.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            vm.chatListState.scrollToItem(messages.size)
        }
    }

    TopScreen(
        messages = messages,
        listState = vm.chatListState,
        modifier = modifier.fillMaxSize(),
        signalLevel = signalLevel,
        roomLetter = roomLetter,
        sidebarSlide = chat?.sidebarSlide?.value ?: 0f
    )
}

/** The interactive bottom screen for the current [AppScreen]. */
@Composable
fun BottomScreenContent(vm: MainViewModel, modifier: Modifier = Modifier) {
    val screen by vm.screen.collectAsState()
    when (val s = screen) {
        is AppScreen.RoomSelection ->
            RoomSelectionBottomScreen(vm, s.menu, modifier.fillMaxSize())
        is AppScreen.OnlineRoomSelection ->
            OnlineRoomSelectionBottomScreen(vm, s.initialCounts, modifier.fillMaxSize())
        is AppScreen.Chat ->
            ChatBottomScreen(vm, s.room, s.isOnline, modifier.fillMaxSize())
    }
}
