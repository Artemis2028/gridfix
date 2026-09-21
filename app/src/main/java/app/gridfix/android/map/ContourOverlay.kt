package app.gridfix.android.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sinh

/**
 * Contour lines generated on-device from the same Terrarium elevation tiles the
 * crosshair readout uses (marching squares over each z13 DEM tile). This gives a
 * worldwide, license-free contour layer that works over any base map — including
 * satellite — and works offline for any area whose elevation has been cached.
 *
 * Interval follows the view scale (10/20/50/100 m); every 5th line is drawn
 * heavier and labeled with its elevation. Computation runs off the UI thread and
 * results are cached per tile, so panning back over terrain is instant.
 */
class ContourOverlay(
    private val context: Context,
    private val density: Float,
    private val scope: CoroutineScope,
    private val requestRedraw: () -> Unit,
) : Overlay() {

    var nightMode = false
    var mapOrientation = 0f
    var bottomInsetPx = 0f

    private class TileContours(
        val minor: Path,
        val major: Path,
        val labels: List<FloatArray>,   // [x, y, elevation] in SCALE units
    )

    private val ready = object : LinkedHashMap<String, TileContours>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TileContours>?): Boolean =
            size > 110
    }
    private val inFlight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
    }

    private val gp = GeoPoint(0.0, 0.0)
    private val p00 = Point()
    private val p10 = Point()
    private val p01 = Point()
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val labelPt = FloatArray(2)

    @Synchronized
    private fun cached(key: String): TileContours? = ready[key]

    @Synchronized
    private fun store(key: String, tc: TileContours) {
        ready[key] = tc
    }

    @Synchronized
    private fun shouldStart(key: String): Boolean {
        if (key in inFlight) return false
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < 30_000L) return false
        inFlight.add(key)
        return true
    }

    @Synchronized
    private fun finished(key: String, ok: Boolean) {
        inFlight.remove(key)
        if (ok) failedAt.remove(key) else failedAt[key] = SystemClock.elapsedRealtime()
    }

    private fun tileLat(y: Double): Double =
        Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / (1 shl Elevation.ZOOM)))))

    private fun tileLon(x: Double): Double = x / (1 shl Elevation.ZOOM) * 360.0 - 180.0

    override fun draw(canvas: Canvas, projection: Projection) {
        val bbox = projection.boundingBox
        val latN = bbox.latNorth.coerceAtMost(84.0)
        val latS = bbox.latSouth.coerceAtLeast(-84.0)
        if (latN <= latS || canvas.width <= 0) return
        val centerLat = (latN + latS) / 2.0
        var lonSpan = bbox.lonEast - bbox.lonWest
        if (lonSpan <= 0.0) lonSpan += 360.0
        val mpp = lonSpan * 111319.49 * cos(Math.toRadians(centerLat)) / canvas.width
        if (mpp > 20.0) return   // zoomed out too far — contours appear as you zoom in
        val interval = when {
            mpp <= 4.0 -> 10
            mpp <= 10.0 -> 20
            else -> 50
        }

        val coverage = terrainTileCoverage(latN, latS, bbox.lonWest, bbox.lonEast, Elevation.ZOOM)
        if (coverage.count > 72L) return

        val lineColor = if (nightMode) Color.rgb(196, 45, 36) else Color.rgb(148, 94, 42)
        minorPaint.color = lineColor
        minorPaint.alpha = if (nightMode) 130 else 150
        majorPaint.color = lineColor
        majorPaint.alpha = if (nightMode) 190 else 215

        var anyReady = false
        for (ty in coverage.rows) {
            for (tx in coverage.columns.asSequence().flatMap { it.asSequence() }) {
                val key = "$tx/$ty/$interval"
                val tc = cached(key)
                if (tc == null) {
                    if (shouldStart(key)) {
                        scope.launch(Dispatchers.Default) {
                            // A throwing compute must never kill the app or leave the
                            // key stuck in flight; treat it as a failed tile and move on.
                            try {
                                compute(tx, ty, interval, key)
                            } catch (e: Exception) {
                                finished(key, ok = false)
                            }
                        }
                    }
                    continue
                }
                anyReady = true
                drawTile(canvas, projection, tx, ty, tc)
            }
        }

        if (anyReady) drawLegend(canvas, interval, lineColor)
    }

    private fun drawTile(canvas: Canvas, projection: Projection, tx: Int, ty: Int, tc: TileContours) {
        val lonW = tileLon(tx.toDouble())
        val lonE = tileLon(tx + 1.0)
        val latN = tileLat(ty.toDouble())
        val latS = tileLat(ty + 1.0)
        gp.setCoords(latN, lonW)
        projection.toPixels(gp, p00)
        gp.setCoords(latN, lonE)
        projection.toPixels(gp, p10)
        gp.setCoords(latS, lonW)
        projection.toPixels(gp, p01)

        // Web-mercator screen position is linear in tile-fraction coordinates, so a
        // single affine matrix places the whole precomputed tile path exactly.
        matrixValues[0] = (p10.x - p00.x) / SCALE
        matrixValues[1] = (p01.x - p00.x) / SCALE
        matrixValues[2] = p00.x.toFloat()
        matrixValues[3] = (p10.y - p00.y) / SCALE
        matrixValues[4] = (p01.y - p00.y) / SCALE
        matrixValues[5] = p00.y.toFloat()
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        matrix.setValues(matrixValues)

        // Stroke widths are specified in screen px; the canvas transform scales
        // them, so pre-divide by the matrix scale to keep line weight constant.
        val scale = hypot(matrixValues[0], matrixValues[3]).coerceAtLeast(1e-6f)
        minorPaint.strokeWidth = 1.1f * density / scale
        majorPaint.strokeWidth = 1.9f * density / scale

        canvas.save()
        canvas.concat(matrix)
        canvas.drawPath(tc.minor, minorPaint)
        canvas.drawPath(tc.major, majorPaint)
        canvas.restore()

        if (tc.labels.isNotEmpty()) {
            val size = 10.5f * density
            textFill.textSize = size
            textHalo.textSize = size
            textFill.color = majorPaint.color
            textFill.alpha = 235
            textHalo.color = if (nightMode) Color.BLACK else Color.WHITE
            textHalo.alpha = 200
            for (l in tc.labels) {
                labelPt[0] = l[0]
                labelPt[1] = l[1]
                matrix.mapPoints(labelPt)
                val text = "${l[2].toInt()}"
                val tw = textFill.measureText(text)
                canvas.save()
                canvas.rotate(-mapOrientation, labelPt[0], labelPt[1])
                canvas.drawText(text, labelPt[0] - tw / 2f, labelPt[1] + size / 3f, textHalo)
                canvas.drawText(text, labelPt[0] - tw / 2f, labelPt[1] + size / 3f, textFill)
                canvas.restore()
            }
        }
    }

    /** Small "CI 20 m" note so the interval is never a mystery; mirrors the attribution row. */
    private fun drawLegend(canvas: Canvas, interval: Int, color: Int) {
        val size = 9.5f * density
        textFill.textSize = size
        textHalo.textSize = size
        textFill.color = color
        textFill.alpha = 220
        textHalo.color = if (nightMode) Color.BLACK else Color.WHITE
        textHalo.alpha = 200
        val text = "CI $interval m"
        val tw = textFill.measureText(text)
        val x = canvas.width - tw - 6f * density
        val y = canvas.height - bottomInsetPx - 5f * density
        canvas.save()
        if (mapOrientation != 0f) {
            canvas.rotate(-mapOrientation, canvas.width / 2f, canvas.height / 2f)
        }
        canvas.drawText(text, x, y, textHalo)
        canvas.drawText(text, x, y, textFill)
        canvas.restore()
    }

    private suspend fun compute(tx: Int, ty: Int, interval: Int, key: String) {
        val bmp = Elevation.tile(context, Elevation.ZOOM, tx, ty)
        if (bmp == null) {
            finished(key, ok = false)
            return
        }
        val tc = marchingSquares(bmp, interval)
        store(key, tc)
        finished(key, ok = true)
        requestRedraw()
    }

    private fun marchingSquares(bmp: Bitmap, baseInterval: Int): TileContours {
        val n = bmp.width
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val e = FloatArray(n * n)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until n * n) {
            val c = px[i]
            val v = ((c shr 16 and 0xFF) * 256 + (c shr 8 and 0xFF) + (c and 0xFF) / 256f) - 32768f
            e[i] = v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        if (mx - mn < 0.75f) return TileContours(Path(), Path(), emptyList())

        var iv = baseInterval
        while ((mx - mn) / iv > 90f) iv *= 2   // extreme-relief guard keeps tiles readable

        val minor = Path()
        val major = Path()
        val labels = ArrayList<FloatArray>()
        val s = SCALE / n
        val half = 0.5f
        val cx = n / 2f
        val cy = n / 2f

        var level = ceil(mn / iv).toInt() * iv
        while (level <= mx) {
            val lv = level.toFloat()
            val isMajor = level % (iv * 5) == 0
            val p = if (isMajor) major else minor
            var bestD = Float.MAX_VALUE
            var bestX = 0f
            var bestY = 0f

            fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
                p.moveTo((x1 + half) * s, (y1 + half) * s)
                p.lineTo((x2 + half) * s, (y2 + half) * s)
                if (isMajor) {
                    val mxp = (x1 + x2) / 2f
                    val myp = (y1 + y2) / 2f
                    val dd = (mxp - cx) * (mxp - cx) + (myp - cy) * (myp - cy)
                    if (dd < bestD) {
                        bestD = dd
                        bestX = (mxp + half) * s
                        bestY = (myp + half) * s
                    }
                }
            }

            for (j in 0 until n - 1) {
                val row = j * n
                for (i in 0 until n - 1) {
                    val a = e[row + i]
                    val b = e[row + i + 1]
                    val c = e[row + n + i + 1]
                    val d = e[row + n + i]
                    var idx = 0
                    if (a >= lv) idx = idx or 1
                    if (b >= lv) idx = idx or 2
                    if (c >= lv) idx = idx or 4
                    if (d >= lv) idx = idx or 8
                    if (idx == 0 || idx == 15) continue

                    val fi = i.toFloat()
                    val fj = j.toFloat()
                    // Crossing points on each cell edge (pixel-index coordinates)
                    val topX = fi + frac(a, b, lv)
                    val rightY = fj + frac(b, c, lv)
                    val botX = fi + frac(d, c, lv)
                    val leftY = fj + frac(a, d, lv)

                    when (idx) {
                        1 -> seg(fi, leftY, topX, fj)
                        2 -> seg(topX, fj, fi + 1f, rightY)
                        3 -> seg(fi, leftY, fi + 1f, rightY)
                        4 -> seg(fi + 1f, rightY, botX, fj + 1f)
                        5 -> if ((a + b + c + d) / 4f >= lv) {
                            seg(topX, fj, fi + 1f, rightY)
                            seg(fi, leftY, botX, fj + 1f)
                        } else {
                            seg(fi, leftY, topX, fj)
                            seg(fi + 1f, rightY, botX, fj + 1f)
                        }
                        6 -> seg(topX, fj, botX, fj + 1f)
                        7 -> seg(fi, leftY, botX, fj + 1f)
                        8 -> seg(fi, leftY, botX, fj + 1f)
                        9 -> seg(topX, fj, botX, fj + 1f)
                        10 -> if ((a + b + c + d) / 4f >= lv) {
                            seg(fi, leftY, topX, fj)
                            seg(fi + 1f, rightY, botX, fj + 1f)
                        } else {
                            seg(topX, fj, fi + 1f, rightY)
                            seg(fi, leftY, botX, fj + 1f)
                        }
                        11 -> seg(fi + 1f, rightY, botX, fj + 1f)
                        12 -> seg(fi, leftY, fi + 1f, rightY)
                        13 -> seg(topX, fj, fi + 1f, rightY)
                        14 -> seg(fi, leftY, topX, fj)
                    }
                }
            }

            if (isMajor && bestD < Float.MAX_VALUE) {
                labels.add(floatArrayOf(bestX, bestY, level.toFloat()))
            }
            level += iv
        }
        return TileContours(minor, major, labels)
    }

    private fun frac(p: Float, q: Float, lv: Float): Float {
        val d = q - p
        if (d == 0f) return 0.5f
        val t = (lv - p) / d
        return if (t < 0f) 0f else if (t > 1f) 1f else t
    }

    companion object {
        private const val SCALE = 1024f
    }

    init {
        isEnabled = false
    }
}
