package com.example.refereetimer.shared.models

data class Player(
    val jerseyNumber: Int,
    val name: String,
    val flagged: Boolean = false,
    /** NEW: league player / pass ID from the game card, e.g. "52416-849506". Blank if unknown. */
    val playerId: String = "",
    /** NEW: date of birth as printed on the card (GotSport prints MM/YY). */
    val dob: String = "",
    /** NEW: club pass player (marked "CPP" on GotSport cards). */
    val cpp: Boolean = false
) {
    /** NEW: a coach / team official (not a player). Uses a number outside the 0–99 jersey range. */
    val isTeamOfficial: Boolean get() = jerseyNumber == TEAM_OFFICIAL_NUMBER

    /** NEW: the card had no jersey number for this player (common for guest / club pass players). */
    val hasNumber: Boolean get() = jerseyNumber != NO_NUMBER

    /** "#7" for players, "TO" for a team official, "#?" when no number is known. */
    val tag: String get() = when {
        isTeamOfficial -> "TO"
        !hasNumber -> "#?"
        else -> "#$jerseyNumber"
    }

    /** Number as shown inside a field marker or a form cell. */
    val shortTag: String get() = when {
        isTeamOfficial -> "TO"
        !hasNumber -> "?"
        else -> "$jerseyNumber"
    }

    companion object {
        const val TEAM_OFFICIAL_NUMBER = 100
        /** NEW: jersey number used when the roster doesn't give one. Never matches a number typed on the watch. */
        const val NO_NUMBER = -1
        val UNKNOWN = Player(jerseyNumber = 0, name = "Unknown")
        fun teamOfficial() = Player(TEAM_OFFICIAL_NUMBER, "Team official")
    }
    override fun toString(): String = "$tag $name"
}
