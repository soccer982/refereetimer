package com.example.refereetimer.phone

import android.content.Context
import com.example.refereetimer.shared.SubstitutionRules
import org.json.JSONObject

/** NEW: saves the substitution rules chosen in Match Setup. */
object SubRulesStore {
    private const val PREFS = "SubRulesPrefs"

    fun load(context: Context): SubstitutionRules = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
            ?.let { SubstitutionRules.fromJson(JSONObject(it)) } ?: SubstitutionRules()
    } catch (e: Exception) { SubstitutionRules() }

    fun save(context: Context, rules: SubstitutionRules) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("json", rules.toJson().toString()).apply()
    }
}
