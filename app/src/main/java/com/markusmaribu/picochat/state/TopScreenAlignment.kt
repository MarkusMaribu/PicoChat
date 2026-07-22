package com.markusmaribu.picochat.state

import androidx.compose.ui.Alignment

/** Display Setup "Top Alignment" steps (Bottom → Center → Top). */
object TopScreenAlignment {
    const val COUNT = 3
    const val DEFAULT_INDEX = 1

    private val LABELS = arrayOf("Bottom", "Center", "Top")

    fun indexCoerced(index: Int): Int = index.coerceIn(0, COUNT - 1)

    fun labelForIndex(index: Int): String = LABELS[indexCoerced(index)]

    fun alignmentForIndex(index: Int): Alignment =
        when (indexCoerced(index)) {
            0 -> Alignment.BottomCenter
            1 -> Alignment.Center
            else -> Alignment.TopCenter
        }

    fun nextIndex(index: Int): Int = (indexCoerced(index) + 1) % COUNT
}
