package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.GpsPoint
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * NEW: combines GPS readings from the watch and the phone at one corner flag.
 * Each reading counts in proportion to 1 / accuracy² (the more accurate device counts more),
 * which is the standard way to average independent measurements. Two ±4 m readings give about ±2.8 m.
 */
object GpsBlend {

    /** Readings worse than this are ignored (unless nothing better exists). */
    const val MAX_USEFUL_ACCURACY_M = 25f

    fun combine(readings: List<GpsPoint>): GpsPoint? {
        val usable = readings.filter { it.accuracy > 0f && it.accuracy <= MAX_USEFUL_ACCURACY_M }.ifEmpty { readings }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return usable[0]
        val weights = usable.map { 1.0 / (it.accuracy.toDouble() * it.accuracy) }
        val total = weights.sum()
        val lat = usable.indices.sumOf { usable[it].latitude * weights[it] } / total
        val lng = usable.indices.sumOf { usable[it].longitude * weights[it] } / total
        val acc = (1.0 / sqrt(total)).toFloat()
        // "corrected" = true marks a corner that used more than one device
        return GpsPoint(lat, lng, acc, true, usable.maxOf { it.timestamp })
    }

    /** Average of several readings from ONE device over a few seconds. */
    fun average(readings: List<GpsPoint>): GpsPoint? {
        if (readings.isEmpty()) return null
        val acc = readings.map { it.accuracy }.average().toFloat() / sqrt(readings.size.toFloat()).coerceAtMost(2f)
        return GpsPoint(readings.map { it.latitude }.average(), readings.map { it.longitude }.average(),
            acc, false, readings.maxOf { it.timestamp })
    }

    fun toJson(p: GpsPoint, samples: Int): String =
        JSONObject().put("lat", p.latitude).put("lng", p.longitude).put("acc", p.accuracy.toDouble()).put("n", samples).toString()

    fun fromJson(s: String): GpsPoint? = try {
        val j = JSONObject(s)
        if (j.optBoolean("none")) null
        else GpsPoint(j.getDouble("lat"), j.getDouble("lng"), j.getDouble("acc").toFloat(), false)
    } catch (e: Exception) { null }
}
