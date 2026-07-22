package com.markusmaribu.picochat.ui.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single [DisplayManager] listener for the whole app (replaces the
 * triplicated listener code in the old activities). Exposes the first
 * non-default display as a [StateFlow] plus a change tick so the
 * presentation can re-evaluate its rotation correction when the secondary
 * display reconfigures.
 */
class DisplayCoordinator(context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _secondaryDisplay = MutableStateFlow<Display?>(null)
    val secondaryDisplay: StateFlow<Display?> = _secondaryDisplay.asStateFlow()

    /** Bumped whenever the secondary display reports a change (e.g. rotation). */
    private val _secondaryDisplayChangeTick = MutableStateFlow(0)
    val secondaryDisplayChangeTick: StateFlow<Int> = _secondaryDisplayChangeTick.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY &&
                displayId == _secondaryDisplay.value?.displayId
            ) {
                _secondaryDisplayChangeTick.value++
            }
            refresh()
        }
    }

    fun start() {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(listener)
    }

    private fun refresh() {
        val current = _secondaryDisplay.value
        val secondary = displayManager.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        if (secondary?.displayId != current?.displayId) {
            _secondaryDisplay.value = secondary
        }
    }
}
