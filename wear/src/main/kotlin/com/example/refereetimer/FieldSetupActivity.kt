package com.example.refereetimer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.refereetimer.databinding.ActivityFieldSetupBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.FieldCalibration
import com.example.refereetimer.shared.GpsBlend
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import com.example.refereetimer.shared.models.GpsPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * NEW: field calibration. Walk the 4 corner flags IN ORDER around the field:
 *   1 → 2 along a goal line, 2 → 3 along a touchline, 3 → 4 along the other goal line.
 * Each capture averages the last few GPS readings to cut down GPS error.
 * Saved on the watch (reused every match on this field until redone) and sent to the phone.
 */
class FieldSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFieldSetupBinding
    private val app by lazy { application as WearApp }
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val recent = ArrayDeque<GpsPoint>()      // last few fixes
    private val corners = mutableListOf<GpsPoint>()
    private var result: FieldCalibration? = null
    private var updating = false

    private val instructions = listOf(
        "Stand on ANY corner flag",
        "Walk along the GOAL LINE to the next corner",
        "Walk along the TOUCHLINE to the next corner",
        "Walk along the other GOAL LINE to the last corner"
    )

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val loc = r.lastLocation ?: return
            recent.addLast(GpsPoint(loc.latitude, loc.longitude, loc.accuracy, false, System.currentTimeMillis()))
            while (recent.size > 8) recent.removeFirst()
            binding.txtGpsAccuracy.text = "GPS ±${loc.accuracy.toInt()} m" + if (loc.accuracy <= GOOD_ACCURACY_M) " ✓" else " (wait…)"
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startUpdates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityFieldSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCapture.setOnClickListener { capture() }
        binding.btnRedo.setOnClickListener { restart() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnClose.setOnClickListener { finish() }
        showStep()

        // NEW: listen for the phone's GPS answers, and check now whether it's helping
        Wearable.getMessageClient(this).addListener(phoneListener)
        binding.txtPhoneStatus.text = "📱 Checking phone…"
        lifecycleScope.launch {
            if (askPhone() == null && binding.txtPhoneStatus.text.startsWith("📱 Checking"))
                binding.txtPhoneStatus.text = "📱 For better accuracy, open Field Setup on the phone too"
        }

        if (app.locationTagger.hasPermission()) startUpdates()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updating) return
        if (!app.locationTagger.hasPermission()) {
            binding.txtGpsAccuracy.text = "Allow location in watch Settings"
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        updating = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(phoneListener)
        if (updating) client.removeLocationUpdates(callback)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showStep() {
        val i = corners.size
        binding.txtSetupStep.text = "CORNER ${i + 1} OF 4"
        binding.txtSetupInstruction.text = instructions[i]
        binding.btnCapture.visibility = View.VISIBLE
        binding.btnSave.visibility = View.GONE
        binding.setupScroll.scrollTo(0, 0)
    }

    // ── NEW: the phone's GPS helps (Field Setup open on the phone) ──

    private var pendingPhoneFix: CompletableDeferred<GpsPoint?>? = null
    private var capturing = false

    private val phoneListener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path != DataLayerPaths.MSG_GPS_FIX_REPLY) return@OnMessageReceivedListener
        val fix = GpsBlend.fromJson(String(ev.data))
        runOnUiThread {
            pendingPhoneFix?.complete(fix)
            binding.txtPhoneStatus.text = if (fix != null) "📱 Phone helping (±${fix.accuracy.toInt()} m)"
                else "📱 Phone open but no GPS fix yet"
        }
    }

    /** Asks the phone for its current averaged position; null if it doesn't answer within 2.5 s. */
    private suspend fun askPhone(): GpsPoint? {
        val answer = CompletableDeferred<GpsPoint?>()
        pendingPhoneFix = answer
        try {
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            if (nodes.isEmpty()) return null
            nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, DataLayerPaths.MSG_GPS_FIX_REQUEST, ByteArray(0)).await() }
        } catch (e: Exception) { return null }
        return withTimeoutOrNull(2_500L) { answer.await() }
    }

    /** Average the watch's recent good fixes, blend in the phone's, and record the corner. */
    private fun capture() {
        if (capturing) return
        val now = System.currentTimeMillis()
        val fresh = recent.filter { now - it.timestamp < 8_000L }
        val good = fresh.filter { it.accuracy <= GOOD_ACCURACY_M }.ifEmpty { fresh.filter { it.accuracy <= OK_ACCURACY_M } }
        val watchFix = GpsBlend.average(good)
        capturing = true
        binding.btnCapture.isEnabled = false
        binding.txtGpsAccuracy.text = "Asking phone…"

        lifecycleScope.launch {
            val phoneFix = askPhone()
            capturing = false
            binding.btnCapture.isEnabled = true
            val corner = GpsBlend.combine(listOfNotNull(watchFix, phoneFix))
            if (corner == null) {
                binding.txtGpsAccuracy.text = "GPS not accurate yet: wait a few seconds"
                vibrate(longArrayOf(0, 80, 80, 80))
                return@launch
            }
            corners.add(corner)
            vibrate(longArrayOf(0, 150))
            binding.txtGpsAccuracy.text = listOfNotNull(
                watchFix?.let { "⌚±${it.accuracy.toInt()}" }, phoneFix?.let { "📱±${it.accuracy.toInt()}" }
            ).joinToString(" + ") + " → ±${"%.1f".format(corner.accuracy)} m ✓"
            binding.txtPhoneStatus.text = if (phoneFix != null) "📱 Phone helping" else "📱 Phone not helping: open Field Setup on the phone"

            if (corners.size < 4) {
                showStep()
            } else {
                val cal = FieldCalibration(corners.toList())
                result = cal
                val problem = cal.problem()
                val both = corners.count { it.corrected }
                binding.txtSetupStep.text = if (problem == null) "FIELD ${cal.describe()} ✓" else "CHECK FIELD"
                binding.txtSetupInstruction.text = problem ?: ("Corner 1→2 is the left end of the report diagram" +
                    if (both > 0) "\n$both/4 corners used watch + phone" else "")
                binding.btnCapture.visibility = View.GONE
                binding.btnSave.visibility = View.VISIBLE
                binding.btnSave.text = if (problem == null) "✓ SAVE FIELD" else "SAVE ANYWAY"
            }
        }
    }

    private fun restart() {
        corners.clear()
        result = null
        showStep()
    }

    private fun save() {
        val cal = result ?: return
        app.saveFieldCalibration(cal)
        vibrate(longArrayOf(0, 100, 60, 100))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        private const val GOOD_ACCURACY_M = 8f
        private const val OK_ACCURACY_M = 15f
    }
}
