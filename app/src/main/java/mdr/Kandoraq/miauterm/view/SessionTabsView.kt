package salu.Kandoraq.Miauterm.view

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import salu.Kandoraq.Miauterm.R

class SessionTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    class Tab(val title: String)

    var onSelect: ((Int) -> Unit)? = null
    var onClose: ((Int) -> Unit)? = null
    var onNew: (() -> Unit)? = null
    var onRename: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scroll = HorizontalScrollView(context)
    private val strip = LinearLayout(context)
    private val newButton = TextView(context)
    private var tabs: List<Tab> = emptyList()
    private var active = -1
    private var canAddSessions = true

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setPadding(dp(6), 0, dp(2), 0)
        scroll.addView(strip, android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        newButton.text = "+"
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        newButton.typeface = Typeface.MONOSPACE
        newButton.gravity = Gravity.CENTER
        newButton.contentDescription = context.getString(R.string.drawer_new_session)
        newButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onNew?.invoke()
        }
        addView(newButton, LayoutParams(dp(46), LayoutParams.MATCH_PARENT))
        applyPalette()
    }

    fun update(newTabs: List<Tab>, activeIndex: Int, canAdd: Boolean = true) {
        val unchanged = activeIndex == active && newTabs.size == tabs.size &&
            canAdd == canAddSessions && newTabs.indices.all { newTabs[it].title == tabs[it].title }
        tabs = newTabs
        canAddSessions = canAdd
        val activeChanged = activeIndex != active
        active = activeIndex
        if (unchanged) return
        newButton.visibility = if (canAddSessions) View.VISIBLE else View.GONE
        rebuild()
        if (activeChanged) scrollToActive()
    }

    fun applyPalette() {
        val p = Chrome.palette
        val scrollX = scroll.scrollX
        setBackgroundColor(p.bar)
        newButton.setTextColor(p.accent)
        newButton.background = null
        newButton.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(p.accent, p.textDim)
        ))
        rebuild()
        scroll.post { scroll.scrollTo(scrollX, 0) }
    }

    private fun scrollToActive() {
        strip.post {
            val v = strip.getChildAt(active) ?: return@post
            scroll.smoothScrollTo(maxOf(0, v.left - dp(28)), 0)
        }
    }

    private fun rebuild() {
        strip.removeAllViews()
        for ((index, tab) in tabs.withIndex()) {
            strip.addView(tabView(index, tab, index == active))
        }
    }

    private fun text(label: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun tabView(index: Int, tab: Tab, isActive: Boolean): View {
        val p = Chrome.palette
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(if (isActive) 3 else 10), 0)
            background = Chrome.flatPressable(if (isActive) p.raised else p.bar, p.raisedHigh)
            isClickable = true
            isFocusable = true
            contentDescription = tab.title
            setOnClickListener {
                if (index != active) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect?.invoke(index)
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onRename?.invoke(index)
                true
            }
        }
        row.addView(text(tab.title, 12f, if (isActive) p.accent else p.textDim, false))
        if (isActive) {
            val close = text("✕", 12f, p.textDim, false).apply {
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.close_session)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClose?.invoke(index)
                }
                background = null
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(p.danger, p.textDim)
                ))
            }
            row.addView(close, LayoutParams(dp(30), dp(26)).apply { marginStart = dp(2) })
        }
        row.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        return row
    }
}
