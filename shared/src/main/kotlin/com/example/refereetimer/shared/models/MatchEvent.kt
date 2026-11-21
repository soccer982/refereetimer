package com.example.refereetimer.shared.models

import java.util.UUID

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val corrected: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    var fieldX: Float = 0f
    var fieldY: Float = 0f
}

data class MatchEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: EventType,
    val team: Team,
    val player: Player,
    val playerOff: Player? = null,
    val assistPlayer: Player? = null,
    val minute: Int,
    val addedTime: Int = 0,          // NEW: minutes past the end of the period ("45+2'")
    val period: Int,
    val elapsedMillis: Long,
    val foulType: FoulType? = null,
    val cardType: CardType? = null,
    val misconductCode: String? = null,
    val isOwnGoal: Boolean = false,
    val isPK: Boolean = false,
    val kftmResult: KftmResult? = null,
    val gpsLocation: GpsPoint? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val bodyPart: String? = null,        // NEW (injuries): see BodyParts, e.g. "LEFT_LEG"
    val seriousInjury: Boolean = false,  // NEW (injuries): set at report time → supplemental report
    val reportText: String? = null,      // NEW: red-card narrative or injury description
    val groupId: String? = null,         // NEW (subs): subs recorded together share an id
    val momentCounted: Boolean = false,  // NEW (subs): this group used a substitution moment
    val flag: String? = null             // NEW (subs): rule problem the referee chose to record anyway
) {
    enum class EventType { GOAL, FOUL, CARD, SUBSTITUTION, KFTM_KICK, INJURY }
    enum class FoulType  { DFK, IFK }
    enum class CardType  { YELLOW, RED }
    enum class KftmResult { SCORED, MISSED, SAVED }

    /**
     * Assist as printed. In an OWN GOAL the assist is credited to an OPPOSING player
     * (the attacker whose play forced it), marked with "*": see [OG_ASSIST_KEY].
     */
    fun assistTag(): String? = assistPlayer?.let { "${it.tag}${if (isOwnGoal) OG_ASSIST_MARK else ""}" }

    /** "Open play", "PK" or "OG" */
    fun goalTypeLabel(): String = when { isOwnGoal -> "OG"; isPK -> "PK"; else -> "Open play" }

    companion object {
        const val OG_ASSIST_MARK = "*"
        const val OG_ASSIST_KEY = "* Own goal: the assist is credited to a player on the opposing team"
    }

    /** "23'" or "45+2'" */
    fun minuteLabel(): String = if (addedTime > 0) "$minute+$addedTime'" else "$minute'"

    private fun who(p: Player): String = buildString {
        append(p.tag)
        if (p.name.isNotBlank() && p.name != "Unknown") append(" ${p.name}")
    }

    fun toDisplayString(): String = when (type) {
        EventType.GOAL -> buildString {
            append("⚽ ${minuteLabel()} ")
            if (isOwnGoal) append("[OG] ")
            if (isPK) append("[PK] ")
            append("${team.label} ${who(player)}")
            assistPlayer?.let { append(" (Assist ${who(it)}${if (isOwnGoal) OG_ASSIST_MARK else ""})") }
        }
        EventType.FOUL -> buildString {
            append("🚩 ${minuteLabel()} ${team.label} ${who(player)} ")
            append(foulType?.name ?: "")
            if (isPK) append(" → PK")
        }
        EventType.CARD -> buildString {
            append(if (cardType == CardType.RED) "🟥 " else "🟨 ")
            append("${minuteLabel()} ${team.label} ${who(player)}")
            if (!misconductCode.isNullOrBlank()) append(" [$misconductCode]")
            if (gpsLocation != null) append(" 📍")
        }
        EventType.SUBSTITUTION -> buildString {
            append("🔄 ${minuteLabel()} ${team.label}: ON ${who(player)} | OFF ${playerOff?.let { who(it) } ?: "?"}")
            if (flag != null) append("  ⚠ $flag")
        }
        EventType.INJURY -> buildString {
            append("🩹 ${minuteLabel()} ${team.label} ${who(player)} · ${BodyParts.label(bodyPart)}")
            if (seriousInjury) append(" · SERIOUS")
            if (gpsLocation != null) append(" 📍")
        }
        EventType.KFTM_KICK -> buildString {
            val icon = if (kftmResult == KftmResult.SCORED) "✅" else "❌"
            append("$icon KFTM: ${team.label} #${player.jerseyNumber} - ${kftmResult?.name}")
        }
    }
}

/** Body parts recorded for injuries. */
object BodyParts {
    const val HEAD = "HEAD"
    const val TORSO = "TORSO"
    const val LEFT_ARM = "LEFT_ARM"
    const val RIGHT_ARM = "RIGHT_ARM"
    const val LEFT_LEG = "LEFT_LEG"
    const val RIGHT_LEG = "RIGHT_LEG"

    fun label(code: String?): String = when (code) {
        HEAD -> "Head"
        TORSO -> "Torso"
        LEFT_ARM -> "Left arm"
        RIGHT_ARM -> "Right arm"
        LEFT_LEG -> "Left leg"
        RIGHT_LEG -> "Right leg"
        else -> "Not recorded"
    }
}
