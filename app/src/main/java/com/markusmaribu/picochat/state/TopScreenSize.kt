package com.markusmaribu.picochat.state

/** Display Setup "Top Size" steps (Full → 75%, including Thor at 77%). */
object TopScreenSize {
    const val COUNT = 7

    private val SCALE_FRACTIONS = floatArrayOf(1f, 0.95f, 0.90f, 0.85f, 0.80f, 0.77f, 0.75f)
    private val LABELS = arrayOf("Full", "95%", "90%", "85%", "80%", "Thor", "75%")

    fun indexCoerced(index: Int): Int = index.coerceIn(0, COUNT - 1)

    fun fractionForIndex(index: Int): Float = SCALE_FRACTIONS[indexCoerced(index)]

    fun labelForIndex(index: Int): String = LABELS[indexCoerced(index)]

    fun nextIndex(index: Int): Int = (indexCoerced(index) + 1) % COUNT
}
