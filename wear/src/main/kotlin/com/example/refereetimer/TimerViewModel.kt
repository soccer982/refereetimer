package com.example.refereetimer

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.refereetimer.shared.MatchClock
import org.json.JSONObject

/**
 * The watch's stopwatch. All timing lives in a saved MatchClock (timestamps),
 * so if the app is closed or crashes mid-match, reopening it picks up at the exact
 * right time. Every start/stop is saved and sent to the phone.
 */
class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("watch_match_clock", Context.MODE_PRIVATE)
    private var clock: MatchClock = loadClock()

    val currentClock: MatchClock get() = clock

    private val _timerState = MutableLiveData(clock.state)
    val timerState: LiveData<MatchClock.State> = _timerState

    private val _mainDisplay = MutableLiveData("")
    val mainDisplay: LiveData<String> = _mainDisplay

    private val _elapsedDisplay = MutableLiveData("")
    val elapsedDisplay: LiveData<String> = _elapsedDisplay

    private val _stoppageDisplay = MutableLiveData("")
    val stoppageDisplay: LiveData<String> = _stoppageDisplay

    private val _periodDisplay = MutableLiveData("")
    val periodDisplay: LiveData<String> = _periodDisplay

    private val _homeTeamName = MutableLiveData(clock.homeName)
    val homeTeamName: LiveData<String> = _homeTeamName

    private val _awayTeamName = MutableLiveData(clock.awayName)
    val awayTeamName: LiveData<String> = _awayTeamName

    /** True from the moment real period time passes the period length (screen goes light red). */
    private val _inAddedTime = MutableLiveData(false)
    val inAddedTime: LiveData<Boolean> = _inAddedTime

    /** Big label shown ABOVE the countdown: "ADDED TIME", "STOPPAGE", "TIME UP"... */
    private val _phaseLabel = MutableLiveData("")
    val phaseLabel: LiveData<String> = _phaseLabel

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 250L)
        }
    }

    init {
        handler.post(ticker)
        // After a restart, make sure the phone has the latest clock
        if (clock.state != MatchClock.State.READY) PhoneLink.sendClock(application, clock)
    }

    override fun onCleared() {
        handler.removeCallbacks(ticker)
    }

    // ── Buttons ──

    /**
     * The one main button: START → STOP ↔ RESUME → END PERIOD/MATCH → START NEXT.
     * STOP freezes the countdown and records stoppage; that stoppage is the added time
     * the countdown runs into after the period length has passed.
     */
    fun onMainButtonClick() {
        val now = System.currentTimeMillis()
        update(when (clock.state) {
            MatchClock.State.READY      -> clock.kickOff(now)
            MatchClock.State.RUNNING    -> clock.startStoppage(now)
            MatchClock.State.PAUSED     -> clock.endStoppage(now)
            MatchClock.State.TIME_UP    -> clock.endPeriod(now)
            MatchClock.State.BREAK      -> clock.nextPeriod(now)
            MatchClock.State.MATCH_OVER -> clock
        })
    }

    fun resetMatch() {
        getApplication<WearApp>().trackRecorder.clear()
        update(clock.reset(System.currentTimeMillis()))
    }

    /** From the phone's Match Setup. Lengths only change before kickoff. */
    fun configure(periods: Int, periodMins: Int, breakMins: Int, home: String, away: String) =
        update(clock.configured(periods, periodMins, breakMins, home, away, System.currentTimeMillis()))

    fun setTeamNames(home: String, away: String) =
        update(clock.withNames(home, away, System.currentTimeMillis()))

    /** (minute, addedTime) for an event happening right now. */
    fun minuteNow(): Pair<Int, Int> = clock.minuteAt(System.currentTimeMillis())

    fun matchClockNow(): Long = clock.matchClockMs(System.currentTimeMillis())

    // ── Internals ──

    private fun update(newClock: MatchClock) {
        if (newClock == clock) return
        // Period just ended: send its full movement track to the phone
        if (clock.isLive && !newClock.isLive) getApplication<WearApp>().trackRecorder.sendPeriod(clock.period)
        clock = newClock
        saveClock()
        publish(System.currentTimeMillis())
        PhoneLink.sendClock(getApplication<Application>(), clock)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val checked = clock.checkTimeUp(now)
        if (checked.state != clock.state) update(checked) else publish(now)
    }

    private fun publish(now: Long) {
        val c = clock
        set(_timerState, c.state)
        set(_homeTeamName, c.homeName)
        set(_awayTeamName, c.awayName)
        val breakLeft = c.breakRemainingMs(now)
        val addedTime = c.isAddedTime(now)
        set(_mainDisplay, when (c.state) {
            MatchClock.State.READY      -> MatchClock.format(c.periodMillis)
            MatchClock.State.TIME_UP    -> "+" + MatchClock.format(c.overtimeMs(now))   // past 0:00, until END PERIOD
            MatchClock.State.BREAK      -> if (breakLeft >= 0) MatchClock.format(breakLeft) else "+" + MatchClock.format(-breakLeft)
            MatchClock.State.MATCH_OVER -> "FT"
            else                        -> MatchClock.format(c.remainingMs(now))        // frozen during stoppage
        })
        // E = total real time since kickoff (intervals included); keeps running until END MATCH
        set(_elapsedDisplay, "E " + MatchClock.format(c.elapsedMs(now)))
        // S = stoppage recorded this period; whole match at full time
        set(_stoppageDisplay, "S " + MatchClock.format(
            if (c.state == MatchClock.State.MATCH_OVER) c.totalStoppageMs(now) else c.stoppageMs(now)))
        set(_phaseLabel, when {
            c.state == MatchClock.State.TIME_UP  -> if (c.isFinalPeriod) "TIME UP · END MATCH" else "TIME UP · END PERIOD"
            c.state == MatchClock.State.PAUSED && addedTime -> "ADDED TIME · ⏱ STOPPAGE"
            c.state == MatchClock.State.PAUSED   -> "⏱ STOPPAGE"
            addedTime                            -> "ADDED TIME"
            c.state == MatchClock.State.BREAK    -> if (breakLeft >= 0) "INTERVAL" else "INTERVAL OVER"
            c.state == MatchClock.State.MATCH_OVER -> "FULL TIME"
            c.state == MatchClock.State.RUNNING  -> "PERIOD ${c.period} OF ${c.regularPeriods}"
            else -> ""
        })
        set(_periodDisplay, when (c.state) {
            MatchClock.State.READY      -> c.formatDescription()
            MatchClock.State.BREAK      -> "P${c.period + 1} next"
            MatchClock.State.MATCH_OVER -> "Total stoppage " + MatchClock.format(c.totalStoppageMs(now))
            else                        -> "swipe ← away · home →"
        })
        // Entering added time: one long buzz (the triple buzz comes at 0:00)
        if (addedTime && _inAddedTime.value == false && c.state != MatchClock.State.TIME_UP) buzz(longArrayOf(0, 700))
        set(_inAddedTime, addedTime || c.state == MatchClock.State.TIME_UP)
    }

    @Suppress("DEPRECATION")
    private fun buzz(pattern: LongArray) {
        try {
            val v = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) { /* no vibrator */ }
    }

    private fun <T> set(live: MutableLiveData<T>, value: T) {
        if (live.value != value) live.value = value
    }

    private fun saveClock() {
        // commit() (not apply()) so it's on disk before anything else can go wrong
        prefs.edit().putString(KEY_CLOCK, clock.toJson().toString()).commit()
    }

    private fun loadClock(): MatchClock = try {
        prefs.getString(KEY_CLOCK, null)?.let { MatchClock.fromJson(JSONObject(it)) } ?: MatchClock()
    } catch (e: Exception) {
        MatchClock()
    }

    companion object {
        private const val KEY_CLOCK = "clock"
    }
}
