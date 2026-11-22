package com.example.refereetimer.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.example.refereetimer.shared.models.Player

/**
 * Roster review list. Each row shows number, name, player ID / DOB / CPP, and, in orange,
 * anything that needs a second look (no number, duplicate number or ID, misread text).
 * Tap any row to fix it or delete it. [onPlayerEdited] gets null when the row is deleted.
 */
class RosterReviewAdapter(
    private val onPlayerEdited: (Int, Player?) -> Unit
) : RecyclerView.Adapter<RosterReviewAdapter.ViewHolder>() {

    private var players: List<Player> = emptyList()
    private var issues: Map<Int, List<String>> = emptyMap()

    fun show(list: List<Player>, problems: Map<Int, List<String>>) {
        players = list.toList(); issues = problems
        notifyDataSetChanged()
    }

    /** Kept for older callers: shows the list without problem notes. */
    fun submitList(list: List<Player>) = show(list, emptyMap())

    class ViewHolder(val row: LinearLayout, val number: TextView, val name: TextView, val detail: TextView, val problem: TextView) :
        RecyclerView.ViewHolder(row)

    override fun getItemCount() = players.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val c = parent.context
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            val tv = TypedValue(); c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val number = TextView(c).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; minWidth = dp(52) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(c).apply { textSize = 15f; setTextColor(Color.WHITE) }
        val detail = TextView(c).apply { textSize = 12f; setTextColor(Color.parseColor("#9E9E9E")) }
        val problem = TextView(c).apply { textSize = 12f; setTextColor(Color.parseColor("#FF9800")) }
        col.addView(name); col.addView(detail); col.addView(problem)
        row.addView(number); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return ViewHolder(row, number, name, detail, problem)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = players[position]
        val problems = issues[position].orEmpty()
        holder.number.text = p.tag
        holder.number.setTextColor(Color.parseColor(if (problems.isNotEmpty()) "#FF9800" else "#4CAF50"))
        holder.name.text = p.name
        holder.detail.text = listOfNotNull(
            p.playerId.takeIf { it.isNotBlank() }?.let { "ID $it" },
            p.dob.takeIf { it.isNotBlank() }?.let { "DOB $it" },
            if (p.cpp) "CPP" else null
        ).joinToString("  ·  ")
        holder.detail.visibility = if (holder.detail.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        holder.problem.text = problems.joinToString(" · ") { "⚠ $it" }
        holder.problem.visibility = if (problems.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        holder.row.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) showEditDialog(holder.row.context, pos, players[pos])
        }
    }

    private fun showEditDialog(context: Context, position: Int, player: Player) {
        editPlayerDialog(context, "Edit ${player.tag} ${player.name}", player, allowDelete = true) { onPlayerEdited(position, it) }
    }

    companion object {
        /**
         * Edit (or add) one player. Leave the number blank if the card has none.
         * [onDone] gets the new player, or null if "Delete" was pressed.
         */
        fun editPlayerDialog(context: Context, title: String, player: Player?, allowDelete: Boolean, onDone: (Player?) -> Unit) {
            fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
            val numberEdit = EditText(context).apply {
                hint = "Jersey # (blank if none)"; inputType = InputType.TYPE_CLASS_NUMBER
                setText(player?.takeIf { it.hasNumber }?.jerseyNumber?.toString() ?: "")
            }
            val nameEdit = EditText(context).apply {
                hint = "Player name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(player?.name ?: "")
            }
            val idEdit = EditText(context).apply { hint = "Player ID (optional)"; setText(player?.playerId ?: "") }
            val dobEdit = EditText(context).apply { hint = "DOB MM/YY (optional)"; setText(player?.dob ?: "") }
            val cppBox = CheckBox(context).apply { text = "Club pass player (CPP)"; isChecked = player?.cpp == true }
            listOf(numberEdit, nameEdit, idEdit, dobEdit, cppBox).forEach { layout.addView(it) }

            val b = AlertDialog.Builder(context).setTitle(title).setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val name = nameEdit.text.toString().trim()
                    if (name.isEmpty() && player == null) return@setPositiveButton
                    val num = numberEdit.text.toString().trim().toIntOrNull()?.takeIf { it in 0..99 } ?: Player.NO_NUMBER
                    onDone(Player(num, name.ifEmpty { player?.name ?: "" }, flagged = false,
                        playerId = idEdit.text.toString().trim(), dob = dobEdit.text.toString().trim(), cpp = cppBox.isChecked))
                }
                .setNegativeButton("Cancel", null)
            if (allowDelete) b.setNeutralButton("Delete") { _, _ -> onDone(null) }
            b.show()
        }
    }
}
