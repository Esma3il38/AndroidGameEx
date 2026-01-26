package com.techted89.gameex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ProcessInfo(val pid: Int, val name: String)

class ProcessAdapter(
    private val processes: List<ProcessInfo>,
    private val onClick: (ProcessInfo) -> Unit
) : RecyclerView.Adapter<ProcessAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_process_name)
        val pid: TextView = view.findViewById(R.id.text_pid)
        val icon: ImageView = view.findViewById(R.id.icon_process)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val process = processes[position]
        holder.name.text = process.name
        holder.pid.text = "PID: ${process.pid}"
        holder.itemView.setOnClickListener { onClick(process) }
    }

    override fun getItemCount() = processes.size
}
