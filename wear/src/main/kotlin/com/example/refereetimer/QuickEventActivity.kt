package com.example.refereetimer

import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.databinding.ActivityQuickEventBinding
import com.example.refereetimer.shared.MisconductCode
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.MatchEvent.CardType

/**
 * Log a GOAL or a CARD for one team:
 *   GOAL:  pick type → pick number → ✓
 *   CARD:  pick type → pick number → ✓ → pick misconduct code → SAVE (GPS-tagged)
 * Turn the crown to change the number or code.
 */
class QuickEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickEventBinding
    private val app by lazy { application as WearApp }

    private var isHome = true
    private var isGoal = true
    private var cardType = CardType.YELLOW
    private var playerNumber = 1
    private var codeIndex = 0
    private var rotaryAccumulator = 0f
    private var step = Step.TYPE

    private enum class Step { TYPE, NUMBER, CODE }

    private val codes: List<MisconductCode>
        get() = if (cardType == CardType.YELLOW) MisconductCodes.CAUTIONS else MisconductCodes.SEND_OFFS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isHome = intent.getBooleanExtra(EXTRA_IS_HOME, true)
        binding.txtTeamLabel.text = app.eventViewModel.team(isHome).name.uppercase()

        binding.btnTypeGoal.setOnClickListener   { isGoal = true; showStep(Step.NUMBER) }
        binding.btnTypeYellow.setOnClickListener { startCard(CardType.YELLOW) }
        binding.btnTypeRed.setOnClickListener    { startCard(CardType.RED) }

        binding.btnNumUp.setOnClickListener   { changeNumber(+1) }
        binding.btnNumDown.setOnClickListener { changeNumber(-1) }
        binding.btnNumConfirm.setOnClickListener {
            if (isGoal) saveGoal() else { codeIndex = 0; updateCode(); showStep(Step.CODE) }
        }

        binding.btnCodeNext.setOnClickListener { changeCode(+1) }
        binding.btnCodePrev.setOnClickListener { changeCode(-1) }
        binding.btnCodeConfirm.setOnClickListener { saveCard() }

        binding.btnCancel.setOnClickListener { finish() }

        // Crown: scrolls the list on the first screen, changes number/code on the others
        binding.quickEventScroll.setOnGenericMotionListener { _, event -> handleRotary(event) }

        updateNumber()
        showStep(Step.TYPE)
    }

    private fun startCard(type: CardType) {
        isGoal = false
        cardType = type
        app.locationTagger.requestOneShot()   // head start on a GPS fix
        showStep(Step.NUMBER)
    }

    private fun showStep(s: Step) {
        step = s
        binding.layoutType.visibility   = if (s == Step.TYPE)   View.VISIBLE else View.GONE
        binding.layoutNumber.visibility = if (s == Step.NUMBER) View.VISIBLE else View.GONE
        binding.layoutCode.visibility   = if (s == Step.CODE)   View.VISIBLE else View.GONE
        binding.txtStepTitle.text = when (s) {
            Step.TYPE   -> "GOAL OR CARD?"
            Step.NUMBER -> if (isGoal) "⚽ SCORER #" else if (cardType == CardType.YELLOW) "🟨 PLAYER #" else "🟥 PLAYER #"
            Step.CODE   -> if (cardType == CardType.YELLOW) "🟨 CAUTION FOR" else "🟥 SEND-OFF FOR"
        }
        binding.quickEventScroll.scrollTo(0, 0)
        binding.quickEventScroll.requestFocus()
    }

    // ── Number ──

    private fun changeNumber(delta: Int) {
        playerNumber = (playerNumber + delta).coerceIn(0, 99)
        updateNumber()
    }

    private fun updateNumber() {
        binding.txtPlayerNumber.text = playerNumber.toString()
        val player = app.eventViewModel.team(isHome).getPlayer(playerNumber)
        val warning = when {
            app.eventViewModel.isSentOff(isHome, playerNumber) -> "  ⚠ sent off"
            !isGoal && cardType == CardType.YELLOW && app.eventViewModel.yellowCount(isHome, playerNumber) >= 1 -> "  ⚠ has yellow"
            else -> ""
        }
        binding.txtPlayerName.text = (player?.name ?: "Not on roster") + warning
    }

    // ── Code ──

    private fun changeCode(delta: Int) {
        codeIndex = (codeIndex + delta).mod(codes.size)
        updateCode()
    }

    private fun updateCode() {
        val c = codes[codeIndex]
        binding.txtCurrentCode.text = c.code
        binding.txtCodeDescription.text = c.description
    }

    private fun handleRotary(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false
        if (step == Step.TYPE) return false   // let the list scroll normally
        rotaryAccumulator += -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        while (rotaryAccumulator >= 1f) { rotaryAccumulator -= 1f; onRotaryStep(+1) }
        while (rotaryAccumulator <= -1f) { rotaryAccumulator += 1f; onRotaryStep(-1) }
        return true
    }

    private fun onRotaryStep(delta: Int) {
        if (step == Step.NUMBER) changeNumber(delta) else if (step == Step.CODE) changeCode(delta)
    }

    // ── Save ──

    private fun saveGoal() {
        val timer = app.timerViewModel
        val (minute, added) = timer.minuteNow()
        val event = app.eventViewModel.addGoal(
            isHome, playerNumber, minute, added, timer.currentClock.period, timer.matchClockNow()
        )
        PhoneLink.sendEvents(this, listOf(event))
        confirm("⚽ GOAL #$playerNumber")
    }

    private fun saveCard() {
        val timer = app.timerViewModel
        val (minute, added) = timer.minuteNow()
        val gps = app.locationTagger.freshFix()
        val created = app.eventViewModel.addCard(
            isHome, playerNumber, cardType, codes[codeIndex].code,
            minute, added, timer.currentClock.period, timer.matchClockNow(), gps
        )
        PhoneLink.sendEvents(this, created)

        val gpsNote = if (gps != null) " 📍" else " (no GPS fix)"
        if (created.size > 1) {
            vibrate(longArrayOf(0, 400, 150, 400))
            confirm("2nd yellow → 🟥 RED (2CT)$gpsNote")
        } else {
            confirm((if (cardType == CardType.YELLOW) "🟨" else "🟥") + " #$playerNumber ${codes[codeIndex].code}$gpsNote")
        }
    }

    private fun confirm(message: String) {
        vibrate(longArrayOf(0, 120))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val EXTRA_IS_HOME = "IS_HOME"
    }
}
