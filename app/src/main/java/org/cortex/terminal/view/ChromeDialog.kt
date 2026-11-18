package org.cortex.terminal.view

import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object ChromeDialog {

    fun style(dialog: AlertDialog, onShow: () -> Unit = {}): AlertDialog {
        val p = Chrome.palette
        val density = dialog.context.resources.displayMetrics.density
        dialog.window?.setBackgroundDrawable(
            InsetDrawable(ColorDrawable(p.bar), 0)
        )
        val applyColors = {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                setTextColor(p.text)
                setTypeface(null, android.graphics.Typeface.BOLD)
                textSize = 17f
            }
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(p.textDim)
            val pad = (10 * density).toInt()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
                background = Chrome.flatPressable(p.accent, Chrome.mix(p.accent, p.fg, 0.18f))
                setTextColor(p.onAccent)
                setPadding(pad * 2, pad, pad * 2, pad)
            }
            for (which in intArrayOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
                dialog.getButton(which)?.apply {
                    background = Chrome.flatPressable(p.raised, p.raisedHigh)
                    setTextColor(p.text)
                    setPadding(pad * 2, pad, pad * 2, pad)
                }
            }
        }
        if (dialog.isShowing) {
            applyColors()
            onShow()
        } else {
            dialog.setOnShowListener {
                applyColors()
                onShow()
            }
        }
        return dialog
    }
}

fun AlertDialog.Builder.showInChrome(): AlertDialog = ChromeDialog.style(create()).also { it.show() }
