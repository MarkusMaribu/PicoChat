package com.markusmaribu.picochat.ui.compose.screens.chat

import android.app.Application
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.state.AppScreen
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.state.MenuScreen
import com.markusmaribu.picochat.ui.compose.canvas.CanvasEngine
import com.markusmaribu.picochat.ui.compose.canvas.CanvasTool
import com.markusmaribu.picochat.ui.compose.core.DecelerateEasing
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardMode
import com.markusmaribu.picochat.ui.compose.keyboard.KeyboardState
import com.markusmaribu.picochat.ui.input.GamepadInput
import com.markusmaribu.picochat.ui.input.GamepadInput.isFirstPress
import com.markusmaribu.picochat.ui.input.GamepadInput.isJoystickMove
import com.markusmaribu.picochat.ui.input.GamepadInput.normalizedKeyCode
import com.markusmaribu.picochat.ui.input.GamepadInput.shouldConsumeOnUp
import com.markusmaribu.picochat.util.SoundManager
import kotlinx.coroutines.launch

/**
 * UI-side logic of the old ChatActivity: the shared canvas engine and
 * keyboard state, tool selection, send/retrieve/clear, the leave dialog,
 * enter/leave animations and controller input.
 */
class ChatController(
    app: Application,
    private val vm: MainViewModel,
    val session: ChatSessionController
) {
    private val scope = vm.uiScope
    private val sound = vm.soundManager

    val engine = CanvasEngine(app).apply {
        tool = CanvasTool.PENCIL
        penSize = 3
        usernameForLayout = session.username
    }

    val keyboard = KeyboardState().apply {
        showFocus = true
        onKeyPressed = { ch ->
            if (!engine.appendText(ch)) sound.play(SoundManager.Sound.INVALID)
        }
        onBackspace = { engine.deleteLastChar() }
        onEnter = {
            if (!engine.appendText("\n")) sound.play(SoundManager.Sound.INVALID)
        }
        onTouchDown = { sound.play(SoundManager.Sound.KEY_DOWN) }
        onTouchUp = { sound.play(SoundManager.Sound.KEY_UP) }
    }

    /** 1 = top-screen badges offscreen; animates to 0 on entry. */
    val sidebarSlide = Animatable(1f)

    private var entryPlayed = false

    fun playEntryAnimation() {
        if (entryPlayed) return
        entryPlayed = true
        scope.launch {
            sidebarSlide.animateTo(0f, tween(300, easing = DecelerateEasing))
        }
    }

    // =====================================================================
    // Tools
    // =====================================================================

    fun onPencilClicked() {
        engine.tool = when (engine.tool) {
            CanvasTool.PENCIL -> CanvasTool.RAINBOW
            CanvasTool.RAINBOW -> CanvasTool.PENCIL
            CanvasTool.ERASER -> CanvasTool.PENCIL
        }
        sound.play(SoundManager.Sound.SELECT_PEN)
    }

    fun onEraserClicked() {
        engine.tool = CanvasTool.ERASER
        sound.play(SoundManager.Sound.SELECT_ERASER)
    }

    fun onPenThickClicked() {
        engine.penSize = 3
        sound.play(SoundManager.Sound.BIG_BRUSH)
    }

    fun onPenThinClicked() {
        engine.penSize = 1
        sound.play(SoundManager.Sound.SMALL_BRUSH)
    }

    fun setKeyboardMode(mode: KeyboardMode) {
        keyboard.setKeyboardMode(mode)
        sound.play(SoundManager.Sound.SELECT_LAYOUT)
    }

    private fun cycleKeyboardMode() {
        val modes = KeyboardMode.entries
        setKeyboardMode(modes[(keyboard.mode.ordinal + 1) % modes.size])
    }

    fun onDrawStart(tool: CanvasTool) {
        val s = if (tool == CanvasTool.ERASER) SoundManager.Sound.ERASER
        else SoundManager.Sound.PEN
        sound.playDrawing(s)
    }

    fun onDrawEnd() {
        sound.stopDrawing()
    }

    // =====================================================================
    // Send / retrieve / clear
    // =====================================================================

    fun sendMessage() {
        if (engine.hasDrawing()) {
            val bits = engine.exportBits()
            val rainbowBits = engine.exportRainbowBits()
            val bitmap = engine.getBitmap()
            val msg = ChatMessage.DrawingMessage(
                username = session.username,
                bitmap = bitmap,
                rawBits = bits,
                rainbowBits = rainbowBits,
                colorIndex = session.colorIndex,
                timestamp = ChatRepository.nextTimestamp()
            )
            ChatRepository.addMessage(msg)
            engine.clear()
            session.broadcastMessage(msg)
            sound.play(SoundManager.Sound.SEND)
        } else {
            sound.play(SoundManager.Sound.INVALID)
        }
    }

    fun retrieveDrawing() {
        val allMessages = ChatRepository.getAllMessages()
        val lastVisible = vm.chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?: Int.MAX_VALUE
        val searchFrom = (lastVisible - 1).coerceIn(-1, allMessages.size - 1)

        var drawing: ChatMessage.DrawingMessage? = null
        for (i in searchFrom downTo 0) {
            val msg = allMessages[i]
            if (msg is ChatMessage.DrawingMessage) {
                drawing = msg
                break
            }
        }
        if (drawing != null) {
            engine.importBits(drawing.rawBits)
            engine.importRainbowBits(drawing.rainbowBits)
        }
    }

    fun clearCanvas() {
        engine.clear()
        sound.play(SoundManager.Sound.CLEAR)
    }

    fun scrollUp() = vm.scrollChatUp()

    fun scrollDown() = vm.scrollChatDown()

    fun placeSymbolAt(symbol: String, bitmapX: Int, bitmapY: Int) {
        engine.placeSymbolAt(symbol, bitmapX, bitmapY)
        sound.play(SoundManager.Sound.SYMBOL_DROP)
        keyboard.consumeShiftAfterDrag()
    }

    // =====================================================================
    // Leave dialog + leaving
    // =====================================================================

    var isLeaveDialogShowing by mutableStateOf(false)
        private set
    var leaveDialogFocusedButton by mutableIntStateOf(0) // 0 = No, 1 = Yes

    fun showLeaveDialog() {
        if (isLeaveDialogShowing || session.isLeaving) return
        isLeaveDialogShowing = true
        leaveDialogFocusedButton = 0
        sound.play(SoundManager.Sound.KEY_DOWN)
    }

    fun dismissLeaveDialog() {
        if (!isLeaveDialogShowing) return
        isLeaveDialogShowing = false
        sound.play(SoundManager.Sound.KEY_DOWN)
    }

    fun performAnimatedLeave() {
        if (session.isLeaving || vm.isNavFading) return
        sound.play(SoundManager.Sound.LEAVE_ROOM)
        session.beginLeave()
        isLeaveDialogShowing = false

        scope.launch {
            sidebarSlide.animateTo(1f, tween(200, easing = DecelerateEasing))
        }
        val target = if (session.isOnline) {
            AppScreen.OnlineRoomSelection(List(4) { 0 })
        } else {
            AppScreen.RoomSelection(MenuScreen.RoomList)
        }
        vm.navigateWithFade(target, fadeInMs = 200, fadeOutMs = 200, onCovered = {
            vm.endChatSession()
        })
    }

    fun handleBack() {
        if (isLeaveDialogShowing) {
            dismissLeaveDialog()
        } else if (!session.isLeaving) {
            performAnimatedLeave()
        }
    }

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.normalizedKeyCode()
        if (isLeaveDialogShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (key) {
                    KeyEvent.KEYCODE_BUTTON_B -> {
                        if (event.isFirstPress()) dismissLeaveDialog()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        leaveDialogFocusedButton = 0
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        leaveDialogFocusedButton = 1
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if (!event.isFirstPress()) return true
                        if (leaveDialogFocusedButton == 0) dismissLeaveDialog()
                        else performAnimatedLeave()
                        return true
                    }
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> { keyboard.moveFocusUp(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { keyboard.moveFocusDown(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { keyboard.moveFocusLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { keyboard.moveFocusRight(); return true }
                KeyEvent.KEYCODE_BUTTON_A -> { keyboard.activateFocusedKey(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (engine.textBuffer.isEmpty()) {
                        if (event.isFirstPress()) showLeaveDialog()
                    } else {
                        engine.deleteLastChar()
                        sound.play(SoundManager.Sound.KEY_DOWN)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { vm.scrollChatDown(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { vm.scrollChatUp(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (keyboard.mode == KeyboardMode.LATIN) {
                        keyboard.cycleCaps()
                        sound.play(SoundManager.Sound.KEY_DOWN)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> { cycleKeyboardMode(); return true }
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
        joystickEdge.onMove(event) { x, y ->
            fun vertical(value: Float) {
                if (!isLeaveDialogShowing) {
                    if (value < 0) keyboard.moveFocusUp() else keyboard.moveFocusDown()
                }
            }

            fun horizontal(value: Float) {
                if (isLeaveDialogShowing) {
                    leaveDialogFocusedButton = if (value < 0) 0 else 1
                } else {
                    if (value < 0) keyboard.moveFocusLeft() else keyboard.moveFocusRight()
                }
            }

            if (y != 0) vertical(if (y < 0) -1f else 1f)
            if (x != 0) horizontal(if (x < 0) -1f else 1f)
        }
        return true
    }

    fun dispose() {
        session.stop()
    }
}
