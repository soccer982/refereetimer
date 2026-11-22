package com.example.refereetimer.phone

import android.content.Context
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.models.MatchEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * NEW: everything the PHONE sends to the watch. Data Items are queued by Wear OS,
 * so if the watch is out of range they arrive when it reconnects.
 */
object WatchLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Goals / fouls / cards / subs entered on the phone, so the watch score and card tracking match. */
    fun sendEvents(context: Context, events: List<MatchEvent>) {
        val app = context.applicationContext
        scope.launch {
            for (e in events) {
                try {
                    val req = PutDataMapRequest.create("${DataLayerPaths.PATH_PHONE_EVENT}/${e.id}").apply {
                        dataMap.putString(DataLayerPaths.KEY_EVENT_JSON, MatchJson.eventToJson(e).toString())
                        dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                    }
                    Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent()).await()
                } catch (ex: Exception) {
                    android.util.Log.e("WATCH_LINK", "Event to watch failed", ex)
                }
            }
        }
    }

    /** A field calibration loaded on the phone (e.g. from the Google Sheet). */
    fun sendCalibration(context: Context, calibrationJson: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val req = PutDataMapRequest.create(DataLayerPaths.PATH_PHONE_CALIBRATION).apply {
                    dataMap.putString(DataLayerPaths.KEY_CALIBRATION_JSON, calibrationJson)
                    dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent()).await()
            } catch (ex: Exception) {
                android.util.Log.e("WATCH_LINK", "Calibration to watch failed", ex)
            }
        }
    }
}
