package com.example.refereetimer.phone

import android.content.Context
import android.graphics.*
import com.example.refereetimer.shared.models.MatchEvent

/**
 * Draws the field with the referee's heatmap and event symbols, PORTRAIT (for US Letter pages).
 *
 * The field stands upright, the same way as on the US Soccer Supplemental Report:
 *   TOP goal line    = corners 1–2        BOTTOM goal line = corners 3–4
 *   LEFT touchline   = corners 1–4        RIGHT touchline  = corners 2–3
 * Field coordinates from the watch's calibration: x = 0..1 along the length (top → bottom),
 * y = 0..1 across the width (left → right). Drawn to the real measured proportions.
 *
 * Image: 1000 × 1200 px; field on top, symbol key (and period colours) underneath.
 */
class FieldDiagramRenderer(
    private val context: Context,
    private val lengthM: Double = 105.0,
    private val widthM: Double = 68.0,
    private val allEvents: List<MatchEvent> = emptyList()   // whole match, for the 2nd-caution star/slash
) {
    companion object {
        const val WIDTH = 1000
        const val HEIGHT = 1200
    }

    private val SIDE = 70f; private val TOP = 70f; private val BOTTOM_SPACE = 210f

    // Fit the real field (upright) into the image
    private val scale = minOf((WIDTH - 2 * SIDE) / widthM.toFloat(), (HEIGHT - TOP - BOTTOM_SPACE) / lengthM.toFloat())
    private val fieldW = widthM.toFloat() * scale          // pixels across (touchline to touchline)
    private val fieldH = lengthM.toFloat() * scale         // pixels down (goal line to goal line)
    private val fieldLeft = (WIDTH - fieldW) / 2f
    private val fieldTop = TOP + ((HEIGHT - TOP - BOTTOM_SPACE) - fieldH) / 2f
    private val fieldRight = fieldLeft + fieldW
    private val fieldBottom = fieldTop + fieldH

    private val PERIOD_COLORS = listOf(
        Color.argb(200, 33, 150, 243), Color.argb(200, 255, 235, 59), Color.argb(200, 255, 152, 0),
        Color.argb(200, 156, 39, 176), Color.argb(200, 0, 188, 212), Color.argb(200, 233, 30, 99)
    )

    /** Screen position for field coordinates (x along the length, y across the width). */
    private fun sx(fieldY: Float) = fieldLeft + fieldY.coerceIn(-0.05f, 1.05f) * fieldW
    private fun sy(fieldX: Float) = fieldTop + fieldX.coerceIn(-0.05f, 1.05f) * fieldH
    private fun m(metres: Float) = metres * scale

    fun renderPeriod(period: Int, trackPoints: List<GpsReferenceService.GpsTrackPoint>, events: List<MatchEvent>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        drawBackground(canvas); drawFieldMarkings(canvas)
        drawHeatmap(canvas, trackPoints, PERIOD_COLORS[(period - 1).coerceIn(0, PERIOD_COLORS.size - 1)])
        drawEventPins(canvas, events); drawLabel(canvas, "Period $period"); drawCornerLabels(canvas)
        return bitmap
    }

    fun renderFullMatch(trackPoints: List<GpsReferenceService.GpsTrackPoint>, events: List<MatchEvent>, totalPeriods: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        drawBackground(canvas); drawFieldMarkings(canvas)
        for (p in 1..totalPeriods) drawHeatmap(canvas, trackPoints.filter { it.period == p }, PERIOD_COLORS[(p - 1).coerceIn(0, PERIOD_COLORS.size - 1)])
        val keyH = drawEventPins(canvas, events); drawLabel(canvas, "Full Match"); drawCornerLabels(canvas)
        if (trackPoints.isNotEmpty()) drawPeriodLegend(canvas, totalPeriods, fieldBottom + 40f + keyH + 30f)
        return bitmap
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#1B5E20"))
        // Mowing stripes across the field, purely cosmetic
        val stripe = Paint().apply { color = Color.parseColor("#215F25") }
        val n = 12; val h = fieldH / n
        for (i in 0 until n step 2) canvas.drawRect(fieldLeft, fieldTop + i * h, fieldRight, fieldTop + (i + 1) * h, stripe)
    }

    private fun drawFieldMarkings(canvas: Canvas) {
        val lp = Paint().apply { color = Color.WHITE; strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        val sp = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val midX = fieldLeft + fieldW / 2f; val midY = fieldTop + fieldH / 2f

        canvas.drawRect(fieldLeft, fieldTop, fieldRight, fieldBottom, lp)
        canvas.drawLine(fieldLeft, midY, fieldRight, midY, lp)                       // halfway line
        canvas.drawCircle(midX, midY, m(9.15f), lp); canvas.drawCircle(midX, midY, 4f, sp)

        // Scale the boxes down on small (youth) fields so they never overlap
        val boxScale = minOf(1f, (widthM / 68.0).toFloat() + 0.15f).coerceAtLeast(0.5f)
        val paDepth = m(16.5f * boxScale); val paHalf = m(20.16f * boxScale)
        val gaDepth = m(5.5f * boxScale);  val gaHalf = m(9.16f * boxScale)
        val spot = m(11f * boxScale)

        // Top end (corners 1–2)
        canvas.drawRect(midX - paHalf, fieldTop, midX + paHalf, fieldTop + paDepth, lp)
        canvas.drawRect(midX - gaHalf, fieldTop, midX + gaHalf, fieldTop + gaDepth, lp)
        canvas.drawCircle(midX, fieldTop + spot, 4f, sp)
        // Bottom end (corners 3–4)
        canvas.drawRect(midX - paHalf, fieldBottom - paDepth, midX + paHalf, fieldBottom, lp)
        canvas.drawRect(midX - gaHalf, fieldBottom - gaDepth, midX + gaHalf, fieldBottom, lp)
        canvas.drawCircle(midX, fieldBottom - spot, 4f, sp)
        // Goals
        val goalHalf = m(3.66f); val gp = Paint(lp).apply { strokeWidth = 5f }
        canvas.drawLine(midX - goalHalf, fieldTop - 6f, midX + goalHalf, fieldTop - 6f, gp)
        canvas.drawLine(midX - goalHalf, fieldBottom + 6f, midX + goalHalf, fieldBottom + 6f, gp)
    }

    private fun drawHeatmap(canvas: Canvas, trackPoints: List<GpsReferenceService.GpsTrackPoint>, baseColor: Int) {
        if (trackPoints.isEmpty()) return
        val rows = 42                                                        // down the length
        val cols = (rows * widthM / lengthM).toInt().coerceIn(12, 40)        // across the width
        val density = Array(rows) { IntArray(cols) }; var maxDensity = 1
        for (p in trackPoints) {
            if (p.fieldX < -0.05f || p.fieldX > 1.05f || p.fieldY < -0.05f || p.fieldY > 1.05f) continue
            val r = (p.fieldX * rows).toInt().coerceIn(0, rows - 1)
            val c = (p.fieldY * cols).toInt().coerceIn(0, cols - 1)
            density[r][c]++; if (density[r][c] > maxDensity) maxDensity = density[r][c]
        }
        val cellW = fieldW / cols; val cellH = fieldH / rows
        val red = Color.red(baseColor); val green = Color.green(baseColor); val blue = Color.blue(baseColor)
        val hp = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        for (r in 0 until rows) for (c in 0 until cols) {
            val d = density[r][c]; if (d == 0) continue
            hp.color = Color.argb((d.toFloat() / maxDensity * 190).toInt().coerceIn(35, 200), red, green, blue)
            val l = fieldLeft + c * cellW; val t = fieldTop + r * cellH
            canvas.drawRoundRect(RectF(l, t, l + cellW, t + cellH), 4f, 4f, hp)
        }
    }

    /**
     * Event symbols (see EventMarkers): circle = home, square = away, colour = event,
     * player number inside. Draws the key under the field and returns its height.
     */
    private fun drawEventPins(canvas: Canvas, events: List<MatchEvent>): Float {
        val ids = events.map { it.id }.toSet()
        val markers = EventMarkers.build(allEvents.ifEmpty { events }).filter { it.event.id in ids }
        val minute = Paint().apply { color = Color.WHITE; textSize = 17f; isAntiAlias = true; textAlign = Paint.Align.CENTER; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
        markers.forEach { mk ->
            val g = mk.event.gpsLocation ?: return@forEach
            if (g.fieldX == 0f && g.fieldY == 0f) return@forEach          // tagged but not placed (no calibration)
            val x = sx(g.fieldY); val y = sy(g.fieldX)
            EventMarkers.draw(canvas, mk, x, y, 17f)
            canvas.drawText(mk.event.minuteLabel(), x, y + 36f, minute)
        }
        return EventMarkers.drawKey(canvas, SIDE, fieldBottom + 40f, WIDTH - 2 * SIDE, 13f, Color.WHITE,
            markers.takeIf { it.isNotEmpty() }?.let { EventMarkers.kindsIn(it) })
    }

    private fun drawLabel(canvas: Canvas, text: String) {
        val p = Paint().apply { color = Color.WHITE; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
        canvas.drawText(text, SIDE, TOP - 22f, p)
        val s = Paint().apply { color = Color.parseColor("#C8E6C9"); textSize = 20f; isAntiAlias = true; textAlign = Paint.Align.RIGHT }
        canvas.drawText("${lengthM.toInt()} × ${widthM.toInt()} m · top = goal line 1–2", WIDTH - SIDE, TOP - 22f, s)
    }

    /** Which corner flag is which, matching the order walked during field setup. */
    private fun drawCornerLabels(canvas: Canvas) {
        val p = Paint().apply { color = Color.parseColor("#C8E6C9"); textSize = 20f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        canvas.drawText("1", fieldLeft - 22f, fieldTop + 7f, p)
        canvas.drawText("2", fieldRight + 22f, fieldTop + 7f, p)
        canvas.drawText("3", fieldRight + 22f, fieldBottom + 7f, p)
        canvas.drawText("4", fieldLeft - 22f, fieldBottom + 7f, p)
    }

    private fun drawPeriodLegend(canvas: Canvas, totalPeriods: Int, y: Float) {
        val tp = Paint().apply { color = Color.WHITE; textSize = 20f; isAntiAlias = true }
        val bp = Paint().apply { style = Paint.Style.FILL }
        var x = SIDE
        canvas.drawText("Heatmap:", x, y, tp); x += 110f
        for (p in 1..totalPeriods) {
            bp.color = PERIOD_COLORS[(p - 1).coerceIn(0, PERIOD_COLORS.size - 1)]
            canvas.drawRect(x, y - 16f, x + 22f, y + 4f, bp)
            canvas.drawText("Period $p", x + 30f, y, tp)
            x += 140f
        }
    }
}
