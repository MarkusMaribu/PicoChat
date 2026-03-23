package com.markusmaribu.picochat.util

import android.content.Context
import java.util.UUID

object Constants {

    private const val PREF_DEVICE_ID = "device_id"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("picochat_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString(PREF_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        }
        return id
    }

    fun deviceIdHash(deviceId: String): Int = deviceId.hashCode() and 0xFFFF
    val SERVICE_UUID: UUID = UUID.fromString("0000CAFE-0001-1000-8000-00805F9B34FB")
    val TEXT_MSG_CHAR_UUID: UUID = UUID.fromString("0000CAFE-0002-1000-8000-00805F9B34FB")
    val PSM_CHAR_UUID: UUID = UUID.fromString("0000CAFE-0003-1000-8000-00805F9B34FB")
    val MSG_HASH_CHAR_UUID: UUID = UUID.fromString("0000CAFE-0004-1000-8000-00805F9B34FB")

    const val PEER_TTL_MS = 10_000L
    const val PEER_EVICT_INTERVAL_MS = 2_000L
    const val MAX_ROOM_USERS = 5
    const val MAX_ONLINE_ROOM_USERS = 16
    const val USERNAME_MAX_LENGTH = 10

    const val CANVAS_W = 256
    const val CANVAS_H = 88
    const val DRAWING_BYTES = 2816 // 256 * 88 / 8

    const val MTU_REQUEST = 517
    const val MESH_PULL_BACKOFF_MIN_MS = 100L
    const val MESH_PULL_BACKOFF_MAX_MS = 500L

    const val BLE_INTER_OP_DELAY_MS = 50L
    const val ADVERTISE_DEBOUNCE_MS = 1_500L
    const val L2CAP_CONNECT_TIMEOUT_MS = 8_000L
    const val L2CAP_ACK_TIMEOUT_MS = 3_000L
    const val GATT_CONNECT_TIMEOUT_SEC = 4L

    const val MANUFACTURER_ID = 0xFFFF
    const val SYSTEM_MSG_PREFIX = "\u0001"

    const val EXTRA_ROOM = "extra_room"
    const val EXTRA_USERNAME = "extra_username"
    const val EXTRA_COLOR_INDEX = "extra_color_index"
    const val EXTRA_IS_ONLINE = "extra_is_online"
    const val EXTRA_INITIAL_ROOM_COUNTS = "extra_initial_room_counts"

    const val ONLINE_CONNECT_TIMEOUT_MS = 8_000L
}
