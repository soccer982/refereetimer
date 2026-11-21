package com.example.refereetimer.shared.models

data class MatchConfig(
    val homeTeam: Team,
    val awayTeam: Team,
    val league: LeagueType,
    val venue: String = "",
    val date: Long = System.currentTimeMillis(),
    val regularPeriodMillis: Long,
    val regularPeriodsCount: Int,
    val breakMillis: Long,
    val hasOvertime: Boolean,
    val otPeriodMillis: Long = 0L,
    val calibrationPoints: List<CalibrationPoint> = emptyList(),
    val homographyMatrix: FloatArray? = null
) {
    enum class LeagueType {
        SJSL, EDP, SJGSL, HIGH_SCHOOL, OTHER
    }

    fun getTeam(isHome: Boolean): Team =
        if (isHome) homeTeam else awayTeam

    fun isCalibrated(): Boolean =
        calibrationPoints.size >= 11 && homographyMatrix != null
}

data class CalibrationPoint(
    val pointIndex: Int,
    val label: String,
    val watchGps: GpsPoint,
    val phoneGps: GpsPoint,
    val weightedGps: GpsPoint,
    val expectedFieldX: Float,
    val expectedFieldY: Float
) {
    companion object {
        val EXPECTED_POINTS = listOf(
            CalibrationPointDef(0,  "Home Left Corner",           0.0f,  0.0f),
            CalibrationPointDef(1,  "Home Right Corner",          1.0f,  0.0f),
            CalibrationPointDef(2,  "Home Left Penalty Corner",   0.0f,  0.147f),
            CalibrationPointDef(3,  "Home Right Penalty Corner",  1.0f,  0.147f),
            CalibrationPointDef(4,  "Home Left Penalty Top",      0.217f,0.147f),
            CalibrationPointDef(5,  "Home Right Penalty Top",     0.783f,0.147f),
            CalibrationPointDef(6,  "Left Midfield Flag",         0.0f,  0.5f),
            CalibrationPointDef(7,  "Center Spot",                0.5f,  0.5f),
            CalibrationPointDef(8,  "Right Midfield Flag",        1.0f,  0.5f),
            CalibrationPointDef(9,  "Away Left Penalty Top",      0.217f,0.853f),
            CalibrationPointDef(10, "Away Right Penalty Top",     0.783f,0.853f)
        )
    }
}

data class CalibrationPointDef(
    val index: Int,
    val label: String,
    val fieldX: Float,
    val fieldY: Float
)

// Re-export LeagueType at top level for OcrProcessor
typealias LeagueType = MatchConfig.LeagueType
