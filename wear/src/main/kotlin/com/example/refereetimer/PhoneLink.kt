package com.example.refereetimer

import android.content.Context
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.MatchClock
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
 * Everything the watch sends to the phone. Uses Data Items, which Wear OS queues:
 * if the phone is out of range, they are delivered when it reconnects.
 */
object PhoneLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Each event gets its own path so queued events never overwrite each other. */
    fun sendEvents(context: Context, events: List<MatchEvent>) {
        val app = context.applicationContext
        scope.launch {
            for (e in events) {
                try {
                    val request = PutDataMapRequest.create("${DataLayerPaths.PATH_MATCH_EVENT}/${e.id}").apply {
                        dataMap.putString(DataLayerPaths.KEY_EVENT_JSON, MatchJson.eventToJson(e).toString())
                        dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                    }
                    Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).await()
                } catch (ex: Exception) {
                    android.util.Log.e("PHONE_LINK", "Event send failed", ex)
                }
            }
        }
    }

    /** One period's movement track (replaces the previous copy for that period). */
    fun sendTrack(context: Context, period: Int, trackJson: String) =
        putItem(context, "${DataLayerPaths.PATH_MATCH_TRACK}/$period", DataLayerPaths.KEY_TRACK_JSON, trackJson)

    /** The 4-corner field calibration. */
    fun sendCalibration(context: Context, calibrationJson: String) =
        putItem(context, DataLayerPaths.PATH_FIELD_CALIBRATION, DataLayerPaths.KEY_CALIBRATION_JSON, calibrationJson)

    private fun putItem(context: Context, path: String, key: String, value: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val request = PutDataMapRequest.create(path).apply {
                    dataMap.putString(key, value)
                    dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).await()
            } catch (ex: Exception) {
                android.util.Log.e("PHONE_LINK", "Send failed: $path", ex)
            }
        }
    }

    /** One clock item, always replaced by the newest: the phone computes time from it. */
    fun sendClock(context: Context, clock: MatchClock) {
        val app = context.applicationContext
        scope.launch {
            try {
                val request = PutDataMapRequest.create(DataLayerPaths.PATH_MATCH_CLOCK).apply {
                    dataMap.putString(DataLayerPaths.KEY_CLOCK_JSON, clock.toJson().toString())
                    dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).await()
            } catch (ex: Exception) {
                android.util.Log.e("PHONE_LINK", "Clock send failed", ex)
            }
        }
    }
}
