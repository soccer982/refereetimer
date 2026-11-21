package com.example.refereetimer.shared.models

data class Team(
    val name: String,
    val players: List<Player>,
    val coaches: List<String>,
    val isHome: Boolean,
    val jerseyColor: String = ""
) {
    val yellowCards: MutableMap<Int, Int> = mutableMapOf()
    val redCards: MutableSet<Int> = mutableSetOf()

    /** "[H]" or "[A]" */
    val tag: String get() = TeamTag.of(isHome)

    /** "[H] Eagles FC": how a team is shown everywhere. */
    val label: String get() = TeamTag.label(name, isHome)

    fun getPlayer(jerseyNumber: Int): Player? =
        players.find { it.jerseyNumber == jerseyNumber }

    fun addYellowCard(jerseyNumber: Int): CardResult {
        val current = yellowCards.getOrDefault(jerseyNumber, 0) + 1
        yellowCards[jerseyNumber] = current
        return if (current >= 2) CardResult.SECOND_YELLOW_RED
        else CardResult.YELLOW
    }

    fun addRedCard(jerseyNumber: Int): CardResult {
        redCards.add(jerseyNumber)
        return CardResult.RED
    }

    fun hasRedCard(jerseyNumber: Int): Boolean =
        redCards.contains(jerseyNumber) ||
        (yellowCards.getOrDefault(jerseyNumber, 0) >= 2)

    enum class CardResult {
        YELLOW,
        SECOND_YELLOW_RED,
        RED
    }
}

/**
 * NEW: one rule for showing team names. The [H]/[A] tag always comes first, so when a long
 * name is cut short to fit, the tag stays visible and only the name is shortened ("…").
 */
object TeamTag {
    const val HOME = "[H]"
    const val AWAY = "[A]"

    fun of(isHome: Boolean) = if (isHome) HOME else AWAY

    /** "[H] Eagles FC" (never doubles up if the name already has a tag). */
    fun label(name: String, isHome: Boolean): String {
        val bare = name.trim().removePrefix(HOME).removePrefix(AWAY).trim()
        return if (bare.isEmpty()) of(isHome) else "${of(isHome)} $bare"
    }

    /** The label cut to at most [maxChars] characters, keeping the tag. */
    fun fit(name: String, isHome: Boolean, maxChars: Int): String {
        val full = label(name, isHome)
        if (full.length <= maxChars) return full
        val room = (maxChars - of(isHome).length - 2).coerceAtLeast(1)   // tag + space + "…"
        return "${of(isHome)} ${full.drop(of(isHome).length + 1).take(room).trimEnd()}…"
    }
}
