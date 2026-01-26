package com.techted89.gameex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

data class MemoryResult(val address: Long, val value: String)

class MemoryResultAdapter(
    private var results: List<MemoryResult> = emptyList()
) : RecyclerView.Adapter<MemoryResultAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textAddress: TextView = view.findViewById(R.id.text_address)
        val textValue: TextView = view.findViewById(R.id.text_value)
        val btnCopy: ImageView = view.findViewById(R.id.btn_copy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_memory_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        holder.textAddress.text = "0x%08X".format(item.address)
        holder.textValue.text = item.value

        holder.btnCopy.setOnClickListener {
            val context = holder.itemView.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Memory Value", item.value)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Value copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = results.size

    fun updateData(newResults: List<MemoryResult>) {
        results = newResults
        notifyDataSetChanged()
    }
}
