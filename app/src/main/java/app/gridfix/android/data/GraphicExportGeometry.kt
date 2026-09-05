package app.gridfix.android.data

import app.gridfix.android.coords.Geodesy
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Geometry for interchange formats that do not understand GridFix control points. */
internal data class GraphicExportGeometry(val points: List<GeoVertex>, val area: Boolean)

/**
 * Ring and sector records contain handles, not the shape's boundary. Sample their
 * boundary at most every five degrees so other map apps receive the visible shape.
 * Spherical ranges use the same mean Earth radius as the map's range-ring label.
 */
internal fun graphicExportGeometry(graphic: TacGraphic): GraphicExportGeometry? {
    val points = graphic.points
    if (points.isEmpty() || points.any {
            !it.lat.isFinite() || !it.lon.isFinite() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0
        }) return null
    val center = points.first()
    fun range(edge: GeoVertex) = Geodesy.greatCircleMeters(center.lat, center.lon, edge.lat, edge.lon)
    return when (graphic.type) {
        "ring" -> {
            if (points.size < 2) return null
            val radius = range(points[1])
            if (radius <= 0.0) return null
            val boundary = (0 until 72).map { destination(center, radius, it * 5.0) }
            // A range ring is an outline, not a filled area. Keep the closing vertex.
            GraphicExportGeometry(boundary + boundary.first(), area = false)
        }
        "sector" -> {
            if (points.size < 3) return null
            val radius = maxOf(range(points[1]), range(points[2]))
            if (radius <= 0.0) return null
            val left = Geodesy.greatCircleBearing(center.lat, center.lon, points[1].lat, points[1].lon)
            val right = Geodesy.greatCircleBearing(center.lat, center.lon, points[2].lat, points[2].lon)
            val sweep = ((right - left + 360.0) % 360.0).let { if (it == 0.0) 360.0 else it }
            val steps = ceil(sweep / 5.0).toInt().coerceIn(1, 72)
            val arc = (0..steps).map { destination(center, radius, left + sweep * it / steps) }
            GraphicExportGeometry(listOf(center) + arc, area = true)
        }
        else -> GraphicExportGeometry(points, GraphicTypes.isArea(graphic.type) && points.size >= 3)
    }
}

private fun destination(start: GeoVertex, metres: Double, bearing: Double): GeoVertex {
    val angle = metres / 6371008.8
    val heading = Math.toRadians(bearing)
    val latitude = Math.toRadians(start.lat)
    val longitude = Math.toRadians(start.lon)
    val endLat = asin((sin(latitude) * cos(angle) + cos(latitude) * sin(angle) * cos(heading)).coerceIn(-1.0, 1.0))
    val endLon = longitude + atan2(
        sin(heading) * sin(angle) * cos(latitude),
        cos(angle) - sin(latitude) * sin(endLat),
    )
    return GeoVertex(Math.toDegrees(endLat), (Math.toDegrees(endLon) + 540.0) % 360.0 - 180.0)
}
