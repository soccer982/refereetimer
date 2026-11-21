package com.example.refereetimer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.refereetimer.shared.models.GpsPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Keeps a fresh GPS fix while the match is live, so a card can be tagged
 * with the referee's position instantly (no waiting for GPS at the moment of the card).
 */
class LocationTagger(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var active = false

    @Volatile
    var latest: GpsPoint? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { store(it) }
        }
    }

    private fun store(loc: Location) {
        latest = GpsPoint(loc.latitude, loc.longitude, loc.accuracy, false, System.currentTimeMillis())
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Start regular updates (call when the match goes live). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (active || !hasPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            active = true
        } catch (e: Exception) {
            android.util.Log.e("GPS", "Could not start location updates", e)
        }
    }

    fun stop() {
        if (!active) return
        client.removeLocationUpdates(callback)
        active = false
    }

    /** Ask for an immediate fix (called the moment a card is started, as a head start). */
    @SuppressLint("MissingPermission")
    fun requestOneShot() {
        if (!hasPermission()) return
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc -> loc?.let { store(it) } }
        } catch (e: Exception) { /* no fix available */ }
    }

    /** The latest fix if it's recent enough to trust, else null. */
    fun freshFix(maxAgeMs: Long = 60_000L): GpsPoint? =
        latest?.takeIf { System.currentTimeMillis() - it.timestamp <= maxAgeMs }
}
