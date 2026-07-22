package com.markusmaribu.picochat.ui.compose.screens.online

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.viewModelScope
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.online.SupabaseProvider
import com.markusmaribu.picochat.state.AppScreen
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.ui.input.GamepadInput
import com.markusmaribu.picochat.ui.input.GamepadInput.isFirstPress
import com.markusmaribu.picochat.ui.input.GamepadInput.isJoystickMove
import com.markusmaribu.picochat.ui.input.GamepadInput.normalizedKeyCode
import com.markusmaribu.picochat.ui.input.GamepadInput.shouldConsumeOnUp
import com.markusmaribu.picochat.state.MenuScreen
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Logic of the old OnlineRoomSelectionActivity: live Supabase presence
 * counts per room, join/back navigation with gray fades, controller input.
 */
class OnlineRoomSelectionController(private val vm: MainViewModel) {

    private val scope = vm.uiScope
    private val sound = vm.soundManager

    private val _roomCounts = MutableStateFlow(List(4) { 0 })
    val roomCounts: StateFlow<List<Int>> = _roomCounts.asStateFlow()

    private val roomMemberKeys = Array(4) { mutableSetOf<String>() }
    private val presenceChannels = mutableListOf<RealtimeChannel>()
    private var presenceJob: Job? = null
    private var channelCleanupJob: Job? = null
    private var countsSeeded = false

    /** Counts fetched during the connecting flow, shown until presence syncs. */
    fun seedInitialCounts(counts: List<Int>) {
        if (countsSeeded) return
        countsSeeded = true
        _roomCounts.value = List(4) { counts.getOrElse(it) { 0 } }
    }

    // =====================================================================
    // Presence tracking (runs while the screen is visible)
    // =====================================================================

    fun startPresenceTracking() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            channelCleanupJob?.join()
            channelCleanupJob = null
            SupabaseProvider.pendingChannelCleanup?.join()
            SupabaseProvider.pendingChannelCleanup = null

            val client = SupabaseProvider.client
            Room.entries.forEachIndexed { i, room ->
                val ch = client.channel("online-room-${room.letter}")
                presenceChannels.add(ch)

                launch {
                    var synced = false
                    ch.presenceChangeFlow().collect { action ->
                        if (!synced) {
                            roomMemberKeys[i].clear()
                            synced = true
                        }
                        roomMemberKeys[i].addAll(action.joins.keys)
                        roomMemberKeys[i].removeAll(action.leaves.keys)
                        _roomCounts.value = _roomCounts.value.toMutableList().also {
                            it[i] = roomMemberKeys[i].size
                        }
                    }
                }

                launch {
                    try {
                        ch.subscribe(blockUntilSubscribed = true)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopPresenceTracking() {
        presenceJob?.cancel()
        presenceJob = null
        val channels = presenceChannels.toList()
        presenceChannels.clear()
        if (channels.isNotEmpty()) {
            channelCleanupJob = CoroutineScope(Dispatchers.IO).launch {
                channels.forEach { ch ->
                    try { ch.unsubscribe() } catch (_: Exception) {}
                    try {
                        SupabaseProvider.client.realtime.removeChannel(ch)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    fun selectRoom(index: Int) {
        vm.setOnlineSelectedIndex(index)
    }

    fun joinSelected() {
        if (vm.isNavFading) return
        val index = vm.onlineSelectedIndex.value
        if (roomMemberKeys[index].size >= Constants.MAX_ONLINE_ROOM_USERS) {
            sound.play(SoundManager.Sound.INVALID)
            return
        }
        sound.play(SoundManager.Sound.JOIN)
        vm.navigateWithFade(AppScreen.Chat(Room.entries[index], isOnline = true))
    }

    fun goBack() {
        if (vm.isNavFading) return
        sound.play(SoundManager.Sound.SELECT)
        vm.navigateWithFade(
            AppScreen.RoomSelection(MenuScreen.RoomList),
            fadeOutMs = 200
        )
    }

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    private fun handleControllerDirection(dy: Int) {
        if (dy != 0) {
            val newIndex = (vm.onlineSelectedIndex.value + dy).coerceIn(0, 3)
            if (newIndex != vm.onlineSelectedIndex.value) {
                vm.setOnlineSelectedIndex(newIndex)
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.normalizedKeyCode()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> { handleControllerDirection(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleControllerDirection(1); return true }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (!event.isFirstPress()) return true
                    joinSelected(); return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (!event.isFirstPress()) return true
                    goBack(); return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { vm.scrollChatDown(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { vm.scrollChatUp(); return true }
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.shouldConsumeOnUp()) {
            return true
        }
        return false
    }

    private val joystickEdge = GamepadInput.JoystickEdgeTracker()

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!event.isJoystickMove()) return false
        if (vm.isNavFading) return true
        joystickEdge.onMove(event) { _, dy ->
            if (dy != 0) handleControllerDirection(dy)
        }
        return true
    }
}
