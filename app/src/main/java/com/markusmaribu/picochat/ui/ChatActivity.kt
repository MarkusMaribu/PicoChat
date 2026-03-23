package com.markusmaribu.picochat.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.ble.BleAdvertiser
import com.markusmaribu.picochat.ble.BleScanner
import com.markusmaribu.picochat.ble.GattClient
import com.markusmaribu.picochat.ble.GattServer
import com.markusmaribu.picochat.ble.L2capManager
import com.markusmaribu.picochat.databinding.ActivityChatBinding
import com.markusmaribu.picochat.mesh.MeshManager
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextPaint
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import com.markusmaribu.picochat.util.ThemeColors
import com.markusmaribu.picochat.util.clearFocusability

class ChatActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = ScaleLayout.targetDensityDpi(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatHistoryAdapter
    private lateinit var room: Room
    private lateinit var username: String
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var gattServer: GattServer? = null
    private var gattClient: GattClient? = null
    private var l2capManager: L2capManager? = null
    private var meshManager: MeshManager? = null
    private var onlineChatManager: com.markusmaribu.picochat.online.OnlineChatManager? = null
    private var isOnline = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var viewsSwapped = false
    private var landscapeSwapped = false
    private var layoutGeneration = 0
    private lateinit var soundManager: SoundManager
    private var colorIndex: Int = ThemeColors.DEFAULT_INDEX
    private var localDeviceIdHash: Int = 0
    private val knownBlePeerIds = mutableMapOf<Int, String>()
    private var blePeerTrackingInitialized = false
    private var blePeerGraceUntil = 0L
    private val departingDeviceIds = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var dragOverlay: FrameLayout? = null
    private var dragGhostView: android.widget.TextView? = null

    // --- Leave chat room dialog ---
    private var isLeaveDialogShowing = false
    private var leaveDialogContainer: FrameLayout? = null
    private var leaveDialogPanel: FrameLayout? = null
    private var leaveDialogHighlight: View? = null
    private var leaveDialogHighlightAnimator: android.animation.ValueAnimator? = null
    private var leaveDialogFocusedButton = 0 // 0 = No, 1 = Yes

    // --- Active bottom-screen view references ---

    private class BottomViews(
        val canvas: PictoCanvasView,
        val keyboard: SoftKeyboardView,
        val usernameLabel: android.widget.TextView,
        val btnClose: View,
        val btnSend: View,
        val btnRetrieve: View,
        val btnClear: View,
        val btnPencil: View,
        val btnEraser: View,
        val btnPenThick: View,
        val btnPenThin: View,
        val btnScrollUp: View,
        val btnScrollDown: View,
        val btnKbLatin: View,
        val btnKbAccented: View,
        val btnKbKatakana: View,
        val btnKbSymbols: View,
        val btnKbEmoji: View,
        val peerNamesContainer: ViewGroup
    )

    private lateinit var bv: BottomViews

    private fun bottomViewsFromBinding() = BottomViews(
        canvas       = binding.pictoCanvas,
        keyboard     = binding.softKeyboard,
        usernameLabel = binding.canvasUsername,
        btnClose     = binding.btnClose,
        btnSend      = binding.btnSend,
        btnRetrieve  = binding.btnRetrieve,
        btnClear     = binding.btnClear,
        btnPencil    = binding.btnPencil,
        btnEraser    = binding.btnEraser,
        btnPenThick  = binding.btnPenThick,
        btnPenThin   = binding.btnPenThin,
        btnScrollUp  = binding.btnScrollUp,
        btnScrollDown = binding.btnScrollDown,
        btnKbLatin   = binding.btnKbLatin,
        btnKbAccented = binding.btnKbAccented,
        btnKbKatakana = binding.btnKbKatakana,
        btnKbSymbols = binding.btnKbSymbols,
        btnKbEmoji   = binding.btnKbEmoji,
        peerNamesContainer = binding.peerNamesContainer
    )

    private fun bottomViewsFromPresentation(p: CanvasPresentation) = BottomViews(
        canvas       = p.pictoCanvas,
        keyboard     = p.softKeyboard,
        usernameLabel = p.canvasUsername,
        btnClose     = p.btnClose,
        btnSend      = p.btnSend,
        btnRetrieve  = p.btnRetrieve,
        btnClear     = p.btnClear,
        btnPencil    = p.btnPencil,
        btnEraser    = p.btnEraser,
        btnPenThick  = p.btnPenThick,
        btnPenThin   = p.btnPenThin,
        btnScrollUp  = p.btnScrollUp,
        btnScrollDown = p.btnScrollDown,
        btnKbLatin   = p.btnKbLatin,
        btnKbAccented = p.btnKbAccented,
        btnKbKatakana = p.btnKbKatakana,
        btnKbSymbols = p.btnKbSymbols,
        btnKbEmoji   = p.btnKbEmoji,
        peerNamesContainer = p.peerNamesContainer
    )

    // --- Secondary display management ---

    private var displayManager: DisplayManager? = null
    private var canvasPresentation: CanvasPresentation? = null
    private var chatHistoryPresentation: ChatHistoryPresentation? = null
    private var presentationChatAdapter: ChatHistoryAdapter? = null
    private var isSecondaryDisplayActive = false
    private var internalOverlay: View? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayRemoved(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayChanged(displayId: Int) {
            if (!isSecondaryDisplayActive || displayId == Display.DEFAULT_DISPLAY) return
            reconnectSecondaryDisplay()
        }
    }

    // --- Chat listener ---

    private val chatListener: (ChatMessage) -> Unit = { msg ->
        runOnUiThread {
            adapter.addMessage(msg)
            binding.scrollBarVisualizer.addMessage(msg)
            binding.chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
            binding.chatRecyclerView.post { updateScrollBarVisibleRange() }
            presentationChatAdapter?.let { pAdapter ->
                pAdapter.addMessage(msg)
                chatHistoryPresentation?.chatRecyclerView?.scrollToPosition(pAdapter.itemCount - 1)
            }

            if (msg is ChatMessage.SystemMessage) {
                val text = msg.text
                when {
                    text.startsWith("Now entering") ->
                        soundManager.play(SoundManager.Sound.ENTER_ROOM)
                    text.startsWith("Now leaving") && !isLeaving ->
                        soundManager.play(SoundManager.Sound.LEAVE_ROOM)
                }
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.clearFocusability()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        room = Room.valueOf(intent.getStringExtra(Constants.EXTRA_ROOM) ?: Room.A.name)
        username = intent.getStringExtra(Constants.EXTRA_USERNAME) ?: "Player"
        colorIndex = intent.getIntExtra(Constants.EXTRA_COLOR_INDEX, ThemeColors.DEFAULT_INDEX)
        localDeviceIdHash = Constants.deviceIdHash(Constants.getOrCreateDeviceId(this))
        isOnline = intent.getBooleanExtra(Constants.EXTRA_IS_ONLINE, false)
        val prefs = getSharedPreferences("picochat_prefs", MODE_PRIVATE)
        viewsSwapped = prefs.getBoolean("views_swapped", false)
        if (prefs.getBoolean("rotation_locked", false)) {
            requestedOrientation = prefs.getInt(
                "locked_orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            )
        }
        soundManager = SoundManager(this)

        setupChatHistory()
        applyStripedChatBackground()
        adapter.localColorIndex = colorIndex
        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyChatThemeColor()

        binding.roomLetter.text = room.name
        updateSignalIndicator(SignalLevel.NONE)
        setupDisplayManager()
        fitScreensToParent()

        binding.btnSwitchViews.setOnClickListener {
            landscapeSwapped = !landscapeSwapped
            fitScreensToParent()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLeaveDialogShowing) {
                    dismissLeaveDialog()
                } else if (!isLeaving) {
                    performAnimatedLeave()
                }
            }
        })

        playEntryAnimation()
        binding.root.post { startBle() }

        val enterStringRes = if (isOnline) R.string.now_entering_online else R.string.now_entering
        val enterMsg = ChatRepository.postSystemMessage(
            getString(enterStringRes, room.circleLetter, username)
        )

        handler.postDelayed({
            if (isOnline) {
                onlineChatManager?.broadcastSystemMessage(enterMsg.text, enterMsg.timestamp)
            } else {
                meshManager?.broadcastTextToRoom(
                    username,
                    Constants.SYSTEM_MSG_PREFIX + enterMsg.text,
                    enterMsg.timestamp
                )
            }
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        binding.root.clearFocusability()
        ChatRepository.addListener(chatListener)
        adapter.setMessages(ChatRepository.getAllMessages())
        binding.scrollBarVisualizer.setMessages(ChatRepository.getAllMessages())
        if (adapter.itemCount > 0) {
            binding.chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
        }
        binding.chatRecyclerView.post { updateScrollBarVisibleRange() }
        presentationChatAdapter?.let { pAdapter ->
            pAdapter.setMessages(ChatRepository.getAllMessages())
            if (pAdapter.itemCount > 0) {
                chatHistoryPresentation?.chatRecyclerView?.scrollToPosition(pAdapter.itemCount - 1)
            }
        }
        acquireWakeLock()
        reconnectOnlineChatIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        ChatRepository.removeListener(chatListener)
        releaseWakeLock()
        if (isLeaving && isOnline) {
            onlineChatManager?.stop()
            onlineChatManager = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isSecondaryDisplayActive) {
            reconnectSecondaryDisplay()
        }
        binding.root.requestLayout()
        binding.root.post { fitScreensToParent() }
    }

    override fun onStart() {
        super.onStart()
        if (!isSecondaryDisplayActive) {
            checkSecondaryDisplay()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isSecondaryDisplayActive) {
            canvasPresentation?.setOnDismissListener(null)
            chatHistoryPresentation?.setOnDismissListener(null)
            onSecondaryDisplayDisconnected()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        displayManager?.unregisterDisplayListener(displayListener)
        dismissAllPresentations()
        if (isLeaving) {
            val sm = soundManager
            Handler(Looper.getMainLooper()).postDelayed({ sm.release() }, 3000)
        } else {
            soundManager.release()
        }
        super.onDestroy()
        stopBle()
    }

    // =====================================================================
    // Bottom-screen wiring (works for both main layout and presentation)
    // =====================================================================

    private fun wireBottomScreen() {
        val v = bv

        v.canvas.tool = Tool.PENCIL
        v.canvas.penSize = 3
        v.canvas.usernameForLayout = username
        v.usernameLabel.text = username

        (v.canvas.parent as? View)?.doOnLayout { frame ->
            val contentH = frame.height - frame.paddingTop - frame.paddingBottom
            val scale = contentH.toFloat() / Constants.CANVAS_H
            v.usernameLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, PictoCanvasView.TEXT_SIZE * scale)
            val hPad = (PictoCanvasView.NAMETAG_H_PADDING * scale).toInt()
            val vPad = (1f * scale).toInt().coerceAtLeast(1)
            v.usernameLabel.setPadding(hPad, vPad, hPad, vPad)

            (v.usernameLabel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = -frame.paddingTop
                lp.marginStart = -frame.paddingStart
                v.usernameLabel.layoutParams = lp
            }
        }

        v.canvas.onToolChanged = { tool ->
            v.btnPencil.isSelected = (tool == Tool.PENCIL)
            v.btnEraser.isSelected = (tool == Tool.ERASER)
        }
        v.canvas.onPenSizeChanged = { size ->
            v.btnPenThick.isSelected = (size == 3)
            v.btnPenThin.isSelected = (size == 1)
        }
        v.canvas.onDrawStart = { tool ->
            val sound = if (tool == Tool.PENCIL) SoundManager.Sound.PEN else SoundManager.Sound.ERASER
            soundManager.playDrawing(sound)
        }
        v.canvas.onDrawEnd = { soundManager.stopDrawing() }

        v.keyboard.showFocus = true
        v.keyboard.onKeyPressed = { ch ->
            if (!v.canvas.appendText(ch)) soundManager.play(SoundManager.Sound.INVALID)
        }
        v.keyboard.onBackspace = { v.canvas.deleteLastChar() }
        v.keyboard.onEnter = {
            if (!v.canvas.appendText("\n")) soundManager.play(SoundManager.Sound.INVALID)
        }
        v.keyboard.onTouchDown = { soundManager.play(SoundManager.Sound.KEY_DOWN) }
        v.keyboard.onTouchUp = { soundManager.play(SoundManager.Sound.KEY_UP) }

        v.btnPencil.setOnClickListener {
            v.canvas.tool = Tool.PENCIL
            soundManager.play(SoundManager.Sound.SELECT_PEN)
        }
        v.btnEraser.setOnClickListener {
            v.canvas.tool = Tool.ERASER
            soundManager.play(SoundManager.Sound.SELECT_ERASER)
        }
        v.btnPenThick.setOnClickListener {
            v.canvas.penSize = 3
            soundManager.play(SoundManager.Sound.BIG_BRUSH)
        }
        v.btnPenThin.setOnClickListener {
            v.canvas.penSize = 1
            soundManager.play(SoundManager.Sound.SMALL_BRUSH)
        }

        v.btnScrollUp.setOnClickListener { scrollChatUp() }
        v.btnScrollDown.setOnClickListener { scrollChatDown() }

        v.btnKbLatin.setOnClickListener {
            setKeyboardMode(KeyboardMode.LATIN)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.btnKbAccented.setOnClickListener {
            setKeyboardMode(KeyboardMode.ACCENTED)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.btnKbKatakana.setOnClickListener {
            setKeyboardMode(KeyboardMode.KATAKANA)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.btnKbSymbols.setOnClickListener {
            setKeyboardMode(KeyboardMode.SYMBOLS)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.btnKbEmoji.setOnClickListener {
            setKeyboardMode(KeyboardMode.EMOTICONS)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }

        v.btnSend.setOnClickListener { sendMessage() }
        v.btnClose.setOnClickListener { performAnimatedLeave() }
        v.btnRetrieve.setOnClickListener {
            val lm = activeChatRecyclerView().layoutManager as? LinearLayoutManager
            val allMessages = ChatRepository.getAllMessages()
            val lastVisible = lm?.findLastVisibleItemPosition() ?: Int.MAX_VALUE
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
                v.canvas.importBits(drawing.rawBits)
            }
        }
        v.btnClear.setOnClickListener {
            v.canvas.clear()
            soundManager.play(SoundManager.Sound.CLEAR)
        }

        v.btnPencil.isSelected = true
        v.btnPenThick.isSelected = true
        v.btnKbLatin.isSelected = true

        val chatKbButtons = listOf(v.btnKbLatin, v.btnKbAccented, v.btnKbKatakana, v.btnKbSymbols, v.btnKbEmoji)
        v.keyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val size = v.keyboard.keyTextSize
            if (size > 0f) {
                for (btn in chatKbButtons) {
                    (btn as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                }
            }
        }

        setupDragToCanvas(v)
    }

    private fun setupDragToCanvas(v: BottomViews) {
        dragOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        dragOverlay = null
        dragGhostView = null

        val kbContainer = v.keyboard.parent as? View ?: return
        val constraintLayout = kbContainer.parent as? ViewGroup ?: return
        val scaleLayout = constraintLayout.parent as? ViewGroup ?: return

        val overlay = FrameLayout(scaleLayout.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }

        val ghost = android.widget.TextView(overlay.context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.cozette_vector)
            setTextColor(0xFF000000.toInt())
            setShadowLayer(2f, 0f, 0f, 0xFFFFFFFF.toInt())
            visibility = View.GONE
        }
        overlay.addView(ghost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        scaleLayout.addView(overlay)
        dragOverlay = overlay
        dragGhostView = ghost

        v.keyboard.onDragStart = { symbol, localX, localY ->
            ghost.text = symbol
            ghost.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.keyboard.keyTextSize)
            ghost.visibility = View.VISIBLE
            ghost.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            ghost.layout(0, 0, ghost.measuredWidth, ghost.measuredHeight)
            val cx = kbContainer.left + kbContainer.paddingLeft + localX
            val cy = kbContainer.top + kbContainer.paddingTop + localY
            ghost.translationX = cx - ghost.measuredWidth / 2f
            ghost.translationY = cy - ghost.measuredHeight / 2f
        }

        v.keyboard.onDragMove = { localX, localY ->
            val cx = kbContainer.left + kbContainer.paddingLeft + localX
            val cy = kbContainer.top + kbContainer.paddingTop + localY
            ghost.translationX = cx - ghost.measuredWidth / 2f
            ghost.translationY = cy - ghost.measuredHeight / 2f
        }

        v.keyboard.onDragEnd = { symbol, localX, localY ->
            ghost.visibility = View.GONE
            val cx = kbContainer.left + kbContainer.paddingLeft + localX
            val cy = kbContainer.top + kbContainer.paddingTop + localY
            val canvasFrame = v.canvas.parent as View
            if (cx >= canvasFrame.left && cx <= canvasFrame.right &&
                cy >= canvasFrame.top && cy <= canvasFrame.bottom &&
                v.canvas.width > 0 && v.canvas.height > 0) {
                val canvasLocalX = cx - canvasFrame.left - canvasFrame.paddingLeft
                val canvasLocalY = cy - canvasFrame.top - canvasFrame.paddingTop
                val bitmapX = (canvasLocalX * Constants.CANVAS_W / v.canvas.width)
                    .toInt().coerceIn(0, Constants.CANVAS_W - 1)
                val bitmapY = (canvasLocalY * Constants.CANVAS_H / v.canvas.height)
                    .toInt().coerceIn(0, Constants.CANVAS_H - 1)
                v.canvas.placeSymbolAt(symbol, bitmapX, bitmapY)
                soundManager.play(SoundManager.Sound.SYMBOL_DROP)
                v.keyboard.consumeShiftAfterDrag()
            }
        }

        v.keyboard.onDragCancel = {
            ghost.visibility = View.GONE
        }
    }

    private fun setKeyboardMode(mode: KeyboardMode) {
        bv.keyboard.keyboardMode = mode
        bv.btnKbLatin.isSelected = (mode == KeyboardMode.LATIN)
        bv.btnKbAccented.isSelected = (mode == KeyboardMode.ACCENTED)
        bv.btnKbKatakana.isSelected = (mode == KeyboardMode.KATAKANA)
        bv.btnKbSymbols.isSelected = (mode == KeyboardMode.SYMBOLS)
        bv.btnKbEmoji.isSelected = (mode == KeyboardMode.EMOTICONS)
    }

    private fun cycleKeyboardMode() {
        val modes = KeyboardMode.entries
        val next = modes[(bv.keyboard.keyboardMode.ordinal + 1) % modes.size]
        setKeyboardMode(next)
        soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
    }

    private fun scrollChatUp() {
        val rv = activeChatRecyclerView()
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible > 0) {
            val scroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_END
            }
            scroller.targetPosition = lastVisible - 1
            lm.startSmoothScroll(scroller)
            soundManager.play(SoundManager.Sound.SCROLL)
        } else {
            soundManager.play(SoundManager.Sound.INVALID)
        }
    }

    private fun scrollChatDown() {
        val rv = activeChatRecyclerView()
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible < (rv.adapter?.itemCount ?: 0) - 1) {
            val scroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_END
            }
            scroller.targetPosition = lastVisible + 1
            lm.startSmoothScroll(scroller)
            soundManager.play(SoundManager.Sound.SCROLL)
        } else {
            soundManager.play(SoundManager.Sound.INVALID)
        }
    }

    private fun applyStripedChatBackground() {
        applyStripedChatBackground(binding.chatHistoryBackground)
    }

    private fun applyStripedChatBackground(view: View) {
        val bgColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val lineColor = 0xFFDDDDDD.toInt()
        val density = resources.displayMetrics.density
        view.background = StripedDrawable(bgColor, lineColor, 3f * density, 1f * density)
    }

    // =====================================================================
    // Leave Chat Room Dialog
    // =====================================================================

    private fun buildLeaveDialog(): FrameLayout {
        val density = resources.displayMetrics.density
        val font = ResourcesCompat.getFont(this, R.font.cozette_vector)
        val orangeColor = 0xFFFF8800.toInt()
        val cornerRadius = 8f * density

        val scaleLayout = binding.bottomScreen
        val panelW = (scaleLayout.refW * 0.70f).toInt()
        val panelH = (scaleLayout.refH * 0.40f).toInt()

        val container = FrameLayout(this).apply {
            elevation = 20 * density
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }

        val panel = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(panelW, panelH).also {
                it.gravity = android.view.Gravity.CENTER
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
        }
        container.addView(panel)
        leaveDialogPanel = panel

        val strokeW = (3 * density).toInt()
        val outerBorder = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(strokeW, android.graphics.Color.BLACK)
            this.cornerRadius = cornerRadius + strokeW
        }
        val borderBg = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(strokeW, orangeColor)
            this.cornerRadius = cornerRadius
        }
        val stripedBg = StripedDrawable(
            0xFF3A3A3A.toInt(),
            0xFF686868.toInt(),
            3f * density,
            1.5f * density
        )
        val totalBorder = strokeW * 2
        val layered = android.graphics.drawable.LayerDrawable(arrayOf(outerBorder, stripedBg, borderBg)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, totalBorder, totalBorder, totalBorder, totalBorder)
            setLayerInset(2, strokeW, strokeW, strokeW, strokeW)
        }
        panel.background = layered
        panel.setPadding(totalBorder, totalBorder, totalBorder, totalBorder)

        val btnGap = (10 * density).toInt()

        panel.clipChildren = false
        panel.clipToPadding = false

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (10 * density).toInt())
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        content.addView(View(this), android.widget.LinearLayout.LayoutParams(
            0, 0, 1f
        ))

        val label = android.widget.TextView(this).apply {
            text = "Leave Chat Room ${room.letter}?"
            typeface = font
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = android.view.Gravity.CENTER
        }
        content.addView(label, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.marginStart = (16 * density).toInt()
            it.marginEnd = (16 * density).toInt()
        })

        content.addView(View(this), android.widget.LinearLayout.LayoutParams(
            0, 0, 1.2f
        ))

        val btnArea = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        content.addView(btnArea, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            tag = "leave_btn_row"
            setPadding(btnGap, 0, btnGap, 0)
            clipChildren = false
            clipToPadding = false
        }
        btnArea.addView(btnRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        fun makeDialogButton(text: String): android.widget.TextView {
            return android.widget.TextView(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                lp.marginStart = btnGap
                lp.marginEnd = btnGap
                layoutParams = lp
                this.text = text
                typeface = font
                setTextColor(0xFF000000.toInt())
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (12 * density).toInt(), (8 * density).toInt(),
                    (12 * density).toInt(), (8 * density).toInt()
                )
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_quit_button)
            }
        }

        val btnNo = makeDialogButton("No")
        val btnYes = makeDialogButton("Yes")
        btnRow.addView(btnNo)
        btnRow.addView(btnYes)

        btnNo.setOnClickListener { dismissLeaveDialog() }
        btnYes.setOnClickListener { performAnimatedLeave() }

        btnNo.tag = "leave_no"
        btnYes.tag = "leave_yes"

        val highlight = View(this).apply {
            foreground = CornerBracketDrawable(
                bracketColor = ThemeColors.PALETTE[colorIndex],
                strokeWidth = 4f * density,
                outlineColor = android.graphics.Color.WHITE,
                outlineWidth = 1.5f * density,
                expandH = 7f * density,
                expandV = 5f * density
            )
        }
        btnArea.addView(highlight, FrameLayout.LayoutParams(0, 0))
        leaveDialogHighlight = highlight

        return container
    }

    private fun updateLeaveDialogFocus(animate: Boolean = true) {
        val panel = leaveDialogPanel ?: return
        val highlight = leaveDialogHighlight ?: return

        val btnRow = panel.findViewWithTag<android.widget.LinearLayout>("leave_btn_row")
            ?: return
        val target = btnRow.getChildAt(leaveDialogFocusedButton) ?: return

        leaveDialogHighlightAnimator?.cancel()

        val targetX = target.left.toFloat()
        val targetW = target.width
        val targetH = target.height

        val alreadyPositioned = highlight.width > 0

        if (animate && alreadyPositioned) {
            val startX = highlight.translationX
            val startW = highlight.width
            val startH = highlight.height

            leaveDialogHighlightAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 150
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    highlight.translationX = startX + (targetX - startX) * t
                    val lp = highlight.layoutParams
                    lp.width = (startW + (targetW - startW) * t).toInt()
                    lp.height = (startH + (targetH - startH) * t).toInt()
                    highlight.layoutParams = lp
                }
                start()
            }
        } else {
            highlight.translationX = targetX
            val lp = highlight.layoutParams
            lp.width = targetW
            lp.height = targetH
            highlight.layoutParams = lp
        }
    }

    private fun showLeaveDialog() {
        if (isLeaveDialogShowing || isLeaving) return
        isLeaveDialogShowing = true
        leaveDialogFocusedButton = 0

        if (leaveDialogContainer == null) {
            leaveDialogContainer = buildLeaveDialog()
            binding.bottomScreen.addView(leaveDialogContainer)
        }

        val container = leaveDialogContainer!!
        val panel = leaveDialogPanel!!
        container.visibility = View.VISIBLE

        panel.post {
            updateLeaveDialogFocus(animate = false)
            val offScreenY = (binding.bottomScreen.refH / 2f + panel.height / 2f)
            panel.translationY = offScreenY
            panel.animate()
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        soundManager.play(SoundManager.Sound.KEY_DOWN)
    }

    private fun dismissLeaveDialog() {
        if (!isLeaveDialogShowing) return
        val panel = leaveDialogPanel ?: return

        val offScreenY = (binding.bottomScreen.refH / 2f + panel.height / 2f)
        panel.animate()
            .translationY(offScreenY)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                leaveDialogContainer?.visibility = View.GONE
                isLeaveDialogShowing = false
            }
            .start()

        soundManager.play(SoundManager.Sound.KEY_DOWN)
    }

    private fun applyChatThemeColor() {
        val color = ThemeColors.PALETTE[colorIndex]
        val dark = ThemeColors.darken(color)

        val canvasOutline = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            setStroke((2 * resources.displayMetrics.density).toInt(), color)
            cornerRadius = 6 * resources.displayMetrics.density
        }
        (bv.canvas.parent as? View)?.background = canvasOutline

        val nametagBg = GradientDrawable().apply {
            setColor(ThemeColors.brighten(color, 0.85f))
            setStroke((2 * resources.displayMetrics.density).toInt(), color)
            cornerRadii = floatArrayOf(
                4f.dp(), 4f.dp(), 0f, 0f,
                4f.dp(), 4f.dp(), 0f, 0f
            )
        }
        bv.usernameLabel.background = nametagBg
        bv.usernameLabel.setTextColor(color)

        val toolButtons = listOf(
            bv.btnPencil, bv.btnEraser, bv.btnPenThick, bv.btnPenThin,
            bv.btnKbLatin, bv.btnKbAccented, bv.btnKbKatakana, bv.btnKbSymbols, bv.btnKbEmoji
        )
        for (btn in toolButtons) {
            btn.background = makeThemedToolButtonDrawable(color, dark)
        }

        bv.keyboard.accentColor = color

        bv.canvas.ruledLineColor = ThemeColors.brighten(color, 0.85f)
    }

    private fun makeThemedToolButtonDrawable(color: Int, dark: Int): StateListDrawable {
        val selected = GradientDrawable().apply {
            setColor(color)
            setStroke((1 * resources.displayMetrics.density).toInt(), dark)
        }
        val normal = GradientDrawable().apply {
            setColor(resources.getColor(R.color.ds_key_bg, null))
            setStroke((1 * resources.displayMetrics.density).toInt(), resources.getColor(R.color.ds_key_border, null))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private inner class ClampedLayoutManager : LinearLayoutManager(this@ChatActivity) {
        init { stackFromEnd = true }

        override fun scrollVerticallyBy(
            dy: Int,
            recycler: RecyclerView.Recycler,
            state: RecyclerView.State
        ): Int {
            if (dy >= 0) return super.scrollVerticallyBy(dy, recycler, state)

            val bannerView = findViewByPosition(0)
            if (bannerView != null) {
                val room = getDecoratedBottom(bannerView) - height
                if (room >= 0) return 0
                return super.scrollVerticallyBy(maxOf(dy, room), recycler, state)
            }
            return super.scrollVerticallyBy(dy, recycler, state)
        }
    }

    private fun setupChatHistory() {
        adapter = ChatHistoryAdapter()
        binding.chatRecyclerView.layoutManager = ClampedLayoutManager()
        binding.chatRecyclerView.adapter = adapter
        binding.scrollBarVisualizer.includeBanner = true

        binding.chatRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildAdapterPosition(view) == 0) {
                    outRect.top = parent.height
                }
            }
        })

        binding.chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollBarVisibleRange()
            }
        })
    }

    private fun updateScrollBarVisibleRange() {
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        binding.scrollBarVisualizer.setVisibleRange(first, last)
    }

    private fun sendMessage() {
        if (bv.canvas.hasDrawing()) {
            val bits = bv.canvas.exportBits()
            val bitmap = bv.canvas.getBitmap()
            val msg = ChatMessage.DrawingMessage(
                username = username,
                bitmap = bitmap,
                rawBits = bits,
                colorIndex = colorIndex,
                timestamp = ChatRepository.nextTimestamp()
            )
            ChatRepository.addMessage(msg)
            bv.canvas.clear()
            broadcastMessage(msg)
            soundManager.play(SoundManager.Sound.SEND)
        } else {
            soundManager.play(SoundManager.Sound.INVALID)
        }
    }

    fun addMessageLocal(msg: ChatMessage) {
        if (msg is ChatMessage.SystemMessage) {
            when {
                msg.text.startsWith("Now entering") -> {
                    if (!isOnline) {
                        val enteringUser = msg.text.substringAfter(": ", "")
                        if (enteringUser.isNotEmpty()) {
                            departingDeviceIds.entries.removeIf { (id, name) ->
                                if (name == enteringUser) {
                                    bleScanner?.unblockDeviceId(id)
                                    true
                                } else false
                            }
                        }
                        runOnUiThread { updatePeerNames() }
                        return
                    }
                    ChatRepository.addMessage(msg)
                    return
                }
                msg.text.startsWith("Now leaving") -> {
                    if (!isOnline) {
                        val leavingUser = msg.text.substringAfter(": ", "")
                        if (leavingUser.isNotEmpty()) {
                            bleScanner?.getPeersInRoom(room)
                                ?.filter { it.username == leavingUser && it.deviceIdHash != 0 }
                                ?.forEach { departingDeviceIds[it.deviceIdHash] = leavingUser }
                            bleScanner?.evictPeersByName(room, leavingUser)
                        }
                        runOnUiThread { updatePeerNames() }
                        return
                    }
                    ChatRepository.addMessage(msg)
                    return
                }
            }
        }
        ChatRepository.addMessage(msg)
        if (msg !is ChatMessage.SystemMessage) {
            runOnUiThread { soundManager.play(SoundManager.Sound.RECEIVED) }
        }
    }

    private fun broadcastMessage(msg: ChatMessage) {
        if (isOnline) {
            if (msg is ChatMessage.DrawingMessage) {
                onlineChatManager?.broadcastDrawing(msg)
            }
        } else {
            meshManager?.onNewLocalMessage(msg)
        }
    }

    // =====================================================================
    // Secondary display
    // =====================================================================

    private fun setupDisplayManager() {
        displayManager = (getSystemService(DISPLAY_SERVICE) as DisplayManager).also {
            it.registerDisplayListener(displayListener, handler)
        }
        checkSecondaryDisplay()
    }

    private fun checkSecondaryDisplay() {
        val dm = displayManager ?: return
        val secondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        if (secondary != null && !isSecondaryDisplayActive) {
            onSecondaryDisplayConnected(secondary)
        } else if (secondary == null && isSecondaryDisplayActive) {
            onSecondaryDisplayDisconnected()
        }
    }

    private fun onSecondaryDisplayConnected(display: Display, existingCanvasBits: ByteArray? = null) {
        val canvasBits = existingCanvasBits ?: bv.canvas.exportBits()
        val hasContent = existingCanvasBits != null || bv.canvas.hasDrawing()

        if (viewsSwapped) {
            val pres = ChatHistoryPresentation(this, display)
            pres.onBackPressedCallback = { if (!isLeaving) performAnimatedLeave() }
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) {
                    onSecondaryDisplayDisconnected()
                    finish()
                }
            }
            pres.show()
            chatHistoryPresentation = pres

            val pAdapter = ChatHistoryAdapter()
            pAdapter.localColorIndex = colorIndex
            pAdapter.setMessages(ChatRepository.getAllMessages())
            pres.chatRecyclerView.layoutManager = ClampedLayoutManager()
            pres.chatRecyclerView.adapter = pAdapter
            pres.chatRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    if (parent.getChildAdapterPosition(view) == 0) {
                        outRect.top = parent.height
                    }
                }
            })
            presentationChatAdapter = pAdapter
            applyStripedChatBackground(pres.chatHistoryBackground)

            bv = bottomViewsFromBinding()
            wireBottomScreen()
            applyChatThemeColor()
        } else {
            val pres = CanvasPresentation(this, display)
            pres.onBackPressedCallback = { if (!isLeaving) performAnimatedLeave() }
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) {
                    onSecondaryDisplayDisconnected()
                    finish()
                }
            }
            pres.show()
            canvasPresentation = pres

            bv = bottomViewsFromPresentation(pres)
            wireBottomScreen()
            applyChatThemeColor()

            if (hasContent) bv.canvas.importBits(canvasBits)
        }

        isSecondaryDisplayActive = true
        fitScreensToParent()
    }

    private fun reconnectSecondaryDisplay() {
        val dm = displayManager ?: return
        val secondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val canvasBits = if (bv.canvas.hasDrawing()) bv.canvas.exportBits() else null

        val oldCanvasPres = canvasPresentation
        val oldChatPres = chatHistoryPresentation
        canvasPresentation = null
        chatHistoryPresentation = null
        presentationChatAdapter = null

        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyChatThemeColor()

        isSecondaryDisplayActive = false

        if (secondary != null) {
            onSecondaryDisplayConnected(secondary, canvasBits)
        } else {
            if (canvasBits != null) bv.canvas.importBits(canvasBits)
            fitScreensToParent()
        }

        oldCanvasPres?.setOnDismissListener(null)
        oldChatPres?.setOnDismissListener(null)
        handler.postDelayed({
            try { if (oldCanvasPres?.isShowing == true) oldCanvasPres.dismiss() } catch (_: Exception) {}
            try { if (oldChatPres?.isShowing == true) oldChatPres.dismiss() } catch (_: Exception) {}
        }, 150)
    }

    private fun dismissAllPresentations() {
        canvasPresentation?.setOnDismissListener(null)
        canvasPresentation?.dismiss()
        canvasPresentation = null
        chatHistoryPresentation?.setOnDismissListener(null)
        chatHistoryPresentation?.dismiss()
        chatHistoryPresentation = null
        presentationChatAdapter = null
    }

    private fun getOrCreateInternalOverlay(): View {
        internalOverlay?.let { return it }
        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(this@ChatActivity, R.color.ds_gray_stripe))
            elevation = 10 * resources.displayMetrics.density
            outlineProvider = null
            visibility = View.GONE
            alpha = 0f
        }
        binding.bottomScreen.addView(overlay)
        internalOverlay = overlay
        return overlay
    }

    private fun activeChatRecyclerView(): RecyclerView =
        if (isSecondaryDisplayActive && viewsSwapped)
            chatHistoryPresentation?.chatRecyclerView ?: binding.chatRecyclerView
        else
            binding.chatRecyclerView

    private fun activeOverlay(): View? = when {
        isSecondaryDisplayActive && !viewsSwapped -> canvasPresentation?.overlay
        isSecondaryDisplayActive && viewsSwapped  -> getOrCreateInternalOverlay()
        else -> binding.bottomOverlay
    }

    private fun onSecondaryDisplayDisconnected() {
        val canvasBits = if ((canvasPresentation != null || chatHistoryPresentation != null) && bv.canvas.hasDrawing()) {
            bv.canvas.exportBits()
        } else null

        dismissAllPresentations()

        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyChatThemeColor()

        if (canvasBits != null) bv.canvas.importBits(canvasBits)

        isSecondaryDisplayActive = false
        fitScreensToParent()
    }

    // =====================================================================
    // BLE lifecycle
    // =====================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBle() {
        if (isOnline) {
            startOnlineChat()
            return
        }
        val btAdapter = (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (btAdapter == null || !btAdapter.isEnabled) return
        val messageStore = ChatRepository.messageStore
        try {
            bleAdvertiser = BleAdvertiser(this).also {
                it.startAdvertising(room, username, messageStore.getLatestHash(), colorIndex, localDeviceIdHash)
            }
            gattServer = GattServer(this, this)
            l2capManager = L2capManager(this).also { l2 ->
                l2.setMessageProvider { requestedHash ->
                    val msg = ChatRepository.messageStore.getMessage(requestedHash)
                    if (msg is ChatMessage.DrawingMessage) {
                        L2capManager.DrawingPayload(msg.username, msg.hash, msg.timestamp, msg.rawBits, msg.colorIndex)
                    } else null
                }
                l2.setOnTextReceived { data -> onTextReceivedViaL2cap(data) }
                l2.startServer { senderName, hash, timestamp, data, colorIdx ->
                    onDrawingReceived(senderName, hash, timestamp, data, colorIdx)
                }
                gattServer?.setL2capPsm(l2.getServerPsm())
            }
            gattServer?.start { msg -> addMessageLocal(msg) }

            bleScanner = BleScanner(this).also { scanner ->
                scanner.onPeersEvicted = { runOnUiThread { updatePeerNames() } }
                scanner.startScan(lowLatency = true) { }
            }

            gattClient = GattClient(this)
            meshManager = MeshManager(
                context = this,
                messageStore = messageStore,
                bleScanner = bleScanner!!,
                gattClient = gattClient!!,
                l2capManager = l2capManager!!,
                bleAdvertiser = bleAdvertiser!!,
                room = room,
                onMessageReceived = { msg -> addMessageLocal(msg) }
            ).also {
                it.localUsername = username
                it.localColorIndex = colorIndex
                it.localDeviceIdHash = localDeviceIdHash
                it.start()
            }

            handler.post(signalUpdateRunnable)
        } catch (e: SecurityException) {
            // BLE permissions not granted -- fall back to local-only mode
        } catch (e: Exception) {
            // Device may not support BLE advertising -- local-only mode
        }
    }

    private fun startOnlineChat() {
        onlineChatManager = com.markusmaribu.picochat.online.OnlineChatManager(
            room = room,
            localUsername = username,
            localColorIndex = colorIndex,
            localDeviceId = Constants.getOrCreateDeviceId(this),
            onMessageReceived = { msg -> runOnUiThread { addMessageLocal(msg) } }
        ).also { it.start() }
        handler.post(onlineSignalUpdateRunnable)
    }

    private fun reconnectOnlineChatIfNeeded() {
        if (!isOnline) return
        val manager = onlineChatManager ?: return
        if (manager.isSubscribed()) return
        manager.stop()
        onlineChatManager = null
        handler.removeCallbacks(onlineSignalUpdateRunnable)
        startOnlineChat()
    }

    private fun stopBle() {
        onlineChatManager?.stop()
        onlineChatManager = null
        meshManager?.stop()
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScan()
        gattServer?.stop()
        l2capManager?.stopServer()
    }

    private fun onTextReceivedViaL2cap(data: ByteArray) {
        try {
            val raw = String(data, Charsets.UTF_8)
            val parts = raw.split('\u0000', limit = 4)
            if (parts.size >= 2) {
                val senderUsername = parts[0]
                var colorIdx = 0
                val timestamp: Long
                val messageText: String

                if (parts.size >= 4) {
                    colorIdx = parts[1].toIntOrNull()?.coerceIn(0, 15) ?: 0
                    timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                    messageText = parts[3]
                } else if (parts.size == 3) {
                    timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                    messageText = parts[2]
                } else {
                    timestamp = System.currentTimeMillis()
                    messageText = parts[1]
                }

                val msg = if (messageText.startsWith(Constants.SYSTEM_MSG_PREFIX)) {
                    ChatMessage.SystemMessage(
                        text = messageText.removePrefix(Constants.SYSTEM_MSG_PREFIX),
                        timestamp = timestamp
                    )
                } else {
                    ChatMessage.TextMessage(
                        username = senderUsername,
                        text = messageText,
                        colorIndex = colorIdx,
                        timestamp = timestamp
                    )
                }
                addMessageLocal(msg)
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error parsing L2CAP text", e)
        }
    }

    private fun onDrawingReceived(senderName: String, hash: Int, timestamp: Long, data: ByteArray, colorIdx: Int = 0) {
        if (ChatRepository.messageStore.hasMessage(hash)) return

        val bitmap = PictoCanvasView.bitmapFromBits(data)
        val msg = ChatMessage.DrawingMessage(
            username = senderName,
            bitmap = bitmap,
            rawBits = data,
            colorIndex = colorIdx,
            timestamp = timestamp,
            hash = hash
        )
        addMessageLocal(msg)
        meshManager?.scheduleHashUpdate()
    }

    // =====================================================================
    // Transition Animations
    // =====================================================================

    private var isLeaving = false

    private fun playEntryAnimation() {
        val overlay = activeOverlay()
        overlay?.alpha = 1f
        overlay?.visibility = View.VISIBLE

        if (isSecondaryDisplayActive && viewsSwapped) {
            overlay?.animate()
                ?.setListener(null)
                ?.alpha(0f)
                ?.setDuration(250)
                ?.withEndAction { overlay.visibility = View.GONE }
                ?.start()
            return
        }

        binding.signalContainer.doOnLayout {
            binding.signalContainer.translationY = -binding.signalContainer.height.toFloat() - binding.signalContainer.top
            binding.roomLetterContainer.translationY = (binding.topSidebar.height - binding.roomLetterContainer.top).toFloat()

            val fadeOut = ObjectAnimator.ofFloat(overlay ?: return@doOnLayout, View.ALPHA, 1f, 0f).apply {
                duration = 250
            }
            val signalSlide = ObjectAnimator.ofFloat(binding.signalContainer, View.TRANSLATION_Y, binding.signalContainer.translationY, 0f).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
            }
            val roomSlide = ObjectAnimator.ofFloat(binding.roomLetterContainer, View.TRANSLATION_Y, binding.roomLetterContainer.translationY, 0f).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(fadeOut, signalSlide, roomSlide)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        overlay.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    private fun performAnimatedLeave() {
        if (isLeaving) return
        isLeaving = true
        soundManager.play(SoundManager.Sound.LEAVE_ROOM)

        val leaveStringRes = if (isOnline) R.string.now_leaving_online else R.string.now_leaving
        val leaveMsg = ChatRepository.postSystemMessage(
            getString(leaveStringRes, room.circleLetter, username)
        )
        if (isOnline) {
            onlineChatManager?.broadcastSystemMessage(leaveMsg.text, leaveMsg.timestamp)
        } else {
            bleAdvertiser?.stopAdvertising()
            meshManager?.broadcastTextToRoom(
                username,
                Constants.SYSTEM_MSG_PREFIX + leaveMsg.text,
                leaveMsg.timestamp
            )
        }

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE

        val finishAction = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finish()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
        }

        if (isSecondaryDisplayActive && viewsSwapped) {
            if (overlay != null) {
                overlay.animate()
                    .setListener(null)
                    .alpha(1f)
                    .setDuration(200)
                    .withEndAction {
                        finish()
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }
                    }
                    .start()
            } else {
                finish()
            }
            return
        }

        val fadeIn = ObjectAnimator.ofFloat(overlay ?: binding.bottomOverlay, View.ALPHA, 0f, 1f).apply {
            duration = 200
        }
        val signalSlide = ObjectAnimator.ofFloat(
            binding.signalContainer, View.TRANSLATION_Y, 0f,
            -binding.signalContainer.height.toFloat() - binding.signalContainer.top
        ).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
        val roomSlide = ObjectAnimator.ofFloat(
            binding.roomLetterContainer, View.TRANSLATION_Y, 0f,
            (binding.topSidebar.height - binding.roomLetterContainer.top).toFloat()
        ).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(signalSlide, roomSlide, fadeIn)
            addListener(finishAction)
            start()
        }
    }


    // =====================================================================
    // WakeLock
    // =====================================================================

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoChat::ChatWakeLock")
        }
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    // =====================================================================
    // Screen Sizing
    // =====================================================================

    private fun fitScreensToParent() {
        layoutGeneration++
        if (isSecondaryDisplayActive) {
            layoutFullscreen()
            return
        }
        val thisGeneration = layoutGeneration
        binding.root.doOnLayout { root ->
            if (thisGeneration != layoutGeneration) return@doOnLayout
            val parentW = root.width - root.paddingLeft - root.paddingRight
            val parentH = root.height - root.paddingTop - root.paddingBottom

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) {
                layoutLandscape(parentW, parentH)
            } else {
                landscapeSwapped = false
                layoutPortrait(parentW, parentH)
            }
        }
    }

    private fun layoutFullscreen() {
        val fullScreenId = if (!viewsSwapped) R.id.topScreen else R.id.bottomScreen
        val hiddenId = if (!viewsSwapped) R.id.bottomScreen else R.id.topScreen

        val set = ConstraintSet()
        set.clone(binding.root)

        set.clear(fullScreenId)
        set.constrainWidth(fullScreenId, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(fullScreenId, ConstraintSet.MATCH_CONSTRAINT)
        set.connect(fullScreenId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(fullScreenId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(fullScreenId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(fullScreenId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

        set.setVisibility(hiddenId, ConstraintSet.GONE)
        set.setVisibility(R.id.bottomOverlay, ConstraintSet.GONE)
        set.setVisibility(R.id.btnSwitchViews, ConstraintSet.GONE)

        set.applyTo(binding.root)
    }

    private fun layoutPortrait(parentW: Int, parentH: Int) {
        val halfH = parentH / 2
        val scale = minOf(parentW / 4f, halfH / 3f)
        val w = (scale * 4).toInt()
        val h = (scale * 3).toInt()

        val topId = if (!viewsSwapped) R.id.topScreen else R.id.bottomScreen
        val bottomId = if (!viewsSwapped) R.id.bottomScreen else R.id.topScreen

        val set = ConstraintSet()
        set.clone(binding.root)

        set.clear(R.id.topScreen)
        set.clear(R.id.bottomScreen)

        set.constrainWidth(topId, w)
        set.constrainHeight(topId, h)
        set.connect(topId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(topId, ConstraintSet.BOTTOM, R.id.guidelineHalf, ConstraintSet.TOP)
        set.connect(topId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(topId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.setVerticalBias(topId, 1.0f)

        set.setVisibility(R.id.bottomScreen, ConstraintSet.VISIBLE)
        set.setVisibility(R.id.topScreen, ConstraintSet.VISIBLE)
        set.constrainWidth(bottomId, w)
        set.constrainHeight(bottomId, h)
        set.connect(bottomId, ConstraintSet.TOP, R.id.guidelineHalf, ConstraintSet.BOTTOM)
        set.connect(bottomId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(bottomId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(bottomId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.setVerticalBias(bottomId, 0.0f)

        set.setVisibility(R.id.btnSwitchViews, ConstraintSet.GONE)

        set.applyTo(binding.root)
    }

    private fun layoutLandscape(parentW: Int, parentH: Int) {
        val bigW = minOf((parentH * 4f / 3f).toInt(), parentW * 3 / 4)
        val bigH = parentH
        val smallW = parentW - bigW
        val smallH = minOf((smallW * 3f / 4f).toInt(), parentH)

        val effectiveSwapped = viewsSwapped != landscapeSwapped
        val rightId = if (!effectiveSwapped) R.id.bottomScreen else R.id.topScreen
        val leftId  = if (!effectiveSwapped) R.id.topScreen else R.id.bottomScreen

        val set = ConstraintSet()
        set.clone(binding.root)

        set.clear(R.id.topScreen)
        set.clear(R.id.bottomScreen)

        set.setVisibility(R.id.bottomScreen, ConstraintSet.VISIBLE)

        set.constrainWidth(rightId, bigW)
        set.constrainHeight(rightId, bigH)
        set.connect(rightId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(rightId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

        set.constrainWidth(leftId, smallW)
        set.constrainHeight(leftId, smallH)
        set.connect(leftId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(leftId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)

        set.clear(R.id.btnSwitchViews)
        set.constrainWidth(R.id.btnSwitchViews, ConstraintSet.WRAP_CONTENT)
        set.constrainHeight(R.id.btnSwitchViews, ConstraintSet.WRAP_CONTENT)
        set.connect(R.id.btnSwitchViews, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(R.id.btnSwitchViews, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.setMargin(R.id.btnSwitchViews, ConstraintSet.BOTTOM, 8.dpToPx())
        set.setMargin(R.id.btnSwitchViews, ConstraintSet.START, 8.dpToPx())

        set.setVisibility(R.id.btnSwitchViews, ConstraintSet.VISIBLE)
        set.applyTo(binding.root)
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    // =====================================================================
    // Signal Indicator
    // =====================================================================

    enum class SignalLevel { NONE, WEAK, MODERATE, GOOD }

    private val signalUpdateRunnable = object : Runnable {
        override fun run() {
            val rssi = bleScanner?.getBestRssiInRoom(room)
            val level = when {
                rssi == null        -> SignalLevel.NONE
                rssi >= -60         -> SignalLevel.GOOD
                rssi >= -80         -> SignalLevel.MODERATE
                else                -> SignalLevel.WEAK
            }
            updateSignalIndicator(level)
            updatePeerNames()
            handler.postDelayed(this, 2000)
        }
    }

    private val onlineSignalUpdateRunnable = object : Runnable {
        override fun run() {
            val manager = onlineChatManager ?: return
            val latency = manager.getLatencyMs()
            val level = when {
                !manager.isSubscribed() -> SignalLevel.NONE
                latency < 0             -> SignalLevel.GOOD
                latency < 300           -> SignalLevel.GOOD
                latency < 600           -> SignalLevel.MODERATE
                else                    -> SignalLevel.WEAK
            }
            updateSignalIndicator(level)
            updatePeerNames()
            handler.postDelayed(this, 3000)
        }
    }

    fun updateSignalIndicator(level: SignalLevel) {
        val lineColor = when (level) {
            SignalLevel.GOOD     -> 0xFF00C800.toInt()
            SignalLevel.MODERATE -> 0xFFFFA500.toInt()
            SignalLevel.WEAK     -> 0xFFC80000.toInt()
            SignalLevel.NONE     -> 0xFF606060.toInt()
        }
        binding.signalLineTop.setBackgroundColor(lineColor)
        binding.signalLineBottom.setBackgroundColor(lineColor)

        val iconRes = when (level) {
            SignalLevel.GOOD     -> R.drawable.ic_signal
            SignalLevel.MODERATE -> R.drawable.ic_signal_2
            SignalLevel.WEAK     -> R.drawable.ic_signal_1
            SignalLevel.NONE     -> R.drawable.ic_signal
        }
        binding.signalIcon.setImageResource(iconRes)

        if (level == SignalLevel.NONE) {
            binding.signalIcon.setColorFilter(0xFF606060.toInt())
        } else {
            binding.signalIcon.clearColorFilter()
        }
    }

    // =====================================================================
    // Peer names in top bar
    // =====================================================================

    private val peerNamePaint by lazy {
        TextPaint().apply {
            typeface = ResourcesCompat.getFont(this@ChatActivity, R.font.cozette_vector)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        }
    }

    private fun updatePeerNames() {
        val container = bv.peerNamesContainer
        val containerWidth = container.width
        if (containerWidth <= 0) {
            container.removeAllViews()
            return
        }

        val peers: List<Pair<String, Int>> = if (isOnline) {
            onlineChatManager?.getOnlineUsers()?.map { it.username to it.colorIndex } ?: emptyList()
        } else {
            val now = System.currentTimeMillis()

            val deduped = bleScanner?.getPeersInRoom(room)
                ?.filter { it.deviceIdHash != 0 && it.deviceIdHash != localDeviceIdHash }
                ?.filter { !departingDeviceIds.containsKey(it.deviceIdHash) }
                ?.groupBy { it.deviceIdHash }
                ?.map { (_, dupes) -> dupes.maxByOrNull { it.lastSeen }!! }
                ?: emptyList()

            val currentMap = deduped.associate { it.deviceIdHash to it.username }
            if (!blePeerTrackingInitialized) {
                knownBlePeerIds.putAll(currentMap)
                blePeerTrackingInitialized = true
                blePeerGraceUntil = now + 8_000
            } else if (now < blePeerGraceUntil) {
                knownBlePeerIds.clear()
                knownBlePeerIds.putAll(currentMap)
            } else {
                val newIds = currentMap.keys - knownBlePeerIds.keys
                for (id in newIds) {
                    val name = currentMap[id] ?: continue
                    val enterText = getString(R.string.now_entering, room.circleLetter, name)
                    ChatRepository.postSystemMessage(enterText)
                }

                val goneIds = knownBlePeerIds.keys - currentMap.keys
                for (id in goneIds) {
                    val name = knownBlePeerIds[id] ?: continue
                    val leaveText = getString(R.string.now_leaving, room.circleLetter, name)
                    ChatRepository.postSystemMessage(leaveText)
                }

                knownBlePeerIds.clear()
                knownBlePeerIds.putAll(currentMap)
            }

            deduped.map { it.username to it.colorIndex }
        }

        container.removeAllViews()
        if (peers.isEmpty()) return

        val density = resources.displayMetrics.density
        val hPad = (3 * density).toInt()
        val sepW = (3 * density).toInt()

        val squareSize = (8 * density).toInt()
        val squareGap = (2 * density).toInt()

        data class MeasuredPeer(val name: String, val colorIndex: Int, val width: Float)
        val measured = peers.sortedBy { it.first }.map { (name, ci) ->
            MeasuredPeer(name, ci, peerNamePaint.measureText(name) + 2 * hPad + squareSize + squareGap)
        }

        fun totalForCount(n: Int): Float {
            var w = 0f
            for (i in 0 until n) {
                if (i > 0) w += sepW
                w += measured[i].width
            }
            return w
        }

        if (totalForCount(measured.size) <= containerWidth) {
            for ((idx, m) in measured.withIndex()) {
                if (idx > 0) container.addView(makePeerSeparator(sepW))
                val color = ThemeColors.PALETTE[m.colorIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)]
                container.addView(makePeerEntry(m.name, color, hPad, squareSize, squareGap))
            }
            return
        }

        var bestFit = 0
        for (i in measured.indices) {
            val remaining = measured.size - (i + 1)
            if (remaining == 0) break
            val overflowW = peerNamePaint.measureText("+$remaining") + 2 * hPad
            val namesW = totalForCount(i + 1)
            if (overflowW + sepW + namesW <= containerWidth) {
                bestFit = i + 1
            } else {
                break
            }
        }

        val overflow = measured.size - bestFit
        container.addView(makePeerOverflowLabel("+$overflow", hPad))
        for (i in 0 until bestFit) {
            container.addView(makePeerSeparator(sepW))
            val color = ThemeColors.PALETTE[measured[i].colorIndex.coerceIn(0, ThemeColors.PALETTE.lastIndex)]
            container.addView(makePeerEntry(measured[i].name, color, hPad, squareSize, squareGap))
        }
    }

    private fun makePeerEntry(name: String, color: Int, hPad: Int, squareSize: Int, squareGap: Int): View {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)

            val square = View(this@ChatActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(squareSize, squareSize).apply {
                    marginEnd = squareGap
                }
                setBackgroundColor(color)
            }
            addView(square)

            val label = TextView(this@ChatActivity).apply {
                text = name
                typeface = ResourcesCompat.getFont(this@ChatActivity, R.font.cozette_vector)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ThemeColors.darken(color))
                includeFontPadding = false
            }
            addView(label)
        }
    }

    private fun makePeerOverflowLabel(text: String, hPad: Int): TextView {
        return TextView(this).apply {
            this.text = text
            typeface = ResourcesCompat.getFont(this@ChatActivity, R.font.cozette_vector)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF808080.toInt())
            includeFontPadding = false
            setPadding(hPad, 0, hPad, 0)
        }
    }

    private fun makePeerSeparator(width: Int): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            val dash = 2f * density
            background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF999999.toInt()
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1f * density
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash), 0f)
                }
                override fun draw(canvas: android.graphics.Canvas) {
                    val cx = bounds.exactCenterX()
                    canvas.drawLine(cx, bounds.top.toFloat(), cx, bounds.bottom.toFloat(), paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
                @Deprecated("Deprecated")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLeaveDialogShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_B -> { if (event.repeatCount == 0) dismissLeaveDialog(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        leaveDialogFocusedButton = 0
                        updateLeaveDialogFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        leaveDialogFocusedButton = 1
                        updateLeaveDialogFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if (leaveDialogFocusedButton == 0) dismissLeaveDialog()
                        else performAnimatedLeave()
                        return true
                    }
                }
            }
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                    KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> return true
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { bv.keyboard.moveFocusUp(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { bv.keyboard.moveFocusDown(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { bv.keyboard.moveFocusLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { bv.keyboard.moveFocusRight(); return true }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    bv.keyboard.activateFocusedKey()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (bv.canvas.textBuffer.isEmpty()) {
                        if (event.repeatCount == 0) showLeaveDialog()
                    } else {
                        bv.canvas.deleteLastChar()
                        soundManager.play(SoundManager.Sound.KEY_DOWN)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { scrollChatDown(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { scrollChatUp(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (bv.keyboard.keyboardMode == KeyboardMode.LATIN) {
                        bv.keyboard.cycleCaps()
                        soundManager.play(SoundManager.Sound.KEY_DOWN)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> { cycleKeyboardMode(); return true }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var stickHeldY = false
    private var stickHeldX = false
    private var hatHeldY = false
    private var hatHeldX = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE
        ) {
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            if (Math.abs(y) > 0.5f) {
                if (!stickHeldY) {
                    stickHeldY = true
                    if (!isLeaveDialogShowing) {
                        if (y < 0) bv.keyboard.moveFocusUp() else bv.keyboard.moveFocusDown()
                    }
                }
            } else {
                stickHeldY = false
            }

            val x = event.getAxisValue(MotionEvent.AXIS_X)
            if (Math.abs(x) > 0.5f) {
                if (!stickHeldX) {
                    stickHeldX = true
                    if (isLeaveDialogShowing) {
                        leaveDialogFocusedButton = if (x < 0) 0 else 1
                        updateLeaveDialogFocus()
                    } else {
                        if (x < 0) bv.keyboard.moveFocusLeft() else bv.keyboard.moveFocusRight()
                    }
                }
            } else {
                stickHeldX = false
            }

            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (Math.abs(hatY) > 0.5f) {
                if (!hatHeldY) {
                    hatHeldY = true
                    if (!isLeaveDialogShowing) {
                        if (hatY < 0) bv.keyboard.moveFocusUp() else bv.keyboard.moveFocusDown()
                    }
                }
            } else {
                hatHeldY = false
            }

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            if (Math.abs(hatX) > 0.5f) {
                if (!hatHeldX) {
                    hatHeldX = true
                    if (isLeaveDialogShowing) {
                        leaveDialogFocusedButton = if (hatX < 0) 0 else 1
                        updateLeaveDialogFocus()
                    } else {
                        if (hatX < 0) bv.keyboard.moveFocusLeft() else bv.keyboard.moveFocusRight()
                    }
                }
            } else {
                hatHeldX = false
            }

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
