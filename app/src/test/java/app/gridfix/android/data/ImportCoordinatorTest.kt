package app.gridfix.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportCoordinatorTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    /** Real waypoint/graphic transactions with an atomic in-memory track sink. */
    private class SavedData {
        val waypointStore = MemoryStore()
        val graphicStore = MemoryStore()
        val tracks = linkedMapOf<String, Pair<TrackInfo, List<TrackPoint>>>()
        val phases = mutableListOf<String>()
        fun waypoints() = WaypointRepository(waypointStore)
        fun graphics() = GraphicsRepository(graphicStore)

        suspend fun restoreTracks(values: List<Pair<TrackInfo, List<TrackPoint>>>): Int {
            var count = 0
            for (value in values) if (value.first.id !in tracks) {
                tracks[value.first.id] = value
                count++
            }
            return count
        }

        suspend fun persist(plan: ImportPlan, failureAt: String? = null): ImportResult {
            // Construct fresh repository/coordinator calls each time, as on restart.
            val waypointRepo = waypoints()
            val graphicRepo = graphics()
            fun attempt(phase: String) {
                phases.add(phase)
                if (phase == failureAt) throw IOException("disk full")
            }
            return ImportCoordinator.persist(plan,
                restoreTracks = { attempt("tracks"); restoreTracks(it) },
                restoreWaypoints = { points, folders ->
                    attempt("waypoints"); waypointRepo.restore(points, folders)
                },
                restoreGraphics = { attempt("graphics"); graphicRepo.restore(it) },
            )
        }
    }

    private fun draft(name: String = "Point", folder: String = DEFAULT_FOLDER) =
        WaypointDraft(name, 34.0, -117.0, folder, DEFAULT_SYMBOL, "none")

    private fun sample() = InterchangeFiles.ImportedData(
        waypoints = listOf(draft("One", "Patrol"), draft("Two", "PATROL")),
        lines = listOf(InterchangeFiles.ImportedLine("Route", "Patrol", listOf(
            GeoVertex(34.0, -117.0), GeoVertex(34.1, -117.1),
        ))),
        areas = listOf(InterchangeFiles.ImportedLine("Area", "Patrol", listOf(
            GeoVertex(34.0, -117.0), GeoVertex(34.1, -117.1), GeoVertex(34.2, -117.0),
        ))),
        tracks = listOf(InterchangeFiles.ImportedTrack("Track", listOf(
            TrackPoint(34.0, -117.0, 0, NO_ALTITUDE), TrackPoint(34.1, -117.1, 0, NO_ALTITUDE),
        ))),
    )

    private fun ids(plan: ImportPlan) = plan.waypoints.map { it.id } +
        plan.graphics.map { it.id } + plan.tracks.map { it.first.id }

    @Test fun stableIdentitySurvivesClockFolderAndCollectionRecreation() {
        val input = sample()
        val first = ImportCoordinator.prepare(input, 100, listOf("patrol"))
        val second = ImportCoordinator.prepare(input.copy(
            waypoints = input.waypoints.map { it.copy() },
            lines = input.lines.map { it.copy(points = it.points.map { point -> point.copy() }) },
            areas = input.areas.map { it.copy(points = it.points.map { point -> point.copy() }) },
            tracks = input.tracks.map { it.copy(points = it.points.map { point -> point.copy() }) },
        ), 9999, listOf("PATROL"))
        assertEquals(ids(first), ids(second))
        assertEquals("patrol", first.waypoints.first().folder)
        assertEquals("PATROL", second.waypoints.first().folder)
        assertNotEquals(first.tracks.first().first.startedAt, second.tracks.first().first.startedAt)
    }

    @Test fun identityEncodingIsAStablePersistedFormat() {
        val plan = ImportCoordinator.prepare(InterchangeFiles.ImportedData(waypoints = listOf(draft())), 1)
        assertEquals("4cc34f85-9016-3a08-96dc-7067edb5095d", plan.waypoints.single().id)
    }

    @Test fun duplicateEntriesWithinOneFileHaveSeparateStableIds() {
        val input = sample().let { it.copy(
            waypoints = listOf(it.waypoints.first(), it.waypoints.first()),
            lines = it.lines + it.lines, tracks = it.tracks + it.tracks,
        ) }
        val original = ids(ImportCoordinator.prepare(input, 1))
        assertEquals(original.size, original.distinct().size)
        assertEquals(original, ids(ImportCoordinator.prepare(input, 2)))
    }

    @Test fun changedContentAndOrderHaveDifferentIdentity() {
        val input = sample()
        val original = ids(ImportCoordinator.prepare(input, 1))
        val alternatives = listOf(
            input.copy(waypoints = input.waypoints.reversed()),
            input.copy(waypoints = input.waypoints.map { it.copy(metadata = WaypointMetadata(color = "red")) }),
            input.copy(lines = input.lines.map { it.copy(name = "Renamed") }),
            input.copy(areas = input.areas.map { it.copy(folder = "Other") }),
            input.copy(tracks = input.tracks.map { it.copy(points = it.points.map { point -> point.copy(time = 1) }) }),
        )
        alternatives.forEach { assertTrue(original.intersect(ids(ImportCoordinator.prepare(it, 1)).toSet()).isEmpty()) }
    }

    @Test fun failedTrackPhaseDoesNotStartMetadataPhases() = runBlocking {
        val saved = SavedData()
        val result = saved.persist(ImportCoordinator.prepare(sample(), 1), "tracks")
        assertEquals(listOf("tracks"), saved.phases)
        assertEquals(0, result.tracks + result.waypoints + result.graphics)
        assertTrue(saved.waypoints().waypoints.first().isEmpty())
        assertTrue(saved.graphics().graphics.first().isEmpty())
        assertTrue(result.summary().contains("No new records were confirmed saved"))
        assertTrue(result.summary().contains("Retry the same unchanged file"))
    }

    @Test fun retryAfterEveryPhaseFailureAddsOnlyMissingRecordsAcrossRestart() = runBlocking {
        for (phase in listOf("tracks", "waypoints", "graphics")) {
            val saved = SavedData()
            val failed = saved.persist(ImportCoordinator.prepare(sample(), 100), phase)
            assertNotNull(failed.failure)
            assertEquals(if (phase == "tracks") 0 else 1, failed.tracks)
            assertEquals(if (phase == "graphics") 2 else 0, failed.waypoints)
            assertEquals(0, failed.graphics)
            val retried = saved.persist(ImportCoordinator.prepare(sample(), 9000))
            assertNull(retried.failure)
            assertEquals(1, failed.tracks + retried.tracks)
            assertEquals(2, failed.waypoints + retried.waypoints)
            assertEquals(2, failed.graphics + retried.graphics)
            assertEquals(1, saved.tracks.size)
            assertEquals(2, saved.waypoints().waypoints.first().size)
            assertEquals(2, saved.graphics().graphics.first().size)
            assertNull(saved.waypoints().selectedId.first())
        }
    }

    @Test fun repeatedImportKeepsEditsAndUnrelatedRecords() = runBlocking {
        val saved = SavedData()
        val plan = ImportCoordinator.prepare(sample(), 1)
        saved.persist(plan)
        val waypointRepo = saved.waypoints()
        waypointRepo.update(plan.waypoints.first().id, draft("User edit", "Moved"))
        val unrelated = waypointRepo.add(draft("Manual point"), 2)
        saved.graphics().rename(plan.graphics.first().id, "Edited route", "Moved", "friendly")
        val trackId = plan.tracks.first().first.id
        saved.tracks[trackId] = saved.tracks.getValue(trackId).let { it.first.copy(name = "Edited track") to it.second }
        val repeated = saved.persist(ImportCoordinator.prepare(sample(), 1000))
        assertEquals(ImportResult(), repeated)
        assertTrue(repeated.summary().contains("already here"))
        val waypoints = waypointRepo.waypoints.first()
        assertEquals("User edit", waypoints.first { it.id == plan.waypoints.first().id }.name)
        assertTrue(waypoints.any { it.id == unrelated })
        assertEquals("Edited route", saved.graphics().graphics.first().first { it.id == plan.graphics.first().id }.name)
        assertEquals("Edited track", saved.tracks.getValue(trackId).first.name)
    }

    @Test fun invalidLaterRecordsNeverReachAnySink() = runBlocking {
        val input = sample()
        val invalid = listOf(
            input.copy(waypoints = input.waypoints + draft().copy(lon = Double.NaN)),
            input.copy(areas = input.areas + input.areas.first().copy(points = listOf(GeoVertex(0.0, 0.0)))),
            input.copy(tracks = input.tracks + InterchangeFiles.ImportedTrack("Invalid", listOf(TrackPoint(91.0, 0.0, 1, 0.0)))),
        )
        for (data in invalid) {
            val saved = SavedData()
            try {
                saved.persist(ImportCoordinator.prepare(data, 1))
                fail("Entire plan must be validated before persistence")
            } catch (_: IllegalArgumentException) {
                assertTrue(saved.phases.isEmpty())
                assertTrue(saved.tracks.isEmpty())
            }
        }
    }

    @Test fun cancellationIsPropagatedWithoutStartingAnotherPhase() = runBlocking {
        var wroteWaypoints = false
        try {
            ImportCoordinator.persist(ImportCoordinator.prepare(sample(), 1),
                restoreTracks = { throw CancellationException("cancelled") },
                restoreWaypoints = { _, _ -> wroteWaypoints = true; 0 },
                restoreGraphics = { 0 },
            )
            fail("Cancellation must reach caller")
        } catch (_: CancellationException) {
            assertFalse(wroteWaypoints)
        }
    }

    @Test fun cancellationDuringCommitFinishesThatPhaseAndRetryContinues() = runBlocking {
        val saved = SavedData()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val plan = ImportCoordinator.prepare(sample(), 1)
        var wroteWaypoints = false
        val job = launch {
            ImportCoordinator.persist(plan,
                restoreTracks = { entered.complete(Unit); release.await(); saved.restoreTracks(it) },
                restoreWaypoints = { _, _ -> wroteWaypoints = true; 0 },
                restoreGraphics = { 0 },
            )
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(1, saved.tracks.size)
        assertFalse(wroteWaypoints)
        val retry = saved.persist(ImportCoordinator.prepare(sample(), 500))
        assertEquals(0, retry.tracks)
        assertEquals(2, retry.waypoints)
        assertEquals(2, retry.graphics)
    }

    @Test fun emptyImportDoesNotWriteAnyStore() = runBlocking {
        val saved = SavedData()
        assertEquals(ImportResult(), saved.persist(ImportCoordinator.prepare(InterchangeFiles.ImportedData(), 1)))
        assertTrue(saved.phases.isEmpty())
    }
}
