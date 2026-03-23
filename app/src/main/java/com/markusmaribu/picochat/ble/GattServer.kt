package com.markusmaribu.picochat.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.markusmaribu.picochat.model.ChatMessage
import com.markusmaribu.picochat.util.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder

@android.annotation.SuppressLint("MissingPermission")
class GattServer(
    context: Context,
    private val lifecycleOwner: Any
) {

    companion object {
        private const val TAG = "GattServer"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var onTextReceived: ((ChatMessage) -> Unit)? = null
    private var l2capPsm: Int = 0

    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                Log.d(TAG, "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                Log.d(TAG, "Device disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                Constants.PSM_CHAR_UUID -> {
                    val psmBytes = ByteBuffer.allocate(2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(l2capPsm.toShort())
                        .array()
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, psmBytes
                    )
                }
                else -> {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                    )
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            when (characteristic.uuid) {
                Constants.TEXT_MSG_CHAR_UUID -> {
                    value?.let { data ->
                        try {
                            val raw = String(data, Charsets.UTF_8)
                            val parts = raw.split('\u0000', limit = 4)
                            if (parts.size >= 2) {
                                val username = parts[0]
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
                                        username = username,
                                        text = messageText,
                                        colorIndex = colorIdx,
                                        timestamp = timestamp
                                    )
                                }
                                onTextReceived?.invoke(msg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing text message", e)
                        }
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null
                        )
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "MTU changed to $mtu for ${device.address}")
        }
    }

    fun setL2capPsm(psm: Int) {
        l2capPsm = psm
    }

    fun start(onText: (ChatMessage) -> Unit) {
        onTextReceived = onText

        if (bluetoothManager.adapter?.isEnabled != true) return

        gattServer = bluetoothManager.openGattServer(
            lifecycleOwner as Context, serverCallback
        ) ?: run {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val service = BluetoothGattService(
            Constants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val textChar = BluetoothGattCharacteristic(
            Constants.TEXT_MSG_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(textChar)

        val psmChar = BluetoothGattCharacteristic(
            Constants.PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(psmChar)

        val hashChar = BluetoothGattCharacteristic(
            Constants.MSG_HASH_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(hashChar)

        gattServer?.addService(service)
        Log.d(TAG, "GATT server started")
    }

    fun stop() {
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        Log.d(TAG, "GATT server stopped")
    }
}
