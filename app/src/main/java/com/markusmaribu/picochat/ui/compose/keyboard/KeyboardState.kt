package com.markusmaribu.picochat.ui.compose.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

enum class KeyboardMode {
    LATIN, ACCENTED, KATAKANA, SYMBOLS, EMOTICONS
}

internal data class KeyDef(
    val label: String,
    val output: String,
    val colSpan: Float = 1f,
    val isWide: Boolean = false,
    val rowSpan: Int = 1
)

internal data class KeyRect(val rect: Rect, val def: KeyDef)

/**
 * State + logic of the old SoftKeyboardView, shared between both display
 * windows and drivable from activity-level key events (D-pad focus).
 * The composable feeds it layout size and touch; screens set the callbacks.
 */
class KeyboardState {

    var onKeyPressed: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onTouchDown: (() -> Unit)? = null
    var onTouchUp: (() -> Unit)? = null

    var onDragStart: ((symbol: String, localX: Float, localY: Float) -> Unit)? = null
    var onDragMove: ((localX: Float, localY: Float) -> Unit)? = null
    var onDragEnd: ((symbol: String, localX: Float, localY: Float) -> Unit)? = null
    var onDragCancel: (() -> Unit)? = null

    var capsLock by mutableStateOf(false)
        private set
    var shiftActive by mutableStateOf(false)
        private set

    var mode by mutableStateOf(KeyboardMode.LATIN)
        private set

    fun setKeyboardMode(value: KeyboardMode) {
        mode = value
        capsLock = false
        shiftActive = false
        focusedRow = 0
        focusedCol = 0
        relayout()
    }

    private var hideEnterKeyState by mutableStateOf(false)
    var hideEnterKey: Boolean
        get() = hideEnterKeyState
        set(value) {
            if (hideEnterKeyState == value) return
            hideEnterKeyState = value
            relayout()
        }

    var showFocus by mutableStateOf(false)
    var focusedRow by mutableIntStateOf(0)
        private set
    var focusedCol by mutableIntStateOf(0)
        private set

    internal var pressedKey by mutableStateOf<KeyRect?>(null)

    /** Bumped whenever the key layout changes so the renderer recomputes. */
    var layoutVersion by mutableIntStateOf(0)
        private set

    private var layoutW = 0f
    private var layoutH = 0f

    internal var keyRects: List<KeyRect> = emptyList()
        private set
    internal var keyGrid: List<List<KeyRect>> = emptyList()
        private set
    internal var keyTextSizePx = 0f
        private set
    internal var smallTextSizePx = 0f
        private set

    internal val currentRows: List<List<KeyDef>>
        get() {
            val rows = when (mode) {
                KeyboardMode.LATIN -> LATIN_ROWS
                KeyboardMode.ACCENTED -> ACCENTED_ROWS
                KeyboardMode.KATAKANA -> KATAKANA_ROWS
                KeyboardMode.SYMBOLS -> SYMBOL_ROWS
                KeyboardMode.EMOTICONS -> EMOTICON_ROWS
            }
            if (!hideEnterKey) return rows
            return rows.map { row ->
                row.map {
                    if (it.output == "ENTER") {
                        KeyDef("", "BLANK", it.colSpan, it.isWide, it.rowSpan)
                    } else it
                }
            }
        }

    internal fun updateLayoutSize(totalW: Float, totalH: Float) {
        if (totalW == layoutW && totalH == layoutH) return
        layoutW = totalW
        layoutH = totalH
        relayout()
    }

    private fun relayout() {
        val totalW = layoutW
        val totalH = layoutH
        if (totalW <= 0 || totalH <= 0) return
        val rows = currentRows
        val rects = mutableListOf<KeyRect>()
        val grid = mutableListOf<List<KeyRect>>()
        val rowHeight = totalH / rows.size
        val gap = 2f

        for ((rowIndex, row) in rows.withIndex()) {
            val totalSpan = row.sumOf { it.colSpan.toDouble() }.toFloat()
            val keyUnitW = (totalW - gap * (row.size + 1)) / totalSpan
            var x = gap
            val y = rowIndex * rowHeight + gap
            val gridRow = mutableListOf<KeyRect>()

            for (key in row) {
                val keyW = keyUnitW * key.colSpan + gap * (key.colSpan.toInt() - 1)
                val rect = Rect(x, y, x + keyW, y + rowHeight * key.rowSpan - gap * 2)
                val kr = KeyRect(rect, key)
                rects.add(kr)
                gridRow.add(kr)
                x += keyW + gap
            }
            grid.add(gridRow)
        }
        keyRects = rects
        keyGrid = grid
        keyTextSizePx = rowHeight * 0.62f
        smallTextSizePx = rowHeight * 0.42f
        layoutVersion++
    }

    val keyTextSize: Float get() = keyTextSizePx

    internal fun getEffectiveOutput(key: KeyDef): String {
        if (mode == KeyboardMode.LATIN && (capsLock || shiftActive)) {
            if (key.output.length == 1 && key.output[0].isLetter()) {
                return key.output.uppercase()
            }
            if (shiftActive) {
                SHIFT_MAP[key.output]?.let { return it }
            }
        }
        return key.output
    }

    internal fun effectiveLabel(key: KeyDef): String {
        return if (mode == KeyboardMode.LATIN && (capsLock || shiftActive)) {
            if (key.output.length == 1 && key.output[0].isLetter()) {
                key.label.uppercase()
            } else if (shiftActive) {
                SHIFT_MAP[key.output] ?: key.label
            } else {
                key.label
            }
        } else {
            key.label
        }
    }

    internal fun handleKeyPress(key: KeyDef) {
        when (key.output) {
            "BACKSPACE" -> onBackspace?.invoke()
            "ENTER" -> onEnter?.invoke()
            "CAPS" -> {
                capsLock = !capsLock
                if (capsLock) shiftActive = false
            }
            "SHIFT" -> {
                shiftActive = !shiftActive
                if (shiftActive) capsLock = false
            }
            else -> {
                val ch = getEffectiveOutput(key)
                onKeyPressed?.invoke(ch)
                if (shiftActive && !capsLock) {
                    shiftActive = false
                }
            }
        }
    }

    fun consumeShiftAfterDrag() {
        if (shiftActive && !capsLock) {
            shiftActive = false
        }
    }

    fun cycleCaps() {
        when {
            !shiftActive && !capsLock -> { shiftActive = true; capsLock = false }
            shiftActive && !capsLock -> { shiftActive = false; capsLock = true }
            else -> { shiftActive = false; capsLock = false }
        }
    }

    // ---- D-pad focus navigation ----

    val rowCount: Int get() = keyGrid.size

    fun moveFocusLeft() {
        if (keyGrid.isEmpty()) return
        val row = keyGrid[focusedRow]
        var next = focusedCol - 1
        while (next >= 0 && row[next].def.output == "BLANK") next--
        if (next >= 0) focusedCol = next
    }

    fun moveFocusRight() {
        if (keyGrid.isEmpty()) return
        val row = keyGrid[focusedRow]
        var next = focusedCol + 1
        while (next < row.size && row[next].def.output == "BLANK") next++
        if (next < row.size) focusedCol = next
    }

    fun moveFocusUp(): Boolean {
        if (keyGrid.isEmpty() || focusedRow <= 0) return false
        val cx = keyGrid[focusedRow].getOrNull(focusedCol)?.rect?.center?.x ?: return false
        focusedRow--
        focusedCol = closestColInRow(focusedRow, cx)
        return true
    }

    fun moveFocusDown(): Boolean {
        if (keyGrid.isEmpty() || focusedRow >= keyGrid.lastIndex) return false
        val cx = keyGrid[focusedRow].getOrNull(focusedCol)?.rect?.center?.x ?: return false
        focusedRow++
        focusedCol = closestColInRow(focusedRow, cx)
        return true
    }

    private fun closestColInRow(row: Int, sourceCenterX: Float): Int {
        val targetRow = keyGrid[row]
        var best = 0
        var bestDist = Float.MAX_VALUE
        for ((i, kr) in targetRow.withIndex()) {
            if (kr.def.output == "BLANK") continue
            val dist = abs(kr.rect.center.x - sourceCenterX)
            if (dist < bestDist) { bestDist = dist; best = i }
        }
        return best
    }

    fun activateFocusedKey(): Boolean {
        val kr = keyGrid.getOrNull(focusedRow)?.getOrNull(focusedCol) ?: return false
        if (kr.def.output == "BLANK") return false
        handleKeyPress(kr.def)
        return true
    }

    internal companion object {
        private fun kd(ch: String) = KeyDef(ch, ch)

        val SHIFT_MAP = mapOf(
            "1" to "!", "2" to "@", "3" to "#", "4" to "$",
            "5" to "%", "6" to "^", "7" to "&", "8" to "*",
            "9" to "(", "0" to ")", "-" to "_", "=" to "+"
        )

        val SPECIAL_OUTPUTS = setOf("CAPS", "SHIFT", "BACKSPACE", "ENTER", " ")
        val NON_DRAGGABLE_OUTPUTS = setOf("CAPS", "SHIFT", "BACKSPACE", "ENTER", " ", "BLANK")

        val LATIN_ROWS = listOf(
            listOf(
                KeyDef("1", "1"), KeyDef("2", "2"), KeyDef("3", "3"), KeyDef("4", "4"),
                KeyDef("5", "5"), KeyDef("6", "6"), KeyDef("7", "7"), KeyDef("8", "8"),
                KeyDef("9", "9"), KeyDef("0", "0"), KeyDef("-", "-"), KeyDef("=", "=")
            ),
            listOf(
                KeyDef("q", "q"), KeyDef("w", "w"), KeyDef("e", "e"), KeyDef("r", "r"),
                KeyDef("t", "t"), KeyDef("y", "y"), KeyDef("u", "u"), KeyDef("i", "i"),
                KeyDef("o", "o"), KeyDef("p", "p"),
                KeyDef("\u2190", "BACKSPACE", 2f, true)
            ),
            listOf(
                KeyDef("CAPS", "CAPS", 1.5f, true),
                KeyDef("a", "a"), KeyDef("s", "s"), KeyDef("d", "d"), KeyDef("f", "f"),
                KeyDef("g", "g"), KeyDef("h", "h"), KeyDef("j", "j"), KeyDef("k", "k"),
                KeyDef("l", "l"),
                KeyDef("\u21B5ENTER", "ENTER", 1.5f, true)
            ),
            listOf(
                KeyDef("SHIFT", "SHIFT", 2f, true),
                KeyDef("z", "z"), KeyDef("x", "x"), KeyDef("c", "c"), KeyDef("v", "v"),
                KeyDef("b", "b"), KeyDef("n", "n"), KeyDef("m", "m"),
                KeyDef(",", ","), KeyDef(".", "."), KeyDef("/", "/")
            ),
            listOf(
                KeyDef(";", ";"), KeyDef("'", "'"),
                KeyDef("SPACE", " ", 6f, true),
                KeyDef("[", "["), KeyDef("]", "]")
            )
        )

        val ACCENTED_ROWS = listOf(
            listOf(
                kd("à"), kd("á"), kd("â"), kd("ã"), kd("è"), kd("é"),
                kd("ê"), kd("ë"), kd("ì"), kd("í"), kd("î")
            ),
            listOf(
                kd("ï"), kd("ò"), kd("ó"), kd("ô"), kd("ö"), kd("œ"),
                kd("ù"), kd("ú"), kd("û"), kd("ü"), kd("ç"),
                KeyDef("\u2190", "BACKSPACE", 1f, true)
            ),
            listOf(
                kd("ñ"), kd("ß"), kd("À"), kd("Á"), kd("Â"), kd("Ä"),
                kd("È"), kd("É"), kd("Ê"), kd("Ë"), kd("Ì"),
                KeyDef("ENTER", "ENTER", 1f, true)
            ),
            listOf(
                kd("Í"), kd("Î"), kd("Ï"), kd("Ò"), kd("Ó"), kd("Ô"),
                kd("Ö"), kd("Œ"), kd("Ù"), kd("Ú"), kd("Û"), kd("Ü")
            ),
            listOf(
                kd("Ç"), kd("Ñ"), kd("¡"), kd("¿"), kd("€"), kd("¢"), kd("£"),
                KeyDef("SPACE", " ", 5f, true)
            )
        )

        val KATAKANA_ROWS = listOf(
            listOf(
                kd("あ"), kd("か"), kd("さ"), kd("た"), kd("な"),
                kd("は"), kd("ま"), kd("や"), kd("ら"), kd("わ"), kd("ー")
            ),
            listOf(
                kd("い"), kd("き"), kd("し"), kd("ち"), kd("に"),
                kd("ひ"), kd("み"), kd("ゆ"), kd("り"), kd("を"),
                KeyDef("\u2190", "BACKSPACE", 1f, true)
            ),
            listOf(
                kd("う"), kd("く"), kd("す"), kd("つ"), kd("ぬ"),
                kd("ふ"), kd("む"), kd("よ"), kd("る"), kd("ん"),
                KeyDef("ENTER", "ENTER", 1f, true)
            ),
            listOf(
                kd("え"), kd("け"), kd("せ"), kd("て"), kd("ね"),
                kd("へ"), kd("め"), kd("れ"), kd("、"), kd("!")
            ),
            listOf(
                kd("お"), kd("こ"), kd("そ"), kd("と"), kd("の"),
                kd("ほ"), kd("も"), kd("ろ"), kd("。"), kd("?"),
                KeyDef("SPACE", " ", 1f, true)
            )
        )

        val SYMBOL_ROWS = listOf(
            listOf(
                kd("!"), kd("?"), kd("&"), kd("*"), kd("'"),
                kd("~"), kd(":"), kd("@"), kd("^"), kd("_")
            ),
            listOf(
                kd("+"), kd("-"), kd("×"), kd("/"), kd("÷"),
                kd("="), kd("→"), kd("←"), kd("↑"), kd("↓"),
                KeyDef("\u2190", "BACKSPACE", 1f, true)
            ),
            listOf(
                kd("「"), kd("」"), kd("\""), kd("("), kd(")"),
                kd("<"), kd(">"), kd("{"), kd("}"), kd("•"),
                KeyDef("ENTER", "ENTER", 1f, true)
            ),
            listOf(
                kd("%"), kd("※"), kd("#"), kd("♭"), kd("♪"),
                kd("±"), kd("$"), kd("¢"), kd("£"), kd("\\")
            ),
            listOf(
                kd("°"), kd("|"), kd("∞"), kd("…"), kd("™"),
                kd("©"), kd("®"), kd("§"), kd("¶"),
                KeyDef("SPACE", " ", 3f, true)
            )
        )

        val EMOTICON_ROWS = listOf(
            listOf(
                kd("1"), kd("2"), kd("3"), kd("4"), kd("5"),
                kd("6"), kd("7"), kd("8"), kd("9"), kd("0"), kd("="),
                KeyDef("", "BLANK")
            ),
            listOf(
                kd("😊"), kd("😐"), kd("😎"), kd("💀"), kd("✨"),
                kd("☽"), kd("🚀"), kd("🎁"), kd("✉"), kd("🖩"), kd("🕒"),
                KeyDef("\u2190", "BACKSPACE", 1f, true)
            ),
            listOf(
                kd("🅐"), kd("🅑"), kd("🅧"), kd("🅨"), kd("🅻"),
                kd("🆁"), kd("✙"), kd("♠"), kd("♦"), kd("♥"), kd("♣"),
                KeyDef("ENTER", "ENTER", 1f, true, 2)
            ),
            listOf(
                kd("❕"), kd("❔"), kd("+"), kd("-"), kd("✩"),
                kd("◯"), kd("⬦"), kd("⬜"), kd("△"), kd("▽"), kd("◉"),
                KeyDef("", "BLANK")
            ),
            listOf(
                kd("⭢"), kd("⭠"), kd("⭡"), kd("⭣"), kd("✬"),
                kd("🌑"), kd("⬥"), kd("⬛"), kd("▲"), kd("▼"), kd("⨉"),
                KeyDef("SPACE", " ", 1f, true)
            )
        )
    }
}
