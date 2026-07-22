package com.markusmaribu.picochat.ui.compose.screens.chat

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.markusmaribu.picochat.R
import com.markusmaribu.picochat.ble.BleAdvertiser
import com.markusmaribu.picochat.ble.BleScanner
import com.markusmaribu.picochat.ble.GattClient
import com.markusmaribu.picochat.ble.GattServer
import com.markusmaribu.picochat.ble.L2capManager
import com.markusmaribu.picochat.mesh.MeshManager
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.ChatRepository
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.online.OnlineChatManager
import com.markusmaribu.picochat.state.MainViewModel
import com.markusmaribu.picochat.ui.compose.canvas.CanvasEngine
import com.markusmaribu.picochat.ui.compose.screens.SignalLevel
import com.markusmaribu.picochat.util.Constants
import com.markusmaribu.picochat.util.SoundManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A connected peer shown in the chat top bar. */
data class PeerEntry(val name: String, val colorIndex: Int)

/**
 * Owns everything network-related for one chat session: the BLE mesh stack
 * (advertiser/scanner/GATT/L2CAP/mesh) or the Supabase online manager, the
 * wake lock, signal-level polling and the peer list. Created when the Chat
 * screen is entered and stopped when it is left (port of
 * ChatActivity.startBle/stopBle and friends).
 */
class ChatSessionController(
    private val app: Application,
    private val vm: MainViewModel,
    val room: Room,
    val isOnline: Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sound = vm.soundManager

    val username: String = vm.settings.username.value
    val colorIndex: Int = vm.settings.colorIndex.value
    private val localDeviceIdHash = Constants.deviceIdHash(vm.deviceId)

    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var gattServer: GattServer? = null
    private var gattClient: GattClient? = null
    private var l2capManager: L2capManager? = null
    private var meshManager: MeshManager? = null
    private var onlineChatManager: OnlineChatManager? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val _signalLevel = MutableStateFlow(SignalLevel.NONE)
    val signalLevel: StateFlow<SignalLevel> = _signalLevel.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerEntry>>(emptyList())
    val peers: StateFlow<List<PeerEntry>> = _peers.asStateFlow()

    var isLeaving = false
        private set

    // BLE peer tracking (enter/leave detection by device-id hash)
    private val knownBlePeerIds = mutableMapOf<Int, String>()
    private var blePeerTrackingInitialized = false
    private var blePeerGraceUntil = 0L
    private val departingDeviceIds = ConcurrentHashMap<Int, String>()

    private var started = false

    /** Plays enter/leave jingles for arriving system messages. */
    private val soundListener: (ChatMessage) -> Unit = { msg ->
        if (msg is ChatMessage.SystemMessage) {
            when {
                msg.text.startsWith("Now entering") ->
                    sound.play(SoundManager.Sound.ENTER_ROOM)
                msg.text.startsWith("Now leaving") && !isLeaving ->
                    sound.play(SoundManager.Sound.LEAVE_ROOM)
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun start() {
        if (started) return
        started = true
        ChatRepository.addListener(soundListener)
        if (isOnline) startOnlineChat() else startBle()

        val enterStringRes =
            if (isOnline) R.string.now_entering_online else R.string.now_entering
        val enterMsg = ChatRepository.postSystemMessage(
            app.getString(enterStringRes, room.circleLetter, username)
        )
        scope.launch {
            delay(3000)
            if (isOnline) {
                onlineChatManager?.broadcastSystemMessage(enterMsg.text, enterMsg.timestamp)
            } else {
                meshManager?.broadcastTextToRoom(
                    username,
                    Constants.SYSTEM_MSG_PREFIX + enterMsg.text,
                    enterMsg.timestamp
                )
            }
        }
    }

    fun stop() {
        ChatRepository.removeListener(soundListener)
        releaseWakeLock()
        onlineChatManager?.stop()
        onlineChatManager = null
        meshManager?.stop()
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScan()
        gattServer?.stop()
        l2capManager?.stopServer()
        scope.cancel()
    }

    /** Old onResume: keep the CPU alive and re-join the online channel. */
    fun onVisible() {
        acquireWakeLock()
        reconnectOnlineChatIfNeeded()
    }

    fun onHidden() {
        releaseWakeLock()
        if (isLeaving && isOnline) {
            onlineChatManager?.stop()
            onlineChatManager = null
        }
    }

    // =====================================================================
    // BLE / online startup (ported nearly verbatim)
    // =====================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBle() {
        val btAdapter =
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null || !btAdapter.isEnabled) return
        val messageStore = ChatRepository.messageStore
        try {
            bleAdvertiser = BleAdvertiser(app).also {
                it.startAdvertising(
                    room, username, messageStore.getLatestHash(), colorIndex, localDeviceIdHash
                )
            }
            gattServer = GattServer(app, this)
            l2capManager = L2capManager(app).also { l2 ->
                l2.setMessageProvider { requestedHash ->
                    val msg = ChatRepository.messageStore.getMessage(requestedHash)
                    if (msg is ChatMessage.DrawingMessage) {
                        L2capManager.DrawingPayload(
                            msg.username, msg.hash, msg.timestamp,
                            msg.rawBits, msg.colorIndex, msg.rainbowBits
                        )
                    } else null
                }
                l2.setOnTextReceived { data -> onTextReceivedViaL2cap(data) }
                l2.startServer { senderName, hash, timestamp, data, colorIdx, rainbowBits ->
                    onDrawingReceived(senderName, hash, timestamp, data, colorIdx, rainbowBits)
                }
                gattServer?.setL2capPsm(l2.getServerPsm())
            }
            gattServer?.start { msg -> addMessageLocal(msg) }

            bleScanner = BleScanner(app).also { scanner ->
                scanner.onPeersEvicted = { scope.launch { updatePeers() } }
                scanner.startScan(lowLatency = true) { }
            }

            gattClient = GattClient(app)
            meshManager = MeshManager(
                context = app,
                messageStore = messageStore,
                bleScanner = bleScanner!!,
                gattClient = gattClient!!,
                l2capManager = l2capManager!!,
                bleAdvertiser = bleAdvertiser!!,
                room = room,
                onMessageReceived = { msg -> addMessageLocal(msg) }
            ).also {
                it.localUsername = username
                it.localColorIndex = colorIndex
                it.localDeviceIdHash = localDeviceIdHash
                it.start()
            }

            scope.launch {
                while (isActive) {
                    updateBleSignal()
                    updatePeers()
                    delay(2000)
                }
            }
        } catch (e: SecurityException) {
            // BLE permissions not granted -- fall back to local-only mode
        } catch (e: Exception) {
            // Device may not support BLE advertising -- local-only mode
        }
    }

    private fun startOnlineChat() {
        onlineChatManager = OnlineChatManager(
            room = room,
            localUsername = username,
            localColorIndex = colorIndex,
            localDeviceId = vm.deviceId,
            onMessageReceived = { msg -> scope.launch { addMessageLocal(msg) } }
        ).also { it.start() }
        scope.launch {
            while (isActive) {
                updateOnlineSignal()
                updatePeers()
                delay(3000)
            }
        }
    }

    private fun reconnectOnlineChatIfNeeded() {
        if (!isOnline) return
        val manager = onlineChatManager ?: return
        if (manager.isSubscribed()) return
        manager.stop()
        onlineChatManager = null
        startOnlineChat()
    }

    // =====================================================================
    // Message plumbing
    // =====================================================================

    fun addMessageLocal(msg: ChatMessage) {
        if (msg is ChatMessage.SystemMessage) {
            when {
                msg.text.startsWith("Now entering") -> {
                    if (!isOnline) {
                        val enteringUser = msg.text.substringAfter(": ", "")
                        if (enteringUser.isNotEmpty()) {
                            departingDeviceIds.entries.removeIf { (id, name) ->
                                if (name == enteringUser) {
                                    bleScanner?.unblockDeviceId(id)
                                    true
                                } else false
                            }
                        }
                        scope.launch { updatePeers() }
                        return
                    }
                    ChatRepository.addMessage(msg)
                    return
                }
                msg.text.startsWith("Now leaving") -> {
                    if (!isOnline) {
                        val leavingUser = msg.text.substringAfter(": ", "")
                        if (leavingUser.isNotEmpty()) {
                            bleScanner?.getPeersInRoom(room)
                                ?.filter { it.username == leavingUser && it.deviceIdHash != 0 }
                                ?.forEach { departingDeviceIds[it.deviceIdHash] = leavingUser }
                            bleScanner?.evictPeersByName(room, leavingUser)
                        }
                        scope.launch { updatePeers() }
                        return
                    }
                    ChatRepository.addMessage(msg)
                    return
                }
            }
        }
        ChatRepository.addMessage(msg)
        if (msg !is ChatMessage.SystemMessage) {
            scope.launch { sound.play(SoundManager.Sound.RECEIVED) }
        }
    }

    fun broadcastMessage(msg: ChatMessage) {
        if (isOnline) {
            if (msg is ChatMessage.DrawingMessage) {
                onlineChatManager?.broadcastDrawing(msg)
            }
        } else {
            meshManager?.onNewLocalMessage(msg)
        }
    }

    /** Posts + broadcasts the leave message and stops advertising. */
    fun beginLeave() {
        if (isLeaving) return
        isLeaving = true
        val leaveStringRes =
            if (isOnline) R.string.now_leaving_online else R.string.now_leaving
        val leaveMsg = ChatRepository.postSystemMessage(
            app.getString(leaveStringRes, room.circleLetter, username)
        )
        if (isOnline) {
            onlineChatManager?.broadcastSystemMessage(leaveMsg.text, leaveMsg.timestamp)
        } else {
            bleAdvertiser?.stopAdvertising()
            meshManager?.broadcastTextToRoom(
                username,
                Constants.SYSTEM_MSG_PREFIX + leaveMsg.text,
                leaveMsg.timestamp
            )
        }
    }

    private fun onTextReceivedViaL2cap(data: ByteArray) {
        try {
            val raw = String(data, Charsets.UTF_8)
            val parts = raw.split('\u0000', limit = 4)
            if (parts.size >= 2) {
                val senderUsername = parts[0]
                var colorIdx = 0
                val timestamp: Long
                val messageText: String

                if (parts.size >= 4) {
                    colorIdx = parts[1].toIntOrNull()?.coerceIn(0, 15) ?: 0
                    timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                    messageText = parts[3]
                } else if (parts.size == 3) {
                    timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                    messageText = parts[2]
                } else {
                    timestamp = System.currentTimeMillis()
                    messageText = parts[1]
                }

                val msg = if (messageText.startsWith(Constants.SYSTEM_MSG_PREFIX)) {
                    ChatMessage.SystemMessage(
                        text = messageText.removePrefix(Constants.SYSTEM_MSG_PREFIX),
                        timestamp = timestamp
                    )
                } else {
                    ChatMessage.TextMessage(
                        username = senderUsername,
                        text = messageText,
                        colorIndex = colorIdx,
                        timestamp = timestamp
                    )
                }
                addMessageLocal(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing L2CAP text", e)
        }
    }

    private fun onDrawingReceived(
        senderName: String,
        hash: Int,
        timestamp: Long,
        data: ByteArray,
        colorIdx: Int = 0,
        rainbowBits: ByteArray? = null
    ) {
        if (ChatRepository.messageStore.hasMessage(hash)) return

        val bitmap = CanvasEngine.bitmapFromBits(data)
        val msg = ChatMessage.DrawingMessage(
            username = senderName,
            bitmap = bitmap,
            rawBits = data,
            rainbowBits = rainbowBits,
            colorIndex = colorIdx,
            timestamp = timestamp,
            hash = hash
        )
        addMessageLocal(msg)
        meshManager?.scheduleHashUpdate()
    }

    // =====================================================================
    // Signal + peers
    // =====================================================================

    private fun updateBleSignal() {
        val rssi = bleScanner?.getBestRssiInRoom(room)
        _signalLevel.value = when {
            rssi == null -> SignalLevel.NONE
            rssi >= -60 -> SignalLevel.GOOD
            rssi >= -80 -> SignalLevel.MODERATE
            else -> SignalLevel.WEAK
        }
    }

    private fun updateOnlineSignal() {
        val manager = onlineChatManager ?: return
        val latency = manager.getLatencyMs()
        _signalLevel.value = when {
            !manager.isSubscribed() -> SignalLevel.NONE
            latency < 0 -> SignalLevel.GOOD
            latency < 600 -> if (latency < 300) SignalLevel.GOOD else SignalLevel.MODERATE
            else -> SignalLevel.WEAK
        }
    }

    private fun updatePeers() {
        val peers: List<PeerEntry> = if (isOnline) {
            onlineChatManager?.getOnlineUsers()
                ?.map { PeerEntry(it.username, it.colorIndex) } ?: emptyList()
        } else {
            val now = System.currentTimeMillis()

            val deduped = bleScanner?.getPeersInRoom(room)
                ?.filter { it.deviceIdHash != 0 && it.deviceIdHash != localDeviceIdHash }
                ?.filter { !departingDeviceIds.containsKey(it.deviceIdHash) }
                ?.groupBy { it.deviceIdHash }
                ?.map { (_, dupes) -> dupes.maxByOrNull { it.lastSeen }!! }
                ?: emptyList()

            val currentMap = deduped.associate { it.deviceIdHash to it.username }
            if (!blePeerTrackingInitialized) {
                knownBlePeerIds.putAll(currentMap)
                blePeerTrackingInitialized = true
                blePeerGraceUntil = now + 8_000
            } else if (now < blePeerGraceUntil) {
                knownBlePeerIds.clear()
                knownBlePeerIds.putAll(currentMap)
            } else {
                val newIds = currentMap.keys - knownBlePeerIds.keys
                for (id in newIds) {
                    val name = currentMap[id] ?: continue
                    ChatRepository.postSystemMessage(
                        app.getString(R.string.now_entering, room.circleLetter, name)
                    )
                }

                val goneIds = knownBlePeerIds.keys - currentMap.keys
                for (id in goneIds) {
                    val name = knownBlePeerIds[id] ?: continue
                    ChatRepository.postSystemMessage(
                        app.getString(R.string.now_leaving, room.circleLetter, name)
                    )
                }

                knownBlePeerIds.clear()
                knownBlePeerIds.putAll(currentMap)
            }

            deduped.map { PeerEntry(it.username, it.colorIndex) }
        }
        _peers.value = peers.sortedBy { it.name }
    }

    // =====================================================================
    // WakeLock
    // =====================================================================

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoChat::ChatWakeLock")
        }
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    companion object {
        private const val TAG = "ChatSessionController"
    }
}
