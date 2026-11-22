package com.example.refereetimer.shared

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of recognised text, with its position on the screenshot (0s when it came from plain text). */
data class OcrLine(val text: String, val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val hasBox: Boolean get() = right > left
    val centerX: Int get() = (left + right) / 2
}

/** What could be read from a game assignment confirmation (screenshot or email). Blank = not found. */
data class GameConfirmation(
    val homeTeam: String = "",
    val awayTeam: String = "",
    val gameNumber: String = "",
    val date: LocalDate? = null,
    val kickoff: String = "",
    val endTime: String = "",
    val ageGroup: String = "",
    val league: String = "",
    val venue: String = "",
    val address: String = "",
    val referee: String = "",
    val ar1: String = "",
    val ar2: String = "",
    val fourth: String = ""
) {
    val isEmpty: Boolean get() = listOf(homeTeam, awayTeam, gameNumber, venue, referee).all { it.isBlank() } && date == null

    /** "Venue, address" for the report's location line. */
    val location: String get() = listOf(venue, address).filter { it.isNotBlank() }.joinToString(", ")

    val dateText: String get() = date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)) ?: ""

    /** Scheduled slot length in minutes (e.g. 10:45–11:55 = 70), if both times were found. */
    val slotMinutes: Int? get() {
        val a = toMinutes(kickoff) ?: return null
        val b = toMinutes(endTime) ?: return null
        return (b - a).takeIf { it in 10..300 }
    }

    /** Rows for the "here's what I found" review. */
    fun summary(): List<Pair<String, String>> = listOf(
        "Home team" to homeTeam, "Away team" to awayTeam, "Game #" to gameNumber,
        "Date" to (date?.format(DateTimeFormatter.ofPattern("EEE MM/dd/yyyy", Locale.US)) ?: ""),
        "Kickoff" to kickoff + (if (endTime.isNotBlank()) " – $endTime" else ""),
        "Division / Age group" to ageGroup, "Association / League" to league, "Location" to location,
        "Referee" to referee, "Assistant Referee 1" to ar1, "Assistant Referee 2" to ar2, "Fourth Official" to fourth
    )

    private fun toMinutes(t: String): Int? {
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp])?").find(t) ?: return null
        var h = m.groupValues[1].toInt() % 12
        if (m.groupValues[3].equals("p", true)) h += 12
        return h * 60 + m.groupValues[2].toInt()
    }
}

/**
 * NEW: reads a game confirmation from an assigning app screenshot (via text recognition)
 * or from pasted / shared email text. Handles the common layouts:
 *   • "Team A [HOME] vs Team B [AWAY]" titles      • teams in left/right columns around the date
 *   • "Home: X / Away: Y / Referee: Z" labels       • officials listed as a name line + a role line
 * Everything it finds is shown for review before it's applied.
 */
object GameConfirmationParser {

    private val HEADERS = setOf("location", "contacts", "officials", "assignments", "payment", "payments", "game fee",
        "notes", "schedule", "inbox", "profile", "game details", "game report", "directions", "weather", "crew",
        "details", "teams", "map", "you accepted this game", "accepted", "pending", "declined", "final", "scheduled")
    private val STATUS = Regex("(?i)\\s*(accepted|pending|declined|confirmed|published|>|›|»)\\s*$")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parseText(text: String, today: LocalDate = LocalDate.now()): GameConfirmation =
        parse(text.lines().map { OcrLine(it) }, 0, today)

    fun parse(raw: List<OcrLine>, width: Int = 0, today: LocalDate = LocalDate.now()): GameConfirmation {
        val lines = raw.mapNotNull { l -> clean(l.text).takeIf { keep(it) }?.let { l.copy(text = it) } }
            .let { ls -> if (ls.all { it.hasBox }) ls.sortedWith(compareBy({ it.top }, { it.left })) else ls }
        val w = if (width > 0) width else lines.maxOfOrNull { it.right } ?: 0
        val all = lines.joinToString("\n") { it.text }

        val labels = labelled(lines)

        // ── Date & time ──
        val dateLine = lines.firstOrNull { findDate(it.text, today) != null }
        val date = labels["date"]?.let { findDate(it, today) } ?: dateLine?.let { findDate(it.text, today) }
            ?: findDate(all.replace('\n', ' '), today)
        val (kickoff, endTime) = findTimes(lines, labels["time"])

        // ── Game number, age group, league ──
        val gameNumber = labels["game"] ?: Regex("(?i)\\b(?:game|match)\\s*(?:#|no\\.?|number|num|id)\\s*:?\\s*#?\\s*([A-Za-z0-9][\\w-]*)")
            .find(all)?.groupValues?.get(1) ?: ""
        val ageGroup = labels["division"]?.let { normaliseAge(it) ?: it } ?: findAge(all) ?: ""
        val league = labels["league"] ?: lines.firstOrNull { l ->
            Regex("(?i)\\b(league|association|conference|cup|tournament|showcase|premier|federation)\\b").containsMatchIn(l.text) &&
                !isTeamTitle(l.text) && l.text.lowercase() !in HEADERS && !l.text.contains(" vs ", true)
        }?.text ?: ""

        // ── Teams ──
        var home = labels["home"] ?: ""
        var away = labels["away"] ?: ""
        if (home.isBlank() || away.isBlank()) {
            titleTeams(lines, dateLine)?.let { (h, a) -> if (home.isBlank()) home = h; if (away.isBlank()) away = a }
        }
        if ((home.isBlank() || away.isBlank()) && dateLine != null && dateLine.hasBox && w > 0) {
            columnTeams(lines, dateLine, w)?.let { (h, a) -> if (home.isBlank()) home = h; if (away.isBlank()) away = a }
        }

        // ── Location ──
        val addressRe = Regex("(?i)(\\b\\d{1,6}\\s+[\\w .'-]+?\\b(road|rd|street|st|avenue|ave|lane|ln|drive|dr|boulevard|blvd|way|pike|highway|hwy|court|ct|place|pl|parkway|pkwy|route|rt)\\b.*)|(\\b[A-Z]{2}\\s+\\d{5}\\b)|(\\b(new jersey|pennsylvania|new york|delaware|maryland|connecticut)\\s+\\d{5}\\b)")
        var address = lines.firstOrNull { addressRe.containsMatchIn(it.text) && !it.text.contains("game", true) }?.text ?: ""
        var venue = labels["venue"] ?: ""
        if (venue.isBlank()) {
            val locIdx = lines.indexOfFirst { it.text.equals("location", true) }
            if (locIdx >= 0) {
                venue = lines.drop(locIdx + 1).takeWhile { it.text.lowercase() !in HEADERS }
                    .filter { !it.text.startsWith("distance", true) && it.text != address }
                    .joinToString(" ") { it.text }
            }
        }
        if (venue.isBlank()) {
            venue = lines.firstOrNull { l ->
                Regex("(?i)\\b(field|fields|park|complex|stadium|school|sportsplex|turf|facility|center|centre|f\\d)\\b").containsMatchIn(l.text) &&
                    !isTeamTitle(l.text) && l != dateLine && roleOf(l.text) == null && l.text != address &&
                    !l.text.contains(" vs ", true) && !l.text.startsWith("vs ", true)
            }?.text ?: ""
        }
        if (venue.contains(address) && address.isNotBlank()) address = ""

        // ── Officials ──
        val officials = officials(lines, labels)

        return GameConfirmation(
            homeTeam = tidyTeam(home), awayTeam = tidyTeam(away), gameNumber = gameNumber.trim(),
            date = date, kickoff = kickoff, endTime = endTime, ageGroup = ageGroup.trim(), league = league.trim(),
            venue = venue.trim(), address = address.trim(),
            referee = officials["referee"] ?: "", ar1 = officials["ar1"] ?: "", ar2 = officials["ar2"] ?: "",
            fourth = officials["fourth"] ?: ""
        )
    }

    // ── Cleaning ──

    /** Strips icon artefacts (©, @, oO, "FE]") from the start, and status words from the end. */
    private fun clean(s: String): String {
        var t = s.trim().replace('•', '·').replace(Regex("\\s+"), " ")
        repeat(2) { t = t.replace(Regex("^(?:[^\\w(\\[]+|oO|\\w{1,2}[\\]\\)]|=\\.)\\s+"), "") }
        t = t.replace(STATUS, "")
        return t.trim()
    }

    private fun keep(t: String): Boolean {
        if (t.length < 2) return false
        if (Regex("^[\\W_]+$").matches(t) || t == "oO") return false
        // phone status-bar clock ("9:40", "9:39 T ¥")
        if (Regex("^\\d{1,2}:\\d{2}(?!\\s*[AaPp]\\.?[Mm])\\b.{0,8}$").matches(t) && !t.contains(Regex("[A-Za-z]{3}"))) return false
        return true
    }

    /** "Label: value" lines (emails and some apps). */
    private fun labelled(lines: List<OcrLine>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val map = listOf(
            "home" to "home(?:\\s*team)?", "away" to "(?:away|visitor|visiting)(?:\\s*team)?",
            "game" to "(?:game|match)\\s*(?:#|no\\.?|number|id)", "date" to "(?:game\\s*)?date",
            "time" to "(?:kick-?off|start|game)?\\s*time", "league" to "(?:league|association|association/league|competition)",
            "division" to "(?:division|age\\s*group|division/age\\s*group|age|level)",
            "venue" to "(?:field|location|venue|site|facility)"
        )
        for (l in lines) {
            val m = Regex("^([A-Za-z#/ .]{2,30}?)\\s*[:]\\s*(.+)$").find(l.text) ?: continue
            val key = m.groupValues[1].trim()
            map.firstOrNull { (_, re) -> Regex("(?i)^$re$").matches(key) }?.let { (k, _) -> out.putIfAbsent(k, m.groupValues[2].trim()) }
        }
        return out
    }

    // ── Dates & times ──

    private fun findDate(s: String, today: LocalDate): LocalDate? {
        Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").find(s)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})\\b").find(s)?.let { m ->
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            return safeDate(y, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        val monthRe = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?")
        monthRe.find(s)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            val day = m.groupValues[2].toInt()
            m.groupValues[3].takeIf { it.isNotEmpty() }?.let { return safeDate(it.toInt(), month, day) }
            // No year: use the weekday if given, otherwise the nearest sensible year
            val weekday = Regex("(?i)\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b").find(s)?.groupValues?.get(1)?.lowercase()?.let {
                listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").indexOf(it.take(3)) + 1
            }
            val candidates = listOf(today.year, today.year + 1, today.year - 1).mapNotNull { safeDate(it, month, day) }
            if (weekday != null) candidates.firstOrNull { it.dayOfWeek == DayOfWeek.of(weekday) }?.let { return it }
            return candidates.firstOrNull { !it.isBefore(today.minusDays(120)) } ?: candidates.firstOrNull()
        }
        return null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    private fun findTimes(lines: List<OcrLine>, labelTime: String?): Pair<String, String> {
        val timeRe = Regex("\\b(\\d{1,2}:\\d{2})\\s*([AaPp]\\.?[Mm]\\.?)?")
        val source = labelTime ?: lines.firstOrNull { timeRe.containsMatchIn(it.text) }?.let { line ->
            // An "AM"/"PM" wrapped onto the next line belongs to the end time
            val wrapped = lines.firstOrNull { o ->
                o != line && Regex("^[AaPp]\\.?[Mm]\\.?$").matches(o.text) &&
                    (!line.hasBox || (o.top in line.top..(line.bottom + 80) && o.centerX in (line.left - 40)..(line.right + 40)))
            }
            line.text + (wrapped?.let { " ${it.text}" } ?: "")
        } ?: return "" to ""
        val ms = timeRe.findAll(source).toList()
        fun fmt(m: MatchResult, fallback: String) =
            m.groupValues[1] + " " + (m.groupValues[2].ifBlank { fallback }.uppercase().replace(".", "")).trim()
        val endAmPm = ms.getOrNull(1)?.groupValues?.get(2) ?: ""
        val start = ms.firstOrNull()?.let { fmt(it, endAmPm) }?.trim() ?: ""
        val end = ms.getOrNull(1)?.let { fmt(it, ms[0].groupValues[2]) }?.trim() ?: ""
        return start to end
    }

    private fun normaliseAge(s: String): String? {
        val m = Regex("(?i)\\bU\\s*-?\\s*(\\d{1,2})\\s*([BG])?(?=\\b|\\s|$)").find(s) ?: return null
        val sex = when (m.groupValues[2].uppercase()) { "G" -> " Girls"; "B" -> " Boys"; else -> "" }
        val rest = Regex("(?i)\\b(boys|girls)\\b").find(s)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }
        return "U${m.groupValues[1]}" + (if (sex.isNotEmpty()) sex else rest?.let { " $it" } ?: "")
    }

    private fun findAge(all: String): String? = normaliseAge(all)

    // ── Teams ──

    private fun isTeamTitle(t: String) = Regex("(?i)\\[(home|away)\\]").containsMatchIn(t)

    /** "Team A [HOME] (Club) vs Team B [AWAY]" or "Away @ Home" in the title lines (before the date). */
    private fun titleTeams(lines: List<OcrLine>, dateLine: OcrLine?): Pair<String, String>? {
        val before = if (dateLine != null) lines.takeWhile { it != dateLine } else lines.take(8)
        val title = before.filter { it.text.lowercase() !in HEADERS }.joinToString(" ") { it.text }
        Regex("(?i)^(.*?)\\s+(?:vs\\.?|versus|v\\.)\\s+(.*)$").find(title)?.let { m ->
            var a = m.groupValues[1]; var b = m.groupValues[2]
            if (Regex("(?i)\\[away\\]").containsMatchIn(a) && Regex("(?i)\\[home\\]").containsMatchIn(b)) { val t = a; a = b; b = t }
            return cutAfterTeam(a) to cutAfterTeam(b)
        }
        Regex("^(.*?[A-Za-z]{3}.*?)\\s+@\\s+(.*?[A-Za-z]{3}.*)$").find(title)?.let { m ->
            return cutAfterTeam(m.groupValues[2]) to cutAfterTeam(m.groupValues[1])   // "Away @ Home"
        }
        return null
    }

    /** Keeps text up to the [HOME]/[AWAY] marker and its (Club) — drops anything after. */
    private fun cutAfterTeam(s: String): String {
        val m = Regex("(?i)^(.*?\\[(?:home|away)\\](?:\\s*\\([^)]*\\))?)").find(s)
        return (m?.groupValues?.get(1) ?: s).trim()
    }

    /** Teams shown left and right of the date block (home on the left). */
    private fun columnTeams(lines: List<OcrLine>, dateLine: OcrLine, width: Int): Pair<String, String>? {
        val ageLine = lines.lastOrNull { it.bottom <= dateLine.top && findAge(it.text) != null }
        val top = ageLine?.bottom ?: (dateLine.top - 150)
        val headerBelow = lines.firstOrNull { it.top > dateLine.bottom && (it.text.lowercase() in HEADERS) }
        val bottom = headerBelow?.top ?: (dateLine.bottom + 300)
        val band = lines.filter { it.top >= top && it.bottom <= bottom && it.text.length > 2 }
        val left = band.filter { it.centerX < width * 0.34 }.joinToString(" ") { it.text }
        val right = band.filter { it.centerX > width * 0.66 }.joinToString(" ") { it.text }
        return if (left.isNotBlank() || right.isNotBlank()) left to right else null
    }

    /** Removes [HOME]/[AWAY] and (Club) and a repeated club prefix: "PA Classics PA Classics NAL…" → "PA Classics NAL…". */
    private fun tidyTeam(raw: String): String {
        var s = raw.trim()
        val club = Regex("\\(([^)]*)\\)").find(s)?.groupValues?.get(1)?.trim()
        s = s.replace(Regex("(?i)\\[(home|away)\\]"), " ").replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (club != null && club.isNotBlank() && s.startsWith(club, true) && s.length > club.length + 2) s = s.drop(club.length).trim()
        val words = s.split(" ")
        for (k in words.size / 2 downTo 1) {
            if (words.take(k).map { it.lowercase() } == words.drop(k).take(k).map { it.lowercase() }) {
                s = words.drop(k).joinToString(" "); break
            }
        }
        return s
    }

    // ── Officials ──

    /** Which slot a role label means, or null. */
    private fun roleOf(t: String): String? {
        val s = t.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("^(center |centre )?referee$|^ref$|^cr$|^center$|^centre$").matches(s) -> "referee"
            Regex("^(ar|asst ref(eree)?|assistant ref(eree)?|ar ?)\\s?1$").matches(s) -> "ar1"
            Regex("^(ar|asst ref(eree)?|assistant ref(eree)?|ar ?)\\s?2$").matches(s) -> "ar2"
            Regex("^(asst ref(eree)?|assistant ref(eree)?|ar|assistant|linesman|lines)$").matches(s) -> "ar"
            Regex("^(4th|fourth)( official)?$").matches(s) -> "fourth"
            Regex("^assi\\w{0,3}nor$|^mentor$|^assessor$|^observer$|^coordinator$").matches(s) -> "skip"
            else -> null
        }
    }

    private fun looksLikeName(t: String) =
        Regex("^[A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]*\\.?){1,3}$").matches(t) && roleOf(t) == null &&
            t.lowercase() !in HEADERS && !t.contains(Regex("\\d"))

    private fun officials(lines: List<OcrLine>, labels: Map<String, String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val genericAr = mutableListOf<String>()
        fun put(slot: String, name: String) {
            when (slot) {
                "ar" -> genericAr.add(name)
                "skip" -> {}
                else -> out.putIfAbsent(slot, name)
            }
        }
        lines.forEachIndexed { i, l ->
            // "Referee: John Smith"
            Regex("^([A-Za-z0-9 .]{2,25}?)\\s*[:\\-–]\\s*([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]*\\.?){1,3})$").find(l.text)?.let { m ->
                roleOf(m.groupValues[1])?.let { slot -> put(slot, m.groupValues[2].trim()); return@forEachIndexed }
            }
            val slot = roleOf(l.text) ?: return@forEachIndexed
            // The name is the line just above (same column when positions are known)
            val name = if (l.hasBox) {
                lines.filter { o -> o.bottom <= l.top + 5 && o.top >= l.top - 140 &&
                        o.left < l.right + 120 && o.right > l.left - 120 && looksLikeName(o.text) }
                    .maxByOrNull { it.bottom }?.text
            } else lines.getOrNull(i - 1)?.text?.takeIf { looksLikeName(it) }
            if (name != null) put(slot, name)
        }
        genericAr.forEach { n -> if (out["ar1"] == null) out["ar1"] = n else if (out["ar2"] == null) out["ar2"] = n }
        return out
    }
}
