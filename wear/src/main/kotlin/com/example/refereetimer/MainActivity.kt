package com.example.refereetimer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.databinding.ActivityMainBinding
import com.example.refereetimer.shared.MatchClock
import kotlin.math.abs

/**
 * Watch main screen. The watch does four things: start, stop, goals, cards.
 *  - Big button: START → STOP ↔ RESUME → END PERIOD → START NEXT PERIOD
 *  - Swipe right = HOME event, swipe left = AWAY event
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app by lazy { application as WearApp }
    private val timer: TimerViewModel get() = app.timerViewModel
    private val events: MatchEventViewModel get() = app.eventViewModel
    private lateinit var gestureDetector: GestureDetector

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateGpsHint()
            syncLocationTracking(timer.currentClock.state)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        setupButtons()
        observe()

        if (!app.locationTagger.hasPermission()) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        updateGpsHint()
    }

    private fun setupButtons() {
        binding.btnStartMatch.setOnClickListener {
            timer.onMainButtonClick()
            vibrate(longArrayOf(0, 300))
        }
        binding.btnStartStop.setOnClickListener {
            timer.onMainButtonClick()
            vibrate(if (timer.currentClock.state == MatchClock.State.PAUSED) longArrayOf(0, 150, 100, 150) else longArrayOf(0, 200))
        }
        // Hold (not tap) to reset, so it can't happen by accident
        binding.btnReset.setOnLongClickListener {
            timer.resetMatch()
            events.reset()
            vibrate(longArrayOf(0, 100, 50, 100))
            true
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || !timer.currentClock.isLive) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > 80 && abs(velocityX) > 100) {
                    openQuickEvent(isHome = dx > 0)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun openQuickEvent(isHome: Boolean) {
        vibrate(longArrayOf(0, 50))
        startActivity(Intent(this, QuickEventActivity::class.java).putExtra(QuickEventActivity.EXTRA_IS_HOME, isHome))
    }

    private fun observe() {
        timer.homeTeamName.observe(this) { updateReadyTeams() }
        timer.awayTeamName.observe(this) { updateReadyTeams() }
        timer.mainDisplay.observe(this) { binding.mainTimerTextView.text = it }
        timer.elapsedDisplay.observe(this) { binding.elapsedTimerTextView.text = it }
        timer.stoppageDisplay.observe(this) { binding.stoppageTimerTextView.text = it }
        timer.periodDisplay.observe(this) {
            binding.periodTextView.text = it
            binding.txtReadyInfo.text = it
        }
        timer.addedTimeComplete.observe(this) { complete ->
            // In added time, the owed-stoppage text turns bold once it's been played
            binding.periodTextView.setTypeface(null, if (complete) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        events.homeScore.observe(this) { updateScore() }
        events.awayScore.observe(this) { updateScore() }

        var lastState: MatchClock.State? = null
        timer.timerState.observe(this) { state ->
            val isFirst = lastState == null
            if (state == lastState) return@observe
            lastState = state
            render(state, withVibration = !isFirst)
            syncLocationTracking(state)
        }
    }

    private fun render(state: MatchClock.State, withVibration: Boolean) {
        val ready = state == MatchClock.State.READY
        binding.readyLayout.visibility = if (ready) View.VISIBLE else View.GONE
        binding.timerLayout.visibility = if (ready) View.GONE else View.VISIBLE
        binding.btnReset.visibility = if (state == MatchClock.State.MATCH_OVER) View.VISIBLE else View.GONE
        binding.btnStartStop.visibility = if (state == MatchClock.State.MATCH_OVER) View.GONE else View.VISIBLE

        fun button(text: String, color: String) {
            binding.btnStartStop.text = text
            binding.btnStartStop.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        }
        var timerColor = Color.WHITE
        when (state) {
            MatchClock.State.RUNNING -> button("STOP", "#C62828")
            MatchClock.State.PAUSED  -> button("RESUME", "#1976D2")
            MatchClock.State.TIME_UP -> {
                button(if (timer.currentClock.isFinalPeriod) "END MATCH" else "END PERIOD", "#B71C1C")
                timerColor = Color.parseColor("#B71C1C")
                if (withVibration) vibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            }
            MatchClock.State.BREAK -> {
                button("START P${timer.currentClock.period + 1}", "#2E7D32"); timerColor = Color.YELLOW
                if (withVibration) vibrate(longArrayOf(0, 1000, 500, 1000))
            }
            MatchClock.State.MATCH_OVER -> {
                timerColor = Color.GRAY
                if (withVibration) vibrate(longArrayOf(0, 1500, 500, 1500, 500, 1500))
            }
            MatchClock.State.READY -> {}
        }
        binding.mainTimerTextView.setTextColor(timerColor)
        applyAddedTimeTheme(state == MatchClock.State.TIME_UP)
        // While recording stoppage, the stoppage timer is highlighted
        binding.stoppageTimerTextView.setTypeface(null,
            if (state == MatchClock.State.PAUSED) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** Added time: light red screen with dark text, so it's obvious at a glance. */
    private fun applyAddedTimeTheme(addedTime: Boolean) {
        val bg = if (addedTime) Color.parseColor("#FFCDD2") else Color.BLACK
        val text = if (addedTime) Color.parseColor("#4A0000") else Color.WHITE
        binding.timerLayout.setBackgroundColor(bg)
        binding.scoreTextView.setTextColor(text)
        binding.periodTextView.setTextColor(if (addedTime) text else Color.parseColor("#9E9E9E"))
        binding.elapsedTimerTextView.setTextColor(if (addedTime) Color.parseColor("#1B5E20") else Color.parseColor("#4CAF50"))
        binding.stoppageTimerTextView.setTextColor(if (addedTime) Color.parseColor("#B71C1C") else Color.parseColor("#F44336"))
    }

    /** Keep GPS warm while the match is live so card tags are instant. */
    private fun syncLocationTracking(state: MatchClock.State) {
        val live = state == MatchClock.State.RUNNING || state == MatchClock.State.PAUSED ||
            state == MatchClock.State.TIME_UP || state == MatchClock.State.BREAK
        if (live) app.locationTagger.start() else app.locationTagger.stop()
    }

    private fun updateGpsHint() {
        binding.txtGpsHint.text = if (app.locationTagger.hasPermission()) "📍 Cards will be GPS-tagged"
            else "📍 Allow location for card GPS tags"
    }

    private fun updateReadyTeams() {
        val c = timer.currentClock
        binding.txtReadyTeams.text = "${c.homeName} vs ${c.awayName}"
    }

    private fun updateScore() {
        binding.scoreTextView.text = "${events.homeScore.value ?: 0} - ${events.awayScore.value ?: 0}"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
