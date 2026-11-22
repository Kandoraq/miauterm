package salu.Kandoraq.miauterm

import salu.Kandoraq.Miauterm.R
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import salu.Kandoraq.miauterm.view.Chrome

class BootstrapDialog(activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val view: View = activity.layoutInflater.inflate(R.layout.dialog_bootstrap, null)
    private val bar: ProgressBar = view.findViewById(R.id.bootstrapBar)
    private val percent: TextView = view.findViewById(R.id.bootstrapPercent)
    private val dialog: AlertDialog

    init {
        val p = Chrome.palette
        view.background = ColorDrawable(p.bar)
        view.findViewById<TextView>(R.id.bootstrapTitle).setTextColor(p.text)
        view.findViewById<TextView>(R.id.bootstrapMessage).setTextColor(p.textDim)
        percent.setTextColor(p.textDim)
        bar.progressTintList = ColorStateList.valueOf(p.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(p.raisedHigh)
        bar.indeterminateTintList = ColorStateList.valueOf(p.accent)
        bar.isIndeterminate = false
        bar.max = 100
        bar.progress = 0

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(p.bar))
    }

    var isIndeterminate: Boolean
        get() = bar.isIndeterminate
        set(value) {
            bar.isIndeterminate = value
            percent.visibility = if (value) View.INVISIBLE else View.VISIBLE
        }

    var progress: Int
        get() = bar.progress
        set(value) {
            bar.progress = value
            percent.text = "$value%"
        }

    val isShowing: Boolean get() = dialog.isShowing

    fun show() {
        bar.max = 100
        bar.progress = 0
        percent.visibility = View.VISIBLE
        percent.text = "0%"
        dialog.show()
    }

    fun postUpdate(value: Int) {
        mainHandler.post {
            bar.isIndeterminate = false
            bar.progress = value.coerceIn(0, 100)
            percent.visibility = View.VISIBLE
            percent.text = "$value%"
        }
    }

    fun dismiss() = dialog.dismiss()

    fun setOnCancel(listener: () -> Unit) {
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, view.context.getString(android.R.string.cancel)) { _, _ ->
            listener()
        }
    }
}
