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

class RoomSelectionPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private val activity: Activity? = outerContext as? Activity
    var onBackPressedCallback: (() -> Unit)? = null

    lateinit var roomRowA: View  private set
    lateinit var roomRowB: View  private set
    lateinit var roomRowC: View  private set
    lateinit var roomRowD: View  private set
    lateinit var btnOptions: View private set
    lateinit var btnJoin: View   private set
    lateinit var roomSelectionContent: View private set
    lateinit var optionsContent: View private set
    lateinit var btnName: View   private set
    lateinit var btnColor: View  private set
    lateinit var btnBack: View   private set
    lateinit var nameInputContent: View private set
    lateinit var nameInputBoxes: NameInputBoxes private set
    lateinit var nameKeyboard: SoftKeyboardView private set
    lateinit var btnNameQuit: View  private set
    lateinit var btnNameConfirm: View private set
    lateinit var btnKbCancel: View    private set
    lateinit var btnKbConfirm: View   private set
    lateinit var nameKbLatin: View    private set
    lateinit var nameKbAccented: View private set
    lateinit var nameKbKatakana: View private set
    lateinit var nameKbSymbols: View  private set
    lateinit var nameKbEmoji: View    private set
    lateinit var colorContent: View       private set
    lateinit var colorGrid: ColorGridView private set
    lateinit var colorPreviewSwatch: View private set
    lateinit var btnColorCancel: View     private set
    lateinit var btnColorConfirm: View    private set
    lateinit var creditsContent: View     private set
    lateinit var btnCredits: View         private set
    lateinit var btnCreditsBack: View     private set
    lateinit var btnDisplaySetup: View     private set
    lateinit var displaySetupContent: View private set
    lateinit var btnSwapViews: View       private set
    lateinit var btnLockRotation: View   private set
    lateinit var cornerHighlightDisplaySetup: View private set
    lateinit var btnDisplaySetupBack: View private set
    lateinit var btnExportChat: View          private set
    lateinit var exportChatContent: View      private set
    lateinit var exportConfirmText: View      private set
    lateinit var exportPreviewImage: android.widget.ImageView private set
    lateinit var exportHintText: View         private set
    lateinit var btnExportScrollDown: View    private set
    lateinit var btnExportScrollUp: View      private set
    lateinit var exportProgressBar: View      private set
    lateinit var btnExportNo: View            private set
    lateinit var btnExportYes: View           private set
    lateinit var headerViews: List<View>  private set
    lateinit var bottomBarViews: List<View> private set
    lateinit var cornerHighlight: View private set
    lateinit var cornerHighlightOptions: View private set
    lateinit var connectingContent: View private set
    lateinit var connectingAnimation: android.widget.ImageView private set
    lateinit var overlay: View  private set
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
            .inflate(R.layout.presentation_room_selection, null)
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

        roomRowA = view.findViewById(R.id.roomRowA)
        roomRowB = view.findViewById(R.id.roomRowB)
        roomRowC = view.findViewById(R.id.roomRowC)
        roomRowD = view.findViewById(R.id.roomRowD)
        btnOptions = view.findViewById(R.id.btnOptions)
        btnJoin  = view.findViewById(R.id.btnJoin)
        roomSelectionContent = view.findViewById(R.id.roomSelectionContent)
        optionsContent = view.findViewById(R.id.optionsContent)
        btnName  = view.findViewById(R.id.btnName)
        btnColor = view.findViewById(R.id.btnColor)
        btnBack  = view.findViewById(R.id.btnBack)
        nameInputContent = view.findViewById(R.id.nameInputContent)
        nameInputBoxes = view.findViewById(R.id.nameInputBoxes)
        nameKeyboard = view.findViewById(R.id.nameKeyboard)
        btnNameQuit = view.findViewById(R.id.btnNameQuit)
        btnNameConfirm = view.findViewById(R.id.btnNameConfirm)
        btnKbCancel    = view.findViewById(R.id.btnKbCancel)
        btnKbConfirm   = view.findViewById(R.id.btnKbConfirm)
        nameKbLatin    = view.findViewById(R.id.nameKbLatin)
        nameKbAccented = view.findViewById(R.id.nameKbAccented)
        nameKbKatakana = view.findViewById(R.id.nameKbKatakana)
        nameKbSymbols  = view.findViewById(R.id.nameKbSymbols)
        nameKbEmoji    = view.findViewById(R.id.nameKbEmoji)
        colorContent       = view.findViewById(R.id.colorContent)
        colorGrid          = view.findViewById(R.id.colorGrid)
        colorPreviewSwatch = view.findViewById(R.id.colorPreviewSwatch)
        btnColorCancel     = view.findViewById(R.id.btnColorCancel)
        btnColorConfirm    = view.findViewById(R.id.btnColorConfirm)
        creditsContent     = view.findViewById(R.id.creditsContent)
        btnCredits         = view.findViewById(R.id.btnCredits)
        btnCreditsBack     = view.findViewById(R.id.btnCreditsBack)
        btnDisplaySetup    = view.findViewById(R.id.btnDisplaySetup)
        displaySetupContent = view.findViewById(R.id.displaySetupContent)
        btnSwapViews       = view.findViewById(R.id.btnSwapViews)
        btnLockRotation    = view.findViewById(R.id.btnLockRotation)
        cornerHighlightDisplaySetup = view.findViewById(R.id.cornerHighlightDisplaySetup)
        btnDisplaySetupBack = view.findViewById(R.id.btnDisplaySetupBack)
        btnExportChat      = view.findViewById(R.id.btnExportChat)
        exportChatContent  = view.findViewById(R.id.exportChatContent)
        exportConfirmText  = view.findViewById(R.id.exportConfirmText)
        exportPreviewImage = view.findViewById(R.id.exportPreviewImage)
        exportHintText     = view.findViewById(R.id.exportHintText)
        btnExportScrollDown = view.findViewById(R.id.btnExportScrollDown)
        btnExportScrollUp  = view.findViewById(R.id.btnExportScrollUp)
        exportProgressBar  = view.findViewById(R.id.exportProgressBar)
        btnExportNo        = view.findViewById(R.id.btnExportNo)
        btnExportYes       = view.findViewById(R.id.btnExportYes)
        headerViews = listOf(
            view.findViewById(R.id.roomSelHeader),
            view.findViewById(R.id.optionsHeader),
            view.findViewById(R.id.nameInputHeader),
            view.findViewById(R.id.colorHeader),
            view.findViewById(R.id.creditsHeader),
            view.findViewById(R.id.displaySetupHeader),
            view.findViewById(R.id.exportChatHeader),
            view.findViewById(R.id.connectingHeader)
        )
        bottomBarViews = listOf(
            view.findViewById(R.id.roomSelBottomBar),
            view.findViewById(R.id.optionsBottomBar),
            view.findViewById(R.id.nameInputBottomBar),
            view.findViewById(R.id.colorBottomBar),
            view.findViewById(R.id.creditsBottomBar),
            view.findViewById(R.id.displaySetupBottomBar),
            view.findViewById(R.id.exportChatBottomBar),
            view.findViewById(R.id.connectingBottomBar)
        )
        cornerHighlight = view.findViewById(R.id.cornerHighlight)
        cornerHighlightOptions = view.findViewById(R.id.cornerHighlightOptions)
        connectingContent = view.findViewById(R.id.connectingContent)
        connectingAnimation = view.findViewById(R.id.connectingAnimation)
        overlay  = view.findViewById(R.id.presentationOverlay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackPressedCallback?.invoke()
    }
}
