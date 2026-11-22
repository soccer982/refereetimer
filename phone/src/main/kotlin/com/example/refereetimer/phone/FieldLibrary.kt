package com.example.refereetimer.phone

import android.content.Context
import com.example.refereetimer.shared.FieldCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A field calibration saved in the Google Sheet. */
data class SavedField(
    val name: String,
    val address: String,
    val lengthM: Double,
    val widthM: Double,
    val accuracyM: Double,
    val method: String,
    val saved: String,
    val calibrationJson: String
) {
    val calibration: FieldCalibration? get() = runCatching { FieldCalibration.fromJson(JSONObject(calibrationJson)) }.getOrNull()

    /** Centre of the field, from the four corners. */
    val centre: Pair<Double, Double>? get() = calibration?.corners?.let { c ->
        c.map { it.latitude }.average() to c.map { it.longitude }.average()
    }
}

/**
 * NEW: saves and loads field calibrations in your Google Sheet, through the small
 * Apps Script web app attached to the sheet (see RefereeFieldLibrary.gs).
 */
object FieldLibrary {
    private const val PREFS = "FieldLibraryPrefs"

    fun scriptUrl(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "") ?: ""
    fun key(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", "") ?: ""
    fun saveSettings(ctx: Context, url: String, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("url", url.trim()).putString("key", key.trim()).apply()
    }
    fun isSetUp(ctx: Context) = scriptUrl(ctx).startsWith("https://script.google.com/") && key(ctx).isNotBlank()

    suspend fun list(ctx: Context): List<SavedField> = withContext(Dispatchers.IO) {
        val url = "${scriptUrl(ctx)}?action=list&key=${URLEncoder.encode(key(ctx), "UTF-8")}"
        val res = JSONObject(http(url, null))
        if (!res.optBoolean("ok")) throw IllegalStateException(res.optString("error", "Sheet error"))
        val arr = res.getJSONArray("fields")
        (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            SavedField(
                f.optString("name"), f.optString("address"), f.optDouble("lengthM", 0.0), f.optDouble("widthM", 0.0),
                f.optDouble("accuracyM", 0.0), f.optString("method"), f.optString("saved"), f.optString("calibration")
            )
        }
    }

    /** Adds the field, or updates it if a field with the same name is already in the sheet. Returns true if updated. */
    suspend fun save(ctx: Context, name: String, address: String, savedBy: String, cal: FieldCalibration): Boolean =
        withContext(Dispatchers.IO) {
            val both = cal.corners.count { it.corrected }
            val body = JSONObject().apply {
                put("key", key(ctx))
                put("action", "save")
                put("field", JSONObject().apply {
                    put("name", name); put("address", address); put("savedBy", savedBy)
                    put("lengthM", cal.lengthMetres); put("widthM", cal.widthMetres)
                    put("accuracyM", cal.corners.map { it.accuracy.toDouble() }.average())
                    put("method", if (both > 0) "watch + phone ($both/4 corners)" else "watch only")
                    put("calibration", cal.toJson().toString())
                })
            }.toString()
            val res = JSONObject(http(scriptUrl(ctx), body))
            if (!res.optBoolean("ok")) throw IllegalStateException(res.optString("error", "Sheet error"))
            res.optBoolean("updated")
        }

    /** Distance in km between two points. */
    fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.first - a.first); val dLng = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * GET (body = null) or POST JSON. Google Apps Script answers with a redirect to the result;
     * redirects are followed by hand so a POST's answer is fetched with GET, as Google expects.
     */
    private fun http(startUrl: String, body: String?): String {
        var url = startUrl
        var method = if (body != null) "POST" else "GET"
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 20_000
                requestMethod = method
                if (method == "POST" && body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                    method = "GET"
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("Sheet returned HTTP $code")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                if (text.trimStart().startsWith("<")) throw IllegalStateException(
                    "The sheet's web app isn't set up for 'Anyone' access, or the URL is wrong")
                return text
            } finally { c.disconnect() }
        }
        throw IllegalStateException("Too many redirects")
    }
}
