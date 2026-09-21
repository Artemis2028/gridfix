package app.gridfix.android.data

import app.gridfix.android.coords.Geodesy
import java.util.UUID

/** Prepare the entire import before staging files; track logs must remain readable by Backup. */
internal fun prepareImportedTrack(
    name: String,
    points: List<TrackPoint>,
    nowMillis: Long,
    id: String = UUID.randomUUID().toString(),
    folder: String = DEFAULT_FOLDER,
): Pair<TrackInfo, List<TrackPoint>> {
    requireTrackId(id)
    require(nowMillis >= 0L) { "Invalid import time" }
    val normalized = points.map { point ->
        require(point.lat.isFinite() && point.lat in -90.0..90.0 &&
            point.lon.isFinite() && point.lon in -180.0..180.0 && point.alt.isFinite()) {
            "Invalid point in imported track $name"
        }
        // Zero is the track format's unknown-time sentinel. Historical dates
        // remain valid waypoint metadata, but cannot be negative track-log times.
        point.copy(time = point.time.coerceAtLeast(0L))
    }
    var distance = 0.0
    for (i in 1 until normalized.size) {
        val from = normalized[i - 1]
        val to = normalized[i]
        distance += Geodesy.distanceAndBearing(from.lat, from.lon, to.lat, to.lon)[0]
    }
    require(distance.isFinite()) { "Invalid distance in imported track $name" }
    val info = TrackInfo(
        id = id,
        name = name.trim().ifBlank { "Imported track" },
        startedAt = normalized.firstOrNull()?.time?.takeIf { it > 0 } ?: nowMillis,
        endedAt = normalized.lastOrNull()?.time?.takeIf { it > 0 } ?: nowMillis,
        distanceM = distance,
        pointCount = normalized.size,
        folder = canonicalFolder(folder),
    )
    return info to normalized
}
