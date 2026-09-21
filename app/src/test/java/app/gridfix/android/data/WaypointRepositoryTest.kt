package app.gridfix.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Exercise real repository transactions and JSON persistence without an Android Context. */
class WaypointRepositoryTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        var writes = 0
            private set
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock {
                transform(state.value).also { state.value = it; writes++ }
            }
    }

    private fun draft(name: String = "Point", folder: String = DEFAULT_FOLDER) =
        WaypointDraft(name, 34.0, -117.0, folder, DEFAULT_SYMBOL, "none")

    private val vertices = listOf(GeoVertex(34.0, -117.0), GeoVertex(34.1, -117.1))

    private suspend fun resolved(repo: WaypointRepository): Waypoint? {
        val selected = repo.selectedId.first()
        val candidates = NavigationTarget.navigable(repo.waypoints.first(), repo.folders.first(), selected)
        return NavigationTarget.resolve(candidates, selected)
    }

    @Test fun deletingSelectedPointCannotChooseAnotherAcrossRestartAddOrRestore() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        val a = repo.add(draft("A"), 1)
        val b = repo.add(draft("B"), 2)
        repo.select(b)
        val backup = repo.waypoints.first()
        repo.delete(b)
        assertNull(resolved(repo))

        val reopened = WaypointRepository(store)
        assertNull(resolved(reopened))
        reopened.add(draft("C"), 3)
        assertNull(resolved(reopened))
        reopened.restore(backup, emptyList())
        assertNull(resolved(reopened))
        reopened.select(a)
        assertEquals(a, resolved(reopened)?.id)
    }

    @Test fun batchAndFolderDeletionRequireExplicitReselection() = runBlocking {
        for (deleteFolder in listOf(false, true)) {
            val repo = WaypointRepository(MemoryStore())
            val kept = repo.add(draft("Kept"), 1)
            val selected = repo.add(draft("Selected", "Patrol"), 2)
            val extra = repo.add(draft("Extra", "Patrol"), 3)
            repo.select(selected)
            if (deleteFolder) repo.deleteFolder("Patrol", deleteContents = true)
            else repo.deleteAll(setOf(selected, extra))
            assertEquals(listOf(kept), repo.waypoints.first().map { it.id })
            assertNull(resolved(repo))
            repo.addAll(listOf(draft("Imported")), 4)
            assertNull(resolved(repo))
        }
    }

    @Test fun deletingUnselectedPointOrMovingFolderKeepsSelection() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        val a = repo.add(draft("A", "Patrol"), 1)
        val b = repo.add(draft("B"), 2)
        repo.select(a)
        repo.delete(b)
        repo.deleteFolder("Patrol", deleteContents = false)
        assertEquals(a, resolved(repo)?.id)
        assertEquals(DEFAULT_FOLDER, resolved(repo)?.folder)
    }

    @Test fun importDoesNotCreateAnImplicitDestinationThatCanChangeAfterDeletion() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        repo.addAll(listOf(draft("A"), draft("B")), 1)
        assertNull(resolved(repo))
        repo.delete(repo.waypoints.first().first().id)
        assertNull(resolved(repo))
    }

    @Test fun routeReplacementUsesOnlyOwnershipAndWritesOnce() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        val legacy = repo.add(draft("Patrol WP 1", "Routes"), 1)
        val manualElsewhere = repo.add(draft("Patrol WP 2", "Other"), 2)
        repo.replaceRouteWaypoints("route-A", "Patrol", vertices, "Routes", 3)
        repo.replaceRouteWaypoints("route-B", "Patrol", vertices, "Routes", 4)
        val old = repo.waypoints.first()
        val oldA = old.filter { it.sourceRouteId == "route-A" }
        val unrelated = old.filter { it.sourceRouteId != "route-A" }
        repo.select(oldA[1].id)
        val writesBefore = store.writes
        val moved = listOf(GeoVertex(35.0, -116.0), GeoVertex(35.1, -116.1))
        repo.replaceRouteWaypoints("route-A", "Renamed", moved, "Moved", 5)
        assertEquals(writesBefore + 1, store.writes)
        val after = repo.waypoints.first()
        assertEquals(unrelated, after.filter { it.sourceRouteId != "route-A" })
        assertTrue(after.any { it.id == legacy })
        assertTrue(after.any { it.id == manualElsewhere })
        val newA = after.filter { it.sourceRouteId == "route-A" }
        assertEquals(oldA.map { it.id }, newA.map { it.id })
        assertEquals(listOf(0, 1), newA.map { it.sourceRoutePointIndex })
        assertEquals(listOf("Renamed WP 1", "Renamed WP 2"), newA.map { it.name })
        assertTrue(newA.all { it.folder == "Moved" })
        assertEquals(oldA[1].id, resolved(repo)?.id)
        assertEquals(35.1, resolved(repo)!!.lat, 0.0)
    }

    @Test fun removingOneGeneratedPointDoesNotReassignTheNextVertexIdentity() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val old = repo.waypoints.first()
        repo.select(old[1].id)
        repo.delete(old[0].id)
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 2)
        assertEquals(old[1].id, resolved(repo)?.id)
        assertEquals(1, resolved(repo)?.sourceRoutePointIndex)
        assertEquals(vertices[1].lat, resolved(repo)!!.lat, 0.0)
        assertNotEquals(old[0].id, repo.waypoints.first().first().id)
    }

    @Test fun shorteningSelectedRouteLeavesNoTargetEvenWhenItGrowsAgain() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        repo.select(repo.waypoints.first()[1].id)
        repo.replaceRouteWaypoints("route", "Patrol", vertices.take(1), "Routes", 2)
        assertNull(resolved(repo))
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 3)
        assertNull(resolved(repo))
    }

    @Test fun routeOwnershipAndVertexIndexSurviveJsonAndOrdinaryEdits() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val point = repo.waypoints.first().first()
        repo.update(point.id, draft("Custom name", "Another folder"))
        val reopened = WaypointRepository(store)
        val saved = reopened.waypoints.first().first()
        assertEquals("route", saved.sourceRouteId)
        assertEquals(0, saved.sourceRoutePointIndex)
        val raw = JSONArray(store.data.first()[stringPreferencesKey("list")])
        assertEquals("route", raw.getJSONObject(0).getString("sourceRouteId"))
        assertEquals(0, raw.getJSONObject(0).getInt("sourceRoutePointIndex"))
    }

    @Test fun malformedRouteCoordinatesCannotPartiallyReplaceExistingPoints() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val before = repo.waypoints.first()
        val writesBefore = store.writes
        try {
            repo.replaceRouteWaypoints("route", "Patrol", listOf(GeoVertex(Double.NaN, 0.0)), "Routes", 2)
            fail("Invalid route must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, repo.waypoints.first())
            assertEquals(writesBefore, store.writes)
        }
    }
}
