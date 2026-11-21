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
