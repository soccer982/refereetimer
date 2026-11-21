package com.example.refereetimer.phone

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.refereetimer.phone.databinding.ActivityPhoneMainBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.models.Team
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
    }

    /** NEW: grab the watch's latest saved clock in case an update was missed while the app was closed. */
    private fun resyncClockFromWatch() {
        try {
            val uri = Uri.Builder().scheme("wear").path(DataLayerPaths.PATH_MATCH_CLOCK).build()
            Wearable.getDataClient(this).getDataItems(uri)
                .addOnSuccessListener { buffer ->
                    try {
                        for (item in buffer) {
                            DataMapItem.fromDataItem(item).dataMap
                                .getString(DataLayerPaths.KEY_CLOCK_JSON)
                                ?.let { viewModel.applyWatchClock(it) }
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
                else -> false
            }
        }
    }

    private fun setupTiles() {
        binding.cardHomeTeam.setOnClickListener { showEventSelectionMenu(isHome = true) }
        binding.cardAwayTeam.setOnClickListener { showEventSelectionMenu(isHome = false) }
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
            .setTitle("$teamName Action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showGoalEntryDialog(isHome)
                    1 -> showFoulEntryDialog(isHome)
                    2 -> Toast.makeText(this, "Substitution UI coming soon", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this, "Shootout UI coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun observeViewModel() {
        viewModel.homeScore.observe(this)    { updateScoreDisplay() }
        viewModel.awayScore.observe(this)    { updateScoreDisplay() }
        viewModel.homeTeamName.observe(this) { binding.txtHomeTeamName.text = it; updateScoreDisplay() }
        viewModel.awayTeamName.observe(this) { binding.txtAwayTeamName.text = it; updateScoreDisplay() }
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
        GoalEntryDialogFragment(team) { scorer, assist, isOG ->
            viewModel.addGoal(isHome, scorer, assist, isOG)
        }.show(supportFragmentManager, "GoalEntry")
    }

    private fun showFoulEntryDialog(isHome: Boolean) {
        seedTeamsIfMissing()
        val team = if (isHome) viewModel.homeTeam.value!! else viewModel.awayTeam.value!!
        FoulEntryDialogFragment(team) { player, foulType, isPK, cardType ->
            viewModel.addFoul(isHome, player, foulType, isPK, cardType)
        }.show(supportFragmentManager, "FoulEntry")
    }

    private fun generatePdfReport() {
        // Take a snapshot so events arriving mid-generation can't crash the report
        val events = viewModel.events.value?.toList().orEmpty()
        if (events.isEmpty()) {
            Toast.makeText(this, "No events logged yet. Log a goal or card first.", Toast.LENGTH_LONG).show()
            return
        }
        binding.txtMatchStatus.text = "Generating PDF…"
        lifecycleScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    PdfReportGenerator(
                        context = this@PhoneMainActivity,
                        config = viewModel.matchConfig.value,
                        events = events,
                        gpsTrack = viewModel.gpsTrack.value ?: emptyList(),
                        homeName = viewModel.homeTeamName.value ?: "Home",
                        awayName = viewModel.awayTeamName.value ?: "Away",
                        totalStoppageMillis = viewModel.totalStoppageMillis.value ?: 0L,
                        regularPeriods = viewModel.regularPeriods,
                        matchSummary = viewModel.matchSummary()
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
