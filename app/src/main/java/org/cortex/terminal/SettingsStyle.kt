package org.cortex.terminal

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
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
        fragment.setDivider(ColorDrawable(p.outline))
        fragment.setDividerHeight(1)
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
        val title = view.findViewById<TextView>(android.R.id.title)
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val switch = view.findViewById<View>(androidx.preference.R.id.switchWidget) as? SwitchCompat
        val icon = view.findViewById<ImageView>(androidx.preference.R.id.icon)
        val isCategory = summary == null && (icon == null || icon.visibility != View.VISIBLE)

        if (isCategory) {
            title?.setTextColor(p.textFaint)
            title?.textSize = 12f
            return
        }
        title?.setTextColor(forState(p.text))
        summary?.setTextColor(forState(p.textDim))
        icon?.imageTintList = ColorStateList.valueOf(p.textDim)
        if (switch != null) {
            val checked = intArrayOf(android.R.attr.state_checked)
            switch.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.accent, p.textFaint))
            switch.trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(Chrome.withAlpha(p.accent, 100), p.raisedHigh)
            )
        }
    }
}
