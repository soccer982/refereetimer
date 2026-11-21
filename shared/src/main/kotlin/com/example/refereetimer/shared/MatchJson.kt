package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.GpsPoint
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.Player
import com.example.refereetimer.shared.models.Team
import org.json.JSONArray
import org.json.JSONObject

/**
 * ONE format for turning teams and events into text and back.
 * Used for saving to storage (so nothing is lost if an app closes)
 * and for sending events from the watch to the phone.
 */
object MatchJson {

    fun teamToJson(t: Team): JSONObject = JSONObject().apply {
        put("name", t.name); put("isHome", t.isHome); put("jerseyColor", t.jerseyColor)
        put("coaches", JSONArray(t.coaches))
        put("players", JSONArray().apply {
            t.players.forEach { p ->
                put(JSONObject().apply { put("number", p.jerseyNumber); put("name", p.name); put("flagged", p.flagged) })
            }
        })
    }

    fun teamFromJson(j: JSONObject): Team {
        val pa = j.optJSONArray("players") ?: JSONArray()
        val players = (0 until pa.length()).map { i ->
            val p = pa.getJSONObject(i)
            Player(p.getInt("number"), p.optString("name", "Unknown"), p.optBoolean("flagged", false))
        }
        val ca = j.optJSONArray("coaches") ?: JSONArray()
        val coaches = (0 until ca.length()).map { ca.getString(it) }
        return Team(j.optString("name", "Team"), players, coaches, j.optBoolean("isHome", true), j.optString("jerseyColor", ""))
    }

    fun gpsToJson(g: GpsPoint): JSONObject = JSONObject().apply {
        put("lat", g.latitude); put("lng", g.longitude); put("acc", g.accuracy.toDouble()); put("ts", g.timestamp)
    }

    fun gpsFromJson(j: JSONObject) = GpsPoint(
        j.getDouble("lat"), j.getDouble("lng"), j.optDouble("acc", 0.0).toFloat(), false,
        j.optLong("ts", System.currentTimeMillis())
    )

    fun eventToJson(e: MatchEvent): JSONObject = JSONObject().apply {
        put("id", e.id); put("type", e.type.name)
        put("isHome", e.team.isHome); put("teamName", e.team.name)
        put("playerNumber", e.player.jerseyNumber); put("playerName", e.player.name)
        e.assistPlayer?.let { put("assistNumber", it.jerseyNumber); put("assistName", it.name) }
        e.playerOff?.let { put("offNumber", it.jerseyNumber); put("offName", it.name) }
        put("minute", e.minute); put("addedTime", e.addedTime)
        put("period", e.period); put("elapsedMillis", e.elapsedMillis)
        e.foulType?.let { put("foulType", it.name) }
        e.cardType?.let { put("cardType", it.name) }
        e.misconductCode?.let { put("misconductCode", it) }
        put("isOwnGoal", e.isOwnGoal); put("isPK", e.isPK)
        e.kftmResult?.let { put("kftmResult", it.name) }
        e.gpsLocation?.let { put("gps", gpsToJson(it)) }
        put("timestamp", e.timestamp)
        e.bodyPart?.let { put("bodyPart", it) }
        put("seriousInjury", e.seriousInjury)
        e.reportText?.let { put("reportText", it) }
        e.groupId?.let { put("groupId", it) }
        put("momentCounted", e.momentCounted)
        e.flag?.let { put("flag", it) }
    }

    /**
     * [teamFor] returns the roster for a side (true = home) so player names
     * can be filled in from the roster on the receiving device.
     */
    fun eventFromJson(j: JSONObject, teamFor: (Boolean) -> Team?): MatchEvent {
        val isHome = j.getBoolean("isHome")
        val team = teamFor(isHome)
            ?: Team(j.optString("teamName", if (isHome) "Home" else "Away"), emptyList(), emptyList(), isHome)

        fun playerFrom(numberKey: String, nameKey: String, roster: Team? = team): Player? {
            if (!j.has(numberKey)) return null
            val n = j.getInt(numberKey)
            return roster?.getPlayer(n) ?: Player(n, j.optString(nameKey, "Unknown"))
        }
        // In an own goal the assist is an opposing player, so look the name up on the other roster
        val ownGoal = j.optBoolean("isOwnGoal", false)
        fun text(key: String): String? = j.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }

        return MatchEvent(
            id = j.getString("id"),
            type = MatchEvent.EventType.valueOf(j.getString("type")),
            team = team,
            player = playerFrom("playerNumber", "playerName") ?: Player.UNKNOWN,
            playerOff = playerFrom("offNumber", "offName"),
            assistPlayer = playerFrom("assistNumber", "assistName", if (ownGoal) teamFor(!isHome) else team),
            minute = j.optInt("minute", 0),
            addedTime = j.optInt("addedTime", 0),
            period = j.optInt("period", 1),
            elapsedMillis = j.optLong("elapsedMillis", 0L),
            foulType = text("foulType")?.let { MatchEvent.FoulType.valueOf(it) },
            cardType = text("cardType")?.let { MatchEvent.CardType.valueOf(it) },
            misconductCode = text("misconductCode"),
            isOwnGoal = j.optBoolean("isOwnGoal", false),
            isPK = j.optBoolean("isPK", false),
            kftmResult = text("kftmResult")?.let { MatchEvent.KftmResult.valueOf(it) },
            gpsLocation = j.optJSONObject("gps")?.let { gpsFromJson(it) },
            timestamp = j.optLong("timestamp", System.currentTimeMillis()),
            bodyPart = text("bodyPart"),
            seriousInjury = j.optBoolean("seriousInjury", false),
            reportText = text("reportText"),
            groupId = text("groupId"),
            momentCounted = j.optBoolean("momentCounted", false),
            flag = text("flag")
        )
    }

    fun eventsToJson(events: List<MatchEvent>): JSONArray =
        JSONArray().apply { events.forEach { put(eventToJson(it)) } }

    /** Skips any single damaged entry instead of losing the whole list. */
    fun eventsFromJson(arr: JSONArray, teamFor: (Boolean) -> Team?): List<MatchEvent> =
        (0 until arr.length()).mapNotNull { i ->
            runCatching { eventFromJson(arr.getJSONObject(i), teamFor) }.getOrNull()
        }
}
