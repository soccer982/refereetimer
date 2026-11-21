package com.example.refereetimer.shared

/**
 * Misconduct report codes: U.S. Soccer Federation Referee Program,
 * "Report Codes for Cautionable and Sending-Off Offenses" (IFAB 2020-21 Laws of the Game).
 * This is the only list: used by the watch and by every report.
 */
data class MisconductCode(val code: String, val description: String)

object MisconductCodes {

    // ── Players, substitutes and substituted players ──

    /** Cautionable offenses (yellow card), in USSF order. ERA/ERS apply only in VAR competitions. */
    val CAUTIONS = listOf(
        MisconductCode("UB",  "Unsporting behavior"),
        MisconductCode("DT",  "Dissent by word or action"),
        MisconductCode("PO",  "Persistent offenses"),
        MisconductCode("DR",  "Delaying the restart of play"),
        MisconductCode("FRD", "Failure to respect the required distance"),
        MisconductCode("ERL", "Entering, re-entering, or deliberately leaving the field without permission"),
        MisconductCode("ERA", "Entering the referee review area (VAR)"),
        MisconductCode("ERS", "Excessively using the 'review' (TV screen) signal (VAR)")
    )

    /** Sending-off offenses (red card) a referee picks directly. 2CT is added automatically. */
    val SEND_OFFS = listOf(
        MisconductCode("SFP", "Serious foul play"),
        MisconductCode("VC",  "Violent conduct"),
        MisconductCode("SB",  "Spitting at or biting an opponent or any other person"),
        MisconductCode("DGH", "Denying a goal or obvious goal-scoring opportunity by deliberate handling"),
        MisconductCode("DGF", "Denying an obvious goal-scoring opportunity by an offense punishable by a free kick"),
        MisconductCode("AL",  "Using offensive, insulting, or abusive language and/or gestures"),
        MisconductCode("EOR", "Entering the video operation room (VAR)")
    )

    /** Recorded automatically when a player receives a second yellow. */
    val SECOND_CAUTION = MisconductCode("2CT", "Receiving a second caution in the same match")

    // ── Team officials (coaches) ──

    val TEAM_OFFICIAL_CAUTIONS = listOf(
        MisconductCode("CTO1", "Clearly/persistently not respecting the confines of the technical area"),
        MisconductCode("CTO2", "Delaying the restart of play by their team"),
        MisconductCode("CTO3", "Deliberately entering the opposing technical area (non-confrontational)"),
        MisconductCode("CTO4", "Dissent by word or action"),
        MisconductCode("CTO5", "Excessively/persistently gesturing for a yellow or red card"),
        MisconductCode("CTO6", "Gesturing or acting in a provocative or inflammatory manner"),
        MisconductCode("CTO7", "Persistent unacceptable behavior (including repeated offenses)"),
        MisconductCode("CTO8", "Showing a lack of respect for the game"),
        MisconductCode("CTO9", "Entering the referee review area (VAR)"),
        MisconductCode("CTO10", "Excessively showing the TV signal for a VAR review")
    )

    /** SOTO7 (second caution) is added automatically, like 2CT for players. */
    val TEAM_OFFICIAL_SEND_OFFS = listOf(
        MisconductCode("SOTO1", "Delaying the restart of play by the opposing team"),
        MisconductCode("SOTO2", "Deliberately leaving the technical area to show dissent or provoke"),
        MisconductCode("SOTO3", "Entering the opposing technical area aggressively/confrontationally"),
        MisconductCode("SOTO4", "Deliberately throwing/kicking an object onto the field"),
        MisconductCode("SOTO5", "Entering the field to confront an official or interfere with play"),
        MisconductCode("SOTO6", "Physical or aggressive behavior (including spitting or biting)"),
        MisconductCode("SOTO8", "Unauthorized electronic or communication equipment"),
        MisconductCode("SOTO9", "Violent conduct"),
        MisconductCode("SOTO10", "Entering the video operations room (VAR)")
    )

    val TEAM_OFFICIAL_SECOND_CAUTION = MisconductCode("SOTO7", "Receiving a second caution in the same match")

    /** Codes for a send-off that came from a second caution (player or team official). */
    val SECOND_CAUTION_CODES = setOf(SECOND_CAUTION.code, TEAM_OFFICIAL_SECOND_CAUTION.code)

    private val ALL = CAUTIONS + SEND_OFFS + SECOND_CAUTION +
        TEAM_OFFICIAL_CAUTIONS + TEAM_OFFICIAL_SEND_OFFS + TEAM_OFFICIAL_SECOND_CAUTION

    /** Older abbreviations from earlier versions of this app, mapped to the USSF codes. */
    private val LEGACY = mapOf("PI" to "PO", "E" to "ERL", "L" to "ERL", "S" to "SB")

    /** The USSF code for a stored code (converts old abbreviations). */
    fun official(code: String?): String? = code?.let { LEGACY[it.uppercase()] ?: it }

    fun describe(code: String?): String {
        val c = official(code) ?: return ""
        return ALL.firstOrNull { it.code.equals(c, ignoreCase = true) }?.description ?: c
    }
}
