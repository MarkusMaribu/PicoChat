package com.markusmaribu.picochat.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.markusmaribu.picochat.model.Room
import com.markusmaribu.picochat.util.Constants

class BleAdvertiser(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val advertiser: BluetoothLeAdvertiser? =
        bluetoothManager.adapter?.bluetoothLeAdvertiser

    private var callback: AdvertiseCallback? = null
    @Volatile
    private var lastAdvertisedHash: Int = Int.MIN_VALUE

    @android.annotation.SuppressLint("MissingPermission")
    fun startAdvertising(room: Room, username: String, latestHash: Int = 0, colorIndex: Int = 0, deviceIdHash: Int = 0) {
        if (advertiser == null || bluetoothManager.adapter?.isEnabled != true) {
            Log.w(TAG, "BLE advertising not supported")
            return
        }

        stopAdvertising()

        val payload = buildPayload(room, username, latestHash, colorIndex, deviceIdHash)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(Constants.MANUFACTURER_ID, payload)
            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Advertising started for room ${room.letter}")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed with error: $errorCode")
            }
        }

        advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stopAdvertising() {
        callback?.let {
            if (bluetoothManager.adapter?.isEnabled == true) {
                try {
                    advertiser?.stopAdvertising(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping advertising", e)
                }
            }
        }
        callback = null
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun updateHash(room: Room, username: String, latestHash: Int, colorIndex: Int = 0, deviceIdHash: Int = 0) {
        if (latestHash == lastAdvertisedHash) return
        lastAdvertisedHash = latestHash
        stopAdvertising()
        startAdvertising(room, username, latestHash, colorIndex, deviceIdHash)
    }

    private fun buildPayload(room: Room, username: String, latestHash: Int, colorIndex: Int, deviceIdHash: Int): ByteArray {
        val roomBytes = room.serviceDataBytes()
        val nameBytes = username.toByteArray(Charsets.UTF_8).let {
            if (it.size > Constants.USERNAME_MAX_LENGTH) it.copyOf(Constants.USERNAME_MAX_LENGTH) else it
        }
        val colorByte = byteArrayOf(colorIndex.coerceIn(0, 15).toByte())
        val devIdBytes = byteArrayOf(
            (deviceIdHash shr 8).toByte(),
            deviceIdHash.toByte()
        )
        val hashBytes = byteArrayOf(
            (latestHash shr 24).toByte(),
            (latestHash shr 16).toByte(),
            (latestHash shr 8).toByte(),
            latestHash.toByte()
        )
        return roomBytes + nameBytes + colorByte + devIdBytes + hashBytes
    }

    data class ParsedPayload(val room: Room?, val name: String, val hash: Int, val colorIndex: Int, val deviceIdHash: Int)

    companion object {
        private const val TAG = "BleAdvertiser"

        fun parsePayload(data: ByteArray): ParsedPayload? {
            if (data.size < 9) return null
            val room = Room.fromServiceData(data)
            val hashStart = data.size - 4
            val deviceIdStart = hashStart - 2
            val colorByteIdx = deviceIdStart - 1
            val nameBytes = data.copyOfRange(2, colorByteIdx)
            val name = String(nameBytes, Charsets.UTF_8)
            val colorIdx = (data[colorByteIdx].toInt() and 0xFF).coerceIn(0, 15)
            val devIdHash = ((data[deviceIdStart].toInt() and 0xFF) shl 8) or
                    (data[deviceIdStart + 1].toInt() and 0xFF)
            val hash = ((data[hashStart].toInt() and 0xFF) shl 24) or
                    ((data[hashStart + 1].toInt() and 0xFF) shl 16) or
                    ((data[hashStart + 2].toInt() and 0xFF) shl 8) or
                    (data[hashStart + 3].toInt() and 0xFF)
            return ParsedPayload(room, name, hash, colorIdx, devIdHash)
        }
    }
}
