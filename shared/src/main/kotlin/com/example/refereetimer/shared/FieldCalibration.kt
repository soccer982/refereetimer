package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.GpsPoint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Field calibration from the 4 corner flags, walked IN ORDER around the field:
 *
 *    Corner 1 ──goal line── Corner 2
 *       │                      │
 *   touchline              touchline
 *       │                      │
 *    Corner 4 ──goal line── Corner 3
 *
 * Field coordinates: x = 0..1 along the LENGTH (goal line of corners 1-2 → goal line of 3-4),
 *                    y = 0..1 across the WIDTH  (touchline of corners 1-4 → touchline of 2-3).
 *
 * GPS positions are converted to metres on a flat local grid, then a least-squares fit
 * (using all 4 corners, so small GPS errors average out) maps metres → field coordinates.
 * Works at any angle the field happens to face.
 */
class FieldCalibration(val corners: List<GpsPoint>, val createdAt: Long = System.currentTimeMillis()) {

    init { require(corners.size == 4) { "Need exactly 4 corners" } }

    private val lat0 = corners.map { it.latitude }.average()
    private val lng0 = corners.map { it.longitude }.average()
    private val metresPerDegLat = 111_132.0
    private val metresPerDegLng = 111_320.0 * cos(Math.toRadians(lat0))

    /** Field coordinates of corners 1..4 (in walking order). */
    private val targets = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0)

    // x = a*e + b*n + c ;  y = d*e + f*n + g   (e/n = metres east/north)
    private val xCoef: DoubleArray
    private val yCoef: DoubleArray

    init {
        val pts = corners.map { toMetres(it.latitude, it.longitude) }
        xCoef = fitPlane(pts, targets.map { it.first })
        yCoef = fitPlane(pts, targets.map { it.second })
    }

    private fun toMetres(lat: Double, lng: Double): Pair<Double, Double> =
        Pair((lng - lng0) * metresPerDegLng, (lat - lat0) * metresPerDegLat)

    /** Distance in metres between two corners (0-based). */
    private fun dist(i: Int, j: Int): Double {
        val a = toMetres(corners[i].latitude, corners[i].longitude)
        val b = toMetres(corners[j].latitude, corners[j].longitude)
        return hypot(a.first - b.first, a.second - b.second)
    }

    /** Average of the two touchlines (corner 2→3 and 4→1). */
    val lengthMetres: Double get() = (dist(1, 2) + dist(3, 0)) / 2

    /** Average of the two goal lines (corner 1→2 and 3→4). */
    val widthMetres: Double get() = (dist(0, 1) + dist(2, 3)) / 2

    /**
     * A sanity check so a bad GPS reading gets caught at the field, not in the report.
     * Returns null if fine, or a short problem description.
     */
    fun problem(): String? {
        val l = lengthMetres; val w = widthMetres
        val touchDiff = kotlin.math.abs(dist(1, 2) - dist(3, 0)) / l
        val goalDiff = kotlin.math.abs(dist(0, 1) - dist(2, 3)) / w
        return when {
            l < 30 || w < 18 -> "Field looks too small (${l.toInt()} × ${w.toInt()} m)"
            l > 140 || w > 100 -> "Field looks too large (${l.toInt()} × ${w.toInt()} m)"
            w > l * 1.05 -> "Corners out of order? Walk 1→2 along a GOAL line"
            touchDiff > 0.2 || goalDiff > 0.25 -> "Sides don't match; GPS may be off"
            else -> null
        }
    }

    /** Position on the field, 0..1 each way (slightly outside = just off the field). */
    fun toField(lat: Double, lng: Double): Pair<Float, Float> {
        val (e, n) = toMetres(lat, lng)
        val x = xCoef[0] * e + xCoef[1] * n + xCoef[2]
        val y = yCoef[0] * e + yCoef[1] * n + yCoef[2]
        return Pair(x.toFloat(), y.toFloat())
    }

    fun describe(): String = "${lengthMetres.toInt()} × ${widthMetres.toInt()} m"

    fun toJson(): JSONObject = JSONObject().apply {
        put("createdAt", createdAt)
        put("corners", JSONArray().apply { corners.forEach { put(MatchJson.gpsToJson(it)) } })
    }

    companion object {
        fun fromJson(j: JSONObject): FieldCalibration? = try {
            val arr = j.getJSONArray("corners")
            FieldCalibration((0 until arr.length()).map { MatchJson.gpsFromJson(arr.getJSONObject(it)) }, j.optLong("createdAt"))
        } catch (e: Exception) { null }

        /** Least-squares fit of t = a*e + b*n + c through the points. */
        private fun fitPlane(pts: List<Pair<Double, Double>>, t: List<Double>): DoubleArray {
            var see = 0.0; var sen = 0.0; var se = 0.0; var snn = 0.0; var sn = 0.0
            var set = 0.0; var snt = 0.0; var st = 0.0
            val count = pts.size.toDouble()
            for (i in pts.indices) {
                val (e, n) = pts[i]; val v = t[i]
                see += e * e; sen += e * n; se += e; snn += n * n; sn += n
                set += e * v; snt += n * v; st += v
            }
            return solve3(
                arrayOf(doubleArrayOf(see, sen, se), doubleArrayOf(sen, snn, sn), doubleArrayOf(se, sn, count)),
                doubleArrayOf(set, snt, st)
            )
        }

        /** Solves a 3×3 linear system (Cramer's rule). */
        private fun solve3(m: Array<DoubleArray>, r: DoubleArray): DoubleArray {
            fun det(a: Array<DoubleArray>) =
                a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
            val d = det(m)
            if (kotlin.math.abs(d) < 1e-12) return doubleArrayOf(0.0, 0.0, 0.5)
            return DoubleArray(3) { col ->
                val mc = Array(3) { row -> DoubleArray(3) { c -> if (c == col) r[row] else m[row][c] } }
                det(mc) / d
            }
        }
    }
}
