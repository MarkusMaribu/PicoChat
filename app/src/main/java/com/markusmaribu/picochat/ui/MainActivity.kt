package com.markusmaribu.picochat.ui

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.markusmaribu.picochat.state.AppScreen
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.ui.compose.AppRoot
import com.markusmaribu.picochat.ui.compose.DsScreenPane
import com.markusmaribu.picochat.ui.compose.core.DsTransitionState
import com.markusmaribu.picochat.ui.compose.screens.roomselection.RoomSelectionController
import com.markusmaribu.picochat.ui.compose.theme.DsColors
import com.markusmaribu.picochat.ui.host.ComposePresentation
import com.markusmaribu.picochat.ui.host.DisplayCoordinator
import kotlinx.coroutines.launch

/**
 * The app's single activity: hosts the Compose UI in the phone window,
 * mirrors one DS screen to a secondary display via [ComposePresentation],
 * and is the single entry point for key/controller input.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var displayCoordinator: DisplayCoordinator
    private var presentation: ComposePresentation? = null

    /** Shared swap-transition state driven from Display Setup's "Swap Views". */
    private val swapTransition = DsTransitionState()

    // Activity-side orientation state the presentation window observes for
    // its rotation-mismatch correction.
    private var activityOrientation by mutableIntStateOf(Configuration.ORIENTATION_PORTRAIT)
    private var mainDisplayRotation by mutableIntStateOf(Surface.ROTATION_0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Restore the rotation lock (old RoomSelectionActivity.onCreate).
        if (vm.settings.rotationLocked.value) {
            requestedOrientation = vm.settings.lockedOrientation.value
                .takeIf { it != -1 } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        updateOrientationState()

        vm.onSwapViewsRequested = { runAnimatedSwap() }
        vm.onRotationLockToggle = { toggleRotationLock() }

        onBackPressedDispatcher.addCallback(this) {
            when (vm.screen.value) {
                is AppScreen.RoomSelection ->
                    if (!vm.roomSelection.handleBack()) moveTaskToBack(true)
                is AppScreen.OnlineRoomSelection -> vm.onlineRoomSelection.goBack()
                is AppScreen.Chat -> vm.chat?.handleBack()
            }
        }

        displayCoordinator = DisplayCoordinator(this)
        displayCoordinator.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    displayCoordinator.secondaryDisplay.collect { display ->
                        vm.setDualScreenActive(display != null)
                        syncSecondaryPresentation(display)
                    }
                } finally {
                    dismissSecondaryPresentation()
                }
            }
        }

        requestBlePermissions()

        setContent {
            val secondaryDisplay by displayCoordinator.secondaryDisplay.collectAsState()
            AppRoot(
                vm = vm,
                swap = swapTransition,
                secondaryDisplayActive = secondaryDisplay != null,
                onSwitchViews = { onSwitchViewsClicked() }
            )
        }
    }

    override fun onDestroy() {
        dismissSecondaryPresentation()
        displayCoordinator.stop()
        if (vm.onSwapViewsRequested != null) vm.onSwapViewsRequested = null
        if (vm.onRotationLockToggle != null) vm.onRotationLockToggle = null
        super.onDestroy()
    }

    /** Shows or hides the secondary-display window when a display is connected. */
    private fun syncSecondaryPresentation(display: Display?) {
        val current = presentation
        if (display == null) {
            dismissSecondaryPresentation()
            return
        }
        if (current != null && current.display.displayId == display.displayId) return

        dismissSecondaryPresentation()
        presentation = ComposePresentation(this, display) {
            PresentationRoot()
        }.also { pres ->
            pres.setOnDismissListener {
                if (presentation === pres) presentation = null
            }
            pres.show()
        }
    }

    private fun dismissSecondaryPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationState()
    }

    private fun updateOrientationState() {
        activityOrientation = resources.configuration.orientation
        @Suppress("DEPRECATION")
        mainDisplayRotation = windowManager.defaultDisplay.rotation
    }

    // =====================================================================
    // Presentation content
    // =====================================================================

    /**
     * The secondary display always shows the DS screen the phone window is
     * NOT showing, rotated when the presentation display's orientation
     * doesn't match the activity's (port of the old Presentation classes'
     * contentRotation correction).
     */
    @Composable
    private fun PresentationRoot() {
        val viewsSwapped by vm.settings.viewsSwapped.collectAsState()
        // Recompute rotation whenever the secondary display changes.
        val changeTick by displayCoordinator.secondaryDisplayChangeTick.collectAsState()

        val presLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val actLandscape = activityOrientation == Configuration.ORIENTATION_LANDSCAPE
        val rotation = remember(changeTick, presLandscape, actLandscape, mainDisplayRotation) {
            if (actLandscape != presLandscape) {
                val degrees = mainDisplayRotation * 90
                if (degrees == 0 && actLandscape) 90 else degrees
            } else 0
        }

        Box(Modifier.fillMaxSize().background(DsColors.black)) {
            DsScreenPane(
                vm = vm,
                swap = swapTransition,
                isTop = viewsSwapped,
                modifier = Modifier.fillMaxSize(),
                contentRotation = rotation,
                isMainDisplayWindow = false,
            )
        }
    }

    // =====================================================================
    // View swap + rotation lock (activity-level hooks)
    // =====================================================================

    /** Landscape SWITCH button: instant flip, no transition (old btnSwitchViews). */
    private fun onSwitchViewsClicked() {
        if (vm.screen.value is AppScreen.Chat) {
            vm.landscapeSwapped = !vm.landscapeSwapped
        } else {
            vm.settings.setViewsSwapped(!vm.settings.viewsSwapped.value)
        }
    }

    /** Display Setup "Swap Views": animated DS swap on both windows. */
    private fun runAnimatedSwap() {
        if (swapTransition.isTransitioning) return
        // vm.uiScope provides the MonotonicFrameClock the Animatables need.
        vm.uiScope.launch {
            swapTransition.run(exitDirection = 1f) {
                vm.settings.setViewsSwapped(!vm.settings.viewsSwapped.value)
            }
        }
    }

    private fun toggleRotationLock() {
        val settings = vm.settings
        val locking = !settings.rotationLocked.value
        if (locking) {
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val orientation = when (rotation) {
                Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            requestedOrientation = orientation
            settings.setRotationLock(true, orientation)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            settings.setRotationLock(false)
        }
    }

    // =====================================================================
    // Input: single entry point for keys/controllers
    // =====================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (vm.dispatchGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // =====================================================================
    // BLE permissions
    // =====================================================================

    private fun requestBlePermissions() {
        val needed = RoomSelectionController.BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, needed.toTypedArray(), BLE_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLE_PERMISSION_REQUEST &&
            vm.screen.value is AppScreen.RoomSelection
        ) {
            vm.roomSelection.startScanning()
        }
    }

    companion object {
        private const val BLE_PERMISSION_REQUEST = 1001
    }
}
