package com.example.refereetimer.phone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.BodyParts
import com.example.refereetimer.shared.models.TeamTag
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEW: the official referee reports, added after the match report.
 *  1. GAME REPORT: game info, officials, score, goals, cautions, send-offs, injuries.
 *  2. SUPPLEMENTAL REPORT: one report covering every send-off and every serious injury,
 *     with the narrative entered on the phone. Only added when there is at least one.
 *
 * Laid out in the standard US Soccer referee report structure on US Letter portrait pages.
 * Long narratives flow onto extra pages automatically.
 */
class OfficialReports(
    private val doc: PdfDocument,
    private val events: List<MatchEvent>,
    private val details: MatchDetails,
    private val clock: MatchClock?,
    private val homeName: String,
    private val awayName: String,
    private val fieldLengthM: Double?,
    private val fieldWidthM: Double?,
    private val subRulesText: String = ""
) {
    private val W = 612f; private val H = 792f; private val L = 40f; private val R = W - 40f
    private val BOTTOM = H - 60f

    private val ink = Color.parseColor("#111111"); private val grey = Color.parseColor("#666666")
    private val rule = Color.parseColor("#BDBDBD"); private val band = Color.parseColor("#0D2C54")
    private val tint = Color.parseColor("#EEF2F7")

    private val sorted = events.sortedBy { it.timestamp }
    private val goals = sorted.filter { it.type == EventType.GOAL }
    private val cautions = sorted.filter { it.type == EventType.CARD && it.cardType == CardType.YELLOW }
    private val sendOffs = sorted.filter { it.type == EventType.CARD && it.cardType == CardType.RED }
    private val injuries = sorted.filter { it.type == EventType.INJURY }
    private val seriousInjuries = injuries.filter { it.seriousInjury }
    private val subs = sorted.filter { it.type == EventType.SUBSTITUTION }

    val needsSupplemental: Boolean get() = sendOffs.isNotEmpty() || seriousInjuries.isNotEmpty()

    private val day = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
    private val tod = SimpleDateFormat("h:mm a", Locale.US)

    // ── Page handling ──
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNum = 0
    private var formTitle = ""

    private fun newPage() {
        page?.let { finishPage(it) }
        pageNum++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNum).create())
        page = p; canvas = p.canvas
        canvas.drawColor(Color.WHITE)
        // Header band
        canvas.drawRect(0f, 0f, W, 58f, fill(band))
        // "MATCH DETAIL" is locked on the left; the match title gets only the width left over
        // (shortened with "…" if it doesn't fit), so the two can never overlap.
        text(formTitle, L, 36f, 17f, Color.WHITE, bold = true)
        val titleSpace = R - (L + paint(17f, Color.WHITE, true).measureText(formTitle) + 24f)
        // Home and away each get their own line (and the full leftover width), then the date
        text(fit(TeamTag.label(homeName, true), 11f, titleSpace, true), R, 20f, 11f,
            Color.parseColor("#90CAF9"), bold = true, align = Paint.Align.RIGHT)
        text(fit(TeamTag.label(awayName, false), 11f, titleSpace, true), R, 35f, 11f,
            Color.parseColor("#EF9A9A"), bold = true, align = Paint.Align.RIGHT)
        text(fit(gameDate(), 8.5f, titleSpace), R, 50f, 8.5f, Color.parseColor("#B0C4DE"), align = Paint.Align.RIGHT)
        y = 78f
    }

    private fun finishPage(p: PdfDocument.Page) {
        text("$formTitle · page $pageNum", W / 2, H - 25f, 8f, grey, align = Paint.Align.CENTER)
        doc.finishPage(p)
    }

    private fun finishAll() { page?.let { finishPage(it) }; page = null }

    /** Start a new page if fewer than [space] points remain. */
    private fun ensure(space: Float) { if (y + space > BOTTOM) newPage() }

    // ── Drawing helpers ──
    private fun fill(c: Int) = Paint().apply { color = c; style = Paint.Style.FILL }
    private fun paint(size: Float, c: Int, bold: Boolean = false) = Paint().apply {
        textSize = size; color = c; isAntiAlias = true
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    private fun text(s: String, x: Float, yy: Float, size: Float, c: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        canvas.drawText(s, x, yy, paint(size, c, bold).apply { textAlign = align })
    }

    /** Fits text into a width, cutting with "…" if needed. */
    private fun fit(s: String, size: Float, width: Float, bold: Boolean = false): String {
        val p = paint(size, ink, bold)
        if (p.measureText(s) <= width) return s
        var cut = s
        while (cut.isNotEmpty() && p.measureText("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** Word-wraps text to a width. */
    private fun wrap(s: String, size: Float, width: Float): List<String> {
        val p = paint(size, ink)
        val out = mutableListOf<String>()
        s.split("\n").forEach { para ->
            var line = ""
            para.split(" ").forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(trial) <= width) line = trial
                else { if (line.isNotEmpty()) out.add(line); line = word }
            }
            out.add(line)
        }
        return out
    }

    private fun section(title: String) {
        ensure(48f)
        y += 6f
        canvas.drawRect(L, y, R, y + 18f, fill(tint))
        text(title, L + 6f, y + 13f, 10f, band, bold = true)
        y += 26f
    }

    /** Label/value pairs in two columns. */
    private fun infoGrid(rows: List<Pair<Pair<String, String>, Pair<String, String>?>>) {
        val colW = (R - L) / 2
        rows.forEach { (left, right) ->
            ensure(18f)
            text(left.first, L, y, 8f, grey); text(fit(left.second.ifBlank { "—" }, 10f, colW - 90f), L + 88f, y, 10f)
            right?.let {
                text(it.first, L + colW, y, 8f, grey); text(fit(it.second.ifBlank { "—" }, 10f, colW - 90f), L + colW + 88f, y, 10f)
            }
            canvas.drawLine(L, y + 5f, R, y + 5f, Paint().apply { color = rule; strokeWidth = 0.5f })
            y += 17f
        }
    }

    /** A simple table; column widths are fractions of the page width. */
    private fun table(headers: List<String>, widths: List<Float>, rows: List<List<String>>, empty: String) {
        val total = R - L
        fun row(cells: List<String>, bold: Boolean, c: Int) {
            ensure(16f)
            var x = L
            cells.forEachIndexed { i, cell ->
                val w = widths[i] * total
                text(fit(cell, 9f, w - 4f, bold), x + 2f, y, 9f, c, bold)
                x += w
            }
            y += 14f
        }
        row(headers, true, grey)
        canvas.drawLine(L, y - 10f, R, y - 10f, Paint().apply { color = rule; strokeWidth = 0.7f })
        if (rows.isEmpty()) { text(empty, L + 2f, y, 9f, grey); y += 14f }
        rows.forEach { row(it, false, ink) }
        y += 4f
    }

    /**
     * One official: role, name, and, when it's you, your USSF ID right after your name.
     * The ID is never shortened: the line's font shrinks to make room (down to 7 pt), and only
     * the name would ever be cut. A grade, if entered, sits at the right edge.
     */
    private fun officialRow(role: String, name: String, grade: String, ussfId: String) {
        ensure(18f)
        text(role, L, y, 8f, grey)
        val x0 = L + 88f
        val gradeText = grade.takeIf { it.isNotBlank() }?.let { "Grade: $it" } ?: ""
        val gradeW = if (gradeText.isEmpty()) 0f else paint(8.5f, grey).measureText(gradeText) + 12f
        val idText = if (ussfId.isNotBlank()) "  ·  USSF ID $ussfId" else ""
        val avail = R - x0 - gradeW
        var size = 10f
        while (size > 7f && paint(size, ink, true).measureText(name + idText) > avail) size -= 0.5f
        val idW = paint(size, ink, true).measureText(idText)
        val shownName = fit(name.ifBlank { "—" }, size, avail - idW, bold = idText.isNotEmpty())
        text(shownName, x0, y, size, ink, bold = idText.isNotEmpty())
        if (idText.isNotEmpty()) {
            text(idText, x0 + paint(size, ink, true).measureText(shownName), y, size, band, bold = true)
        }
        if (gradeText.isNotEmpty()) text(gradeText, R, y, 8.5f, grey, align = Paint.Align.RIGHT)
        canvas.drawLine(L, y + 5f, R, y + 5f, Paint().apply { color = rule; strokeWidth = 0.5f })
        y += 17f
    }

    private fun signature() {
        ensure(60f)
        y += 22f
        val line = Paint().apply { color = ink; strokeWidth = 0.8f }
        canvas.drawLine(L, y, L + 260f, y, line); canvas.drawLine(L + 320f, y, R, y, line)
        text("Referee signature", L, y + 11f, 8f, grey)
        text("Date", L + 320f, y + 11f, 8f, grey)
        text(details.referee.ifBlank { "" }, L, y - 4f, 10f)
        text(day.format(Date()), L + 320f, y - 4f, 10f)
        y += 24f
    }

    // ── Shared formatting ──
    private fun gameDate(): String = day.format(Date(clock?.kickoffAt?.takeIf { it > 0 } ?: System.currentTimeMillis()))
    private fun kickoff(): String = clock?.kickoffAt?.takeIf { it > 0 }?.let { tod.format(Date(it)) } ?: "—"
    private fun player(e: MatchEvent) = if (e.player.name.isBlank() || e.player.name == "Unknown") "—" else e.player.name
    private fun whenText(e: MatchEvent) = "${e.minuteLabel()} (period ${e.period}) · ${tod.format(Date(e.timestamp))}"

    /** Where on the field, from the watch's calibration (or raw GPS if the field wasn't calibrated). */
    private fun location(e: MatchEvent): String {
        val g = e.gpsLocation ?: return "Not recorded"
        val gps = String.format(Locale.US, "GPS %.5f, %.5f", g.latitude, g.longitude)
        val placed = g.fieldX != 0f || g.fieldY != 0f
        if (!placed || fieldLengthM == null || fieldWidthM == null) return gps
        val fromGoal = (g.fieldX * fieldLengthM).coerceIn(0.0, fieldLengthM)
        val fromTouch = (g.fieldY * fieldWidthM).coerceIn(0.0, fieldWidthM)
        return String.format(Locale.US, "%.0f m from goal line (corners 1–2), %.0f m from touchline (corners 1–4) · %s",
            fromGoal, fromTouch, gps)
    }

    private fun scoreLine(): String {
        fun homeScored(e: MatchEvent) = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
        val h = goals.count { homeScored(it) }; val a = goals.size - h
        val periods = clock?.regularPeriods ?: (goals.maxOfOrNull { it.period } ?: 1)
        val byPeriod = (1..periods).joinToString("  ") { p ->
            val g = goals.filter { it.period == p }; val ph = g.count { homeScored(it) }
            "P$p ${ph}-${g.size - ph}"
        }
        return "${TeamTag.label(homeName, true)} $h – $a ${TeamTag.label(awayName, false)}   ($byPeriod)"
    }

    // ── 1. GAME REPORT ──

    fun writeGameReport(startPage: Int): Int {
        pageNum = startPage - 1
        formTitle = "MATCH DETAIL"
        newPage()
        val now = System.currentTimeMillis()

        section("GAME INFORMATION")
        infoGrid(listOf(
            ("Date" to gameDate()) to ("Kickoff" to kickoff()),
            ("Association" to details.competition) to ("Game #" to details.gameNumber),
            ("Division" to details.division) to null,
            ("Venue" to details.venue) to ("Format" to (clock?.formatDescription() ?: "")),
            ("Home team" to TeamTag.label(homeName, true)) to ("Away team" to TeamTag.label(awayName, false)),
            ("Total elapsed" to (clock?.let { MatchClock.format(it.elapsedMs(now)) } ?: "")) to
                ("Stoppage" to (clock?.let { MatchClock.format(it.totalStoppageMs(now)) } ?: ""))
        ))
        ensure(20f)
        text("FINAL SCORE", L, y, 8f, grey); text(fit(scoreLine(), 11f, R - L - 88f, true), L + 88f, y, 11f, bold = true)
        y += 18f

        section("MATCH OFFICIALS")
        // Your USSF ID goes with YOUR name, whichever role you had (or a "Report by" line)
        val me = details.yourName.trim()
        fun isMe(name: String) = me.isNotBlank() && name.trim().equals(me, ignoreCase = true)
        val crew = listOf(
            Triple("Referee", details.referee, details.refereeGrade),
            Triple("Asst. Ref. 1", details.ar1, details.ar1Grade),
            Triple("Asst. Ref. 2", details.ar2, details.ar2Grade),
            Triple("4th Official", details.fourth, details.fourthGrade)
        )
        crew.forEach { (role, name, grade) ->
            if (name.isNotBlank() || role == "Referee") officialRow(role, name, grade, if (isMe(name)) details.refereeId else "")
        }
        if (me.isNotBlank() && crew.none { isMe(it.second) }) officialRow("Report by", me, "", details.refereeId)

        section("GOALS")
        table(listOf("MIN", "TEAM", "#", "PLAYER", "ASSIST", "TYPE"), listOf(0.09f, 0.27f, 0.07f, 0.27f, 0.18f, 0.12f),
            goals.map { listOf(it.minuteLabel(), it.team.label, it.player.shortTag, player(it),
                it.assistTag() ?: "", it.goalTypeLabel()) },
            "No goals")
        if (goals.any { it.isOwnGoal && it.assistPlayer != null }) {
            ensure(12f); text(MatchEvent.OG_ASSIST_KEY, L, y, 8.5f, grey); y += 11f
        }
        if (goals.any { it.isOwnGoal || it.isPK }) {
            ensure(12f); text("OG = own goal (TEAM is the scorer's team; it counts for the opponent) · PK = penalty kick", L, y, 8.5f, grey); y += 13f
        }

        section("CAUTIONS (YELLOW CARDS)")
        table(listOf("MIN", "TEAM", "#", "PLAYER", "CODE", "OFFENCE"), listOf(0.09f, 0.20f, 0.06f, 0.22f, 0.08f, 0.35f),
            cautions.map { listOf(it.minuteLabel(), it.team.label, it.player.shortTag, player(it),
                it.misconductCode ?: "", MisconductCodes.describe(it.misconductCode)) },
            "No cautions")

        section("SEND-OFFS (RED CARDS)")
        table(listOf("MIN", "TEAM", "#", "PLAYER", "CODE", "OFFENCE"), listOf(0.09f, 0.20f, 0.06f, 0.22f, 0.08f, 0.35f),
            sendOffs.map { listOf(it.minuteLabel(), it.team.label, it.player.shortTag, player(it),
                it.misconductCode ?: "", MisconductCodes.describe(it.misconductCode)) },
            "No send-offs")
        if (sendOffs.isNotEmpty()) { ensure(14f); text("Details of each send-off are on the Supplemental Report.", L, y, 8.5f, grey); y += 14f }

        section("INJURIES")
        table(listOf("MIN", "TEAM", "#", "PLAYER", "BODY PART", "SERIOUS", "TIME"), listOf(0.09f, 0.20f, 0.06f, 0.24f, 0.15f, 0.12f, 0.14f),
            injuries.map { listOf(it.minuteLabel(), it.team.label, it.player.shortTag, player(it),
                BodyParts.label(it.bodyPart), if (it.seriousInjury) "YES" else "No", tod.format(Date(it.timestamp))) },
            "No injuries recorded")
        // Short notes for non-serious injuries (serious ones go on the supplemental)
        injuries.filter { !it.seriousInjury && !it.reportText.isNullOrBlank() }.forEach { e ->
            wrap("${e.minuteLabel()} ${e.player.tag} ${e.team.label}: ${e.reportText}", 8.5f, R - L).forEach { line ->
                ensure(12f); text(line, L, y, 8.5f, grey); y += 11f
            }
        }

        if (subs.isNotEmpty()) {
            section("SUBSTITUTIONS")
            if (subRulesText.isNotBlank()) { ensure(14f); text("Rules: $subRulesText", L, y, 8.5f, grey); y += 14f }
            listOf(true to TeamTag.label(homeName, true), false to TeamTag.label(awayName, false)).forEach { (isHome, name) ->
                val t = subs.filter { it.team.isHome == isHome }
                val moments = t.filter { it.momentCounted }.mapNotNull { it.groupId }.distinct().size
                ensure(14f); text("$name: ${t.size} substitution${if (t.size == 1) "" else "s"}, $moments moment${if (moments == 1) "" else "s"}", L, y, 9.5f, bold = true); y += 14f
            }
            table(listOf("MIN", "TEAM", "ON", "OFF", "NOTE"), listOf(0.09f, 0.22f, 0.22f, 0.22f, 0.25f),
                subs.map {
                    fun p(n: Int, nm: String?) = "#$n" + (nm?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { " $it" } ?: "")
                    listOf(it.minuteLabel(), it.team.label, p(it.player.jerseyNumber, it.player.name),
                        it.playerOff?.let { o -> p(o.jerseyNumber, o.name) } ?: "?",
                        it.flag?.let { f -> "⚠ $f" } ?: "")
                },
                "")
        }

        section("REMARKS")
        ensure(16f)
        val supp = if (needsSupplemental)
            "US Soccer Supplemental Report attached: YES (${sendOffs.size} send-off(s), ${seriousInjuries.size} serious injur${if (seriousInjuries.size == 1) "y" else "ies"})"
        else "US Soccer Supplemental Report attached: NO"
        text(supp, L, y, 10f, bold = needsSupplemental); y += 16f

        // Code legend for codes used
        val used = (cautions + sendOffs).mapNotNull { it.misconductCode }.distinct()
        if (used.isNotEmpty()) {
            ensure(14f); text("Codes: " + used.joinToString("  ·  ") { "$it = ${MisconductCodes.describe(it)}" }.let { fit(it, 8f, R - L) }, L, y, 8f, grey)
            y += 12f
        }
        signature()
        finishAll()
        return pageNum + 1
    }

    // ── 2. SUPPLEMENTAL REPORT ──

    fun writeSupplemental(startPage: Int): Int {
        if (!needsSupplemental) return startPage
        pageNum = startPage - 1
        formTitle = "SUPPLEMENTAL REPORT"
        newPage()

        section("GAME INFORMATION")
        infoGrid(listOf(
            ("Date" to gameDate()) to ("Game #" to details.gameNumber),
            ("Competition" to details.competition) to ("Venue" to details.venue),
            ("Home team" to TeamTag.label(homeName, true)) to ("Away team" to TeamTag.label(awayName, false)),
            ("Referee" to details.referee) to ("Final score" to scoreLine().substringBefore("   ("))
        ))

        val incidents = (sendOffs.map { it to "SEND-OFF" } + seriousInjuries.map { it to "SERIOUS INJURY" })
            .sortedBy { it.first.timestamp }

        incidents.forEachIndexed { i, (e, kind) ->
            section("INCIDENT ${i + 1} OF ${incidents.size}: $kind")
            val rows = mutableListOf(
                "Time" to whenText(e),
                "Team" to e.team.label,
                "Player" to "${e.player.tag}  ${player(e)}"
            )
            if (e.type == EventType.CARD) {
                rows.add("Offence" to "${e.misconductCode ?: ""}: ${MisconductCodes.describe(e.misconductCode)}")
                if (e.misconductCode in MisconductCodes.SECOND_CAUTION_CODES) {
                    val earlier = cautions.filter { it.team.isHome == e.team.isHome && it.player.jerseyNumber == e.player.jerseyNumber }
                    rows.add("Cautions" to earlier.joinToString("; ") { "${it.minuteLabel()} ${it.misconductCode}" })
                }
            } else {
                rows.add("Body part" to BodyParts.label(e.bodyPart))
            }
            rows.add("Location" to location(e))
            rows.forEach { (k, v) ->
                val lines = wrap(v, 10f, R - L - 80f)
                ensure(14f * lines.size + 2f)
                text(k, L, y, 8f, grey)
                lines.forEach { line -> text(line, L + 80f, y, 10f); y += 14f }
            }
            ensure(30f)
            y += 4f
            text(if (e.type == EventType.CARD) "Referee's account" else "Description", L, y, 8f, grey, bold = true)
            y += 14f
            val narrative = e.reportText?.takeIf { it.isNotBlank() } ?: "(No description entered)"
            wrap(narrative, 10.5f, R - L).forEach { line -> ensure(15f); text(line, L, y, 10.5f); y += 15f }
            y += 6f
        }

        signature()
        finishAll()
        return pageNum + 1
    }
}
