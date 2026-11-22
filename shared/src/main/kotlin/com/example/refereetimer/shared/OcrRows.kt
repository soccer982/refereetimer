package com.example.refereetimer.shared

/**
 * NEW: puts text recognition back into printed rows.
 *
 * ML Kit returns a table as separate pieces (often a whole column at a time), so reading its plain
 * text gives all the numbers, then all the names. This regroups the pieces by their position:
 * pieces at the same height become one row, read left to right. If the page has two tables side by
 * side (home on the left, away on the right), each column is read on its own, left column first.
 *
 * Lines should already be straightened (see [deskew]) so a tilted photo still lines up.
 */
object OcrRows {

    fun rows(lines: List<OcrLine>): List<String> {
        val boxed = lines.filter { it.hasBox && it.text.isNotBlank() }
        if (boxed.isEmpty()) return lines.map { it.text }
        return splitColumns(boxed).flatMap { groupRows(it) }
    }

    private fun groupRows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val heights = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
        val h = heights[heights.size / 2].toFloat()
        val out = mutableListOf<MutableList<OcrLine>>()
        var rowCenter = 0f
        for (l in lines.sortedBy { (it.top + it.bottom) / 2 }) {
            val cy = (l.top + l.bottom) / 2f
            val row = out.lastOrNull()
            if (row != null && kotlin.math.abs(cy - rowCenter) <= h * 0.55f) {
                row += l
                rowCenter = row.map { (it.top + it.bottom) / 2f }.average().toFloat()
            } else {
                out += mutableListOf(l); rowCenter = cy
            }
        }
        return out.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /**
     * Finds a clear vertical gap down the middle third of the page that most lines don't cross.
     * Lines wider than half the page (titles, match info) are kept above both columns.
     */
    private fun splitColumns(lines: List<OcrLine>): List<List<OcrLine>> {
        val left = lines.minOf { it.left }; val right = lines.maxOf { it.right }
        val width = right - left
        if (width <= 0 || lines.size < 8) return listOf(lines)
        val narrow = lines.filter { it.right - it.left < width * 0.5 }
        val buckets = 60
        val cover = IntArray(buckets)
        narrow.forEach { l ->
            val a = ((l.left - left) * buckets / width).coerceIn(0, buckets - 1)
            val b = ((l.right - left) * buckets / width).coerceIn(0, buckets - 1)
            for (k in a..b) cover[k]++
        }
        // Widest run of empty buckets inside the middle third
        var best = -1; var bestLen = 0; var k = buckets / 3
        while (k < buckets * 2 / 3) {
            if (cover[k] == 0) {
                var e = k; while (e < buckets && cover[e] == 0) e++
                if (e - k > bestLen) { bestLen = e - k; best = k }
                k = e
            } else k++
        }
        if (best < 0) return listOf(lines)
        val splitX = left + (best + bestLen / 2f) * width / buckets
        val leftCol = narrow.filter { it.centerX < splitX }
        val rightCol = narrow.filter { it.centerX >= splitX }
        // Both sides need real content (a table each), or it's just a page with a margin
        if (leftCol.size < 4 || rightCol.size < 4) return listOf(lines)
        val wide = lines.filter { it.right - it.left >= width * 0.5 }
        val firstColumnTop = minOf(leftCol.minOf { it.top }, rightCol.minOf { it.top })
        val header = wide.filter { it.bottom <= firstColumnTop }
        val footer = wide - header.toSet()
        return listOf(header, leftCol, rightCol, footer).filter { it.isNotEmpty() }
    }

    /**
     * Rotates boxes by -[angleDegrees] around the origin so rows of a tilted photo become level.
     * [angleDegrees] is the typical text angle (from each line's corner points).
     */
    fun deskew(lines: List<OcrLine>, angleDegrees: Double): List<OcrLine> {
        if (kotlin.math.abs(angleDegrees) < 0.5) return lines
        val r = Math.toRadians(-angleDegrees)
        val c = kotlin.math.cos(r); val s = kotlin.math.sin(r)
        return lines.map { l ->
            val cx = (l.left + l.right) / 2.0; val cy = (l.top + l.bottom) / 2.0
            val nx = cx * c - cy * s; val ny = cx * s + cy * c
            val hw = (l.right - l.left) / 2.0; val hh = (l.bottom - l.top) / 2.0
            l.copy(left = (nx - hw).toInt(), right = (nx + hw).toInt(), top = (ny - hh).toInt(), bottom = (ny + hh).toInt())
        }
    }
}
