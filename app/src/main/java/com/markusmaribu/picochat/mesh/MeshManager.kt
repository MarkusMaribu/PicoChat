package com.markusmaribu.picochat.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.markusmaribu.picochat.ble.BleAdvertiser
import com.markusmaribu.picochat.ble.BleScanner
import com.markusmaribu.picochat.ble.GattClient
import com.markusmaribu.picochat.ble.L2capManager
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.model.PeerUser
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.ui.PictoCanvasView
import com.markusmaribu.picochat.util.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@android.annotation.SuppressLint("MissingPermission")
class MeshManager(
    private val context: Context,
    private val messageStore: MessageStore,
    private val bleScanner: BleScanner,
    private val gattClient: GattClient,
    private val l2capManager: L2capManager,
    private val bleAdvertiser: BleAdvertiser,
    private val room: Room,
    private val onMessageReceived: (ChatMessage) -> Unit
) {

    companion object {
        private const val TAG = "MeshManager"
        private const val PULL_CHECK_INTERVAL_MS = 2_500L
        private const val PSM_PREFETCH_INTERVAL_MS = 4_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRIES = 2
    }

    // Four threads allow parallel L2CAP sends to different peers while
    // GATT operations are serialized via gattLock. CallerRunsPolicy
    // applies backpressure instead of silently dropping messages.
    private val bleExecutor = ThreadPoolExecutor(
        4, 4, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(128),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val gattLock = ReentrantLock()

    private val handler = Handler(Looper.getMainLooper())
    private val psmCache = ConcurrentHashMap<String, Int>()
    private val inFlightPulls = ConcurrentHashMap<Int, Long>()
    @Volatile
    private var running = false

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    var localUsername: String = "Player"
    var localColorIndex: Int = 0
    var localDeviceIdHash: Int = 0

    private val hashDebounceRunnable = Runnable {
        if (running) {
            bleAdvertiser.updateHash(room, localUsername, messageStore.getLatestHash(), localColorIndex, localDeviceIdHash)
        }
    }

    private val pullCheckRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            checkForMissingMessages()
            handler.postDelayed(this, PULL_CHECK_INTERVAL_MS)
        }
    }

    private val psmPrefetchRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            prefetchPsms()
            handler.postDelayed(this, PSM_PREFETCH_INTERVAL_MS)
        }
    }

    fun start() {
        running = true
        handler.postDelayed(pullCheckRunnable, PULL_CHECK_INTERVAL_MS)
        handler.postDelayed(psmPrefetchRunnable, 2_000L)
        Log.d(TAG, "Mesh manager started for room ${room.letter}")
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        bleExecutor.shutdownNow()
        Log.d(TAG, "Mesh manager stopped")
    }

    fun broadcastTextToRoom(senderUsername: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        if (!running) return
        val msg = ChatMessage.TextMessage(
            username = senderUsername,
            text = text,
            colorIndex = localColorIndex,
            timestamp = timestamp
        )
        val peers = bleScanner.getPeersInRoom(room)
        for (peer in peers) {
            submitBleWork { sendToPeer(peer, msg) }
        }
    }

    fun onNewLocalMessage(msg: ChatMessage) {
        messageStore.addMessage(msg)
        localUsername = msg.username
        scheduleHashUpdate()

        val peers = bleScanner.getPeersInRoom(room)
        for (peer in peers) {
            submitBleWork { sendToPeer(peer, msg) }
        }
    }

    fun scheduleHashUpdate() {
        handler.removeCallbacks(hashDebounceRunnable)
        handler.postDelayed(hashDebounceRunnable, Constants.ADVERTISE_DEBOUNCE_MS)
    }

    private fun submitBleWork(block: () -> Unit) {
        if (!running) return
        try {
            bleExecutor.submit {
                if (!running) return@submit
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "BLE operation failed", e)
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Serialize GATT operations (readPsm, sendTextMessage) so two
     * executor threads never attempt GATT connects at the same time.
     * If the lock is already held, the caller gives up immediately
     * instead of also blocking.
     */
    private fun <T> doGattSerialized(block: () -> T): T? {
        if (!gattLock.tryLock(2_000, TimeUnit.MILLISECONDS)) return null
        try {
            val result = block()
            try {
                Thread.sleep(Constants.BLE_INTER_OP_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return result
        } finally {
            gattLock.unlock()
        }
    }

    private fun sendToPeer(peer: PeerUser, msg: ChatMessage) {
        val device = adapter?.getRemoteDevice(peer.macAddress) ?: return

        when (msg) {
            is ChatMessage.TextMessage -> {
                val wireText = "${msg.timestamp}\u0000${msg.text}"
                val psm = psmCache[peer.macAddress]
                if (psm != null && psm > 0) {
                    val payload = "${msg.username}\u0000${msg.colorIndex}\u0000$wireText".toByteArray(Charsets.UTF_8)
                    if (l2capManager.sendTextData(device, psm, payload)) return
                }
                val sent = doGattSerialized {
                    gattClient.sendTextMessage(device, msg.username, wireText, msg.colorIndex)
                }
                if (sent != true) {
                    scheduleRetrySend(peer, msg)
                }
            }
            is ChatMessage.DrawingMessage -> {
                val psm = psmCache[peer.macAddress]
                if (psm == null || psm <= 0) {
                    val fresh = doGattSerialized { gattClient.readPsm(device) }
                    if (fresh != null && fresh > 0) {
                        psmCache[peer.macAddress] = fresh
                        val sent = l2capManager.sendDrawing(device, fresh, msg.hash, msg.timestamp, msg.username, msg.rawBits, msg.colorIndex, msg.rainbowBits)
                        if (!sent) {
                            psmCache.remove(peer.macAddress)
                            scheduleRetrySend(peer, msg)
                        }
                    } else {
                        scheduleRetrySend(peer, msg)
                    }
                    return
                }
                val sent = l2capManager.sendDrawing(device, psm, msg.hash, msg.timestamp, msg.username, msg.rawBits, msg.colorIndex, msg.rainbowBits)
                if (!sent) {
                    psmCache.remove(peer.macAddress)
                    scheduleRetrySend(peer, msg)
                }
            }
            is ChatMessage.SystemMessage -> { }
        }
    }

    private data class RetryEntry(val peer: PeerUser, val msg: ChatMessage, val attempt: Int)

    private val pendingRetries = ConcurrentHashMap<String, RetryEntry>()

    private fun scheduleRetrySend(peer: PeerUser, msg: ChatMessage, attempt: Int = 1) {
        if (attempt > MAX_RETRIES) return
        val key = "${peer.macAddress}:${msg.hash}"
        if (pendingRetries.containsKey(key)) return
        pendingRetries[key] = RetryEntry(peer, msg, attempt)
        val delay = RETRY_DELAY_MS * attempt
        handler.postDelayed({
            val retry = pendingRetries.remove(key) ?: return@postDelayed
            if (!running) return@postDelayed
            submitBleWork { retrySendToPeer(retry.peer, retry.msg, retry.attempt) }
        }, delay)
    }

    private fun retrySendToPeer(peer: PeerUser, msg: ChatMessage, attempt: Int) {
        val device = adapter?.getRemoteDevice(peer.macAddress) ?: return

        val success: Boolean = when (msg) {
            is ChatMessage.DrawingMessage -> {
                val psm = doGattSerialized { gattClient.readPsm(device) }
                if (psm != null && psm > 0) {
                    psmCache[peer.macAddress] = psm
                    l2capManager.sendDrawing(device, psm, msg.hash, msg.timestamp, msg.username, msg.rawBits, msg.colorIndex, msg.rainbowBits)
                } else false
            }
            is ChatMessage.TextMessage -> {
                val wireText = "${msg.timestamp}\u0000${msg.text}"
                val psm = psmCache[peer.macAddress]
                if (psm != null && psm > 0) {
                    val payload = "${msg.username}\u0000${msg.colorIndex}\u0000$wireText".toByteArray(Charsets.UTF_8)
                    if (l2capManager.sendTextData(device, psm, payload)) true
                    else doGattSerialized {
                        gattClient.sendTextMessage(device, msg.username, wireText, msg.colorIndex)
                    } == true
                } else {
                    doGattSerialized {
                        gattClient.sendTextMessage(device, msg.username, wireText, msg.colorIndex)
                    } == true
                }
            }
            else -> true
        }

        if (!success) {
            scheduleRetrySend(peer, msg, attempt + 1)
        }
    }

    private fun prefetchPsms() {
        val peers = bleScanner.getPeersInRoom(room)
        for (peer in peers) {
            if (!psmCache.containsKey(peer.macAddress)) {
                submitBleWork {
                    val device = adapter?.getRemoteDevice(peer.macAddress) ?: return@submitBleWork
                    val psm = doGattSerialized { gattClient.readPsm(device) }
                    if (psm != null && psm > 0) {
                        psmCache[peer.macAddress] = psm
                        Log.d(TAG, "Pre-fetched PSM $psm for ${peer.macAddress}")
                    }
                }
            }
        }
    }

    private fun checkForMissingMessages() {
        val peers = bleScanner.getPeersInRoom(room)
        for (peer in peers) {
            val hash = peer.latestHash
            if (hash != 0 && !messageStore.hasMessage(hash) && !isInFlightPull(hash)) {
                submitBleWork { pullFromPeer(peer, hash) }
            }
        }
    }

    private fun pullFromPeer(peer: PeerUser, hash: Int) {
        if (bleScanner.getPeersInRoom(room).none { it.macAddress == peer.macAddress }) return
        inFlightPulls[hash] = System.currentTimeMillis()
        try {
            val device = adapter?.getRemoteDevice(peer.macAddress) ?: return
            val psm = psmCache[peer.macAddress]
            if (psm == null || psm <= 0) return

            val result = l2capManager.pullMessage(device, psm, hash)
            if (result != null && !messageStore.hasMessage(result.hash)) {
                val bitmap = PictoCanvasView.bitmapFromBits(result.rawBits)
                val msg = ChatMessage.DrawingMessage(
                    username = peer.username,
                    bitmap = bitmap,
                    rawBits = result.rawBits,
                    rainbowBits = result.rainbowBits,
                    colorIndex = result.colorIndex,
                    timestamp = result.timestamp,
                    hash = result.hash
                )
                messageStore.addMessage(msg)
                onMessageReceived(msg)
                scheduleHashUpdate()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pull failed from ${peer.macAddress} for hash $hash", e)
        } finally {
            inFlightPulls.remove(hash)
        }
    }

    private fun isInFlightPull(hash: Int): Boolean {
        val timestamp = inFlightPulls[hash] ?: return false
        if (System.currentTimeMillis() - timestamp > 30_000L) {
            inFlightPulls.remove(hash)
            return false
        }
        return true
    }
}
