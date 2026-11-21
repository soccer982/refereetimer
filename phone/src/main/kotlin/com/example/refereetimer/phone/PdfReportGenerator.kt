package com.example.refereetimer.phone

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.models.TeamTag
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.MatchConfig
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import com.example.refereetimer.shared.models.MatchEvent.KftmResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfReportGenerator(
    private val context: Context,
    private val config: MatchConfig?,
    private val events: List<MatchEvent>,
    private val gpsTrack: List<GpsReferenceService.GpsTrackPoint>,
    private val homeName: String,
    private val awayName: String,
    private val totalStoppageMillis: Long,
    private val regularPeriods: Int = 2,
    private val matchSummary: String = "",
    private val fieldLengthM: Double? = null,   // from the watch's field calibration
    private val fieldWidthM: Double? = null,
    private val details: MatchDetails = MatchDetails(),      // officials, game #, venue
    private val clock: MatchClock? = null,
    private val subRulesText: String = ""
) {
    private val plot = FieldPlot(fieldLengthM ?: 105.0, fieldWidthM ?: 68.0)

    // ── US LETTER, PORTRAIT: 8.5 × 11 in = 612 × 792 points, ½-inch margins ──
    private val PAGE_W = 612
    private val PAGE_H = 792
    private val MARGIN = 36f
    private val LEFT = MARGIN
    private val RIGHT = PAGE_W - MARGIN
    private val CONTENT_W = RIGHT - LEFT
    private val BOTTOM = PAGE_H - 54f          // content stops above the footer


    /** An event is "placed" when its GPS tag has been mapped onto the calibrated field. */
    private fun isPlaced(e: MatchEvent) = e.gpsLocation?.let { it.fieldX != 0f || it.fieldY != 0f } == true

    private val CLR_BG = Color.WHITE
    private val CLR_HEADER = Color.parseColor("#1B5E20")
    private val CLR_TEXT = Color.parseColor("#212121")
    private val CLR_SUBTEXT = Color.parseColor("#757575")
    private val CLR_DIVIDER = Color.parseColor("#E0E0E0")
    private val CLR_HOME = Color.parseColor("#1565C0")
    private val CLR_AWAY = Color.parseColor("#B71C1C")
    private val CLR_YELLOW = Color.parseColor("#F9A825")
    private val CLR_RED = Color.parseColor("#C62828")

    fun generate(): File {
        val doc = PdfDocument()
        val totalPeriods = config?.regularPeriodsCount ?: regularPeriods
        var pageNum = 1

        pageNum = addCoverPage(doc, pageNum)
        pageNum = addSummaryPage(doc, pageNum)

        // Per-period field pages when the field was calibrated (heatmap and/or placed pins).
        if (gpsTrack.isNotEmpty() || events.any { isPlaced(it) }) {
            for (period in 1..totalPeriods) {
                pageNum = addFieldDiagramPage(doc, pageNum, period, gpsTrack.filter { it.period == period }, events.filter { it.period == period })
            }
        }

        // Full match detail (goals, cards, injuries, substitutions): last page of our own report
        pageNum = OfficialReports(doc, events, details, clock, homeName, awayName, fieldLengthM, fieldWidthM, subRulesText)
            .writeGameReport(pageNum)

        // The official US Soccer forms, filled in
        val ussf = UssfForms(context, doc, events, details, clock, homeName, awayName)
        try {
            pageNum = ussf.writeRefereeReport(pageNum)
            pageNum = ussf.writeSupplemental(pageNum, fieldLengthM, fieldWidthM)   // send-offs / serious injuries only
            pageNum = ussf.writeFourthOfficial(pageNum)
        } finally {
            ussf.release()
        }

        // The LAST page is always the full-match heatmap
        addFullMatchDiagramPage(doc, pageNum, totalPeriods)

        val dateStr  = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        // FIX: team names like "13/14F" contained "/", which Android read as a folder → "ENOENT".
        // Keep only safe characters, and make sure the folder exists.
        fun safe(t: String) = t.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').take(40).ifBlank { "team" }
        val fileName = "match_report_${safe(homeName)}_vs_${safe(awayName)}_$dateStr.pdf"
        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, "Documents"))
            .apply { mkdirs() }
        val file = File(dir, fileName)

        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun startPage(doc: PdfDocument, pageNum: Int): PdfDocument.Page {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        page.canvas.drawColor(CLR_BG)
        return page
    }

    // ══════════════ COVER ══════════════

    private fun addCoverPage(doc: PdfDocument, pageNum: Int): Int {
        val page = startPage(doc, pageNum)
        val canvas = page.canvas
        val cx = PAGE_W / 2f

        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 110f, Paint().apply { color = CLR_HEADER })
        drawText(canvas, "MATCH REPORT", cx, 58f, 32f, Color.WHITE, true, Paint.Align.CENTER)
        drawText(canvas, "Official Match Record", cx, 88f, 13f, Color.parseColor("#A5D6A7"), false, Paint.Align.CENTER)

        // ── Scores (regulation, overtime, kicks from the mark) ──
        val regularPeriods = config?.regularPeriodsCount ?: this.regularPeriods
        var homeReg = 0; var awayReg = 0
        var homeOt = 0;  var awayOt = 0
        var homeKftm = 0; var awayKftm = 0
        var totalKicks = 0
        events.forEach { e ->
            if (e.type == EventType.GOAL) {
                val isHomeScorer = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
                if (e.period <= regularPeriods) { if (isHomeScorer) homeReg++ else awayReg++ }
                else { if (isHomeScorer) homeOt++ else awayOt++ }
            } else if (e.type == EventType.KFTM_KICK) {
                totalKicks++
                if (e.kftmResult == KftmResult.SCORED) { if (e.team.isHome) homeKftm++ else awayKftm++ }
            }
        }
        val homeTotal = homeReg + homeOt; val awayTotal = awayReg + awayOt
        val wentToOt = homeOt > 0 || awayOt > 0 || events.any { it.period > regularPeriods }
        val wentToKftm = totalKicks > 0
        var homeScoreStr = homeTotal.toString(); var awayScoreStr = awayTotal.toString()
        if (wentToOt && !wentToKftm && homeTotal > awayTotal) homeScoreStr += "*"
        if (wentToOt && !wentToKftm && awayTotal > homeTotal) awayScoreStr += "*"
        if (wentToKftm && homeKftm > awayKftm) homeScoreStr += "*"
        if (wentToKftm && awayKftm > homeKftm) awayScoreStr += "*"

        // Score card: one row per team, [H]/[A] name on the left (shortened if long), score on the right
        canvas.drawRoundRect(RectF(LEFT, 140f, RIGHT, 330f), 14f, 14f, Paint().apply { color = Color.parseColor("#F5F5F5") })
        val nameW = CONTENT_W - 140f
        drawText(canvas, fitText(TeamTag.label(homeName, true), 22f, nameW, true), LEFT + 22f, 205f, 22f, CLR_HOME, true)
        drawText(canvas, homeScoreStr, RIGHT - 24f, 210f, 44f, CLR_HOME, true, Paint.Align.RIGHT)
        canvas.drawLine(LEFT + 22f, 235f, RIGHT - 22f, 235f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        drawText(canvas, fitText(TeamTag.label(awayName, false), 22f, nameW, true), LEFT + 22f, 280f, 22f, CLR_AWAY, true)
        drawText(canvas, awayScoreStr, RIGHT - 24f, 285f, 44f, CLR_AWAY, true, Paint.Align.RIGHT)
        var extra = 316f
        val sub = listOfNotNull(
            if (wentToOt) "OT ($homeOt - $awayOt)" else null,
            if (wentToKftm) "KFTM ($homeKftm - $awayKftm) · $totalKicks kicks" else null
        )
        if (sub.isNotEmpty()) drawText(canvas, sub.joinToString("   ·   ") + "   (* = winner)", cx, extra, 11f, CLR_SUBTEXT, true, Paint.Align.CENTER)

        // ── Game details ──
        var y = 370f
        fun line(label: String, value: String) {
            if (value.isBlank()) return
            drawText(canvas, label, LEFT + 10f, y, 10f, CLR_SUBTEXT, true)
            // A line with your USSF ID is never cut: its font shrinks to fit instead (down to 8 pt)
            var size = 12f
            if (value.contains("USSF ID")) while (size > 8f && textWidth(value, size) > CONTENT_W - 130f) size -= 0.5f
            drawText(canvas, if (value.contains("USSF ID")) value else fitText(value, 12f, CONTENT_W - 130f), LEFT + 120f, y, size, CLR_TEXT)
            canvas.drawLine(LEFT, y + 8f, RIGHT, y + 8f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 0.7f })
            y += 24f
        }
        val date = details.gameDate.ifBlank { SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date()) }
        line("DATE", listOf(date, details.kickoff).filter { it.isNotBlank() }.joinToString("  ·  "))
        line("GAME #", details.gameNumber)
        line("COMPETITION", listOf(details.competition, details.division).filter { it.isNotBlank() }.joinToString("  ·  "))
        line("VENUE", details.venue)
        // Your USSF ID always goes with your name
        val me = details.yourName.trim()
        fun crew(n: String) = if (me.isNotBlank() && n.trim().equals(me, true) && details.refereeId.isNotBlank())
            "$n (USSF ID ${details.refereeId})" else n
        line("REFEREE", crew(details.referee))
        line("ASSISTANTS", listOf(details.ar1, details.ar2).filter { it.isNotBlank() }.joinToString("  ·  ") { crew(it) })
        line("4TH OFFICIAL", crew(details.fourth))
        val onCrew = listOf(details.referee, details.ar1, details.ar2, details.fourth).any { it.trim().equals(me, true) }
        if (me.isNotBlank() && !onCrew) line("REPORT BY", if (details.refereeId.isBlank()) me else "$me (USSF ID ${details.refereeId})")
        // "3 × 25 min · 5 min intervals" on one line, the times and field on the next
        val summaryParts = matchSummary.split("  ·  ").filter { it.isNotBlank() }
        line("FORMAT", summaryParts.firstOrNull() ?: "")
        line("MATCH TIME", summaryParts.drop(1).joinToString("  ·  "))

        // ── Stat boxes ──
        val fouls = events.count { it.type == EventType.FOUL }
        val yellow = events.count { it.type == EventType.CARD && it.cardType == CardType.YELLOW }
        val red = events.count { it.type == EventType.CARD && it.cardType == CardType.RED }
        val injuries = events.count { it.type == EventType.INJURY }
        val stopStr = String.format("%d:%02d", totalStoppageMillis / 60000, (totalStoppageMillis / 1000) % 60)
        val boxY = maxOf(y + 50f, 640f)
        val step = CONTENT_W / 5f
        listOf(
            Triple("FOULS", fouls.toString(), CLR_TEXT), Triple("YELLOW", yellow.toString(), CLR_YELLOW),
            Triple("RED", red.toString(), CLR_RED), Triple("INJURIES", injuries.toString(), Color.parseColor("#1E88E5")),
            Triple("STOPPAGE", stopStr, Color.parseColor("#1976D2"))
        ).forEachIndexed { i, (label, value, color) -> drawStatBox(canvas, label, value, LEFT + step * (i + 0.5f), boxY, color, step - 8f) }

        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    // ══════════════ SUMMARY (continues onto more pages as needed) ══════════════

    private fun addSummaryPage(doc: PdfDocument, firstPage: Int): Int {
        var pageNum = firstPage
        var page = startPage(doc, pageNum)
        var canvas = page.canvas
        var y = 40f

        fun newPage(header: String) {
            drawPageFooter(canvas, pageNum); doc.finishPage(page)
            pageNum++; page = startPage(doc, pageNum); canvas = page.canvas
            y = 40f
            y = drawSectionHeader(canvas, header, y)
        }
        /** Table that flows onto the next page, repeating its header there. */
        fun table(title: String, headers: List<String>, weights: List<Float>, rows: List<Pair<List<String>, Int>>) {
            if (rows.isEmpty()) return
            if (y > BOTTOM - 90f) newPage(title) else { y = drawSectionHeader(canvas, title, y) }   // starts here
            y = drawTableHeader(canvas, headers, weights, y)
            rows.forEach { (cells, accent) ->
                if (y > BOTTOM - 22f) { newPage("$title (continued)"); y = drawTableHeader(canvas, headers, weights, y) }
                y = drawTableRow(canvas, cells, weights, y, accent)
            }
            y += 14f
        }
        fun teamColor(e: MatchEvent) = if (e.team.isHome) CLR_HOME else CLR_AWAY

        y = drawSectionHeader(canvas, "MATCH SUMMARY", y)
        drawText(canvas, fitText("${TeamTag.label(homeName, true)}  vs  ${TeamTag.label(awayName, false)}", 12f, CONTENT_W), LEFT, y + 4f, 12f, CLR_TEXT, true)
        y += 22f

        val goalList = events.filter { it.type == EventType.GOAL }
        table("GOALS", listOf("MIN", "TEAM", "SCORER", "ASSIST", "TYPE"), listOf(0.09f, 0.31f, 0.24f, 0.22f, 0.14f),
            goalList.map { e ->
                listOf(e.minuteLabel(), e.team.label, "${e.player.tag} ${e.player.name}",
                    e.assistTag()?.let { "$it ${e.assistPlayer?.name}" } ?: "—",
                    e.goalTypeLabel()) to teamColor(e)
            })
        // Key for own-goal assists (credited to the opposing team) and the type column
        if (goalList.any { it.isOwnGoal && it.assistPlayer != null }) {
            drawText(canvas, MatchEvent.OG_ASSIST_KEY, LEFT + 4f, y - 4f, 8.5f, CLR_SUBTEXT); y += 10f
        }
        if (goalList.any { it.isOwnGoal || it.isPK }) {
            drawText(canvas, "TEAM = team of the player who scored. OG = own goal (counts for the opponent) · PK = penalty kick", LEFT + 4f, y - 4f, 8.5f, CLR_SUBTEXT); y += 14f
        }

        table("FOULS", listOf("MIN", "TEAM", "PLAYER", "FREE KICK", "CARD"), listOf(0.09f, 0.31f, 0.28f, 0.16f, 0.16f),
            events.filter { it.type == EventType.FOUL }.map { e ->
                val card = events.find { it.type == EventType.CARD && it.player == e.player && it.minute == e.minute }
                listOf(e.minuteLabel(), e.team.label, "${e.player.tag} ${e.player.name}",
                    "${e.foulType?.name ?: ""}${if (e.isPK) " → PK" else ""}",
                    when (card?.cardType) { CardType.YELLOW -> "Yellow"; CardType.RED -> "Red"; null -> "—" }) to teamColor(e)
            })

        val cards = events.filter { it.type == EventType.CARD }
        table("CAUTIONS & SEND-OFFS", listOf("MIN", "TEAM", "PLAYER", "CARD", "CODE", "GPS TAG"),
            listOf(0.08f, 0.26f, 0.22f, 0.09f, 0.10f, 0.25f),
            cards.map { e ->
                listOf(e.minuteLabel(), e.team.label, "${e.player.tag} ${e.player.name}",
                    if (e.cardType == CardType.RED) "Red" else "Yellow",
                    MisconductCodes.official(e.misconductCode) ?: "—",
                    e.gpsLocation?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "no fix") to teamColor(e)
            })
        // Legend: spell out each misconduct code used
        cards.mapNotNull { MisconductCodes.official(it.misconductCode) }.distinct().forEach { code ->
            if (y > BOTTOM - 14f) newPage("MISCONDUCT CODES (continued)")
            drawText(canvas, fitText("$code = ${MisconductCodes.describe(code)}", 9f, CONTENT_W), LEFT + 4f, y, 9f, CLR_SUBTEXT)
            y += 13f
        }

        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    // ══════════════ FIELD PAGES: heatmap on the top half, incidents on the bottom half ══════════════

    /** Fouls, cautions and send-offs (and injuries) shown on the incident diagram. */
    private fun isIncident(e: MatchEvent) = e.type == EventType.FOUL || e.type == EventType.CARD || e.type == EventType.INJURY

    private fun addFieldDiagramPage(doc: PdfDocument, pageNum: Int, period: Int, track: List<GpsReferenceService.GpsTrackPoint>, periodEvents: List<MatchEvent>): Int =
        addTwoFieldPage(doc, pageNum, "PERIOD $period", track, periodEvents.filter { isIncident(it) }, listOf(period))

    private fun addFullMatchDiagramPage(doc: PdfDocument, pageNum: Int, totalPeriods: Int): Int =
        addTwoFieldPage(doc, pageNum, "FULL MATCH", gpsTrack, events.filter { isIncident(it) }, (1..totalPeriods).toList())

    private fun addTwoFieldPage(
        doc: PdfDocument, pageNum: Int, title: String,
        track: List<GpsReferenceService.GpsTrackPoint>, incidents: List<MatchEvent>, periods: List<Int>
    ): Int {
        val page = startPage(doc, pageNum)
        val canvas = page.canvas
        drawText(canvas, "$title: FIELD DIAGRAMS", PAGE_W / 2f, 34f, 16f, CLR_TEXT, true, Paint.Align.CENTER)
        drawText(canvas, fitText("${TeamTag.label(homeName, true)}  vs  ${TeamTag.label(awayName, false)}", 10f, CONTENT_W),
            PAGE_W / 2f, 52f, 10f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        val orientation = if (fieldLengthM != null) "${fieldLengthM.toInt()} × ${fieldWidthM?.toInt() ?: 0} m · left goal line = corners 1–2"
            else "Standard field shape (field not calibrated)"

        // ── TOP HALF: referee heatmap ──
        drawText(canvas, "REFEREE MOVEMENT (HEATMAP)", LEFT, 80f, 10f, CLR_HEADER, true)
        drawText(canvas, orientation, RIGHT, 80f, 7.5f, CLR_SUBTEXT, false, Paint.Align.RIGHT)
        val top = plot.fit(LEFT, 90f, CONTENT_W, 282f)
        plot.drawGrass(canvas, top)
        periods.forEach { p -> plot.drawHeatmap(canvas, top, track.filter { it.period == p }, FieldPlot.periodColor(p)) }
        plot.drawLines(canvas, top)
        var y = top.bottom + 16f
        if (track.isEmpty()) {
            drawText(canvas, "No movement recorded: do FIELD SETUP on the watch before kickoff", top.centerX(), top.centerY(), 9f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        } else {
            // Period colour legend + how to read it
            var x = LEFT
            periods.forEach { p ->
                canvas.drawRect(x, y - 8f, x + 10f, y + 1f, Paint().apply { color = FieldPlot.periodColor(p) })
                drawText(canvas, "Period $p", x + 14f, y, 8.5f, CLR_TEXT); x += 70f
            }
            drawText(canvas, "Darker = more time spent there · ${track.size} GPS points", RIGHT, y, 8.5f, CLR_SUBTEXT, false, Paint.Align.RIGHT)
        }

        // ── BOTTOM HALF: fouls, cautions, send-offs (and injuries) ──
        val bottomTop = 412f
        canvas.drawLine(LEFT, bottomTop - 14f, RIGHT, bottomTop - 14f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        drawText(canvas, "FOULS, CAUTIONS, SEND-OFFS & INJURIES", LEFT, bottomTop, 10f, CLR_HEADER, true)
        drawText(canvas, "${incidents.count { it.type == EventType.FOUL }} fouls · " +
            "${incidents.count { it.type == EventType.CARD && it.cardType == CardType.YELLOW }} YC · " +
            "${incidents.count { it.type == EventType.CARD && it.cardType == CardType.RED }} RC · " +
            "${incidents.count { it.type == EventType.INJURY }} injuries", RIGHT, bottomTop, 7.5f, CLR_SUBTEXT, false, Paint.Align.RIGHT)
        val bottom = plot.fit(LEFT, bottomTop + 10f, CONTENT_W, 262f)
        plot.drawField(canvas, bottom)
        // Whole-match card list so a 2nd caution gets its "*" and red slash even across periods
        val ids = incidents.map { it.id }.toSet()
        val markers = EventMarkers.build(events).filter { it.event.id in ids }
        val notPlaced = plot.drawMarkers(canvas, bottom, markers)
        if (markers.isNotEmpty() && notPlaced.size == markers.size) {
            drawText(canvas, "No field positions recorded for these incidents", bottom.centerX(), bottom.centerY(), 9f, CLR_RED, false, Paint.Align.CENTER)
        } else if (markers.isEmpty()) {
            drawText(canvas, "No fouls, cards or injuries", bottom.centerX(), bottom.centerY(), 9f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        }
        y = bottom.bottom + 10f
        y += EventMarkers.drawKey(canvas, LEFT, y, CONTENT_W, 4.2f, Color.parseColor("#333333"),
            markers.takeIf { it.isNotEmpty() }?.let { EventMarkers.kindsIn(it) }) + 9f
        if (notPlaced.isNotEmpty() && notPlaced.size < markers.size && y < BOTTOM) {
            drawText(canvas, fitText("Not on the diagram (no field position): " + notPlaced.joinToString("; ") {
                "${it.minuteLabel()} ${it.team.label} ${it.player.tag}" }, 8f, CONTENT_W), LEFT, y, 8f, CLR_SUBTEXT)
        }

        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    // ══════════════ Drawing helpers ══════════════

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        canvas.drawText(text, x, y, Paint().apply { this.color = color; textSize = size; isAntiAlias = true; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; textAlign = align })
    }

    private fun drawSectionHeader(canvas: Canvas, title: String, y: Float): Float {
        canvas.drawRect(LEFT, y, RIGHT, y + 24f, Paint().apply { color = CLR_HEADER })
        drawText(canvas, title, LEFT + 8f, y + 17f, 12f, Color.WHITE, true)
        return y + 32f
    }

    /** Column x positions from width fractions. */
    private fun columns(weights: List<Float>): List<Pair<Float, Float>> {
        val total = weights.sum()
        var x = LEFT
        return weights.map { w -> val width = CONTENT_W * w / total; (x to width).also { x += width } }
    }

    private fun drawTableHeader(canvas: Canvas, headers: List<String>, weights: List<Float>, y: Float): Float {
        canvas.drawRect(LEFT, y, RIGHT, y + 20f, Paint().apply { color = Color.parseColor("#EEEEEE") })
        columns(weights).forEachIndexed { i, (x, w) -> drawText(canvas, fitText(headers[i], 9f, w - 6f, true), x + 3f, y + 14f, 9f, CLR_SUBTEXT, true) }
        return y + 24f
    }

    private fun drawTableRow(canvas: Canvas, cells: List<String>, weights: List<Float>, y: Float, accentColor: Int = CLR_TEXT): Float {
        // Long text (e.g. team names) is cut to its column with "…" instead of running into the next one
        columns(weights).forEachIndexed { i, (x, w) ->
            drawText(canvas, fitText(cells[i], 9.5f, w - 6f), x + 3f, y + 13f, 9.5f, if (i == 1) accentColor else CLR_TEXT)
        }
        canvas.drawLine(LEFT, y + 19f, RIGHT, y + 19f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 0.7f })
        return y + 21f
    }

    private fun textWidth(s: String, size: Float, bold: Boolean = false): Float =
        Paint().apply { textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }.measureText(s)

    /** Shortens text with "…" so it fits [width]; a leading [H]/[A] tag is kept. */
    private fun fitText(s: String, size: Float, width: Float, bold: Boolean = false): String {
        if (textWidth(s, size, bold) <= width) return s
        var cut = s
        while (cut.length > 4 && textWidth("$cut…", size, bold) > width) cut = cut.dropLast(1)
        return "${cut.trimEnd()}…"
    }

    private fun drawStatBox(canvas: Canvas, label: String, value: String, cx: Float, cy: Float, color: Int, width: Float) {
        val half = width / 2f
        canvas.drawRoundRect(RectF(cx - half, cy - 30f, cx + half, cy + 30f), 8f, 8f, Paint().apply { this.color = Color.parseColor("#F5F5F5") })
        drawText(canvas, value, cx, cy + 6f, 24f, color, true, Paint.Align.CENTER)
        drawText(canvas, label, cx, cy + 24f, 8.5f, CLR_SUBTEXT, false, Paint.Align.CENTER)
    }

    private fun drawPageFooter(canvas: Canvas, pageNum: Int) {
        canvas.drawLine(LEFT, PAGE_H - 36f, RIGHT, PAGE_H - 36f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        drawText(canvas, "Referee Timer Match Report  •  Page $pageNum", PAGE_W / 2f, PAGE_H - 22f, 8.5f, CLR_SUBTEXT, false, Paint.Align.CENTER)
    }
}
