package com.example.refereetimer.shared.models

data class Player(
    val jerseyNumber: Int,
    val name: String,
    val flagged: Boolean = false
) {
    /** NEW: a coach / team official (not a player). Uses a number outside the 0–99 jersey range. */
    val isTeamOfficial: Boolean get() = jerseyNumber == TEAM_OFFICIAL_NUMBER

    /** "#7" for players, "TO" for a team official: used wherever a number is printed. */
    val tag: String get() = if (isTeamOfficial) "TO" else "#$jerseyNumber"

    /** Number as shown inside a field marker or a form cell. */
    val shortTag: String get() = if (isTeamOfficial) "TO" else "$jerseyNumber"

    companion object {
        const val TEAM_OFFICIAL_NUMBER = 100
        val UNKNOWN = Player(jerseyNumber = 0, name = "Unknown")
        fun teamOfficial() = Player(TEAM_OFFICIAL_NUMBER, "Team official")
    }
    override fun toString(): String = "$tag $name"
}
