package com.example.refereetimer.phone

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.refereetimer.phone.databinding.ActivityPhoneMainBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.BodyParts
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.Team
import com.example.refereetimer.shared.models.TeamTag
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PhoneMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhoneMainBinding

    // FIX: use the ONE shared match ViewModel (see PhoneApp.kt)
    private val viewModel by lazy { (application as PhoneApp).matchViewModel }
    private lateinit var eventLogAdapter: EventLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEventLog()
        setupTiles()
        setupNavigation()
        observeViewModel()
        // Watch data flows PhoneDataService -> shared ViewModel (which saves itself).
    }

    override fun onResume() {
        super.onResume()
        resyncClockFromWatch()
        // The Subs tab only appears when substitution tracking is on in Match Setup
        binding.bottomNavigation.menu.findItem(R.id.nav_subs)?.isVisible = SubRulesStore.load(this).tracking
    }

    /**
     * Grab the watch's latest clock, field calibration and movement tracks,
     * in case an update was missed while the phone app was closed.
     */
    private fun resyncClockFromWatch() {
        try {
            val client = Wearable.getDataClient(this)
            val uri = Uri.Builder().scheme("wear").path("/match").build()
            client.getDataItems(uri, DataClient.FILTER_PREFIX)
                .addOnSuccessListener { buffer ->
                    try {
                        for (item in buffer) {
                            val path = item.uri.path ?: continue
                            val map = DataMapItem.fromDataItem(item).dataMap
                            when {
                                path == DataLayerPaths.PATH_MATCH_CLOCK ->
                                    map.getString(DataLayerPaths.KEY_CLOCK_JSON)?.let { viewModel.applyWatchClock(it) }
                                path == DataLayerPaths.PATH_FIELD_CALIBRATION ->
                                    map.getString(DataLayerPaths.KEY_CALIBRATION_JSON)?.let { viewModel.applyCalibration(it) }
                                path.startsWith(DataLayerPaths.PATH_MATCH_TRACK + "/") -> {
                                    val period = path.substringAfterLast('/').toIntOrNull()
                                    val json = map.getString(DataLayerPaths.KEY_TRACK_JSON)
                                    if (period != null && json != null) viewModel.applyTrack(period, json)
                                }
                                path.startsWith(DataLayerPaths.PATH_MATCH_EVENT + "/") ->
                                    map.getString(DataLayerPaths.KEY_EVENT_JSON)?.let { viewModel.addEventFromJson(it) }
                            }
                        }
                    } finally {
                        buffer.release()
                    }
                }
        } catch (e: Exception) { /* Wear OS not available on this phone */ }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_dashboard

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    binding.nestedScrollView.visibility = View.VISIBLE
                    true
                }
                R.id.nav_setup -> {
                    startActivity(Intent(this, MatchConfigActivity::class.java))
                    false
                }
                R.id.nav_report -> {
                    generatePdfReport()
                    false
                }
                R.id.nav_reset -> {
                    confirmReset()
                    false
                }
                R.id.nav_subs -> {
                    startActivity(Intent(this, SubstitutionActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }

    private fun setupTiles() {
        binding.cardHomeTeam.setOnClickListener { showEventSelectionMenu(isHome = true) }
        binding.cardAwayTeam.setOnClickListener { showEventSelectionMenu(isHome = false) }
        binding.txtGpsStatus.setOnClickListener { startActivity(Intent(this, FieldSetupActivity::class.java)) }
    }

    private fun setupEventLog() {
        eventLogAdapter = EventLogAdapter()
        binding.recyclerEventLog.apply {
            layoutManager = LinearLayoutManager(this@PhoneMainActivity).also { it.stackFromEnd = true }
            adapter = eventLogAdapter
        }
    }

    private fun showEventSelectionMenu(isHome: Boolean) {
        seedTeamsIfMissing()
        val teamName = if (isHome) viewModel.homeTeamName.value.orEmpty().ifBlank { "Home" }
        else viewModel.awayTeamName.value.orEmpty().ifBlank { "Away" }

        val options = arrayOf("⚽ Log Goal", "🚩 Log Foul / Card", "🔄 Substitution", "🥅 Shootout Kick (KFTM)")

        AlertDialog.Builder(this)
            .setTitle("${TeamTag.label(teamName, isHome)} action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showGoalEntryDialog(isHome)
                    1 -> showFoulEntryDialog(isHome)
                    2 -> if (SubRulesStore.load(this).tracking) startActivity(Intent(this, SubstitutionActivity::class.java))
                         else Toast.makeText(this, "Turn on substitution tracking in Match Setup", Toast.LENGTH_LONG).show()
                    3 -> Toast.makeText(this, "Shootout UI coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun observeViewModel() {
        viewModel.homeScore.observe(this)    { updateScoreDisplay() }
        viewModel.awayScore.observe(this)    { updateScoreDisplay() }
        viewModel.homeTeamName.observe(this) { binding.txtHomeTeamName.text = TeamTag.label(it, true); updateScoreDisplay() }
        viewModel.awayTeamName.observe(this) { binding.txtAwayTeamName.text = TeamTag.label(it, false); updateScoreDisplay() }
        viewModel.timerDisplay.observe(this) { binding.txtTimerDisplay.text  = it }
        viewModel.periodDisplay.observe(this){ binding.txtPeriodDisplay.text = it }
        viewModel.timerState.observe(this)   {
            binding.txtMatchStatus.text = when (it) {
                "PAUSED"  -> "STOPPAGE"
                "TIME_UP" -> "ADDED TIME"
                "BREAK"   -> "INTERVAL"
                else      -> it.replace('_', ' ')
            }
            updateStatusColor(it)
            applyAddedTimeTheme(it == "TIME_UP")
        }
        viewModel.gpsStatus.observe(this)    { binding.txtGpsStatus.text     = it }
        viewModel.events.observe(this) { events ->
            eventLogAdapter.submitList(events.map { it.toDisplayString() })
            if (eventLogAdapter.itemCount > 0) binding.recyclerEventLog.scrollToPosition(eventLogAdapter.itemCount - 1)
        }

        viewModel.kftmWinner.observe(this) { winnerData ->
            if (winnerData != null) {
                val payload = "${winnerData.first}|${winnerData.second}"
                Wearable.getNodeClient(this).connectedNodes
                    .addOnSuccessListener { nodes ->
                        for (node in nodes) {
                            Wearable.getMessageClient(this)
                                .sendMessage(node.id, "/match/kftm_winner", payload.toByteArray())
                        }
                    }
            }
        }
    }

    private fun updateScoreDisplay() {
        binding.txtHomeScore.text = (viewModel.homeScore.value ?: 0).toString()
        binding.txtAwayScore.text = (viewModel.awayScore.value ?: 0).toString()
    }

    /** Added time: timer goes onto a light red background, like the watch. */
    private fun applyAddedTimeTheme(addedTime: Boolean) {
        binding.txtTimerDisplay.setBackgroundColor(if (addedTime) 0xFFFFCDD2.toInt() else android.graphics.Color.TRANSPARENT)
        binding.txtTimerDisplay.setTextColor(if (addedTime) 0xFFB71C1C.toInt() else 0xFF4CAF50.toInt())
    }

    private fun updateStatusColor(state: String) {
        binding.txtMatchStatus.setTextColor(when (state) {
            "RUNNING"    -> 0xFF4CAF50.toInt()
            "PAUSED"     -> 0xFFFF9800.toInt()
            "TIME_UP"    -> 0xFFFF9800.toInt()
            "BREAK"      -> 0xFFFFEB3B.toInt()
            "EXTRA_TIME" -> 0xFFF44336.toInt()
            "MATCH_OVER" -> 0xFF9E9E9E.toInt()
            else -> 0xFFFFFFFF.toInt()
        })
    }

    private fun seedTeamsIfMissing() {
        if (viewModel.homeTeam.value == null || viewModel.awayTeam.value == null) {
            val hName = viewModel.homeTeamName.value.orEmpty().ifBlank { "Home" }
            val aName = viewModel.awayTeamName.value.orEmpty().ifBlank { "Away" }
            viewModel.setTeams(
                viewModel.homeTeam.value ?: Team(hName, emptyList(), emptyList(), true),
                viewModel.awayTeam.value ?: Team(aName, emptyList(), emptyList(), false)
            )
        }
    }

    private fun showGoalEntryDialog(isHome: Boolean) {
        seedTeamsIfMissing()
        val team = if (isHome) viewModel.homeTeam.value!! else viewModel.awayTeam.value!!
        val opponent = if (isHome) viewModel.awayTeam.value else viewModel.homeTeam.value
        GoalEntryDialogFragment(team, opponent) { scorer, assist, isOG, isPK ->
            viewModel.addGoal(isHome, scorer, assist, isOG, isPK)
        }.show(supportFragmentManager, "GoalEntry")
    }

    private fun showFoulEntryDialog(isHome: Boolean) {
        seedTeamsIfMissing()
        val team = if (isHome) viewModel.homeTeam.value!! else viewModel.awayTeam.value!!
        FoulEntryDialogFragment(team) { player, foulType, isPK, cardType ->
            if (cardType == null) viewModel.addFoul(isHome, player, foulType, isPK, null)
            else pickMisconductCode(isHome, player, cardType) { code ->
                viewModel.addFoul(isHome, player, foulType, isPK, cardType, code)
            }
        }.show(supportFragmentManager, "FoulEntry")
    }

    // ── REPORT: walk through injuries, then red cards, then build the PDF ──

    /** NEW: USSF misconduct code for a card entered on the phone (the forms need it). */
    private fun pickMisconductCode(isHome: Boolean, player: Int, card: MatchEvent.CardType, onPicked: (String) -> Unit) {
        val codes = if (card == MatchEvent.CardType.YELLOW) MisconductCodes.CAUTIONS else MisconductCodes.SEND_OFFS
        val second = card == MatchEvent.CardType.YELLOW && viewModel.hasYellow(isHome, player)
        AlertDialog.Builder(this)
            .setTitle((if (card == MatchEvent.CardType.YELLOW) "🟨 #$player caution for" else "🟥 #$player send-off for") +
                if (second) "  (2nd yellow → 2CT)" else "")
            .setItems(codes.map { "${it.code}  ·  ${it.description}" }.toTypedArray()) { _, which -> onPicked(codes[which].code) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generatePdfReport() {
        val all = viewModel.events.value?.toList().orEmpty()
        if (all.isEmpty()) {
            Toast.makeText(this, "No events logged yet. Log a goal or card first.", Toast.LENGTH_LONG).show()
            return
        }
        val injuries = all.filter { it.type == MatchEvent.EventType.INJURY }.sortedBy { it.timestamp }.map { it.id }
        val reds = all.filter { it.type == MatchEvent.EventType.CARD && it.cardType == MatchEvent.CardType.RED }
            .sortedBy { it.timestamp }.map { it.id }
        walkInjuries(injuries, 0) { walkRedCards(reds, 0) { buildPdf() } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun who(e: MatchEvent): String {
        val name = e.player.name.takeIf { it.isNotBlank() && it != "Unknown" }?.let { " $it" } ?: ""
        return "${e.team.label} ${e.player.tag}$name"
    }

    private fun multiLineInput(hint: String, existing: String?): EditText = EditText(this).apply {
        this.hint = hint
        setText(existing ?: "")
        minLines = 4
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    /** One dialog per injury: serious? + description. Serious injuries go on the Supplemental Report. */
    private fun walkInjuries(ids: List<String>, i: Int, done: () -> Unit) {
        if (i >= ids.size) { done(); return }
        val e = viewModel.eventById(ids[i]) ?: return walkInjuries(ids, i + 1, done)
        val timeOfDay = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(e.timestamp))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = "${e.minuteLabel()} (period ${e.period}, $timeOfDay)\n${who(e)}\n${BodyParts.label(e.bodyPart)}" +
                if (e.gpsLocation != null) "  📍" else ""
            textSize = 16f
        })
        val serious = CheckBox(this).apply {
            text = "Serious injury (goes on the Supplemental Report)"
            isChecked = e.seriousInjury
        }
        box.addView(serious)
        val desc = multiLineInput("What happened, treatment given, did the player leave / return?", e.reportText)
        box.addView(desc)

        val dialog = AlertDialog.Builder(this)
            .setTitle("🩹 Injury ${i + 1} of ${ids.size}")
            .setView(box)
            .setPositiveButton(if (i == ids.lastIndex) "Done" else "Next", null)
            .setNegativeButton("Cancel report", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = desc.text.toString().trim()
                if (serious.isChecked && text.isEmpty()) {
                    desc.error = "A serious injury needs a description for the Supplemental Report"
                    return@setOnClickListener
                }
                viewModel.updateEventReport(e.id, serious.isChecked, text.ifEmpty { null })
                dialog.dismiss()
                walkInjuries(ids, i + 1, done)
            }
        }
        dialog.show()
    }

    /** One dialog per red card: the narrative is required for the Supplemental Report. */
    private fun walkRedCards(ids: List<String>, i: Int, done: () -> Unit) {
        if (i >= ids.size) { done(); return }
        val e = viewModel.eventById(ids[i]) ?: return walkRedCards(ids, i + 1, done)
        val code = e.misconductCode ?: ""
        val isSecondYellow = code in MisconductCodes.SECOND_CAUTION_CODES

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = "${e.minuteLabel()} (period ${e.period})\n${who(e)}\n$code: ${MisconductCodes.describe(code)}"
            textSize = 16f
        })
        val hint = if (isSecondYellow) "Describe both cautionable offences and what led to the send-off"
            else "Describe exactly what happened: the action, where, any injury, anything said or done after"
        val desc = multiLineInput(hint, e.reportText)
        box.addView(desc)

        val dialog = AlertDialog.Builder(this)
            .setTitle("🟥 Send-off report ${i + 1} of ${ids.size}")
            .setView(box)
            .setPositiveButton(if (i == ids.lastIndex) "Done" else "Next", null)
            .setNegativeButton("Cancel report", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = desc.text.toString().trim()
                if (text.isEmpty()) {
                    desc.error = "A send-off needs a report for the Supplemental Report"
                    return@setOnClickListener
                }
                viewModel.updateEventReport(e.id, e.seriousInjury, text)
                dialog.dismiss()
                walkRedCards(ids, i + 1, done)
            }
        }
        dialog.show()
    }

    private fun buildPdf() {
        // Events (now with the answers above) and movement track placed on the calibrated field
        val events = viewModel.reportEvents()
        binding.txtMatchStatus.text = "Generating PDF…"
        lifecycleScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    PdfReportGenerator(
                        context = this@PhoneMainActivity,
                        config = viewModel.matchConfig.value,
                        events = events,
                        gpsTrack = viewModel.reportTrack(),
                        homeName = viewModel.homeTeamName.value ?: "Home",
                        awayName = viewModel.awayTeamName.value ?: "Away",
                        totalStoppageMillis = viewModel.totalStoppageMillis.value ?: 0L,
                        regularPeriods = viewModel.regularPeriods,
                        matchSummary = viewModel.matchSummary(),
                        fieldLengthM = viewModel.fieldCalibration?.lengthMetres,
                        fieldWidthM = viewModel.fieldCalibration?.widthMetres,
                        details = MatchDetails.load(this@PhoneMainActivity),
                        clock = viewModel.currentClock,
                        subRulesText = SubRulesStore.load(this@PhoneMainActivity).let { if (it.tracking) it.describe() else "" }
                    ).generate()
                }
                binding.txtMatchStatus.text = "PDF ready"
                val uri = FileProvider.getUriForFile(this@PhoneMainActivity, "$packageName.fileprovider", pdfFile)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share Match Report"))
            } catch (e: Exception) {
                android.util.Log.e("PDF", "Report failed", e)
                binding.txtMatchStatus.text = "PDF generation failed: ${e.message}"
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset match?")
            .setMessage("This clears the score, clock and all logged events on the phone and the watch. Team rosters are kept.")
            .setPositiveButton("Reset") { _, _ -> resetMatchEverywhere() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetMatchEverywhere() {
        viewModel.reset()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Wearable.getNodeClient(this@PhoneMainActivity).connectedNodes.await().forEach { node ->
                    Wearable.getMessageClient(this@PhoneMainActivity)
                        .sendMessage(node.id, DataLayerPaths.MSG_RESET, ByteArray(0)).await()
                }
            } catch (e: Exception) { /* watch not connected: phone is still reset */ }
        }
    }
}
