package com.example.refereetimer.shared

import org.json.JSONObject

/**
 * The match clock, stored as timestamps so it survives the app closing.
 *
 * RUNNING-CLOCK model (FIFA style):
 *  - The period countdown never stops once a period starts.
 *  - STOP records stoppage (injuries, etc.) while the countdown keeps going.
 *  - At 0:00 the period goes into ADDED TIME: a count-up timer shows added time played,
 *    against the stoppage recorded ("owed"). The referee ends the period.
 *  - Total ELAPSED is real time since kickoff, including the intervals between periods,
 *    and keeps running through the final added time until the referee ends the match.
 *
 * Every function takes `now` (System.currentTimeMillis()) and returns a NEW clock.
 */
data class MatchClock(
    val state: State = State.READY,
    val period: Int = 1,
    val regularPeriods: Int = 2,
    val periodMillis: Long = 45 * 60_000L,
    val breakMillis: Long = 10 * 60_000L,
    val kickoffAt: Long = 0L,             // start of period 1 (for total elapsed)
    val periodStartedAt: Long = 0L,       // start of the current period
    val stoppageBeforeMs: Long = 0L,      // stoppage recorded this period, closed segments
    val stoppageStartedAt: Long = 0L,     // start of an open stoppage (state PAUSED)
    val periodEndedPlayedMs: Long = 0L,   // length of the period just ended (shown during interval)
    val breakStartedAt: Long = 0L,
    val endedAt: Long = 0L,               // final whistle (freezes total elapsed)
    val earlierStoppageMs: Long = 0L,     // stoppage from previous periods
    val homeName: String = "Home",
    val awayName: String = "Away",
    val updatedAt: Long = 0L
) {
    /** PAUSED = stoppage being recorded (the countdown keeps running). */
    enum class State { READY, RUNNING, PAUSED, TIME_UP, BREAK, MATCH_OVER }

    val isLive: Boolean get() = state == State.RUNNING || state == State.PAUSED || state == State.TIME_UP
    val isFinalPeriod: Boolean get() = period >= regularPeriods

    /** Time since the current period kicked off. */
    fun playedMs(now: Long): Long = when (state) {
        State.RUNNING, State.PAUSED, State.TIME_UP -> (now - periodStartedAt).coerceAtLeast(0)
        State.BREAK, State.MATCH_OVER -> periodEndedPlayedMs
        State.READY -> 0L
    }

    /** Stoppage recorded this period = added time owed. */
    fun stoppageMs(now: Long): Long =
        stoppageBeforeMs + if (state == State.PAUSED && stoppageStartedAt > 0) (now - stoppageStartedAt).coerceAtLeast(0) else 0L

    fun totalStoppageMs(now: Long): Long = earlierStoppageMs + stoppageMs(now)

    fun remainingMs(now: Long): Long = (periodMillis - playedMs(now)).coerceAtLeast(0)

    /** The count-up shown in added time. */
    fun overtimeMs(now: Long): Long = (playedMs(now) - periodMillis).coerceAtLeast(0)

    /** True once the added time played has reached the stoppage owed. */
    fun addedTimeComplete(now: Long): Boolean = state == State.TIME_UP && overtimeMs(now) >= stoppageMs(now)

    /** Interval remaining (negative once the interval has run over). */
    fun breakRemainingMs(now: Long): Long = if (state == State.BREAK) breakMillis - (now - breakStartedAt) else 0L

    /** Real time since kickoff, intervals included; frozen at the final whistle. */
    fun elapsedMs(now: Long): Long = when {
        kickoffAt == 0L -> 0L
        endedAt > 0L -> endedAt - kickoffAt
        else -> (now - kickoffAt).coerceAtLeast(0)
    }

    /** Game clock, e.g. 30:10 in the 2nd of 3 × 25-minute periods. */
    fun matchClockMs(now: Long): Long = (period - 1) * periodMillis + playedMs(now)

    /** The minute a referee writes down, as (minute, addedTime): "23'" or "25+2'". */
    fun minuteAt(now: Long): Pair<Int, Int> {
        val periodMins = (periodMillis / 60_000L).toInt()
        val base = (period - 1) * periodMins
        val played = playedMs(now)
        return if (played < periodMillis) Pair(base + (played / 60_000L).toInt() + 1, 0)
        else Pair(base + periodMins, ((played - periodMillis) / 60_000L).toInt() + 1)
    }

    /** e.g. "3 × 25 min · 5 min intervals" */
    fun formatDescription(): String {
        val p = "$regularPeriods × ${periodMillis / 60_000} min"
        return if (regularPeriods > 1) "$p · ${breakMillis / 60_000} min intervals" else p
    }

    // ── Transitions ──

    fun configured(periods: Int, periodMins: Int, breakMins: Int, home: String, away: String, now: Long): MatchClock =
        if (state == State.READY) copy(
            regularPeriods = periods.coerceIn(1, 8),
            periodMillis = periodMins.coerceIn(1, 120) * 60_000L,
            breakMillis = breakMins.coerceIn(0, 60) * 60_000L,
            homeName = home, awayName = away, updatedAt = now
        ) else copy(homeName = home, awayName = away, updatedAt = now) // never change lengths mid-match

    fun withNames(home: String, away: String, now: Long) = copy(homeName = home, awayName = away, updatedAt = now)

    fun kickOff(now: Long): MatchClock = if (state != State.READY) this else copy(
        state = State.RUNNING, period = 1, kickoffAt = now, periodStartedAt = now,
        stoppageBeforeMs = 0, stoppageStartedAt = 0, periodEndedPlayedMs = 0,
        breakStartedAt = 0, endedAt = 0, earlierStoppageMs = 0, updatedAt = now
    )

    /** STOP: start recording stoppage. The countdown keeps running. */
    fun startStoppage(now: Long): MatchClock = if (state != State.RUNNING) this
        else copy(state = State.PAUSED, stoppageStartedAt = now, updatedAt = now)

    /** RESUME: stop recording stoppage. */
    fun endStoppage(now: Long): MatchClock = if (state != State.PAUSED) this else copy(
        state = State.RUNNING, stoppageBeforeMs = stoppageMs(now), stoppageStartedAt = 0, updatedAt = now
    ).checkTimeUp(now)

    /**
     * Call regularly. When the countdown reaches 0 the period goes into added time.
     * Uses the exact moment time ran out (not "now"), so it's correct even if the
     * app was closed when it happened.
     */
    fun checkTimeUp(now: Long): MatchClock {
        if ((state != State.RUNNING && state != State.PAUSED) || playedMs(now) < periodMillis) return this
        val timeUpAt = periodStartedAt + periodMillis
        return copy(
            state = State.TIME_UP,
            stoppageBeforeMs = if (state == State.PAUSED) stoppageMs(timeUpAt) else stoppageBeforeMs,
            stoppageStartedAt = 0,
            updatedAt = now
        )
    }

    /** END PERIOD / END MATCH. */
    fun endPeriod(now: Long): MatchClock {
        if (!isLive) return this
        val c = checkTimeUp(now)
        val frozen = c.copy(
            stoppageBeforeMs = c.stoppageMs(now), stoppageStartedAt = 0,
            periodEndedPlayedMs = c.playedMs(now)
        )
        return if (!isFinalPeriod) frozen.copy(state = State.BREAK, breakStartedAt = now, updatedAt = now)
        else frozen.copy(state = State.MATCH_OVER, endedAt = now, updatedAt = now)
    }

    fun nextPeriod(now: Long): MatchClock = if (state != State.BREAK) this else copy(
        state = State.RUNNING, period = period + 1, periodStartedAt = now,
        earlierStoppageMs = earlierStoppageMs + stoppageBeforeMs,
        stoppageBeforeMs = 0, stoppageStartedAt = 0, periodEndedPlayedMs = 0,
        breakStartedAt = 0, updatedAt = now
    )

    fun reset(now: Long) = MatchClock(
        regularPeriods = regularPeriods, periodMillis = periodMillis, breakMillis = breakMillis,
        homeName = homeName, awayName = awayName, updatedAt = now
    )

    // ── Saving / sending ──

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 3)
        put("state", state.name); put("period", period); put("regularPeriods", regularPeriods)
        put("periodMillis", periodMillis); put("breakMillis", breakMillis)
        put("kickoffAt", kickoffAt); put("periodStartedAt", periodStartedAt)
        put("stoppageBeforeMs", stoppageBeforeMs); put("stoppageStartedAt", stoppageStartedAt)
        put("periodEndedPlayedMs", periodEndedPlayedMs); put("breakStartedAt", breakStartedAt)
        put("endedAt", endedAt); put("earlierStoppageMs", earlierStoppageMs)
        put("homeName", homeName); put("awayName", awayName); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JSONObject): MatchClock {
            val base = MatchClock(
                regularPeriods = j.optInt("regularPeriods", 2),
                periodMillis = j.optLong("periodMillis", 45 * 60_000L),
                breakMillis = j.optLong("breakMillis", 10 * 60_000L),
                homeName = j.optString("homeName", "Home"),
                awayName = j.optString("awayName", "Away"),
                updatedAt = j.optLong("updatedAt", 0L)
            )
            // A save from the older stop-clock version can't be converted reliably: keep settings only.
            if (j.optInt("v", 0) < 3) return base
            return base.copy(
                state = runCatching { State.valueOf(j.optString("state")) }.getOrDefault(State.READY),
                period = j.optInt("period", 1),
                kickoffAt = j.optLong("kickoffAt", 0L),
                periodStartedAt = j.optLong("periodStartedAt", 0L),
                stoppageBeforeMs = j.optLong("stoppageBeforeMs", 0L),
                stoppageStartedAt = j.optLong("stoppageStartedAt", 0L),
                periodEndedPlayedMs = j.optLong("periodEndedPlayedMs", 0L),
                breakStartedAt = j.optLong("breakStartedAt", 0L),
                endedAt = j.optLong("endedAt", 0L),
                earlierStoppageMs = j.optLong("earlierStoppageMs", 0L)
            )
        }

        /** mm:ss, or h:mm:ss once past an hour. */
        fun format(ms: Long): String {
            val totalSec = ms.coerceAtLeast(0) / 1000
            val h = totalSec / 3600; val m = (totalSec / 60) % 60; val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }
    }
}
