package com.markusmaribu.picochat.ui.compose.screens.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.ui.compose.components.DsMenuScaffold
import com.markusmaribu.picochat.ui.compose.screens.roomselection.BoxWithHighlight
import com.markusmaribu.picochat.ui.compose.screens.roomselection.MenuBottomBarTwoButtons
import com.markusmaribu.picochat.ui.compose.screens.roomselection.RoomRow
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.ThemeColors

private val onlineLabels = intArrayOf(
    R.string.online_room_a,
    R.string.online_room_b,
    R.string.online_room_c,
    R.string.online_room_d
)

/**
 * Online room list "bottom screen" with live Supabase presence counts
 * (port of OnlineRoomSelectionActivity's bottom-screen content).
 */
@Composable
fun OnlineRoomSelectionBottomScreen(
    vm: MainViewModel,
    initialCounts: List<Int>,
    modifier: Modifier = Modifier
) {
    val controller = vm.onlineRoomSelection
    val colorIndex by vm.settings.colorIndex.collectAsState()
    val selectedIndex by vm.onlineSelectedIndex.collectAsState()
    val counts by controller.roomCounts.collectAsState()
    val highlightColor = Color(ThemeColors.PALETTE[colorIndex])

    LaunchedEffect(controller) {
        controller.seedInitialCounts(initialCounts)
    }

    DisposableEffect(controller) {
        controller.startPresenceTracking()
        onDispose {
            controller.stopPresenceTracking()
        }
    }

    Box(modifier.fillMaxSize()) {
        DsMenuScaffold(
            title = stringResource(R.string.choose_online_room),
            themeColorIndex = colorIndex,
            bottomBar = {
                MenuBottomBarTwoButtons(
                    left = stringResource(R.string.btn_back),
                    onLeft = { controller.goBack() },
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
                            RoomRow(
                                letter = room.letter,
                                label = stringResource(onlineLabels[index]),
                                countText = onlineCountText(counts.getOrElse(index) { 0 }),
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
                        }
                    }
                    Spacer(Modifier.weight(0.15f))
                }
            }
        }

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

@Composable
private fun onlineCountText(count: Int): String =
    if (count >= Constants.MAX_ONLINE_ROOM_USERS) stringResource(R.string.room_full)
    else stringResource(R.string.online_room_count_format, count)
