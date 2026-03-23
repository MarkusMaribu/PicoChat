package com.markusmaribu.picochat.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
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
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.databinding.ActivityOnlineRoomSelectionBinding
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.online.SupabaseProvider
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import com.markusmaribu.picochat.util.ThemeColors
import com.markusmaribu.picochat.util.clearFocusability
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.launch

class OnlineRoomSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = ScaleLayout.targetDensityDpi(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private lateinit var binding: ActivityOnlineRoomSelectionBinding
    private lateinit var soundManager: SoundManager
    private lateinit var chatAdapter: ChatHistoryAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var selectedIndex = 0
    private var username = "Player"
    private var colorIndex = ThemeColors.DEFAULT_INDEX
    private var highlightAnimator: ValueAnimator? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var isTransitioning = false
    private var initialCounts = IntArray(4)

    private var displayManager: DisplayManager? = null
    private var chatHistoryPresentation: ChatHistoryPresentation? = null
    private var onlineRoomPresentation: OnlineRoomSelectionPresentation? = null
    private var presentationChatAdapter: ChatHistoryAdapter? = null
    private var isSecondaryDisplayActive = false
    private var viewsSwapped = false

    private lateinit var roomRows: List<View>
    private lateinit var roomCountViews: List<TextView>
    private lateinit var cornerHighlight: View
    private lateinit var headerViews: List<View>
    private lateinit var bottomBarViews: List<View>

    private val presenceChannels = mutableListOf<RealtimeChannel>()
    private val roomMemberKeys = Array(4) { mutableSetOf<String>() }
    private var presenceJob: kotlinx.coroutines.Job? = null
    private var channelCleanupJob: kotlinx.coroutines.Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayRemoved(displayId: Int) { checkSecondaryDisplay() }
        override fun onDisplayChanged(displayId: Int) {
            if (!isSecondaryDisplayActive || displayId == Display.DEFAULT_DISPLAY) return
            reconnectSecondaryDisplay()
        }
    }

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

    private val onlineLabels = arrayOf(
        R.string.online_room_a,
        R.string.online_room_b,
        R.string.online_room_c,
        R.string.online_room_d
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineRoomSelectionBinding.inflate(layoutInflater)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        username = intent.getStringExtra(Constants.EXTRA_USERNAME) ?: username
        colorIndex = intent.getIntExtra(Constants.EXTRA_COLOR_INDEX, colorIndex)
        initialCounts = intent.getIntArrayExtra(Constants.EXTRA_INITIAL_ROOM_COUNTS) ?: IntArray(4)
        soundManager = SoundManager(this)

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
            }
        })

        roomRows = listOf(
            binding.roomRowA.root,
            binding.roomRowB.root,
            binding.roomRowC.root,
            binding.roomRowD.root
        )

        val rooms = Room.entries
        roomCountViews = roomRows.mapIndexed { i, row ->
            val room = rooms[i]
            row.findViewById<TextView>(R.id.roomLetterIcon).text = room.letter
            row.findViewById<TextView>(R.id.roomLabel).text = getString(onlineLabels[i])
            val countView = row.findViewById<TextView>(R.id.roomCount)
            val count = initialCounts.getOrElse(i) { 0 }
            countView.text = if (count >= Constants.MAX_ONLINE_ROOM_USERS)
                getString(R.string.room_full)
            else
                getString(R.string.online_room_count_format, count)
            countView
        }

        cornerHighlight = binding.cornerHighlight
        headerViews = listOf(binding.roomSelHeader)
        bottomBarViews = listOf(binding.roomSelBottomBar)

        roomRows.forEachIndexed { index, row ->
            row.setOnClickListener {
                selectedIndex = index
                updateSelection()
                joinOnlineRoom()
            }
        }

        binding.btnBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            performAnimatedBack()
        }

        binding.btnJoin.setOnClickListener { joinOnlineRoom() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = OnBackInvokedCallback { performAnimatedBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback!!
            )
        }

        applyStripedBackground(binding.chatHistoryBackground)
        applyStripedBackground(binding.roomSelectionContent)
        applyThemeColor()

        viewsSwapped = getSharedPreferences("picochat_prefs", MODE_PRIVATE)
            .getBoolean("views_swapped", false)
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
        }
        updateSelection()
        playEntryAnimation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_index", selectedIndex)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        displayManager?.unregisterDisplayListener(displayListener)
        chatHistoryPresentation?.setOnDismissListener(null)
        chatHistoryPresentation?.dismiss()
        chatHistoryPresentation = null
        onlineRoomPresentation?.setOnDismissListener(null)
        onlineRoomPresentation?.dismiss()
        onlineRoomPresentation = null
        presentationChatAdapter = null
        val sm = soundManager
        handler.postDelayed({ sm.release() }, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        super.onDestroy()
    }

    private fun joinOnlineRoom() {
        if (isTransitioning) return

        val room = Room.entries[selectedIndex]
        if (roomMemberKeys[selectedIndex].size >= Constants.MAX_ONLINE_ROOM_USERS) {
            soundManager.play(SoundManager.Sound.INVALID)
            return
        }

        isTransitioning = true
        soundManager.play(SoundManager.Sound.JOIN)

        val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val bot = activeAnimationTarget()
        bot.swapBackgroundColor = greyColor
        bot.swapOverlayAlpha = 0f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { bot.swapOverlayAlpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { launchChat(room) }
            })
            start()
        }
    }

    private fun launchChat(room: Room) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(Constants.EXTRA_ROOM, room.name)
            putExtra(Constants.EXTRA_USERNAME, username)
            putExtra(Constants.EXTRA_COLOR_INDEX, colorIndex)
            putExtra(Constants.EXTRA_IS_ONLINE, true)
        }
        startActivity(intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun playEntryAnimation() {
        val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val bot = activeAnimationTarget()
        bot.swapBackgroundColor = greyColor
        bot.swapOverlayAlpha = 1f

        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 250
            addUpdateListener { bot.swapOverlayAlpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    bot.swapBackgroundColor = 0
                }
            })
            start()
        }
    }

    private fun performAnimatedBack() {
        val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val bot = activeAnimationTarget()
        bot.swapBackgroundColor = greyColor
        bot.swapOverlayAlpha = 0f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { bot.swapOverlayAlpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    finish()
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }
            })
            start()
        }
    }

    private fun startPresenceTracking() {
        presenceJob?.cancel()
        presenceJob = lifecycleScope.launch {
            channelCleanupJob?.join()
            channelCleanupJob = null
            SupabaseProvider.pendingChannelCleanup?.join()
            SupabaseProvider.pendingChannelCleanup = null

            val client = SupabaseProvider.client
            val rooms = Room.entries
            rooms.forEachIndexed { i, room ->
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
                        val size = roomMemberKeys[i].size
                        roomCountViews[i].text = if (size >= Constants.MAX_ONLINE_ROOM_USERS)
                            getString(R.string.room_full)
                        else
                            getString(R.string.online_room_count_format, size)
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

    private fun stopPresenceTracking() {
        presenceJob?.cancel()
        presenceJob = null
        val channels = presenceChannels.toList()
        presenceChannels.clear()
        if (channels.isNotEmpty()) {
            channelCleanupJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                channels.forEach { ch ->
                    try { ch.unsubscribe() } catch (_: Exception) {}
                    try { SupabaseProvider.client.realtime.removeChannel(ch) } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isTransitioning = false
        ChatRepository.addListener(chatListener)
        chatAdapter.setMessages(ChatRepository.getAllMessages())
        binding.scrollBarVisualizer.setMessages(ChatRepository.getAllMessages())
        if (chatAdapter.itemCount > 0) {
            binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
        binding.chatRecyclerView.post { updateScrollBarVisibleRange() }
        presentationChatAdapter?.let { adapter ->
            adapter.setMessages(ChatRepository.getAllMessages())
            if (adapter.itemCount > 0) {
                chatHistoryPresentation?.chatRecyclerView?.scrollToPosition(adapter.itemCount - 1)
            }
        }
        startPresenceTracking()
        val top = binding.topScreen
        val bot = binding.bottomScreen
        val topActive = top.swapOverlayAlpha > 0f
        val botActive = bot.swapOverlayAlpha > 0f
        if (topActive || botActive) {
            val greyColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
            val startAlpha = maxOf(top.swapOverlayAlpha, bot.swapOverlayAlpha)
            if (topActive) top.swapBackgroundColor = greyColor
            if (botActive) bot.swapBackgroundColor = greyColor
            ValueAnimator.ofFloat(startAlpha, 0f).apply {
                duration = 200
                addUpdateListener {
                    val v = it.animatedValue as Float
                    if (topActive) top.swapOverlayAlpha = v
                    if (botActive) bot.swapOverlayAlpha = v
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        top.swapBackgroundColor = 0
                        bot.swapBackgroundColor = 0
                    }
                })
                start()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.requestLayout()
        binding.root.post { fitScreensToParent() }
    }

    override fun onPause() {
        super.onPause()
        ChatRepository.removeListener(chatListener)
        stopPresenceTracking()
    }

    private fun updateSelection() {
        val color = ThemeColors.PALETTE[colorIndex]
        cornerHighlight.foreground = createHighlightDrawable(color)
        highlightAnimator?.cancel()

        if (cornerHighlight.tag != null) {
            positionHighlight()
        } else {
            val row = roomRows[selectedIndex]
            row.post { row.post { positionHighlight() } }
        }
    }

    private fun positionHighlight() {
        val highlight = cornerHighlight
        val row = roomRows[selectedIndex]
        val density = resources.displayMetrics.density
        val vertPad = (5f * density).toInt()

        if (row.height == 0) return

        val lp = highlight.layoutParams
        lp.height = row.height + vertPad * 2
        highlight.layoutParams = lp

        val targetY = row.top.toFloat() - vertPad

        if (highlight.tag != null) {
            highlightAnimator = ValueAnimator.ofFloat(
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

    private fun applyThemeColor() {
        val color = ThemeColors.PALETTE[colorIndex]
        val topBright = ThemeColors.brighten(color, 0.70f)
        val bottomBright = ThemeColors.brighten(color, 0.60f)

        headerViews.forEach {
            it.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topBright, color)
            )
        }
        bottomBarViews.forEach {
            it.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, bottomBright)
            )
        }

        cornerHighlight.foreground = createHighlightDrawable(color)
    }

    private fun applyStripedBackground(view: View) {
        val bgColor = ContextCompat.getColor(this, R.color.ds_gray_stripe)
        val lineColor = 0xFFDDDDDD.toInt()
        val density = resources.displayMetrics.density
        view.background = StripedDrawable(bgColor, lineColor, 3f * density, 1f * density)
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
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) onSecondaryDisplayDisconnected()
            }
            pres.show()
            chatHistoryPresentation = pres

            val adapter = ChatHistoryAdapter()
            adapter.setMessages(ChatRepository.getAllMessages())
            pres.chatRecyclerView.layoutManager = ClampedLayoutManager()
            pres.chatRecyclerView.adapter = adapter
            pres.chatRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    if (parent.getChildAdapterPosition(view) == 0) outRect.top = parent.height
                }
            })
            presentationChatAdapter = adapter
            applyStripedBackground(pres.chatHistoryBackground)
        } else {
            val pres = OnlineRoomSelectionPresentation(this, display)
            pres.setOnDismissListener {
                if (isSecondaryDisplayActive) onSecondaryDisplayDisconnected()
            }
            pres.show()
            onlineRoomPresentation = pres

            wireRoomViewsFromPresentation(pres)
            applyStripedBackground(pres.roomSelectionContent)
            applyThemeColor()
            updateSelection()
        }

        isSecondaryDisplayActive = true
        fitScreensToParent()
    }

    private fun onSecondaryDisplayDisconnected() {
        chatHistoryPresentation?.setOnDismissListener(null)
        chatHistoryPresentation?.dismiss()
        chatHistoryPresentation = null
        presentationChatAdapter = null

        onlineRoomPresentation?.setOnDismissListener(null)
        onlineRoomPresentation?.dismiss()
        onlineRoomPresentation = null

        wireRoomViewsFromBinding()
        applyThemeColor()
        updateSelection()

        isSecondaryDisplayActive = false
        fitScreensToParent()
    }

    private fun reconnectSecondaryDisplay() {
        val dm = displayManager ?: return
        val secondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        val oldChatPres = chatHistoryPresentation
        val oldRoomPres = onlineRoomPresentation
        chatHistoryPresentation = null
        presentationChatAdapter = null
        onlineRoomPresentation = null
        isSecondaryDisplayActive = false

        if (secondary != null) {
            onSecondaryDisplayConnected(secondary)
        } else {
            wireRoomViewsFromBinding()
            applyThemeColor()
            updateSelection()
            fitScreensToParent()
        }

        oldChatPres?.setOnDismissListener(null)
        oldRoomPres?.setOnDismissListener(null)
        handler.postDelayed({
            try { if (oldChatPres?.isShowing == true) oldChatPres.dismiss() } catch (_: Exception) {}
            try { if (oldRoomPres?.isShowing == true) oldRoomPres.dismiss() } catch (_: Exception) {}
        }, 150)
    }

    private fun wireRoomViewsFromPresentation(pres: OnlineRoomSelectionPresentation) {
        roomRows = listOf(pres.roomRowA, pres.roomRowB, pres.roomRowC, pres.roomRowD)

        val rooms = Room.entries
        roomCountViews = roomRows.mapIndexed { i, row ->
            row.findViewById<TextView>(R.id.roomLetterIcon).text = rooms[i].letter
            row.findViewById<TextView>(R.id.roomLabel).text = getString(onlineLabels[i])
            val countView = row.findViewById<TextView>(R.id.roomCount)
            val size = roomMemberKeys[i].size
            countView.text = if (size >= Constants.MAX_ONLINE_ROOM_USERS)
                getString(R.string.room_full)
            else
                getString(R.string.online_room_count_format, size)
            countView
        }

        cornerHighlight = pres.cornerHighlight
        headerViews = listOf(pres.roomSelHeader)
        bottomBarViews = listOf(pres.roomSelBottomBar)

        roomRows.forEachIndexed { index, row ->
            row.setOnClickListener {
                selectedIndex = index
                updateSelection()
                joinOnlineRoom()
            }
        }

        pres.btnBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            performAnimatedBack()
        }
        pres.btnJoin.setOnClickListener { joinOnlineRoom() }
    }

    private fun wireRoomViewsFromBinding() {
        roomRows = listOf(
            binding.roomRowA.root,
            binding.roomRowB.root,
            binding.roomRowC.root,
            binding.roomRowD.root
        )

        val rooms = Room.entries
        roomCountViews = roomRows.mapIndexed { i, row ->
            row.findViewById<TextView>(R.id.roomLetterIcon).text = rooms[i].letter
            row.findViewById<TextView>(R.id.roomLabel).text = getString(onlineLabels[i])
            val countView = row.findViewById<TextView>(R.id.roomCount)
            val size = roomMemberKeys[i].size
            countView.text = if (size >= Constants.MAX_ONLINE_ROOM_USERS)
                getString(R.string.room_full)
            else
                getString(R.string.online_room_count_format, size)
            countView
        }

        cornerHighlight = binding.cornerHighlight
        headerViews = listOf(binding.roomSelHeader)
        bottomBarViews = listOf(binding.roomSelBottomBar)

        roomRows.forEachIndexed { index, row ->
            row.setOnClickListener {
                selectedIndex = index
                updateSelection()
                joinOnlineRoom()
            }
        }

        binding.btnBack.setOnClickListener {
            soundManager.play(SoundManager.Sound.SELECT)
            performAnimatedBack()
        }
        binding.btnJoin.setOnClickListener { joinOnlineRoom() }
    }

    private fun activeAnimationTarget(): ScaleLayout = binding.bottomScreen

    // =====================================================================
    // Screen Sizing
    // =====================================================================

    private fun fitScreensToParent() {
        if (isSecondaryDisplayActive) {
            layoutFullscreen()
            return
        }
        binding.root.doOnLayout { root ->
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
        set.setVisibility(R.id.btnSwitchViews, ConstraintSet.GONE)

        set.applyTo(binding.root)
    }

    private fun layoutPortrait(parentW: Int, parentH: Int) {
        val halfH = parentH / 2
        val screenW: Int
        val screenH: Int
        if (halfH * 4 > parentW * 3) {
            screenW = parentW
            screenH = parentW * 3 / 4
        } else {
            screenH = halfH
            screenW = halfH * 4 / 3
        }

        val topId = if (!viewsSwapped) R.id.topScreen else R.id.bottomScreen
        val bottomId = if (!viewsSwapped) R.id.bottomScreen else R.id.topScreen

        val set = ConstraintSet()
        set.clone(binding.root)

        set.clear(R.id.topScreen)
        set.clear(R.id.bottomScreen)

        set.setVisibility(R.id.topScreen, ConstraintSet.VISIBLE)
        set.setVisibility(R.id.bottomScreen, ConstraintSet.VISIBLE)

        set.constrainWidth(topId, screenW)
        set.constrainHeight(topId, screenH)
        set.connect(topId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(topId, ConstraintSet.BOTTOM, R.id.guidelineHalf, ConstraintSet.TOP)
        set.connect(topId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(topId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.setVerticalBias(topId, 1.0f)

        set.constrainWidth(bottomId, screenW)
        set.constrainHeight(bottomId, screenH)
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

        set.setVisibility(R.id.topScreen, ConstraintSet.VISIBLE)
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

    private inner class ClampedLayoutManager : LinearLayoutManager(this@OnlineRoomSelectionActivity) {
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

    // =====================================================================
    // Controller / D-pad input
    // =====================================================================

    private fun handleControllerDirection(dy: Int) {
        if (dy != 0) {
            val newIndex = (selectedIndex + dy).coerceIn(0, roomRows.lastIndex)
            if (newIndex != selectedIndex) {
                selectedIndex = newIndex
                updateSelection()
            }
        }
    }

    private fun updateScrollBarVisibleRange() {
        val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        binding.scrollBarVisualizer.setVisibleRange(first, last)
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
        if (lastVisible < chatAdapter.itemCount - 1) {
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { handleControllerDirection(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleControllerDirection(1); return true }
                KeyEvent.KEYCODE_BUTTON_A -> { joinOnlineRoom(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    soundManager.play(SoundManager.Sound.SELECT)
                    performAnimatedBack()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { scrollChatDown(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { scrollChatUp(); return true }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var stickHeldY = false
    private var hatHeldY = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE
        ) {
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            if (Math.abs(y) > 0.5f) {
                if (!stickHeldY) {
                    stickHeldY = true
                    handleControllerDirection(if (y > 0) 1 else -1)
                }
            } else {
                stickHeldY = false
            }

            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (Math.abs(hatY) > 0.5f) {
                if (!hatHeldY) {
                    hatHeldY = true
                    handleControllerDirection(if (hatY > 0) 1 else -1)
                }
            } else {
                hatHeldY = false
            }

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
