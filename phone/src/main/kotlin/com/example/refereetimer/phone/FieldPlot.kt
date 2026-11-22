package com.example.refereetimer.phone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.refereetimer.shared.models.MatchEvent

/**
 * NEW: draws a horizontal field directly onto a PDF page (vector lines, sharp when printed).
 * Used for the half-page diagrams: heatmap on top, incidents underneath.
 *
 *   LEFT goal line = corners 1–2, RIGHT goal line = corners 3–4
 *   corner 1 top-left, 2 bottom-left, 3 bottom-right, 4 top-right
 * Field coordinates from the watch's calibration: x = 0..1 along the length (left → right),
 * y = 0..1 across the width (top → bottom).
 */
class FieldPlot(private val lengthM: Double, private val widthM: Double) {

    companion object {
        /** Heatmap colour per period (readable on the light field, distinct from card colours). */
        val PERIOD_COLORS = listOf(
            Color.parseColor("#1E88E5"), Color.parseColor("#FB8C00"), Color.parseColor("#8E24AA"),
            Color.parseColor("#00897B"), Color.parseColor("#D81B60"), Color.parseColor("#6D4C41")
        )
        fun periodColor(period: Int) = PERIOD_COLORS[(period - 1).coerceIn(0, PERIOD_COLORS.size - 1)]
    }

    private val grass = Color.parseColor("#EEF6EE")
    private val lines = Color.parseColor("#2E7D32")
    private val label = Color.parseColor("#555555")

    /** The largest field of the right shape that fits in the box (centred, room left for corner labels). */
    fun fit(boxLeft: Float, boxTop: Float, boxW: Float, boxH: Float): RectF {
        val w = boxW - 28f
        val ratio = (widthM / lengthM).toFloat().coerceIn(0.45f, 0.8f)
        var fw = w; var fh = fw * ratio
        if (fh > boxH) { fh = boxH; fw = fh / ratio }
        val left = boxLeft + (boxW - fw) / 2f
        return RectF(left, boxTop, left + fw, boxTop + fh)
    }

    /** Grass background (draw this first, then any heatmap, then [drawLines]). */
    fun drawGrass(c: Canvas, r: RectF) = c.drawRect(r, Paint().apply { color = grass; style = Paint.Style.FILL })

    /** Grass + markings, for a field with no heatmap. */
    fun drawField(c: Canvas, r: RectF) { drawGrass(c, r); drawLines(c, r) }

    /** Field markings and corner numbers (drawn on top of the heatmap so they stay visible). */
    fun drawLines(c: Canvas, r: RectF) {
        val lp = Paint().apply { color = lines; style = Paint.Style.STROKE; strokeWidth = 0.9f; isAntiAlias = true }
        val dot = Paint().apply { color = lines; style = Paint.Style.FILL; isAntiAlias = true }
        val mx = r.width() / lengthM.toFloat(); val my = r.height() / widthM.toFloat()     // points per metre
        val midX = r.centerX(); val midY = r.centerY()
        c.drawRect(r, lp)
        c.drawLine(midX, r.top, midX, r.bottom, lp)
        c.drawOval(RectF(midX - 9.15f * mx, midY - 9.15f * my, midX + 9.15f * mx, midY + 9.15f * my), lp)
        c.drawCircle(midX, midY, 1.3f, dot)
        val bs = minOf(1f, (widthM / 68.0).toFloat() + 0.15f).coerceAtLeast(0.5f)    // smaller boxes on youth fields
        for (end in listOf(r.left, r.right)) {
            val dir = if (end == r.left) 1f else -1f
            fun box(depth: Float, half: Float) = c.drawRect(RectF(minOf(end, end + dir * depth * mx), midY - half * my,
                maxOf(end, end + dir * depth * mx), midY + half * my), lp)
            box(16.5f * bs, 20.16f * bs); box(5.5f * bs, 9.16f * bs)
            c.drawCircle(end + dir * 11f * bs * mx, midY, 1.3f, dot)
            c.drawLine(end - dir * 3f, midY - 3.66f * my, end - dir * 3f, midY + 3.66f * my, Paint(lp).apply { strokeWidth = 2.2f })
        }
        val t = Paint().apply { color = label; textSize = 7f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        c.drawText("1", r.left - 8f, r.top + 3f, t); c.drawText("2", r.left - 8f, r.bottom + 3f, t)
        c.drawText("3", r.right + 8f, r.bottom + 3f, t); c.drawText("4", r.right + 8f, r.top + 3f, t)
    }

    /** Time spent in each part of the field: darker = more time. */
    fun drawHeatmap(c: Canvas, r: RectF, points: List<GpsReferenceService.GpsTrackPoint>, color: Int) {
        if (points.isEmpty()) return
        val cols = 36; val rows = (cols * widthM / lengthM).toInt().coerceIn(10, 30)
        val density = Array(cols) { IntArray(rows) }; var max = 1
        for (p in points) {
            if (p.fieldX < -0.03f || p.fieldX > 1.03f || p.fieldY < -0.03f || p.fieldY > 1.03f) continue
            val cx = (p.fieldX * cols).toInt().coerceIn(0, cols - 1); val cy = (p.fieldY * rows).toInt().coerceIn(0, rows - 1)
            density[cx][cy]++; if (density[cx][cy] > max) max = density[cx][cy]
        }
        val cw = r.width() / cols; val ch = r.height() / rows
        val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        for (x in 0 until cols) for (y in 0 until rows) {
            val d = density[x][y]; if (d == 0) continue
            paint.color = Color.argb((40 + 175f * d / max).toInt().coerceAtMost(215), Color.red(color), Color.green(color), Color.blue(color))
            c.drawRect(r.left + x * cw, r.top + y * ch, r.left + (x + 1) * cw, r.top + (y + 1) * ch, paint)
        }
    }

    /** Draws event symbols with the minute underneath. Returns events that have no field position. */
    fun drawMarkers(c: Canvas, r: RectF, markers: List<EventMarkers.Marker>, size: Float = 7.5f): List<MatchEvent> {
        val notPlaced = mutableListOf<MatchEvent>()
        val minute = Paint().apply { color = label; textSize = 6.5f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        markers.forEach { m ->
            val g = m.event.gpsLocation
            if (g == null || (g.fieldX == 0f && g.fieldY == 0f)) { notPlaced.add(m.event); return@forEach }
            val x = r.left + g.fieldX.coerceIn(0f, 1f) * r.width()
            val y = r.top + g.fieldY.coerceIn(0f, 1f) * r.height()
            EventMarkers.draw(c, m, x, y, size)
            c.drawText(m.event.minuteLabel(), x, y + size + 7.5f, minute)
        }
        return notPlaced
    }
}
