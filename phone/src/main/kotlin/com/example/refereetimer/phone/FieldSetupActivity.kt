package com.example.refereetimer.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.refereetimer.phone.databinding.ActivityPhoneFieldSetupBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.GpsBlend
import com.example.refereetimer.shared.models.GpsPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch

/**
 * NEW: phone Field Setup.
 *  1. GPS helper: while this screen is open, every CAPTURE on the watch asks the phone for its
 *     averaged position, and the watch blends both (better accuracy than the watch alone).
 *  2. Google Sheet: load a saved field (nearest first) or save the current one.
 */
class FieldSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneFieldSetupBinding
    private val viewModel by lazy { (application as PhoneApp).matchViewModel }
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val recent = ArrayDeque<GpsPoint>()
    private var updating = false
    private var answered = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            recent.addLast(GpsPoint(l.latitude, l.longitude, l.accuracy, false, System.currentTimeMillis()))
            while (recent.size > 10) recent.removeFirst()
            binding.txtPhoneGps.text = "📱 Phone GPS ±${l.accuracy.toInt()} m" +
                if (l.accuracy <= 8f) "  ✓ ready" else "  (waiting for better accuracy)"
        }
    }

    /** The watch asks: reply with the phone's averaged recent position. */
    private val watchListener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path != DataLayerPaths.MSG_GPS_FIX_REQUEST) return@OnMessageReceivedListener
        val now = System.currentTimeMillis()
        val fresh = recent.filter { now - it.timestamp < 8_000L }
        val good = fresh.filter { it.accuracy <= 10f }.ifEmpty { fresh.filter { it.accuracy <= 20f } }
        val fix = GpsBlend.average(good)
        val payload = if (fix != null) GpsBlend.toJson(fix, good.size) else """{"none":true}"""
        Wearable.getMessageClient(this).sendMessage(ev.sourceNodeId, DataLayerPaths.MSG_GPS_FIX_REPLY, payload.toByteArray())
        runOnUiThread {
            answered++
            binding.txtHelperLog.text = if (fix != null) "⌚ Watch asked $answered× · sent ±${"%.1f".format(fix.accuracy)} m"
                else "⌚ Watch asked, but the phone has no GPS fix yet"
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startGps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // keeps GPS running in a pocket
        binding = ActivityPhoneFieldSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.inputScriptUrl.setText(FieldLibrary.scriptUrl(this))
        binding.inputScriptKey.setText(FieldLibrary.key(this))
        binding.btnSaveSheetSettings.setOnClickListener { saveSettingsAndTest() }
        binding.btnLoadField.setOnClickListener { loadField() }
        binding.btnSaveField.setOnClickListener { saveField() }
        updateSheetStatus()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(watchListener)
        if (hasPermission()) startGps()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        showCurrentField()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(watchListener)
        if (updating) { location.removeLocationUpdates(callback); updating = false }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (updating) return
        if (!hasPermission()) { binding.txtPhoneGps.text = "📱 Allow location for this app in phone Settings"; return }
        location.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build(), callback, Looper.getMainLooper())
        updating = true
    }

    private fun showCurrentField() {
        val cal = viewModel.fieldCalibration
        binding.txtCurrentField.text = if (cal == null) "Not calibrated" else {
            val both = cal.corners.count { it.corrected }
            "${cal.describe()} · ±${"%.1f".format(cal.corners.map { it.accuracy.toDouble() }.average())} m · " +
                if (both > 0) "watch + phone ($both/4 corners)" else "watch only"
        }
    }

    // ── Google Sheet ──

    private fun updateSheetStatus() {
        binding.txtSheetStatus.text = if (FieldLibrary.isSetUp(this)) "Connected to your sheet"
            else "Set up the sheet connection below first (one time)."
    }

    private fun saveSettingsAndTest() {
        FieldLibrary.saveSettings(this, binding.inputScriptUrl.text.toString(), binding.inputScriptKey.text.toString())
        if (!FieldLibrary.isSetUp(this)) {
            binding.txtSheetStatus.text = "⚠ The URL should start with https://script.google.com/ and a key is needed"
            return
        }
        binding.txtSheetStatus.text = "Testing…"
        lifecycleScope.launch {
            binding.txtSheetStatus.text = try {
                val n = FieldLibrary.list(this@FieldSetupActivity).size
                "✓ Connected: $n saved field${if (n == 1) "" else "s"} in the sheet"
            } catch (e: Exception) { "⚠ ${e.message}" }
        }
    }

    private fun loadField() {
        if (!FieldLibrary.isSetUp(this)) { updateSheetStatus(); return }
        binding.txtSheetStatus.text = "Loading saved fields…"
        lifecycleScope.launch {
            val fields = try { FieldLibrary.list(this@FieldSetupActivity) } catch (e: Exception) {
                binding.txtSheetStatus.text = "⚠ ${e.message}"; return@launch
            }.filter { it.calibration != null }
            if (fields.isEmpty()) { binding.txtSheetStatus.text = "No saved fields yet"; return@launch }

            // Nearest field first (when the phone knows where it is)
            val here = recent.lastOrNull()?.let { it.latitude to it.longitude }
            val sorted = if (here != null) fields.sortedBy { f -> f.centre?.let { FieldLibrary.distanceKm(here, it) } ?: 1e9 } else fields
            val labels = sorted.map { f ->
                val dist = if (here != null) f.centre?.let { " · %.1f km away".format(FieldLibrary.distanceKm(here, it)) } ?: "" else ""
                "${f.name}\n${f.lengthM.toInt()} × ${f.widthM.toInt()} m · ±${"%.1f".format(f.accuracyM)} m$dist"
            }
            binding.txtSheetStatus.text = "${fields.size} saved field${if (fields.size == 1) "" else "s"}"
            AlertDialog.Builder(this@FieldSetupActivity)
                .setTitle("Load a saved field")
                .setItems(labels.toTypedArray()) { _, i -> useField(sorted[i]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun useField(f: SavedField) {
        viewModel.applyCalibration(f.calibrationJson)
        WatchLink.sendCalibration(this, f.calibrationJson)      // the watch uses it too
        showCurrentField()
        binding.txtSheetStatus.text = "✓ Using \"${f.name}\" on phone and watch"
    }

    private fun saveField() {
        val cal = viewModel.fieldCalibration
        if (cal == null) { binding.txtSheetStatus.text = "Calibrate the field on the watch first"; return }
        if (!FieldLibrary.isSetUp(this)) { updateSheetStatus(); return }
        val details = MatchDetails.load(this)
        val input = EditText(this).apply {
            hint = "Field name (e.g. Valleybrook Field #3)"
            setText(details.venue.substringBefore(",").trim())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Save field to sheet")
            .setMessage("${cal.describe()}. Saving with an existing name replaces that field.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Field ${cal.describe()}" }
                binding.txtSheetStatus.text = "Saving…"
                lifecycleScope.launch {
                    binding.txtSheetStatus.text = try {
                        val updated = FieldLibrary.save(this@FieldSetupActivity, name, details.venue,
                            details.yourName.ifBlank { details.referee }, cal)
                        if (updated) "✓ Updated \"$name\" in the sheet" else "✓ Saved \"$name\" to the sheet"
                    } catch (e: Exception) { "⚠ ${e.message}" }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
