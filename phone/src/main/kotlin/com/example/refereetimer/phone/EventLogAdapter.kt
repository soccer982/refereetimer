package com.example.refereetimer.phone

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class EventLogAdapter : ListAdapter<String, EventLogAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(a: String, b: String) = a == b
        override fun areContentsTheSame(a: String, b: String) = a == b
    }
) {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.text.text     = item
        holder.text.textSize = 13f
        holder.text.setTextColor(when {
            item.startsWith("⚽") -> Color.parseColor("#4CAF50")
            item.startsWith("🟥") -> Color.parseColor("#F44336")
            item.startsWith("🟨") -> Color.parseColor("#FFC107")
            item.startsWith("🚩") -> Color.parseColor("#FF9800")
            else                  -> Color.WHITE
        })
        holder.text.setPadding(16, 8, 16, 8)
    }
}
