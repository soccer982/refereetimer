package com.example.refereetimer.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.refereetimer.shared.MatchClock
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.BodyParts
import com.example.refereetimer.shared.models.TeamTag
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEW: fills in the official US Soccer forms (bundled in assets/forms) exactly as printed:
 *   1. Referee Report             (always)
 *   2. Supplemental Referee Report (send-offs and serious injuries; incidents marked on its field)
 *   3. Fourth Official Report      (goals, substitutions, misconduct by team + notes)
 * The blank form is drawn as the page background and the match data is written onto its lines.
 * All positions are in PDF points (US Letter, 612 × 792), measured from the original forms.
 */
class UssfForms(
    private val context: Context,
    private val doc: PdfDocument,
    events: List<MatchEvent>,
    private val d: MatchDetails,
    private val clock: MatchClock?,
    private val homeName: String,
    private val awayName: String
) {
    private val W = 612; private val H = 792

    private val sorted = events.sortedBy { it.timestamp }
    private val goals = sorted.filter { it.type == EventType.GOAL }
    private val cautions = sorted.filter { it.type == EventType.CARD && it.cardType == CardType.YELLOW }
    private val sendOffs = sorted.filter { it.type == EventType.CARD && it.cardType == CardType.RED }
    private val injuries = sorted.filter { it.type == EventType.INJURY }
    private val seriousInjuries = injuries.filter { it.seriousInjury }
    private val subs = sorted.filter { it.type == EventType.SUBSTITUTION }

    val needsSupplemental: Boolean get() = sendOffs.isNotEmpty() || seriousInjuries.isNotEmpty()

    private fun homeScored(e: MatchEvent) = if (e.isOwnGoal) !e.team.isHome else e.team.isHome
    private val homeScore = goals.count { homeScored(it) }
    private val awayScore = goals.size - homeScore

    private val dateFmt = SimpleDateFormat("MM/dd/yyyy", Locale.US)
    // Scheduled date from the game confirmation, else the day the match was played
    private val gameDate = d.gameDate.ifBlank { dateFmt.format(Date(clock?.kickoffAt?.takeIf { it > 0 } ?: System.currentTimeMillis())) }
    private val today = dateFmt.format(Date())

    // ── Template rendering ──

    private val templates = mutableMapOf<String, Bitmap>()

    /** Renders page 1 of a bundled form at 2× for a crisp background. */
    private fun template(asset: String): Bitmap = templates.getOrPut(asset) {
        val file = File(context.cacheDir, "form_$asset")
        if (!file.exists()) context.assets.open("forms/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val page = renderer.openPage(0)
            try {
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bmp
            } finally { page.close() }
        } finally { renderer.close(); pfd.close() }
    }

    private fun startForm(pageNum: Int, asset: String): Pair<PdfDocument.Page, Canvas> {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
        val c = page.canvas
        c.drawBitmap(template(asset), null, RectF(0f, 0f, W.toFloat(), H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return page to c
    }

    // ── Writing helpers ──

    private val ink = Color.parseColor("#0D2C8C")      // blue "pen" so filled entries stand out
    private fun pen(size: Float, bold: Boolean = false, color: Int = ink) = Paint().apply {
        textSize = size; this.color = color; isAntiAlias = true
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fit(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var cut = s
        while (cut.isNotEmpty() && p.measureText("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** Writes on a printed underline whose top edge is [lineY]. */
    private fun onLine(c: Canvas, text: String, x0: Float, x1: Float, lineY: Float, size: Float = 10f) {
        if (text.isBlank()) return
        val p = pen(size)
        c.drawText(fit(text, p, x1 - x0 - 4f), x0 + 2f, lineY - 3f, p)
    }

    /** Writes centred in a table cell. */
    private fun inCell(c: Canvas, text: String, l: Float, r: Float, t: Float, b: Float, size: Float = 9f, color: Int = ink, center: Boolean = false) {
        if (text.isBlank()) return
        val p = pen(size, color = color).apply { if (center) textAlign = Paint.Align.CENTER }
        val s = fit(text, p, r - l - 4f)
        val x = if (center) (l + r) / 2f else l + 3f
        c.drawText(s, x, (t + b) / 2f + size * 0.36f, p)
    }

    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { para ->
            var line = ""
            para.split(" ").forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(trial) <= width) line = trial
                else {
                    if (line.isNotEmpty()) out.add(line)
                    line = word
                    while (p.measureText(line) > width && line.length > 1) {          // very long word
                        val n = p.breakText(line, true, width, null); out.add(line.take(n)); line = line.drop(n)
                    }
                }
            }
            out.add(line)
        }
        return out
    }

    private fun continued(c: Canvas, page: Int, of: Int) {
        if (of <= 1) return
        c.drawText("Page $page of $of (continued)", 576f, 112f, pen(8f, color = Color.parseColor("#C62828")).apply { textAlign = Paint.Align.RIGHT })
    }

    private fun who(e: MatchEvent): String {
        val name = e.player.name.takeIf { it.isNotBlank() && it != "Unknown" }
        return "${e.player.tag}" + (name?.let { " $it" } ?: "")
    }

    // Shared header block (same labels on all three forms; only the line positions differ)
    private val MAIN_X0 = 144f; private val MAIN_X1 = 432f
    private val RIGHT_X0 = 504f; private val RIGHT_X1 = 576f

    private fun gameHeader(c: Canvas, homeY: Float, awayY: Float, assocY: Float, divY: Float, gameY: Float, dateY: Float) {
        onLine(c, TeamTag.label(homeName, true), MAIN_X0, MAIN_X1, homeY); onLine(c, "$homeScore", RIGHT_X0, RIGHT_X1, homeY)
        onLine(c, TeamTag.label(awayName, false), MAIN_X0, MAIN_X1, awayY); onLine(c, "$awayScore", RIGHT_X0, RIGHT_X1, awayY)
        onLine(c, d.competition, MAIN_X0, MAIN_X1, assocY)
        onLine(c, d.division, MAIN_X0, MAIN_X1, divY)
        onLine(c, d.gameNumber, MAIN_X0, MAIN_X1, gameY)
        onLine(c, listOf(gameDate, d.kickoff, d.venue).filter { it.isNotBlank() }.joinToString("  ·  "), MAIN_X0, MAIN_X1, dateY)
    }

    private fun officials(c: Canvas, refY: Float, ar1Y: Float, ar2Y: Float, fourthY: Float) {
        onLine(c, d.referee, MAIN_X0, MAIN_X1, refY); onLine(c, d.refereeGrade, RIGHT_X0, RIGHT_X1, refY)
        onLine(c, d.ar1, MAIN_X0, MAIN_X1, ar1Y); onLine(c, d.ar1Grade, RIGHT_X0, RIGHT_X1, ar1Y)
        onLine(c, d.ar2, MAIN_X0, MAIN_X1, ar2Y); onLine(c, d.ar2Grade, RIGHT_X0, RIGHT_X1, ar2Y)
        onLine(c, d.fourth, MAIN_X0, MAIN_X1, fourthY); onLine(c, d.fourthGrade, RIGHT_X0, RIGHT_X1, fourthY)
    }

    private fun signature(c: Canvas, nameY: Float, dateY: Float, idY: Float, phoneY: Float, emailY: Float) {
        onLine(c, d.yourName.ifBlank { d.referee }, MAIN_X0, MAIN_X1, nameY); onLine(c, today, RIGHT_X0, RIGHT_X1, dateY)
        onLine(c, d.refereeId, MAIN_X0, MAIN_X1, idY)
        onLine(c, d.phone, MAIN_X0, MAIN_X1, phoneY)
        onLine(c, d.email, MAIN_X0, MAIN_X1, emailY)
    }

    // ══════════════ 1. REFEREE REPORT ══════════════

    private val RR_CAUTION_ROWS = listOf(415.0f to 428.1f, 428.1f to 442.0f, 442.0f to 455.1f, 455.1f to 469.0f, 469.0f to 482.1f,
        482.1f to 496.0f, 496.0f to 509.1f, 509.1f to 523.0f, 523.0f to 536.1f, 536.1f to 550.8f)
    private val RR_CAUTION_COLS = listOf(30.8f to 168.3f, 168.7f to 306.2f, 306.6f to 444.2f, 444.7f to 582.1f)
    private val RR_SENDOFF_ROWS = listOf(602.3f to 615.9f, 615.9f to 629.0f, 629.0f to 642.6f, 642.6f to 655.7f, 655.7f to 670.1f)
    private val RR_SENDOFF_COLS = listOf(30.8f to 168.0f, 168.5f to 305.7f, 306.2f to 443.4f, 443.8f to 581.0f)

    fun writeRefereeReport(startPage: Int): Int {
        val pages = maxOf(1, (cautions.size + 9) / 10, (sendOffs.size + 4) / 5)
        for (i in 0 until pages) {
            val (page, c) = startForm(startPage + i, "referee_report.pdf")
            gameHeader(c, 191.2f, 210.1f, 229.1f, 248.1f, 267.0f, 286.0f)
            officials(c, 305.0f, 324.1f, 343.0f, 362.0f)
            fun row(e: MatchEvent, cols: List<Pair<Float, Float>>, r: Pair<Float, Float>, red: Boolean) {
                inCell(c, "${who(e)} (${e.minuteLabel()})", cols[0].first, cols[0].second, r.first, r.second)
                // Pass/ID column left blank: player pass numbers aren't on the roster
                inCell(c, e.team.label, cols[2].first, cols[2].second, r.first, r.second)
                inCell(c, MisconductCodes.official(e.misconductCode) ?: "", cols[3].first, cols[3].second, r.first, r.second, 9.5f,
                    if (red) Color.parseColor("#C62828") else ink, center = true)
            }
            cautions.drop(i * 10).take(10).forEachIndexed { k, e -> row(e, RR_CAUTION_COLS, RR_CAUTION_ROWS[k], false) }
            sendOffs.drop(i * 5).take(5).forEachIndexed { k, e -> row(e, RR_SENDOFF_COLS, RR_SENDOFF_ROWS[k], true) }
            signature(c, 694.7f, 694.7f, 713.6f, 732.6f, 751.5f)
            continued(c, i + 1, pages)
            doc.finishPage(page)
        }
        return startPage + pages
    }

    // ══════════════ 2. SUPPLEMENTAL REFEREE REPORT ══════════════

    private val SUP_LINES = listOf(357.5f, 378.0f, 398.6f, 419.4f, 440.0f, 460.8f, 481.4f, 502.2f, 522.8f,
        543.6f, 564.2f, 585.0f, 605.6f, 626.4f, 647.0f)
    private val SUP_X0 = 37.8f; private val SUP_X1 = 323.7f
    // The printed pitch (goal lines at top and bottom)
    private val PITCH_L = 348.0f; private val PITCH_R = 566.8f; private val PITCH_T = 333.8f; private val PITCH_B = 650.8f

    /**
     * Incidents for the supplemental: every send-off and serious injury. For a second-caution
     * send-off the player's first caution is shown on the field too (marked "*").
     */
    private fun incidents(): List<MatchEvent> = (sendOffs + seriousInjuries).sortedBy { it.timestamp }

    private fun statementText(): String = incidents().mapIndexed { i, e ->
        val head = when (e.type) {
            EventType.CARD -> {
                val code = MisconductCodes.official(e.misconductCode) ?: ""
                val extra = if (code in MisconductCodes.SECOND_CAUTION_CODES) {
                    val ys = cautions.filter { it.team.isHome == e.team.isHome && it.player.jerseyNumber == e.player.jerseyNumber }
                    " (cautions: " + ys.joinToString(", ") { "${it.minuteLabel()} ${it.misconductCode}" } + ")"
                } else ""
                "${i + 1}) SEND-OFF ${e.minuteLabel()}, period ${e.period}: ${e.team.label} ${who(e)}. $code: ${MisconductCodes.describe(code)}$extra."
            }
            else -> "${i + 1}) SERIOUS INJURY ${e.minuteLabel()}, period ${e.period}: ${e.team.label} ${who(e)}. ${BodyParts.label(e.bodyPart)}."
        }
        head + "\n" + (e.reportText?.takeIf { it.isNotBlank() } ?: "(No description entered.)")
    }.joinToString("\n")

    fun writeSupplemental(startPage: Int, fieldLengthM: Double?, fieldWidthM: Double?): Int {
        if (!needsSupplemental) return startPage
        val p = pen(8.6f)
        val lines = wrap(statementText(), p, SUP_X1 - SUP_X0 - 4f)
        val pages = maxOf(1, (lines.size + SUP_LINES.size - 1) / SUP_LINES.size)

        // Markers: incidents + the first caution(s) of any second-caution send-off
        val shown = incidents().toMutableList()
        sendOffs.filter { it.misconductCode in MisconductCodes.SECOND_CAUTION_CODES }.forEach { r ->
            shown += cautions.filter { it.team.isHome == r.team.isHome && it.player.jerseyNumber == r.player.jerseyNumber }
        }
        // Use the full card list so the 2nd-caution slash/star logic works, then keep only what's shown
        val ids = shown.map { it.id }.toSet()
        val markers = EventMarkers.build(cautions + sendOffs + seriousInjuries).filter { it.event.id in ids }

        for (i in 0 until pages) {
            val (page, c) = startForm(startPage + i, "supplemental_report.pdf")
            gameHeader(c, 203.5f, 222.5f, 241.5f, 260.6f, 279.4f, 298.4f)

            lines.drop(i * SUP_LINES.size).take(SUP_LINES.size).forEachIndexed { k, line ->
                val bold = Regex("^\\d+\\) ").containsMatchIn(line)
                c.drawText(line, SUP_X0 + 2f, SUP_LINES[k] - 3f, pen(8.6f, bold))
            }

            // Field: length runs top (goal line of corners 1–2) to bottom; width left (touchline 1–4) to right
            var located = 0
            markers.forEach { m ->
                val g = m.event.gpsLocation ?: return@forEach
                if (g.fieldX == 0f && g.fieldY == 0f) return@forEach
                val x = PITCH_L + g.fieldY.coerceIn(0f, 1f) * (PITCH_R - PITCH_L)
                val y = PITCH_T + g.fieldX.coerceIn(0f, 1f) * (PITCH_B - PITCH_T)
                EventMarkers.draw(c, m, x, y, 8.5f)
                located++
            }
            if (markers.isNotEmpty() && located == 0) {
                c.drawText("No field positions: calibrate the field", (PITCH_L + PITCH_R) / 2f, 500f,
                    pen(7.5f, color = Color.parseColor("#C62828")).apply { textAlign = Paint.Align.CENTER })
            }
            if (fieldLengthM != null && fieldWidthM != null) {
                c.drawText("${fieldLengthM.toInt()} × ${fieldWidthM.toInt()} m · top = goal line of corners 1–2", (PITCH_L + PITCH_R) / 2f, 329f,
                    pen(6f, color = Color.parseColor("#555555")).apply { textAlign = Paint.Align.CENTER })
            }
            // Key under the field
            EventMarkers.drawKey(c, 342f, 657f, 236f, 4.2f, Color.parseColor("#333333"), EventMarkers.kindsIn(markers))

            signature(c, 691.2f, 690.6f, 709.7f, 728.6f, 747.5f)
            continued(c, i + 1, pages)
            doc.finishPage(page)
        }
        return startPage + pages
    }

    // ══════════════ 3. FOURTH OFFICIAL REPORT ══════════════

    private val FO_HOME_CELLS = listOf(91.8f to 116.5f, 117.0f to 142.0f, 142.5f to 167.3f, 167.8f to 192.7f,
        193.2f to 218.2f, 218.7f to 243.8f, 244.2f to 269.2f, 269.7f to 294.7f)
    private val FO_AWAY_CELLS = listOf(379.0f to 403.9f, 404.4f to 429.2f, 429.7f to 454.5f, 455.0f to 479.8f,
        480.3f to 505.2f, 505.7f to 530.5f, 531.0f to 555.9f, 556.4f to 581.3f)
    private val FO_AWAY_MISC_CELLS = listOf(383.2f to 408.1f, 408.6f to 433.4f, 433.9f to 458.7f, 459.2f to 484.1f,
        484.5f to 509.4f, 509.9f to 534.7f, 535.2f to 560.1f, 560.6f to 581.3f)
    // Row bands (top, bottom)
    private val FO_HOME_GOALS = listOf(363.3f to 376.4f, 376.4f to 389.6f)                       // #, Minute
    private val FO_AWAY_GOALS = listOf(364.1f to 377.3f, 377.3f to 390.4f)
    private val FO_HOME_SUBS = listOf(415.4f to 428.6f, 428.6f to 441.7f, 441.7f to 454.9f)      // In, Out, Minute
    private val FO_AWAY_SUBS = listOf(416.3f to 429.4f, 429.4f to 442.6f, 442.6f to 455.8f)
    private val FO_HOME_MISC = listOf(480.7f to 493.9f, 493.9f to 507.1f, 507.1f to 520.1f)      // #, Minute, Code
    private val FO_AWAY_MISC = listOf(481.6f to 494.7f, 494.7f to 507.9f, 507.9f to 521.1f)
    private val FO_NOTES = listOf(573.4f, 594.1f, 614.7f, 635.4f, 656.0f, 676.7f, 697.3f, 717.9f, 738.6f, 759.2f)

    private fun notesText(): String = buildList {
        clock?.let {
            val now = System.currentTimeMillis()
            add("Format ${it.formatDescription()} · total elapsed ${MatchClock.format(it.elapsedMs(now))} · stoppage ${MatchClock.format(it.totalStoppageMs(now))}.")
        }
        goals.filter { it.assistPlayer != null }.takeIf { it.isNotEmpty() }?.let { list ->
            add("Assists: " + list.joinToString("; ") { "${it.minuteLabel()} ${it.team.label} ${it.player.tag} from ${it.assistTag()}" } + ".")
        }
        goals.filter { it.isOwnGoal }.takeIf { it.isNotEmpty() }?.let { list ->
            add("Own goals (OG): " + list.joinToString("; ") { "${it.minuteLabel()} by ${it.team.label} ${it.player.tag}" } + ".")
            if (list.any { it.assistPlayer != null }) add(MatchEvent.OG_ASSIST_KEY + ".")
        }
        goals.filter { it.isPK }.takeIf { it.isNotEmpty() }?.let { list ->
            add("Penalty kicks (PK): " + list.joinToString("; ") { "${it.minuteLabel()} ${it.team.label} ${it.player.tag}" } + ".")
        }
        if (injuries.isNotEmpty()) add("Injuries: " + injuries.joinToString("; ") {
            "${it.minuteLabel()} ${it.team.label} ${it.player.tag} ${BodyParts.label(it.bodyPart).lowercase()}" + if (it.seriousInjury) " (SERIOUS, see supplemental)" else ""
        } + ".")
        subs.filter { it.flag != null }.forEach { add("Sub flag ${it.minuteLabel()} ${it.team.label}: ${it.flag}.") }
        if (sendOffs.isNotEmpty()) add("Misconduct codes in red are send-offs.")
    }.joinToString("\n")

    fun writeFourthOfficial(startPage: Int): Int {
        fun goalsFor(home: Boolean) = goals.filter { homeScored(it) == home }
        fun subsFor(home: Boolean) = subs.filter { it.team.isHome == home }
        fun miscFor(home: Boolean) = (cautions + sendOffs).filter { it.team.isHome == home }.sortedBy { it.timestamp }

        val most = listOf(true, false).flatMap { listOf(goalsFor(it).size, subsFor(it).size, miscFor(it).size) }.maxOrNull() ?: 0
        val gridPages = maxOf(1, (most + 7) / 8)
        val notePen = pen(9f)
        val noteLines = wrap(notesText(), notePen, 576f - 36f - 6f)
        val pages = maxOf(gridPages, (noteLines.size + FO_NOTES.size - 1) / FO_NOTES.size)

        for (i in 0 until pages) {
            val (page, c) = startForm(startPage + i, "fourth_official_report.pdf")
            gameHeader(c, 153.3f, 172.2f, 191.2f, 210.1f, 229.1f, 248.1f)
            officials(c, 267.1f, 286.0f, 305.0f, 324.1f)

            for (home in listOf(true, false)) {
                val cells = if (home) FO_HOME_CELLS else FO_AWAY_CELLS
                val miscCells = if (home) FO_HOME_CELLS else FO_AWAY_MISC_CELLS
                val g = if (home) FO_HOME_GOALS else FO_AWAY_GOALS
                val s = if (home) FO_HOME_SUBS else FO_AWAY_SUBS
                val m = if (home) FO_HOME_MISC else FO_AWAY_MISC

                goalsFor(home).drop(i * 8).take(8).forEachIndexed { k, e ->
                    val (l, r) = cells[k]
                    inCell(c, if (e.isOwnGoal) "OG" else e.player.shortTag, l, r, g[0].first, g[0].second, 8.5f, center = true)
                    inCell(c, e.minuteLabel().trimEnd('\''), l, r, g[1].first, g[1].second, 7.5f, center = true)
                }
                subsFor(home).drop(i * 8).take(8).forEachIndexed { k, e ->
                    val (l, r) = cells[k]
                    val col = if (e.flag != null) Color.parseColor("#C62828") else ink
                    inCell(c, e.player.shortTag, l, r, s[0].first, s[0].second, 8.5f, col, center = true)
                    inCell(c, "${e.playerOff?.jerseyNumber ?: ""}", l, r, s[1].first, s[1].second, 8.5f, col, center = true)
                    inCell(c, e.minuteLabel().trimEnd('\''), l, r, s[2].first, s[2].second, 7.5f, center = true)
                }
                miscFor(home).drop(i * 8).take(8).forEachIndexed { k, e ->
                    val (l, r) = miscCells[k]
                    val red = e.cardType == CardType.RED
                    inCell(c, e.player.shortTag, l, r, m[0].first, m[0].second, 8.5f, center = true)
                    inCell(c, e.minuteLabel().trimEnd('\''), l, r, m[1].first, m[1].second, 7.5f, center = true)
                    inCell(c, MisconductCodes.official(e.misconductCode) ?: "", l, r, m[2].first, m[2].second, 7.5f,
                        if (red) Color.parseColor("#C62828") else ink, center = true)
                }
            }

            noteLines.drop(i * FO_NOTES.size).take(FO_NOTES.size).forEachIndexed { k, line ->
                c.drawText(line, 38f, FO_NOTES[k] - 3f, notePen)
            }
            continued(c, i + 1, pages)
            doc.finishPage(page)
        }
        return startPage + pages
    }

    fun release() { templates.values.forEach { it.recycle() }; templates.clear() }
}
