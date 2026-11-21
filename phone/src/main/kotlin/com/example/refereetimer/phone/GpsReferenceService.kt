package com.example.refereetimer.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class GpsReferenceService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var calibratedLat = 0.0; private var calibratedLng = 0.0
    private var isCalibrated  = false
    private var currentLat    = 0.0; private var currentLng    = 0.0
    private var currentAccuracy = 99f
    private var lastCorrectionSentAt = 0L
    private val CORRECTION_INTERVAL  = 30_000L

    val gpsTrack = mutableListOf<GpsTrackPoint>()

    data class GpsTrackPoint(
        val lat: Double, val lng: Double,
        val fieldX: Float, val fieldY: Float,
        val period: Int, val elapsed: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            currentLat = loc.latitude; currentLng = loc.longitude; currentAccuracy = loc.accuracy
            if (!isCalibrated) return
            val driftLat = currentLat - calibratedLat; val driftLng = currentLng - calibratedLng
            val driftM   = haversineMeters(calibratedLat, calibratedLng, currentLat, currentLng)
            val now      = System.currentTimeMillis()
            if (driftM > 0.5f && now - lastCorrectionSentAt > CORRECTION_INTERVAL) {
                sendDriftCorrection(driftLat, driftLng); lastCorrectionSentAt = now
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_CALIBRATED_POSITION -> { calibratedLat = currentLat; calibratedLng = currentLng; isCalibrated = true }
            ACTION_ADD_TRACK_POINT -> gpsTrack.add(GpsTrackPoint(
                lat = intent.getDoubleExtra(EXTRA_LAT, 0.0), lng = intent.getDoubleExtra(EXTRA_LNG, 0.0),
                fieldX = intent.getFloatExtra(EXTRA_FIELD_X, 0f), fieldY = intent.getFloatExtra(EXTRA_FIELD_Y, 0f),
                period = intent.getIntExtra(EXTRA_PERIOD, 1),   elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)))
            ACTION_STOP -> { stopLocationUpdates(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(5_000L).setMaxUpdateDelayMillis(10_000L).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {}
    }

    private fun stopLocationUpdates() { fusedLocationClient.removeLocationUpdates(locationCallback) }

    private fun sendDriftCorrection(driftLat: Double, driftLng: Double) {
        serviceScope.launch {
            try {
                val request = PutDataMapRequest.create("/data/gps/batch").apply {
                    dataMap.putDouble("drift_lat", driftLat); dataMap.putDouble("drift_lng", driftLng)
                    dataMap.putFloat("accuracy", currentAccuracy); dataMap.putLong("timestamp", System.currentTimeMillis())
                }
                Wearable.getDataClient(this@GpsReferenceService).putDataItem(request.asPutDataRequest()).await()
            } catch (e: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "gps_reference"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "GPS Reference Station", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Referee Timer").setContentText("GPS reference active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2)
        return (6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))).toFloat()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); stopLocationUpdates(); serviceScope.coroutineContext[Job]?.cancel() }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_SET_CALIBRATED_POSITION = "com.refereetimer.SET_CALIBRATED"
        const val ACTION_ADD_TRACK_POINT = "com.refereetimer.ADD_TRACK_POINT"
        const val ACTION_STOP = "com.refereetimer.GPS_STOP"
        const val EXTRA_LAT = "lat"; const val EXTRA_LNG = "lng"
        const val EXTRA_FIELD_X = "field_x"; const val EXTRA_FIELD_Y = "field_y"
        const val EXTRA_PERIOD = "period"; const val EXTRA_ELAPSED = "elapsed"
    }
}
