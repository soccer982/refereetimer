package com.example.refereetimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.refereetimer.databinding.ActivityGpsCalibrationBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.models.CalibrationPoint
import com.example.refereetimer.shared.models.CalibrationPointDef
import com.example.refereetimer.shared.models.GpsPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.Locale

class GpsCalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGpsCalibrationBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech
    private lateinit var vibratorManager: VibratorManager

    private var currentPointIndex = 0
    private val calibratedPoints  = mutableListOf<CalibrationPoint>()
    private var pendingPhoneGps: GpsPoint? = null

    // Reference the list directly — defined at top level for easy access
    private val expectedPoints = listOf(
        CalibrationPointDef(0,  "Home Left Corner",          0.0f,  0.0f),
        CalibrationPointDef(1,  "Home Right Corner",         1.0f,  0.0f),
        CalibrationPointDef(2,  "Home Left Penalty Corner",  0.0f,  0.147f),
        CalibrationPointDef(3,  "Home Right Penalty Corner", 1.0f,  0.147f),
        CalibrationPointDef(4,  "Home Left Penalty Top",     0.217f,0.147f),
        CalibrationPointDef(5,  "Home Right Penalty Top",    0.783f,0.147f),
        CalibrationPointDef(6,  "Left Midfield Flag",        0.0f,  0.5f),
        CalibrationPointDef(7,  "Center Spot",               0.5f,  0.5f),
        CalibrationPointDef(8,  "Right Midfield Flag",       1.0f,  0.5f),
        CalibrationPointDef(9,  "Away Left Penalty Top",     0.217f,0.853f),
        CalibrationPointDef(10, "Away Right Penalty Top",    0.783f,0.853f)
    )
    private val totalPoints = expectedPoints.size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding         = ActivityGpsCalibrationBinding.inflate(layoutInflater)
        vibratorManager = getSystemService(VibratorManager::class.java)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContentView(binding.root)
        setupTTS()
        setupUI()
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    private fun setupTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                lifecycleScope.launch {
                    delay(500)
                    promptForPoint(0)
                }
            }
        }
    }

    private fun setupUI() {
        binding.btnSkipCalibration.setOnClickListener {
            finishCalibration(skipped = true)
        }
        updateProgressDisplay()
    }

    private fun updateProgressDisplay() {
        val point = expectedPoints.getOrNull(currentPointIndex)
        binding.txtCalibrationProgress.text =
            "Point ${currentPointIndex + 1} of $totalPoints"
        binding.txtCalibrationLabel.text    = point?.label ?: "Complete"
        binding.txtCalibrationHint.text     = "Walk to this point\nthen press Crown"
    }

    private fun promptForPoint(index: Int) {
        if (index >= totalPoints) {
            finishCalibration(skipped = false)
            return
        }
        updateProgressDisplay()
        tts.speak(
            "Walk to ${expectedPoints[index].label}, then press the crown",
            TextToSpeech.QUEUE_FLUSH, null, null
        )
        vibratorManager.defaultVibrator.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun captureCurrentPoint() {
        val pointDef = expectedPoints[currentPointIndex]
        binding.txtCalibrationHint.text = "Reading GPS…"

        lifecycleScope.launch {
            try {
                val watchLocation = if (ActivityCompat.checkSelfPermission(
                        this@GpsCalibrationActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token
                    ).await()
                } else null

                if (watchLocation == null) {
                    binding.txtCalibrationHint.text = "GPS not ready\nTry again"
                    return@launch
                }

                val watchGps = GpsPoint(
                    watchLocation.latitude,
                    watchLocation.longitude,
                    watchLocation.accuracy,
                    false
                )

                sendCalibrationPointRequest(currentPointIndex)

                var waited = 0
                while (pendingPhoneGps == null && waited < 5000) {
                    delay(200)
                    waited += 200
                }

                val phoneGps    = pendingPhoneGps
                pendingPhoneGps = null

                val weightedGps = if (phoneGps != null) {
                    GpsPoint(
                        phoneGps.latitude  * 0.7 + watchGps.latitude  * 0.3,
                        phoneGps.longitude * 0.7 + watchGps.longitude * 0.3,
                        phoneGps.accuracy  * 0.7f + watchGps.accuracy * 0.3f,
                        true
                    )
                } else {
                    watchGps.copy()
                }

                calibratedPoints.add(
                    CalibrationPoint(
                        pointIndex     = currentPointIndex,
                        label          = pointDef.label,
                        watchGps       = watchGps,
                        phoneGps       = phoneGps ?: watchGps,
                        weightedGps    = weightedGps,
                        expectedFieldX = pointDef.fieldX,
                        expectedFieldY = pointDef.fieldY
                    )
                )

                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150), -1)
                )
                binding.txtCalibrationHint.text = "✓ Captured!"
                tts.speak(
                    "Point ${currentPointIndex + 1} confirmed",
                    TextToSpeech.QUEUE_FLUSH, null, null
                )

                delay(800)
                currentPointIndex++
                promptForPoint(currentPointIndex)

            } catch (e: Exception) {
                binding.txtCalibrationHint.text = "Error. Try again."
            }
        }
    }

    private fun sendCalibrationPointRequest(pointIndex: Int) {
        lifecycleScope.launch {
            try {
                val request = PutDataMapRequest.create(
                    DataLayerPaths.MSG_CALIBRATE_POINT
                ).apply {
                    dataMap.putInt(DataLayerPaths.KEY_POINT_INDEX, pointIndex)
                    dataMap.putLong(
                        DataLayerPaths.KEY_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                }
                Wearable.getDataClient(this@GpsCalibrationActivity)
                    .putDataItem(request.asPutDataRequest().setUrgent())
                    .await()
            } catch (e: Exception) { /* Phone unavailable */ }
        }
    }

    private fun computeAffineTransform(
        src: List<Pair<Float, Float>>,
        dst: List<Pair<Float, Float>>
    ): FloatArray {
        val n = src.size
        var sumX = 0f; var sumY = 0f; var sumU = 0f; var sumV = 0f
        var sumXX = 0f; var sumXY = 0f; var sumYY = 0f
        var sumXU = 0f; var sumYU = 0f; var sumXV = 0f; var sumYV = 0f

        for (i in 0 until n) {
            val x = src[i].first;  val y = src[i].second
            val u = dst[i].first;  val v = dst[i].second
            sumX+=x; sumY+=y; sumU+=u; sumV+=v
            sumXX+=x*x; sumXY+=x*y; sumYY+=y*y
            sumXU+=x*u; sumYU+=y*u; sumXV+=x*v; sumYV+=y*v
        }

        val det = sumXX * sumYY - sumXY * sumXY
        val a = if (det != 0f) (sumXU*sumYY - sumYU*sumXY) / det else 1f
        val b = if (det != 0f) (sumXX*sumYU - sumXY*sumXU) / det else 0f
        val c = (sumU - a*sumX - b*sumY) / n
        val d = if (det != 0f) (sumXV*sumYY - sumYV*sumXY) / det else 0f
        val e = if (det != 0f) (sumXX*sumYV - sumXY*sumXV) / det else 1f
        val f = (sumV - d*sumX - e*sumY) / n

        return floatArrayOf(a, b, c, d, e, f)
    }

    private fun finishCalibration(skipped: Boolean) {
        if (skipped) {
            tts.speak("Calibration skipped.", TextToSpeech.QUEUE_FLUSH, null, null)
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val matrix = if (calibratedPoints.size >= 4) {
            computeAffineTransform(
                calibratedPoints.map {
                    Pair(it.weightedGps.latitude.toFloat(),
                        it.weightedGps.longitude.toFloat())
                },
                calibratedPoints.map {
                    Pair(it.expectedFieldX, it.expectedFieldY)
                }
            )
        } else null

        lifecycleScope.launch {
            try {
                val request = PutDataMapRequest.create(
                    DataLayerPaths.MSG_CALIBRATE_DONE
                ).apply {
                    dataMap.putInt("point_count", calibratedPoints.size)
                    dataMap.putLong(
                        DataLayerPaths.KEY_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                }
                Wearable.getDataClient(this@GpsCalibrationActivity)
                    .putDataItem(request.asPutDataRequest().setUrgent())
                    .await()
            } catch (e: Exception) { /* Phone unavailable */ }
        }

        tts.speak(
            "Calibration complete. ${calibratedPoints.size} points captured.",
            TextToSpeech.QUEUE_FLUSH, null, null
        )
        vibratorManager.defaultVibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 200, 100, 200, 100, 400), -1)
        )

        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra("CALIBRATION_COMPLETE", true)
            putExtra("CALIBRATION_POINT_COUNT", calibratedPoints.size)
            matrix?.let { putExtra("HOMOGRAPHY_MATRIX", it) }
        })
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            captureCurrentPoint()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}