package com.example.refereetimer.shared.models

data class Player(
    val jerseyNumber: Int,
    val name: String,
    val flagged: Boolean = false
) {
    companion object {
        val UNKNOWN = Player(jerseyNumber = 0, name = "Unknown")
    }
    override fun toString(): String = "#$jerseyNumber $name"
}
