package com.example.refereetimer.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.refereetimer.shared.GameConfirmation
import com.example.refereetimer.shared.models.Team
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.phone.databinding.ActivityMatchConfigBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.SubstitutionRules
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Match Setup: number of periods, period length, interval between periods.
 * Saving sends the settings straight to the watch.
 */
class MatchConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMatchConfigBinding
    private val viewModel by lazy { (application as PhoneApp).matchViewModel }

    // NEW: pick one or more screenshots of the game confirmation
    private val pickScreenshots = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importFromImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentSettings()
        binding.btnImportGame.setOnClickListener { pickScreenshots.launch("image/*") }
        binding.btnFieldSetup.setOnClickListener { startActivity(Intent(this, FieldSetupActivity::class.java)) }
        handleSharedConfirmation(intent)

        // Update the summary as you type
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSummary() }
        }
        binding.inputPeriods.addTextChangedListener(watcher)
        binding.inputDuration.addTextChangedListener(watcher)
        binding.inputBreak.addTextChangedListener(watcher)
        updateSummary()

        binding.btnSaveAndNext.setOnClickListener {
            val settings = readValidated() ?: return@setOnClickListener
            val subRules = readSubRules() ?: return@setOnClickListener
            saveSettings(settings)
            SubRulesStore.save(this, subRules)
            fun t(e: EditText) = e.text?.toString()?.trim().orEmpty()
            MatchDetails(
                referee = t(binding.inputReferee), refereeId = t(binding.inputRefereeId),
                ar1 = t(binding.inputAr1), ar2 = t(binding.inputAr2), fourth = t(binding.inputFourth),
                competition = t(binding.inputCompetition), gameNumber = t(binding.inputGameNumber),
                venue = t(binding.inputVenue), division = t(binding.inputDivision),
                refereeGrade = t(binding.inputRefereeGrade), ar1Grade = t(binding.inputAr1Grade),
                ar2Grade = t(binding.inputAr2Grade), fourthGrade = t(binding.inputFourthGrade),
                phone = t(binding.inputPhone), email = t(binding.inputEmail),
                yourName = t(binding.inputYourName), gameDate = t(binding.inputGameDate), kickoff = t(binding.inputKickoff)
            ).save(this)
            sendToWatch(settings)
            startActivity(Intent(this, RosterSetupActivity::class.java))
            finish()
        }
    }

    private data class Settings(val periods: Int, val duration: Int, val interval: Int, val drinkBreak: Boolean)

    private fun loadCurrentSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        binding.inputPeriods.setText(prefs.getInt("periods", 2).toString())
        binding.inputDuration.setText(prefs.getInt("duration", 45).toString())
        binding.inputBreak.setText(prefs.getInt("breakDuration", 10).toString())
        binding.switchDrinkBreak.isChecked = prefs.getBoolean("drinkBreak", false)

        // Official report details (remembered between matches)
        val d = MatchDetails.load(this)
        binding.inputReferee.setText(d.referee); binding.inputRefereeId.setText(d.refereeId)
        binding.inputAr1.setText(d.ar1); binding.inputAr2.setText(d.ar2); binding.inputFourth.setText(d.fourth)
        binding.inputCompetition.setText(d.competition); binding.inputGameNumber.setText(d.gameNumber)
        binding.inputVenue.setText(d.venue); binding.inputDivision.setText(d.division)
        binding.inputRefereeGrade.setText(d.refereeGrade); binding.inputAr1Grade.setText(d.ar1Grade)
        binding.inputAr2Grade.setText(d.ar2Grade); binding.inputFourthGrade.setText(d.fourthGrade)
        binding.inputPhone.setText(d.phone); binding.inputEmail.setText(d.email)
        binding.inputYourName.setText(d.yourName); binding.inputGameDate.setText(d.gameDate)
        binding.inputKickoff.setText(d.kickoff)

        // Substitution rules
        val r = SubRulesStore.load(this)
        binding.switchTrackSubs.isChecked = r.tracking
        binding.radioSubLimit.check(if (r.maxSubs > 0) R.id.radioSubsLimited else R.id.radioSubsUnlimited)
        if (r.maxSubs > 0) binding.inputMaxSubs.setText(r.maxSubs.toString())
        binding.switchTrackMoments.isChecked = r.trackMoments
        if (r.maxMoments > 0) binding.inputMaxMoments.setText(r.maxMoments.toString())
        binding.checkIntervalsFree.isChecked = r.intervalsFree
        binding.radioReEntry.check(when (r.reEntry) {
            SubstitutionRules.ReEntry.ALLOWED -> R.id.radioReEntryAllowed
            SubstitutionRules.ReEntry.NOT_SAME_PERIOD -> R.id.radioReEntryPeriod
            SubstitutionRules.ReEntry.NOT_REST_OF_MATCH -> R.id.radioReEntryNever
        })
        binding.switchTrackSubs.setOnCheckedChangeListener { _, _ -> updateSubVisibility() }
        binding.switchTrackMoments.setOnCheckedChangeListener { _, _ -> updateSubVisibility() }
        binding.radioSubLimit.setOnCheckedChangeListener { _, _ -> updateSubVisibility() }
        updateSubVisibility()
    }

    /** Only show the options that apply. */
    private fun updateSubVisibility() {
        val on = binding.switchTrackSubs.isChecked
        binding.groupSubRules.visibility = if (on) View.VISIBLE else View.GONE
        binding.inputMaxSubsLayout.visibility =
            if (binding.radioSubLimit.checkedRadioButtonId == R.id.radioSubsLimited) View.VISIBLE else View.GONE
        val moments = binding.switchTrackMoments.isChecked
        binding.inputMaxMomentsLayout.visibility = if (moments) View.VISIBLE else View.GONE
        binding.checkIntervalsFree.visibility = if (moments) View.VISIBLE else View.GONE
    }

    /** Reads the substitution rules from the screen, or null (with the field marked) if invalid. */
    private fun readSubRules(): SubstitutionRules? {
        val limited = binding.radioSubLimit.checkedRadioButtonId == R.id.radioSubsLimited
        val maxSubs = if (limited) number(binding.inputMaxSubs) else 0
        if (binding.switchTrackSubs.isChecked && limited && (maxSubs == null || maxSubs !in 1..50)) {
            binding.inputMaxSubs.error = "Enter 1 to 50"; return null
        }
        val maxMoments = binding.inputMaxMoments.text?.toString()?.trim()?.toIntOrNull() ?: 0
        return SubstitutionRules(
            tracking = binding.switchTrackSubs.isChecked,
            maxSubs = maxSubs ?: 0,
            trackMoments = binding.switchTrackMoments.isChecked,
            maxMoments = maxMoments.coerceIn(0, 20),
            intervalsFree = binding.checkIntervalsFree.isChecked,
            reEntry = when (binding.radioReEntry.checkedRadioButtonId) {
                R.id.radioReEntryPeriod -> SubstitutionRules.ReEntry.NOT_SAME_PERIOD
                R.id.radioReEntryNever -> SubstitutionRules.ReEntry.NOT_REST_OF_MATCH
                else -> SubstitutionRules.ReEntry.ALLOWED
            }
        )
    }

    private fun number(field: EditText): Int? = field.text?.toString()?.trim()?.toIntOrNull()

    /** Returns the settings, or null (with the problem field marked) if something's out of range. */
    private fun readValidated(): Settings? {
        val periods = number(binding.inputPeriods)
        val duration = number(binding.inputDuration)
        val interval = number(binding.inputBreak)
        var ok = true
        if (periods == null || periods !in 1..8) { binding.inputPeriods.error = "Enter 1 to 8"; ok = false }
        if (duration == null || duration !in 1..120) { binding.inputDuration.error = "Enter 1 to 120 minutes"; ok = false }
        if (interval == null || interval !in 0..60) { binding.inputBreak.error = "Enter 0 to 60 minutes"; ok = false }
        if (!ok) return null
        return Settings(periods!!, duration!!, interval!!, binding.switchDrinkBreak.isChecked)
    }

    private fun updateSummary() {
        val p = number(binding.inputPeriods)
        val d = number(binding.inputDuration)
        val i = number(binding.inputBreak)
        binding.txtConfigSummary.text = if (p == null || d == null || i == null || p !in 1..8 || d !in 1..120 || i !in 0..60) {
            "Enter periods, length and interval"
        } else {
            val playing = p * d
            val intervals = (p - 1) * i
            val intervalText = if (p > 1) " with $i min intervals" else ""
            "$p × $d min$intervalText\n= ${playing + intervals} min total + stoppage"
        }
    }

    private fun saveSettings(s: Settings) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("periods", s.periods)
            putInt("duration", s.duration)
            putInt("breakDuration", s.interval)
            putBoolean("drinkBreak", s.drinkBreak)
            apply()
        }
    }

    /** Sends the settings to the watch right away (the watch applies them before kickoff). */
    private fun sendToWatch(s: Settings) {
        val inProgress = viewModel.isMatchInProgress
        val json = JSONObject().apply {
            put("homeName", viewModel.homeTeamName.value ?: "Home")
            put("awayName", viewModel.awayTeamName.value ?: "Away")
            put("periodMins", s.duration)
            put("breakMins", s.interval)
            put("numPeriods", s.periods)
        }.toString()
        val appContext = applicationContext

        // MainScope (not lifecycleScope): this screen closes right after Save,
        // and the send must finish anyway.
        MainScope().launch {
            val sent = try {
                withContext(Dispatchers.IO) {
                    val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
                    val messages = Wearable.getMessageClient(appContext)
                    nodes.forEach { messages.sendMessage(it.id, DataLayerPaths.DATA_MATCH_CONFIG, json.toByteArray()).await() }
                    nodes.isNotEmpty()
                }
            } catch (e: Exception) { false }

            val msg = when {
                inProgress -> "Saved. A match is in progress: new lengths apply after you reset."
                sent -> "Saved and sent to watch"
                else -> "Saved. Watch not connected; settings will be sent with the rosters."
            }
            Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── NEW: import a game confirmation ──

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedConfirmation(intent)
    }

    /** Screenshots or email text shared to this app from another app. */
    @Suppress("DEPRECATION")
    private fun handleSharedConfirmation(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> importFromImages(listOf(uri))
                    !text.isNullOrBlank() -> reviewImport(GameImporter.fromText(text))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.isNotEmpty()) importFromImages(uris)
            }
        }
        intent.action = null   // don't re-import on rotation
    }

    private fun importFromImages(uris: List<Uri>) {
        binding.btnImportGame.isEnabled = false
        binding.btnImportGame.text = "Reading confirmation…"
        lifecycleScope.launch {
            val result = try { GameImporter.fromImages(this@MatchConfigActivity, uris) } catch (e: Exception) {
                android.util.Log.e("IMPORT", "Could not read screenshot", e); null
            }
            binding.btnImportGame.isEnabled = true
            binding.btnImportGame.text = "📷  IMPORT GAME CONFIRMATION"
            if (result == null || result.isEmpty) {
                AlertDialog.Builder(this@MatchConfigActivity)
                    .setTitle("Couldn't read that confirmation")
                    .setMessage("Try a screenshot that shows the teams, date and officials, or share the confirmation email instead.")
                    .setPositiveButton("OK", null).show()
            } else reviewImport(result)
        }
    }

    /** Shows what was found; nothing changes until "Use these details". */
    private fun reviewImport(c: GameConfirmation) {
        val found = c.summary().joinToString("\n") { (k, v) -> "$k:  ${v.ifBlank { "not found" }}" } +
            (c.slotMinutes?.let { "\n\nScheduled slot: $it min (set the periods below to match)" } ?: "")
        val hasMatch = viewModel.events.value.orEmpty().isNotEmpty()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 24, 56, 0) }
        box.addView(TextView(this).apply { text = found; textSize = 14f })
        val newMatch = CheckBox(this).apply {
            text = "Start a new match (clears the current score and events)"
            isChecked = true
            visibility = if (hasMatch) View.VISIBLE else View.GONE
        }
        box.addView(newMatch)
        AlertDialog.Builder(this)
            .setTitle("Game confirmation")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Use these details") { _, _ -> applyImport(c, hasMatch && newMatch.isChecked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyImport(c: GameConfirmation, clearMatch: Boolean) {
        fun setIfFound(e: EditText, v: String) { if (v.isNotBlank()) e.setText(v) }
        setIfFound(binding.inputCompetition, c.league)
        setIfFound(binding.inputDivision, c.ageGroup)
        setIfFound(binding.inputGameNumber, c.gameNumber)
        setIfFound(binding.inputVenue, c.location)
        setIfFound(binding.inputGameDate, c.dateText)
        setIfFound(binding.inputKickoff, c.kickoff)
        // A new crew each game: replace all four (blank if not listed), grades too
        binding.inputReferee.setText(c.referee); binding.inputAr1.setText(c.ar1)
        binding.inputAr2.setText(c.ar2); binding.inputFourth.setText(c.fourth)
        listOf(binding.inputRefereeGrade, binding.inputAr1Grade, binding.inputAr2Grade, binding.inputFourthGrade).forEach { it.setText("") }

        if (clearMatch) {
            viewModel.reset()
            MainScope().launch(Dispatchers.IO) {
                try {
                    Wearable.getNodeClient(applicationContext).connectedNodes.await().forEach {
                        Wearable.getMessageClient(applicationContext).sendMessage(it.id, DataLayerPaths.MSG_RESET, ByteArray(0)).await()
                    }
                } catch (e: Exception) { }
            }
        }
        // Team names (rosters are kept only if the team didn't change)
        val oldHome = viewModel.homeTeam.value; val oldAway = viewModel.awayTeam.value
        val home = c.homeTeam.ifBlank { oldHome?.name ?: "Home" }
        val away = c.awayTeam.ifBlank { oldAway?.name ?: "Away" }
        viewModel.setTeams(
            if (oldHome?.name == home) oldHome else Team(home, emptyList(), emptyList(), true),
            if (oldAway?.name == away) oldAway else Team(away, emptyList(), emptyList(), false)
        )
        Toast.makeText(this, "Imported: check the details, then Save", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFS = "MatchConfigPrefs"
    }
}
