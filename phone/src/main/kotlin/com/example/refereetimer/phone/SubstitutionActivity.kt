package com.example.refereetimer.phone

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.phone.databinding.ActivitySubstitutionBinding
import com.example.refereetimer.shared.SubstitutionCheck
import com.example.refereetimer.shared.SubstitutionRules
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.TeamTag

/**
 * NEW: substitutions, Home and Away side by side.
 * Each row = player coming ON and player coming OFF. Everything recorded with one tap
 * counts as ONE substitution moment per team (unless it's during an interval and the
 * rules say intervals are free). Players who aren't allowed back on are flagged as you type.
 */
class SubstitutionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubstitutionBinding
    private val viewModel by lazy { (application as PhoneApp).matchViewModel }
    private lateinit var rules: SubstitutionRules

    private data class Row(val isHome: Boolean, val view: LinearLayout, val on: EditText, val off: EditText, val status: TextView)
    private val rows = mutableListOf<Row>()

    private val ok = Color.parseColor("#A5D6A7")
    private val warn = Color.parseColor("#FFB74D")
    private val muted = Color.parseColor("#9E9E9E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubstitutionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        rules = SubRulesStore.load(this)
        binding.txtHomeName.text = TeamTag.label(viewModel.homeTeamName.value ?: "Home", true)
        binding.txtAwayName.text = TeamTag.label(viewModel.awayTeamName.value ?: "Away", false)
        binding.txtSubRules.text = rules.describe()

        binding.btnAddHome.setOnClickListener { addRow(true) }
        binding.btnAddAway.setOnClickListener { addRow(false) }
        binding.btnRecordSubs.setOnClickListener { record() }

        addRow(true); addRow(false)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun events(): List<MatchEvent> = viewModel.events.value.orEmpty()
    private fun checker(isHome: Boolean) = SubstitutionCheck(rules, events(), isHome)
    private fun usesMoment() = !(viewModel.subContext().duringInterval && rules.intervalsFree)

    // ── Rows ──

    private fun numberBox(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(android.text.InputFilter.LengthFilter(2))
        setTextColor(Color.WHITE); setHintTextColor(muted)
        textSize = 18f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun addRow(isHome: Boolean) {
        val on = numberBox("ON #")
        val off = numberBox("OFF #")
        val remove = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(40))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(on); addView(TextView(context).apply { text = "⇄"; setTextColor(muted); setPadding(dp(2), 0, dp(2), 0) })
            addView(off); addView(remove)
        }
        val status = TextView(this).apply { textSize = 11f; setTextColor(muted) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
            addView(line); addView(status)
        }
        val row = Row(isHome, box, on, off, status)
        rows.add(row)
        (if (isHome) binding.rowsHome else binding.rowsAway).addView(box)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshFlags() }
        }
        on.addTextChangedListener(watcher); off.addTextChangedListener(watcher)
        remove.setOnClickListener {
            rows.remove(row)
            (if (isHome) binding.rowsHome else binding.rowsAway).removeView(box)
            refreshFlags()
        }
        on.requestFocus()
        refreshFlags()
    }

    private fun num(e: EditText): Int? = e.text?.toString()?.trim()?.toIntOrNull()

    /** All problems for one row, considering what's already recorded and the other rows on screen. */
    private fun problemsFor(row: Row): List<String> {
        val on = num(row.on); val off = num(row.off)
        val c = checker(row.isHome)
        val period = viewModel.subContext().period
        val sameTeam = rows.filter { it.isHome == row.isHome && it !== row }
        val out = mutableListOf<String>()
        if (on != null) c.onProblem(on, period)?.let { out.add(it) }
        if (off != null) c.offProblem(off)?.let { out.add(it) }
        if (on != null && on == off) out.add("Same number on and off")
        if (on != null && sameTeam.any { num(it.on) == on }) out.add("#$on is coming on twice")
        if (off != null && sameTeam.any { num(it.off) == off }) out.add("#$off is going off twice")
        if (on != null && rules.reEntry != SubstitutionRules.ReEntry.ALLOWED && sameTeam.any { num(it.off) == on })
            out.add("#$on is going off in this same change")
        return out
    }

    private fun refreshFlags() {
        rows.forEach { row ->
            val on = num(row.on); val off = num(row.off)
            val problems = problemsFor(row)
            val team = (if (row.isHome) viewModel.homeTeam.value else viewModel.awayTeam.value)
            when {
                problems.isNotEmpty() -> {
                    row.status.text = "⚠ " + problems.joinToString("\n⚠ ")
                    row.status.setTextColor(warn)
                    row.on.setBackgroundColor(if (on != null && problems.any { it.startsWith("#$on") }) Color.parseColor("#4E2A00") else Color.TRANSPARENT)
                }
                on != null || off != null -> {
                    val onName = on?.let { team?.getPlayer(it)?.name } ?: ""
                    val offName = off?.let { team?.getPlayer(it)?.name } ?: ""
                    row.status.text = listOf(onName.takeIf { it.isNotBlank() }?.let { "▲ $it" },
                        offName.takeIf { it.isNotBlank() }?.let { "▼ $it" }).filterNotNull().joinToString("  ")
                    row.status.setTextColor(ok)
                    row.on.setBackgroundColor(Color.TRANSPARENT)
                }
                else -> { row.status.text = ""; row.on.setBackgroundColor(Color.TRANSPARENT) }
            }
        }
        refreshTallies()
    }

    // ── Tallies, context, history ──

    private fun pending(isHome: Boolean) = rows.count { it.isHome == isHome && num(it.on) != null && num(it.off) != null }

    private fun refreshTallies() {
        listOf(true to binding.txtHomeTally, false to binding.txtAwayTally).forEach { (isHome, view) ->
            val c = checker(isHome)
            val p = pending(isHome)
            val limit = if (p > 0) c.limitProblem(p, usesMoment()) else null
            view.text = c.tallyText() + (if (p > 0) "  (+$p)" else "") + (limit?.let { "\n⚠ $it" } ?: "")
            view.setTextColor(if (limit != null) warn else Color.WHITE)
        }
    }

    private fun refresh() {
        val ctx = viewModel.subContext()
        val momentText = when {
            !rules.trackMoments -> ""
            usesMoment() -> " · uses a moment"
            else -> " · no moment used"
        }
        binding.txtSubContext.text = ctx.label + momentText
        listOf(true to binding.historyHome, false to binding.historyAway).forEach { (isHome, list) ->
            list.removeAllViews()
            val subs = events().filter { it.type == MatchEvent.EventType.SUBSTITUTION && it.team.isHome == isHome }
            if (subs.isEmpty()) list.addView(TextView(this).apply { text = "None yet"; setTextColor(muted); textSize = 12f })
            var lastGroup: String? = null; var moment = 0
            subs.forEach { e ->
                if (e.groupId != lastGroup) {
                    lastGroup = e.groupId
                    if (e.momentCounted) moment++
                    list.addView(TextView(this).apply {
                        text = (if (e.momentCounted) "Moment $moment · " else "Interval · ") + e.minuteLabel()
                        setTextColor(muted); textSize = 11f; setPadding(0, dp(6), 0, 0)
                    })
                }
                list.addView(TextView(this).apply {
                    text = "▲ ${e.player.tag}  ▼ #${e.playerOff?.jerseyNumber ?: "?"}" + (e.flag?.let { "\n⚠ $it" } ?: "")
                    setTextColor(if (e.flag != null) warn else Color.WHITE); textSize = 14f
                })
            }
        }
        refreshFlags()
    }

    // ── Record ──

    private fun record() {
        val incomplete = rows.filter { (num(it.on) == null) != (num(it.off) == null) }
        if (incomplete.isNotEmpty()) {
            incomplete.forEach { (if (num(it.on) == null) it.on else it.off).error = "Enter both numbers" }
            return
        }
        val filled = rows.filter { num(it.on) != null && num(it.off) != null }
        if (filled.isEmpty()) {
            Toast.makeText(this, "Enter the player coming on and the player coming off", Toast.LENGTH_SHORT).show()
            return
        }

        // Collect every flag (row problems + team limits)
        val rowFlags = filled.associateWith { problemsFor(it) }
        val teamFlags = listOf(true, false).associateWith { isHome ->
            val p = filled.count { it.isHome == isHome }
            if (p > 0) checker(isHome).limitProblem(p, usesMoment()) else null
        }
        val all = rowFlags.values.flatten() + teamFlags.values.filterNotNull()

        if (all.isEmpty()) { save(filled, rowFlags, teamFlags); return }
        AlertDialog.Builder(this)
            .setTitle("⚠ Check these substitutions")
            .setMessage(all.distinct().joinToString("\n\n") { "• $it" })
            .setPositiveButton("Record anyway") { _, _ -> save(filled, rowFlags, teamFlags) }
            .setNegativeButton("Go back", null)
            .show()
    }

    private fun save(filled: List<Row>, rowFlags: Map<Row, List<String>>, teamFlags: Map<Boolean, String?>) {
        val moment = usesMoment()
        var count = 0
        listOf(true, false).forEach { isHome ->
            val pairs = filled.filter { it.isHome == isHome }.map { r ->
                val flags = rowFlags[r].orEmpty() + listOfNotNull(teamFlags[isHome])
                Triple(num(r.on)!!, num(r.off)!!, flags.takeIf { it.isNotEmpty() }?.joinToString("; "))
            }
            count += viewModel.addSubstitutions(isHome, pairs, moment)
        }
        // Clear the entry rows, leave one empty row per team
        rows.toList().forEach { r -> (if (r.isHome) binding.rowsHome else binding.rowsAway).removeView(r.view) }
        rows.clear()
        addRow(true); addRow(false)
        refresh()
        Toast.makeText(this, "Recorded $count substitution${if (count == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
    }
}
