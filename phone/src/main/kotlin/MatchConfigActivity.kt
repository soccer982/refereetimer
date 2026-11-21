package com.example.refereetimer.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.phone.databinding.ActivityMatchConfigBinding
import com.example.refereetimer.shared.DataLayerPaths
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentSettings()

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
            saveSettings(settings)
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

    companion object {
        private const val PREFS = "MatchConfigPrefs"
    }
}
