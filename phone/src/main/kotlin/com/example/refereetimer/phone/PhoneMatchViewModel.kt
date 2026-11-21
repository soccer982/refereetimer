package com.example.refereetimer.phone

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.refereetimer.shared.FieldCalibration
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.MisconductCodes
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

    /** NEW: field calibration from the watch (4 corner flags). Kept across matches until redone. */
    var fieldCalibration: FieldCalibration? = null
        private set

    /** NEW: referee movement per period, raw GPS: period -> list of (time, lat, lng). */
    private val tracks = mutableMapOf<Int, List<Triple<Long, Double, Double>>>()

    val regularPeriods: Int get() = clock.regularPeriods

    /** NEW: called with events created ON THE PHONE, so they can be sent to the watch (set by PhoneApp). */
    var onLocalEvents: ((List<MatchEvent>) -> Unit)? = null

    /** True if a match is under way (period lengths can't change until reset). */
    val isMatchInProgress: Boolean get() = clock.state != MatchClock.State.READY && clock.state != MatchClock.State.MATCH_OVER

    /** One line for the PDF, e.g. "3 × 25 min · 5 min intervals  ·  Total elapsed 1:30:10  ·  Stoppage 04:00". */
    fun matchSummary(): String {
        val now = System.currentTimeMillis()
        val parts = mutableListOf(clock.formatDescription())
        if (clock.kickoffAt > 0) parts.add("Total elapsed ${MatchClock.format(clock.elapsedMs(now))}")
        parts.add("Stoppage ${MatchClock.format(clock.totalStoppageMs(now))}")
        fieldCalibration?.let { parts.add("Field ${it.describe()}") }
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

    /** [isHome] = team of the scorer; for an own goal the goal counts for the other side and the assist is an opponent. */
    fun addGoal(isHome: Boolean, scorerNumber: Int, assistNumber: Int?, isOwnGoal: Boolean, isPK: Boolean = false) {
        val now = System.currentTimeMillis()
        val (minute, added) = clock.minuteAt(now)
        val team = teamFor(isHome) ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)
        addLocal(MatchEvent(
            type = MatchEvent.EventType.GOAL,
            team = team,
            player = team.getPlayer(scorerNumber) ?: Player(scorerNumber, "Unknown"),
            assistPlayer = assistNumber?.let { n ->
                val roster = if (isOwnGoal) teamFor(!isHome) else team
                roster?.getPlayer(n) ?: Player(n, "Unknown")
            },
            minute = minute, addedTime = added,
            period = clock.period,
            elapsedMillis = clock.matchClockMs(now),
            isOwnGoal = isOwnGoal,
            isPK = isPK
        ))
    }

    fun addFoul(
        isHome: Boolean, playerNumber: Int, foulType: MatchEvent.FoulType, isPK: Boolean,
        cardType: MatchEvent.CardType?, misconductCode: String? = null
    ) {
        val now = System.currentTimeMillis()
        val (minute, added) = clock.minuteAt(now)
        val team = teamFor(isHome) ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)
        val player = if (playerNumber == Player.TEAM_OFFICIAL_NUMBER) Player.teamOfficial()
            else team.getPlayer(playerNumber) ?: Player(playerNumber, "Unknown")
        fun event(type: MatchEvent.CardType?, code: String?, offset: Long) = MatchEvent(
            type = if (type != null) MatchEvent.EventType.CARD else MatchEvent.EventType.FOUL,
            team = team, player = player,
            minute = minute, addedTime = added, period = clock.period,
            elapsedMillis = clock.matchClockMs(now),
            foulType = foulType, isPK = isPK, cardType = type, misconductCode = code,
            timestamp = now + offset
        )
        val created = mutableListOf(event(cardType, misconductCode, 0))
        // Same rule as the watch: a second yellow adds the send-off automatically (2CT / SOTO7)
        if (cardType == MatchEvent.CardType.YELLOW) {
            val earlier = _events.value.orEmpty().count {
                it.type == MatchEvent.EventType.CARD && it.cardType == MatchEvent.CardType.YELLOW &&
                    it.team.isHome == isHome && it.player.jerseyNumber == playerNumber
            }
            if (earlier >= 1) created.add(event(MatchEvent.CardType.RED,
                if (player.isTeamOfficial) MisconductCodes.TEAM_OFFICIAL_SECOND_CAUTION.code else MisconductCodes.SECOND_CAUTION.code, 1))
        }
        created.forEach { addEvent(it) }
        onLocalEvents?.invoke(created)
    }

    /** Adds an event created on the phone and passes it on to the watch. */
    private fun addLocal(event: MatchEvent) {
        addEvent(event)
        onLocalEvents?.invoke(listOf(event))
    }

    /** True if this player already has a yellow (used to warn on the phone). */
    fun hasYellow(isHome: Boolean, number: Int): Boolean = _events.value.orEmpty().any {
        it.type == MatchEvent.EventType.CARD && it.cardType == MatchEvent.CardType.YELLOW &&
            it.team.isHome == isHome && it.player.jerseyNumber == number
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

        updateGpsStatus()
    }

    private fun updateGpsStatus() {
        val cards = _events.value.orEmpty().filter { it.type == MatchEvent.EventType.CARD }
        val tagged = cards.count { it.gpsLocation != null }
        val field = fieldCalibration?.let { "Field ${it.describe()} ✓" } ?: "Field not calibrated"
        val points = tracks.values.sumOf { it.size }
        val cardText = if (cards.isEmpty()) "no cards" else "$tagged/${cards.size} cards 📍"
        setIfChanged(_gpsStatus, "GPS: $field  ·  $cardText  ·  $points track pts")
    }

    // ── Substitutions (phone only) ──

    /** Where substitutions made right now belong. */
    data class SubContext(val minute: Int, val added: Int, val period: Int, val duringInterval: Boolean, val label: String)

    fun subContext(): SubContext {
        val now = System.currentTimeMillis()
        val c = clock
        return when (c.state) {
            // Subs at an interval belong to the NEXT period (so re-entry rules apply to that half)
            MatchClock.State.BREAK -> {
                val minute = c.period * (c.periodMillis / 60_000L).toInt()
                SubContext(minute, 0, c.period + 1, true, "Interval before period ${c.period + 1}")
            }
            MatchClock.State.READY -> SubContext(0, 0, 1, true, "Before kickoff")
            else -> {
                val (m, a) = c.minuteAt(now)
                SubContext(m, a, c.period, false, "${if (a > 0) "$m+$a" else "$m"}' · period ${c.period}")
            }
        }
    }

    /**
     * Records one batch of substitutions for a team, made at the same stoppage.
     * [pairs] = (player ON, player OFF, flag or null). Returns how many were recorded.
     */
    fun addSubstitutions(isHome: Boolean, pairs: List<Triple<Int, Int, String?>>, usesMoment: Boolean): Int {
        if (pairs.isEmpty()) return 0
        val ctx = subContext()
        val team = teamFor(isHome) ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)
        val group = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val list = _events.value ?: mutableListOf()
        pairs.forEachIndexed { i, (on, off, flag) ->
            list.add(MatchEvent(
                type = MatchEvent.EventType.SUBSTITUTION, team = team,
                player = team.getPlayer(on) ?: Player(on, "Unknown"),
                playerOff = team.getPlayer(off) ?: Player(off, "Unknown"),
                minute = ctx.minute, addedTime = ctx.added, period = ctx.period,
                elapsedMillis = clock.matchClockMs(now),
                groupId = group, momentCounted = usesMoment, flag = flag,
                timestamp = now + i     // keeps the order they were entered
            ))
        }
        list.sortBy { it.timestamp }
        _events.value = list
        persist()
        onLocalEvents?.invoke(list.filter { it.groupId == group })
        return pairs.size
    }

    // ── Report answers (injury severity/description, red-card narrative) ──

    fun eventById(id: String): MatchEvent? = _events.value?.firstOrNull { it.id == id }

    /** Saves what the referee entered while preparing the report (kept for next time). */
    fun updateEventReport(id: String, serious: Boolean, text: String?) {
        val list = _events.value ?: return
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(seriousInjury = serious, reportText = text)
        _events.value = list
        recalculate()
        persist()
    }

    /** The clock as it stands (for kickoff time, elapsed and stoppage in the reports). */
    val currentClock: MatchClock get() = clock

    // ── Field calibration + movement tracks (from the watch) ──

    fun applyCalibration(json: String) {
        fieldCalibration = FieldCalibration.fromJson(JSONObject(json)) ?: return
        persist(); updateGpsStatus()
    }

    fun applyTrack(period: Int, json: String) {
        try {
            val arr = JSONArray(json)
            tracks[period] = (0 until arr.length()).map {
                val p = arr.getJSONArray(it); Triple(p.getLong(0), p.getDouble(1), p.getDouble(2))
            }
            persist(); updateGpsStatus()
        } catch (e: Exception) {
            android.util.Log.e("PHONE_VM", "Bad track data", e)
        }
    }

    /** Movement track placed on the field, for the report heatmaps (empty if not calibrated). */
    fun reportTrack(): List<GpsReferenceService.GpsTrackPoint> {
        val cal = fieldCalibration ?: return emptyList()
        return tracks.flatMap { (period, pts) ->
            pts.map { (t, lat, lng) ->
                val (x, y) = cal.toField(lat, lng)
                GpsReferenceService.GpsTrackPoint(lat, lng, x, y, period, t - clock.kickoffAt, t)
            }
        }
    }

    /** Events with their GPS tags placed on the field (positions left unset if not calibrated). */
    fun reportEvents(): List<MatchEvent> {
        val cal = fieldCalibration
        return _events.value.orEmpty().map { e ->
            val g = e.gpsLocation
            if (cal == null || g == null) e
            else e.copy(gpsLocation = g.copy().also {
                val (x, y) = cal.toField(g.latitude, g.longitude)
                it.fieldX = x; it.fieldY = y
            })
        }
    }

    /** New match: clears score and events, keeps rosters. */
    fun reset() {
        tracks.clear()
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
            fieldCalibration?.let { put("field", it.toJson()) }
            put("tracks", JSONObject().apply {
                tracks.forEach { (period, pts) ->
                    put(period.toString(), JSONArray().apply { pts.forEach { (t, la, ln) -> put(JSONArray().put(t).put(la).put(ln)) } })
                }
            })
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
            s.optJSONObject("field")?.let { fieldCalibration = FieldCalibration.fromJson(it) }
            s.optJSONObject("tracks")?.let { tj ->
                tj.keys().forEach { k -> k.toIntOrNull()?.let { applyTrackSilently(it, tj.getJSONArray(k)) } }
            }
            val restored = MatchJson.eventsFromJson(s.optJSONArray("events") ?: JSONArray(), ::teamFor)
            _events.value = restored.toMutableList()
            recalculate()
        } catch (e: Exception) {
            android.util.Log.e("PHONE_VM", "Could not restore saved match", e)
        }
    }

    private fun applyTrackSilently(period: Int, arr: JSONArray) {
        tracks[period] = (0 until arr.length()).map {
            val p = arr.getJSONArray(it); Triple(p.getLong(0), p.getDouble(1), p.getDouble(2))
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
