package com.markusmaribu.picochat.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.markusmaribu.picochat.util.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@android.annotation.SuppressLint("MissingPermission")
class GattClient(private val context: Context) {

    companion object {
        private const val TAG = "GattClient"
    }

    fun sendTextMessage(device: BluetoothDevice, username: String, text: String, colorIndex: Int = 0): Boolean {
        val payload = "$username\u0000$colorIndex\u0000$text".toByteArray(Charsets.UTF_8)
        if (payload.size > 512) {
            Log.w(TAG, "Text payload too large: ${payload.size}")
            return false
        }

        var success = false
        val latch = CountDownLatch(1)
        var gatt: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            g.requestMtu(Constants.MTU_REQUEST)
                        } else {
                            g.disconnect()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        latch.countDown()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.disconnect()
                    return
                }
                val service = g.getService(Constants.SERVICE_UUID)
                val textChar = service?.getCharacteristic(Constants.TEXT_MSG_CHAR_UUID)
                if (textChar == null) {
                    g.disconnect()
                    return
                }
                textChar.value = payload
                textChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(textChar)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                success = (status == BluetoothGatt.GATT_SUCCESS)
                g.disconnect()
            }
        }

        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (!latch.await(Constants.GATT_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Log.w(TAG, "sendTextMessage timed out for ${device.address}")
                gatt.disconnect()
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendTextMessage error for ${device.address}", e)
        } finally {
            try { gatt?.close() } catch (_: Exception) {}
        }
        return success
    }

    fun readPsm(device: BluetoothDevice): Int {
        var psm = -1
        val latch = CountDownLatch(1)
        var gatt: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            // PSM is only 2 bytes — skip MTU negotiation
                            // and go straight to service discovery.
                            g.discoverServices()
                        } else {
                            g.disconnect()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        latch.countDown()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.disconnect()
                    return
                }
                val service = g.getService(Constants.SERVICE_UUID)
                val psmChar = service?.getCharacteristic(Constants.PSM_CHAR_UUID)
                if (psmChar == null) {
                    g.disconnect()
                    return
                }
                g.readCharacteristic(psmChar)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == Constants.PSM_CHAR_UUID) {
                    if (value.size >= 2) {
                        psm = ByteBuffer.wrap(value)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .short.toInt() and 0xFFFF
                    }
                }
                g.disconnect()
            }
        }

        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (!latch.await(Constants.GATT_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Log.w(TAG, "readPsm timed out for ${device.address}")
                gatt.disconnect()
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readPsm error for ${device.address}", e)
        } finally {
            try { gatt?.close() } catch (_: Exception) {}
        }
        return psm
    }
}
