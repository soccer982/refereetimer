package com.example.refereetimer.phone

import android.content.Context

/** Who officiated and where: fills the header and footer of the US Soccer forms. */
data class MatchDetails(
    val referee: String = "",
    val refereeId: String = "",          // USSF ID number
    val ar1: String = "",
    val ar2: String = "",
    val fourth: String = "",
    val competition: String = "",        // Association / League
    val gameNumber: String = "",
    val venue: String = "",
    val division: String = "",           // NEW: Division / Age Group
    val refereeGrade: String = "",       // NEW: grades for each official
    val ar1Grade: String = "",
    val ar2Grade: String = "",
    val fourthGrade: String = "",
    val phone: String = "",              // NEW: referee contact (form footer)
    val email: String = "",
    val yourName: String = "",           // NEW: you, as the person completing the report (form footer)
    val gameDate: String = "",           // NEW: MM/dd/yyyy from the game confirmation
    val kickoff: String = ""             // NEW: scheduled kickoff, e.g. "10:45 AM"
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("referee", referee); putString("refereeId", refereeId)
            putString("ar1", ar1); putString("ar2", ar2); putString("fourth", fourth)
            putString("competition", competition); putString("gameNumber", gameNumber); putString("venue", venue)
            putString("division", division); putString("refereeGrade", refereeGrade)
            putString("ar1Grade", ar1Grade); putString("ar2Grade", ar2Grade); putString("fourthGrade", fourthGrade)
            putString("phone", phone); putString("email", email)
            putString("yourName", yourName); putString("gameDate", gameDate); putString("kickoff", kickoff)
            apply()
        }
    }

    companion object {
        private const val PREFS = "MatchDetailsPrefs"

        // Starting values until changed in Match Setup
        private const val DEFAULT_USSF_ID = "0010-2281-4464-2150"
        private const val DEFAULT_NAME = "Curtis Kleier"

        fun load(context: Context): MatchDetails {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun g(k: String) = p.getString(k, "") ?: ""
            return MatchDetails(
                g("referee"), p.getString("refereeId", null) ?: DEFAULT_USSF_ID, g("ar1"), g("ar2"), g("fourth"),
                g("competition"), g("gameNumber"), g("venue"), g("division"),
                g("refereeGrade"), g("ar1Grade"), g("ar2Grade"), g("fourthGrade"),
                g("phone"), g("email"),
                p.getString("yourName", null) ?: DEFAULT_NAME, g("gameDate"), g("kickoff")
            )
        }
    }
}
