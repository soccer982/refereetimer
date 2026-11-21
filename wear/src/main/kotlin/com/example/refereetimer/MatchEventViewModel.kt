package com.example.refereetimer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.GpsPoint
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import com.example.refereetimer.shared.models.Player
import com.example.refereetimer.shared.models.Team
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch's own copy of goals and cards: enough to show the score and to catch
 * a second yellow. Saved after every change so it survives the app closing.
 */
class MatchEventViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("watch_match_events", Context.MODE_PRIVATE)

    private val _homeTeam = MutableLiveData<Team>(Team("Home", emptyList(), emptyList(), true))
    val homeTeam: LiveData<Team> = _homeTeam

    private val _awayTeam = MutableLiveData<Team>(Team("Away", emptyList(), emptyList(), false))
    val awayTeam: LiveData<Team> = _awayTeam

    private val _homeScore = MutableLiveData(0)
    val homeScore: LiveData<Int> = _homeScore

    private val _awayScore = MutableLiveData(0)
    val awayScore: LiveData<Int> = _awayScore

    private val events = mutableListOf<MatchEvent>()

    init { restore() }

    fun team(isHome: Boolean): Team = (if (isHome) _homeTeam.value else _awayTeam.value)
        ?: Team(if (isHome) "Home" else "Away", emptyList(), emptyList(), isHome)

    fun setTeams(home: Team, away: Team) {
        _homeTeam.value = home
        _awayTeam.value = away
        save()
    }

    fun reset() {
        events.clear()
        recalculate()
        save()
    }

    /**
     * [isHome] = team of the player who scored. For an OWN GOAL that's the defender's team
     * (the goal counts for the other side) and the assist is an OPPOSING player.
     */
    fun addGoal(
        isHome: Boolean, number: Int, assistNumber: Int?,
        minute: Int, added: Int, period: Int, elapsed: Long,
        isPK: Boolean = false, isOwnGoal: Boolean = false
    ): MatchEvent {
        val team = team(isHome)
        val assistRoster = if (isOwnGoal) team(!isHome) else team
        val event = MatchEvent(
            type = EventType.GOAL, team = team,
            player = team.getPlayer(number) ?: Player(number, "Unknown"),
            assistPlayer = assistNumber?.let { assistRoster.getPlayer(it) ?: Player(it, "Unknown") },
            minute = minute, addedTime = added, period = period, elapsedMillis = elapsed,
            isPK = isPK, isOwnGoal = isOwnGoal
        )
        events.add(event)
        recalculate(); save()
        return event
    }

    /** NEW: foul with free-kick type (PK = DFK inside the penalty area), GPS-tagged. */
    fun addFoul(
        isHome: Boolean, number: Int, foulType: MatchEvent.FoulType, isPK: Boolean,
        minute: Int, added: Int, period: Int, elapsed: Long, gps: GpsPoint?
    ): MatchEvent {
        val team = team(isHome)
        val event = MatchEvent(
            type = EventType.FOUL, team = team,
            player = team.getPlayer(number) ?: Player(number, "Unknown"),
            minute = minute, addedTime = added, period = period, elapsedMillis = elapsed,
            foulType = foulType, isPK = isPK, gpsLocation = gps
        )
        events.add(event)
        save()
        return event
    }

    /**
     * Records a card. A player's second yellow automatically adds a red card
     * with code 2CT (both are returned so both are sent to the phone).
     */
    fun addCard(
        isHome: Boolean, number: Int, cardType: CardType, code: String,
        minute: Int, added: Int, period: Int, elapsed: Long, gps: GpsPoint?
    ): List<MatchEvent> {
        val team = team(isHome)
        val player = if (number == Player.TEAM_OFFICIAL_NUMBER) Player.teamOfficial()
            else team.getPlayer(number) ?: Player(number, "Unknown")
        val created = mutableListOf<MatchEvent>()

        fun card(type: CardType, misconduct: String) = MatchEvent(
            type = EventType.CARD, team = team, player = player,
            minute = minute, addedTime = added, period = period, elapsedMillis = elapsed,
            cardType = type, misconductCode = misconduct, gpsLocation = gps
        )

        if (cardType == CardType.YELLOW) {
            val previousYellows = yellowCount(isHome, number)
            created.add(card(CardType.YELLOW, code))
            if (previousYellows >= 1) created.add(card(CardType.RED,
                if (player.isTeamOfficial) MisconductCodes.TEAM_OFFICIAL_SECOND_CAUTION.code else MisconductCodes.SECOND_CAUTION.code))
        } else {
            created.add(card(CardType.RED, code))
        }
        events.addAll(created)
        save()
        return created
    }

    /** NEW: injury logged after a long-press on STOP. */
    fun addInjury(
        isHome: Boolean, number: Int, bodyPart: String,
        minute: Int, added: Int, period: Int, elapsed: Long, gps: GpsPoint?, timestamp: Long
    ): MatchEvent {
        val team = team(isHome)
        val event = MatchEvent(
            type = EventType.INJURY, team = team,
            player = team.getPlayer(number) ?: Player(number, "Unknown"),
            minute = minute, addedTime = added, period = period, elapsedMillis = elapsed,
            gpsLocation = gps, bodyPart = bodyPart, timestamp = timestamp
        )
        events.add(event)
        save()
        return event
    }

    /** NEW: events entered on the phone (goals, fouls, cards, subs). Updates the score; ignores repeats. */
    fun addFromPhone(incoming: List<MatchEvent>) {
        val fresh = incoming.filter { e -> events.none { it.id == e.id } }
        if (fresh.isEmpty()) return
        events.addAll(fresh)
        events.sortBy { it.timestamp }
        recalculate(); save()
    }

    fun yellowCount(isHome: Boolean, number: Int): Int = events.count {
        it.type == EventType.CARD && it.cardType == CardType.YELLOW &&
            it.team.isHome == isHome && it.player.jerseyNumber == number
    }

    fun isSentOff(isHome: Boolean, number: Int): Boolean = events.any {
        it.type == EventType.CARD && it.cardType == CardType.RED &&
            it.team.isHome == isHome && it.player.jerseyNumber == number
    }

    private fun recalculate() {
        var home = 0; var away = 0
        events.filter { it.type == EventType.GOAL }.forEach { e ->
            val homeScored = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
            if (homeScored) home++ else away++
        }
        if (_homeScore.value != home) _homeScore.value = home
        if (_awayScore.value != away) _awayScore.value = away
    }

    private fun save() {
        val state = JSONObject().apply {
            put("home", MatchJson.teamToJson(team(true)))
            put("away", MatchJson.teamToJson(team(false)))
            put("events", MatchJson.eventsToJson(events))
        }
        prefs.edit().putString(KEY_STATE, state.toString()).commit()
    }

    private fun restore() {
        try {
            val s = JSONObject(prefs.getString(KEY_STATE, null) ?: return)
            s.optJSONObject("home")?.let { _homeTeam.value = MatchJson.teamFromJson(it) }
            s.optJSONObject("away")?.let { _awayTeam.value = MatchJson.teamFromJson(it) }
            events.clear()
            events.addAll(MatchJson.eventsFromJson(s.optJSONArray("events") ?: JSONArray()) { team(it) })
            recalculate()
        } catch (e: Exception) {
            android.util.Log.e("WATCH_EVENTS", "Could not restore saved events", e)
        }
    }

    companion object {
        private const val KEY_STATE = "state"
    }
}
