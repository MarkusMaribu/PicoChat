package com.markusmaribu.picochat.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.util.clearFocusability

class OnlineRoomSelectionPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private val activity: Activity? = outerContext as? Activity
    var onBackPressedCallback: (() -> Unit)? = null

    lateinit var roomRowA: View              private set
    lateinit var roomRowB: View              private set
    lateinit var roomRowC: View              private set
    lateinit var roomRowD: View              private set
    lateinit var btnBack: View               private set
    lateinit var btnJoin: View               private set
    lateinit var roomSelectionContent: View   private set
    lateinit var roomSelHeader: View          private set
    lateinit var roomSelBottomBar: View       private set
    lateinit var cornerHighlight: View        private set
    lateinit var overlay: View                private set
    var scaleLayout: ScaleLayout? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        setCancelable(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { onBackPressedCallback?.invoke() }
        }

        val config = Configuration(getContext().resources.configuration)
        config.densityDpi = ScaleLayout.targetDensityDpi(activity ?: context)
        val inflationContext = ContextThemeWrapper(
            getContext().createConfigurationContext(config), R.style.Theme_PicoChat
        )

        val view = LayoutInflater.from(inflationContext)
            .inflate(R.layout.presentation_online_room_selection, null)
        setContentView(view)
        view.clearFocusability()

        val isActivityLandscape = activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isPresLandscape = getContext().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        scaleLayout = (view as? ViewGroup)?.getChildAt(0) as? ScaleLayout
        if (isActivityLandscape != isPresLandscape) {
            scaleLayout?.let { sl ->
                @Suppress("DEPRECATION")
                val mainRot = activity?.windowManager?.defaultDisplay?.rotation ?: 0
                val degrees = mainRot * 90
                sl.contentRotation = if (degrees == 0 && isActivityLandscape) 90 else degrees
            }
        }

        roomRowA             = view.findViewById(R.id.roomRowA)
        roomRowB             = view.findViewById(R.id.roomRowB)
        roomRowC             = view.findViewById(R.id.roomRowC)
        roomRowD             = view.findViewById(R.id.roomRowD)
        btnBack              = view.findViewById(R.id.btnBack)
        btnJoin              = view.findViewById(R.id.btnJoin)
        roomSelectionContent = view.findViewById(R.id.roomSelectionContent)
        roomSelHeader        = view.findViewById(R.id.roomSelHeader)
        roomSelBottomBar     = view.findViewById(R.id.roomSelBottomBar)
        cornerHighlight      = view.findViewById(R.id.cornerHighlight)
        overlay              = view.findViewById(R.id.presentationOverlay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackPressedCallback?.invoke()
    }
}
