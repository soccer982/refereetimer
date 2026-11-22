package com.example.refereetimer

import android.content.Context
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.models.GpsPoint
import org.json.JSONArray
import java.io.File

/**
 * NEW: records where the referee is during play (one point every ~5 s) for the heatmaps.
 * Saved to a file as it goes, so a crash doesn't lose the track. The current period's
 * track is sent to the phone every minute and again when the period ends.
 */
class TrackRecorder(private val context: Context) {

    data class Point(val period: Int, val time: Long, val lat: Double, val lng: Double)

    private val file = File(context.filesDir, "track.csv")
    private val points = mutableListOf<Point>()
    private var lastPointAt = 0L
    private var lastSentAt = 0L

    init { load() }

    /** Called for every GPS fix. Only records while a period is being played. */
    @Synchronized
    fun onFix(fix: GpsPoint, clock: MatchClock) {
        if (!clock.isLive) return
        if (fix.accuracy > MAX_ACCURACY_M) return            // skip poor fixes
        val now = System.currentTimeMillis()
        if (now - lastPointAt < MIN_INTERVAL_MS) return
        lastPointAt = now
        val p = Point(clock.period, now, fix.latitude, fix.longitude)
        points.add(p)
        try { file.appendText("${p.period},${p.time},${p.lat},${p.lng}\n") } catch (e: Exception) { /* storage full */ }

        if (now - lastSentAt > SEND_EVERY_MS) sendPeriod(clock.period)
    }

    /** Send one period's full track to the phone (it replaces the previous copy). */
    @Synchronized
    fun sendPeriod(period: Int) {
        lastSentAt = System.currentTimeMillis()
        val arr = JSONArray()
        points.filter { it.period == period }.forEach {
            arr.put(JSONArray().put(it.time).put(it.lat).put(it.lng))
        }
        if (arr.length() > 0) PhoneLink.sendTrack(context, period, arr.toString())
    }

    @Synchronized
    fun clear() {
        points.clear()
        lastPointAt = 0L
        try { file.delete() } catch (e: Exception) { }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                val f = line.split(",")
                if (f.size == 4) points.add(Point(f[0].toInt(), f[1].toLong(), f[2].toDouble(), f[3].toDouble()))
            }
        } catch (e: Exception) { /* damaged file: start fresh */ }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 4_000L
        private const val SEND_EVERY_MS = 60_000L
        private const val MAX_ACCURACY_M = 25f
    }
}
