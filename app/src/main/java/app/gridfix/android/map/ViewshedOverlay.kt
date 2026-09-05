package app.gridfix.android.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * Draws a computed [Terrain.Viewshed] raster over the map. Day palette:
 * green where the observer sees the ground, amber where only a standing
 * target would show (partial defilade), red where a 3 m target is hidden.
 * Night palette: a red-only intensity ramp — the brighter the red, the
 * better masked — so the shade sits inside red-light discipline. Hatching
 * marks unknown visibility where terrain is missing on a ray. The bitmap
 * is placed with an affine corner fit, so it stays glued under pan, zoom,
 * and rotation.
 */
class ViewshedOverlay : Overlay() {

    var data: Terrain.Viewshed? = null
    var nightMode = false

    private val nightAccent = Color.rgb(196, 45, 36)
    // Preserve discrete classes and the unknown hatching when scaled.
    private val bmpPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val matrix = Matrix()
    private val gp = GeoPoint(0.0, 0.0)
    private val pt = Point()
    private val srcPts = FloatArray(6)
    private val dstPts = FloatArray(6)

    override fun draw(canvas: Canvas, projection: Projection) {
        val d = data ?: return
        val bmp = d.bitmapFor(nightMode)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        val accent = if (nightMode) nightAccent else Color.DKGRAY
        ringPaint.color = accent
        ringPaint.alpha = 160
        dotPaint.color = accent
        dotPaint.alpha = 220

        gp.setCoords(d.latN, d.lonW)
        projection.toPixels(gp, pt)
        dstPts[0] = pt.x.toFloat(); dstPts[1] = pt.y.toFloat()
        gp.setCoords(d.latN, d.lonE)
        projection.toPixels(gp, pt)
        dstPts[2] = pt.x.toFloat(); dstPts[3] = pt.y.toFloat()
        gp.setCoords(d.latS, d.lonW)
        projection.toPixels(gp, pt)
        dstPts[4] = pt.x.toFloat(); dstPts[5] = pt.y.toFloat()

        srcPts[0] = 0f; srcPts[1] = 0f
        srcPts[2] = w; srcPts[3] = 0f
        srcPts[4] = 0f; srcPts[5] = h
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 3)
        canvas.drawBitmap(bmp, matrix, bmpPaint)

        // observer dot + radius ring
        gp.setCoords(d.obsLat, d.obsLon)
        projection.toPixels(gp, pt)
        val ox = pt.x.toFloat()
        val oy = pt.y.toFloat()
        gp.setCoords(d.obsLat, d.lonE)
        projection.toPixels(gp, pt)
        val rPx = hypot(pt.x - ox, pt.y - oy)
        canvas.drawCircle(ox, oy, rPx, ringPaint)
        canvas.drawCircle(ox, oy, 6f, dotPaint)
    }
}
