package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.markusmaribu.picochat.R

class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var outlineColor: Int = 0xFF303030.toInt()
    private var outlineWidth: Float = 4f

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.OutlinedTextView)
            outlineColor = a.getColor(R.styleable.OutlinedTextView_outlineColor, outlineColor)
            outlineWidth = a.getDimension(R.styleable.OutlinedTextView_outlineWidth, outlineWidth)
            a.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textColor = currentTextColor

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.strokeJoin = Paint.Join.ROUND
        setTextColor(outlineColor)
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        setTextColor(textColor)
        super.onDraw(canvas)
    }
}
