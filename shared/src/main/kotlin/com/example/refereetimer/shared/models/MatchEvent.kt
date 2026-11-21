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
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class EventType { GOAL, FOUL, CARD, SUBSTITUTION, KFTM_KICK }
    enum class FoulType  { DFK, IFK }
    enum class CardType  { YELLOW, RED }
    enum class KftmResult { SCORED, MISSED, SAVED }

    /** "23'" or "45+2'" */
    fun minuteLabel(): String = if (addedTime > 0) "$minute+$addedTime'" else "$minute'"

    private fun who(p: Player): String = buildString {
        append("#${p.jerseyNumber}")
        if (p.name.isNotBlank() && p.name != "Unknown") append(" ${p.name}")
    }

    fun toDisplayString(): String = when (type) {
        EventType.GOAL -> buildString {
            append("⚽ ${minuteLabel()} ")
            if (isOwnGoal) append("OG ")
            append("${team.name} ${who(player)}")
            assistPlayer?.let { append(" (Assist ${who(it)})") }
            if (isPK) append(" [PK]")
        }
        EventType.FOUL -> buildString {
            append("🚩 ${minuteLabel()} ${team.name} ${who(player)} ")
            append(foulType?.name ?: "")
            if (isPK) append(" → PK")
        }
        EventType.CARD -> buildString {
            append(if (cardType == CardType.RED) "🟥 " else "🟨 ")
            append("${minuteLabel()} ${team.name} ${who(player)}")
            if (!misconductCode.isNullOrBlank()) append(" [$misconductCode]")
            if (gpsLocation != null) append(" 📍")
        }
        EventType.SUBSTITUTION -> buildString {
            append("🔄 ${minuteLabel()} ${team.name}: IN #${player.jerseyNumber} | OUT #${playerOff?.jerseyNumber ?: "?"}")
        }
        EventType.KFTM_KICK -> buildString {
            val icon = if (kftmResult == KftmResult.SCORED) "✅" else "❌"
            append("$icon KFTM: ${team.name} #${player.jerseyNumber} - ${kftmResult?.name}")
        }
    }
}
