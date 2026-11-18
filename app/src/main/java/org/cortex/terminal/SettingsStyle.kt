package org.cortex.terminal

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.view.Chrome

internal class SettingsStyle(private val fragment: PreferenceFragmentCompat) {

    fun install() {
        val p = Chrome.palette
        fragment.setDivider(null)
        fragment.setDividerHeight(0)
        fragment.listView.setBackgroundColor(p.bg)
        fragment.listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = style(view)
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        for (index in 0 until fragment.listView.childCount) {
            style(fragment.listView.getChildAt(index))
        }
    }

    private fun forState(color: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Chrome.withAlpha(color, 110), color)
    )

    private fun style(view: View) {
        val p = Chrome.palette
        val density = view.resources.displayMetrics.density
        val title = view.findViewById<TextView>(android.R.id.title)
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val switch = view.findViewById<View>(androidx.preference.R.id.switchWidget) as? SwitchCompat
        val icon = view.findViewById<ImageView>(android.R.id.icon)
        val isCategory = summary == null && (icon == null || icon.visibility != View.VISIBLE)

        if (isCategory) {
            view.background = Chrome.flatPressable(p.bg, p.bg)
            title?.setTextColor(p.accent)
            title?.textSize = 13f
            title?.setTypeface(null, Typeface.BOLD)
            title?.setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            return
        }

        val padding = (14 * density).toInt()
        view.setPadding(padding, (12 * density).toInt(), padding, (12 * density).toInt())
        view.elevation = 0f
        view.background = Chrome.flatPressable(p.bg, p.raised)

        title?.setTextColor(forState(p.text))
        title?.textSize = 15f
        title?.setTypeface(null, Typeface.BOLD)
        summary?.setTextColor(forState(p.textDim))
        summary?.textSize = 13f
        icon?.imageTintList = ColorStateList.valueOf(p.accent)
        icon?.layoutParams?.let { params ->
            params.width = (24 * density).toInt()
            params.height = (24 * density).toInt()
            icon.layoutParams = params
        }
        if (switch != null) {
            val checked = intArrayOf(android.R.attr.state_checked)
            val squareRadius = 2 * density
            switch.showText = false
            switch.thumbDrawable = Chrome.rounded(p.textDim, squareRadius).apply {
                setSize((18 * density).toInt(), (18 * density).toInt())
            }
            switch.trackDrawable = Chrome.rounded(p.outline, squareRadius).apply {
                setSize((38 * density).toInt(), (20 * density).toInt())
            }
            switch.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.accent, p.textDim))
            switch.trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(p.accentSoft, p.raisedHigh)
            )
        }
    }
}
