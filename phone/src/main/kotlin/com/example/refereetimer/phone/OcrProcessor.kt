package com.example.refereetimer.phone

import android.content.Context
import android.graphics.Bitmap
import com.example.refereetimer.shared.OcrLine
import com.example.refereetimer.shared.OcrRows
import com.example.refereetimer.shared.RosterCard
import com.example.refereetimer.shared.RosterCardParser
import com.example.refereetimer.shared.RosterCoach
import com.example.refereetimer.shared.RosterEntry
import com.example.refereetimer.shared.RosterSection
import com.example.refereetimer.shared.models.LeagueType
import com.example.refereetimer.shared.models.Player
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrProcessor(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var currentLeague = LeagueType.SJSL

    data class OcrResult(val players: List<Player>, val coaches: List<String>, val teamName: String, val flaggedCount: Int, val confidence: Float)

    fun setLeague(league: LeagueType) { currentLeague = league }

    fun close() = recognizer.close()

    /**
     * NEW: reads a picture and returns its text as printed rows (left to right, top to bottom).
     * Table columns that text recognition returns separately are put back together, a tilted
     * photo is straightened, and side-by-side Home/Away tables are read one after the other.
     */
    suspend fun readRows(bitmap: Bitmap): List<String> {
        val visionText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = mutableListOf<OcrLine>()
        val angles = mutableListOf<Double>()
        for (block in visionText.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            lines += OcrLine(line.text, b.left, b.top, b.right, b.bottom)
            val pts = line.cornerPoints
            if (pts != null && pts.size >= 2 && b.width() > b.height() * 2) {
                angles += Math.toDegrees(Math.atan2((pts[1].y - pts[0].y).toDouble(), (pts[1].x - pts[0].x).toDouble()))
            }
        }
        val skew = angles.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return OcrRows.rows(OcrRows.deskew(lines, skew))
    }

    /** NEW: reads a whole game card, or one cropped team section, into teams + match info. */
    suspend fun readCard(bitmap: Bitmap, league: LeagueType): RosterCard {
        val rows = readRows(bitmap)
        android.util.Log.d("ROSTER", "OCR rows:\n" + rows.joinToString("\n"))
        if (league == LeagueType.HIGH_SCHOOL) {
            val r = parseHighSchoolCard(rows.joinToString("\n"))
            return RosterCard(unassigned = RosterSection(
                r.teamName, r.coaches.map { RosterCoach(it) },
                r.players.map { RosterEntry(it.jerseyNumber, it.name, uncertain = it.flagged) }
            ))
        }
        return RosterCardParser.parseLines(rows)
    }

    suspend fun processGameCard(bitmap: Bitmap, league: LeagueType): OcrResult {
        val fullText = readRows(bitmap).joinToString("\n")
        return when (league) {
            LeagueType.SJSL, LeagueType.EDP, LeagueType.SJGSL -> parseGotSportCard(fullText)
            LeagueType.HIGH_SCHOOL -> parseHighSchoolCard(fullText)
            else -> parseGenericCard(fullText)
        }
    }

    private fun parseGotSportCard(fullText: String): OcrResult {
        val lines   = fullText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val players = mutableListOf<Player>(); val coaches = mutableListOf<String>(); var teamName = ""
        for (line in lines) { if (line.startsWith("H ") || line.startsWith("HOME")) { teamName = extractTeamName(line); break } }
        for (line in lines) { if (line.contains("Coach", ignoreCase = true)) { val n = extractCoachName(line); if (n.isNotBlank()) coaches.add(n) } }
        for (line in lines) { if (isGotSportRosterRow(line)) parseGotSportRosterRow(line)?.let { players.add(it) } }
        val flagged = players.count { it.flagged }
        val confidence = if (players.isEmpty()) 0f else players.count { !it.flagged }.toFloat() / players.size
        return OcrResult(players, coaches, teamName, flagged, confidence)
    }

    private fun isGotSportRosterRow(line: String): Boolean {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 3) return false
        return parts[0].toIntOrNull()?.let { it in 1..30 } == true && parts[1].length >= 5 && parts[1].all { it.isDigit() }
    }

    private fun parseGotSportRosterRow(line: String): Player? {
        val parts = line.trim().split("\\s+".toRegex()); if (parts.size < 4) return null
        return try {
            val jerseyStr = parts[2]; val jerseyNum = jerseyStr.filter { it.isDigit() }.toIntOrNull() ?: return null
            if (jerseyNum !in 1..99) return null
            val withoutDob = line.replace(Regex("""\d{2}/\d{2,4}$"""), "").trim()
            val partsNoDob = withoutDob.split("\\s+".toRegex())
            val name = if (partsNoDob.size > 3) partsNoDob.subList(3, partsNoDob.size).joinToString(" ").trim() else ""
            if (name.isBlank()) return null
            Player(jerseyNum, cleanName(name), jerseyStr.contains("/") || name.length < 3 || name.any { it.isDigit() })
        } catch (e: Exception) { null }
    }

    fun parseHighSchoolCard(fullText: String): OcrResult {
        val lines   = fullText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val players = mutableListOf<Player>(); val coaches = mutableListOf<String>()
        val isMapleShadeType = fullText.contains("Position", ignoreCase = true) && fullText.contains("Grade", ignoreCase = true)
        val teamName = lines.firstOrNull { it.contains("Soccer", ignoreCase = true) || it.contains("Varsity", ignoreCase = true) } ?: ""
        for (line in lines) { if (line.contains("Coach", ignoreCase = true) || line.contains("Head Coach", ignoreCase = true)) {
            val name = line.replace(Regex("(Head|Asst\\.?|Assistant)\\s+Coach\\s*[-–]?", RegexOption.IGNORE_CASE), "").trim()
            if (name.isNotBlank()) coaches.add(name) } }
        var tableStart = lines.indexOfFirst { it.contains("PLAYER", ignoreCase = true) || (it.contains("NAME", ignoreCase = true) && it.contains("NUMBER", ignoreCase = true)) }
        if (tableStart < 0) tableStart = 0
        for (i in tableStart + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.contains("Coach", ignoreCase = true) || line.contains("Date", ignoreCase = true)) continue
            val player = if (isMapleShadeType) parseMapleShadeRow(line) else parseMedfordTechRow(line)
            player?.let { players.add(it) }
        }
        val flagged = players.count { it.flagged }
        val confidence = if (players.isEmpty()) 0f else players.count { !it.flagged }.toFloat() / players.size
        return OcrResult(players, coaches, teamName, flagged, confidence)
    }

    private fun parseMedfordTechRow(line: String): Player? {
        val numbers = Regex("""\b(\d{1,2})\b""").findAll(line).map { it.value.toInt() }.filter { it in 1..99 }.toList()
        if (numbers.isEmpty()) return null
        val jerseyNum = numbers.firstOrNull() ?: return null
        val name = cleanName(line.replace(Regex("^[A-Z]\\s+"), "").replace(jerseyNum.toString(), "").replace(Regex("\\d+"), "").trim())
        if (name.isBlank() || name.length < 3) return null
        return Player(jerseyNum, name, name.any { it.isDigit() } || numbers.size > 2)
    }

    private fun parseMapleShadeRow(line: String): Player? {
        val parts = line.trim().split("\\s{2,}|\\t".toRegex())
        if (parts.size < 3) return null
        val name      = cleanName(parts[0]); if (name.isBlank()) return null
        val jerseyStr = parts.getOrNull(2) ?: return null
        val jerseyNum = jerseyStr.filter { it.isDigit() }.toIntOrNull() ?: return null
        if (jerseyNum !in 1..99) return null
        return Player(jerseyNum, name, jerseyStr.contains("/") || name.length < 3)
    }

    private fun parseGenericCard(fullText: String): OcrResult {
        val lines   = fullText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val players = mutableListOf<Player>()
        for (line in lines) {
            val jerseyNum = Regex("""\b(\d{1,2})\b""").find(line)?.value?.toIntOrNull() ?: continue
            if (jerseyNum !in 1..99) continue
            val name = cleanName(line.replace(jerseyNum.toString(), "").replace(Regex("\\d+"), "").trim())
            if (name.length >= 3) players.add(Player(jerseyNum, name, true))
        }
        return OcrResult(players, emptyList(), "", players.size, 0.5f)
    }

    private fun extractTeamName(line: String) = line.replace(Regex("^[HA]\\s+"), "").replace(Regex("\\d{4}$"), "").trim()
    private fun extractCoachName(line: String) = line.replace(Regex("Coach\\s*[-–]?", RegexOption.IGNORE_CASE), "").trim()
    private fun cleanName(name: String) = name.replace(Regex("""[^A-Za-z\s.\-']"""), "").trim().split(" ").joinToString(" ") { it.lowercase().capitalize() }
}
