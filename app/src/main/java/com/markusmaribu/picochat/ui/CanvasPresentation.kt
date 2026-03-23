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
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.util.clearFocusability

class CanvasPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private val activity: Activity? = outerContext as? Activity
    var onBackPressedCallback: (() -> Unit)? = null

    lateinit var pictoCanvas: PictoCanvasView        private set
    lateinit var softKeyboard: SoftKeyboardView     private set
    lateinit var canvasUsername: TextView            private set
    lateinit var btnClose: View                     private set
    lateinit var btnSend: View                      private set
    lateinit var btnRetrieve: View                  private set
    lateinit var btnClear: View                     private set
    lateinit var btnPencil: View                    private set
    lateinit var btnEraser: View                    private set
    lateinit var btnPenThick: View                  private set
    lateinit var btnPenThin: View                   private set
    lateinit var btnScrollUp: View                  private set
    lateinit var btnScrollDown: View                private set
    lateinit var btnKbLatin: View                   private set
    lateinit var btnKbAccented: View                private set
    lateinit var btnKbKatakana: View                private set
    lateinit var btnKbSymbols: View                 private set
    lateinit var btnKbEmoji: View                   private set
    lateinit var peerNamesContainer: LinearLayout   private set
    lateinit var overlay: View                      private set

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
            .inflate(R.layout.presentation_canvas, null)
        setContentView(view)
        view.clearFocusability()

        val isActivityLandscape = activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isPresLandscape = getContext().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isActivityLandscape != isPresLandscape) {
            val scaleLayout = (view as? ViewGroup)?.getChildAt(0) as? ScaleLayout
            if (scaleLayout != null) {
                @Suppress("DEPRECATION")
                val mainRot = activity?.windowManager?.defaultDisplay?.rotation ?: 0
                val degrees = mainRot * 90
                scaleLayout.contentRotation = if (degrees == 0 && isActivityLandscape) 90 else degrees
            }
        }

        pictoCanvas   = view.findViewById(R.id.pictoCanvas)
        softKeyboard  = view.findViewById(R.id.softKeyboard)
        canvasUsername = view.findViewById(R.id.canvasUsername)
        btnClose      = view.findViewById(R.id.btnClose)
        btnSend       = view.findViewById(R.id.btnSend)
        btnRetrieve   = view.findViewById(R.id.btnRetrieve)
        btnClear      = view.findViewById(R.id.btnClear)
        btnPencil     = view.findViewById(R.id.btnPencil)
        btnEraser     = view.findViewById(R.id.btnEraser)
        btnPenThick   = view.findViewById(R.id.btnPenThick)
        btnPenThin    = view.findViewById(R.id.btnPenThin)
        btnScrollUp   = view.findViewById(R.id.btnScrollUp)
        btnScrollDown = view.findViewById(R.id.btnScrollDown)
        btnKbLatin    = view.findViewById(R.id.btnKbLatin)
        btnKbAccented = view.findViewById(R.id.btnKbAccented)
        btnKbKatakana = view.findViewById(R.id.btnKbKatakana)
        btnKbSymbols  = view.findViewById(R.id.btnKbSymbols)
        btnKbEmoji    = view.findViewById(R.id.btnKbEmoji)
        peerNamesContainer = view.findViewById(R.id.peerNamesContainer)
        overlay       = view.findViewById(R.id.presentationOverlay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackPressedCallback?.invoke()
    }
}
