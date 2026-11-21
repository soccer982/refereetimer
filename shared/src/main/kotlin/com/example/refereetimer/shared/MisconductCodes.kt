package com.example.refereetimer.shared

/**
 * Misconduct codes for the offences listed in Law 12 of the IFAB Laws of the Game
 * (the laws FIFA and US Soccer play under). Abbreviations follow the common US
 * reporting style. If your league uses different abbreviations, change them here:
 * this is the only list, used by both the watch and the phone report.
 */
data class MisconductCode(val code: String, val description: String)

object MisconductCodes {

    /** Cautionable offences (yellow card), most common first. */
    val CAUTIONS = listOf(
        MisconductCode("UB",  "Unsporting behaviour"),
        MisconductCode("DT",  "Dissent by word or action"),
        MisconductCode("PI",  "Persistent offences"),
        MisconductCode("DR",  "Delaying the restart of play"),
        MisconductCode("FRD", "Failing to respect the required distance"),
        MisconductCode("E",   "Entering or re-entering without permission"),
        MisconductCode("L",   "Deliberately leaving without permission")
    )

    /** Sending-off offences (red card) a referee picks directly. */
    val SEND_OFFS = listOf(
        MisconductCode("SFP", "Serious foul play"),
        MisconductCode("VC",  "Violent conduct"),
        MisconductCode("DGF", "Denying an obvious goal-scoring opportunity by an offence"),
        MisconductCode("DGH", "Denying a goal or obvious goal-scoring opportunity by handball"),
        MisconductCode("AL",  "Offensive, insulting or abusive language or gestures"),
        MisconductCode("S",   "Biting or spitting at someone")
    )

    /** Recorded automatically when a player receives a second yellow. */
    val SECOND_CAUTION = MisconductCode("2CT", "Second caution in the same match")

    private val ALL = CAUTIONS + SEND_OFFS + SECOND_CAUTION

    fun describe(code: String?): String =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }?.description ?: (code ?: "")
}
