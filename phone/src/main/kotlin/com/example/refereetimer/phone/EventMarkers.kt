package com.example.refereetimer.phone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.refereetimer.shared.MisconductCodes
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType
import com.example.refereetimer.shared.models.MatchEvent.FoulType

/**
 * NEW: the symbol key used on every field diagram (report pages and the US Soccer Supplemental).
 *
 *   SHAPE  = team:   ● circle = HOME      ■ square = AWAY
 *   COLOUR = event:  yellow = caution (YC), red = send-off (RC), green = goal,
 *                    orange = DFK foul, purple = IFK foul, dark orange = PK, blue = injury
 *   The player's number is always inside the shape.
 *   Second caution:  the FIRST YC shows "7*"; the SECOND YC is yellow with a red slash
 *                    (the slash is the red card, so the separate 2CT red isn't drawn again).
 */
object EventMarkers {

    enum class Kind(val fill: Int, val text: Int, val label: String) {
        YELLOW(Color.parseColor("#FDD835"), Color.BLACK, "Caution (YC)"),
        SECOND_YELLOW(Color.parseColor("#FDD835"), Color.BLACK, "2nd YC → send-off"),
        RED(Color.parseColor("#E53935"), Color.WHITE, "Send-off (RC)"),
        GOAL(Color.parseColor("#43A047"), Color.WHITE, "Goal"),
        DFK(Color.parseColor("#FB8C00"), Color.BLACK, "Foul: DFK"),
        IFK(Color.parseColor("#8E24AA"), Color.WHITE, "Foul: IFK"),
        PK(Color.parseColor("#E65100"), Color.WHITE, "Penalty kick"),
        INJURY(Color.parseColor("#1E88E5"), Color.WHITE, "Injury")
    }

    data class Marker(val event: MatchEvent, val kind: Kind, val label: String) {
        val isHome: Boolean get() = event.team.isHome
    }

    /** Turns events into markers, applying the second-caution rule. Events without a location are skipped later. */
    fun build(events: List<MatchEvent>): List<Marker> {
        val secondYellows = mutableSetOf<String>()
        val starred = mutableSetOf<String>()
        events.filter { it.type == EventType.CARD && it.cardType == CardType.RED &&
                        it.misconductCode in MisconductCodes.SECOND_CAUTION_CODES }
            .forEach { red ->
                val yellows = events.filter {
                    it.type == EventType.CARD && it.cardType == CardType.YELLOW &&
                    it.team.isHome == red.team.isHome && it.player.jerseyNumber == red.player.jerseyNumber &&
                    it.timestamp <= red.timestamp + 5_000
                }.sortedBy { it.timestamp }
                yellows.lastOrNull()?.let { secondYellows.add(it.id) }
                yellows.dropLast(1).forEach { starred.add(it.id) }
            }

        return events.mapNotNull { e ->
            val n = e.player.shortTag
            when (e.type) {
                EventType.CARD -> when {
                    e.cardType == CardType.RED && e.misconductCode in MisconductCodes.SECOND_CAUTION_CODES -> null
                    e.cardType == CardType.RED -> Marker(e, Kind.RED, n)
                    e.id in secondYellows -> Marker(e, Kind.SECOND_YELLOW, n)
                    else -> Marker(e, Kind.YELLOW, if (e.id in starred) "$n*" else n)
                }
                EventType.GOAL -> Marker(e, Kind.GOAL, n)
                EventType.FOUL -> Marker(e, if (e.isPK) Kind.PK else if (e.foulType == FoulType.IFK) Kind.IFK else Kind.DFK, n)
                EventType.INJURY -> Marker(e, Kind.INJURY, n)
                else -> null
            }
        }
    }

    /** Draws one marker centred at (cx, cy); [r] is the half-size of the shape. */
    fun draw(canvas: Canvas, m: Marker, cx: Float, cy: Float, r: Float) {
        val fill = Paint().apply { color = m.kind.fill; style = Paint.Style.FILL; isAntiAlias = true }
        val edge = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = r * 0.14f; isAntiAlias = true }
        if (m.isHome) {
            canvas.drawCircle(cx, cy, r, fill); canvas.drawCircle(cx, cy, r, edge)
        } else {
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawRect(box, fill); canvas.drawRect(box, edge)
        }
        // Number inside (shrinks for "10*" style labels)
        val size = r * when (m.label.length) { 1 -> 1.25f; 2 -> 1.05f; else -> 0.85f }
        val t = Paint().apply {
            color = m.kind.text; textSize = size; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(m.label, cx, cy + size * 0.36f, t)
        // Second caution: red slash = the red card
        if (m.kind == Kind.SECOND_YELLOW) {
            val slash = Paint().apply { color = Color.parseColor("#D50000"); strokeWidth = r * 0.32f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
            canvas.drawLine(cx - r * 0.95f, cy + r * 0.95f, cx + r * 0.95f, cy - r * 0.95f, slash)
        }
    }

    /**
     * Draws the key in rows starting at (x, y) within [width]. Returns the height used.
     * [kinds] limits the key to the event types that appear (all if null).
     */
    fun drawKey(canvas: Canvas, x: Float, y: Float, width: Float, r: Float, textColor: Int, kinds: Set<Kind>? = null): Float {
        val label = Paint().apply { color = textColor; textSize = r * 1.5f; isAntiAlias = true }
        val sample = MatchEvent(type = EventType.GOAL, team = com.example.refereetimer.shared.models.Team("", emptyList(), emptyList(), true),
            player = com.example.refereetimer.shared.models.Player(0, ""), minute = 0, period = 1, elapsedMillis = 0)
        val items = mutableListOf<Pair<Marker, String>>()
        items.add(Marker(sample, Kind.GOAL, "") to "Home")
        items.add(Marker(sample.copy(team = sample.team.copy(isHome = false)), Kind.GOAL, "") to "Away")
        Kind.values().filter { kinds == null || it in kinds }.forEach { k ->
            items.add(Marker(sample, k, if (k == Kind.YELLOW && kinds?.contains(Kind.SECOND_YELLOW) == true) "7*" else "7") to k.label)
        }
        var cx = x; var cy = y + r
        val gap = r * 1.2f
        items.forEach { (m, text) ->
            val shapeOnly = m.label.isEmpty()
            val itemW = r * 2 + r * 0.6f + label.measureText(text) + gap
            if (cx + itemW > x + width && cx > x) { cx = x; cy += r * 2.8f }
            if (shapeOnly) {
                // Team shapes drawn hollow so they read as "shape = team"
                val edge = Paint().apply { color = textColor; style = Paint.Style.STROKE; strokeWidth = r * 0.18f; isAntiAlias = true }
                if (m.isHome) canvas.drawCircle(cx + r, cy, r, edge) else canvas.drawRect(cx, cy - r, cx + 2 * r, cy + r, edge)
            } else {
                draw(canvas, m, cx + r, cy, r)
            }
            canvas.drawText(text, cx + 2 * r + r * 0.6f, cy + r * 0.5f, label)
            cx += itemW
        }
        return cy + r - y
    }

    /** Kinds present in a marker list (for a compact key). */
    fun kindsIn(markers: List<Marker>): Set<Kind> = markers.map { it.kind }.toSet()
}
