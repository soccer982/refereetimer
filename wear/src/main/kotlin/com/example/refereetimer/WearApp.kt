package com.example.refereetimer

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.refereetimer.shared.FieldCalibration
import org.json.JSONObject

/**
 * One shared timer, event record, GPS and field calibration for the whole watch app.
 * Everything saves itself to storage, so the match survives the app closing.
 */
class WearApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val provider by lazy {
        ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory.getInstance(this))
    }

    val timerViewModel: TimerViewModel by lazy { provider[TimerViewModel::class.java] }
    val eventViewModel: MatchEventViewModel by lazy { provider[MatchEventViewModel::class.java] }

    val trackRecorder: TrackRecorder by lazy { TrackRecorder(this) }
    val locationTagger: LocationTagger by lazy {
        LocationTagger(this) { fix -> trackRecorder.onFix(fix, timerViewModel.currentClock) }
    }

    // ── Field calibration (kept until the referee recalibrates) ──

    private val prefs by lazy { getSharedPreferences("field_calibration", Context.MODE_PRIVATE) }

    var fieldCalibration: FieldCalibration? = null
        get() {
            if (field == null) field = prefs.getString("json", null)?.let { FieldCalibration.fromJson(JSONObject(it)) }
            return field
        }
        private set

    fun saveFieldCalibration(cal: FieldCalibration) {
        fieldCalibration = cal
        val json = cal.toJson().toString()
        prefs.edit().putString("json", json).commit()
        PhoneLink.sendCalibration(this, json)
    }
}
