package com.markusmaribu.picochat.model

import com.markusmaribu.picochat.mesh.MessageStore

object ChatRepository {

    val messageStore = MessageStore()

    private val listeners = mutableListOf<(ChatMessage) -> Unit>()
    private val tsLock = Any()
    private var maxTimestamp = 0L

    fun addMessage(msg: ChatMessage) {
        synchronized(tsLock) {
            if (msg.timestamp > maxTimestamp) maxTimestamp = msg.timestamp
        }
        messageStore.addMessage(msg)
        listeners.toList().forEach { it(msg) }
    }

    fun getAllMessages(): List<ChatMessage> = messageStore.getAllMessages()

    fun addListener(listener: (ChatMessage) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ChatMessage) -> Unit) {
        listeners.remove(listener)
    }

    fun nextTimestamp(): Long {
        synchronized(tsLock) {
            val ts = maxOf(System.currentTimeMillis(), maxTimestamp + 1)
            maxTimestamp = ts
            return ts
        }
    }

    fun postSystemMessage(text: String): ChatMessage.SystemMessage {
        val ts = nextTimestamp()
        val msg = ChatMessage.SystemMessage(text = text, timestamp = ts)
        messageStore.addMessage(msg)
        listeners.toList().forEach { it(msg) }
        return msg
    }
}
