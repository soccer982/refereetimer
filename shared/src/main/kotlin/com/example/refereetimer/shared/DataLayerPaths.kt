package com.example.refereetimer.shared

/**
 * VERSION 3: SHARED COMMUNICATION CONSTANTS
 * Centralizing these paths ensures both Phone and Watch modules are
 * perfectly synchronized in their messaging protocols.
 */
object DataLayerPaths {
    // ── NEW V3 COMMAND & SYNC PATHS (Master Clock Architecture) ──
    const val PATH_COMMAND          = "/match/command"         // Used for START, PAUSE, RESET
    const val PATH_SYNC_TIME_UPDATE = "/match/sync_time_update" // Watch pushes time to phone engine
    const val PATH_MATCH_EVENT      = "/match/event"            // Standard event logging
    const val DATA_MATCH_CONFIG     = "/data/config"           // Phone pushes match rules to Watch
    const val PATH_MATCH_CLOCK      = "/match/clock"            // Watch's saved clock (Data Item, latest wins)

    // ── V3 MESSAGE/DATA KEYS ──
    const val KEY_STATE             = "state"
    const val KEY_REMAINING         = "remaining"
    const val KEY_EVENT_JSON        = "event_json"
    const val KEY_TIMESTAMP         = "timestamp"
    const val KEY_CLOCK_JSON        = "clock_json"

    // ── LEGACY & GPS CALIBRATION PATHS (Restored for Compilation) ──
    const val MSG_RESET = "/match/reset"
    const val MSG_GPS_CORRECTION    = "/gps/correction"
    const val MSG_CALIBRATE_POINT   = "/gps/calibrate/point"
    const val MSG_CALIBRATE_DONE    = "/gps/calibrate/done"
    const val DATA_GPS_BATCH        = "/data/gps/batch"
    const val DATA_ROSTER_HOME      = "/data/roster/home"
    const val DATA_ROSTER_AWAY      = "/data/roster/away"

    // ── LEGACY & GPS KEYS (Restored for Compilation) ──
    const val KEY_POINT_INDEX       = "point_index"
    const val KEY_GPS_LAT           = "gps_lat"
    const val KEY_GPS_LNG           = "gps_lng"
    const val KEY_GPS_ACCURACY      = "gps_accuracy"
    const val KEY_DRIFT_LAT         = "drift_lat"
    const val KEY_DRIFT_LNG         = "drift_lng"
    const val KEY_CALIBRATION_JSON  = "calibration_json"
    const val KEY_ROSTER_JSON       = "roster_json"
    const val KEY_GPS_BATCH_JSON    = "gps_batch_json"
    const val KEY_PERIOD            = "period"
    const val KEY_ELAPSED           = "elapsed"
    const val KEY_HOME_SCORE        = "home_score"
    const val KEY_AWAY_SCORE        = "away_score"
    const val KEY_HOME_NAME         = "home_name"
    const val KEY_AWAY_NAME         = "away_name"
}