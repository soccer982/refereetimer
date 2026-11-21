package com.example.refereetimer.phone

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.refereetimer.shared.models.Player

class RosterReviewAdapter(
    private val onPlayerEdited: (Int, Player) -> Unit
) : ListAdapter<Player, RosterReviewAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<Player>() {
        override fun areItemsTheSame(a: Player, b: Player) = a.jerseyNumber == b.jerseyNumber
        override fun areContentsTheSame(a: Player, b: Player) = a == b
    }
) {
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtNumber: TextView = view.findViewById(android.R.id.text1)
        val txtName:   TextView = view.findViewById(android.R.id.text2)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val player = getItem(position)
        holder.txtNumber.text = "#${player.jerseyNumber}"
        holder.txtName.text   = player.name
        val color = if (player.flagged) Color.parseColor("#FF9800") else Color.parseColor("#4CAF50")
        holder.txtNumber.setTextColor(color)
        holder.txtName.setTextColor(if (player.flagged) Color.parseColor("#FF9800") else Color.parseColor("#212121"))
        if (player.flagged) {
            holder.itemView.setOnClickListener { showEditDialog(holder.itemView, position, player) }
        }
    }
    private fun showEditDialog(anchor: View, position: Int, player: Player) {
        val context    = anchor.context
        val layout     = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val numberEdit = EditText(context).apply { hint = "Jersey Number"; setText(player.jerseyNumber.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val nameEdit   = EditText(context).apply { hint = "Player Name";   setText(player.name) }
        layout.addView(numberEdit); layout.addView(nameEdit)
        android.app.AlertDialog.Builder(context)
            .setTitle("Edit Player #${player.jerseyNumber}").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newNum  = numberEdit.text.toString().toIntOrNull() ?: player.jerseyNumber
                val newName = nameEdit.text.toString().ifBlank { player.name }
                onPlayerEdited(position, Player(newNum, newName, flagged = false))
            }.setNegativeButton("Cancel", null).show()
    }
}
