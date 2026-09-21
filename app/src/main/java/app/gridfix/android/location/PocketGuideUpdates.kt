package app.gridfix.android.location

import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

internal data class PocketGuideUpdate(
    val expectedTargetId: String,
    val waypoint: Waypoint?,
    val declinationOverride: Float?,
)

/**
 * Read the repositories themselves, without UI initialValue placeholders or
 * lifecycle-paused snapshots. combine waits for every source's first value, so
 * a loading waypoint list cannot be mistaken for a deleted navigation target.
 */
internal fun pocketGuideUpdates(
    waypoints: Flow<List<Waypoint>>,
    settings: Flow<AppSettings>,
    activeTargetIds: Flow<String?>,
): Flow<PocketGuideUpdate> = combine(waypoints, settings, activeTargetIds) { saved, preferences, id ->
    id?.let {
        PocketGuideUpdate(it, saved.firstOrNull { waypoint -> waypoint.id == it }, preferences.declinationOverride)
    }
}.distinctUntilChanged().filterNotNull()
