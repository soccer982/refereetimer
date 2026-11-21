package com.example.refereetimer.phone

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
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
    private val matchSummary: String = ""
) {
    private val renderer = FieldDiagramRenderer(context)
    private val PAGE_W = 842
    private val PAGE_H = 595
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

        // Field-coverage pages only when there is movement tracking data;
        // card GPS tags are listed with coordinates in the cards table instead.
        if (gpsTrack.isNotEmpty()) {
            for (period in 1..totalPeriods) {
                pageNum = addFieldDiagramPage(doc, pageNum, period, gpsTrack.filter { it.period == period }, events.filter { it.period == period })
            }
            addFullMatchDiagramPage(doc, pageNum, totalPeriods)
        }

        val dateStr  = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val fileName = "match_report_${homeName}_vs_${awayName}_$dateStr.pdf".replace(" ", "_")
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun addCoverPage(doc: PdfDocument, pageNum: Int): Int {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        val canvas = page.canvas

        canvas.drawColor(CLR_BG)
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 120f, Paint().apply { color = CLR_HEADER })
        drawText(canvas, "MATCH REPORT", PAGE_W / 2f, 55f, 36f, Color.WHITE, true, Paint.Align.CENTER)
        drawText(canvas, "Official Match Record", PAGE_W / 2f, 90f, 14f, Color.parseColor("#A5D6A7"), false, Paint.Align.CENTER)

        // ── SCORING CALCULATION LOGIC ──
        val regularPeriods = config?.regularPeriodsCount ?: this.regularPeriods
        var homeReg = 0; var awayReg = 0
        var homeOt = 0;  var awayOt = 0
        var homeKftm = 0; var awayKftm = 0
        var totalKicks = 0

        events.forEach { e ->
            if (e.type == EventType.GOAL) {
                val isHomeScorer = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
                if (e.period <= regularPeriods) {
                    if (isHomeScorer) homeReg++ else awayReg++
                } else {
                    if (isHomeScorer) homeOt++ else awayOt++
                }
            } else if (e.type == EventType.KFTM_KICK) {
                totalKicks++
                if (e.kftmResult == KftmResult.SCORED) {
                    if (e.team.isHome) homeKftm++ else awayKftm++
                }
            }
        }

        val homeTotalPlay = homeReg + homeOt
        val awayTotalPlay = awayReg + awayOt
        val wentToOt = homeOt > 0 || awayOt > 0 || events.any { it.period > regularPeriods }
        val wentToKftm = totalKicks > 0

        // Format strings with asterisk for OT/KFTM winners
        var homeScoreStr = homeTotalPlay.toString()
        var awayScoreStr = awayTotalPlay.toString()

        if (wentToOt && !wentToKftm && homeTotalPlay > awayTotalPlay) homeScoreStr += "*"
        if (wentToOt && !wentToKftm && awayTotalPlay > homeTotalPlay) awayScoreStr += "*"
        if (wentToKftm && homeKftm > awayKftm) homeScoreStr += "*"
        if (wentToKftm && awayKftm > homeKftm) awayScoreStr += "*"

        // Background box for score
        canvas.drawRoundRect(RectF(PAGE_W / 2f - 220f, 150f, PAGE_W / 2f + 220f, 320f), 16f, 16f, Paint().apply { color = Color.parseColor("#F5F5F5") })

        // Main Title Line: Home-Eagles(Score*) vs Away-Giants(score)
        val homeFullStr = "$homeName($homeScoreStr)"
        val awayFullStr = "$awayName($awayScoreStr)"
        drawText(canvas, homeFullStr, PAGE_W / 2f - 40f, 200f, 24f, CLR_HOME, true, Paint.Align.RIGHT)
        drawText(canvas, "vs", PAGE_W / 2f, 200f, 20f, CLR_TEXT, false, Paint.Align.CENTER)
        drawText(canvas, awayFullStr, PAGE_W / 2f + 40f, 200f, 24f, CLR_AWAY, true, Paint.Align.LEFT)

        // Sub-scores for OT and KFTM
        var currentY = 240f
        if (wentToOt) {
            drawText(canvas, "OT ($homeOt - $awayOt)", PAGE_W / 2f, currentY, 14f, CLR_SUBTEXT, true, Paint.Align.CENTER)
            currentY += 25f
        }
        if (wentToKftm) {
            drawText(canvas, "KFTM ($homeKftm - $awayKftm) | Total Kicks: $totalKicks", PAGE_W / 2f, currentY, 14f, CLR_SUBTEXT, true, Paint.Align.CENTER)
        }

        // Meta Data
        drawText(canvas, SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date()), PAGE_W / 2f, 345f, 14f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        drawText(canvas, config?.league?.name ?: "League", PAGE_W / 2f, 370f, 14f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        // Format, total elapsed time (kickoff to final whistle, intervals included) and stoppage
        if (matchSummary.isNotBlank()) {
            drawText(canvas, matchSummary, PAGE_W / 2f, 395f, 12f, CLR_TEXT, true, Paint.Align.CENTER)
        }

        // Bottom Stats Row
        val fouls = events.count { it.type == EventType.FOUL }
        val yellow = events.count { it.type == EventType.CARD && it.cardType == CardType.YELLOW }
        val red = events.count { it.type == EventType.CARD && it.cardType == CardType.RED }
        val stoppageMins = totalStoppageMillis / 60000
        val stoppageSecs = (totalStoppageMillis / 1000) % 60
        val stopStr = String.format("%d:%02d", stoppageMins, stoppageSecs)

        drawStatBox(canvas, "FOULS", fouls.toString(), PAGE_W / 2f - 240f, 460f, CLR_TEXT)
        drawStatBox(canvas, "YELLOW", yellow.toString(), PAGE_W / 2f - 80f, 460f, CLR_YELLOW)
        drawStatBox(canvas, "RED", red.toString(), PAGE_W / 2f + 80f, 460f, CLR_RED)
        drawStatBox(canvas, "STOPPAGE", stopStr, PAGE_W / 2f + 240f, 460f, Color.parseColor("#1976D2"))

        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    private fun addSummaryPage(doc: PdfDocument, pageNum: Int): Int {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        val canvas = page.canvas
        canvas.drawColor(CLR_BG)
        var y = 40f
        y = drawSectionHeader(canvas, "MATCH SUMMARY", y)
        y += 20f
        val goals = events.filter { it.type == EventType.GOAL }
        if (goals.isNotEmpty()) {
            y = drawTableHeader(canvas, listOf("MIN", "TEAM", "SCORER", "ASSIST", "TYPE"), y)
            for (e in goals) {
                if (y > PAGE_H - 80f) break
                y = drawTableRow(
                    canvas,
                    listOf(
                        e.minuteLabel(),
                        e.team.name,
                        "#${e.player.jerseyNumber} ${e.player.name}",
                        e.assistPlayer?.let { "#${it.jerseyNumber} ${it.name}" } ?: "—",
                        "${if (e.isOwnGoal) "OG " else ""}${if (e.isPK) "PK" else "Open Play"}"
                    ),
                    y,
                    if (e.team.isHome) CLR_HOME else CLR_AWAY
                )
            }
            y += 20f
        }
        if (y < PAGE_H - 150f) {
            val fouls = events.filter { it.type == EventType.FOUL }
            if (fouls.isNotEmpty()) {
                y = drawSectionHeader(canvas, "FOULS", y)
                y = drawTableHeader(canvas, listOf("MIN", "TEAM", "PLAYER", "TYPE", "CARD"), y)
                for (e in fouls) {
                    if (y > PAGE_H - 80f) break
                    val card = events.find { it.type == EventType.CARD && it.player == e.player && it.minute == e.minute }
                    y = drawTableRow(
                        canvas,
                        listOf(
                            e.minuteLabel(),
                            e.team.name,
                            "#${e.player.jerseyNumber} ${e.player.name}",
                            "${e.foulType?.name ?: ""}${if (e.isPK) " → PK" else ""}",
                            when (card?.cardType) {
                                CardType.YELLOW -> "🟨 Yellow"
                                CardType.RED -> "🟥 Red"
                                null -> "—"
                            }
                        ),
                        y,
                        if (e.team.isHome) CLR_HOME else CLR_AWAY
                    )
                }
            }
        }
        // NEW: list every card (cards logged on the watch have no matching foul row)
        val cards = events.filter { it.type == EventType.CARD }
        if (cards.isNotEmpty() && y < PAGE_H - 120f) {
            y += 10f
            y = drawSectionHeader(canvas, "CAUTIONS & SEND-OFFS", y)
            y = drawTableHeader(canvas, listOf("MIN", "TEAM", "PLAYER", "CARD", "CODE", "GPS TAG"), y)
            for (e in cards) {
                if (y > PAGE_H - 80f) break
                y = drawTableRow(
                    canvas,
                    listOf(
                        e.minuteLabel(),
                        e.team.name,
                        "#${e.player.jerseyNumber} ${e.player.name}",
                        if (e.cardType == CardType.RED) "Red" else "Yellow",
                        e.misconductCode ?: "—",
                        e.gpsLocation?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "no fix"
                    ),
                    y,
                    if (e.team.isHome) CLR_HOME else CLR_AWAY
                )
            }
            // Legend: spell out each misconduct code used (Law 12)
            cards.mapNotNull { it.misconductCode }.distinct().forEach { code ->
                if (y > PAGE_H - 45f) return@forEach
                y += 13f
                drawText(canvas, "$code = ${MisconductCodes.describe(code)}", 24f, y, 9f, CLR_SUBTEXT)
            }
        }
        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    private fun addFieldDiagramPage(doc: PdfDocument, pageNum: Int, period: Int, track: List<GpsReferenceService.GpsTrackPoint>, periodEvents: List<MatchEvent>): Int {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        val canvas = page.canvas
        canvas.drawColor(CLR_BG)
        drawText(canvas, "PERIOD $period — FIELD COVERAGE", PAGE_W / 2f, 30f, 18f, CLR_TEXT, true, Paint.Align.CENTER)
        drawText(canvas, "Goals: ${periodEvents.count { it.type == EventType.GOAL }}   Fouls: ${periodEvents.count { it.type == EventType.FOUL }}   Cards: ${periodEvents.count { it.type == EventType.CARD }}", PAGE_W / 2f, 52f, 12f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        if (track.isNotEmpty() || periodEvents.any { it.gpsLocation != null }) {
            val bm = renderer.renderPeriod(period, track, periodEvents)
            canvas.drawBitmap(bm, null, RectF(30f, 65f, PAGE_W - 30f, PAGE_H - 50f), null)
        } else {
            drawText(canvas, "No GPS data for this period", PAGE_W / 2f, PAGE_H / 2f, 16f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        }
        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    private fun addFullMatchDiagramPage(doc: PdfDocument, pageNum: Int, totalPeriods: Int): Int {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        val canvas = page.canvas
        canvas.drawColor(CLR_BG)
        drawText(canvas, "FULL MATCH — FIELD COVERAGE", PAGE_W / 2f, 30f, 18f, CLR_TEXT, true, Paint.Align.CENTER)
        if (gpsTrack.isNotEmpty()) {
            canvas.drawBitmap(renderer.renderFullMatch(gpsTrack, events, totalPeriods), null, RectF(30f, 65f, PAGE_W - 30f, PAGE_H - 50f), null)
        } else {
            drawText(canvas, "No GPS tracking data available", PAGE_W / 2f, PAGE_H / 2f, 16f, CLR_SUBTEXT, false, Paint.Align.CENTER)
        }
        drawPageFooter(canvas, pageNum)
        doc.finishPage(page)
        return pageNum + 1
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        canvas.drawText(text, x, y, Paint().apply { this.color = color; textSize = size; isAntiAlias = true; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; textAlign = align })
    }

    private fun drawSectionHeader(canvas: Canvas, title: String, y: Float): Float {
        canvas.drawRect(20f, y, PAGE_W - 20f, y + 28f, Paint().apply { color = CLR_HEADER })
        drawText(canvas, title, 30f, y + 20f, 13f, Color.WHITE, true)
        return y + 38f
    }

    private fun drawTableHeader(canvas: Canvas, headers: List<String>, y: Float): Float {
        canvas.drawRect(20f, y, PAGE_W - 20f, y + 22f, Paint().apply { color = Color.parseColor("#EEEEEE") })
        val colW = (PAGE_W - 40f) / headers.size
        headers.forEachIndexed { i, h -> drawText(canvas, h, 20f + i * colW + 4f, y + 15f, 10f, CLR_SUBTEXT, true) }
        canvas.drawLine(20f, y + 22f, PAGE_W - 20f, y + 22f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        return y + 26f
    }

    private fun drawTableRow(canvas: Canvas, cells: List<String>, y: Float, accentColor: Int = CLR_TEXT): Float {
        val colW = (PAGE_W - 40f) / cells.size
        cells.forEachIndexed { i, c -> drawText(canvas, c, 20f + i * colW + 4f, y + 14f, 10f, if (i == 1) accentColor else CLR_TEXT) }
        canvas.drawLine(20f, y + 20f, PAGE_W - 20f, y + 20f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        return y + 22f
    }

    private fun drawStatBox(canvas: Canvas, label: String, value: String, cx: Float, cy: Float, color: Int) {
        canvas.drawRoundRect(RectF(cx - 60f, cy - 30f, cx + 60f, cy + 30f), 8f, 8f, Paint().apply { this.color = Color.parseColor("#F5F5F5") })
        drawText(canvas, value, cx, cy + 8f, 28f, color, true, Paint.Align.CENTER)
        drawText(canvas, label, cx, cy + 26f, 10f, CLR_SUBTEXT, false, Paint.Align.CENTER)
    }

    private fun drawPageFooter(canvas: Canvas, pageNum: Int) {
        canvas.drawLine(20f, PAGE_H - 30f, PAGE_W - 20f, PAGE_H - 30f, Paint().apply { color = CLR_DIVIDER; strokeWidth = 1f })
        drawText(canvas, "Referee Timer Match Report  •  Page $pageNum", PAGE_W / 2f, PAGE_H - 12f, 9f, CLR_SUBTEXT, false, Paint.Align.CENTER)
    }
}
