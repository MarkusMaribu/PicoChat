package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * A FrameLayout that measures/layouts children at a fixed 4:3 reference size,
 * then uniformly scales the rendered output to fit the actual allocated view
 * bounds. Touch events are inverse-transformed so child hit-testing remains
 * correct.
 *
 * The reference size is the largest 4:3 area that fits two stacked views
 * within the device's portrait dimensions, keeping all ScaleLayout instances
 * on the same display proportionally identical.
 *
 * [contentRotation] (0, 90, 180, 270) applies a clockwise rotation to the
 * rendered content, useful when the Presentation display hasn't rotated to
 * match the device orientation.
 */
class ScaleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        /**
         * dp-width of the reference device where the layout proportions were
         * designed.  All devices will have their density adjusted so that
         * their dp-width matches this value, keeping element proportions
         * consistent inside every ScaleLayout.
         */
        private const val DESIGN_WIDTH_DP = 393f

        /**
         * Returns the densityDpi that makes dp values resolve to the same
         * fraction of [portraitWidth] on every device.  Use this in
         * [android.app.Activity.attachBaseContext] and in Presentation
         * inflation contexts.
         */
        fun targetDensityDpi(context: Context): Int {
            val metrics = context.applicationContext.resources.displayMetrics
            val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
            return ((portraitW / DESIGN_WIDTH_DP) * 160f).toInt()
        }
    }

    val refW: Int
    val refH: Int
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val bgPaint = Paint()

    var contentRotation: Int = 0
        set(value) {
            val normalized = ((value % 360) + 360) % 360
            if (field == normalized) return
            field = normalized
            recalcScale()
            requestLayout()
            invalidate()
        }

    /** Vertical offset applied to all children in reference-space pixels.
     *  +refH slides content fully down (off-screen), -refH slides fully up. */
    var contentOffsetY: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** When non-zero, a filled rect of this colour is drawn at the 4:3 area
     *  behind the (possibly offset) children — used as a swap-transition backdrop. */
    var swapBackgroundColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Alpha (0f–1f) of an overlay drawn ON TOP of children at the 4:3 area,
     *  using [swapBackgroundColor]. Provides the grey fade-in / fade-out effect. */
    var swapOverlayAlpha: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** When true, children are clipped to the 4:3 reference area so that
     *  per-view translationY animations stay within the content bounds. */
    var clipDuringSwap: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        val metrics = context.applicationContext.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        val portraitH = maxOf(metrics.widthPixels, metrics.heightPixels)
        val s = minOf(portraitW / 4f, portraitH / 6f)
        refW = (s * 4).toInt()
        refH = (s * 3).toInt()
    }

    private fun recalcScale() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val rotated90 = (contentRotation % 180) != 0
        val visW = if (rotated90) refH else refW
        val visH = if (rotated90) refW else refH
        val sx = if (visW > 0) w.toFloat() / visW else 1f
        val sy = if (visH > 0) h.toFloat() / visH else 1f
        scale = minOf(sx, sy)
        offsetX = (w - visW * scale) / 2f
        offsetY = (h - visH * scale) / 2f

        drawMatrix.reset()
        drawMatrix.postTranslate(-refW / 2f, -refH / 2f)
        if (contentRotation != 0) {
            drawMatrix.postRotate(contentRotation.toFloat())
        }
        val visWf = if (rotated90) refH.toFloat() else refW.toFloat()
        val visHf = if (rotated90) refW.toFloat() else refH.toFloat()
        drawMatrix.postTranslate(visWf / 2f, visHf / 2f)
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)

        drawMatrix.invert(inverseMatrix)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val childW = MeasureSpec.makeMeasureSpec(refW, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(refH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childW, childH)
        }
        val rotated90 = (contentRotation % 180) != 0
        val prefW = if (rotated90) refH else refW
        val prefH = if (rotated90) refW else refH
        setMeasuredDimension(
            View.resolveSize(prefW, widthMeasureSpec),
            View.resolveSize(prefH, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcScale()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, refW, refH)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val animating = swapBackgroundColor != 0

        canvas.save()

        if (animating) {
            val r90 = (contentRotation % 180) != 0
            val vw = (if (r90) refH else refW) * scale
            val vh = (if (r90) refW else refH) * scale
            bgPaint.color = swapBackgroundColor
            bgPaint.alpha = 255
            canvas.drawRect(offsetX, offsetY, offsetX + vw, offsetY + vh, bgPaint)
        }

        canvas.concat(drawMatrix)
        if (clipDuringSwap) {
            canvas.clipRect(0f, 0f, refW.toFloat(), refH.toFloat())
        }
        if (contentOffsetY != 0f) {
            if (!clipDuringSwap) canvas.clipRect(0f, 0f, refW.toFloat(), refH.toFloat())
            canvas.translate(0f, contentOffsetY)
        }
        super.dispatchDraw(canvas)
        canvas.restore()

        if (animating && swapOverlayAlpha > 0f) {
            canvas.save()
            val r90 = (contentRotation % 180) != 0
            val vw = (if (r90) refH else refW) * scale
            val vh = (if (r90) refW else refH) * scale
            bgPaint.color = swapBackgroundColor
            bgPaint.alpha = (swapOverlayAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(offsetX, offsetY, offsetX + vw, offsetY + vh, bgPaint)
            canvas.restore()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val transformed = MotionEvent.obtain(ev)
        transformed.transform(inverseMatrix)
        val handled = super.dispatchTouchEvent(transformed)
        transformed.recycle()
        return handled
    }
}
