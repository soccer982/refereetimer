package com.example.refereetimer.phone

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.FoulType
import com.example.refereetimer.shared.models.Team
import com.google.android.material.textfield.TextInputEditText

class FoulEntryDialogFragment(
    private val team: Team,
    private val onFoulConfirmed: (player: Int, foulType: FoulType, isPK: Boolean, cardType: CardType?) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Header indicating which team is selected
        layout.addView(TextView(context).apply {
            text = "Logging foul for: ${team.label}"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        val playerInput = TextInputEditText(context).apply {
            hint = "Player jersey #"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val playerName = TextView(context).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }

        playerInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val num = s.toString().toIntOrNull() ?: return
                playerName.text = team.getPlayer(num)?.name ?: ""
            }
        })

        val foulGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val dfkBtn = RadioButton(context).apply { id = View.generateViewId(); text = "DFK"; isChecked = true }
        val ifkBtn = RadioButton(context).apply { id = View.generateViewId(); text = "IFK" }
        foulGroup.addView(dfkBtn); foulGroup.addView(ifkBtn)

        val pkCheck = CheckBox(context).apply { text = "Penalty Kick (PK)" }
        ifkBtn.setOnCheckedChangeListener { _, checked -> if (checked) { pkCheck.isChecked = false; pkCheck.isEnabled = false } }
        dfkBtn.setOnCheckedChangeListener { _, checked -> if (checked) pkCheck.isEnabled = true }

        val cardGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val noCardBtn = RadioButton(context).apply { id = View.generateViewId(); text = "None"; isChecked = true }
        val yellowBtn = RadioButton(context).apply { id = View.generateViewId(); text = "🟨 Yellow" }
        val redBtn    = RadioButton(context).apply { id = View.generateViewId(); text = "🟥 Red" }
        cardGroup.addView(noCardBtn); cardGroup.addView(yellowBtn); cardGroup.addView(redBtn)

        layout.addView(TextView(context).apply { text = "Player #" })
        layout.addView(playerInput)
        layout.addView(playerName)
        layout.addView(TextView(context).apply { text = "Foul Type" })
        layout.addView(foulGroup)
        layout.addView(pkCheck)
        layout.addView(TextView(context).apply { text = "Card" })
        layout.addView(cardGroup)

        return AlertDialog.Builder(context).setTitle("🚩 Log Foul").setView(layout)
            .setPositiveButton("CONFIRM FOUL") { _, _ ->
                val playerNum = playerInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                onFoulConfirmed(
                    playerNum,
                    if (dfkBtn.isChecked) FoulType.DFK else FoulType.IFK,
                    pkCheck.isChecked,
                    when { yellowBtn.isChecked -> CardType.YELLOW; redBtn.isChecked -> CardType.RED; else -> null }
                )
            }.setNegativeButton("Cancel", null).create()
    }
}