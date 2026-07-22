package com.markusmaribu.picochat.ui.compose.screens.roomselection

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.ble.BleScanner
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.online.SupabaseProvider
import com.markusmaribu.picochat.state.AppScreen
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.state.MenuScreen
import com.markusmaribu.picochat.ui.compose.canvas.CanvasEngine
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardMode
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardState
import com.markusmaribu.picochat.ui.input.GamepadInput
import com.markusmaribu.picochat.ui.input.GamepadInput.isFirstPress
import com.markusmaribu.picochat.ui.input.GamepadInput.isJoystickMove
import com.markusmaribu.picochat.ui.input.GamepadInput.normalizedKeyCode
import com.markusmaribu.picochat.ui.input.GamepadInput.shouldConsumeOnUp
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import com.markusmaribu.picochat.util.ThemeColors
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * All non-layout logic of the old RoomSelectionActivity: menu fade
 * transitions, name/color drafts, BLE room-count scanning, the online
 * connecting flow, chat export, and controller/D-pad input.
 */
class RoomSelectionController(
    private val app: Application,
    private val vm: MainViewModel
) {
    private val scope = vm.uiScope
    private val settings = vm.settings
    private val sound = vm.soundManager

    /** Gray overlay used for the 200ms fade between submenus. */
    val overlayAlpha = Animatable(0f)

    var isTransitioning by mutableStateOf(false)
        private set

    private val currentMenu: MenuScreen
        get() = (vm.screen.value as? AppScreen.RoomSelection)?.menu ?: MenuScreen.RoomList

    // =====================================================================
    // Menu transitions (fade overlay in -> switch -> fade out)
    // =====================================================================

    private fun switchMenu(target: MenuScreen, before: () -> Unit = {}): Boolean {
        if (isTransitioning) return false
        isTransitioning = true
        before()
        scope.launch {
            try {
                overlayAlpha.snapTo(0f)
                overlayAlpha.animateTo(1f, tween(200))
                vm.showMenu(target)
                overlayAlpha.animateTo(0f, tween(200))
            } finally {
                overlayAlpha.snapTo(0f)
                isTransitioning = false
            }
        }
        return true
    }

    fun showOptions() {
        if (!switchMenu(MenuScreen.Options) { vm.setOptionsSelectedIndex(0) }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showRoomSelection() {
        if (!switchMenu(MenuScreen.RoomList) { vm.setRoomSelectedIndex(0) }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showNameInput() {
        if (!switchMenu(MenuScreen.NameInput) {
            nameKeyboard.setKeyboardMode(KeyboardMode.LATIN)
            nameKeyboard.showFocus = true
            nameButtonFocused = false
            nameButtonIndex = 0
            vm.setNameDraft(settings.username.value)
        }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showColorPicker() {
        if (!switchMenu(MenuScreen.Color) { vm.setColorDraft(settings.colorIndex.value) }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showCredits() {
        if (!switchMenu(MenuScreen.Credits)) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showDisplaySetup() {
        if (!switchMenu(MenuScreen.DisplaySetup) { vm.setDisplaySetupSelectedIndex(0) }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun showExportChat() {
        val drawings = ChatRepository.getAllMessages()
            .filterIsInstance<ChatMessage.DrawingMessage>()
        if (drawings.isEmpty()) {
            sound.play(SoundManager.Sound.INVALID)
            return
        }
        if (!switchMenu(MenuScreen.ExportChat) { updateExportPreview() }) return
        sound.play(SoundManager.Sound.SELECT)
    }

    private fun backToOptions(playSound: Boolean = true) {
        if (!switchMenu(MenuScreen.Options)) return
        if (playSound) sound.play(SoundManager.Sound.SELECT)
    }

    /** Handles system back; returns false when the app should move to back. */
    fun handleBack(): Boolean {
        when (currentMenu) {
            MenuScreen.Connecting -> {
                cancelConnecting()
                vm.showMenu(MenuScreen.RoomList)
            }
            MenuScreen.Credits -> backToOptions()
            MenuScreen.DisplaySetup -> backToOptions()
            MenuScreen.ExportChat -> {
                exportJob?.cancel()
                backToOptions()
            }
            MenuScreen.Color -> cancelColor()
            MenuScreen.NameInput -> cancelNameInput()
            MenuScreen.Options -> showRoomSelection()
            MenuScreen.RoomList -> return false
        }
        return true
    }

    // =====================================================================
    // Room list / joining
    // =====================================================================

    private var bleScanner: BleScanner? = null

    private val _roomCounts = MutableStateFlow<Map<Room, Int>>(emptyMap())
    val roomCounts: StateFlow<Map<Room, Int>> = _roomCounts.asStateFlow()

    fun hasBlePermissions(): Boolean = BLE_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startScanning() {
        if (!hasBlePermissions()) return
        if (bleScanner == null) bleScanner = BleScanner(app)
        bleScanner?.startScan { counts -> _roomCounts.value = counts }
    }

    fun stopScanning() {
        bleScanner?.stopScan()
    }

    fun selectRoom(index: Int) {
        vm.setRoomSelectedIndex(index)
    }

    fun joinSelected() {
        val index = vm.roomSelectedIndex.value
        if (index == 3) {
            startOnlineConnection()
        } else {
            joinRoom(Room.entries[index])
        }
    }

    private fun joinRoom(room: Room) {
        if (vm.isNavFading) return
        val count = _roomCounts.value[room] ?: 0
        if (count >= Constants.MAX_ROOM_USERS) {
            sound.play(SoundManager.Sound.INVALID)
            return
        }
        sound.play(SoundManager.Sound.JOIN)
        vm.navigateWithFade(AppScreen.Chat(room, isOnline = false))
    }

    // =====================================================================
    // Name input
    // =====================================================================

    val nameKeyboard = KeyboardState().apply {
        hideEnterKey = true
        onKeyPressed = { ch ->
            if (appendNameChar(ch)) {
                sound.play(SoundManager.Sound.KEY_DOWN)
            } else {
                sound.play(SoundManager.Sound.INVALID)
            }
        }
        onBackspace = {
            if (deleteNameChar()) {
                sound.play(SoundManager.Sound.KEY_DOWN)
            } else {
                sound.play(SoundManager.Sound.INVALID)
            }
        }
        onTouchDown = { sound.play(SoundManager.Sound.KEY_DOWN) }
        onTouchUp = { sound.play(SoundManager.Sound.KEY_UP) }
    }

    var nameButtonFocused by mutableStateOf(false)
        private set
    var nameButtonIndex by mutableIntStateOf(0)
        private set

    private fun appendNameChar(ch: String): Boolean {
        val cur = vm.nameDraft.value ?: ""
        if (cur.codePointCount(0, cur.length) >= Constants.USERNAME_MAX_LENGTH) return false
        vm.setNameDraft(cur + ch)
        return true
    }

    fun deleteNameChar(): Boolean {
        val cur = vm.nameDraft.value ?: ""
        if (cur.isEmpty()) return false
        val cpCount = cur.codePointCount(0, cur.length)
        vm.setNameDraft(
            if (cpCount <= 1) "" else cur.substring(0, cur.offsetByCodePoints(0, cpCount - 1))
        )
        return true
    }

    fun eraseNameChar() {
        if (deleteNameChar()) {
            sound.play(SoundManager.Sound.KEY_DOWN)
        } else {
            sound.play(SoundManager.Sound.INVALID)
        }
    }

    fun setNameKeyboardMode(mode: KeyboardMode) {
        nameKeyboard.setKeyboardMode(mode)
        sound.play(SoundManager.Sound.SELECT_LAYOUT)
    }

    fun cycleNameKeyboardMode() {
        val modes = KeyboardMode.entries
        nameKeyboard.setKeyboardMode(modes[(nameKeyboard.mode.ordinal + 1) % modes.size])
        sound.play(SoundManager.Sound.SELECT_LAYOUT)
    }

    fun cancelNameInput() {
        nameKeyboard.showFocus = false
        vm.setNameDraft(null)
        if (!switchMenu(MenuScreen.Options)) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun confirmNameInput() {
        val name = (vm.nameDraft.value ?: "").trim()
        if (name.isEmpty()) {
            sound.play(SoundManager.Sound.INVALID)
            return
        }
        settings.setUsername(name)
        vm.setNameDraft(null)
        nameKeyboard.showFocus = false
        if (!switchMenu(MenuScreen.Options)) return
        sound.play(SoundManager.Sound.CONFIRM)
    }

    /** Activates whatever the D-pad focus is on (keyboard key or button row). */
    fun activateNameFocus(onCancel: () -> Unit = ::cancelNameInput, onConfirm: () -> Unit = ::confirmNameInput) {
        if (nameButtonFocused) {
            if (nameButtonIndex == 0) onCancel() else onConfirm()
        } else {
            nameKeyboard.activateFocusedKey()
        }
    }

    // =====================================================================
    // Color picker
    // =====================================================================

    fun previewColor(idx: Int) {
        sound.play(SoundManager.Sound.SELECT_LAYOUT)
        vm.setColorDraft(idx)
    }

    fun cancelColor() {
        vm.setColorDraft(-1)
        if (!switchMenu(MenuScreen.Options)) return
        sound.play(SoundManager.Sound.SELECT)
    }

    fun confirmColor() {
        val draft = vm.colorDraft.value
        if (draft >= 0) settings.setColorIndex(draft)
        vm.setColorDraft(-1)
        if (!switchMenu(MenuScreen.Options)) return
        sound.play(SoundManager.Sound.CONFIRM)
    }

    // =====================================================================
    // Display setup
    // =====================================================================

    fun swapViews() {
        sound.play(SoundManager.Sound.SELECT)
        vm.onSwapViewsRequested?.invoke()
    }

    fun toggleRotationLock() {
        sound.play(SoundManager.Sound.SELECT)
        vm.onRotationLockToggle?.invoke()
    }

    fun cycleTopScreenSize() {
        if (!vm.dualScreenActive.value) return
        sound.play(SoundManager.Sound.SELECT)
        settings.cycleTopScreenSizeIndex()
    }

    fun cycleTopScreenAlignment() {
        if (!vm.dualScreenActive.value) return
        sound.play(SoundManager.Sound.SELECT)
        settings.cycleTopScreenAlignmentIndex()
    }

    // =====================================================================
    // Export chat
    // =====================================================================

    var currentExportDrawing by mutableStateOf<ChatMessage.DrawingMessage?>(null)
        private set

    private var exportJob: Job? = null

    fun hasExportableDrawings(): Boolean =
        ChatRepository.getAllMessages().any { it is ChatMessage.DrawingMessage }

    fun updateExportPreview() {
        val lastVisible = vm.chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val messages = vm.chatMessages.value
        val searchFrom = if (lastVisible > 0) lastVisible else messages.size
        for (i in searchFrom.coerceAtMost(messages.size) downTo 1) {
            val msg = messages.getOrNull(i - 1)
            if (msg is ChatMessage.DrawingMessage) {
                currentExportDrawing = msg
                return
            }
        }
    }

    fun startExport() {
        val drawing = currentExportDrawing
        if (drawing == null) {
            sound.play(SoundManager.Sound.INVALID)
            return
        }
        sound.play(SoundManager.Sound.CONFIRM)
        exportJob = scope.launch {
            withContext(Dispatchers.IO) {
                val w = Constants.CANVAS_W * 4
                val h = Constants.CANVAS_H * 4
                val sourceBitmap = if (drawing.rainbowBits != null) {
                    CanvasEngine.compositeRainbowBitmap(drawing.bitmap, drawing.rainbowBits)
                } else {
                    drawing.bitmap
                }
                val scaled = Bitmap.createScaledBitmap(sourceBitmap, w, h, false)
                if (drawing.rainbowBits != null) sourceBitmap.recycle()
                val opaque = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(opaque)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                scaled.recycle()
                saveBitmapToMediaStore(opaque, "PicoChat_${System.currentTimeMillis()}.png")
                opaque.recycle()
            }
            settings.addExportedHashes(listOf(drawing.hash.toString()))
            sound.play(SoundManager.Sound.EXPORT_SUCCESS)
            currentExportDrawing = null
            switchMenu(MenuScreen.Options)
        }
    }

    fun cancelExport() {
        currentExportDrawing = null
        if (!switchMenu(MenuScreen.Options)) return
        sound.play(SoundManager.Sound.SELECT)
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, filename: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicoChat")
        }
        val uri = app.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return
        app.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // =====================================================================
    // Chat scrolling (shared top-screen list)
    // =====================================================================

    fun scrollChatUp() = vm.scrollChatUp()

    fun scrollChatDown() = vm.scrollChatDown()

    // =====================================================================
    // Online connecting flow
    // =====================================================================

    var onlineConnecting by mutableStateOf(false)
        private set
    var connectingFrame by mutableStateOf<Bitmap?>(null)
        private set

    private var connectJob: Job? = null
    private var animationJob: Job? = null

    private val loadingFrames = intArrayOf(
        R.drawable.internet_loading_1,
        R.drawable.internet_loading_2,
        R.drawable.internet_loading_3,
        R.drawable.internet_loading_4
    )

    fun startOnlineConnection() {
        if (onlineConnecting) return
        onlineConnecting = true
        sound.play(SoundManager.Sound.SELECT)
        vm.showMenu(MenuScreen.Connecting)

        val themeColor = ThemeColors.PALETTE[vm.effectiveColorIndex()]
        animationJob = scope.launch {
            val tintedFrames = withContext(Dispatchers.Default) {
                loadingFrames.map { tintBlackPixels(it, themeColor) }
            }
            var frameIndex = 0
            while (true) {
                connectingFrame = tintedFrames[frameIndex]
                frameIndex = (frameIndex + 1) % tintedFrames.size
                delay(100)
            }
        }
        sound.playLooping(SoundManager.Sound.ONLINE_SEARCHING)

        val startTime = System.currentTimeMillis()
        val client = SupabaseProvider.client

        connectJob = scope.launch {
            var connected = false
            try {
                val channel = client.channel("connection-test")
                channel.subscribe()

                val deadline = System.currentTimeMillis() + Constants.ONLINE_CONNECT_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (channel.status.value == RealtimeChannel.Status.SUBSCRIBED) {
                        connected = true
                        break
                    }
                    delay(200)
                }
                try { channel.unsubscribe() } catch (_: Exception) {}
            } catch (_: Exception) {
                connected = false
            }

            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 1000L - elapsed
            if (remaining > 0) delay(remaining)

            if (connected) {
                val roomCounts = fetchInitialPresenceCounts(client)
                stopConnectingAnimation()
                onConnectionSuccess(roomCounts)
            } else {
                stopConnectingAnimation()
                onConnectionFailure()
            }
        }
    }

    fun cancelConnecting() {
        connectJob?.cancel()
        connectJob = null
        stopConnectingAnimation()
        onlineConnecting = false
    }

    private fun stopConnectingAnimation() {
        animationJob?.cancel()
        animationJob = null
        sound.stopLooping()
    }

    private fun onConnectionSuccess(roomCounts: IntArray) {
        sound.play(SoundManager.Sound.ONLINE_FOUND)
        onlineConnecting = false
        vm.navigateWithFade(AppScreen.OnlineRoomSelection(roomCounts.toList()))
    }

    private fun onConnectionFailure() {
        sound.play(SoundManager.Sound.FAILURE)
        onlineConnecting = false
        vm.showMenu(MenuScreen.RoomList)
    }

    private fun tintBlackPixels(resId: Int, color: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val bmp = BitmapFactory.decodeResource(app.resources, resId, opts)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val cr = android.graphics.Color.red(color)
        val cg = android.graphics.Color.green(color)
        val cb = android.graphics.Color.blue(color)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (a > 0 && r < 30 && g < 30 && b < 30) {
                pixels[i] = (a shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    private suspend fun fetchInitialPresenceCounts(client: SupabaseClient): IntArray {
        val roomCounts = IntArray(4)
        val rooms = Room.entries
        val channels = rooms.map { room ->
            client.channel("online-room-${room.letter}")
        }
        try {
            withTimeoutOrNull(5000) {
                channels.forEachIndexed { i, ch ->
                    launch {
                        roomCounts[i] = ch.presenceChangeFlow().first().joins.size
                    }
                    launch {
                        try {
                            ch.subscribe(blockUntilSubscribed = true)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        withContext(Dispatchers.IO) {
            channels.forEach { ch ->
                try { ch.unsubscribe() } catch (_: Exception) {}
                try { client.realtime.removeChannel(ch) } catch (_: Exception) {}
            }
        }
        return roomCounts
    }

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    private fun handleControllerDirection(dx: Int, dy: Int) {
        when (currentMenu) {
            MenuScreen.RoomList -> {
                if (dy != 0) {
                    val newIndex = (vm.roomSelectedIndex.value + dy).coerceIn(0, 3)
                    if (newIndex != vm.roomSelectedIndex.value) {
                        vm.setRoomSelectedIndex(newIndex)
                    }
                }
            }
            MenuScreen.Options -> {
                if (dy != 0) {
                    val newIndex = (vm.optionsSelectedIndex.value + dy).coerceIn(0, 3)
                    if (newIndex != vm.optionsSelectedIndex.value) {
                        vm.setOptionsSelectedIndex(newIndex)
                    }
                }
            }
            MenuScreen.DisplaySetup -> {
                if (dy != 0) {
                    val newIndex = (vm.displaySetupSelectedIndex.value + dy).coerceIn(0, 3)
                    if (newIndex != vm.displaySetupSelectedIndex.value) {
                        vm.setDisplaySetupSelectedIndex(newIndex)
                    }
                }
            }
            MenuScreen.ExportChat -> {
                if (dx < 0) scrollChatDown()
                else if (dx > 0) scrollChatUp()
            }
            MenuScreen.Color -> {
                val cols = 4
                val idx = vm.colorDraft.value.takeIf { it >= 0 } ?: settings.colorIndex.value
                val row = idx / cols
                val col = idx % cols
                val newRow = (row + dy).coerceIn(0, 3)
                val newCol = (col + dx).coerceIn(0, cols - 1)
                val newIdx = newRow * cols + newCol
                if (newIdx != idx && newIdx in ThemeColors.PALETTE.indices) {
                    previewColor(newIdx)
                }
            }
            MenuScreen.NameInput -> {
                if (nameButtonFocused) {
                    if (dy < 0) {
                        nameButtonFocused = false
                        nameKeyboard.showFocus = true
                    }
                    if (dx != 0) {
                        nameButtonIndex = if (nameButtonIndex == 0) 1 else 0
                    }
                } else {
                    if (dy < 0) nameKeyboard.moveFocusUp()
                    else if (dy > 0) {
                        if (!nameKeyboard.moveFocusDown()) {
                            nameButtonFocused = true
                            nameButtonIndex = 0
                            nameKeyboard.showFocus = false
                        }
                    }
                    if (dx < 0) nameKeyboard.moveFocusLeft()
                    else if (dx > 0) nameKeyboard.moveFocusRight()
                }
            }
            MenuScreen.Connecting -> {}
            MenuScreen.Credits -> {}
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.normalizedKeyCode()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> { handleControllerDirection(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleControllerDirection(0, 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { handleControllerDirection(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { handleControllerDirection(1, 0); return true }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (!event.isFirstPress()) return true
                    when (currentMenu) {
                        MenuScreen.RoomList -> { joinSelected(); return true }
                        MenuScreen.Options -> { activateOption(); return true }
                        MenuScreen.DisplaySetup -> { activateDisplaySetupOption(); return true }
                        MenuScreen.ExportChat -> { startExport(); return true }
                        MenuScreen.Color -> { confirmColor(); return true }
                        MenuScreen.NameInput -> { activateNameFocus(); return true }
                        else -> {}
                    }
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (!event.isFirstPress()) return true
                    when (currentMenu) {
                        MenuScreen.RoomList -> { showOptions(); return true }
                        MenuScreen.Options -> { showRoomSelection(); return true }
                        MenuScreen.Color -> { cancelColor(); return true }
                        MenuScreen.Credits -> { backToOptions(); return true }
                        MenuScreen.DisplaySetup -> { backToOptions(); return true }
                        MenuScreen.ExportChat -> { cancelExport(); return true }
                        MenuScreen.NameInput -> { eraseNameChar(); return true }
                        else -> {}
                    }
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (!event.isFirstPress()) return true
                    when (currentMenu) {
                        MenuScreen.Options -> { showCredits(); return true }
                        MenuScreen.NameInput -> { cycleNameKeyboardMode(); return true }
                        else -> {}
                    }
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { scrollChatDown(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { scrollChatUp(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (currentMenu == MenuScreen.NameInput &&
                        nameKeyboard.mode == KeyboardMode.LATIN
                    ) {
                        nameKeyboard.cycleCaps()
                        sound.play(SoundManager.Sound.KEY_DOWN)
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.shouldConsumeOnUp()) {
            return true
        }
        return false
    }

    private fun activateOption() {
        when (vm.optionsSelectedIndex.value) {
            0 -> showNameInput()
            1 -> showColorPicker()
            2 -> showDisplaySetup()
            3 -> showExportChat()
        }
    }

    private fun activateDisplaySetupOption() {
        when (vm.displaySetupSelectedIndex.value) {
            0 -> swapViews()
            1 -> toggleRotationLock()
            2 -> cycleTopScreenSize()
            3 -> cycleTopScreenAlignment()
        }
    }

    private val joystickEdge = GamepadInput.JoystickEdgeTracker()

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!event.isJoystickMove()) return false
        if (isTransitioning || vm.isNavFading) return true
        joystickEdge.onMove(event) { dx, dy -> handleControllerDirection(dx, dy) }
        return true
    }

    companion object {
        val BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }
}
