package com.example.refereetimer.phone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * NEW: saves the whole match (teams, events, clock) to a small file on the phone
 * after every change, so nothing is lost if the app closes, crashes or the phone restarts.
 * Writes to a temp file first, then swaps it in, so a crash mid-save can't corrupt it.
 */
class MatchStateStore(context: Context) {
    private val file = File(context.filesDir, "match_state.json")

    fun load(): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Log.e("MATCH_STORE", "Saved match unreadable, starting fresh", e)
        null
    }

    fun save(state: JSONObject) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(state.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(state.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e("MATCH_STORE", "Could not save match", e)
        }
    }
}
