package com.markusmaribu.picochat.model

data class PeerUser(
    val macAddress: String,
    val username: String,
    val room: Room,
    var lastSeen: Long = System.currentTimeMillis(),
    var latestHash: Int = 0,
    var rssi: Int = -127,
    val colorIndex: Int = 0,
    val deviceIdHash: Int = 0
)
