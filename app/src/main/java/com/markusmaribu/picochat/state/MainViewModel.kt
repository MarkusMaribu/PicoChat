package com.markusmaribu.picochat.state

import android.app.Application
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.ui.compose.screens.chat.ChatController
import com.markusmaribu.picochat.ui.compose.screens.chat.ChatSessionController
import com.markusmaribu.picochat.ui.compose.screens.online.OnlineRoomSelectionController
import com.markusmaribu.picochat.ui.compose.screens.roomselection.RoomSelectionController
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide state owner: navigation state machine, settings, sounds and the
 * key/controller input routing. Survives configuration changes and restores
 * its navigation state after process death via [SavedStateHandle].
 */
class MainViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    val settings: SettingsRepository = SettingsRepository.get(application)
    val soundManager: SoundManager = SoundManager(application)
    val deviceId: String = Constants.getOrCreateDeviceId(application)

    /**
     * [viewModelScope] with a [androidx.compose.runtime.MonotonicFrameClock],
     * so controllers can drive Animatable / list-scroll animations outside of
     * composition. Cancelled with the ViewModel (shares viewModelScope's Job).
     */
    val uiScope = CoroutineScope(viewModelScope.coroutineContext + AndroidUiDispatcher.Main)

    // ---- Chat history (shared top screen) ----

    /** Scroll state of the single top-screen chat list. */
    val chatListState = LazyListState()

    private val _chatMessages = MutableStateFlow(ChatRepository.getAllMessages())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val chatListener: (ChatMessage) -> Unit = {
        _chatMessages.value = ChatRepository.getAllMessages()
    }

    init {
        ChatRepository.addListener(chatListener)
    }

    // Pending scroll target: fast taps advance from the in-flight target
    // instead of re-reading the layout mid-animation (which would compute
    // the same index twice and appear to swallow taps).
    private var chatScrollJob: Job? = null
    private var chatScrollTarget = -1

    private fun currentScrollBase(): Int? =
        if (chatScrollJob?.isActive == true && chatScrollTarget >= 0) chatScrollTarget
        else chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index

    /** Scrolls one message up (SNAP_TO_END semantics, with sounds). */
    fun scrollChatUp() {
        val base = currentScrollBase() ?: return
        if (base > 0) {
            soundManager.play(SoundManager.Sound.SCROLL)
            chatScrollTarget = base - 1
            chatScrollJob = uiScope.launch { snapChatItemBottom(base - 1) }
        } else {
            soundManager.play(SoundManager.Sound.INVALID)
        }
    }

    /** Scrolls one message down (SNAP_TO_END semantics, with sounds). */
    fun scrollChatDown() {
        val base = currentScrollBase() ?: return
        if (base < chatListState.layoutInfo.totalItemsCount - 1) {
            soundManager.play(SoundManager.Sound.SCROLL)
            chatScrollTarget = base + 1
            chatScrollJob = uiScope.launch { snapChatItemBottom(base + 1) }
        } else {
            soundManager.play(SoundManager.Sound.INVALID)
        }
    }

    /** Aligns [index]'s bottom edge with the viewport bottom. */
    private suspend fun snapChatItemBottom(index: Int) {
        val state = chatListState

        fun findItem() = state.layoutInfo.visibleItemsInfo.find { it.index == index }

        // Targets sit at the viewport edge; nudge with tiny instant scrolls
        // until the item is composed (never animateScrollToItem, which would
        // fly the item to the viewport *top* before snapping back).
        var item = findItem()
        var guard = 0
        while (item == null && guard++ < 30) {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return
            val first = visible.first()
            val last = visible.last()
            when {
                index > last.index ->
                    state.scrollBy((last.offset + last.size - info.viewportEndOffset + 1).toFloat())
                index < first.index ->
                    state.scrollBy((first.offset - info.viewportStartOffset - 1).toFloat())
                else -> return
            }
            item = findItem()
        }
        val target = item ?: return
        val viewportBottom = state.layoutInfo.viewportEndOffset
        state.animateScrollBy((target.offset + target.size - viewportBottom).toFloat())
    }

    // ---- Navigation ----

    private val _screen = MutableStateFlow(restoreScreen())
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    fun navigateTo(target: AppScreen) {
        _screen.value = target
        persistScreen(target)
    }

    /** Convenience for moving within the room-selection submenu tree. */
    fun showMenu(menu: MenuScreen) = navigateTo(AppScreen.RoomSelection(menu))

    /**
     * Gray fade used for transitions between top-level screens (replaces the
     * old activity-launch fades). Rendered on the bottom screen by all
     * screens; navigation happens while fully covered.
     */
    val navOverlayAlpha = Animatable(0f)

    private var navFadeJob: Job? = null

    val isNavFading: Boolean get() = navFadeJob?.isActive == true

    fun navigateWithFade(
        target: AppScreen,
        fadeInMs: Int = 200,
        fadeOutMs: Int = 250,
        onCovered: (() -> Unit)? = null
    ) {
        if (isNavFading) return
        navFadeJob = uiScope.launch {
            try {
                navOverlayAlpha.snapTo(0f)
                navOverlayAlpha.animateTo(1f, tween(fadeInMs))
                onCovered?.invoke()
                navigateTo(target)
                navOverlayAlpha.animateTo(0f, tween(fadeOutMs))
            } finally {
                navOverlayAlpha.snapTo(0f)
            }
        }
    }

    private fun persistScreen(screen: AppScreen) {
        when (screen) {
            is AppScreen.RoomSelection -> {
                savedState[KEY_SCREEN] = SCREEN_ROOM_SELECTION
                savedState[KEY_MENU] = screen.menu.name
            }
            is AppScreen.OnlineRoomSelection -> {
                savedState[KEY_SCREEN] = SCREEN_ONLINE
                savedState[KEY_ONLINE_COUNTS] = screen.initialCounts.toIntArray()
            }
            is AppScreen.Chat -> {
                savedState[KEY_SCREEN] = SCREEN_CHAT
                savedState[KEY_CHAT_ROOM] = screen.room.name
                savedState[KEY_CHAT_IS_ONLINE] = screen.isOnline
            }
        }
    }

    private fun restoreScreen(): AppScreen {
        return when (savedState.get<String>(KEY_SCREEN)) {
            SCREEN_ONLINE -> AppScreen.OnlineRoomSelection(
                savedState.get<IntArray>(KEY_ONLINE_COUNTS)?.toList() ?: List(4) { 0 }
            )
            SCREEN_CHAT -> {
                val room = savedState.get<String>(KEY_CHAT_ROOM)
                    ?.let { name -> Room.entries.find { it.name == name } }
                if (room != null) {
                    AppScreen.Chat(room, savedState.get<Boolean>(KEY_CHAT_IS_ONLINE) ?: false)
                } else {
                    AppScreen.RoomSelection()
                }
            }
            SCREEN_ROOM_SELECTION -> AppScreen.RoomSelection(
                savedState.get<String>(KEY_MENU)
                    ?.let { name -> MenuScreen.entries.find { it.name == name } }
                    ?: MenuScreen.RoomList
            )
            else -> AppScreen.RoomSelection()
        }
    }

    // ---- Persisted selection / draft state (old onSaveInstanceState) ----

    val roomSelectedIndex: StateFlow<Int> = savedState.getStateFlow(KEY_SELECTED_INDEX, 0)
    fun setRoomSelectedIndex(index: Int) { savedState[KEY_SELECTED_INDEX] = index }

    val optionsSelectedIndex: StateFlow<Int> = savedState.getStateFlow(KEY_OPTIONS_INDEX, 0)
    fun setOptionsSelectedIndex(index: Int) { savedState[KEY_OPTIONS_INDEX] = index }

    val displaySetupSelectedIndex: StateFlow<Int> = savedState.getStateFlow(KEY_DISPLAY_SETUP_INDEX, 0)
    fun setDisplaySetupSelectedIndex(index: Int) { savedState[KEY_DISPLAY_SETUP_INDEX] = index }

    val onlineSelectedIndex: StateFlow<Int> = savedState.getStateFlow(KEY_ONLINE_INDEX, 0)
    fun setOnlineSelectedIndex(index: Int) { savedState[KEY_ONLINE_INDEX] = index }

    /** Name being edited on the NameInput screen (null when not editing). */
    val nameDraft: StateFlow<String?> = savedState.getStateFlow(KEY_NAME_DRAFT, null)
    fun setNameDraft(draft: String?) { savedState[KEY_NAME_DRAFT] = draft }

    /** Color being previewed on the Color screen (-1 when not editing). */
    val colorDraft: StateFlow<Int> = savedState.getStateFlow(KEY_COLOR_DRAFT, -1)
    fun setColorDraft(draft: Int) { savedState[KEY_COLOR_DRAFT] = draft }

    /** The theme color currently shown: the draft while previewing, else the setting. */
    fun effectiveColorIndex(): Int {
        val draft = colorDraft.value
        return if (draft >= 0) draft else settings.colorIndex.value
    }

    // ---- Screen controllers ----

    /** Logic holder for the room-selection screen (lives for the app session). */
    val roomSelection: RoomSelectionController by lazy {
        RoomSelectionController(getApplication(), this)
    }

    /** Logic holder for the online room-selection screen. */
    val onlineRoomSelection: OnlineRoomSelectionController by lazy {
        OnlineRoomSelectionController(this)
    }

    /** Chat controller (and its network session), alive while in a chat. */
    var chat: ChatController? by mutableStateOf(null)
        private set

    /**
     * Transient landscape-only swap used by the Chat screen's SWITCH button
     * (the old ChatActivity.landscapeSwapped); other screens' SWITCH button
     * persists [SettingsRepository.viewsSwapped] instead.
     */
    var landscapeSwapped by mutableStateOf(false)

    /** Returns the controller for this chat, (re)creating the session if needed. */
    fun ensureChatController(room: Room, isOnline: Boolean): ChatController {
        val existing = chat
        if (existing != null &&
            existing.session.room == room &&
            existing.session.isOnline == isOnline
        ) {
            return existing
        }
        existing?.dispose()
        val session = ChatSessionController(getApplication(), this, room, isOnline)
        val controller = ChatController(getApplication(), this, session)
        chat = controller
        session.start()
        return controller
    }

    fun endChatSession() {
        chat?.dispose()
        chat = null
    }

    // ---- Input routing ----
    //
    // MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent stay the
    // single entry point. Routing follows [screen] so gamepad input is
    // never dropped while composables reattach handlers.

    fun dispatchKeyEvent(event: KeyEvent): Boolean = when (val s = _screen.value) {
        is AppScreen.RoomSelection -> roomSelection.handleKeyEvent(event)
        is AppScreen.OnlineRoomSelection -> onlineRoomSelection.handleKeyEvent(event)
        is AppScreen.Chat -> chat?.handleKeyEvent(event) ?: false
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = when (val s = _screen.value) {
        is AppScreen.RoomSelection -> roomSelection.handleMotionEvent(event)
        is AppScreen.OnlineRoomSelection -> onlineRoomSelection.handleMotionEvent(event)
        is AppScreen.Chat -> chat?.handleMotionEvent(event) ?: false
    }

    /** True while a secondary display is connected (dual-screen layout). */
    private val _dualScreenActive = MutableStateFlow(false)
    val dualScreenActive: StateFlow<Boolean> = _dualScreenActive.asStateFlow()

    fun setDualScreenActive(active: Boolean) {
        _dualScreenActive.value = active
        if (!active) {
            settings.resetTopScreenSizeToFull()
            settings.resetTopScreenAlignmentToCenter()
        }
    }

    // ---- Activity-level hooks (set by MainActivity) ----

    /** Triggers the DS view-swap transition in whatever layout is active. */
    var onSwapViewsRequested: (() -> Unit)? = null

    /** Toggles the rotation lock (needs the Activity for orientation APIs). */
    var onRotationLockToggle: (() -> Unit)? = null

    override fun onCleared() {
        chat?.dispose()
        chat = null
        ChatRepository.removeListener(chatListener)
        soundManager.release()
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val KEY_MENU = "menu_screen"
        private const val KEY_ONLINE_COUNTS = "online_initial_counts"
        private const val KEY_CHAT_ROOM = "chat_room"
        private const val KEY_CHAT_IS_ONLINE = "chat_is_online"
        private const val KEY_SELECTED_INDEX = "selected_index"
        private const val KEY_OPTIONS_INDEX = "options_selected_index"
        private const val KEY_DISPLAY_SETUP_INDEX = "display_setup_selected_index"
        private const val KEY_ONLINE_INDEX = "online_selected_index"
        private const val KEY_NAME_DRAFT = "name_draft"
        private const val KEY_COLOR_DRAFT = "color_draft"

        private const val SCREEN_ROOM_SELECTION = "room_selection"
        private const val SCREEN_ONLINE = "online"
        private const val SCREEN_CHAT = "chat"
    }
}
