package com.markusmaribu.picochat.ui.host

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Generic secondary-display window hosting a [ComposeView] (replaces the four
 * layout-specific Presentation subclasses). Presentation windows don't get
 * view-tree owners automatically, so this class provides its own lifecycle
 * and saved-state registry and borrows the activity's ViewModel store.
 *
 * The window stays focusable on purpose: on dual-screen handhelds (AYN Thor)
 * the system can move key routing to the secondary display, and if this
 * window refused focus the shell running behind it would receive all gamepad
 * input. Events that land here are forwarded to the shared input pipeline
 * via [onKeyEvent] / [onMotionEvent].
 */
class ComposePresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val onKeyEvent: (KeyEvent) -> Boolean = { false },
    private val onMotionEvent: (MotionEvent) -> Boolean = { false },
    private val content: @Composable () -> Unit
) : Presentation(activity, display), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ComposePresentation)
            setViewTreeSavedStateRegistryOwner(this@ComposePresentation)
            setViewTreeViewModelStoreOwner(activity)
            setContent(content)
        }
        setContentView(composeView)
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
            it.setViewTreeViewModelStoreOwner(activity)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onStop()
    }
}
