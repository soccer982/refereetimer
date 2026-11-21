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
import com.example.refereetimer.shared.models.MatchEvent.FoulType
import com.example.refereetimer.shared.models.Player

/**
 * Log an event for one team (swipe right = home, left = away):
 *   GOAL:  scorer # → ✓ → assist # → ✓   (or "No assist")
 *   FOUL:  player # → ✓ → DFK / IFK / PK            (GPS-tagged)
 *   CARD:  player # → ✓ → misconduct code → SAVE     (GPS-tagged)
 * Numbers are typed on the keypad; the crown also steps the number or code.
 */
class QuickEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickEventBinding
    private val app by lazy { application as WearApp }

    private enum class Kind { GOAL, FOUL, CARD }
    private enum class Step { TYPE, GOAL_TYPE, NUMBER, ASSIST, FOUL_TYPE, CODE }

    private var isHome = true
    private var kind = Kind.GOAL
    private var cardType = CardType.YELLOW
    private var step = Step.TYPE

    private var typed = ""             // digits on the keypad for the current step
    private var playerNumber = 0       // scorer / fouling / carded player
    private var goalIsPk = false
    private var goalIsOwn = false      // own goal: scorer is on THIS team, assist is an opponent
    private var codeIndex = 0
    private var rotaryAccumulator = 0f

    private val isTeamOfficial: Boolean get() = playerNumber == Player.TEAM_OFFICIAL_NUMBER

    /** USSF codes: player codes, or CTO/SOTO codes for a team official. */
    private val codes: List<MisconductCode>
        get() = when {
            isTeamOfficial && cardType == CardType.YELLOW -> MisconductCodes.TEAM_OFFICIAL_CAUTIONS
            isTeamOfficial -> MisconductCodes.TEAM_OFFICIAL_SEND_OFFS
            cardType == CardType.YELLOW -> MisconductCodes.CAUTIONS
            else -> MisconductCodes.SEND_OFFS
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isHome = intent.getBooleanExtra(EXTRA_IS_HOME, true)
        binding.txtTeamLabel.text = app.eventViewModel.team(isHome).label.uppercase()
        binding.txtTeamLabel.setTextColor(android.graphics.Color.parseColor(if (isHome) "#64B5F6" else "#EF9A9A"))

        binding.btnTypeGoal.setOnClickListener   { kind = Kind.GOAL; showStep(Step.GOAL_TYPE) }
        binding.btnGoalOpen.setOnClickListener { goalIsPk = false; goalIsOwn = false; startNumber() }
        binding.btnGoalPk.setOnClickListener   { goalIsPk = true;  goalIsOwn = false; startNumber() }
        binding.btnGoalOg.setOnClickListener   { goalIsPk = false; goalIsOwn = true;  startNumber() }
        binding.btnTypeFoul.setOnClickListener   { kind = Kind.FOUL; app.locationTagger.requestOneShot(); startNumber() }
        binding.btnTypeYellow.setOnClickListener { startCard(CardType.YELLOW) }
        binding.btnTypeRed.setOnClickListener    { startCard(CardType.RED) }

        listOf(binding.btnDigit0, binding.btnDigit1, binding.btnDigit2, binding.btnDigit3, binding.btnDigit4,
               binding.btnDigit5, binding.btnDigit6, binding.btnDigit7, binding.btnDigit8, binding.btnDigit9)
            .forEachIndexed { digit, button -> button.setOnClickListener { typeDigit(digit) } }
        binding.btnBackspace.setOnClickListener { typed = typed.dropLast(1); updateNumber() }
        binding.btnNumConfirm.setOnClickListener { confirmNumber() }
        binding.btnNoAssist.setOnClickListener { saveGoal(assist = null) }
        binding.btnTeamOfficial.setOnClickListener {
            playerNumber = Player.TEAM_OFFICIAL_NUMBER
            codeIndex = 0; updateCode(); showStep(Step.CODE)
        }

        binding.btnFoulDfk.setOnClickListener { saveFoul(FoulType.DFK, isPK = false) }
        binding.btnFoulIfk.setOnClickListener { saveFoul(FoulType.IFK, isPK = false) }
        binding.btnFoulPk.setOnClickListener  { saveFoul(FoulType.DFK, isPK = true) }

        binding.btnCodeNext.setOnClickListener { changeCode(+1) }
        binding.btnCodePrev.setOnClickListener { changeCode(-1) }
        binding.btnCodeConfirm.setOnClickListener { saveCard() }

        binding.btnCancel.setOnClickListener { finish() }
        binding.quickEventScroll.setOnGenericMotionListener { _, event -> handleRotary(event) }

        showStep(Step.TYPE)
    }

    private fun startCard(type: CardType) {
        kind = Kind.CARD
        cardType = type
        app.locationTagger.requestOneShot()   // head start on a GPS fix
        startNumber()
    }

    private fun startNumber() { typed = ""; showStep(Step.NUMBER) }

    private fun confirmNumber() {
        if (typed.isEmpty()) return
        when (step) {
            Step.NUMBER -> {
                playerNumber = typed.toInt()
                when (kind) {
                    Kind.GOAL -> if (goalIsPk) saveGoal(assist = null)        // no assist on a penalty kick
                                 else { typed = ""; showStep(Step.ASSIST) }
                    Kind.FOUL -> showStep(Step.FOUL_TYPE)
                    Kind.CARD -> { codeIndex = 0; updateCode(); showStep(Step.CODE) }
                }
            }
            Step.ASSIST -> saveGoal(assist = typed.toInt())
            else -> {}
        }
    }

    private fun showStep(s: Step) {
        step = s
        val numberStep = s == Step.NUMBER || s == Step.ASSIST
        binding.layoutType.visibility     = if (s == Step.TYPE) View.VISIBLE else View.GONE
        binding.layoutGoalType.visibility = if (s == Step.GOAL_TYPE) View.VISIBLE else View.GONE
        binding.layoutNumber.visibility   = if (numberStep) View.VISIBLE else View.GONE
        binding.layoutFoulType.visibility = if (s == Step.FOUL_TYPE) View.VISIBLE else View.GONE
        binding.layoutCode.visibility     = if (s == Step.CODE) View.VISIBLE else View.GONE
        binding.btnNoAssist.visibility    = if (s == Step.ASSIST) View.VISIBLE else View.GONE
        binding.btnTeamOfficial.visibility = if (s == Step.NUMBER && kind == Kind.CARD) View.VISIBLE else View.GONE
        binding.txtStepTitle.text = when (s) {
            Step.TYPE      -> "GOAL, FOUL OR CARD?"
            Step.GOAL_TYPE -> "⚽ WHAT KIND OF GOAL?"
            Step.NUMBER    -> when (kind) {
                Kind.GOAL -> when { goalIsOwn -> "OG: PUT IN BY #"; goalIsPk -> "⚽ PK TAKEN BY #"; else -> "⚽ SCORER #" }
                Kind.FOUL -> "🚩 FOUL BY #"
                Kind.CARD -> if (cardType == CardType.YELLOW) "🟨 PLAYER #" else "🟥 PLAYER #"
            }
            Step.ASSIST    -> if (goalIsOwn) "OG #$playerNumber · ASSIST (OPPONENT) #*" else "⚽ #$playerNumber · ASSIST BY #"
            Step.FOUL_TYPE -> "🚩 #$playerNumber · FREE KICK?"
            Step.CODE      -> {
                val who = if (isTeamOfficial) "COACH" else "#$playerNumber"
                if (cardType == CardType.YELLOW) "🟨 $who CAUTION FOR" else "🟥 $who SEND-OFF FOR"
            }
        }
        if (numberStep) updateNumber()
        binding.quickEventScroll.scrollTo(0, 0)
        binding.quickEventScroll.requestFocus()
    }

    // ── Number keypad ──

    private fun typeDigit(d: Int) {
        typed = if (typed.length >= 2) "$d" else typed + d
        if (typed.length == 2 && typed.startsWith("0")) typed = typed.drop(1)   // "07" → "7"
        updateNumber()
    }

    /** Crown: step the number up/down. */
    private fun changeNumber(delta: Int) {
        val start = typed.toIntOrNull() ?: 0
        typed = (start + delta).coerceIn(0, 99).toString()
        updateNumber()
    }

    private fun updateNumber() {
        binding.txtPlayerNumber.text = typed.ifEmpty { "—" }
        binding.btnNumConfirm.isEnabled = typed.isNotEmpty()
        binding.btnNumConfirm.alpha = if (typed.isNotEmpty()) 1f else 0.4f
        if (typed.isEmpty()) {
            binding.txtPlayerName.text = if (step == Step.ASSIST) "Type assist #, or No assist" else "Type the player number"
            return
        }
        val n = typed.toInt()
        val events = app.eventViewModel
        // An own-goal assist is an opposing player: look them up on the other team
        val roster = if (step == Step.ASSIST && goalIsOwn) events.team(!isHome) else events.team(isHome)
        val name = (roster.getPlayer(n)?.name ?: "Not on roster") + if (step == Step.ASSIST && goalIsOwn) " (opponent)" else ""
        val warning = when {
            step == Step.ASSIST && n == playerNumber && !goalIsOwn -> "  ⚠ same as scorer"
            events.isSentOff(isHome, n) -> "  ⚠ sent off"
            kind == Kind.CARD && cardType == CardType.YELLOW && events.yellowCount(isHome, n) >= 1 -> "  ⚠ has yellow"
            else -> ""
        }
        binding.txtPlayerName.text = name + warning
    }

    // ── Misconduct code ──

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
        if (step == Step.TYPE || step == Step.GOAL_TYPE || step == Step.FOUL_TYPE) return false   // let the list scroll normally
        rotaryAccumulator += -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        while (rotaryAccumulator >= 1f) { rotaryAccumulator -= 1f; onRotaryStep(+1) }
        while (rotaryAccumulator <= -1f) { rotaryAccumulator += 1f; onRotaryStep(-1) }
        return true
    }

    private fun onRotaryStep(delta: Int) {
        if (step == Step.CODE) changeCode(delta) else changeNumber(delta)
    }

    // ── Save ──

    private fun saveGoal(assist: Int?) {
        val timer = app.timerViewModel
        val (minute, added) = timer.minuteNow()
        val event = app.eventViewModel.addGoal(
            isHome, playerNumber, assist?.takeIf { goalIsOwn || it != playerNumber },
            minute, added, timer.currentClock.period, timer.matchClockNow(),
            isPK = goalIsPk, isOwnGoal = goalIsOwn
        )
        PhoneLink.sendEvents(this, listOf(event))
        val label = when { goalIsOwn -> "OG #$playerNumber → goal for ${app.eventViewModel.team(!isHome).label}"; goalIsPk -> "⚽ PK #$playerNumber"; else -> "⚽ GOAL #$playerNumber" }
        confirm(label + (assist?.let { " (A #$it${if (goalIsOwn) "*" else ""})" } ?: ""))
    }

    private fun saveFoul(type: FoulType, isPK: Boolean) {
        val timer = app.timerViewModel
        val (minute, added) = timer.minuteNow()
        val gps = app.locationTagger.freshFix()
        val event = app.eventViewModel.addFoul(
            isHome, playerNumber, type, isPK, minute, added, timer.currentClock.period, timer.matchClockNow(), gps
        )
        PhoneLink.sendEvents(this, listOf(event))
        val label = if (isPK) "PK" else type.name
        confirm("🚩 #$playerNumber $label" + if (gps != null) " 📍" else "")
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
            confirm("2nd yellow → 🟥 RED (${created.last().misconductCode})$gpsNote")
        } else {
            val who = if (isTeamOfficial) "Coach" else "#$playerNumber"
            confirm((if (cardType == CardType.YELLOW) "🟨" else "🟥") + " $who ${codes[codeIndex].code}$gpsNote")
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
