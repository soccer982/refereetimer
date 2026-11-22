package com.example.refereetimer

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.databinding.ActivityInjuryBinding
import com.example.refereetimer.shared.models.BodyParts
import com.example.refereetimer.shared.models.GpsPoint

/**
 * NEW: opened by LONG-PRESSING the STOP button (stoppage starts automatically).
 *   Team → player number (keypad) → body part → left/right (arm or leg only)
 * The time and GPS position are taken at the moment of the long-press.
 * Severity and a description are added on the phone when the report is made.
 */
class InjuryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInjuryBinding
    private val app by lazy { application as WearApp }

    private var isHome = true
    private var typed = ""
    private var limb = ""             // "ARM" or "LEG" while choosing a side

    // Captured at the moment of the long-press
    private var minute = 0
    private var added = 0
    private var period = 1
    private var elapsed = 0L
    private var gps: GpsPoint? = null
    private var happenedAt = 0L

    private enum class Step { TEAM, NUMBER, BODY, SIDE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInjuryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val timer = app.timerViewModel
        timer.minuteNow().let { minute = it.first; added = it.second }
        period = timer.currentClock.period
        elapsed = timer.matchClockNow()
        happenedAt = System.currentTimeMillis()
        gps = app.locationTagger.freshFix()
        if (gps == null) app.locationTagger.requestOneShot()

        val label = if (added > 0) "$minute+$added'" else "$minute'"
        binding.txtInjurySub.text = "$label · stoppage running"
        listOf(binding.btnTeamHome to true, binding.btnTeamAway to false).forEach { (button, home) ->
            button.text = app.eventViewModel.team(home).label.uppercase()
            button.maxLines = 1
            button.ellipsize = android.text.TextUtils.TruncateAt.END
        }

        binding.btnTeamHome.setOnClickListener { isHome = true; showStep(Step.NUMBER) }
        binding.btnTeamAway.setOnClickListener { isHome = false; showStep(Step.NUMBER) }

        listOf(binding.btnDigit0, binding.btnDigit1, binding.btnDigit2, binding.btnDigit3, binding.btnDigit4,
               binding.btnDigit5, binding.btnDigit6, binding.btnDigit7, binding.btnDigit8, binding.btnDigit9)
            .forEachIndexed { digit, button -> button.setOnClickListener { typeDigit(digit) } }
        binding.btnBackspace.setOnClickListener { typed = typed.dropLast(1); updateNumber() }
        binding.btnNumConfirm.setOnClickListener { if (typed.isNotEmpty()) showStep(Step.BODY) }

        binding.btnHead.setOnClickListener  { save(BodyParts.HEAD) }
        binding.btnTorso.setOnClickListener { save(BodyParts.TORSO) }
        binding.btnArm.setOnClickListener   { limb = "ARM"; showStep(Step.SIDE) }
        binding.btnLeg.setOnClickListener   { limb = "LEG"; showStep(Step.SIDE) }
        binding.btnLeft.setOnClickListener  { save(if (limb == "ARM") BodyParts.LEFT_ARM else BodyParts.LEFT_LEG) }
        binding.btnRight.setOnClickListener { save(if (limb == "ARM") BodyParts.RIGHT_ARM else BodyParts.RIGHT_LEG) }

        binding.btnInjuryCancel.setOnClickListener { finish() }   // stoppage keeps running either way

        updateNumber()
        showStep(Step.TEAM)
    }

    private fun showStep(step: Step) {
        binding.layoutTeam.visibility   = if (step == Step.TEAM)   View.VISIBLE else View.GONE
        binding.layoutNumber.visibility = if (step == Step.NUMBER) View.VISIBLE else View.GONE
        binding.layoutBody.visibility   = if (step == Step.BODY)   View.VISIBLE else View.GONE
        binding.layoutSide.visibility   = if (step == Step.SIDE)   View.VISIBLE else View.GONE
        val team = app.eventViewModel.team(isHome).name
        binding.txtInjuryTitle.text = when (step) {
            Step.TEAM   -> "🩹 INJURY · WHICH TEAM?"
            Step.NUMBER -> "🩹 ${app.eventViewModel.team(isHome).label} · #"
            Step.BODY   -> "🩹 #$typed · WHERE?"
            Step.SIDE   -> "🩹 #$typed · ${if (limb == "ARM") "ARM" else "LEG"}: WHICH SIDE?"
        }
        binding.injuryScroll.scrollTo(0, 0)
        binding.injuryScroll.requestFocus()
    }

    private fun typeDigit(d: Int) {
        typed = if (typed.length >= 2) "$d" else typed + d
        if (typed.length == 2 && typed.startsWith("0")) typed = typed.drop(1)
        updateNumber()
    }

    private fun updateNumber() {
        binding.txtPlayerNumber.text = typed.ifEmpty { "—" }
        binding.btnNumConfirm.isEnabled = typed.isNotEmpty()
        binding.btnNumConfirm.alpha = if (typed.isNotEmpty()) 1f else 0.4f
        binding.txtPlayerName.text = if (typed.isEmpty()) "Type the player number"
            else app.eventViewModel.team(isHome).getPlayer(typed.toInt())?.name ?: "Not on roster"
    }

    private fun save(bodyPart: String) {
        // If there was no GPS fix at the long-press, use one that arrived since (we're still near the player)
        val location = gps ?: app.locationTagger.freshFix()
        val event = app.eventViewModel.addInjury(
            isHome, typed.toIntOrNull() ?: 0, bodyPart, minute, added, period, elapsed, location, happenedAt
        )
        PhoneLink.sendEvents(this, listOf(event))
        vibrate(longArrayOf(0, 120))
        Toast.makeText(this, "🩹 #$typed ${BodyParts.label(bodyPart)}" + if (location != null) " 📍" else "", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    }
}
