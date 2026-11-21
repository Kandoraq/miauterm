package salu.Kandoraq.Miauterm.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import salu.Kandoraq.Miauterm.databinding.ItemSessionBinding
import salu.Kandoraq.Miauterm.view.Chrome

class SessionAdapter(
    private val sessionManager: SessionManager,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onRename: (Int) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val isActive = position == sessionManager.currentSessionIndex
        val binding = holder.binding
        val p = Chrome.palette

        val session = synchronized(sessionManager.sessions) { sessionManager.sessions.getOrNull(position) }
        binding.sessionLabelText.text = session?.sessionName?.takeIf { it.isNotBlank() }
            ?: holder.itemView.context.getString(salu.Kandoraq.Miauterm.R.string.session_label, position)

        binding.sessionItemRoot.background = Chrome.flatPressable(
            if (isActive) p.raised else p.bar,
            p.raisedHigh
        )
        binding.sessionLabelText.setTextColor(p.text)

        binding.btnSessionClose.visibility = View.VISIBLE
        binding.btnSessionClose.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(p.danger, p.textDim)
        ))
        binding.btnSessionClose.background = null
        binding.btnSessionClose.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onClose(pos)
            }
        }

        binding.sessionItemRoot.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onSelect(pos)
            }
        }
        binding.sessionItemRoot.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRename(pos)
            true
        }
    }

    override fun getItemCount(): Int = sessionManager.sessionCount()
}
