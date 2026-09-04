package app.gridfix.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.TacGraphic
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import app.gridfix.android.location.Declination

/**
 * Printable strip map for a route: an overview sketch (route line, numbered
 * legs, north arrow, scale bar) plus the classic leg table — azimuth in the
 * user's north reference and unit, back-azimuth, distance, paces, and the
 * 8-digit grid of each point. Built with the platform PdfDocument, shared as
 * a normal PDF. A4 portrait (595x842 pt).
 */
object StripMapPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    private data class PdfLeg(
        val index: Int,
        val azimuth: String,
        val back: String,
        val dist: String,
        val paces: Int,
        val toGrid: String,
        val meters: Float,
    )

    fun build(context: Context, route: TacGraphic, settings: AppSettings): File? = runCatching {
        val coords = app.gridfix.android.coords.Coordinates
        val refLetter = when (settings.northRef) {
            1 -> "M"
            2 -> "G"
            else -> "T"
        }
        val pts = route.points
        if (pts.size < 2) return null

        val legs = buildList {
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val nav = coords.navInfo(a.lat, a.lon, b.lat, b.lon)
                val midLat = (a.lat + b.lat) / 2.0
                val midLon = (a.lon + b.lon) / 2.0
                val dec = Declination.at(settings, midLat, midLon)
                fun toRef(t: Float): Float = when (settings.northRef) {
                    1 -> (t - dec + 360f) % 360f
                    2 -> (t - coords.gridConvergence(midLat, midLon).toFloat() + 360f) % 360f
                    else -> t
                }
                add(
                    PdfLeg(
                        index = i + 1,
                        azimuth = coords.formatAngle(toRef(nav.bearingTrue), settings.angleUnit),
                        back = coords.formatAngle(toRef((nav.bearingTrue + 180f) % 360f), settings.angleUnit),
                        dist = coords.formatDistance(nav.distanceMeters, settings.units),
                        paces = (nav.distanceMeters / 100f * settings.pacePer100m).roundToInt(),
                        toGrid = coords.mgrs(b.lat, b.lon, 8)?.full ?: "—",
                        meters = nav.distanceMeters,
                    )
                )
            }
        }
        val startGrid = coords.mgrs(pts[0].lat, pts[0].lon, 8)?.full ?: "—"
        val totalM = legs.sumOf { it.meters.toDouble() }.toFloat()
        val totalPaces = legs.sumOf { it.paces }

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 16f
            color = Color.BLACK
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            color = Color.BLACK
        }
        val bodyBold = Paint(body).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val small = Paint(body).apply { textSize = 8f; color = Color.DKGRAY }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
            strokeJoin = Paint.Join.ROUND
        }
        val thin = Paint(line).apply { strokeWidth = 0.8f; color = Color.DKGRAY }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN + 8f

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN + 8f
        }

        // ---- Header ----
        canvas.drawText("ROUTE CARD — ${route.name.uppercase(Locale.US)}", MARGIN, y, title)
        y += 16f
        canvas.drawText(
            "${coords.dtg(System.currentTimeMillis())} · north $refLetter · pace ${settings.pacePer100m}/100 m · MGRS GPS",
            MARGIN, y, small,
        )
        y += 14f

        // ---- Overview sketch ----
        val boxW = PAGE_W - 2 * MARGIN
        val boxH = 280f
        canvas.drawRect(MARGIN, y, MARGIN + boxW, y + boxH, thin)
        run {
            // Equirectangular plot with cos(lat) x-scale — true-north up
            val latC = pts.map { it.lat }.average()
            val kx = cos(Math.toRadians(latC))
            val xs = pts.map { it.lon * kx }
            val ys = pts.map { it.lat }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()
            val spanX = max(maxX - minX, 1e-6)
            val spanY = max(maxY - minY, 1e-6)
            val pad = 34f
            val scale = minOf((boxW - 2 * pad) / spanX, (boxH - 2 * pad) / spanY).toFloat()
            val ox = MARGIN + (boxW - (spanX * scale).toFloat()) / 2f
            val oy = y + (boxH - (spanY * scale).toFloat()) / 2f
            fun px(i: Int) = ox + ((xs[i] - minX) * scale).toFloat()
            fun py(i: Int) = oy + ((maxY - ys[i]) * scale).toFloat()

            val path = Path()
            path.moveTo(px(0), py(0))
            for (i in 1 until pts.size) path.lineTo(px(i), py(i))
            canvas.drawPath(path, line)
            // vertices + leg numbers at midpoints
            for (i in pts.indices) {
                canvas.drawCircle(px(i), py(i), 3f, fill)
            }
            for (i in 0 until pts.size - 1) {
                canvas.drawText(
                    "${i + 1}",
                    (px(i) + px(i + 1)) / 2f + 4f,
                    (py(i) + py(i + 1)) / 2f - 4f,
                    bodyBold,
                )
            }
            canvas.drawText("SP", px(0) + 5f, py(0) + 3f, bodyBold)
            canvas.drawText("RP", px(pts.size - 1) + 5f, py(pts.size - 1) + 3f, bodyBold)

            // North arrow (plot is true-north up)
            val nx = MARGIN + boxW - 24f
            val nyTop = y + 16f
            canvas.drawLine(nx, nyTop + 26f, nx, nyTop + 4f, line)
            val head = Path().apply {
                moveTo(nx, nyTop)
                lineTo(nx - 4f, nyTop + 9f)
                lineTo(nx + 4f, nyTop + 9f)
                close()
            }
            canvas.drawPath(head, fill)
            canvas.drawText("N", nx - 3f, nyTop + 38f, bodyBold)

            // Scale bar: a round number close to a third of the drawn width
            val metersPerPt = run {
                // one degree of latitude ≈ 111,319.49 m; scale maps degrees→pt
                (111319.49 / scale).toFloat()
            }
            val targetM = metersPerPt * (boxW / 3f)
            val niceM = listOf(100f, 200f, 250f, 500f, 1000f, 2000f, 2500f, 5000f, 10000f)
                .minByOrNull { kotlin.math.abs(it - targetM) } ?: 500f
            val barPt = niceM / metersPerPt
            val bx = MARGIN + 12f
            val by = y + boxH - 14f
            canvas.drawLine(bx, by, bx + barPt, by, line)
            canvas.drawLine(bx, by - 4f, bx, by + 4f, line)
            canvas.drawLine(bx + barPt, by - 4f, bx + barPt, by + 4f, line)
            val barLabel = if (niceM >= 1000f) {
                // 2500 m is a 2.5 km bar. "%.0f" rounded it to "3 km", so the printed
                // label disagreed with the bar it was labelling - on paper, in the field,
                // with no way to check it.
                if (niceM % 1000f == 0f) String.format(Locale.US, "%.0f km", niceM / 1000f)
                else String.format(Locale.US, "%.1f km", niceM / 1000f)
            } else {
                String.format(Locale.US, "%.0f m", niceM)
            }
            canvas.drawText(barLabel, bx + barPt / 2f - body.measureText(barLabel) / 2f, by - 7f, body)
        }
        y += boxH + 18f

        // ---- Leg table ----
        canvas.drawText("START  $startGrid", MARGIN, y, bodyBold)
        y += 14f
        val colX = floatArrayOf(MARGIN, MARGIN + 34f, MARGIN + 128f, MARGIN + 222f, MARGIN + 300f, MARGIN + 358f)
        fun tableHeader() {
            canvas.drawText("LEG", colX[0], y, bodyBold)
            canvas.drawText("AZ ($refLetter)", colX[1], y, bodyBold)
            canvas.drawText("BACK", colX[2], y, bodyBold)
            canvas.drawText("DIST", colX[3], y, bodyBold)
            canvas.drawText("PACES", colX[4], y, bodyBold)
            canvas.drawText("TO GRID (8-DIGIT)", colX[5], y, bodyBold)
            y += 5f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, thin)
            y += 12f
        }
        tableHeader()
        for (l in legs) {
            if (y > PAGE_H - MARGIN - 30f) {
                newPage()
                canvas.drawText(
                    "ROUTE CARD — ${route.name.uppercase(Locale.US)} (cont.)",
                    MARGIN, y, bodyBold,
                )
                y += 16f
                tableHeader()
            }
            canvas.drawText("${l.index}", colX[0], y, body)
            canvas.drawText(l.azimuth, colX[1], y, body)
            canvas.drawText(l.back, colX[2], y, body)
            canvas.drawText(l.dist, colX[3], y, body)
            canvas.drawText("${l.paces}", colX[4], y, body)
            canvas.drawText(l.toGrid, colX[5], y, body)
            y += 13f
        }
        y += 4f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, thin)
        y += 13f
        canvas.drawText(
            "TOTAL  ${coords.formatDistance(totalM, settings.units)}   $totalPaces paces",
            MARGIN, y, bodyBold,
        )
        y += 18f
        canvas.drawText(
            "Training aid — verify azimuths and grids against your map before stepping off.",
            MARGIN, y, small,
        )

        doc.finishPage(page)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safeName = route.name.ifBlank { "route" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val out = File(dir, "gridfix_route_$safeName.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
    }.getOrNull()
}
