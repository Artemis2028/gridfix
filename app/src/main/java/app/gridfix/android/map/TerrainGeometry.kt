package app.gridfix.android.map

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Geographic sampling shared by terrain consumers; no Android or map-engine dependency. */
internal fun wrapTerrainLongitude(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

internal fun terrainDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(wrapTerrainLongitude(lon2 - lon1))
    val h = (sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
    return 2 * TerrainSight.EARTH_R * atan2(sqrt(h), sqrt(1 - h))
}

/** A fraction of the shortest great-circle arc, including arcs across +/-180 degrees. */
internal fun terrainPositionAt(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double, fraction: Double,
): Pair<Double, Double> {
    if (fraction <= 0.0) return lat1 to wrapTerrainLongitude(lon1)
    if (fraction >= 1.0) return lat2 to wrapTerrainLongitude(lon2)
    val aLat = Math.toRadians(lat1)
    val aLon = Math.toRadians(lon1)
    val bLat = Math.toRadians(lat2)
    val bLon = Math.toRadians(lon2)
    val ax = cos(aLat) * cos(aLon)
    val ay = cos(aLat) * sin(aLon)
    val az = sin(aLat)
    val bx = cos(bLat) * cos(bLon)
    val by = cos(bLat) * sin(bLon)
    val bz = sin(bLat)
    val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
    var tx = bx - dot * ax
    var ty = by - dot * ay
    var tz = bz - dot * az
    val tangentLength = sqrt(tx * tx + ty * ty + tz * tz)
    if (tangentLength < 1e-12) {
        if (dot > 0.0) return lat1 to wrapTerrainLongitude(lon1)
        // Antipodes have no unique shortest arc. Choose a deterministic northward arc.
        tx = -sin(aLat) * cos(aLon)
        ty = -sin(aLat) * sin(aLon)
        tz = cos(aLat)
    } else {
        tx /= tangentLength
        ty /= tangentLength
        tz /= tangentLength
    }
    val angle = atan2(tangentLength, dot) * fraction
    val x = ax * cos(angle) + tx * sin(angle)
    val y = ay * cos(angle) + ty * sin(angle)
    val z = az * cos(angle) + tz * sin(angle)
    return Math.toDegrees(atan2(z, hypot(x, y))) to
        wrapTerrainLongitude(Math.toDegrees(atan2(y, x)))
}

internal fun terrainTileX(lon: Double, zoom: Int): Double =
    (wrapTerrainLongitude(lon) + 180.0) / 360.0 * (1 shl zoom)

internal fun terrainTileY(lat: Double, zoom: Int): Double {
    val radians = Math.toRadians(lat.coerceIn(-85.0511287798066, 85.0511287798066))
    return (1.0 - asinh(tan(radians)) / Math.PI) / 2.0 * (1 shl zoom)
}

internal data class TerrainTileCoverage(val columns: List<IntRange>, val rows: IntRange) {
    val count: Long = columns.sumOf { it.last.toLong() - it.first + 1 } *
        (if (rows.isEmpty()) 0L else rows.last.toLong() - rows.first + 1)
}

/** At most two column ranges, in west-to-east order, with no duplicate wrapped tiles. */
internal fun terrainTileCoverage(
    latNorth: Double, latSouth: Double, lonWest: Double, lonEast: Double, zoom: Int,
): TerrainTileCoverage {
    require(zoom in 0..22)
    if (!latNorth.isFinite() || !latSouth.isFinite() || !lonWest.isFinite() ||
        !lonEast.isFinite() || latNorth < latSouth
    ) return TerrainTileCoverage(emptyList(), IntRange.EMPTY)
    val size = 1 shl zoom
    val firstY = floor(terrainTileY(latNorth, zoom)).toInt().coerceIn(0, size - 1)
    val lastY = floor(terrainTileY(latSouth, zoom)).toInt().coerceIn(0, size - 1)
    val rawSpan = lonEast - lonWest
    val span = if (abs(rawSpan) >= 360.0) 360.0 else if (rawSpan < 0.0) rawSpan + 360.0 else rawSpan
    val startX = terrainTileX(lonWest, zoom)
    val firstX = floor(startX).toInt().coerceIn(0, size - 1)
    val lastX = floor(startX + span / 360.0 * size).toInt()
    val columns = when {
        span >= 360.0 || lastX - firstX + 1 >= size -> listOf(0 until size)
        lastX < size -> listOf(firstX..lastX)
        else -> listOf(firstX until size, 0..(lastX - size))
    }
    return TerrainTileCoverage(columns, firstY..lastY)
}

/** A rejected oversized area has omitted == expected and performs no network requests. */
data class ElevationDownloadResult(
    val expected: Long,
    val cached: Int,
    val failed: Int,
    val omitted: Long = 0,
) {
    val complete: Boolean get() = expected > 0 && cached.toLong() == expected && failed == 0 && omitted == 0L
    val tooLarge: Boolean get() = omitted > 0
}

const val ELEVATION_PREFETCH_MAX_TILES = 400

/** The download policy is testable with a fake tile fetcher and no Android dependencies. */
internal suspend fun prefetchTerrainTiles(
    coverage: TerrainTileCoverage,
    maxTiles: Int = ELEVATION_PREFETCH_MAX_TILES,
    fetch: suspend (x: Int, y: Int) -> Boolean,
): ElevationDownloadResult {
    require(maxTiles > 0)
    if (coverage.count > maxTiles) return ElevationDownloadResult(coverage.count, 0, 0, coverage.count)
    var cached = 0
    var failed = 0
    for (columns in coverage.columns) for (x in columns) for (y in coverage.rows) {
        if (fetch(x, y)) cached++ else failed++
    }
    return ElevationDownloadResult(coverage.count, cached, failed)
}
