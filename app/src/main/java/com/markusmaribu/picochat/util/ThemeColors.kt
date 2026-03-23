package com.markusmaribu.picochat.util

import android.graphics.Color

object ThemeColors {

    val PALETTE = intArrayOf(
        0xFF62829B.toInt(),  // 0  slate blue
        0xFFB94800.toInt(),  // 1  burnt orange
        0xFFFC0019.toInt(),  // 2  red
        0xFFFB8BFB.toInt(),  // 3  pink
        0xFFFB9200.toInt(),  // 4  orange
        0xFFF2E300.toInt(),  // 5  yellow
        0xFFAAFB00.toInt(),  // 6  lime
        0xFF00FB00.toInt(),  // 7  green
        0xFF00A137.toInt(),  // 8  dark green
        0xFF49DB8A.toInt(),  // 9  mint
        0xFF2EBAF3.toInt(),  // 10 sky blue
        0xFF0058F2.toInt(),  // 11 blue
        0xFF000091.toInt(),  // 12 navy
        0xFF8A00D2.toInt(),  // 13 purple
        0xFFD300EB.toInt(),  // 14 magenta
        0xFFFC0095.toInt()   // 15 hot pink
    )

    const val DEFAULT_INDEX = 0

    fun brighten(color: Int, factor: Float = 0.45f): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb(
            255,
            r + ((255 - r) * factor).toInt(),
            g + ((255 - g) * factor).toInt(),
            b + ((255 - b) * factor).toInt()
        )
    }

    fun darken(color: Int, factor: Float = 0.3f): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb(
            255,
            (r * (1f - factor)).toInt(),
            (g * (1f - factor)).toInt(),
            (b * (1f - factor)).toInt()
        )
    }
}
