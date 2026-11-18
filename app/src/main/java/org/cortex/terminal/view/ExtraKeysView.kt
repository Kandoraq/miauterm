package org.cortex.terminal.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatStarter: Runnable? = null
    private var repeatTicker: Runnable? = null
    private var repeatArmedView: View? = null

    companion object {
        private const val TAG = "ExtraKeysView"
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
        private const val LATCH_HOLD_MS = 500L

        const val ROW_HEIGHT_DP = 36

        const val PADDING_DP = 0

        fun barHeightDp(rows: Int): Int = rows * ROW_HEIGHT_DP + 2 * PADDING_DP
    }

    private class KeyButton(
        val label: String,
        val repeats: Boolean,
        val action: () -> Any?,
        val modifier: ExtraKeysLayout.Special? = null,
        val special: ExtraKeysLayout.Special? = null
    )

    var terminalView: TerminalView? = null
        set(value) {
            field = value
            value?.onModifiersChanged = {
                post { updateModifierStyles() }
            }
            updateModifierStyles()
        }

    internal var keySequenceSink: ((Int) -> Unit)? = null

    var onMenuClick: (() -> Unit)? = null

    private var ctrlButton: Button? = null
    private var altButton: Button? = null
    private var shiftButton: Button? = null

    private val keyButtons = mutableListOf<Button>()

    private var latchedKey: Pair<Button, Int>? = null
    private val latchStarters = mutableMapOf<Button, Runnable>()
    private val keyRows = mutableListOf<Pair<LinearLayout, List<Button>>>()
    private var swipeRow: HorizontalScrollView? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeLastX = 0f
    private var horizontalSwipeActive = false

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    var rowCount = 0
        private set

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(Chrome.palette.bar)
        setPadding(0, 0, 0, 0)
        setKeys(ExtraKeysLayout.DEFAULT_ROWS)
    }

    fun setKeys(rows: List<List<ExtraKeysLayout.Key>>) {
        cancelRepeat()
        cancelPendingLatches()
        removeAllViews()
        keyButtons.clear()
        keyRows.clear()
        ctrlButton = null
        altButton = null
        shiftButton = null
        latchedKey = null
        for (row in rows) {
            val visibleKeys = row.filterNot {
                it is ExtraKeysLayout.SpecialKey && it.special == ExtraKeysLayout.Special.FIND
            }
            if (visibleKeys.isNotEmpty()) {
                addView(createRow(visibleKeys.map { key ->
                    KeyButton(key.label, keyRepeats(key), actionFor(key), modifierOf(key), specialOf(key))
                }))
            }
        }
        rowCount = keyRows.size
        updateRowWidths(width)
        updateModifierStyles()
    }

    fun scrollLastRowToEnd() {
        val lastRow = getChildAt(childCount - 1) as? HorizontalScrollView ?: return
        lastRow.post { lastRow.fullScroll(View.FOCUS_RIGHT) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRowWidths(w)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeRow = (0 until childCount)
                    .map { getChildAt(it) }
                    .filterIsInstance<HorizontalScrollView>()
                    .firstOrNull { event.y >= it.top && event.y < it.bottom }
                swipeDownX = event.x
                swipeDownY = event.y
                swipeLastX = event.x
                horizontalSwipeActive = false
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val row = swipeRow
                if (row != null) {
                    val distanceX = kotlin.math.abs(event.x - swipeDownX)
                    val distanceY = kotlin.math.abs(event.y - swipeDownY)
                    if (!horizontalSwipeActive &&
                        distanceX > ViewConfiguration.get(context).scaledTouchSlop &&
                        distanceX > distanceY
                    ) {
                        horizontalSwipeActive = true
                        val cancel = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        try {
                            super.dispatchTouchEvent(cancel)
                        } finally {
                            cancel.recycle()
                        }
                    }
                    if (horizontalSwipeActive) {
                        row.scrollBy((swipeLastX - event.x).toInt(), 0)
                        swipeLastX = event.x
                        return true
                    }
                }
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (horizontalSwipeActive) {
                    swipeRow = null
                    horizontalSwipeActive = false
                    return true
                }
                swipeRow = null
                return super.dispatchTouchEvent(event)
            }
        }
        return if (horizontalSwipeActive) true else super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) {
            updateRowWidths(View.MeasureSpec.getSize(widthMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateRowWidths(viewWidth: Int) {
        val availableWidth = viewWidth - paddingLeft - paddingRight
        if (availableWidth <= 0) return

        val marginPx = 0
        val minKeyWidth = dp(40f).toInt()
        keyRows.forEach { (row, buttons) ->
            if (buttons.isEmpty()) return@forEach
            val marginsWidth = buttons.size * marginPx * 2
            val rowWidth = maxOf(availableWidth, buttons.size * minKeyWidth + marginsWidth)
            if (row.layoutParams.width != rowWidth) {
                row.layoutParams = row.layoutParams.apply { width = rowWidth }
            }

            val buttonSpace = rowWidth - marginsWidth
            val baseButtonWidth = buttonSpace / buttons.size
            val remainder = buttonSpace % buttons.size
            buttons.forEachIndexed { index, button ->
                val buttonWidth = baseButtonWidth + if (index < remainder) 1 else 0
                val params = button.layoutParams as LayoutParams
                if (params.width != buttonWidth) {
                    params.width = buttonWidth
                    button.layoutParams = params
                }
            }
        }
    }

    private fun keyRepeats(key: ExtraKeysLayout.Key) =
        key is ExtraKeysLayout.SpecialKey && key.special.repeats

    private fun modifierOf(key: ExtraKeysLayout.Key) =
        (key as? ExtraKeysLayout.SpecialKey)?.special?.takeIf {
            it == ExtraKeysLayout.Special.CTRL || it == ExtraKeysLayout.Special.ALT || it == ExtraKeysLayout.Special.SHIFT
        }

    private fun specialOf(key: ExtraKeysLayout.Key) = (key as? ExtraKeysLayout.SpecialKey)?.special

    private fun actionFor(key: ExtraKeysLayout.Key): () -> Any? {
        val action: () -> Any? = when (key) {
            is ExtraKeysLayout.TextKey -> {
                val text = key.text
                if (text.length == 1) {
                    { terminalView?.sendChar(text[0]) }
                } else {
                    { terminalView?.sendText(text) }
                }
            }
            is ExtraKeysLayout.SpecialKey -> when (key.special) {
                ExtraKeysLayout.Special.CTRL -> { { toggleCtrl() } }
                ExtraKeysLayout.Special.ALT -> { { toggleAlt() } }
                ExtraKeysLayout.Special.SHIFT -> { { toggleShift() } }
                ExtraKeysLayout.Special.MENU -> { { onMenuClick?.invoke() } }
                ExtraKeysLayout.Special.FIND -> { {} }
                ExtraKeysLayout.Special.PASTE -> { { terminalView?.pasteFromClipboard() } }
                ExtraKeysLayout.Special.KEYBOARD -> { { terminalView?.toggleKeyboard() } }
                ExtraKeysLayout.Special.SPACE -> { { terminalView?.sendChar(' ') } }
                else -> {
                    val keyCode = keyCodeOf(key.special)
                    val fallback: () -> Any? = { terminalView?.sendKeySequence(keyCode) }
                    fallback
                }
            }
        }
        return action
    }

    private fun keyCodeOf(special: ExtraKeysLayout.Special): Int = when (special) {
        ExtraKeysLayout.Special.ESC -> KeyEvent.KEYCODE_ESCAPE
        ExtraKeysLayout.Special.TAB -> KeyEvent.KEYCODE_TAB
        ExtraKeysLayout.Special.UP -> KeyEvent.KEYCODE_DPAD_UP
        ExtraKeysLayout.Special.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        ExtraKeysLayout.Special.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        ExtraKeysLayout.Special.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        ExtraKeysLayout.Special.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        ExtraKeysLayout.Special.END -> KeyEvent.KEYCODE_MOVE_END
        ExtraKeysLayout.Special.PGUP -> KeyEvent.KEYCODE_PAGE_UP
        ExtraKeysLayout.Special.PGDN -> KeyEvent.KEYCODE_PAGE_DOWN
        ExtraKeysLayout.Special.INS -> KeyEvent.KEYCODE_INSERT
        ExtraKeysLayout.Special.DEL -> KeyEvent.KEYCODE_FORWARD_DEL
        ExtraKeysLayout.Special.BKSP -> KeyEvent.KEYCODE_DEL
        ExtraKeysLayout.Special.ENTER -> KeyEvent.KEYCODE_ENTER
        ExtraKeysLayout.Special.SPACE -> KeyEvent.KEYCODE_SPACE
        else -> KeyEvent.KEYCODE_F1 + (special.ordinal - ExtraKeysLayout.Special.F1.ordinal)
    }

    private fun dispatchKeySequence(keyCode: Int) {
        val sink = keySequenceSink
        if (sink != null) sink(keyCode) else terminalView?.sendKeySequence(keyCode)
    }

    private fun latchKey(button: Button, keyCode: Int) {
        val current = latchedKey
        when {
            current != null && current.first === button -> {
                latchedKey = null
                styleKey(button, false)
            }
            else -> {
                current?.first?.let { styleKey(it, false) }
                latchedKey = button to keyCode
                styleKey(button, true)
            }
        }
        updateModifierStyles()
    }

    private fun flushLatchedFunction() {
        val latched = latchedKey ?: return
        latchedKey = null
        styleKey(latched.first, false)
        dispatchKeySequence(latched.second)
    }

    private fun cancelPendingLatches() {
        latchStarters.values.forEach { starter -> repeatHandler.removeCallbacks(starter) }
        latchStarters.clear()
    }

    private fun cancelPendingLatch(button: Button) {
        latchStarters.remove(button)?.let { starter -> repeatHandler.removeCallbacks(starter) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createRow(keys: List<KeyButton>): View {
        val rowLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                (ROW_HEIGHT_DP * resources.displayMetrics.density).toInt()
            )
        }

        val marginPx = 0
        val buttons = mutableListOf<Button>()

        for (key in keys) {
            val label = key.label
            val action = key.action
            val btn = Button(context).apply {
                text = label
                textSize = when { label.length > 5 -> 10f; label.length > 3 -> 11f; else -> 13f }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                styleKey(this, false)
                isAllCaps = false
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
                minWidth = 0
                minHeight = 0
                stateListAnimator = null

                val params = LayoutParams(dp(40f).toInt(), LayoutParams.MATCH_PARENT).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
                layoutParams = params

                val latchSpecial = key.special?.takeIf { special ->
                    special != ExtraKeysLayout.Special.CTRL && special != ExtraKeysLayout.Special.ALT &&
                        special != ExtraKeysLayout.Special.MENU && special != ExtraKeysLayout.Special.FIND &&
                        special != ExtraKeysLayout.Special.PASTE && special != ExtraKeysLayout.Special.KEYBOARD &&
                        special != ExtraKeysLayout.Special.SPACE && !special.repeats
                }
                if (latchSpecial != null) {
                    val keyReference = this
                    setOnClickListener {
                        val latched = latchedKey
                        when {
                            latched != null && latched.first === keyReference -> {
                                latchedKey = null
                                styleKey(keyReference, false)
                                updateModifierStyles()
                            }
                            else -> {
                                if (latched != null) {
                                    latchedKey = null
                                    styleKey(latched.first, false)
                                    updateModifierStyles()
                                }
                                dispatchKeySequence(keyCodeOf(latchSpecial))
                            }
                        }
                    }
                }
                if (latchSpecial == null) {
                    setOnClickListener {
                        try { action() } catch (e: Exception) { Log.w(TAG, "Extra key ${key.label} failed: ${e.message}") }
                    }
                }
                val flashesAccent = !key.repeats
                val latchable = latchSpecial != null
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                var downX = 0f
                var downY = 0f
                var gestureMoved = false
                var repeatSentKey = false
                var latchArmed = false
                setOnTouchListener { v, event ->
                    val keyButton = v as Button
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            gestureMoved = false
                            repeatSentKey = false
                            latchArmed = false
                            v.isPressed = true
                            if (flashesAccent) styleKey(keyButton, true)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            cancelRepeat()
                            if (key.repeats) {
                                armRepeat(v) {
                                    flushLatchedFunction()
                                    repeatSentKey = true
                                    action()
                                }
                            }
                            if (latchSpecial != null) {
                                val keyCode = keyCodeOf(latchSpecial)
                                val starter = Runnable {
                                    if (latchStarters.remove(keyButton) == null) return@Runnable
                                    latchArmed = true
                                    latchKey(keyButton, keyCode)
                                }
                                latchStarters[keyButton] = starter
                                try { repeatHandler.postDelayed(starter, LATCH_HOLD_MS) } catch (_: Exception) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val distanceX = kotlin.math.abs(event.x - downX)
                            val distanceY = kotlin.math.abs(event.y - downY)
                            if (distanceX > touchSlop || distanceY > touchSlop) {
                                gestureMoved = true
                                cancelRepeat()
                                cancelPendingLatch(keyButton)
                                v.isPressed = false
                                if (distanceX > touchSlop && distanceX > distanceY && flashesAccent) {
                                    styleKey(keyButton, false)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            cancelPendingLatch(keyButton)
                            v.isPressed = false
                            if (flashesAccent) styleKey(keyButton, false)
                            updateModifierStyles()
                            if (!gestureMoved && !repeatSentKey) {
                                if (!latchable) flushLatchedFunction()
                                if (!latchArmed) v.performClick()
                            } else if (repeatSentKey) {
                                flushLatchedFunction()
                            }
                            cancelRepeat()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            gestureMoved = true
                            cancelPendingLatch(keyButton)
                            v.isPressed = false
                            if (flashesAccent) styleKey(keyButton, false)
                            updateModifierStyles()
                            cancelRepeat()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            true
                        }
                        else -> false
                    }
                }
            }

            if (key.modifier == ExtraKeysLayout.Special.CTRL) ctrlButton = btn
            if (key.modifier == ExtraKeysLayout.Special.ALT) altButton = btn
            if (key.modifier == ExtraKeysLayout.Special.SHIFT) shiftButton = btn

            keyButtons.add(btn)
            buttons.add(btn)
            rowLayout.addView(btn)
        }

        keyRows.add(rowLayout to buttons)
        return HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (ROW_HEIGHT_DP * resources.displayMetrics.density).toInt())
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(rowLayout, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun armRepeat(v: View, action: () -> Any?) {
        cancelRepeat()
        repeatArmedView = v
        val starter = Runnable {
            repeatStarter = null
            if (repeatArmedView !== v) return@Runnable
            val tick = object : Runnable {
                override fun run() {
                    if (repeatArmedView !== v) return
                    try { action() } catch (e: Exception) { Log.w(TAG, "Held extra key repeat failed: ${e.message}") }
                    repeatTicker = this
                    try { repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS) } catch (_: Exception) {}
                }
            }
            repeatTicker = tick
            try { repeatHandler.postDelayed(tick, REPEAT_INTERVAL_MS) } catch (_: Exception) {}
        }
        repeatStarter = starter
        try { repeatHandler.postDelayed(starter, REPEAT_START_MS) } catch (_: Exception) {}
    }

    private fun cancelRepeat() {
        repeatArmedView = null
        repeatStarter?.let { s ->
            try { repeatHandler.removeCallbacks(s) } catch (_: Exception) {}
        }
        repeatStarter = null
        repeatTicker?.let { t ->
            try { repeatHandler.removeCallbacks(t) } catch (_: Exception) {}
        }
        repeatTicker = null
    }

    private fun toggleCtrl() {
        val view = terminalView ?: return
        view.isCtrlPressed = !view.isCtrlPressed
        updateModifierStyles()
    }

    private fun toggleAlt() {
        val view = terminalView ?: return
        view.isAltPressed = !view.isAltPressed
        updateModifierStyles()
    }

    private fun toggleShift() {
        val view = terminalView ?: return
        view.isShiftPressed = !view.isShiftPressed
        updateModifierStyles()
    }

    private fun styleKey(btn: Button, on: Boolean) {
        val p = Chrome.palette
        btn.background = Chrome.flatPressable(
            if (on) p.accent else p.raised,
            if (on) Chrome.mix(p.accent, p.fg, 0.18f) else p.raisedHigh
        )
        btn.elevation = 0f
        btn.translationZ = 0f
        btn.setTextColor(if (on) p.onAccent else p.text)
    }

    fun applyPalette() {
        setBackgroundColor(Chrome.palette.bar)
        for (btn in keyButtons) styleKey(btn, false)
        updateModifierStyles()
    }

    fun updateModifierStyles() {
        latchedKey?.let { (btn, _) -> styleKey(btn, true) }

        val view = terminalView ?: return
        ctrlButton?.let { btn -> styleKey(btn, view.isCtrlPressed) }
        altButton?.let { btn -> styleKey(btn, view.isAltPressed) }
        shiftButton?.let { btn -> styleKey(btn, view.isShiftPressed) }
    }

    fun clearLatch() {
        latchedKey?.let { styleKey(it.first, false) }
        latchedKey = null
        cancelPendingLatches()
        terminalView?.isShiftPressed = false
        updateModifierStyles()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRepeat()
        clearLatch()
    }
}
