package com.markusmaribu.picochat.model

import android.graphics.Bitmap

sealed class ChatMessage {
    abstract val username: String
    abstract val timestamp: Long
    abstract val hash: Int
    abstract val colorIndex: Int

    data class TextMessage(
        override val username: String,
        val text: String,
        override val colorIndex: Int = 0,
        override val timestamp: Long = System.currentTimeMillis(),
        override val hash: Int = computeHash(username, text + timestamp)
    ) : ChatMessage()

    data class DrawingMessage(
        override val username: String,
        val bitmap: Bitmap,
        val rawBits: ByteArray,
        override val colorIndex: Int = 0,
        override val timestamp: Long = System.currentTimeMillis(),
        override val hash: Int = computeHash(username, rawBits, timestamp)
    ) : ChatMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DrawingMessage) return false
            return hash == other.hash && username == other.username
        }

        override fun hashCode(): Int = hash
    }

    data class SystemMessage(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val hash: Int = computeHash("__system__", text + timestamp)
    ) : ChatMessage() {
        override val username: String get() = ""
        override val colorIndex: Int get() = 0
    }

    companion object {
        private fun computeHash(username: String, text: String): Int {
            val crc = java.util.zip.CRC32()
            crc.update(username.toByteArray())
            crc.update(text.toByteArray())
            return crc.value.toInt()
        }

        private fun computeHash(username: String, data: ByteArray): Int {
            val crc = java.util.zip.CRC32()
            crc.update(username.toByteArray())
            crc.update(data)
            return crc.value.toInt()
        }

        private fun computeHash(username: String, data: ByteArray, timestamp: Long): Int {
            val crc = java.util.zip.CRC32()
            crc.update(username.toByteArray())
            crc.update(data)
            crc.update(timestamp.toString().toByteArray())
            return crc.value.toInt()
        }
    }
}
