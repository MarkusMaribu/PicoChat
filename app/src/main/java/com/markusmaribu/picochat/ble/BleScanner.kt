package com.markusmaribu.picochat.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.markusmaribu.picochat.model.PeerUser
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.util.Constants
import java.util.concurrent.ConcurrentHashMap

class BleScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner: BluetoothLeScanner? =
        bluetoothManager.adapter?.bluetoothLeScanner

    private val peerMap = ConcurrentHashMap<String, PeerUser>()
    private val departedAtNanos = ConcurrentHashMap<Int, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var onCountsUpdated: ((Map<Room, Int>) -> Unit)? = null
    var onPeersEvicted: (() -> Unit)? = null
    private var isScanning = false

    companion object {
        private const val TAG = "BleScanner"
        private const val DEPARTED_CLEANUP_NANOS = 60_000_000_000L
    }

    private val countUpdateRunnable = Runnable {
        onCountsUpdated?.invoke(getRoomCounts())
    }

    private val evictRunnable = object : Runnable {
        override fun run() {
            evictStalePeers()
            handler.postDelayed(this, Constants.PEER_EVICT_INTERVAL_MS)
        }
    }

    private fun notifyCountsChanged() {
        handler.removeCallbacks(countUpdateRunnable)
        handler.postDelayed(countUpdateRunnable, 250)
    }

    private val scanCallback = object : ScanCallback() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord
                ?.getManufacturerSpecificData(Constants.MANUFACTURER_ID)
                ?: return

            val parsed = BleAdvertiser.parsePayload(mfgData) ?: return
            val (room, username, hash, colorIdx, devIdHash) = parsed
            if (room == null) return

            if (departedAtNanos.containsKey(devIdHash)) return

            val now = System.currentTimeMillis()

            val mac = result.device.address

            val existing = peerMap[mac]
            peerMap[mac] = PeerUser(
                macAddress = mac,
                username = username,
                room = room,
                lastSeen = now,
                latestHash = hash,
                rssi = result.rssi,
                colorIndex = colorIdx,
                deviceIdHash = devIdHash
            )

            if (existing == null || existing.room != room) {
                notifyCountsChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
        }
    }

    /**
     * @param lowLatency true for room-selection screen (fast discovery),
     *                   false for chat screen (balanced mode saves radio
     *                   airtime for active GATT/L2CAP connections).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun startScan(
        lowLatency: Boolean = true,
        onCounts: (Map<Room, Int>) -> Unit
    ) {
        if (scanner == null || isScanning) return
        if (bluetoothManager.adapter?.isEnabled != true) return
        onCountsUpdated = onCounts

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()

        val mode = if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY
                   else ScanSettings.SCAN_MODE_BALANCED

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true

        handler.postDelayed(evictRunnable, Constants.PEER_EVICT_INTERVAL_MS)
        Log.d(TAG, "Scan started (lowLatency=$lowLatency)")
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        if (bluetoothManager.adapter?.isEnabled == true) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan", e)
            }
        }
        handler.removeCallbacks(evictRunnable)
        isScanning = false
        Log.d(TAG, "Scan stopped")
    }

    private fun evictStalePeers() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        departedAtNanos.entries.removeIf { nowNanos - it.value > DEPARTED_CLEANUP_NANOS }
        val now = System.currentTimeMillis()
        val stale = peerMap.entries.filter { now - it.value.lastSeen > Constants.PEER_TTL_MS }
        if (stale.isNotEmpty()) {
            stale.forEach { peerMap.remove(it.key) }
            notifyCountsChanged()
            onPeersEvicted?.invoke()
        }
    }

    fun getRoomCounts(): Map<Room, Int> {
        val counts = mutableMapOf<Room, Int>()
        for (room in Room.entries) {
            counts[room] = peerMap.values.count { it.room == room }
        }
        return counts
    }

    fun getPeersInRoom(room: Room): List<PeerUser> {
        return peerMap.values.filter { it.room == room }
    }

    fun evictPeersByName(room: Room, username: String) {
        val departureNanos = SystemClock.elapsedRealtimeNanos()
        val toRemove = peerMap.entries.filter {
            it.value.room == room && it.value.username == username
        }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach {
                if (it.value.deviceIdHash != 0) {
                    departedAtNanos[it.value.deviceIdHash] = departureNanos
                }
                peerMap.remove(it.key)
            }
            notifyCountsChanged()
        }
    }

    fun blockDeviceId(deviceIdHash: Int) {
        if (deviceIdHash != 0) {
            departedAtNanos[deviceIdHash] = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun unblockDeviceId(deviceIdHash: Int) {
        departedAtNanos.remove(deviceIdHash)
    }

    fun getAllPeers(): Collection<PeerUser> = peerMap.values

    fun getBestRssiInRoom(room: Room): Int? {
        return peerMap.values
            .filter { it.room == room }
            .maxByOrNull { it.rssi }
            ?.rssi
    }
}
