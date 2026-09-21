package app.gridfix.android.data

import app.gridfix.android.coords.Coordinates
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class RouteLegBearings(val forward: Float, val reverse: Float, val distanceMeters: Float)

/** Forward is read at the leg's start; the return bearing is read at its end. */
internal fun routeLegBearings(
    start: GeoVertex,
    end: GeoVertex,
    northRef: Int,
    declinationOverride: Float?,
    modelDeclination: (Double, Double) -> Float,
): RouteLegBearings {
    val forward = Coordinates.navInfo(start.lat, start.lon, end.lat, end.lon)
    val reverse = Coordinates.navInfo(end.lat, end.lon, start.lat, start.lon)
    fun referenced(bearing: Float, at: GeoVertex): Float {
        val correction = when (northRef) {
            1 -> declinationOverride ?: modelDeclination(at.lat, at.lon)
            2 -> Coordinates.gridConvergence(at.lat, at.lon).toFloat()
            else -> 0f
        }
        return ((bearing - correction) % 360f + 360f) % 360f
    }
    return RouteLegBearings(
        referenced(forward.bearingTrue, start), referenced(reverse.bearingTrue, end), forward.distanceMeters,
    )
}

internal data class RouteCardLeg(
    val index: Int,
    val azimuth: String,
    val backAzimuth: String,
    val distance: String,
    val paces: Int,
    val toGrid: String,
    val distanceMeters: Float,
)

/** Shared numbers and formatting keep the screen, text export, and PDF in agreement. */
internal fun routeCardLegs(
    points: List<GeoVertex>,
    settings: AppSettings,
    modelDeclination: (Double, Double) -> Float,
): List<RouteCardLeg> = points.zipWithNext().mapIndexed { index, (start, end) ->
    val bearings = routeLegBearings(start, end, settings.northRef, settings.declinationOverride, modelDeclination)
    RouteCardLeg(
        index = index + 1,
        azimuth = Coordinates.formatAngle(bearings.forward, settings.angleUnit),
        backAzimuth = Coordinates.formatAngle(bearings.reverse, settings.angleUnit),
        distance = Coordinates.formatDistance(bearings.distanceMeters, settings.units),
        paces = (bearings.distanceMeters / 100f * settings.pacePer100m).roundToInt(),
        toGrid = Coordinates.mgrs(end.lat, end.lon, 8)?.full ?: "—",
        distanceMeters = bearings.distanceMeters,
    )
}

internal data class RouteSketchPoint(val x: Double, val y: Double)

/** True-north-up sketch coordinates in degrees, preserving each leg's short east/west path. */
internal fun routeSketchCoordinates(points: List<GeoVertex>): List<RouteSketchPoint> {
    if (points.isEmpty()) return emptyList()
    val longitudeScale = cos(Math.toRadians(points.map { it.lat }.average()))
    var longitudeOffset = 0.0
    return points.mapIndexed { index, point ->
        if (index > 0) longitudeOffset += Coordinates.normalizeLongitude(point.lon - points[index - 1].lon)
        RouteSketchPoint(longitudeOffset * longitudeScale, point.lat)
    }
}

/** Perimeter in metres and enclosed area in square metres, on a common UTM plane. */
internal fun routePolygonMetrics(points: List<GeoVertex>): Pair<Double, Double> {
    if (points.size < 2) return 0.0 to 0.0
    val first = points.first()
    val zone = Coordinates.utm(first.lat, first.lon)?.zone
        ?: (((first.lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
    val north = first.lat >= 0.0
    val projected = points.map { Coordinates.utmForZone(it.lat, it.lon, zone, north) }
    // Translate the shoelace calculation near zero to avoid subtracting large
    // northing products for small polygons far from the equator.
    val origin = projected.first()
    var perimeter = 0.0
    var area2 = 0.0
    for (i in projected.indices) {
        val a = projected[i]
        val b = projected[(i + 1) % projected.size]
        perimeter += hypot(b[0] - a[0], b[1] - a[1])
        area2 += (a[0] - origin[0]) * (b[1] - origin[1]) - (b[0] - origin[0]) * (a[1] - origin[1])
    }
    return perimeter to abs(area2) / 2.0
}
