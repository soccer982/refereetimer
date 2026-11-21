package com.example.refereetimer.phone

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.example.refereetimer.shared.models.Team
import com.google.android.material.textfield.TextInputEditText

class GoalEntryDialogFragment(
    private val team: Team,
    private val onGoalConfirmed: (scorer: Int, assist: Int?, isOG: Boolean) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Header identifies which team was clicked on the dashboard
        layout.addView(TextView(context).apply {
            text = "Logging goal for: ${team.name}"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        val ogCheck = CheckBox(context).apply { text = "Own Goal (OG)" }

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
                assistName.text = team.getPlayer(num)?.name ?: ""
            }
        })

        layout.addView(ogCheck)
        layout.addView(TextView(context).apply { text = "Scorer #" })
        layout.addView(scorerInput)
        layout.addView(scorerName)
        layout.addView(TextView(context).apply { text = "Assist # (optional)" })
        layout.addView(assistInput)
        layout.addView(assistName)

        return AlertDialog.Builder(context).setTitle("⚽ Log Goal").setView(layout)
            .setPositiveButton("CONFIRM GOAL") { _, _ ->
                val scorer = scorerInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                onGoalConfirmed(scorer, assistInput.text.toString().toIntOrNull(), ogCheck.isChecked)
            }.setNegativeButton("Cancel", null).create()
    }
}