package com.example.refereetimer

import android.os.Handler
import android.os.Looper
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.models.Team
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.example.refereetimer.shared.FieldCalibration
import org.json.JSONObject

/** Receives rosters, match settings and reset from the phone. */
class WearDataService : WearableListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as WearApp

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val payload = String(messageEvent.data)
        when (messageEvent.path) {
            DataLayerPaths.DATA_MATCH_CONFIG -> main.post { applyMatchConfig(payload) }
            DataLayerPaths.MSG_RESET -> main.post {
                app.timerViewModel.resetMatch()
                app.eventViewModel.reset()
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val rosters = mutableListOf<Pair<Boolean, String>>() // (isHome, json)
        val phoneEvents = mutableListOf<Pair<android.net.Uri, String>>()
        var calibration: String? = null
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            // NEW: events entered on the phone
            if (path.startsWith(DataLayerPaths.PATH_PHONE_EVENT + "/")) {
                map.getString(DataLayerPaths.KEY_EVENT_JSON)?.let { phoneEvents.add(event.dataItem.uri to it) }
                continue
            }
            // NEW: a field loaded on the phone (e.g. from the Google Sheet)
            if (path == DataLayerPaths.PATH_PHONE_CALIBRATION) {
                calibration = map.getString(DataLayerPaths.KEY_CALIBRATION_JSON) ?: calibration
                continue
            }
            val isHome = when (path) {
                DataLayerPaths.DATA_ROSTER_HOME -> true
                DataLayerPaths.DATA_ROSTER_AWAY -> false
                else -> continue   // includes our own /match/... items
            }
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap
                .getString(DataLayerPaths.KEY_ROSTER_JSON) ?: continue
            rosters.add(isHome to json)
        }
        if (rosters.isNotEmpty()) main.post { rosters.forEach { applyRoster(it.second, it.first) } }

        if (phoneEvents.isNotEmpty()) {
            main.post {
                val ev = app.eventViewModel
                val parsed = phoneEvents.mapNotNull { (_, json) ->
                    runCatching { MatchJson.eventFromJson(JSONObject(json)) { ev.team(it) } }.getOrNull()
                }
                ev.addFromPhone(parsed)
            }
            // Saved on the watch now; clear them from the sync queue
            val client = Wearable.getDataClient(this)
            phoneEvents.forEach { client.deleteDataItems(it.first) }
        }

        calibration?.let { json ->
            main.post {
                FieldCalibration.fromJson(JSONObject(json))?.let { app.saveFieldCalibration(it) }
            }
        }
    }

    private fun applyRoster(json: String, isHome: Boolean) {
        try {
            val team = MatchJson.teamFromJson(JSONObject(json)).copy(isHome = isHome)
            val ev = app.eventViewModel
            val timer = app.timerViewModel
            if (isHome) {
                ev.setTeams(team, ev.team(false))
                timer.setTeamNames(team.name, timer.currentClock.awayName)
            } else {
                ev.setTeams(ev.team(true), team)
                timer.setTeamNames(timer.currentClock.homeName, team.name)
            }
        } catch (e: Exception) {
            android.util.Log.e("WEAR_DATA", "Bad roster data", e)
        }
    }

    private fun applyMatchConfig(json: String) {
        try {
            val j = JSONObject(json)
            val home = j.optString("homeName", "Home")
            val away = j.optString("awayName", "Away")
            app.timerViewModel.configure(
                j.optInt("numPeriods", 2), j.optInt("periodMins", 45), j.optInt("breakMins", 10), home, away
            )
            // Rename teams but keep any roster players already received
            val ev = app.eventViewModel
            ev.setTeams(ev.team(true).copy(name = home), ev.team(false).copy(name = away))
        } catch (e: Exception) {
            android.util.Log.e("WEAR_DATA", "Bad match config", e)
        }
    }
}
