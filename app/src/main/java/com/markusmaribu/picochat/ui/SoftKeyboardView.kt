package com.markusmaribu.picochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.markusmaribu.picochat.R

enum class KeyboardMode {
    LATIN, ACCENTED, KATAKANA, SYMBOLS, EMOTICONS
}

class SoftKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onKeyPressed: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onTouchDown: (() -> Unit)? = null
    var onTouchUp: (() -> Unit)? = null

    var onDragStart: ((symbol: String, localX: Float, localY: Float) -> Unit)? = null
    var onDragMove: ((localX: Float, localY: Float) -> Unit)? = null
    var onDragEnd: ((symbol: String, localX: Float, localY: Float) -> Unit)? = null
    var onDragCancel: (() -> Unit)? = null

    private var capsLock = false
    private var shiftActive = false

    var keyboardMode: KeyboardMode = KeyboardMode.LATIN
        set(value) {
            field = value
            capsLock = false
            shiftActive = false
            focusedRow = 0
            focusedCol = 0
            currentRows = getRowsForMode(value)
            if (width > 0 && height > 0) {
                layoutKeys(width.toFloat(), height.toFloat())
            }
            invalidate()
        }

    var hideEnterKey: Boolean = false
        set(value) {
            field = value
            currentRows = getRowsForMode(keyboardMode)
            requestLayout()
            invalidate()
        }

    private var currentRows: List<List<KeyDef>> = LATIN_ROWS

    private val keyFillPaint = Paint().apply {
        color = 0xFFDADADA.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val specialKeyFillPaint = Paint().apply {
        color = 0xFFC0C0C0.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val cozetteFont = ResourcesCompat.getFont(context, R.font.cozette_vector)

    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = cozetteFont
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = cozetteFont
    }
    private val activeFillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.ds_teal_border)
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    var accentColor: Int
        get() = activeFillPaint.color
        set(value) {
            activeFillPaint.color = value
            invalidate()
        }
    private val pressedFillPaint = Paint().apply {
        color = 0xFFB0B0B0.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val focusPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        isAntiAlias = false
    }

    var showFocus: Boolean = false
        set(value) { field = value; invalidate() }
    var focusedRow: Int = 0
        private set
    var focusedCol: Int = 0
        private set

    private val specialOutputs = setOf("CAPS", "SHIFT", "BACKSPACE", "ENTER", " ")

    private data class KeyDef(
        val label: String,
        val output: String,
        val colSpan: Float = 1f,
        val isWide: Boolean = false,
        val rowSpan: Int = 1
    )

    private data class KeyRect(val rect: RectF, val def: KeyDef)
    private var keyRects: List<KeyRect> = emptyList()
    private var keyGrid: List<List<KeyRect>> = emptyList()
    private var pressedKey: KeyRect? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false
    private var dragSymbol: String? = null
    private val dragThresholdPx = 15f * resources.displayMetrics.density
    private val nonDraggableOutputs = setOf("CAPS", "SHIFT", "BACKSPACE", "ENTER", " ", "BLANK")

    private fun getRowsForMode(mode: KeyboardMode): List<List<KeyDef>> {
        val rows = when (mode) {
            KeyboardMode.LATIN -> LATIN_ROWS
            KeyboardMode.ACCENTED -> ACCENTED_ROWS
            KeyboardMode.KATAKANA -> KATAKANA_ROWS
            KeyboardMode.SYMBOLS -> SYMBOL_ROWS
            KeyboardMode.EMOTICONS -> EMOTICON_ROWS
        }
        if (!hideEnterKey) return rows
        return rows.map { row ->
            row.map { if (it.output == "ENTER") KeyDef("", "BLANK", it.colSpan, it.isWide, it.rowSpan) else it }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys(w.toFloat(), h.toFloat())
    }

    private fun layoutKeys(totalW: Float, totalH: Float) {
        if (totalW <= 0 || totalH <= 0) return
        val rects = mutableListOf<KeyRect>()
        val grid = mutableListOf<List<KeyRect>>()
        val rowHeight = totalH / currentRows.size
        val gap = 2f

        for ((rowIndex, row) in currentRows.withIndex()) {
            val totalSpan = row.sumOf { it.colSpan.toDouble() }.toFloat()
            val keyUnitW = (totalW - gap * (row.size + 1)) / totalSpan
            var x = gap
            val y = rowIndex * rowHeight + gap
            val gridRow = mutableListOf<KeyRect>()

            for (key in row) {
                val keyW = keyUnitW * key.colSpan + gap * (key.colSpan.toInt() - 1)
                val rect = RectF(x, y, x + keyW, y + rowHeight * key.rowSpan - gap * 2)
                val kr = KeyRect(rect, key)
                rects.add(kr)
                gridRow.add(kr)
                x += keyW + gap
            }
            grid.add(gridRow)
        }
        keyRects = rects
        keyGrid = grid
        keyTextPaint.textSize = rowHeight * 0.62f
        smallTextPaint.textSize = rowHeight * 0.42f
    }

    override fun onDraw(canvas: Canvas) {
        for (kr in keyRects) {
            if (kr.def.output == "BLANK") continue
            val isPressed = (pressedKey == kr)
            val isCapsActive = kr.def.output == "CAPS" && capsLock
            val isShiftActive = kr.def.output == "SHIFT" && shiftActive
            val isSpecial = kr.def.output in specialOutputs
            val r = kr.rect

            val fill = when {
                isPressed -> pressedFillPaint
                isCapsActive || isShiftActive -> activeFillPaint
                isSpecial -> specialKeyFillPaint
                else -> keyFillPaint
            }
            canvas.drawRect(r, fill)

            val label = if (keyboardMode == KeyboardMode.LATIN && (capsLock || shiftActive)) {
                if (kr.def.output.length == 1 && kr.def.output[0].isLetter()) {
                    kr.def.label.uppercase()
                } else if (shiftActive) {
                    SHIFT_MAP[kr.def.output] ?: kr.def.label
                } else {
                    kr.def.label
                }
            } else {
                kr.def.label
            }

            val paint = if (kr.def.isWide && label.length > 3) smallTextPaint else keyTextPaint
            val textY = r.centerY() - (paint.descent() + paint.ascent()) / 2
            val drawLabel = if (!isSpecial && label.length in 1..2
                && label.codePointCount(0, label.length) == 1
            ) label + "\uFE0E" else label
            canvas.drawText(drawLabel, r.centerX(), textY, paint)
        }

        if (showFocus && focusedRow in keyGrid.indices) {
            val row = keyGrid[focusedRow]
            if (focusedCol in row.indices) {
                canvas.drawRect(row[focusedCol].rect, focusPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = keyRects.find { it.rect.contains(event.x, event.y) && it.def.output != "BLANK" }
                if (hit != pressedKey) {
                    pressedKey = hit
                    invalidate()
                }
                isDragging = false
                dragSymbol = null
                if (hit != null && hit.def.output !in nonDraggableOutputs) {
                    dragStartX = event.x
                    dragStartY = event.y
                    dragSymbol = getEffectiveOutput(hit.def)
                }
                if (hit != null) onTouchDown?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && dragSymbol != null) {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    if (dx * dx + dy * dy > dragThresholdPx * dragThresholdPx) {
                        isDragging = true
                        pressedKey = null
                        invalidate()
                        onDragStart?.invoke(dragSymbol!!, event.x, event.y)
                    }
                }
                if (isDragging) {
                    onDragMove?.invoke(event.x, event.y)
                } else {
                    val hit = keyRects.find { it.rect.contains(event.x, event.y) && it.def.output != "BLANK" }
                    if (hit != pressedKey) {
                        pressedKey = hit
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    onDragEnd?.invoke(dragSymbol!!, event.x, event.y)
                    isDragging = false
                    dragSymbol = null
                } else {
                    pressedKey?.let {
                        onTouchUp?.invoke()
                        handleKeyPress(it.def)
                    }
                }
                pressedKey = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    onDragCancel?.invoke()
                    isDragging = false
                    dragSymbol = null
                }
                pressedKey = null
                invalidate()
            }
        }
        return true
    }

    private fun handleKeyPress(key: KeyDef) {
        when (key.output) {
            "BACKSPACE" -> onBackspace?.invoke()
            "ENTER" -> onEnter?.invoke()
            "CAPS" -> {
                capsLock = !capsLock
                if (capsLock) shiftActive = false
                invalidate()
            }
            "SHIFT" -> {
                shiftActive = !shiftActive
                if (shiftActive) capsLock = false
                invalidate()
            }
            else -> {
                val ch = if (keyboardMode == KeyboardMode.LATIN && (capsLock || shiftActive)) {
                    if (key.output.length == 1 && key.output[0].isLetter()) {
                        key.output.uppercase()
                    } else if (shiftActive) {
                        SHIFT_MAP[key.output] ?: key.output
                    } else {
                        key.output
                    }
                } else {
                    key.output
                }
                onKeyPressed?.invoke(ch)
                if (shiftActive && !capsLock) {
                    shiftActive = false
                    invalidate()
                }
            }
        }
    }

    val keyTextSize: Float get() = keyTextPaint.textSize

    fun consumeShiftAfterDrag() {
        if (shiftActive && !capsLock) {
            shiftActive = false
            invalidate()
        }
    }

    fun cycleCaps() {
        when {
            !shiftActive && !capsLock -> { shiftActive = true; capsLock = false }
            shiftActive && !capsLock  -> { shiftActive = false; capsLock = true }
            else                      -> { shiftActive = false; capsLock = false }
        }
        invalidate()
    }

    private fun getEffectiveOutput(key: KeyDef): String {
        if (keyboardMode == KeyboardMode.LATIN && (capsLock || shiftActive)) {
            if (key.output.length == 1 && key.output[0].isLetter()) {
                return key.output.uppercase()
            }
            if (shiftActive) {
                SHIFT_MAP[key.output]?.let { return it }
            }
        }
        return key.output
    }

    fun moveFocusLeft() {
        if (keyGrid.isEmpty()) return
        val row = keyGrid[focusedRow]
        var next = focusedCol - 1
        while (next >= 0 && row[next].def.output == "BLANK") next--
        if (next >= 0) { focusedCol = next; invalidate() }
    }

    fun moveFocusRight() {
        if (keyGrid.isEmpty()) return
        val row = keyGrid[focusedRow]
        var next = focusedCol + 1
        while (next < row.size && row[next].def.output == "BLANK") next++
        if (next < row.size) { focusedCol = next; invalidate() }
    }

    val rowCount: Int get() = keyGrid.size

    fun moveFocusUp(): Boolean {
        if (keyGrid.isEmpty() || focusedRow <= 0) return false
        val cx = keyGrid[focusedRow].getOrNull(focusedCol)?.rect?.centerX() ?: return false
        focusedRow--
        focusedCol = closestColInRow(focusedRow, cx)
        invalidate()
        return true
    }

    fun moveFocusDown(): Boolean {
        if (keyGrid.isEmpty() || focusedRow >= keyGrid.lastIndex) return false
        val cx = keyGrid[focusedRow].getOrNull(focusedCol)?.rect?.centerX() ?: return false
        focusedRow++
        focusedCol = closestColInRow(focusedRow, cx)
        invalidate()
        return true
    }

    private fun closestColInRow(row: Int, sourceCenterX: Float): Int {
        val targetRow = keyGrid[row]
        var best = 0
        var bestDist = Float.MAX_VALUE
        for ((i, kr) in targetRow.withIndex()) {
            if (kr.def.output == "BLANK") continue
            val dist = Math.abs(kr.rect.centerX() - sourceCenterX)
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

    companion object {
        private fun kd(ch: String) = KeyDef(ch, ch)

        private val SHIFT_MAP = mapOf(
            "1" to "!", "2" to "@", "3" to "#", "4" to "$",
            "5" to "%", "6" to "^", "7" to "&", "8" to "*",
            "9" to "(", "0" to ")", "-" to "_", "=" to "+"
        )

        private val LATIN_ROWS = listOf(
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

        private val ACCENTED_ROWS = listOf(
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

        private val KATAKANA_ROWS = listOf(
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

        private val SYMBOL_ROWS = listOf(
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

        private val EMOTICON_ROWS = listOf(
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
