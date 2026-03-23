package com.markusmaribu.picochat.model

enum class Room(val roomId: Int, val label: String, val letter: String, val circleLetter: String) {
    A(0x000A, "Chat Room A", "A", "\u24B6"),
    B(0x000B, "Chat Room B", "B", "\u24B7"),
    C(0x000C, "Chat Room C", "C", "\u24B8"),
    D(0x000D, "Chat Room D", "D", "\u24B9");

    fun serviceDataBytes(): ByteArray =
        byteArrayOf((roomId shr 8).toByte(), (roomId and 0xFF).toByte())

    companion object {
        fun fromRoomId(id: Int): Room? = entries.find { it.roomId == id }

        fun fromServiceData(data: ByteArray): Room? {
            if (data.size < 2) return null
            val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return fromRoomId(id)
        }
    }
}
