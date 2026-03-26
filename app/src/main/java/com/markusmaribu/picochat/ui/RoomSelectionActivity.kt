package com.markusmaribu.picochat.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.graphics.Bitmap
import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.ble.BleScanner
import com.markusmaribu.picochat.databinding.ActivityRoomSelectionBinding
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.MediaStore
import android.graphics.drawable.StateListDrawable
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import com.markusmaribu.picochat.util.ThemeColors
import com.markusmaribu.picochat.util.clearFocusability
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class RoomSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = ScaleLayout.targetDensityDpi(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private lateinit var binding: ActivityRoomSelectionBinding
    private lateinit var chatAdapter: ChatHistoryAdapter
    private var selectedIndex = 0
    private var optionsSelectedIndex = 0
    private var displaySetupSelectedIndex = 0
    private var highlightAnimatorDisplaySetup: android.animation.ValueAnimator? = null
    private var nameButtonFocused = false
    private var nameButtonIndex = 0
    private var username: String = "Player"
    private var colorIndex: Int = ThemeColors.DEFAULT_INDEX

    private var bleScanner: BleScanner? = null
    private var viewsSwapped = false
    private var rotationLocked = false
    private var activeMenuScreen: String = "room_selection"
    private var colorDraft: Int = -1
    private var nameDraft: String? = null
    private var exportedHashes = mutableSetOf<Int>()
    private var restoredScrollPosition = RecyclerView.NO_POSITION
    private var layoutGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var soundManager: SoundManager
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var highlightAnimator: ValueAnimator? = null
    private var highlightAnimatorOptions: ValueAnimator? = null

    // --- Active bottom-screen view references ---

    private class BottomViews(
        val roomRows: List<View>,
        val roomCountViews: List<TextView>,
        val cornerHighlight: View,
        val btnOptions: View,
        val btnJoin: View,
        val roomSelectionContent: View,
        val optionsContent: View,
        val btnName: View,
        val btnColor: View,
        val btnDisplaySetup: View,
        val btnBack: View,
        val nameInputContent: View,
        val nameInputBoxes: NameInputBoxes,
        val nameKeyboard: SoftKeyboardView,
        val btnNameQuit: View,
        val btnNameConfirm: View,
        val btnKbCancel: View,
        val btnKbConfirm: View,
        val nameKbLatin: View,
        val nameKbAccented: View,
        val nameKbKatakana: View,
        val nameKbSymbols: View,
        val nameKbEmoji: View,
        val colorContent: View,
        val colorGrid: ColorGridView,
        val colorPreviewSwatch: View,
        val btnColorCancel: View,
        val btnColorConfirm: View,
        val creditsContent: View,
        val btnCredits: View,
        val btnCreditsBack: View,
        val optionButtons: List<View>,
        val cornerHighlightOptions: View,
        val displaySetupContent: View,
        val btnSwapViews: View,
        val btnLockRotation: View,
        val displaySetupButtons: List<View>,
        val cornerHighlightDisplaySetup: View,
        val btnDisplaySetupBack: View,
        val btnExportChat: View,
        val exportChatContent: View,
        val exportConfirmText: TextView,
        val exportPreviewImage: android.widget.ImageView,
        val exportHintText: View,
        val btnExportScrollDown: View,
        val btnExportScrollUp: View,
        val exportProgressBar: android.widget.ProgressBar,
        val btnExportNo: View,
        val btnExportYes: View,
        val headerViews: List<View>,
        val bottomBarViews: List<View>,
        val connectingContent: View,
        val connectingAnimation: android.widget.ImageView
    )

    private lateinit var bv: BottomViews

    private fun bottomViewsFromBinding(): BottomViews {
        val rows = listOf(
            binding.roomRowA.root,
            binding.roomRowB.root,
            binding.roomRowC.root,
            binding.roomRowD.root
        )
        val counts = initRoomRowLabels(rows)
        return BottomViews(
            rows, counts, binding.cornerHighlight, binding.btnOptions, binding.btnJoin,
            binding.roomSelectionContent, binding.optionsContent,
            binding.btnName, binding.btnColor, binding.btnDisplaySetup, binding.btnBack,
            binding.nameInputContent, binding.nameInputBoxes,
            binding.nameKeyboard, binding.btnNameQuit, binding.btnNameConfirm,
            binding.btnKbCancel, binding.btnKbConfirm,
            binding.nameKbLatin, binding.nameKbAccented, binding.nameKbKatakana,
            binding.nameKbSymbols, binding.nameKbEmoji,
            binding.colorContent, binding.colorGrid, binding.colorPreviewSwatch,
            binding.btnColorCancel, binding.btnColorConfirm,
            binding.creditsContent, binding.btnCredits, binding.btnCreditsBack,
            listOf(binding.btnName, binding.btnColor, binding.btnDisplaySetup, binding.btnExportChat),
            binding.cornerHighlightOptions,
            binding.displaySetupContent,
            binding.btnSwapViews, binding.btnLockRotation,
            listOf(binding.btnSwapViews, binding.btnLockRotation),
            binding.cornerHighlightDisplaySetup,
            binding.btnDisplaySetupBack,
            binding.btnExportChat, binding.exportChatContent,
            binding.exportConfirmText, binding.exportPreviewImage,
            binding.exportHintText, binding.btnExportScrollDown, binding.btnExportScrollUp,
            binding.exportProgressBar,
            binding.btnExportNo, binding.btnExportYes,
            listOf(binding.roomSelHeader, binding.optionsHeader, binding.nameInputHeader, binding.colorHeader, binding.creditsHeader, binding.displaySetupHeader, binding.exportChatHeader, binding.connectingHeader),
            listOf(binding.roomSelBottomBar, binding.optionsBottomBar, binding.nameInputBottomBar, binding.colorBottomBar, binding.creditsBottomBar, binding.displaySetupBottomBar, binding.exportChatBottomBar, binding.connectingBottomBar),
            binding.connectingContent,
            binding.connectingAnimation
        )
    }

    private fun bottomViewsFromPresentation(p: RoomSelectionPresentation): BottomViews {
        val rows = listOf(p.roomRowA, p.roomRowB, p.roomRowC, p.roomRowD)
        val counts = initRoomRowLabels(rows)
        return BottomViews(
            rows, counts, p.cornerHighlight, p.btnOptions, p.btnJoin,
            p.roomSelectionContent, p.optionsContent,
            p.btnName, p.btnColor, p.btnDisplaySetup, p.btnBack,
            p.nameInputContent, p.nameInputBoxes,
            p.nameKeyboard, p.btnNameQuit, p.btnNameConfirm,
            p.btnKbCancel, p.btnKbConfirm,
            p.nameKbLatin, p.nameKbAccented, p.nameKbKatakana,
            p.nameKbSymbols, p.nameKbEmoji,
            p.colorContent, p.colorGrid, p.colorPreviewSwatch,
            p.btnColorCancel, p.btnColorConfirm,
            p.creditsContent, p.btnCredits, p.btnCreditsBack,
            listOf(p.btnName, p.btnColor, p.btnDisplaySetup, p.btnExportChat),
            p.cornerHighlightOptions,
            p.displaySetupContent,
            p.btnSwapViews, p.btnLockRotation,
            listOf(p.btnSwapViews, p.btnLockRotation),
            p.cornerHighlightDisplaySetup,
            p.btnDisplaySetupBack,
            p.btnExportChat, p.exportChatContent,
            p.exportConfirmText as android.widget.TextView,
            p.exportPreviewImage,
            p.exportHintText, p.btnExportScrollDown, p.btnExportScrollUp,
            p.exportProgressBar as android.widget.ProgressBar,
            p.btnExportNo, p.btnExportYes,
            p.headerViews, p.bottomBarViews,
            p.connectingContent,
            p.connectingAnimation
        )
    }

    private fun initRoomRowLabels(rows: List<View>): List<TextView> {
        val rooms = Room.entries
        return rows.mapIndexed { i, row ->
            val room = rooms[i]
            if (i == 3) {
                row.findViewById<View>(R.id.roomLetterIcon).visibility = View.GONE
                row.findViewById<View>(R.id.roomLabel).visibility = View.GONE
                row.findViewById<View>(R.id.userIcon).visibility = View.GONE
                row.findViewById<View>(R.id.roomCount).visibility = View.GONE
                row.setBackgroundColor(Color.TRANSPARENT)
                row.setPadding(0, 0, 0, 0)
                val lp = row.layoutParams as LinearLayout.LayoutParams
                lp.topMargin = (6 * resources.displayMetrics.density).toInt()
                lp.marginStart = 0
                lp.marginEnd = 0
                row.layoutParams = lp
                val parent = row as androidx.constraintlayout.widget.ConstraintLayout
                val globeMargin = (4 * resources.displayMetrics.density).toInt()
                val globePad = (3 * resources.displayMetrics.density).toInt()
                val globe = TextView(this).apply {
                    id = View.generateViewId()
                    text = "\uF0AC"
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.cozette_vector)
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_room_row)
                    setTextColor(ContextCompat.getColor(context, R.color.ds_black))
                    setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
                    includeFontPadding = false
                    setPadding(globePad, globePad, globePad, globePad)
                }
                val glp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
                    dimensionRatio = "1:1"
                    topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin = globeMargin
                    bottomMargin = globeMargin
                }
                parent.addView(globe, glp)
                row.setTag(R.id.roomLetterIcon, globe)
            } else {
                row.findViewById<TextView>(R.id.roomLetterIcon).text = room.letter
                row.findViewById<TextView>(R.id.roomLabel).text = room.label
            }
            val countView = row.findViewById<TextView>(R.id.roomCount)
            if (i != 3) countView.text = getString(R.string.room_count_format, 0)
            countView
        }
    }

    // --- Secondary display management ---

    private var displayManager: DisplayManager? = null
    private var roomPresentation: RoomSelectionPresentation? = null
    private var chatHistoryPresentation: ChatHistoryPresentation? = null
    private var presentationChatAdapter: ChatHistoryAdapter? = null
    private var isSecondaryDisplayActive = false
    private var internalOverlay: View? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayRemoved(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayChanged(displayId: Int) {
            if (!isSecondaryDisplayActive || displayId == Display.DEFAULT_DISPLAY) return
            val display = displayManager?.getDisplay(displayId) ?: return
            reconnectSecondaryDisplay()
        }
    }

    // --- Chat listener ---

    private val chatListener: (ChatMessage) -> Unit = { msg ->
        runOnUiThread {
            chatAdapter.addMessage(msg)
            binding.scrollBarVisualizer.addMessage(msg)
            binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            binding.chatRecyclerView.post { updateScrollBarVisibleRange() }
            presentationChatAdapter?.let { adapter ->
                adapter.addMessage(msg)
                chatHistoryPresentation?.chatRecyclerView?.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    companion object {
        private const val BLE_PERMISSION_REQUEST = 1001
        private val BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomSelectionBinding.inflate(layoutInflater)
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

        soundManager = SoundManager(this)
        val prefs = getSharedPreferences("picochat_prefs", MODE_PRIVATE)
        colorIndex = prefs.getInt("theme_color_index", ThemeColors.DEFAULT_INDEX)
        if (!prefs.contains("theme_color_index")) {
            prefs.edit().putInt("theme_color_index", colorIndex).apply()
        }
        username = prefs.getString("username", username) ?: username
        viewsSwapped = prefs.getBoolean("views_swapped", false)
        rotationLocked = prefs.getBoolean("rotation_locked", false)
        exportedHashes = prefs.getStringSet("exported_hashes", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
        if (rotationLocked) {
            requestedOrientation = prefs.getInt(
                "locked_orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            )
        }

        setupChatHistory()
        applyStripedBackground(binding.chatHistoryBackground)
        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyStripedBackgrounds()
        applyThemeColor(colorIndex)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = OnBackInvokedCallback { handleBackNavigation() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback!!
            )
        }

        setupDisplayManager()
        fitScreensToParent()

        binding.btnSwitchViews.setOnClickListener {
            viewsSwapped = !viewsSwapped
            getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                .putBoolean("views_swapped", viewsSwapped).apply()
            fitScreensToParent()
        }

        savedInstanceState?.let {
            selectedIndex = it.getInt("selected_index", 0)
            optionsSelectedIndex = it.getInt("options_selected_index", 0)
            displaySetupSelectedIndex = it.getInt("display_setup_selected_index", 0)
            restoredScrollPosition = it.getInt("chat_scroll_position", RecyclerView.NO_POSITION)
        }
        updateSelection()
        requestBlePermissions()

        savedInstanceState?.getString("menu_screen")?.let { screen ->
            activeMenuScreen = screen
            nameDraft = savedInstanceState.getString("name_draft")
            colorDraft = savedInstanceState.getInt("color_draft", -1)
            if (screen != "room_selection") {
                bv.roomSelectionContent.visibility = View.GONE
                when (screen) {
                    "options"    -> {
                        bv.optionsContent.visibility = View.VISIBLE
                        updateOptionsSelection()
                    }
                    "name_input" -> {
                        bv.nameInputContent.visibility = View.VISIBLE
                        bv.nameInputBoxes.text = nameDraft ?: username
                    }
                    "color"      -> {
                        val draft = if (colorDraft >= 0) colorDraft else colorIndex
                        bv.colorContent.visibility = View.VISIBLE
                        bv.colorGrid.selectedIndex = draft
                        bv.colorPreviewSwatch.setBackgroundColor(ThemeColors.PALETTE[draft])
                        applyThemeColor(draft)
                    }
                    "credits"    -> {
                        bv.creditsContent.visibility = View.VISIBLE
                    }
                    "display_setup" -> {
                        bv.displaySetupContent.visibility = View.VISIBLE
                        updateDisplaySetupSelection()
                    }
                    "export_chat" -> {
                        bv.exportChatContent.visibility = View.VISIBLE
                        bv.exportConfirmText.text = getString(R.string.export_chat_confirm)
                        bv.exportConfirmText.visibility = View.VISIBLE
                        bv.exportHintText.visibility = View.VISIBLE
                        bv.exportPreviewImage.visibility = View.VISIBLE
                        bv.btnExportScrollDown.visibility = View.VISIBLE
                        bv.btnExportScrollUp.visibility = View.VISIBLE
                        bv.exportProgressBar.visibility = View.GONE
                        bv.btnExportNo.visibility = View.VISIBLE
                        bv.btnExportYes.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("menu_screen", activeMenuScreen)
        outState.putString("name_draft", nameDraft ?: username)
        outState.putInt("color_draft", if (colorDraft >= 0) colorDraft else colorIndex)
        outState.putInt("selected_index", selectedIndex)
        outState.putInt("options_selected_index", optionsSelectedIndex)
        outState.putInt("display_setup_selected_index", displaySetupSelectedIndex)
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager
        outState.putInt("chat_scroll_position", lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION)
    }

    override fun onResume() {
        super.onResume()
        isTransitioning = false
        binding.root.clearFocusability()
        ChatRepository.addListener(chatListener)
        chatAdapter.setMessages(ChatRepository.getAllMessages())
        binding.scrollBarVisualizer.setMessages(ChatRepository.getAllMessages())
        if (chatAdapter.itemCount > 0) {
            val pos = if (restoredScrollPosition != RecyclerView.NO_POSITION)
                restoredScrollPosition else chatAdapter.itemCount - 1
            binding.chatRecyclerView.scrollToPosition(pos)
            restoredScrollPosition = RecyclerView.NO_POSITION
        }
        binding.chatRecyclerView.post { updateScrollBarVisibleRange() }
        if (activeMenuScreen == "export_chat") {
            updateExportPreview()
        }
        presentationChatAdapter?.let { adapter ->
            adapter.setMessages(ChatRepository.getAllMessages())
            if (adapter.itemCount > 0) {
                chatHistoryPresentation?.chatRecyclerView?.scrollToPosition(adapter.itemCount - 1)
            }
        }
        startBleScanning()

        val overlay = activeOverlay()
        if (overlay != null && overlay.alpha > 0f) {
            overlay.animate()
                .setListener(null)
                .alpha(0f)
                .setDuration(200)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }
    }

    override fun onPause() {
        super.onPause()
        ChatRepository.removeListener(chatListener)
        bleScanner?.stopScan()
    }

    override fun onStart() {
        super.onStart()
        if (!isSecondaryDisplayActive) {
            checkSecondaryDisplay()
            activeOverlay()?.let {
                it.alpha = 1f
                it.visibility = View.VISIBLE
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isSecondaryDisplayActive) {
            roomPresentation?.setOnDismissListener(null)
            chatHistoryPresentation?.setOnDismissListener(null)
            onSecondaryDisplayDisconnected()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        handler.removeCallbacksAndMessages(null)
        displayManager?.unregisterDisplayListener(displayListener)
        roomPresentation?.setOnDismissListener(null)
        roomPresentation?.dismiss()
        roomPresentation = null
        chatHistoryPresentation?.setOnDismissListener(null)
        chatHistoryPresentation?.dismiss()
        chatHistoryPresentation = null
        presentationChatAdapter = null
        exportJob?.cancel()
        soundManager.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun handleBackNavigation() {
        if (isTransitioning) return
        when {
            bv.connectingContent.visibility == View.VISIBLE -> {
                stopConnectingAnimation()
                onlineConnecting = false
                bv.connectingContent.visibility = View.GONE
                bv.roomSelectionContent.visibility = View.VISIBLE
            }
            bv.creditsContent.visibility == View.VISIBLE -> {
                soundManager.play(SoundManager.Sound.SELECT)
                showOptionsFromCredits()
            }
            bv.displaySetupContent.visibility == View.VISIBLE -> {
                soundManager.play(SoundManager.Sound.SELECT)
                showOptionsFromDisplaySetup()
            }
            bv.exportChatContent.visibility == View.VISIBLE -> {
                exportJob?.cancel()
                soundManager.play(SoundManager.Sound.SELECT)
                showOptionsFromExportChat()
            }
            bv.colorContent.visibility == View.VISIBLE -> {
                soundManager.play(SoundManager.Sound.SELECT)
                applyThemeColor(colorIndex)
                showOptionsFromColor()
            }
            bv.nameInputContent.visibility == View.VISIBLE -> {
                soundManager.play(SoundManager.Sound.SELECT)
                showOptionsFromName()
            }
            bv.optionsContent.visibility == View.VISIBLE -> {
                soundManager.play(SoundManager.Sound.SELECT)
                showRoomSelection()
            }
            else -> moveTaskToBack(true)
        }
    }

    // =====================================================================
    // Bottom-screen wiring
    // =====================================================================

    private fun wireBottomScreen() {
        val v = bv

        v.roomRows.forEachIndexed { index, row ->
            row.setOnClickListener {
                selectedIndex = index
                updateSelection()
                if (index == 3) {
                    startOnlineConnection()
                } else {
                    joinRoom()
                }
            }
        }

        v.btnOptions.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showOptions()
        }
        v.btnJoin.setOnClickListener {
            if (selectedIndex == 3) {
                startOnlineConnection()
            } else {
                joinRoom()
            }
        }

        v.btnName.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showNameInput()
        }
        v.btnColor.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showColorPicker()
        }
        v.btnDisplaySetup.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showDisplaySetup()
        }
        v.btnSwapViews.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            soundManager.play(SoundManager.Sound.SELECT)

            if (isSecondaryDisplayActive) {
                animateSwap()
            } else {
                animateSwapSingleScreen()
            }
        }
        (v.btnLockRotation as TextView).text = getString(
            if (rotationLocked) R.string.btn_unlock_rotation else R.string.btn_lock_rotation
        )
        v.btnLockRotation.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            rotationLocked = !rotationLocked
            val prefs = getSharedPreferences("picochat_prefs", MODE_PRIVATE)
            if (rotationLocked) {
                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation
                val orientation = when (rotation) {
                    Surface.ROTATION_90  -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else                 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                requestedOrientation = orientation
                prefs.edit()
                    .putBoolean("rotation_locked", true)
                    .putInt("locked_orientation", orientation)
                    .apply()
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                prefs.edit()
                    .putBoolean("rotation_locked", false)
                    .remove("locked_orientation")
                    .apply()
            }
            (v.btnLockRotation as TextView).text = getString(
                if (rotationLocked) R.string.btn_unlock_rotation else R.string.btn_lock_rotation
            )
        }
        v.btnDisplaySetupBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showOptionsFromDisplaySetup()
        }
        v.btnBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showRoomSelection()
        }
        v.btnCredits.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showCredits()
        }
        v.btnCreditsBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showOptionsFromCredits()
        }
        v.btnExportChat.setOnClickListener {
            val drawings = ChatRepository.getAllMessages().filterIsInstance<ChatMessage.DrawingMessage>()
            if (drawings.isEmpty()) {
                soundManager.play(SoundManager.Sound.INVALID)
                return@setOnClickListener
            }
            soundManager.play(SoundManager.Sound.SELECT)
            showExportChat()
        }
        v.btnExportNo.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            showOptionsFromExportChat()
        }
        v.btnExportYes.setOnClickListener {
            soundManager.play(SoundManager.Sound.CONFIRM)
            startExport()
        }
        v.btnExportScrollDown.setOnClickListener {
            scrollChatDown()
            updateExportPreview()
        }
        v.btnExportScrollUp.setOnClickListener {
            scrollChatUp()
            updateExportPreview()
        }

        v.nameKeyboard.hideEnterKey = true
        v.nameKeyboard.onKeyPressed = { ch ->
            if (bv.nameInputBoxes.appendChar(ch)) {
                soundManager.play(SoundManager.Sound.KEY_DOWN)
            } else {
                soundManager.play(SoundManager.Sound.INVALID)
            }
            nameDraft = bv.nameInputBoxes.text
        }
        v.nameKeyboard.onBackspace = {
            if (bv.nameInputBoxes.deleteChar()) {
                soundManager.play(SoundManager.Sound.KEY_DOWN)
            } else {
                soundManager.play(SoundManager.Sound.INVALID)
            }
            nameDraft = bv.nameInputBoxes.text
        }
        v.nameKeyboard.onTouchDown = { soundManager.play(SoundManager.Sound.KEY_DOWN) }
        v.nameKeyboard.onTouchUp = { soundManager.play(SoundManager.Sound.KEY_UP) }

        v.btnNameQuit.setOnClickListener {
            if (bv.nameInputBoxes.deleteChar()) {
                soundManager.play(SoundManager.Sound.KEY_DOWN)
            } else {
                soundManager.play(SoundManager.Sound.INVALID)
            }
            nameDraft = bv.nameInputBoxes.text
        }
        v.btnNameConfirm.setOnClickListener {
            if (nameButtonFocused) {
                val btn = if (nameButtonIndex == 0) bv.btnKbCancel else bv.btnKbConfirm
                btn.performClick()
            } else {
                bv.nameKeyboard.activateFocusedKey()
            }
        }

        v.btnKbCancel.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            nameDraft = null
            showOptionsFromName()
        }
        v.btnKbConfirm.setOnClickListener {
            val name = bv.nameInputBoxes.text.trim()
            if (name.isEmpty()) {
                soundManager.play(SoundManager.Sound.INVALID)
                return@setOnClickListener
            }
            username = name
            getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                .putString("username", username).apply()
            nameDraft = null
            soundManager.play(SoundManager.Sound.CONFIRM)
            showOptionsFromName()
        }

        v.nameKbLatin.setOnClickListener {
            setNameKeyboardMode(KeyboardMode.LATIN)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.nameKbAccented.setOnClickListener {
            setNameKeyboardMode(KeyboardMode.ACCENTED)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.nameKbKatakana.setOnClickListener {
            setNameKeyboardMode(KeyboardMode.KATAKANA)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.nameKbSymbols.setOnClickListener {
            setNameKeyboardMode(KeyboardMode.SYMBOLS)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.nameKbEmoji.setOnClickListener {
            setNameKeyboardMode(KeyboardMode.EMOTICONS)
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
        }
        v.nameKbLatin.isSelected = true

        val nameKbButtons = listOf(v.nameKbLatin, v.nameKbAccented, v.nameKbKatakana, v.nameKbSymbols, v.nameKbEmoji)
        v.nameKeyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val size = v.nameKeyboard.keyTextSize
            if (size > 0f) {
                for (btn in nameKbButtons) {
                    (btn as? TextView)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
                }
            }
        }

        v.colorGrid.selectedIndex = colorIndex
        v.colorPreviewSwatch.setBackgroundColor(ThemeColors.PALETTE[colorIndex])
        v.colorGrid.onColorSelected = { idx ->
            soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
            colorDraft = idx
            v.colorPreviewSwatch.setBackgroundColor(ThemeColors.PALETTE[idx])
            applyThemeColor(idx)
        }
        v.btnColorCancel.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            colorDraft = -1
            applyThemeColor(colorIndex)
            showOptionsFromColor()
        }
        v.btnColorConfirm.setOnClickListener {
            colorIndex = v.colorGrid.selectedIndex
            colorDraft = -1
            getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                .putInt("theme_color_index", colorIndex).apply()
            soundManager.play(SoundManager.Sound.CONFIRM)
            showOptionsFromColor()
        }

        updateExportButtonStyle()
    }

    private fun createHighlightDrawable(color: Int): CornerBracketDrawable {
        val density = resources.displayMetrics.density
        return CornerBracketDrawable(
            bracketColor = color,
            strokeWidth = 4f * density,
            outlineColor = Color.WHITE,
            outlineWidth = 1.5f * density,
            expandH = 7f * density,
            expandV = 2f * density
        )
    }

    private fun positionHighlight() {
        val highlight = bv.cornerHighlight
        val row = bv.roomRows[selectedIndex]
        val density = resources.displayMetrics.density
        val vertPad = (5f * density).toInt()

        if (row.height == 0) return

        val isOnlineRow = selectedIndex == 3
        val globeView = if (isOnlineRow) row.getTag(R.id.roomLetterIcon) as? View else null
        if (isOnlineRow && (globeView == null || globeView.width == 0)) return

        val constraintParent = highlight.parent as androidx.constraintlayout.widget.ConstraintLayout
        val roomListContainer = constraintParent.findViewById<View>(R.id.roomListContainer)
        val targetWidth = if (isOnlineRow && globeView != null) globeView.width else roomListContainer.width
        val targetHeight = (if (isOnlineRow && globeView != null) globeView.height else row.height) + vertPad * 2
        val targetY = if (isOnlineRow && globeView != null) {
            (row.top + globeView.top).toFloat() - vertPad
        } else {
            row.top.toFloat() - vertPad
        }

        val animate = highlight.tag != null && highlight.width > 0
        val initWidth = if (animate) highlight.width else targetWidth

        val cs = ConstraintSet()
        cs.clone(constraintParent)
        cs.clear(highlight.id, ConstraintSet.START)
        cs.clear(highlight.id, ConstraintSet.END)
        cs.connect(highlight.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(highlight.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        cs.constrainWidth(highlight.id, initWidth)
        cs.applyTo(constraintParent)

        if (animate) {
            val startWidth = highlight.width
            val startHeight = highlight.height
            val startY = highlight.translationY

            highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 150
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    highlight.translationY = startY + (targetY - startY) * t
                    val lp = highlight.layoutParams
                    lp.height = (startHeight + (targetHeight - startHeight) * t).toInt()
                    lp.width = (startWidth + (targetWidth - startWidth) * t).toInt()
                    highlight.layoutParams = lp
                }
                start()
            }
        } else {
            highlight.translationY = targetY
            val lp = highlight.layoutParams
            lp.height = targetHeight
            highlight.layoutParams = lp
        }
        highlight.tag = true
    }

    private fun updateSelection() {
        val highlight = bv.cornerHighlight

        highlight.foreground = createHighlightDrawable(ThemeColors.PALETTE[colorIndex])

        highlightAnimator?.cancel()

        if (highlight.tag != null) {
            positionHighlight()
        } else {
            val row = bv.roomRows[selectedIndex]
            row.post { row.post { positionHighlight() } }
        }
    }

    private fun positionOptionsHighlight() {
        val highlight = bv.cornerHighlightOptions
        val btn = bv.optionButtons[optionsSelectedIndex]
        val density = resources.displayMetrics.density
        val vertPad = (5f * density).toInt()

        if (btn.height == 0) return

        val lp = highlight.layoutParams
        lp.height = btn.height + vertPad * 2
        highlight.layoutParams = lp

        val targetY = btn.top.toFloat() - vertPad

        if (highlight.tag != null) {
            highlightAnimatorOptions = ValueAnimator.ofFloat(
                highlight.translationY, targetY
            ).apply {
                duration = 150
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { highlight.translationY = it.animatedValue as Float }
                start()
            }
        } else {
            highlight.translationY = targetY
        }
        highlight.tag = true
    }

    private fun updateOptionsSelection() {
        val highlight = bv.cornerHighlightOptions

        highlight.foreground = createHighlightDrawable(ThemeColors.PALETTE[colorIndex])

        highlightAnimatorOptions?.cancel()

        if (highlight.tag != null) {
            positionOptionsHighlight()
        } else {
            val btn = bv.optionButtons[optionsSelectedIndex]
            btn.post { btn.post { positionOptionsHighlight() } }
        }

        updateExportButtonStyle()
    }

    fun updateRoomCounts(counts: Map<Room, Int>) {
        Room.entries.forEachIndexed { i, room ->
            val count = counts[room] ?: 0
            bv.roomCountViews[i].text = if (count >= Constants.MAX_ROOM_USERS)
                getString(R.string.room_full)
            else
                getString(R.string.room_count_format, count)
        }
    }

    private fun setNameKeyboardMode(mode: KeyboardMode) {
        bv.nameKeyboard.keyboardMode = mode
        bv.nameKbLatin.isSelected = (mode == KeyboardMode.LATIN)
        bv.nameKbAccented.isSelected = (mode == KeyboardMode.ACCENTED)
        bv.nameKbKatakana.isSelected = (mode == KeyboardMode.KATAKANA)
        bv.nameKbSymbols.isSelected = (mode == KeyboardMode.SYMBOLS)
        bv.nameKbEmoji.isSelected = (mode == KeyboardMode.EMOTICONS)
    }

    private fun cycleNameKeyboardMode() {
        val modes = KeyboardMode.entries
        val next = modes[(bv.nameKeyboard.keyboardMode.ordinal + 1) % modes.size]
        setNameKeyboardMode(next)
        soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
    }

    private fun scrollChatUp() {
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager ?: return
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
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible < (chatAdapter.itemCount) - 1) {
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

    private fun createDottedRedBorderDrawable(radii: FloatArray): Drawable {
        val density = resources.displayMetrics.density
        val sw = 2f * density
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = sw
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            isAntiAlias = false
        }
        val path = android.graphics.Path()
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                val r = bounds
                val inset = sw / 2f
                val rect = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
                path.reset()
                path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
                canvas.drawPath(path, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun updateKbButtonHighlight() {
        val density = resources.displayMetrics.density
        val cr = 6f * density
        if (nameButtonFocused) {
            val leftRadii = floatArrayOf(cr, cr, 0f, 0f, 0f, 0f, cr, cr)
            val rightRadii = floatArrayOf(0f, 0f, cr, cr, cr, cr, 0f, 0f)
            val focusedRadii = if (nameButtonIndex == 0) leftRadii else rightRadii
            val focused = if (nameButtonIndex == 0) bv.btnKbCancel else bv.btnKbConfirm
            val other = if (nameButtonIndex == 0) bv.btnKbConfirm else bv.btnKbCancel
            focused.foreground = createDottedRedBorderDrawable(focusedRadii)
            other.foreground = null
        } else {
            bv.btnKbCancel.foreground = null
            bv.btnKbConfirm.foreground = null
        }
    }

    private inner class ClampedLayoutManager : LinearLayoutManager(this@RoomSelectionActivity) {
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
        chatAdapter = ChatHistoryAdapter()
        binding.chatRecyclerView.layoutManager = ClampedLayoutManager()
        binding.chatRecyclerView.adapter = chatAdapter
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
                if (activeMenuScreen == "export_chat") {
                    updateExportPreview()
                }
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

    private fun onSecondaryDisplayConnected(display: Display) {
        if (viewsSwapped) {
            val pres = ChatHistoryPresentation(this, display)
            pres.onBackPressedCallback = { handleBackNavigation() }
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) {
                    onSecondaryDisplayDisconnected()
                    finish()
                }
            }
            pres.show()
            chatHistoryPresentation = pres

            val adapter = ChatHistoryAdapter()
            adapter.setMessages(ChatRepository.getAllMessages())
            pres.chatRecyclerView.layoutManager = ClampedLayoutManager()
            pres.chatRecyclerView.adapter = adapter
            pres.chatRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    if (parent.getChildAdapterPosition(view) == 0) {
                        outRect.top = parent.height
                    }
                }
            })
            presentationChatAdapter = adapter
            applyStripedBackground(pres.chatHistoryBackground)

            bv = bottomViewsFromBinding()
            wireBottomScreen()
            applyStripedBackgrounds()
            applyThemeColor(colorIndex)
            updateSelection()
        } else {
            val pres = RoomSelectionPresentation(this, display)
            pres.onBackPressedCallback = { handleBackNavigation() }
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) {
                    onSecondaryDisplayDisconnected()
                    finish()
                }
            }
            pres.show()
            roomPresentation = pres

            bv = bottomViewsFromPresentation(pres)
            wireBottomScreen()
            applyStripedBackgrounds()
            applyThemeColor(colorIndex)
            updateSelection()
        }

        isSecondaryDisplayActive = true
        fitScreensToParent()
        refreshRoomCounts()
    }

    private fun reconnectSecondaryDisplay() {
        val dm = displayManager ?: return
        val secondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        val oldRoomPres = roomPresentation
        val oldChatPres = chatHistoryPresentation
        roomPresentation = null
        chatHistoryPresentation = null
        presentationChatAdapter = null

        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyStripedBackgrounds()
        applyThemeColor(colorIndex)
        updateSelection()

        isSecondaryDisplayActive = false

        if (secondary != null) {
            onSecondaryDisplayConnected(secondary)
        } else {
            fitScreensToParent()
        }

        oldRoomPres?.setOnDismissListener(null)
        oldChatPres?.setOnDismissListener(null)
        handler.postDelayed({
            try { if (oldRoomPres?.isShowing == true) oldRoomPres.dismiss() } catch (_: Exception) {}
            try { if (oldChatPres?.isShowing == true) oldChatPres.dismiss() } catch (_: Exception) {}
        }, 150)

        refreshRoomCounts()
    }

    private fun dismissAllPresentations() {
        roomPresentation?.setOnDismissListener(null)
        roomPresentation?.dismiss()
        roomPresentation = null
        chatHistoryPresentation?.setOnDismissListener(null)
        chatHistoryPresentation?.dismiss()
        chatHistoryPresentation = null
        presentationChatAdapter = null
    }

    private fun onSecondaryDisplayDisconnected() {
        dismissAllPresentations()

        bv = bottomViewsFromBinding()
        wireBottomScreen()
        applyStripedBackgrounds()
        applyThemeColor(colorIndex)
        updateSelection()

        isSecondaryDisplayActive = false
        fitScreensToParent()

        refreshRoomCounts()
    }

    private fun getOrCreateInternalOverlay(): View {
        internalOverlay?.let { return it }
        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(this@RoomSelectionActivity, R.color.ds_gray_stripe))
            elevation = 10 * resources.displayMetrics.density
            outlineProvider = null
            visibility = View.GONE
            alpha = 0f
        }
        binding.bottomScreen.addView(overlay)
        internalOverlay = overlay
        return overlay
    }

    private fun activeOverlay(): View? = when {
        isSecondaryDisplayActive && !viewsSwapped -> roomPresentation?.overlay
        isSecondaryDisplayActive && viewsSwapped  -> getOrCreateInternalOverlay()
        else -> binding.bottomOverlay
    }

    private fun visibleMenuContent(): View? = when {
        bv.optionsContent.visibility == View.VISIBLE        -> bv.optionsContent
        bv.nameInputContent.visibility == View.VISIBLE      -> bv.nameInputContent
        bv.colorContent.visibility == View.VISIBLE          -> bv.colorContent
        bv.creditsContent.visibility == View.VISIBLE        -> bv.creditsContent
        bv.displaySetupContent.visibility == View.VISIBLE   -> bv.displaySetupContent
        bv.exportChatContent.visibility == View.VISIBLE    -> bv.exportChatContent
        else -> bv.roomSelectionContent
    }

    private data class MenuAnimViews(
        val header: View, val headerDivider: View,
        val middle: View,
        val bottomBarDivider: View, val bottomBar: View
    )

    private fun menuAnimViewsFrom(content: View): MenuAnimViews {
        val vg = content as android.view.ViewGroup
        return MenuAnimViews(
            header           = vg.getChildAt(0),
            headerDivider    = vg.getChildAt(1),
            middle           = vg.getChildAt(2),
            bottomBarDivider = vg.getChildAt(3),
            bottomBar        = vg.getChildAt(4)
        )
    }

    private fun resetTranslations(vararg views: View?) {
        views.forEach { v -> v ?: return@forEach; v.translationY = 0f; v.translationX = 0f }
    }

    private fun animateSwapSingleScreen() {
        if (isTransitioning) return
        isTransitioning = true

        val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val topSL  = binding.topScreen as ScaleLayout
        val botSL  = binding.bottomScreen as ScaleLayout
        val refH   = topSL.refH.toFloat()

        val wasOptions      = bv.optionsContent.visibility == View.VISIBLE
        val wasNameInput    = bv.nameInputContent.visibility == View.VISIBLE
        val wasColor        = bv.colorContent.visibility == View.VISIBLE
        val wasCredits      = bv.creditsContent.visibility == View.VISIBLE
        val wasDisplaySetup = bv.displaySetupContent.visibility == View.VISIBLE
        val wasExportChat   = bv.exportChatContent.visibility == View.VISIBLE

        val chatRV: View = binding.chatRecyclerView
        val sidebar: View = binding.topSidebar
        val sidebarW = sidebar.width.toFloat()

        val menuContent = visibleMenuContent() ?: bv.roomSelectionContent
        val menu = menuAnimViewsFrom(menuContent)

        val chatIsOnTop = !viewsSwapped
        val chatExitY = if (chatIsOnTop) refH else -refH
        val menuExitY = if (chatIsOnTop) -refH else refH

        topSL.swapBackgroundColor = greyColor
        topSL.swapOverlayAlpha = 0f
        topSL.clipDuringSwap = true
        botSL.swapBackgroundColor = greyColor
        botSL.swapOverlayAlpha = 0f
        botSL.clipDuringSwap = true

        val exitAnims = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(topSL, "swapOverlayAlpha", 0f, 1f),
            ObjectAnimator.ofFloat(botSL, "swapOverlayAlpha", 0f, 1f),
            ObjectAnimator.ofFloat(chatRV, View.TRANSLATION_Y, 0f, chatExitY),
            ObjectAnimator.ofFloat(sidebar, View.TRANSLATION_X, 0f, -sidebarW),
            ObjectAnimator.ofFloat(menu.header, View.TRANSLATION_Y, 0f, -refH),
            ObjectAnimator.ofFloat(menu.headerDivider, View.TRANSLATION_Y, 0f, -refH),
            ObjectAnimator.ofFloat(menu.bottomBar, View.TRANSLATION_Y, 0f, refH),
            ObjectAnimator.ofFloat(menu.bottomBarDivider, View.TRANSLATION_Y, 0f, refH),
            ObjectAnimator.ofFloat(menu.middle, View.TRANSLATION_Y, 0f, menuExitY)
        )

        val exitSet = AnimatorSet().apply {
            playTogether(exitAnims)
            duration = 300
            interpolator = AccelerateInterpolator()
        }

        exitSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                resetTranslations(
                    chatRV, sidebar,
                    menu.header, menu.headerDivider, menu.middle,
                    menu.bottomBarDivider, menu.bottomBar
                )
                topSL.swapOverlayAlpha = 0f
                topSL.swapBackgroundColor = 0
                topSL.clipDuringSwap = false
                botSL.swapOverlayAlpha = 0f
                botSL.swapBackgroundColor = 0
                botSL.clipDuringSwap = false

                viewsSwapped = !viewsSwapped
                getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                    .putBoolean("views_swapped", viewsSwapped).apply()
                fitScreensToParent()

                if (wasOptions || wasNameInput || wasColor || wasCredits || wasDisplaySetup || wasExportChat) {
                    bv.roomSelectionContent.visibility = View.GONE
                }
                if (wasOptions)      bv.optionsContent.visibility = View.VISIBLE
                if (wasNameInput)    bv.nameInputContent.visibility = View.VISIBLE
                if (wasColor)        bv.colorContent.visibility = View.VISIBLE
                if (wasCredits)      bv.creditsContent.visibility = View.VISIBLE
                if (wasDisplaySetup) bv.displaySetupContent.visibility = View.VISIBLE
                if (wasExportChat)   bv.exportChatContent.visibility = View.VISIBLE

                val newMenuContent = visibleMenuContent() ?: bv.roomSelectionContent
                val newMenu = menuAnimViewsFrom(newMenuContent)
                val newSidebarW = sidebar.width.toFloat().let { if (it > 0f) it else sidebarW }

                topSL.swapBackgroundColor = greyColor
                topSL.swapOverlayAlpha = 1f
                topSL.clipDuringSwap = true
                botSL.swapBackgroundColor = greyColor
                botSL.swapOverlayAlpha = 1f
                botSL.clipDuringSwap = true

                val chatEntryY = -chatExitY
                val menuMiddleEntryY = -menuExitY

                chatRV.translationY = chatEntryY
                sidebar.translationX = -newSidebarW
                newMenu.header.translationY = -refH
                newMenu.headerDivider.translationY = -refH
                newMenu.bottomBar.translationY = refH
                newMenu.bottomBarDivider.translationY = refH
                newMenu.middle.translationY = menuMiddleEntryY

                handler.postDelayed({
                    val entryAnims = mutableListOf<Animator>(
                        ObjectAnimator.ofFloat(topSL, "swapOverlayAlpha", 1f, 0f),
                        ObjectAnimator.ofFloat(botSL, "swapOverlayAlpha", 1f, 0f),
                        ObjectAnimator.ofFloat(chatRV, View.TRANSLATION_Y, chatEntryY, 0f),
                        ObjectAnimator.ofFloat(sidebar, View.TRANSLATION_X, -newSidebarW, 0f),
                        ObjectAnimator.ofFloat(newMenu.header, View.TRANSLATION_Y, -refH, 0f),
                        ObjectAnimator.ofFloat(newMenu.headerDivider, View.TRANSLATION_Y, -refH, 0f),
                        ObjectAnimator.ofFloat(newMenu.bottomBar, View.TRANSLATION_Y, refH, 0f),
                        ObjectAnimator.ofFloat(newMenu.bottomBarDivider, View.TRANSLATION_Y, refH, 0f),
                        ObjectAnimator.ofFloat(newMenu.middle, View.TRANSLATION_Y, menuMiddleEntryY, 0f)
                    )

                    AnimatorSet().apply {
                        playTogether(entryAnims)
                        duration = 300
                        interpolator = DecelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                resetTranslations(
                                    chatRV, sidebar,
                                    newMenu.header, newMenu.headerDivider, newMenu.middle,
                                    newMenu.bottomBarDivider, newMenu.bottomBar
                                )
                                topSL.swapOverlayAlpha = 0f
                                topSL.swapBackgroundColor = 0
                                topSL.clipDuringSwap = false
                                botSL.swapOverlayAlpha = 0f
                                botSL.swapBackgroundColor = 0
                                botSL.clipDuringSwap = false
                                isTransitioning = false
                            }
                        })
                        start()
                    }
                }, 100)
            }
        })

        exitSet.start()
    }

    private fun animateSwap() {
        if (isTransitioning) return
        isTransitioning = true

        val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val refH = (binding.topScreen as ScaleLayout).refH.toFloat()

        val wasOptions      = bv.optionsContent.visibility == View.VISIBLE
        val wasNameInput    = bv.nameInputContent.visibility == View.VISIBLE
        val wasColor        = bv.colorContent.visibility == View.VISIBLE
        val wasCredits      = bv.creditsContent.visibility == View.VISIBLE
        val wasDisplaySetup = bv.displaySetupContent.visibility == View.VISIBLE
        val wasExportChat   = bv.exportChatContent.visibility == View.VISIBLE

        val mainIsMenu = viewsSwapped
        val mainSL  = (if (viewsSwapped) binding.bottomScreen else binding.topScreen) as ScaleLayout
        val presSL  = (roomPresentation?.scaleLayout ?: chatHistoryPresentation?.scaleLayout)

        val mainChatRV: View? = if (!mainIsMenu) binding.chatRecyclerView else null
        val mainChatSidebar: View? = if (!mainIsMenu) binding.topSidebar else null
        val presChatRV: View? = if (mainIsMenu) chatHistoryPresentation?.chatRecyclerView else null
        val presChatSidebar: View? = if (mainIsMenu)
            chatHistoryPresentation?.chatHistoryBackground?.findViewById(R.id.topSidebar) else null

        val mainMenuContent = if (mainIsMenu) visibleMenuContent() else null
        val mainMenu = mainMenuContent?.let { menuAnimViewsFrom(it) }

        val presMenuContent: View? = if (!mainIsMenu) {
            val p = roomPresentation
            if (p != null) {
                when {
                    p.optionsContent.visibility == View.VISIBLE        -> p.optionsContent
                    p.nameInputContent.visibility == View.VISIBLE      -> p.nameInputContent
                    p.colorContent.visibility == View.VISIBLE          -> p.colorContent
                    p.creditsContent.visibility == View.VISIBLE        -> p.creditsContent
                    p.displaySetupContent.visibility == View.VISIBLE   -> p.displaySetupContent
                    p.exportChatContent.visibility == View.VISIBLE     -> p.exportChatContent
                    else -> p.roomSelectionContent
                }
            } else null
        } else null
        val presMenu = presMenuContent?.let { menuAnimViewsFrom(it) }

        mainSL.swapBackgroundColor = greyColor
        mainSL.swapOverlayAlpha = 0f
        mainSL.clipDuringSwap = true
        presSL?.swapBackgroundColor = greyColor
        presSL?.swapOverlayAlpha = 0f
        presSL?.clipDuringSwap = true

        val exitAnims = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(mainSL, "swapOverlayAlpha", 0f, 1f)
        )
        presSL?.let {
            exitAnims.add(ObjectAnimator.ofFloat(it, "swapOverlayAlpha", 0f, 1f))
        }

        val sidebarW = (mainChatSidebar ?: presChatSidebar)?.width?.toFloat() ?: 0f

        mainChatRV?.let {
            exitAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 0f, refH))
        }
        mainChatSidebar?.let {
            exitAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_X, 0f, -sidebarW))
        }
        presChatRV?.let {
            exitAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 0f, -refH))
        }
        presChatSidebar?.let {
            exitAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_X, 0f, -sidebarW))
        }

        mainMenu?.let { m ->
            exitAnims.add(ObjectAnimator.ofFloat(m.header, View.TRANSLATION_Y, 0f, -refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.headerDivider, View.TRANSLATION_Y, 0f, -refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.bottomBar, View.TRANSLATION_Y, 0f, refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.bottomBarDivider, View.TRANSLATION_Y, 0f, refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.middle, View.TRANSLATION_Y, 0f, refH))
        }
        presMenu?.let { m ->
            exitAnims.add(ObjectAnimator.ofFloat(m.header, View.TRANSLATION_Y, 0f, -refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.headerDivider, View.TRANSLATION_Y, 0f, -refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.bottomBar, View.TRANSLATION_Y, 0f, refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.bottomBarDivider, View.TRANSLATION_Y, 0f, refH))
            exitAnims.add(ObjectAnimator.ofFloat(m.middle, View.TRANSLATION_Y, 0f, -refH))
        }

        val exitSet = AnimatorSet().apply {
            playTogether(exitAnims)
            duration = 300
            interpolator = AccelerateInterpolator()
        }

        exitSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                resetTranslations(
                    mainChatRV, mainChatSidebar, presChatRV, presChatSidebar,
                    mainMenu?.header, mainMenu?.headerDivider, mainMenu?.middle,
                    mainMenu?.bottomBarDivider, mainMenu?.bottomBar,
                    presMenu?.header, presMenu?.headerDivider, presMenu?.middle,
                    presMenu?.bottomBarDivider, presMenu?.bottomBar
                )
                mainSL.swapOverlayAlpha = 0f
                mainSL.swapBackgroundColor = 0
                mainSL.clipDuringSwap = false
                presSL?.swapOverlayAlpha = 0f
                presSL?.swapBackgroundColor = 0
                presSL?.clipDuringSwap = false

                viewsSwapped = !viewsSwapped
                getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                    .putBoolean("views_swapped", viewsSwapped).apply()

                reconnectSecondaryDisplay()
                fitScreensToParent()

                if (wasOptions || wasNameInput || wasColor || wasCredits || wasDisplaySetup || wasExportChat) {
                    bv.roomSelectionContent.visibility = View.GONE
                }
                if (wasOptions)      bv.optionsContent.visibility = View.VISIBLE
                if (wasNameInput)    bv.nameInputContent.visibility = View.VISIBLE
                if (wasColor)        bv.colorContent.visibility = View.VISIBLE
                if (wasCredits)      bv.creditsContent.visibility = View.VISIBLE
                if (wasDisplaySetup) bv.displaySetupContent.visibility = View.VISIBLE
                if (wasExportChat)   bv.exportChatContent.visibility = View.VISIBLE

                val newMainIsMenu = viewsSwapped
                val newMainSL = (if (viewsSwapped) binding.bottomScreen else binding.topScreen) as ScaleLayout
                val newPresSL = roomPresentation?.scaleLayout ?: chatHistoryPresentation?.scaleLayout

                val newMainChatRV: View? = if (!newMainIsMenu) binding.chatRecyclerView else null
                val newMainChatSidebar: View? = if (!newMainIsMenu) binding.topSidebar else null
                val newPresChatRV: View? = if (newMainIsMenu) chatHistoryPresentation?.chatRecyclerView else null
                val newPresChatSidebar: View? = if (newMainIsMenu)
                    chatHistoryPresentation?.chatHistoryBackground?.findViewById(R.id.topSidebar) else null
                val newSidebarW = (newMainChatSidebar ?: newPresChatSidebar)?.width?.toFloat() ?: sidebarW

                val newMainMenuContent = if (newMainIsMenu) visibleMenuContent() else null
                val newMainMenu = newMainMenuContent?.let { menuAnimViewsFrom(it) }

                val newPresMenuContent: View? = if (!newMainIsMenu) {
                    val p = roomPresentation
                    if (p != null) {
                        when {
                            p.optionsContent.visibility == View.VISIBLE        -> p.optionsContent
                            p.nameInputContent.visibility == View.VISIBLE      -> p.nameInputContent
                            p.colorContent.visibility == View.VISIBLE          -> p.colorContent
                            p.creditsContent.visibility == View.VISIBLE        -> p.creditsContent
                            p.displaySetupContent.visibility == View.VISIBLE   -> p.displaySetupContent
                            p.exportChatContent.visibility == View.VISIBLE     -> p.exportChatContent
                            else -> p.roomSelectionContent
                        }
                    } else null
                } else null
                val newPresMenu = newPresMenuContent?.let { menuAnimViewsFrom(it) }

                newMainSL.swapBackgroundColor = greyColor
                newMainSL.swapOverlayAlpha = 1f
                newMainSL.clipDuringSwap = true
                newPresSL?.swapBackgroundColor = greyColor
                newPresSL?.swapOverlayAlpha = 1f
                newPresSL?.clipDuringSwap = true

                newMainChatRV?.translationY = refH
                newMainChatSidebar?.translationX = -newSidebarW
                newPresChatRV?.translationY = -refH
                newPresChatSidebar?.translationX = -newSidebarW

                newMainMenu?.let { m ->
                    m.header.translationY = -refH
                    m.headerDivider.translationY = -refH
                    m.bottomBar.translationY = refH
                    m.bottomBarDivider.translationY = refH
                    m.middle.translationY = refH
                }
                newPresMenu?.let { m ->
                    m.header.translationY = -refH
                    m.headerDivider.translationY = -refH
                    m.bottomBar.translationY = refH
                    m.bottomBarDivider.translationY = refH
                    m.middle.translationY = -refH
                }

                handler.postDelayed({
                    val entryAnims = mutableListOf<Animator>(
                        ObjectAnimator.ofFloat(newMainSL, "swapOverlayAlpha", 1f, 0f)
                    )
                    newPresSL?.let {
                        entryAnims.add(ObjectAnimator.ofFloat(it, "swapOverlayAlpha", 1f, 0f))
                    }

                    newMainChatRV?.let {
                        entryAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, refH, 0f))
                    }
                    newMainChatSidebar?.let {
                        entryAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_X, -newSidebarW, 0f))
                    }
                    newPresChatRV?.let {
                        entryAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, -refH, 0f))
                    }
                    newPresChatSidebar?.let {
                        entryAnims.add(ObjectAnimator.ofFloat(it, View.TRANSLATION_X, -newSidebarW, 0f))
                    }

                    newMainMenu?.let { m ->
                        entryAnims.add(ObjectAnimator.ofFloat(m.header, View.TRANSLATION_Y, -refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.headerDivider, View.TRANSLATION_Y, -refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.bottomBar, View.TRANSLATION_Y, refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.bottomBarDivider, View.TRANSLATION_Y, refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.middle, View.TRANSLATION_Y, refH, 0f))
                    }
                    newPresMenu?.let { m ->
                        entryAnims.add(ObjectAnimator.ofFloat(m.header, View.TRANSLATION_Y, -refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.headerDivider, View.TRANSLATION_Y, -refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.bottomBar, View.TRANSLATION_Y, refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.bottomBarDivider, View.TRANSLATION_Y, refH, 0f))
                        entryAnims.add(ObjectAnimator.ofFloat(m.middle, View.TRANSLATION_Y, -refH, 0f))
                    }

                    AnimatorSet().apply {
                        playTogether(entryAnims)
                        duration = 300
                        interpolator = DecelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                resetTranslations(
                                    newMainChatRV, newMainChatSidebar,
                                    newPresChatRV, newPresChatSidebar,
                                    newMainMenu?.header, newMainMenu?.headerDivider,
                                    newMainMenu?.middle,
                                    newMainMenu?.bottomBarDivider, newMainMenu?.bottomBar,
                                    newPresMenu?.header, newPresMenu?.headerDivider,
                                    newPresMenu?.middle,
                                    newPresMenu?.bottomBarDivider, newPresMenu?.bottomBar
                                )
                                newMainSL.swapOverlayAlpha = 0f
                                newMainSL.swapBackgroundColor = 0
                                newMainSL.clipDuringSwap = false
                                newPresSL?.swapOverlayAlpha = 0f
                                newPresSL?.swapBackgroundColor = 0
                                newPresSL?.clipDuringSwap = false
                                isTransitioning = false
                            }
                        })
                        start()
                    }
                }, 100)
            }
        })

        exitSet.start()
    }

    private fun refreshRoomCounts() {
        val counts = bleScanner?.getRoomCounts()
        if (counts != null) updateRoomCounts(counts)
    }

    // =====================================================================
    // Options screen transition
    // =====================================================================

    private fun showOptions() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        optionsSelectedIndex = 0
        bv.cornerHighlightOptions.tag = null

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.roomSelectionContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    updateOptionsSelection()
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.roomSelectionContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                updateOptionsSelection()
                isTransitioning = false
            }
    }

    private fun showRoomSelection() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "room_selection"
        selectedIndex = 0
        bv.cornerHighlight.tag = null

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.roomSelectionContent.visibility = View.VISIBLE
                    updateSelection()
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.roomSelectionContent.visibility = View.VISIBLE
                updateSelection()
                isTransitioning = false
            }
    }

    // =====================================================================
    // Name input screen transition
    // =====================================================================

    private fun showNameInput() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "name_input"
        setNameKeyboardMode(KeyboardMode.LATIN)
        bv.nameKeyboard.showFocus = true
        nameButtonFocused = false
        nameButtonIndex = 0
        updateKbButtonHighlight()

        nameDraft = username
        bv.nameInputBoxes.text = username

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.nameInputContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.nameInputContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun showOptionsFromName() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        bv.nameKeyboard.showFocus = false
        updateExportButtonStyle()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.nameInputContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.nameInputContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    // =====================================================================
    // Color picker screen transition
    // =====================================================================

    private fun showColorPicker() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "color"

        colorDraft = colorIndex
        bv.colorGrid.selectedIndex = colorIndex
        bv.colorPreviewSwatch.setBackgroundColor(ThemeColors.PALETTE[colorIndex])

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.colorContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.colorContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun showOptionsFromColor() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        updateExportButtonStyle()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.colorContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.colorContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun showCredits() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "credits"

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.creditsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.creditsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun showOptionsFromCredits() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        updateExportButtonStyle()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.creditsContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.creditsContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    // =====================================================================
    // Display Setup screen transition
    // =====================================================================

    private fun showDisplaySetup() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "display_setup"
        displaySetupSelectedIndex = 0
        bv.cornerHighlightDisplaySetup.tag = null

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.displaySetupContent.visibility = View.VISIBLE
                    updateDisplaySetupSelection()
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.displaySetupContent.visibility = View.VISIBLE
                updateDisplaySetupSelection()
                isTransitioning = false
            }
    }

    private fun showOptionsFromDisplaySetup() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        updateExportButtonStyle()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.displaySetupContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.displaySetupContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun updateDisplaySetupSelection() {
        val highlight = bv.cornerHighlightDisplaySetup

        highlight.foreground = createHighlightDrawable(ThemeColors.PALETTE[colorIndex])

        highlightAnimatorDisplaySetup?.cancel()

        if (highlight.tag != null) {
            positionDisplaySetupHighlight()
        } else {
            val btn = bv.displaySetupButtons[displaySetupSelectedIndex]
            btn.post { btn.post { positionDisplaySetupHighlight() } }
        }
    }

    private fun positionDisplaySetupHighlight() {
        val highlight = bv.cornerHighlightDisplaySetup
        val btn = bv.displaySetupButtons[displaySetupSelectedIndex]
        val density = resources.displayMetrics.density
        val vertPad = (5f * density).toInt()

        if (btn.height == 0) return

        val lp = highlight.layoutParams
        lp.height = btn.height + vertPad * 2
        highlight.layoutParams = lp

        val targetY = btn.top.toFloat() - vertPad

        if (highlight.tag == null || !highlight.isLaidOut) {
            highlight.translationY = targetY
        } else {
            highlightAnimatorDisplaySetup = android.animation.ValueAnimator.ofFloat(highlight.translationY, targetY).apply {
                duration = 120
                addUpdateListener { highlight.translationY = it.animatedValue as Float }
                start()
            }
        }
        highlight.tag = true
    }

    private fun activateDisplaySetupOption() {
        bv.displaySetupButtons[displaySetupSelectedIndex].performClick()
    }

    // =====================================================================
    // Export Chat screen transition + logic
    // =====================================================================

    private var exportJob: kotlinx.coroutines.Job? = null
    private var currentExportDrawing: ChatMessage.DrawingMessage? = null

    private fun unexportedDrawings(): List<ChatMessage.DrawingMessage> =
        ChatRepository.getAllMessages()
            .filterIsInstance<ChatMessage.DrawingMessage>()
            .filter { it.hash !in exportedHashes }

    private fun updateExportButtonStyle() {
        val hasDrawings = ChatRepository.getAllMessages().any { it is ChatMessage.DrawingMessage }
        val btn = bv.btnExportChat as TextView
        if (hasDrawings) {
            btn.setTextColor(ContextCompat.getColor(this, R.color.ds_black))
            btn.background = ContextCompat.getDrawable(this, R.drawable.bg_quit_button)
        } else {
            val grey = Color.GRAY
            btn.setTextColor(grey)
            val d = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ContextCompat.getColor(this@RoomSelectionActivity, R.color.ds_gray_stripe))
                setStroke((1f * resources.displayMetrics.density).toInt(), grey)
            }
            btn.background = d
        }
    }

    private fun updateExportPreview() {
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager
        val lastVisible = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val messages = chatAdapter.getMessages()
        val searchFrom = if (lastVisible > 0) lastVisible else messages.size
        for (i in searchFrom downTo 1) {
            val msg = messages.getOrNull(i - 1)
            if (msg is ChatMessage.DrawingMessage) {
                currentExportDrawing = msg
                val drawable = if (msg.rainbowBits != null) {
                    RainbowBitmapDrawable(msg.bitmap, msg.rainbowBits)
                } else {
                    android.graphics.drawable.BitmapDrawable(resources, msg.bitmap).apply {
                        isFilterBitmap = false
                        setAntiAlias(false)
                    }
                }
                bv.exportPreviewImage.setImageDrawable(drawable)
                return
            }
        }
    }

    private fun showExportChat() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "export_chat"

        bv.exportConfirmText.text = getString(R.string.export_chat_confirm)
        bv.exportConfirmText.visibility = View.VISIBLE
        bv.exportHintText.visibility = View.VISIBLE
        bv.exportPreviewImage.visibility = View.VISIBLE
        bv.btnExportScrollDown.visibility = View.VISIBLE
        bv.btnExportScrollUp.visibility = View.VISIBLE
        bv.exportProgressBar.visibility = View.GONE
        bv.btnExportNo.visibility = View.VISIBLE
        bv.btnExportYes.visibility = View.VISIBLE
        updateExportPreview()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.optionsContent.visibility = View.GONE
                    bv.exportChatContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.optionsContent.visibility = View.GONE
                bv.exportChatContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun showOptionsFromExportChat() {
        if (isTransitioning) return
        isTransitioning = true
        activeMenuScreen = "options"
        currentExportDrawing = null
        updateExportButtonStyle()

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bv.exportChatContent.visibility = View.GONE
                    bv.optionsContent.visibility = View.VISIBLE
                    overlay?.animate()
                        ?.alpha(0f)
                        ?.setDuration(200)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlay?.visibility = View.GONE
                                isTransitioning = false
                            }
                        })
                        ?.start()
                }
            })
            ?.start()
            ?: run {
                bv.exportChatContent.visibility = View.GONE
                bv.optionsContent.visibility = View.VISIBLE
                isTransitioning = false
            }
    }

    private fun startExport() {
        val drawing = currentExportDrawing
        if (drawing == null) {
            soundManager.play(SoundManager.Sound.INVALID)
            return
        }

        exportJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val w = Constants.CANVAS_W * 4
                val h = Constants.CANVAS_H * 4
                val sourceBitmap = if (drawing.rainbowBits != null) {
                    PictoCanvasView.compositeRainbowBitmap(drawing.bitmap, drawing.rainbowBits)
                } else {
                    drawing.bitmap
                }
                val scaled = Bitmap.createScaledBitmap(sourceBitmap, w, h, false)
                if (drawing.rainbowBits != null) sourceBitmap.recycle()
                val opaque = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(opaque)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                scaled.recycle()
                saveBitmapToMediaStore(opaque, "PicoChat_${System.currentTimeMillis()}.png")
                opaque.recycle()
            }
            exportedHashes.add(drawing.hash)
            getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                .putStringSet("exported_hashes", exportedHashes.map { it.toString() }.toSet())
                .apply()
            soundManager.play(SoundManager.Sound.EXPORT_SUCCESS)
            showOptionsFromExportChat()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, filename: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicoChat")
        }
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // =====================================================================
    // Theme color
    // =====================================================================

    private fun applyThemeColor(idx: Int) {
        val color = ThemeColors.PALETTE[idx]
        val topBright = ThemeColors.brighten(color, 0.70f)
        val bottomBright = ThemeColors.brighten(color, 0.60f)

        bv.headerViews.forEach { it.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topBright, color)
        ) }
        bv.bottomBarViews.forEach { it.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, bottomBright)
        ) }

        val kbButtons = listOf(bv.nameKbLatin, bv.nameKbAccented, bv.nameKbKatakana, bv.nameKbSymbols, bv.nameKbEmoji)
        val keyBg = ContextCompat.getColor(this, R.color.ds_key_bg)
        val keyBorder = ContextCompat.getColor(this, R.color.ds_key_border)
        val dark = ThemeColors.darken(color)
        for (btn in kbButtons) {
            btn.background = makeToolButtonDrawable(color, dark, keyBg, keyBorder)
        }

        bv.nameKeyboard.accentColor = color

        bv.cornerHighlight.foreground = createHighlightDrawable(color)
        bv.cornerHighlightOptions.foreground = createHighlightDrawable(color)
        bv.cornerHighlightDisplaySetup.foreground = createHighlightDrawable(color)
    }

    private fun makeToolButtonDrawable(
        selectedFill: Int, selectedStroke: Int,
        normalFill: Int, normalStroke: Int
    ): StateListDrawable {
        val strokePx = (1f * resources.displayMetrics.density).toInt()
        val selected = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(selectedFill)
            setStroke(strokePx, selectedStroke)
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(normalFill)
            setStroke(strokePx, normalStroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }
    }

    private fun makeStripedDrawable(): StripedDrawable {
        val bgColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val lineColor = 0xFFDDDDDD.toInt()
        val density = resources.displayMetrics.density
        return StripedDrawable(bgColor, lineColor, 3f * density, 1f * density)
    }

    private fun applyStripedBackground(view: View) {
        view.background = makeStripedDrawable()
    }

    private fun applyStripedBackgrounds() {
        val screens = listOf(bv.roomSelectionContent, bv.optionsContent, bv.nameInputContent, bv.colorContent, bv.creditsContent, bv.displaySetupContent, bv.exportChatContent, bv.connectingContent)
        for (screen in screens) {
            screen.background = makeStripedDrawable()
        }
    }

    // =====================================================================
    // Room joining
    // =====================================================================

    private var isTransitioning = false

    private fun joinRoom() {
        if (isTransitioning) return

        val room = Room.entries[selectedIndex]
        val count = bleScanner?.getRoomCounts()?.get(room) ?: 0
        if (count >= Constants.MAX_ROOM_USERS) {
            soundManager.play(SoundManager.Sound.INVALID)
            return
        }

        isTransitioning = true
        soundManager.play(SoundManager.Sound.JOIN)

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.setListener(null)
            ?.alpha(1f)
            ?.setDuration(200)
            ?.withEndAction { launchChat(room) }
            ?.start()
            ?: launchChat(room)
    }

    private fun launchChat(room: Room) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(Constants.EXTRA_ROOM, room.name)
            putExtra(Constants.EXTRA_USERNAME, username)
            putExtra(Constants.EXTRA_COLOR_INDEX, colorIndex)
        }
        startActivity(intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // =====================================================================
    // Online connection
    // =====================================================================

    private val loadingFrames = intArrayOf(
        R.drawable.internet_loading_1,
        R.drawable.internet_loading_2,
        R.drawable.internet_loading_3,
        R.drawable.internet_loading_4
    )
    private var animationRunnable: Runnable? = null
    private var onlineConnecting = false

    private fun startOnlineConnection() {
        if (onlineConnecting) return
        onlineConnecting = true

        soundManager.play(SoundManager.Sound.SELECT)

        bv.roomSelectionContent.visibility = View.GONE
        bv.connectingContent.visibility = View.VISIBLE

        val animView = bv.connectingAnimation
        val themeColor = ThemeColors.PALETTE[colorIndex]
        val tintedFrames = loadingFrames.map { tintBlackPixels(it, themeColor) }

        var frameIndex = 0
        val frameRunnable = object : Runnable {
            override fun run() {
                animView.setImageBitmap(tintedFrames[frameIndex])
                frameIndex = (frameIndex + 1) % tintedFrames.size
                handler.postDelayed(this, 100)
            }
        }
        animationRunnable = frameRunnable
        handler.post(frameRunnable)

        soundManager.playLooping(SoundManager.Sound.ONLINE_SEARCHING)

        val startTime = System.currentTimeMillis()
        val client = com.markusmaribu.picochat.online.SupabaseProvider.client

        lifecycleScope.launch {
            var connected = false
            try {
                val channel = client.channel("connection-test")
                channel.subscribe()

                val deadline = System.currentTimeMillis() + Constants.ONLINE_CONNECT_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (channel.status.value == io.github.jan.supabase.realtime.RealtimeChannel.Status.SUBSCRIBED) {
                        connected = true
                        break
                    }
                    kotlinx.coroutines.delay(200)
                }
                try { channel.unsubscribe() } catch (_: Exception) {}
            } catch (_: Exception) {
                connected = false
            }

            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 1000L - elapsed
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
            }

            if (connected) {
                val roomCounts = fetchInitialPresenceCounts(client)
                withContext(Dispatchers.Main) {
                    stopConnectingAnimation()
                    onConnectionSuccess(roomCounts)
                }
            } else {
                withContext(Dispatchers.Main) {
                    stopConnectingAnimation()
                    onConnectionFailure()
                }
            }
        }
    }

    private fun stopConnectingAnimation() {
        animationRunnable?.let { handler.removeCallbacks(it) }
        animationRunnable = null
        soundManager.stopLooping()
    }

    private fun tintBlackPixels(resId: Int, color: Int): android.graphics.Bitmap {
        val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true }
        val bmp = android.graphics.BitmapFactory.decodeResource(resources, resId, opts)
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

    private suspend fun fetchInitialPresenceCounts(
        client: io.github.jan.supabase.SupabaseClient
    ): IntArray {
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
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            channels.forEach { ch ->
                try { ch.unsubscribe() } catch (_: Exception) {}
                try { client.realtime.removeChannel(ch) } catch (_: Exception) {}
            }
        }
        return roomCounts
    }

    private fun onConnectionSuccess(roomCounts: IntArray) {
        soundManager.play(SoundManager.Sound.ONLINE_FOUND)
        onlineConnecting = false

        val overlay = activeOverlay()
        overlay?.alpha = 0f
        overlay?.visibility = View.VISIBLE
        overlay?.animate()
            ?.setListener(null)
            ?.alpha(1f)
            ?.setDuration(200)
            ?.withEndAction {
                bv.connectingContent.visibility = View.GONE
                bv.roomSelectionContent.visibility = View.VISIBLE
                launchOnlineRoomSelection(roomCounts)
            }
            ?.start()
            ?: run {
                bv.connectingContent.visibility = View.GONE
                bv.roomSelectionContent.visibility = View.VISIBLE
                launchOnlineRoomSelection(roomCounts)
            }
    }

    private fun launchOnlineRoomSelection(roomCounts: IntArray) {
        val intent = Intent(this, OnlineRoomSelectionActivity::class.java).apply {
            putExtra(Constants.EXTRA_USERNAME, username)
            putExtra(Constants.EXTRA_COLOR_INDEX, colorIndex)
            putExtra(Constants.EXTRA_INITIAL_ROOM_COUNTS, roomCounts)
        }
        startActivity(intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun onConnectionFailure() {
        soundManager.play(SoundManager.Sound.FAILURE)
        onlineConnecting = false
        bv.connectingContent.visibility = View.GONE
        bv.roomSelectionContent.visibility = View.VISIBLE
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

        val rightId = if (!viewsSwapped) R.id.bottomScreen else R.id.topScreen
        val leftId  = if (!viewsSwapped) R.id.topScreen else R.id.bottomScreen

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
    // Username & Permissions
    // =====================================================================

    private fun requestBlePermissions() {
        val needed = BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), BLE_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLE_PERMISSION_REQUEST) {
            startBleScanning()
        }
    }

    private fun startBleScanning() {
        if (!hasBlePermissions()) return
        if (bleScanner == null) {
            bleScanner = BleScanner(this)
        }
        bleScanner?.startScan { counts ->
            runOnUiThread { updateRoomCounts(counts) }
        }
    }

    private fun hasBlePermissions(): Boolean {
        return BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    private fun handleControllerDirection(dx: Int, dy: Int) {
        when (activeMenuScreen) {
            "room_selection" -> {
                if (dy != 0) {
                    val newIndex = (selectedIndex + dy).coerceIn(0, bv.roomRows.lastIndex)
                    if (newIndex != selectedIndex) {
                        selectedIndex = newIndex
                        updateSelection()
                    }
                }
            }
            "options" -> {
                if (dy != 0) {
                    val newIndex = (optionsSelectedIndex + dy).coerceIn(0, bv.optionButtons.lastIndex)
                    if (newIndex != optionsSelectedIndex) {
                        optionsSelectedIndex = newIndex
                        updateOptionsSelection()
                    }
                }
            }
            "display_setup" -> {
                if (dy != 0) {
                    val newIndex = (displaySetupSelectedIndex + dy).coerceIn(0, bv.displaySetupButtons.lastIndex)
                    if (newIndex != displaySetupSelectedIndex) {
                        displaySetupSelectedIndex = newIndex
                        updateDisplaySetupSelection()
                    }
                }
            }
            "export_chat" -> {
                if (dx < 0) {
                    scrollChatDown()
                    updateExportPreview()
                } else if (dx > 0) {
                    scrollChatUp()
                    updateExportPreview()
                }
            }
            "color" -> {
                val cols = 4
                val idx = bv.colorGrid.selectedIndex
                val row = idx / cols
                val col = idx % cols
                val newRow = (row + dy).coerceIn(0, 3)
                val newCol = (col + dx).coerceIn(0, cols - 1)
                val newIdx = newRow * cols + newCol
                if (newIdx != idx && newIdx in ThemeColors.PALETTE.indices) {
                    bv.colorGrid.selectedIndex = newIdx
                    soundManager.play(SoundManager.Sound.SELECT_LAYOUT)
                    colorDraft = newIdx
                    bv.colorPreviewSwatch.setBackgroundColor(ThemeColors.PALETTE[newIdx])
                    applyThemeColor(newIdx)
                }
            }
            "name_input" -> {
                if (nameButtonFocused) {
                    if (dy < 0) {
                        nameButtonFocused = false
                        bv.nameKeyboard.showFocus = true
                        updateKbButtonHighlight()
                    }
                    if (dx != 0) {
                        nameButtonIndex = if (nameButtonIndex == 0) 1 else 0
                        updateKbButtonHighlight()
                    }
                } else {
                    if (dy < 0) bv.nameKeyboard.moveFocusUp()
                    else if (dy > 0) {
                        if (!bv.nameKeyboard.moveFocusDown()) {
                            nameButtonFocused = true
                            nameButtonIndex = 0
                            bv.nameKeyboard.showFocus = false
                            updateKbButtonHighlight()
                        }
                    }
                    if (dx < 0) bv.nameKeyboard.moveFocusLeft()
                    else if (dx > 0) bv.nameKeyboard.moveFocusRight()
                }
            }
        }
    }

    private fun activateOption() {
        bv.optionButtons[optionsSelectedIndex].performClick()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { handleControllerDirection(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleControllerDirection(0, 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { handleControllerDirection(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { handleControllerDirection(1, 0); return true }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    when (activeMenuScreen) {
                        "room_selection" -> { if (selectedIndex == 3) startOnlineConnection() else joinRoom(); return true }
                        "options" -> { activateOption(); return true }
                        "display_setup" -> { activateDisplaySetupOption(); return true }
                        "export_chat" -> {
                            if (bv.btnExportYes.visibility == View.VISIBLE) {
                                bv.btnExportYes.performClick()
                            }
                            return true
                        }
                        "color" -> {
                            colorIndex = bv.colorGrid.selectedIndex
                            colorDraft = -1
                            getSharedPreferences("picochat_prefs", MODE_PRIVATE).edit()
                                .putInt("theme_color_index", colorIndex).apply()
                            soundManager.play(SoundManager.Sound.CONFIRM)
                            showOptionsFromColor()
                            return true
                        }
                        "name_input" -> {
                            if (nameButtonFocused) {
                                val btn = if (nameButtonIndex == 0) bv.btnKbCancel else bv.btnKbConfirm
                                btn.performClick()
                            } else {
                                bv.nameKeyboard.activateFocusedKey()
                            }
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    when (activeMenuScreen) {
                        "room_selection" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            showOptions()
                            return true
                        }
                        "options" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            showRoomSelection()
                            return true
                        }
                        "color" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            colorDraft = -1
                            applyThemeColor(colorIndex)
                            showOptionsFromColor()
                            return true
                        }
                        "credits" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            showOptionsFromCredits()
                            return true
                        }
                        "display_setup" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            showOptionsFromDisplaySetup()
                            return true
                        }
                        "export_chat" -> {
                            if (bv.btnExportNo.visibility == View.VISIBLE) {
                                bv.btnExportNo.performClick()
                            }
                            return true
                        }
                        "name_input" -> {
                            if (bv.nameInputBoxes.deleteChar()) {
                                soundManager.play(SoundManager.Sound.KEY_DOWN)
                            } else {
                                soundManager.play(SoundManager.Sound.INVALID)
                            }
                            nameDraft = bv.nameInputBoxes.text
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    when (activeMenuScreen) {
                        "options" -> {
                            soundManager.play(SoundManager.Sound.SELECT)
                            showCredits()
                            return true
                        }
                        "name_input" -> {
                            cycleNameKeyboardMode()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    scrollChatDown()
                    if (activeMenuScreen == "export_chat") updateExportPreview()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    scrollChatUp()
                    if (activeMenuScreen == "export_chat") updateExportPreview()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (activeMenuScreen == "name_input" && bv.nameKeyboard.keyboardMode == KeyboardMode.LATIN) {
                        bv.nameKeyboard.cycleCaps()
                        soundManager.play(SoundManager.Sound.KEY_DOWN)
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_X -> return true
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
                    handleControllerDirection(0, if (y > 0) 1 else -1)
                }
            } else {
                stickHeldY = false
            }

            val x = event.getAxisValue(MotionEvent.AXIS_X)
            if (Math.abs(x) > 0.5f) {
                if (!stickHeldX) {
                    stickHeldX = true
                    handleControllerDirection(if (x > 0) 1 else -1, 0)
                }
            } else {
                stickHeldX = false
            }

            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (Math.abs(hatY) > 0.5f) {
                if (!hatHeldY) {
                    hatHeldY = true
                    handleControllerDirection(0, if (hatY > 0) 1 else -1)
                }
            } else {
                hatHeldY = false
            }

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            if (Math.abs(hatX) > 0.5f) {
                if (!hatHeldX) {
                    hatHeldX = true
                    handleControllerDirection(if (hatX > 0) 1 else -1, 0)
                }
            } else {
                hatHeldX = false
            }

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
