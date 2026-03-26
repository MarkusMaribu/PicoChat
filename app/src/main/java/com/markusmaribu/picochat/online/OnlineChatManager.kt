package com.markusmaribu.picochat.online

import android.util.Base64
import android.util.Log
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.util.CompressionUtil
import com.markusmaribu.picochat.util.Constants
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class OnlineChatManager(
    private val room: Room,
    private val localUsername: String,
    private val localColorIndex: Int,
    private val localDeviceId: String,
    private val onMessageReceived: (ChatMessage) -> Unit
) {
    data class OnlineUser(val username: String, val colorIndex: Int)

    companion object {
        private const val TAG = "OnlineChatManager"
        private const val EVENT_DRAWING = "drawing"
        private const val EVENT_SYSTEM = "system"
        private const val LATENCY_INTERVAL_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var channel: RealtimeChannel? = null
    private val onlineUsersMap = ConcurrentHashMap<String, Int>()
    private val onlineUserNames = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastLatencyMs: Long = -1

    fun isSubscribed(): Boolean =
        channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    fun getLatencyMs(): Long = lastLatencyMs

    fun start() {
        val client = SupabaseProvider.client
        val ch = client.channel("online-room-${room.letter}") {
            presence { key = localDeviceId }
        }
        channel = ch

        scope.launch {
            ch.broadcastFlow<JsonObject>(EVENT_DRAWING).collect { payload ->
                handleDrawingPayload(payload)
            }
        }

        scope.launch {
            ch.broadcastFlow<JsonObject>(EVENT_SYSTEM).collect { payload ->
                handleSystemPayload(payload)
            }
        }

        scope.launch {
            ch.presenceChangeFlow().collect { action ->
                action.joins.forEach { (key, presence) ->
                    if (key != localDeviceId) {
                        val name = try {
                            presence.state["username"]?.jsonPrimitive?.content
                        } catch (_: Exception) { null } ?: return@forEach
                        val ci = try {
                            presence.state["colorIndex"]?.jsonPrimitive?.int ?: 0
                        } catch (_: Exception) { 0 }
                        onlineUsersMap[key] = ci
                        onlineUserNames[key] = name
                    }
                }
                action.leaves.forEach { (key, _) ->
                    onlineUsersMap.remove(key)
                    onlineUserNames.remove(key)
                }
            }
        }

        scope.launch {
            try {
                ch.subscribe(blockUntilSubscribed = true)
                ch.track(buildJsonObject {
                    put("username", localUsername)
                    put("colorIndex", localColorIndex)
                })
                Log.d(TAG, "Subscribed to online-room-${room.letter}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            while (true) {
                lastLatencyMs = measureLatency()
                kotlinx.coroutines.delay(LATENCY_INTERVAL_MS)
            }
        }
    }

    fun getOnlineUsers(): List<OnlineUser> {
        return onlineUsersMap.entries.mapNotNull { (key, ci) ->
            val name = onlineUserNames[key] ?: return@mapNotNull null
            OnlineUser(name, ci)
        }
    }

    fun stop() {
        onlineUsersMap.clear()
        onlineUserNames.clear()
        val ch = channel
        channel = null
        scope.cancel()
        SupabaseProvider.pendingChannelCleanup = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { ch?.untrack() } catch (_: Exception) {}
            try { ch?.unsubscribe() } catch (_: Exception) {}
            try { ch?.let { SupabaseProvider.client.realtime.removeChannel(it) } } catch (_: Exception) {}
        }
    }

    fun broadcastDrawing(msg: ChatMessage.DrawingMessage) {
        val payload = buildJsonObject {
            put("sid", localDeviceId)
            put("username", msg.username)
            put("rawBits", Base64.encodeToString(msg.rawBits, Base64.NO_WRAP))
            put("colorIndex", msg.colorIndex)
            put("timestamp", msg.timestamp)
            put("hash", msg.hash)
            if (msg.rainbowBits != null) {
                val compressed = CompressionUtil.deflate(msg.rainbowBits)
                put("rainbowBits", Base64.encodeToString(compressed, Base64.NO_WRAP))
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                channel?.broadcast(EVENT_DRAWING, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast drawing", e)
            }
        }
    }

    fun broadcastSystemMessage(text: String, timestamp: Long = System.currentTimeMillis()) {
        val payload = buildJsonObject {
            put("sid", localDeviceId)
            put("text", text)
            put("timestamp", timestamp)
        }
        scope.launch(Dispatchers.IO) {
            try {
                channel?.broadcast(EVENT_SYSTEM, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast system message", e)
            }
        }
    }

    private fun handleDrawingPayload(payload: JsonObject) {
        try {
            val sid = payload["sid"]?.jsonPrimitive?.content
            if (sid == localDeviceId) return
            val senderUsername = payload["username"]?.jsonPrimitive?.content ?: return

            val rawBitsB64 = payload["rawBits"]?.jsonPrimitive?.content ?: return
            val rawBits = Base64.decode(rawBitsB64, Base64.NO_WRAP)
            val colorIndex = payload["colorIndex"]?.jsonPrimitive?.int ?: 0
            val timestamp = payload["timestamp"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
            val hash = payload["hash"]?.jsonPrimitive?.int ?: 0

            val rainbowBits = try {
                val rb64 = payload["rainbowBits"]?.jsonPrimitive?.content
                if (rb64 != null) {
                    val compressed = Base64.decode(rb64, Base64.NO_WRAP)
                    CompressionUtil.inflate(compressed, Constants.DRAWING_BYTES)
                } else null
            } catch (_: Exception) { null }

            val bitmap = com.markusmaribu.picochat.ui.PictoCanvasView.bitmapFromBits(rawBits)
            val msg = ChatMessage.DrawingMessage(
                username = senderUsername,
                bitmap = bitmap,
                rawBits = rawBits,
                rainbowBits = rainbowBits,
                colorIndex = colorIndex,
                timestamp = timestamp,
                hash = hash
            )
            onMessageReceived(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode drawing payload", e)
        }
    }

    private fun handleSystemPayload(payload: JsonObject) {
        try {
            val sid = payload["sid"]?.jsonPrimitive?.content
            if (sid == localDeviceId) return
            val text = payload["text"]?.jsonPrimitive?.content ?: return
            val timestamp = payload["timestamp"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
            val msg = ChatMessage.SystemMessage(text = text, timestamp = timestamp)
            onMessageReceived(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode system payload", e)
        }
    }

    private fun measureLatency(): Long {
        return try {
            val host = java.net.URI(SupabaseProvider.client.supabaseUrl).host
                ?: return -1L
            val socket = java.net.Socket()
            val start = System.currentTimeMillis()
            socket.connect(java.net.InetSocketAddress(host, 443), 5_000)
            val elapsed = System.currentTimeMillis() - start
            socket.close()
            elapsed
        } catch (_: Exception) {
            -1L
        }
    }

}
