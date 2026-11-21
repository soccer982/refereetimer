package com.example.refereetimer.phone

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's record of the match. The watch is the stopwatch; the phone is the
 * official record. Everything here is saved after every change and restored on startup.
 */
class PhoneMatchViewModel(private val store: MatchStateStore) : ViewModel() {

    private val _homeTeam = MutableLiveData<Team?>()
    val homeTeam: LiveData<Team?> = _homeTeam

    private val _awayTeam = MutableLiveData<Team?>()
    val awayTeam: LiveData<Team?> = _awayTeam

    private val _homeTeamName = MutableLiveData("Home")
    val homeTeamName: LiveData<String> = _homeTeamName

    private val _awayTeamName = MutableLiveData("Away")
    val awayTeamName: LiveData<String> = _awayTeamName

    private val _homeScore = MutableLiveData(0)
    val homeScore: LiveData<Int> = _homeScore

    private val _awayScore = MutableLiveData(0)
    val awayScore: LiveData<Int> = _awayScore

    private val _timerDisplay = MutableLiveData("00:00")
    val timerDisplay: LiveData<String> = _timerDisplay

    private val _periodDisplay = MutableLiveData("Not started")
    val periodDisplay: LiveData<String> = _periodDisplay

    private val _timerState = MutableLiveData("READY")
    val timerState: LiveData<String> = _timerState

    private val _events = MutableLiveData<MutableList<MatchEvent>>(mutableListOf())
    val events: LiveData<MutableList<MatchEvent>> = _events

    private val _matchConfig = MutableLiveData<MatchConfig?>()
    val matchConfig: LiveData<MatchConfig?> = _matchConfig

    private val _gpsTrack = MutableLiveData<List<GpsReferenceService.GpsTrackPoint>>(emptyList())
    val gpsTrack: LiveData<List<GpsReferenceService.GpsTrackPoint>> = _gpsTrack

    private val _gpsStatus = MutableLiveData("GPS: no cards yet")
    val gpsStatus: LiveData<String> = _gpsStatus

    private val _totalStoppageMillis = MutableLiveData(0L)
    val totalStoppageMillis: LiveData<Long> = _totalStoppageMillis

    private val _kftmWinner = MutableLiveData<Pair<String, String>?>()
    val kftmWinner: LiveData<Pair<String, String>?> = _kftmWinner

    /** The watch's clock, mirrored. Phone time is calculated from it, so both screens agree. */
    private var clock = MatchClock()

    val regularPeriods: Int get() = clock.regularPeriods

    /** True if a match is under way (period lengths can't change until reset). */
    val isMatchInProgress: Boolean get() = clock.state != MatchClock.State.READY && clock.state != MatchClock.State.MATCH_OVER

    /** One line for the PDF, e.g. "3 × 25 min · 5 min intervals  ·  Total elapsed 1:30:10  ·  Stoppage 04:00". */
    fun matchSummary(): String {
        val now = System.currentTimeMillis()
        val parts = mutableListOf(clock.formatDescription())
        if (clock.kickoffAt > 0) parts.add("Total elapsed ${MatchClock.format(clock.elapsedMs(now))}")
        parts.add("Stoppage ${MatchClock.format(clock.totalStoppageMs(now))}")
        return parts.joinToString("  ·  ")
    }

    init {
        restore()
        refreshClockDisplay()
        viewModelScope.launch {
            while (true) {
                delay(500)
                refreshClockDisplay()
            }
        }
    }

    // ── Clock ──

    /** Called when the watch sends its clock (start, stop, end period, etc.). */
    fun applyWatchClock(json: String) {
        try {
            val incoming = MatchClock.fromJson(JSONObject(json))
            if (incoming.updatedAt >= clock.updatedAt) {
                clock = incoming
                persist()
                refreshClockDisplay()
            }
        } catch (e: Exception) {
            android.util.Log.e("PHONE_VM", "Bad clock from watch", e)
        }
    }

    private fun refreshClockDisplay() {
        val now = System.currentTimeMillis()
        val c = clock.checkTimeUp(now)
        val breakLeft = c.breakRemainingMs(now)
        setIfChanged(_timerDisplay, when (c.state) {
            MatchClock.State.READY      -> MatchClock.format(c.periodMillis)
            MatchClock.State.TIME_UP    -> "+" + MatchClock.format(c.overtimeMs(now))   // added-time count-up
            MatchClock.State.BREAK      -> if (breakLeft >= 0) MatchClock.format(breakLeft) else "+" + MatchClock.format(-breakLeft)
            MatchClock.State.MATCH_OVER -> "FT"
            else                        -> MatchClock.format(c.remainingMs(now))
        })
        val elapsed = "Elapsed ${MatchClock.format(c.elapsedMs(now))}"
        val stoppage = MatchClock.format(c.stoppageMs(now))
        setIfChanged(_periodDisplay, when (c.state) {
            MatchClock.State.READY      -> "Not started  ·  ${c.formatDescription()}"
            MatchClock.State.RUNNING    -> "Period ${c.period} of ${c.regularPeriods}  ·  $elapsed  ·  Stoppage $stoppage"
            MatchClock.State.PAUSED     -> "Period ${c.period}  ·  ⏱ recording stoppage $stoppage  ·  $elapsed"
            MatchClock.State.TIME_UP    -> "ADDED TIME  ·  owed +$stoppage  ·  $elapsed"
            MatchClock.State.BREAK      -> "Interval after period ${c.period}  ·  $elapsed"
            MatchClock.State.MATCH_OVER -> "Full time  ·  Total $elapsed  ·  Stoppage ${MatchClock.format(c.totalStoppageMs(now))}"
        })
        setIfChanged(_timerState, c.state.name)
        setIfChanged(_totalStoppageMillis, c.totalStoppageMs(now))
    }

    private fun <T> setIfChanged(live: MutableLiveData<T>, value: T) {
        if (live.value != value) live.value = value
    }

    // ── Teams ──

    fun setTeams(home: Team, away: Team) {
        _homeTeam.value = home
        _awayTeam.value = away
        _homeTeamName.value = home.name
        _awayTeamName.value = away.name
        persist()
    }

    private fun teamFor(isHome: Boolean): Team? = if (isHome) _homeTeam.value else _awayTeam.value

    // ── Events ──

    fun addEvent(event: MatchEvent) {
        val current = _events.value ?: mutableListOf()
        if (current.any { it.id == event.id }) return   // already have it
        current.add(event)
        current.sortBy { it.timestamp }
        _events.value = current
        recalculate()
        persist()
        evaluateKftmStatus()
    }

    /** Events logged on the watch arrive here. */
    fun addEventFromJson(jsonString: String) {
        try {
            addEvent(MatchJson.eventFromJson(JSONObject(jsonString), ::teamFor))
        } catch (e: Exception) {
            android.util.Log.e("PHONE_VM", "Failed to parse incoming event JSON", e)
        }
    }

    fun addGoal(isHome: Boolean, scorerNumber: Int, assistNumber: Int?, isOwnGoal: Boolean) {
        val now = System.currentTimeMillis()
        val (minute, added) = clock.minuteAt(now)
        val team = teamFor(isHome) ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)
        addEvent(MatchEvent(
            type = MatchEvent.EventType.GOAL,
            team = team,
            player = team.getPlayer(scorerNumber) ?: Player(scorerNumber, "Unknown"),
            assistPlayer = assistNumber?.let { team.getPlayer(it) ?: Player(it, "Unknown") },
            minute = minute, addedTime = added,
            period = clock.period,
            elapsedMillis = clock.matchClockMs(now),
            isOwnGoal = isOwnGoal
        ))
    }

    fun addFoul(isHome: Boolean, playerNumber: Int, foulType: MatchEvent.FoulType, isPK: Boolean, cardType: MatchEvent.CardType?) {
        val now = System.currentTimeMillis()
        val (minute, added) = clock.minuteAt(now)
        val team = teamFor(isHome) ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)
        addEvent(MatchEvent(
            type = if (cardType != null) MatchEvent.EventType.CARD else MatchEvent.EventType.FOUL,
            team = team,
            player = team.getPlayer(playerNumber) ?: Player(playerNumber, "Unknown"),
            minute = minute, addedTime = added,
            period = clock.period,
            elapsedMillis = clock.matchClockMs(now),
            foulType = foulType,
            isPK = isPK,
            cardType = cardType
        ))
    }

    /** Scores are always counted from the event list, so they can never drift out of sync. */
    private fun recalculate() {
        val events = _events.value.orEmpty()
        var home = 0; var away = 0
        events.filter { it.type == MatchEvent.EventType.GOAL }.forEach { e ->
            val homeScored = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
            if (homeScored) home++ else away++
        }
        setIfChanged(_homeScore, home)
        setIfChanged(_awayScore, away)

        val cards = events.filter { it.type == MatchEvent.EventType.CARD }
        val tagged = cards.count { it.gpsLocation != null }
        setIfChanged(_gpsStatus, if (cards.isEmpty()) "GPS: no cards yet" else "GPS: $tagged of ${cards.size} cards tagged 📍")
    }

    /** New match: clears score and events, keeps rosters. */
    fun reset() {
        _events.value = mutableListOf()
        clock = clock.reset(System.currentTimeMillis())
        _kftmWinner.value = null
        recalculate()
        persist()
        refreshClockDisplay()
    }

    // ── Saving ──

    private fun persist() {
        val state = JSONObject().apply {
            _homeTeam.value?.let { put("home", MatchJson.teamToJson(it)) }
            _awayTeam.value?.let { put("away", MatchJson.teamToJson(it)) }
            put("clock", clock.toJson())
            put("events", MatchJson.eventsToJson(_events.value.orEmpty()))
            put("savedAt", System.currentTimeMillis())
        }
        store.save(state)
    }

    private fun restore() {
        val s = store.load() ?: return
        try {
            s.optJSONObject("home")?.let { val t = MatchJson.teamFromJson(it); _homeTeam.value = t; _homeTeamName.value = t.name }
            s.optJSONObject("away")?.let { val t = MatchJson.teamFromJson(it); _awayTeam.value = t; _awayTeamName.value = t.name }
            s.optJSONObject("clock")?.let { clock = MatchClock.fromJson(it) }
            val restored = MatchJson.eventsFromJson(s.optJSONArray("events") ?: JSONArray(), ::teamFor)
            _events.value = restored.toMutableList()
            recalculate()
        } catch (e: Exception) {
            android.util.Log.e("PHONE_VM", "Could not restore saved match", e)
        }
    }

    // ── Shootout (phone only) ──

    private fun evaluateKftmStatus() {
        val kftmEvents = _events.value?.filter { it.type == MatchEvent.EventType.KFTM_KICK } ?: return
        if (kftmEvents.isEmpty()) return

        var homeKicks = 0; var awayKicks = 0
        var homeGoals = 0; var awayGoals = 0
        kftmEvents.forEach { e ->
            if (e.team.isHome) {
                homeKicks++; if (e.kftmResult == MatchEvent.KftmResult.SCORED) homeGoals++
            } else {
                awayKicks++; if (e.kftmResult == MatchEvent.KftmResult.SCORED) awayGoals++
            }
        }

        var winner: String? = null
        val homeRemainingBase = maxOf(0, 5 - homeKicks)
        val awayRemainingBase = maxOf(0, 5 - awayKicks)
        if (homeKicks <= 5 && awayKicks <= 5) {
            if (homeGoals > awayGoals + awayRemainingBase) winner = "HOME"
            else if (awayGoals > homeGoals + homeRemainingBase) winner = "AWAY"
        } else if (homeKicks == awayKicks) {
            if (homeGoals > awayGoals) winner = "HOME"
            else if (awayGoals > homeGoals) winner = "AWAY"
        }
        if (winner != null) _kftmWinner.value = Pair(winner, "$homeGoals - $awayGoals")
    }
}
