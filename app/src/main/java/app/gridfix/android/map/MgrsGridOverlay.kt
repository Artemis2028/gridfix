package app.gridfix.android.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import app.gridfix.android.coords.Coordinates
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the MGRS grid on the map: grid-zone (GZD) boundaries, 100 km squares with
 * their letter pairs, and 10 km / 1 km / 100 m / 10 m lines with principal-digit
 * labels along the screen edges. Line density adapts to zoom. Also paints the
 * basemap attribution line, which stays visible even with the grid switched off.
 *
 * Projection math lives in [Coordinates] (UTM forward/inverse validated to < 1 cm
 * everywhere grid lines are drawn). The map is always north-up, so GZD meridians
 * and parallels are straight screen lines, while UTM grid lines are sampled along
 * their length and drawn as paths so they curve correctly near zone edges.
 */
class MgrsGridOverlay(private val density: Float) : Overlay() {

    var gridEnabled = true
    var nightMode = false
    var lightLines = false          // true over satellite imagery
    var attribution = ""
    var bottomInsetPx = 0f          // height of the compose readout bar over the map bottom
    var mapOrientation = 0f         // degrees; edge labels only make sense north-up
    var onIntervalLabel: ((String) -> Unit)? = null

    private val northUp: Boolean get() = mapOrientation > -0.5f && mapOrientation < 0.5f

    private val gzdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val heavyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * density
    }
    private val finePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
    }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)
    private val path = Path()
    private val squareCache = HashMap<String, String>()
    private var lastIntervalLabel: String? = null

    // Per-line label-detection state (see addPoint)
    private var lastX = 0f
    private var lastY = 0f
    private var haveLast = false
    private var labelWanted = 0     // 0 none, 1 easting (horizontal guide), 2 northing (vertical guide)
    private var guideY = 0f
    private var guideX = 0f
    private var labelHit = Float.NaN

    private data class Cell(
        val zone: Int,
        val band: Int,
        val lonW: Double,
        val lonE: Double,
        val latS: Double,
        val latN: Double,
    ) {
        val north: Boolean get() = band >= 10
        val letter: Char get() = "CDEFGHJKLMNPQRSTUVWX"[band]
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val lineColor = when {
            nightMode -> Color.rgb(255, 59, 48)
            lightLines -> Color.WHITE
            else -> Color.rgb(20, 22, 26)
        }
        val haloColor = if (nightMode || lightLines) Color.BLACK else Color.WHITE
        val gzdColor = if (nightMode) Color.rgb(255, 59, 48) else Color.rgb(224, 180, 88)
        gzdPaint.color = gzdColor
        gzdPaint.alpha = 215
        heavyPaint.color = lineColor
        heavyPaint.alpha = 195
        finePaint.color = lineColor
        finePaint.alpha = 150

        drawAttribution(canvas, lineColor, haloColor)

        if (!gridEnabled) {
            emitInterval("")
            return
        }

        val bbox = projection.boundingBox
        val latN = min(bbox.latNorth, 84.0)
        val latS = max(bbox.latSouth, -80.0)
        val w = canvas.width
        if (latN <= latS || w <= 0) {
            emitInterval("")
            return
        }
        val centerLat = (bbox.latNorth + bbox.latSouth) / 2.0
        var lonSpan = bbox.lonEast - bbox.lonWest
        if (lonSpan <= 0.0) lonSpan += 360.0
        val mpp = lonSpan * 111319.49 * cos(Math.toRadians(centerLat)) / w
        val minSpacing = 48f * density
        val interval = intArrayOf(10, 100, 1000, 10000, 100000)
            .firstOrNull { it / mpp >= minSpacing } ?: 0
        emitInterval(
            when (interval) {
                0 -> "GZD"
                100000 -> "100 km"
                10000 -> "10 km"
                1000 -> "1 km"
                100 -> "100 m"
                else -> "10 m"
            }
        )

        val ranges = if (bbox.lonWest <= bbox.lonEast) {
            listOf(bbox.lonWest to bbox.lonEast)
        } else {
            listOf(bbox.lonWest to 180.0, -180.0 to bbox.lonEast)
        }
        for ((rw, re) in ranges) {
            val cells = cellsInView(latS, latN, rw, re)
            if (cells.size > 200) {
                drawCoarseGzd(canvas, projection, rw, re, latS, latN)
            } else {
                if (interval > 0) {
                    for (cell in cells) {
                        drawCellGrid(canvas, projection, cell, rw, re, latS, latN, interval, lineColor, haloColor)
                    }
                }
                for (cell in cells) {
                    drawGzdEdgesAndLabel(canvas, projection, cell, rw, re, latS, latN, gzdColor, haloColor)
                }
            }
        }
    }

    private fun emitInterval(label: String) {
        if (label != lastIntervalLabel) {
            lastIntervalLabel = label
            onIntervalLabel?.invoke(label)
        }
    }

    /** GZD cells (with the Norway/Svalbard exceptions) intersecting the view. */
    private fun cellsInView(latS: Double, latN: Double, lonW: Double, lonE: Double): List<Cell> {
        val out = ArrayList<Cell>()
        val bS = floor((max(latS, -80.0) + 80.0) / 8.0).toInt().coerceIn(0, 19)
        val bN = floor((min(latN, 83.999) + 80.0) / 8.0).toInt().coerceIn(0, 19)
        for (b in bS..bN) {
            val cs = -80.0 + 8.0 * b
            val cn = if (b == 19) 84.0 else cs + 8.0
            for (z in 1..60) {
                var cw = (z - 1) * 6.0 - 180.0
                var ce = cw + 6.0
                if (b == 17) {                       // band V: Norway
                    if (z == 31) ce = 3.0
                    if (z == 32) cw = 3.0
                }
                if (b == 19 && z in 31..37) {        // band X: Svalbard
                    when (z) {
                        31 -> { cw = 0.0; ce = 9.0 }
                        33 -> { cw = 9.0; ce = 21.0 }
                        35 -> { cw = 21.0; ce = 33.0 }
                        37 -> { cw = 33.0; ce = 42.0 }
                        else -> continue             // 32X, 34X, 36X do not exist
                    }
                }
                if (ce < lonW || cw > lonE) continue
                out.add(Cell(z, b, cw, ce, cs, cn))
            }
        }
        return out
    }

    /** World-zoom fallback: plain 6-degree / 8-degree lattice, no per-cell detail. */
    private fun drawCoarseGzd(canvas: Canvas, proj: Projection, lonW: Double, lonE: Double, latS: Double, latN: Double) {
        var lon = floor(lonW / 6.0) * 6.0
        while (lon <= lonE + 1e-9) {
            gp.setCoords(0.0, lon.coerceIn(-180.0, 180.0))
            proj.toPixels(gp, pt)
            canvas.drawLine(pt.x.toFloat(), 0f, pt.x.toFloat(), canvas.height.toFloat(), gzdPaint)
            lon += 6.0
        }
        var lat = -80.0
        while (lat <= 84.0 + 1e-9) {
            if (lat in latS..latN) {
                gp.setCoords(lat, (lonW + lonE) / 2.0)
                proj.toPixels(gp, pt)
                canvas.drawLine(0f, pt.y.toFloat(), canvas.width.toFloat(), pt.y.toFloat(), gzdPaint)
            }
            lat += if (lat >= 72.0) 12.0 else 8.0
        }
    }

    private fun drawGzdEdgesAndLabel(
        canvas: Canvas,
        proj: Projection,
        cell: Cell,
        rw: Double,
        re: Double,
        latS: Double,
        latN: Double,
        gzdColor: Int,
        haloColor: Int,
    ) {
        val cw = max(cell.lonW, rw)
        val ce = min(cell.lonE, re)
        val cs = max(cell.latS, latS)
        val cn = min(cell.latN, latN)
        if (cw >= ce || cs >= cn) return

        // North-up Mercator: meridians are vertical, parallels horizontal straight lines.
        gp.setCoords(cell.latS, cell.lonW)
        proj.toPixels(gp, pt)
        val xw = pt.x.toFloat()
        val ys = pt.y.toFloat()
        gp.setCoords(cell.latN, cell.lonE)
        proj.toPixels(gp, pt)
        val xe = pt.x.toFloat()
        val yn = pt.y.toFloat()
        canvas.drawLine(xw, ys, xw, yn, gzdPaint)
        canvas.drawLine(xe, ys, xe, yn, gzdPaint)
        canvas.drawLine(xw, ys, xe, ys, gzdPaint)
        canvas.drawLine(xw, yn, xe, yn, gzdPaint)

        // Label at the center of the visible part of the cell
        gp.setCoords((cs + cn) / 2.0, (cw + ce) / 2.0)
        proj.toPixels(gp, pt)
        val visibleW = abs(
            run {
                gp.setCoords(cs, ce); proj.toPixels(gp, pt).x.toFloat()
            } - run {
                gp.setCoords(cs, cw); proj.toPixels(gp, pt).x.toFloat()
            }
        )
        if (visibleW >= 72f * density) {
            gp.setCoords((cs + cn) / 2.0, (cw + ce) / 2.0)
            proj.toPixels(gp, pt)
            // The crosshair readout already names the zone under it; a label there would
            // sit on top of the crosshair. Zone boundaries in view still get theirs.
            val nearCrosshair = abs(pt.x - canvas.width / 2f) < 44f * density &&
                abs(pt.y - canvas.height / 2f) < 44f * density
            if (nearCrosshair) return
            val size = 15f * density
            textFill.textSize = size
            textHalo.textSize = size
            textFill.color = gzdColor
            textFill.alpha = 185
            textHalo.color = haloColor
            textHalo.alpha = 150
            val label = "${cell.zone}${cell.letter}"
            val tw = textFill.measureText(label)
            canvas.drawText(label, pt.x - tw / 2f, pt.y + size / 3f, textHalo)
            canvas.drawText(label, pt.x - tw / 2f, pt.y + size / 3f, textFill)
        }
    }

    private fun drawCellGrid(
        canvas: Canvas,
        proj: Projection,
        cell: Cell,
        rw: Double,
        re: Double,
        latS: Double,
        latN: Double,
        interval: Int,
        lineColor: Int,
        haloColor: Int,
    ) {
        val cw = max(cell.lonW, rw)
        val ce = min(cell.lonE, re)
        val cs = max(cell.latS, latS)
        val cn = min(cell.latN, latN)
        if (cw >= ce || cs >= cn) return
        val zone = cell.zone
        val north = cell.north

        // UTM bounds of the clipped cell from corner + edge-midpoint samples
        var eMin = Double.MAX_VALUE
        var eMax = -Double.MAX_VALUE
        var nMin = Double.MAX_VALUE
        var nMax = -Double.MAX_VALUE
        val midLat = (cs + cn) / 2.0
        val midLon = (cw + ce) / 2.0
        val corners = arrayOf(
            doubleArrayOf(cs, cw), doubleArrayOf(cs, ce), doubleArrayOf(cn, cw), doubleArrayOf(cn, ce),
            doubleArrayOf(cs, midLon), doubleArrayOf(cn, midLon), doubleArrayOf(midLat, cw), doubleArrayOf(midLat, ce),
        )
        for (s in corners) {
            val en = Coordinates.utmForZone(s[0], s[1], zone, north)
            eMin = min(eMin, en[0]); eMax = max(eMax, en[0])
            nMin = min(nMin, en[1]); nMax = max(nMax, en[1])
        }
        eMin = floor(eMin / interval) * interval
        nMin = floor(nMin / interval) * interval
        eMax += interval
        nMax += interval

        guideY = canvas.height - bottomInsetPx - 14f * density
        guideX = 10f * density

        if (interval < 100000) {
            gridPass(canvas, proj, cell, 100000, eMin, eMax, nMin, nMax, heavyPaint, 0, lineColor, haloColor, labeled = northUp)
            gridPass(canvas, proj, cell, interval, eMin, eMax, nMin, nMax, finePaint, 100000, lineColor, haloColor, labeled = northUp)
        } else {
            gridPass(canvas, proj, cell, 100000, eMin, eMax, nMin, nMax, heavyPaint, 0, lineColor, haloColor, labeled = false)
        }

        drawSquareLetters(canvas, proj, cell, cs, cn, cw, ce, eMin, eMax, nMin, nMax, lineColor, haloColor)
    }

    private fun drawSquareLetters(
        canvas: Canvas,
        proj: Projection,
        cell: Cell,
        cs: Double,
        cn: Double,
        cw: Double,
        ce: Double,
        eMin: Double,
        eMax: Double,
        nMin: Double,
        nMax: Double,
        lineColor: Int,
        haloColor: Int,
    ) {
        // On-screen width of 100 km at the cell's mid-latitude
        val midLat = (cs + cn) / 2.0
        val midLon = (cw + ce) / 2.0
        val en = Coordinates.utmForZone(midLat, midLon, cell.zone, cell.north)
        val ll = Coordinates.utmInverse(en[0] + 100000.0, en[1], cell.zone, cell.north)
        gp.setCoords(midLat, midLon)
        proj.toPixels(gp, pt)
        val x0 = pt.x
        gp.setCoords(ll[0], ll[1])
        proj.toPixels(gp, pt)
        if (abs(pt.x - x0) < 64f * density) return

        var e100 = floor(eMin / 100000.0) * 100000.0
        while (e100 <= eMax) {
            var n100 = floor(nMin / 100000.0) * 100000.0
            while (n100 <= nMax) {
                val c = Coordinates.utmInverse(e100 + 50000.0, n100 + 50000.0, cell.zone, cell.north)
                val insideCell = c[1] >= cell.lonW && c[1] <= cell.lonE &&
                    c[0] >= cell.latS && c[0] <= cell.latN
                if (insideCell) {
                    val key = "${cell.zone}${cell.letter}:${(e100 / 100000).toLong()}:${(n100 / 100000).toLong()}"
                    if (squareCache.size > 2048) squareCache.clear()
                    val letters = squareCache.getOrPut(key) {
                        Coordinates.mgrs(c[0], c[1], 4)?.square ?: ""
                    }
                    if (letters.isNotEmpty()) {
                        gp.setCoords(c[0], c[1])
                        proj.toPixels(gp, pt)
                        val size = 24f * density
                        textFill.textSize = size
                        textHalo.textSize = size
                        textFill.color = lineColor
                        textFill.alpha = 105
                        textHalo.color = haloColor
                        textHalo.alpha = 85
                        val tw = textFill.measureText(letters)
                        canvas.drawText(letters, pt.x - tw / 2f, pt.y + size / 3f, textHalo)
                        canvas.drawText(letters, pt.x - tw / 2f, pt.y + size / 3f, textFill)
                    }
                }
                n100 += 100000.0
            }
            e100 += 100000.0
        }
    }

    /**
     * One pass of grid lines at [interval]: vertical (constant easting) sampled along
     * northing, then horizontal (constant northing) sampled along easting, clipped at
     * the cell's meridians. Labeled passes drop principal digits where lines cross the
     * bottom (eastings) and left (northings) label guides.
     */
    private fun gridPass(
        canvas: Canvas,
        proj: Projection,
        cell: Cell,
        interval: Int,
        eMin: Double,
        eMax: Double,
        nMin: Double,
        nMax: Double,
        paint: Paint,
        skipMultiple: Int,
        lineColor: Int,
        haloColor: Int,
        labeled: Boolean,
    ) {
        val steps = 12
        val guard = 90

        // Vertical lines: constant easting.
        // The values come from the pure helper (and its tests) rather than from a
        // rounded walk off an unaligned start - see gridLineValues for what that cost.
        for (eL in gridLineValues(eMin, eMax, interval, guard)) {
            if (skipMultiple > 0 && eL % skipMultiple == 0L) continue
            beginLine(if (labeled) 1 else 0)
            var prevIn = false
            var prevLat = 0.0
            var prevLon = 0.0
            for (i in 0..steps) {
                val nn = nMin + (nMax - nMin) * i / steps
                val ll = Coordinates.utmInverse(eL.toDouble(), nn, cell.zone, cell.north)
                prevIn = clipStep(proj, cell, ll[0], ll[1], prevLat, prevLon, prevIn, i)
                prevLat = ll[0]
                prevLon = ll[1]
            }
            canvas.drawPath(path, paint)
            if (labeled && !labelHit.isNaN()) {
                drawPrincipal(canvas, eL, interval, labelHit, guideY + 4.5f * density, lineColor, haloColor, centered = true)
            }
        }

        // Horizontal lines: constant northing
        for (nL in gridLineValues(nMin, nMax, interval, guard)) {
            if (skipMultiple > 0 && nL % skipMultiple == 0L) continue
            beginLine(if (labeled) 2 else 0)
            var prevIn = false
            var prevLat = 0.0
            var prevLon = 0.0
            for (i in 0..steps) {
                val ee = eMin + (eMax - eMin) * i / steps
                val ll = Coordinates.utmInverse(ee, nL.toDouble(), cell.zone, cell.north)
                prevIn = clipStep(proj, cell, ll[0], ll[1], prevLat, prevLon, prevIn, i)
                prevLat = ll[0]
                prevLon = ll[1]
            }
            canvas.drawPath(path, paint)
            if (labeled && !labelHit.isNaN()) {
                drawPrincipal(canvas, nL, interval, guideX, labelHit - 5f * density, lineColor, haloColor, centered = false)
            }
        }
    }

    private fun beginLine(wantLabel: Int) {
        path.reset()
        haveLast = false
        labelWanted = wantLabel
        labelHit = Float.NaN
    }

    /**
     * Feed one sampled point of a grid line, clipping against the cell's meridians.
     * Returns whether this sample was inside the cell (becomes prevIn for the next step).
     */
    private fun clipStep(
        proj: Projection,
        cell: Cell,
        lat: Double,
        lon: Double,
        prevLat: Double,
        prevLon: Double,
        prevIn: Boolean,
        index: Int,
    ): Boolean {
        val inside = lon >= cell.lonW - 1e-9 && lon <= cell.lonE + 1e-9
        if (inside) {
            if (!prevIn && index > 0) {
                // entering the cell: start at the boundary crossing
                val bound = if (prevLon < cell.lonW) cell.lonW else cell.lonE
                val t = safeT(bound, prevLon, lon)
                addPoint(proj, prevLat + t * (lat - prevLat), bound, newSegment = true)
            }
            addPoint(proj, lat, lon, newSegment = !prevIn && index == 0 || !haveLast)
        } else if (prevIn) {
            // leaving the cell: end at the boundary crossing
            val bound = if (lon < cell.lonW) cell.lonW else cell.lonE
            val t = safeT(bound, prevLon, lon)
            addPoint(proj, prevLat + t * (lat - prevLat), bound, newSegment = false)
            haveLast = false
        }
        return inside
    }

    /** Project and append a point; detect label-guide crossings on drawn segments. */
    private fun addPoint(proj: Projection, lat: Double, lon: Double, newSegment: Boolean) {
        gp.setCoords(lat, lon)
        proj.toPixels(gp, pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        if (newSegment || !haveLast) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
            if (labelHit.isNaN()) {
                when (labelWanted) {
                    1 -> if ((lastY - guideY) * (y - guideY) <= 0f && lastY != y) {
                        val t = (guideY - lastY) / (y - lastY)
                        labelHit = lastX + t * (x - lastX)
                    }
                    2 -> if ((lastX - guideX) * (x - guideX) <= 0f && lastX != x) {
                        val t = (guideX - lastX) / (x - lastX)
                        labelHit = lastY + t * (y - lastY)
                    }
                }
            }
        }
        lastX = x
        lastY = y
        haveLast = true
    }

    /** Principal-digit label for a grid line value at the given interval. */
    private fun drawPrincipal(
        canvas: Canvas,
        value: Long,
        interval: Int,
        x: Float,
        y: Float,
        lineColor: Int,
        haloColor: Int,
        centered: Boolean,
    ) {
        val big = String.format(java.util.Locale.US, "%02d", (value / 1000) % 100)
        val small = when (interval) {
            100 -> ((value / 100) % 10).toString()
            10 -> String.format(java.util.Locale.US, "%02d", (value % 1000) / 10)
            else -> ""
        }
        val bigSize = 13f * density
        val smallSize = 10f * density
        textFill.textSize = bigSize
        val bigW = textFill.measureText(big)
        textFill.textSize = smallSize
        val smallW = if (small.isEmpty()) 0f else textFill.measureText(small)
        val x0 = if (centered) x - (bigW + smallW) / 2f else x
        textFill.color = lineColor
        textFill.alpha = 235
        textHalo.color = haloColor
        textHalo.alpha = 210

        textFill.textSize = bigSize
        textHalo.textSize = bigSize
        canvas.drawText(big, x0, y, textHalo)
        canvas.drawText(big, x0, y, textFill)
        if (small.isNotEmpty()) {
            textFill.textSize = smallSize
            textHalo.textSize = smallSize
            canvas.drawText(small, x0 + bigW + 1f * density, y, textHalo)
            canvas.drawText(small, x0 + bigW + 1f * density, y, textFill)
        }
    }

    private fun safeT(bound: Double, a: Double, b: Double): Double {
        val d = b - a
        if (d == 0.0) return 0.0
        return ((bound - a) / d).coerceIn(0.0, 1.0)
    }

    private fun drawAttribution(canvas: Canvas, lineColor: Int, haloColor: Int) {
        if (attribution.isEmpty()) return
        val size = 9.5f * density
        textFill.textSize = size
        textHalo.textSize = size
        textFill.color = lineColor
        textFill.alpha = 200
        textHalo.color = haloColor
        textHalo.alpha = 190
        val y = canvas.height - bottomInsetPx - 5f * density
        // The canvas is pre-rotated when the map is turned; counter-rotate so the
        // attribution stays screen-anchored and upright.
        canvas.save()
        if (!northUp) {
            canvas.rotate(-mapOrientation, canvas.width / 2f, canvas.height / 2f)
        }
        canvas.drawText(attribution, 6f * density, y, textHalo)
        canvas.drawText(attribution, 6f * density, y, textFill)
        canvas.restore()
    }
}
