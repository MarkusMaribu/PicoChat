package com.markusmaribu.picochat.mesh

import com.markusmaribu.picochat.model.ChatMessage
import java.util.concurrent.ConcurrentHashMap

class MessageStore {

    private val messages = ConcurrentHashMap<Int, ChatMessage>()

    fun hasMessage(hash: Int): Boolean = messages.containsKey(hash)

    fun addMessage(msg: ChatMessage): Int {
        messages[msg.hash] = msg
        return msg.hash
    }

    fun getMessage(hash: Int): ChatMessage? = messages[hash]

    fun getHashes(): Set<Int> = messages.keys.toSet()

    fun getLatestHash(): Int {
        return messages.keys.maxOrNull() ?: 0
    }

    fun getAllMessages(): List<ChatMessage> {
        return messages.values.sortedBy { it.timestamp }
    }

    fun size(): Int = messages.size
}
