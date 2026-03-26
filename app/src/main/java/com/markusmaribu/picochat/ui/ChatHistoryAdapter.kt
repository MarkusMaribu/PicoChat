package com.markusmaribu.picochat.ui

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.ThemeColors

class ChatHistoryAdapter : RecyclerView.Adapter<ChatHistoryAdapter.MessageViewHolder>() {

    companion object {
        private const val TYPE_BANNER = 0
        private const val TYPE_USER_MSG = 1
        private const val TYPE_SYSTEM_MSG = 2
    }

    var localColorIndex: Int = 0

    private val messages = mutableListOf<ChatMessage>()

    fun getMessages(): List<ChatMessage> = messages

    fun addMessage(message: ChatMessage) {
        val idx = messages.indexOfLast { it.timestamp <= message.timestamp } + 1
        messages.add(idx, message)
        notifyItemInserted(idx + 1) // +1 for banner at position 0
    }

    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = messages.size + 1

    override fun getItemViewType(position: Int): Int {
        if (position == 0) return TYPE_BANNER
        val msg = messages[position - 1]
        return if (msg is ChatMessage.SystemMessage) TYPE_SYSTEM_MSG else TYPE_USER_MSG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        if (position == 0) {
            holder.bindBanner()
        } else {
            holder.bind(messages[position - 1])
        }
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val msgBanner: TextView = itemView.findViewById(R.id.msgBanner)
        private val msgFrame: FrameLayout = itemView.findViewById(R.id.msgFrame)
        private val username: TextView = itemView.findViewById(R.id.msgUsername)
        private val textContent: TextView = itemView.findViewById(R.id.msgText)
        private val drawingContent: ImageView = itemView.findViewById(R.id.msgDrawing)
        private val systemText: TextView = itemView.findViewById(R.id.msgSystem)

        init {
            drawingContent.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun bindBanner() {
            msgBanner.visibility = View.VISIBLE
            msgBanner.setText(R.string.title_pictochat)
            msgFrame.visibility = View.GONE
            systemText.visibility = View.GONE
        }

        fun bind(message: ChatMessage) {
            msgBanner.visibility = View.GONE
            when (message) {
                is ChatMessage.SystemMessage -> {
                    msgFrame.visibility = View.GONE
                    systemText.visibility = View.VISIBLE
                    val text = message.text
                    val colonIdx = text.indexOf(": ")
                    if (colonIdx >= 0) {
                        val spannable = SpannableString(text)
                        spannable.setSpan(
                            ForegroundColorSpan(Color.WHITE),
                            colonIdx + 2, text.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        systemText.text = spannable
                    } else {
                        systemText.text = text
                    }
                }
                is ChatMessage.TextMessage -> {
                    msgFrame.visibility = View.VISIBLE
                    systemText.visibility = View.GONE
                    username.visibility = View.VISIBLE
                    username.text = message.username
                    applyMessageColor(message.colorIndex)
                    scaleNametag()
                    drawingContent.visibility = View.GONE
                    textContent.visibility = View.VISIBLE
                    textContent.text = message.text
                }
                is ChatMessage.DrawingMessage -> {
                    msgFrame.visibility = View.VISIBLE
                    systemText.visibility = View.GONE
                    username.visibility = View.VISIBLE
                    username.text = message.username
                    applyMessageColor(message.colorIndex)
                    scaleNametag()
                    textContent.visibility = View.GONE
                    drawingContent.visibility = View.VISIBLE
                    val drawable = if (message.rainbowBits != null) {
                        RainbowBitmapDrawable(message.bitmap, message.rainbowBits)
                    } else {
                        BitmapDrawable(itemView.resources, message.bitmap).apply {
                            isFilterBitmap = false
                            setAntiAlias(false)
                        }
                    }
                    drawingContent.setImageDrawable(drawable)
                }
            }
        }

        private fun scaleNametag() {
            msgFrame.doOnLayout { frame ->
                val contentH = frame.height - frame.paddingTop - frame.paddingBottom
                val scale = contentH.toFloat() / Constants.CANVAS_H
                username.setTextSize(TypedValue.COMPLEX_UNIT_PX, PictoCanvasView.TEXT_SIZE * scale)
                val hPad = (PictoCanvasView.NAMETAG_H_PADDING * scale).toInt()
                val vPad = (1f * scale).toInt().coerceAtLeast(1)
                username.setPadding(hPad, vPad, hPad, vPad)
                (username.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = -frame.paddingTop
                    lp.marginStart = -frame.paddingStart
                    username.layoutParams = lp
                }
            }
        }

        private fun applyMessageColor(colorIdx: Int) {
            val color = ThemeColors.PALETTE[colorIdx.coerceIn(0, ThemeColors.PALETTE.size - 1)]
            val density = itemView.resources.displayMetrics.density

            val frameBg = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke((2 * density).toInt(), color)
                cornerRadius = 6 * density
            }
            msgFrame.background = frameBg

            val nametagBg = GradientDrawable().apply {
                setColor(ThemeColors.brighten(color, 0.85f))
                setStroke((2 * density).toInt(), color)
                cornerRadii = floatArrayOf(
                    4 * density, 4 * density, 0f, 0f,
                    4 * density, 4 * density, 0f, 0f
                )
            }
            username.background = nametagBg
            username.setTextColor(color)
        }
    }
}
