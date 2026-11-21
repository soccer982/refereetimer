package com.example.refereetimer.phone

import android.content.Context
import android.graphics.*
import com.example.refereetimer.shared.models.MatchEvent
import com.example.refereetimer.shared.models.MatchEvent.CardType
import com.example.refereetimer.shared.models.MatchEvent.EventType

class FieldDiagramRenderer(private val context: Context) {
    private val WIDTH = 1200; private val HEIGHT = 800; private val MARGIN = 60f
    private val fieldLeft = MARGIN; private val fieldTop = MARGIN
    private val fieldRight = WIDTH - MARGIN; private val fieldBottom = HEIGHT - MARGIN
    private val fieldWidth = fieldRight - fieldLeft; private val fieldHeight = fieldBottom - fieldTop
    private val PA_LENGTH = 16.5f / 105f; private val PA_WIDTH = 40.32f / 68f
    private val GA_LENGTH = 5.5f / 105f;  private val GA_WIDTH  = 18.32f / 68f
    private val PERIOD_COLORS = listOf(Color.argb(200,33,150,243), Color.argb(200,76,175,80), Color.argb(200,255,152,0), Color.argb(200,156,39,176))

    fun renderPeriod(period: Int, trackPoints: List<GpsReferenceService.GpsTrackPoint>, events: List<MatchEvent>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        drawBackground(canvas); drawFieldMarkings(canvas)
        drawHeatmap(canvas, trackPoints, PERIOD_COLORS[(period-1).coerceIn(0, PERIOD_COLORS.size-1)])
        drawEventPins(canvas, events); drawLabel(canvas, "Period $period"); return bitmap
    }

    fun renderFullMatch(trackPoints: List<GpsReferenceService.GpsTrackPoint>, events: List<MatchEvent>, totalPeriods: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        drawBackground(canvas); drawFieldMarkings(canvas)
        for (p in 1..totalPeriods) drawHeatmap(canvas, trackPoints.filter { it.period == p }, PERIOD_COLORS[(p-1).coerceIn(0, PERIOD_COLORS.size-1)])
        drawEventPins(canvas, events); drawLabel(canvas, "Full Match"); return bitmap
    }

    private fun drawBackground(canvas: Canvas) { canvas.drawColor(Color.parseColor("#1B5E20")) }

    private fun drawFieldMarkings(canvas: Canvas) {
        val lp = Paint().apply { color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
        val sp = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRect(fieldLeft, fieldTop, fieldRight, fieldBottom, lp)
        val midY = fieldTop + fieldHeight/2f; canvas.drawLine(fieldLeft, midY, fieldRight, midY, lp)
        val cR = (9.15f/105f) * fieldWidth; canvas.drawCircle(fieldLeft+fieldWidth/2f, fieldTop+fieldHeight/2f, cR, lp)
        canvas.drawCircle(fieldLeft+fieldWidth/2f, fieldTop+fieldHeight/2f, 5f, sp)
        canvas.drawRect(RectF(fieldLeft+fieldWidth*((1-PA_WIDTH)/2f), fieldTop, fieldLeft+fieldWidth*((1+PA_WIDTH)/2f), fieldTop+fieldHeight*PA_LENGTH), lp)
        canvas.drawRect(RectF(fieldLeft+fieldWidth*((1-PA_WIDTH)/2f), fieldBottom-fieldHeight*PA_LENGTH, fieldLeft+fieldWidth*((1+PA_WIDTH)/2f), fieldBottom), lp)
        canvas.drawRect(RectF(fieldLeft+fieldWidth*((1-GA_WIDTH)/2f), fieldTop, fieldLeft+fieldWidth*((1+GA_WIDTH)/2f), fieldTop+fieldHeight*GA_LENGTH), lp)
        canvas.drawRect(RectF(fieldLeft+fieldWidth*((1-GA_WIDTH)/2f), fieldBottom-fieldHeight*GA_LENGTH, fieldLeft+fieldWidth*((1+GA_WIDTH)/2f), fieldBottom), lp)
        val psDist = 11f/105f
        canvas.drawCircle(fieldLeft+fieldWidth/2f, fieldTop+fieldHeight*psDist, 4f, sp)
        canvas.drawCircle(fieldLeft+fieldWidth/2f, fieldBottom-fieldHeight*psDist, 4f, sp)
    }

    private fun drawHeatmap(canvas: Canvas, trackPoints: List<GpsReferenceService.GpsTrackPoint>, baseColor: Int) {
        if (trackPoints.isEmpty()) return
        val gridW = 50; val gridH = 33; val density = Array(gridW) { IntArray(gridH) }; var maxDensity = 1
        for (p in trackPoints) {
            val gx = (p.fieldX*(gridW-1)).toInt().coerceIn(0,gridW-1); val gy = (p.fieldY*(gridH-1)).toInt().coerceIn(0,gridH-1)
            density[gx][gy]++; if (density[gx][gy] > maxDensity) maxDensity = density[gx][gy]
        }
        val cellW = fieldWidth/gridW; val cellH = fieldHeight/gridH
        val r = Color.red(baseColor); val g = Color.green(baseColor); val b = Color.blue(baseColor)
        val hp = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        for (gx in 0 until gridW) for (gy in 0 until gridH) {
            val d = density[gx][gy]; if (d == 0) continue
            hp.color = Color.argb((d.toFloat()/maxDensity*180).toInt().coerceIn(20,200), r, g, b)
            val l = fieldLeft+gx*cellW; val t = fieldTop+gy*cellH
            canvas.drawRoundRect(RectF(l, t, l+cellW, t+cellH), 4f, 4f, hp)
        }
    }

    private fun drawEventPins(canvas: Canvas, events: List<MatchEvent>) {
        val tp = Paint().apply { textSize = 22f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val lp = Paint().apply { color = Color.WHITE; textSize = 14f; isAntiAlias = true; textAlign = Paint.Align.CENTER; setShadowLayer(3f,1f,1f,Color.BLACK) }
        val bp = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        for (event in events) {
            val gps = event.gpsLocation ?: continue
            val px = fieldLeft + gps.fieldX * fieldWidth; val py = fieldTop + gps.fieldY * fieldHeight
            when (event.type) {
                EventType.GOAL -> { bp.color = if (event.isOwnGoal) Color.parseColor("#FF5722") else Color.parseColor("#4CAF50"); canvas.drawCircle(px,py,14f,bp); tp.color=Color.WHITE; canvas.drawText("⚽",px,py+8f,tp); canvas.drawText(event.minuteLabel(),px,py+26f,lp) }
                EventType.FOUL -> { bp.color = if (event.foulType==MatchEvent.FoulType.DFK) Color.parseColor("#FF9800") else Color.parseColor("#9C27B0"); val tri=Path().apply{moveTo(px,py-14f);lineTo(px+12f,py+10f);lineTo(px-12f,py+10f);close()}; canvas.drawPath(tri,bp); canvas.drawText("${event.foulType?.name?:""}",px,py+26f,lp); canvas.drawText(event.minuteLabel(),px,py+40f,lp) }
                EventType.CARD -> { bp.color = if (event.cardType==CardType.RED) Color.parseColor("#F44336") else Color.parseColor("#FFEB3B"); canvas.drawRoundRect(RectF(px-8f,py-14f,px+8f,py+14f),3f,3f,bp); lp.color=if(event.cardType==CardType.RED)Color.WHITE else Color.BLACK; canvas.drawText("#${event.player.jerseyNumber}",px,py+5f,lp); lp.color=Color.WHITE; canvas.drawText(event.minuteLabel(),px,py+30f,lp) }
                EventType.SUBSTITUTION -> { /* Do nothing on the map for subs */ }
                EventType.KFTM_KICK -> {
                    bp.color = if (event.kftmResult == MatchEvent.KftmResult.SCORED) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
                    canvas.drawCircle(px, py, 14f, bp)
                    tp.color = Color.WHITE
                    val icon = if (event.kftmResult == MatchEvent.KftmResult.SCORED) "✅" else "❌"
                    canvas.drawText(icon, px, py + 8f, tp)
                }
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String) {
        val p = Paint().apply { color=Color.WHITE; textSize=28f; typeface=Typeface.DEFAULT_BOLD; alpha=180; setShadowLayer(4f,2f,2f,Color.BLACK) }
        canvas.drawText(text, fieldLeft+10f, fieldTop-10f, p)
    }

    fun applyHomography(lat: Double, lng: Double, matrix: FloatArray): Pair<Float,Float> {
        val x=lat.toFloat(); val y=lng.toFloat()
        return Pair((matrix[0]*x+matrix[1]*y+matrix[2]).coerceIn(0f,1f), (matrix[3]*x+matrix[4]*y+matrix[5]).coerceIn(0f,1f))
    }
}
