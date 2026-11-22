package com.example.refereetimer.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NEW: crop a game-card photo into its HOME and AWAY sections.
 *
 * Step 1: drag a box around the home team's section (the "H" heading, coaches and player table).
 * Step 2: the same for the away team. Either step can be skipped, and "Whole card" lets the app
 * find both sections itself. Boxes are returned as fractions of the (rotated) picture so the
 * caller can cut them out of a full-resolution copy.
 */
class RosterCropActivity : AppCompatActivity() {

    private lateinit var cropView: CropBoxView
    private lateinit var txtStep: TextView
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnBack: MaterialButton

    private var original: Bitmap? = null
    private var rotation = 0
    private var step = 0                       // 0 = home, 1 = away
    private val boxes = arrayOfNulls<RectF>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rotation = savedInstanceState?.getInt(KEY_ROTATION) ?: 0
        buildUi()
        val uri = intent.data ?: run { finish(); return }
        lifecycleScope.launch {
            val bmp = try { withContext(Dispatchers.IO) { decode(this@RosterCropActivity, uri, DISPLAY_MAX_SIDE) } }
            catch (e: Exception) { null }
            if (bmp == null) { setResult(Activity.RESULT_CANCELED); finish(); return@launch }
            original = bmp
            showRotated()
            updateStep()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ROTATION, rotation)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(text: String, color: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text; isAllCaps = false
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        txtStep = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 0, 0, dp(4))
        }
        val hint = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E")); textSize = 12f
            text = "Drag across the section to draw a box. Drag the corners to adjust, or drag inside to move it."
            setPadding(0, 0, 0, dp(8))
        }
        cropView = CropBoxView(this).apply { onBoxChanged = { btnNext.isEnabled = hasBox } }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        tools.addView(button("⟳ Rotate", "#424242") { rotate() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        tools.addView(button("Whole card (auto)", "#424242") { finishWith(auto = true) },
            LinearLayout.LayoutParams(0, -2, 1.4f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnBack = button("← Back", "#424242") { goBack() }
        btnSkip = button("Skip", "#424242") { boxes[step] = null; advance() }
        btnNext = button("Next →", "#2E7D32") { boxes[step] = cropView.normalizedBox(); advance() }
        nav.addView(btnBack, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        nav.addView(btnSkip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        nav.addView(btnNext, LinearLayout.LayoutParams(0, -2, 1.3f))

        root.addView(txtStep); root.addView(hint)
        root.addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun updateStep() {
        val home = step == 0
        txtStep.text = if (home) "Step 1 of 2: box the HOME team (H)" else "Step 2 of 2: box the AWAY team (A)"
        txtStep.setTextColor(Color.parseColor(if (home) "#64B5F6" else "#EF9A9A"))
        cropView.boxColor = Color.parseColor(if (home) "#1E88E5" else "#E53935")
        cropView.ghostBox = if (home) null else boxes[0]
        cropView.setNormalizedBox(boxes[step])
        btnBack.isEnabled = !home
        btnNext.text = if (home) "Next →" else "Done ✓"
        btnNext.isEnabled = cropView.hasBox
    }

    private fun advance() {
        if (step == 0) { step = 1; updateStep() } else finishWith(auto = false)
    }

    private fun goBack() { if (step == 1) { boxes[1] = cropView.normalizedBox(); step = 0; updateStep() } }

    private fun rotate() {
        rotation = (rotation + 90) % 360
        boxes[0] = null; boxes[1] = null; step = 0
        showRotated(); updateStep()
    }

    private fun showRotated() {
        val bmp = original ?: return
        cropView.setBitmap(rotate(bmp, rotation))
    }

    private fun finishWith(auto: Boolean) {
        val data = Intent().apply {
            putExtra(EXTRA_ROTATION, rotation)
            if (!auto) {
                boxes[0]?.let { putExtra(EXTRA_HOME_BOX, floatArrayOf(it.left, it.top, it.right, it.bottom)) }
                boxes[1]?.let { putExtra(EXTRA_AWAY_BOX, floatArrayOf(it.left, it.top, it.right, it.bottom)) }
            }
            putExtra(EXTRA_AUTO, auto || (boxes[0] == null && boxes[1] == null))
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    companion object {
        const val EXTRA_HOME_BOX = "home_box"
        const val EXTRA_AWAY_BOX = "away_box"
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_AUTO = "auto"
        private const val KEY_ROTATION = "rotation"
        private const val DISPLAY_MAX_SIDE = 1600

        fun intent(context: Context, uri: Uri) = Intent(context, RosterCropActivity::class.java).setData(uri)

        /** Loads a picture scaled so its longest side is at most [maxSide] (EXIF rotation applied). */
        fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                }
            }
        }

        fun rotate(bmp: Bitmap, degrees: Int): Bitmap =
            if (degrees % 360 == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

        /** Cuts a box (fractions 0–1 of the picture) out of [bmp], with a small margin so edge text isn't clipped. */
        fun crop(bmp: Bitmap, box: FloatArray): Bitmap {
            val pad = 0.01f
            val l = ((box[0] - pad).coerceIn(0f, 1f) * bmp.width).toInt()
            val t = ((box[1] - pad).coerceIn(0f, 1f) * bmp.height).toInt()
            val r = ((box[2] + pad).coerceIn(0f, 1f) * bmp.width).toInt()
            val b = ((box[3] + pad).coerceIn(0f, 1f) * bmp.height).toInt()
            if (r - l < 10 || b - t < 10) return bmp
            return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        }
    }
}

/** Shows a picture and lets you draw / move / resize one rectangle on it. */
class CropBoxView(context: Context) : View(context) {

    var boxColor: Int = Color.parseColor("#1E88E5")
        set(v) { field = v; invalidate() }
    /** The other team's box, drawn faintly for reference (fractions 0–1). */
    var ghostBox: RectF? = null
        set(v) { field = v; invalidate() }
    var onBoxChanged: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private var box: RectF? = null              // view coordinates
    val hasBox: Boolean get() = box?.let { it.width() > minSize && it.height() > minSize } == true

    private val density = resources.displayMetrics.density
    private val handleRadius = 22 * density
    private val minSize = 24 * density

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.argb(160, 255, 255, 255)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var pendingNormalized: RectF? = null

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp; box = null
        layoutImage(); invalidate()
    }

    fun setNormalizedBox(r: RectF?) {
        if (imageRect.isEmpty) { pendingNormalized = r; box = null; return }
        box = r?.let { RectF(toViewX(it.left), toViewY(it.top), toViewX(it.right), toViewY(it.bottom)) }
        invalidate(); onBoxChanged?.invoke()
    }

    fun normalizedBox(): RectF? {
        val b = box ?: return null
        if (imageRect.isEmpty) return null
        return RectF(
            ((b.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((b.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
            ((b.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((b.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        )
    }

    private fun toViewX(f: Float) = imageRect.left + f * imageRect.width()
    private fun toViewY(f: Float) = imageRect.top + f * imageRect.height()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val keep = normalizedBox() ?: pendingNormalized
        layoutImage()
        pendingNormalized = null
        setNormalizedBox(keep)
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale; val h = bmp.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        pendingNormalized?.let { p -> pendingNormalized = null; setNormalizedBox(p) }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)
        ghostBox?.let { g ->
            canvas.drawRect(toViewX(g.left), toViewY(g.top), toViewX(g.right), toViewY(g.bottom), ghostPaint)
        }
        val b = box ?: return
        // Dim everything outside the box
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, b.top, dimPaint)
        canvas.drawRect(imageRect.left, b.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, b.top, b.left, b.bottom, dimPaint)
        canvas.drawRect(b.right, b.top, imageRect.right, b.bottom, dimPaint)
        boxPaint.color = boxColor; handlePaint.color = boxColor
        canvas.drawRect(b, boxPaint)
        for ((x, y) in corners(b)) canvas.drawCircle(x, y, 9 * density, handlePaint)
    }

    private fun corners(b: RectF) = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)

    private enum class Drag { NONE, NEW, MOVE, CORNER }
    private var drag = Drag.NONE
    private var corner = 0
    private var startX = 0f; private var startY = 0f
    private val startBox = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        val x = e.x.coerceIn(imageRect.left, imageRect.right)
        val y = e.y.coerceIn(imageRect.top, imageRect.bottom)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = x; startY = y
                val b = box
                val hit = b?.let { corners(it).indexOfFirst { (cx, cy) -> Math.hypot((cx - e.x).toDouble(), (cy - e.y).toDouble()) < handleRadius } } ?: -1
                drag = when {
                    b != null && hit >= 0 -> { corner = hit; Drag.CORNER }
                    b != null && b.contains(e.x, e.y) -> Drag.MOVE
                    else -> Drag.NEW
                }
                b?.let { startBox.set(it) }
                if (drag == Drag.NEW) box = RectF(x, y, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val b = box ?: return true
                when (drag) {
                    Drag.NEW -> b.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                    Drag.MOVE -> {
                        val dx = (x - startX).coerceIn(imageRect.left - startBox.left, imageRect.right - startBox.right)
                        val dy = (y - startY).coerceIn(imageRect.top - startBox.top, imageRect.bottom - startBox.bottom)
                        b.set(startBox); b.offset(dx, dy)
                    }
                    Drag.CORNER -> {
                        val l = if (corner == 0 || corner == 2) minOf(x, startBox.right - minSize) else startBox.left
                        val r = if (corner == 1 || corner == 3) maxOf(x, startBox.left + minSize) else startBox.right
                        val t = if (corner == 0 || corner == 1) minOf(y, startBox.bottom - minSize) else startBox.top
                        val bt = if (corner == 2 || corner == 3) maxOf(y, startBox.top + minSize) else startBox.bottom
                        b.set(l, t, r, bt)
                    }
                    Drag.NONE -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.NEW && !hasBox) box = null
                drag = Drag.NONE
                invalidate(); onBoxChanged?.invoke()
            }
        }
        return true
    }
}
