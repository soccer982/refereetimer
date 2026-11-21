package com.example.refereetimer.phone

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.example.refereetimer.shared.models.Team
import com.google.android.material.textfield.TextInputEditText

/**
 * [team] = team of the player who scored. For an OWN GOAL that's the defender's team
 * (the goal counts for [opponent]) and the assist is an opposing player, shown with "*".
 */
class GoalEntryDialogFragment(
    private val team: Team,
    private val opponent: Team?,
    private val onGoalConfirmed: (scorer: Int, assist: Int?, isOG: Boolean, isPK: Boolean) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Header identifies which team was clicked on the dashboard
        layout.addView(TextView(context).apply {
            text = "Logging goal for: ${team.label}"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        val ogCheck = CheckBox(context).apply { text = "Own goal (OG): scored into their own net" }
        val pkCheck = CheckBox(context).apply { text = "Penalty kick (PK)" }

        val scorerInput = TextInputEditText(context).apply {
            hint = "Scorer jersey #"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val scorerName = TextView(context).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }

        scorerInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val num = s.toString().toIntOrNull() ?: return
                scorerName.text = team.getPlayer(num)?.name ?: ""
            }
        })

        val assistInput = TextInputEditText(context).apply {
            hint = "Assist jersey # (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val assistName = TextView(context).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }

        assistInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val num = s.toString().toIntOrNull() ?: run { assistName.text = ""; return }
                assistName.text = if (ogCheck.isChecked) "${opponent?.getPlayer(num)?.name ?: ""} (opponent*)"
                    else team.getPlayer(num)?.name ?: ""
            }
        })

        layout.addView(pkCheck)
        layout.addView(ogCheck)
        val assistLabel = TextView(context).apply { text = "Assist # (optional)" }
        val ogKey = TextView(context).apply {
            text = "* Own goal: the assist is a player on the OPPOSING team"
            textSize = 11f; visibility = android.view.View.GONE
        }
        // PK and OG can't both apply; an own goal's assist comes from the other team
        pkCheck.setOnCheckedChangeListener { _, on -> if (on) ogCheck.isChecked = false; assistInput.isEnabled = !on }
        ogCheck.setOnCheckedChangeListener { _, on ->
            if (on) pkCheck.isChecked = false
            assistLabel.text = if (on) "Assist # (opponent)*" else "Assist # (optional)"
            ogKey.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            assistInput.text = assistInput.text   // refresh the name shown
        }
        layout.addView(TextView(context).apply { text = "Scorer #" })
        layout.addView(scorerInput)
        layout.addView(scorerName)
        layout.addView(assistLabel)
        layout.addView(assistInput)
        layout.addView(assistName)
        layout.addView(ogKey)

        return AlertDialog.Builder(context).setTitle("⚽ Log Goal").setView(layout)
            .setPositiveButton("CONFIRM GOAL") { _, _ ->
                val scorer = scorerInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                val assist = if (pkCheck.isChecked) null else assistInput.text.toString().toIntOrNull()
                onGoalConfirmed(scorer, assist, ogCheck.isChecked, pkCheck.isChecked)
            }.setNegativeButton("Cancel", null).create()
    }
}