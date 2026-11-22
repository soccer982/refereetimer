package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import org.json.JSONObject

/**
 * NEW: substitution rules, set in Match Setup and tracked on the phone only.
 *  - maxSubs:    0 = unlimited substitutions, otherwise the limit per team
 *  - trackMoments / maxMoments: count substitution "moments" (separate stoppages used for subs);
 *                maxMoments 0 = keep a tally with no limit
 *  - intervalsFree: subs made during an interval (e.g. half-time) don't use a moment (IFAB rule)
 *  - reEntry:    ALLOWED, NOT_SAME_PERIOD (no re-entry in the same half/period),
 *                NOT_REST_OF_MATCH (a player who comes off can't return)
 */
data class SubstitutionRules(
    val tracking: Boolean = false,
    val maxSubs: Int = 0,
    val trackMoments: Boolean = false,
    val maxMoments: Int = 0,
    val intervalsFree: Boolean = true,
    val reEntry: ReEntry = ReEntry.ALLOWED
) {
    enum class ReEntry { ALLOWED, NOT_SAME_PERIOD, NOT_REST_OF_MATCH }

    fun describe(): String = buildList {
        add(if (maxSubs == 0) "Unlimited subs" else "Max $maxSubs subs")
        if (trackMoments) add(if (maxMoments == 0) "moments tallied" else "max $maxMoments moments")
        add(when (reEntry) {
            ReEntry.ALLOWED -> "re-entry allowed"
            ReEntry.NOT_SAME_PERIOD -> "no re-entry in the same period"
            ReEntry.NOT_REST_OF_MATCH -> "no re-entry for rest of match"
        })
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("tracking", tracking); put("maxSubs", maxSubs); put("trackMoments", trackMoments)
        put("maxMoments", maxMoments); put("intervalsFree", intervalsFree); put("reEntry", reEntry.name)
    }

    companion object {
        fun fromJson(j: JSONObject) = SubstitutionRules(
            tracking = j.optBoolean("tracking", false),
            maxSubs = j.optInt("maxSubs", 0),
            trackMoments = j.optBoolean("trackMoments", false),
            maxMoments = j.optInt("maxMoments", 0),
            intervalsFree = j.optBoolean("intervalsFree", true),
            reEntry = runCatching { ReEntry.valueOf(j.optString("reEntry")) }.getOrDefault(ReEntry.ALLOWED)
        )
    }
}

/** Tallies and rule checks for one team, from the match events. */
class SubstitutionCheck(private val rules: SubstitutionRules, events: List<MatchEvent>, private val isHome: Boolean) {

    private val subs = events.filter { it.type == EventType.SUBSTITUTION && it.team.isHome == isHome }.sortedBy { it.timestamp }
    private val sentOff = events.filter { it.type == EventType.CARD && it.cardType == CardType.RED && it.team.isHome == isHome }
        .map { it.player.jerseyNumber }.toSet()

    val subsUsed: Int get() = subs.size
    val momentsUsed: Int get() = subs.filter { it.momentCounted }.mapNotNull { it.groupId }.distinct().size

    /** e.g. "Subs 3/5 · Moments 1/3" or "Subs 7 · Moments 2" */
    fun tallyText(): String = buildString {
        append("Subs $subsUsed")
        if (rules.maxSubs > 0) append("/${rules.maxSubs}")
        if (rules.trackMoments) {
            append(" · Moments $momentsUsed")
            if (rules.maxMoments > 0) append("/${rules.maxMoments}")
        }
    }

    /** Problem with this player coming ON in [period], or null if fine. */
    fun onProblem(number: Int, period: Int): String? {
        if (number in sentOff) return "#$number was sent off and can't return"
        val wentOff = subs.filter { it.playerOff?.jerseyNumber == number }
        if (wentOff.isEmpty()) return null
        val last = wentOff.last()
        return when (rules.reEntry) {
            SubstitutionRules.ReEntry.ALLOWED -> null
            SubstitutionRules.ReEntry.NOT_SAME_PERIOD ->
                wentOff.lastOrNull { it.period == period }?.let { "#$number came off at ${it.minuteLabel()}: no re-entry this period" }
            SubstitutionRules.ReEntry.NOT_REST_OF_MATCH ->
                "#$number came off at ${last.minuteLabel()}: no re-entry for the rest of the match"
        }
    }

    /** Problem with this player going OFF, or null if fine. */
    fun offProblem(number: Int): String? {
        if (number in sentOff) return "#$number was sent off: a sent-off player can't be replaced"
        val lastInvolving = subs.lastOrNull { it.player.jerseyNumber == number || it.playerOff?.jerseyNumber == number }
        if (lastInvolving != null && lastInvolving.playerOff?.jerseyNumber == number)
            return "#$number already came off at ${lastInvolving.minuteLabel()}"
        return null
    }

    /** Problem with adding [count] more subs (and maybe one more moment), or null if fine. */
    fun limitProblem(count: Int, usesMoment: Boolean): String? {
        if (rules.maxSubs > 0 && subsUsed + count > rules.maxSubs)
            return "Sub limit is ${rules.maxSubs}: this makes ${subsUsed + count}"
        if (rules.trackMoments && rules.maxMoments > 0 && usesMoment && momentsUsed + 1 > rules.maxMoments)
            return "Moment limit is ${rules.maxMoments}: this would be moment ${momentsUsed + 1}"
        return null
    }
}
