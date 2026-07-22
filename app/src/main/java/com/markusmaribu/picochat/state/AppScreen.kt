package com.markusmaribu.picochat.state

import com.markusmaribu.picochat.model.Room

/**
 * The room-selection submenu tree, mirroring the old string-based
 * `activeMenuScreen` state machine in RoomSelectionActivity.
 */
enum class MenuScreen {
    RoomList,
    Options,
    NameInput,
    Color,
    Credits,
    DisplaySetup,
    ExportChat,
    Connecting
}

/** Top-level navigation state machine, replacing the three activities. */
sealed interface AppScreen {

    data class RoomSelection(val menu: MenuScreen = MenuScreen.RoomList) : AppScreen

    data class OnlineRoomSelection(val initialCounts: List<Int> = List(4) { 0 }) : AppScreen

    data class Chat(val room: Room, val isOnline: Boolean) : AppScreen
}
