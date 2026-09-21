package app.gridfix.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise real repository edits and persistence, including waypoint deletion/restore. */
class CourseRepositoryTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private fun draft(name: String) = WaypointDraft(name, 34.0, -117.0, DEFAULT_FOLDER, DEFAULT_SYMBOL, "none")
    private suspend fun ids(repo: WaypointRepository) = repo.waypoints.first().map { it.id }.toSet()

    @Test fun deletingFuturePointPersistsPauseAndRestoringOriginalPointResumesWithoutSkipping() = runBlocking {
        val waypoints = WaypointRepository(MemoryStore())
        val courseStore = MemoryStore()
        val courses = CourseRepository(courseStore)
        val cp1 = waypoints.add(draft("CP 1"), 1)
        val cp2 = waypoints.add(draft("CP 2"), 2)
        val cp3 = waypoints.add(draft("CP 3"), 3)
        val original = waypoints.waypoints.first()
        val run = courses.start("Practice", listOf(cp1, cp2, cp3), 100)
        assertTrue(courses.markFound(run, cp1, 200))

        waypoints.delete(cp3)
        val paused = courses.validateWaypoints(run, ids(waypoints))!!
        assertTrue(paused.paused)
        assertEquals(listOf(cp3), paused.missingWaypointIds)
        assertEquals("Checkpoint 3 is missing.", paused.pauseReason)
        assertEquals(listOf(cp1, cp2, cp3), paused.waypointIds)
        assertEquals(listOf(200L), paused.foundAt)
        assertNull(CourseNavigationPolicy.resolve(paused, ids(waypoints)).targetId)
        assertFalse(courses.markFound(run, cp2, 300))
        assertFalse(courses.finish(run))

        val reopened = CourseRepository(courseStore)
        assertEquals(paused, reopened.active.first())
        assertEquals(1, waypoints.restore(original, emptyList()))
        val resumed = reopened.validateWaypoints(run, ids(waypoints))!!
        assertFalse(resumed.paused)
        assertNull(resumed.pauseReason)
        assertEquals(run, resumed.runId)
        assertEquals(listOf(200L), resumed.foundAt)
        assertEquals(cp2, CourseNavigationPolicy.resolve(resumed, ids(waypoints)).targetId)
        assertTrue(reopened.markFound(run, cp2, 800))
        assertTrue(reopened.markFound(run, cp3, 900))
        assertTrue(reopened.finish(run))
        assertFalse(reopened.finish(run))
        assertNull(reopened.active.first())
        val result = reopened.history.first().single()
        // The existing clock continues through the blocked interval.
        assertEquals(800L, result.totalMillis)
        assertEquals(listOf(100L, 600L, 100L), result.splitsMillis)
    }

    @Test fun replacementAtSameCoordinatesAndNameDoesNotRecoverMissingCheckpointIdentity() = runBlocking {
        val waypoints = WaypointRepository(MemoryStore())
        val courses = CourseRepository(MemoryStore())
        val cp1 = waypoints.add(draft("CP 1"), 1)
        val cp2 = waypoints.add(draft("CP 2"), 2)
        val run = courses.start("Practice", listOf(cp1, cp2), 100)
        waypoints.delete(cp1)
        val replacement = waypoints.add(draft("CP 1"), 3)
        assertNotEquals(cp1, replacement)
        val paused = courses.validateWaypoints(run, ids(waypoints))!!
        assertEquals(listOf(cp1), paused.missingWaypointIds)
        assertTrue(paused.foundAt.isEmpty())
        assertFalse(courses.markFound(run, replacement, 200))
        assertTrue(courses.abandon(run))
        assertNull(courses.active.first())
        assertTrue(courses.history.first().isEmpty())
    }

    @Test fun shorteningOwnedRoutePausesAtFutureMissingVertexAndOriginalBackupRecovers() = runBlocking {
        val waypoints = WaypointRepository(MemoryStore())
        val courses = CourseRepository(MemoryStore())
        val vertices = listOf(GeoVertex(34.0, -117.0), GeoVertex(34.1, -117.1), GeoVertex(34.2, -117.2))
        waypoints.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val original = waypoints.waypoints.first()
        val orderedIds = original.map { it.id }
        val run = courses.start("Patrol", orderedIds, 100)
        waypoints.replaceRouteWaypoints("route", "Patrol", vertices.take(2), "Routes", 2)

        val paused = courses.validateWaypoints(run, ids(waypoints))!!
        assertTrue(paused.paused)
        assertEquals(listOf(orderedIds[2]), paused.missingWaypointIds)
        assertEquals(orderedIds, paused.waypointIds)
        assertTrue(paused.foundAt.isEmpty())
        assertFalse(courses.markFound(run, orderedIds[0], 200))

        assertEquals(1, waypoints.restore(original, emptyList()))
        val resumed = courses.validateWaypoints(run, ids(waypoints))!!
        assertFalse(resumed.paused)
        assertEquals(orderedIds[0], CourseNavigationPolicy.resolve(resumed, ids(waypoints)).targetId)
    }

    @Test fun regrowingRouteDoesNotSubstituteNewVertexIdentityIntoCourse() = runBlocking {
        val waypoints = WaypointRepository(MemoryStore())
        val courses = CourseRepository(MemoryStore())
        val vertices = listOf(GeoVertex(34.0, -117.0), GeoVertex(34.1, -117.1), GeoVertex(34.2, -117.2))
        waypoints.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val orderedIds = waypoints.waypoints.first().map { it.id }
        val run = courses.start("Patrol", orderedIds, 100)
        waypoints.replaceRouteWaypoints("route", "Patrol", vertices.take(2), "Routes", 2)
        courses.validateWaypoints(run, ids(waypoints))
        waypoints.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 3)
        val paused = courses.validateWaypoints(run, ids(waypoints))!!
        assertTrue(paused.paused)
        assertEquals(listOf(orderedIds[2]), paused.missingWaypointIds)
        assertEquals(orderedIds, paused.waypointIds)
        assertNull(CourseNavigationPolicy.resolve(paused, ids(waypoints)).targetId)
    }

    @Test fun validationUsesCurrentProgressAndDoesNotPauseForAlreadyFoundPoint() = runBlocking {
        val courses = CourseRepository(MemoryStore())
        val run = courses.start("Practice", listOf("cp1", "cp2"), 100)
        val olderSnapshot = courses.active.first()!!
        assertTrue(courses.markFound(run, "cp1", 200))
        val validated = courses.validateWaypoints(olderSnapshot.runId, setOf("cp2"))!!
        assertFalse(validated.paused)
        assertEquals(1, validated.nextIndex)
        assertTrue(courses.markFound(run, "cp2", 300))
    }

    @Test fun staleRunCallbacksCannotScorePauseFinishOrAbandonReplacementCourse() = runBlocking {
        val courses = CourseRepository(MemoryStore())
        val oldRun = courses.start("Practice", listOf("cp1", "cp2"), 100)
        val newRun = courses.start("Practice", listOf("cp1", "cp2"), 100)
        assertNotEquals(oldRun, newRun)
        val before = courses.active.first()
        assertNull(courses.validateWaypoints(oldRun, emptySet()))
        assertFalse(courses.markFound(oldRun, "cp1", 200))
        assertFalse(courses.finish(oldRun))
        assertFalse(courses.abandon(oldRun))
        assertEquals(before, courses.active.first())
        assertTrue(courses.markFound(newRun, "cp1", 200))
    }

    @Test fun staleCheckpointCallbacksCannotAdvanceTwiceOrSkipAndFinishRequiresCompletion() = runBlocking {
        val courses = CourseRepository(MemoryStore())
        val run = courses.start("Practice", listOf("cp1", "cp2", "cp3"), 100)
        assertFalse(courses.finish(run))
        assertFalse(courses.markFound(run, "cp2", 200))
        assertTrue(courses.markFound(run, "cp1", 200))
        assertFalse(courses.markFound(run, "cp1", 201))
        assertFalse(courses.markFound(run, "cp3", 202))
        assertEquals(listOf(200L), courses.active.first()!!.foundAt)
        assertTrue(courses.markFound(run, "cp2", 300))
        assertTrue(courses.markFound(run, "cp3", 400))
        assertFalse(courses.markFound(run, "cp3", 401))
        assertTrue(courses.finish(run))
        assertEquals(1, courses.history.first().size)
    }

    @Test fun legacyCourseHasStableIdentityAcrossReadsReopenAndFirstMutation() = runBlocking {
        val store = MemoryStore()
        store.edit { p ->
            p[stringPreferencesKey("active")] = JSONObject()
                .put("name", "Legacy")
                .put("ids", JSONArray(listOf("cp1", "cp2")))
                .put("started", 100L)
                .put("found", JSONArray())
                .toString()
        }
        val courses = CourseRepository(store)
        val initial = courses.active.first()!!
        assertTrue(initial.runId.isNotBlank())
        assertEquals(initial, courses.active.first())
        assertEquals(initial, CourseRepository(store).active.first())
        val paused = courses.validateWaypoints(initial.runId, setOf("cp1"))!!
        assertEquals(initial.runId, paused.runId)
        assertTrue(paused.paused)
        assertEquals(paused, CourseRepository(store).active.first())
        val raw = JSONObject(store.data.first()[stringPreferencesKey("active")]!!)
        assertEquals(initial.runId, raw.getString("run"))
        assertEquals("cp2", raw.getJSONArray("missing").getString(0))
        assertFalse(courses.validateWaypoints(initial.runId, setOf("cp1", "cp2"))!!.paused)
        assertTrue(courses.markFound(initial.runId, "cp1", 200))
        assertEquals(initial.runId, CourseRepository(store).active.first()!!.runId)
    }

    @Test fun missingCheckpointNumbersPreserveCourseOrderAndReportAllMissingPoints() = runBlocking {
        val courses = CourseRepository(MemoryStore())
        val run = courses.start("Practice", listOf("cp1", "cp2", "cp3", "cp4"), 100)
        assertTrue(courses.markFound(run, "cp1", 200))
        val paused = courses.validateWaypoints(run, setOf("cp3"))!!
        assertEquals(listOf("cp2", "cp4"), paused.missingWaypointIds)
        assertEquals("Checkpoints 2, 4 are missing.", paused.pauseReason)
        assertEquals(1, paused.nextIndex)
    }
}
