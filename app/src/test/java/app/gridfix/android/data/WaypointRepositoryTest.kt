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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    /** Encode with the production backup writer, then parse the actual archive. */
    private fun backupSnapshot(points: List<Waypoint>): List<Waypoint> {
        val root = JSONObject().put("app", "GridFix").put("version", Backup.VERSION)
            .put("waypoints", JSONArray().also { a -> points.forEach { a.put(it.toWaypointJson()) } })
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("gridfix-backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return Backup.parse(bytes.toByteArray().inputStream()).waypoints
    }

    @Test fun restoringOldRouteBackupKeepsRegeneratedLiveIdsMetadataAndSelection() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 1)
        val original = repo.waypoints.first()
        val backup = backupSnapshot(original)
        repo.delete(original[0].id)
        repo.replaceRouteWaypoints("route", "Patrol", vertices, "Routes", 2)
        val regenerated = repo.waypoints.first().first { it.sourceRoutePointIndex == 0 }
        assertNotEquals(original[0].id, regenerated.id)
        val metadata = WaypointMetadata("red", 3200, 125.0, 123456L)
        repo.update(regenerated.id, draft("Edited live point", "Moved").copy(metadata = metadata))
        repo.setVisible(regenerated.id, false)
        repo.select(regenerated.id)
        val beforeRestore = repo.waypoints.first()
        val courseWaypointIds = beforeRestore.map { it.id }

        assertEquals(0, repo.restore(backup, emptyList()))
        assertEquals(beforeRestore, repo.waypoints.first())
        assertEquals(0, repo.restore(backup, emptyList()))
        assertEquals(regenerated.id, resolved(repo)?.id)

        val reopened = WaypointRepository(store)
        val moved = listOf(GeoVertex(35.0, -116.0), GeoVertex(35.1, -116.1))
        reopened.replaceRouteWaypoints("route", "Renamed", moved, "Routes", 3)
        val after = reopened.waypoints.first()
        assertEquals(courseWaypointIds, after.map { it.id })
        assertEquals(regenerated.id, resolved(reopened)?.id)
        assertEquals(metadata, resolved(reopened)?.metadata)
        assertFalse(resolved(reopened)!!.visible)
        assertEquals(35.0, resolved(reopened)!!.lat, 0.0)
        assertEquals(0, reopened.restore(backup, emptyList()))
        assertEquals(after, reopened.waypoints.first())
    }

    @Test fun restoreDeduplicatesIncomingOwnerPairsAndIdsWithAccurateRetryCounts() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        val first = Waypoint("first", "Patrol WP 1", 34.0, -117.0, 1,
            sourceRouteId = "route-A", sourceRoutePointIndex = 0)
        val sameVertex = first.copy(id = "older-duplicate", name = "Conflicting backup name", lat = 40.0)
        val sameIdOtherOwner = first.copy(sourceRouteId = "route-B")
        val otherOwner = first.copy(id = "other-owner", sourceRouteId = "route-B")
        val manual = first.copy(id = "manual", sourceRouteId = null, sourceRoutePointIndex = null)
        val imported = listOf(first, sameVertex, sameIdOtherOwner, otherOwner, manual, manual)
        assertEquals(3, repo.restore(imported, emptyList()))
        assertEquals(listOf(first, otherOwner, manual), repo.waypoints.first())
        assertEquals(0, repo.restore(imported, emptyList()))
        assertEquals(listOf(first, otherOwner, manual), repo.waypoints.first())
    }

    @Test fun conflictingBackupOwnershipCannotAlterCurrentUuidOrMetadata() = runBlocking {
        val repo = WaypointRepository(MemoryStore())
        val live = Waypoint("live", "Current", 34.0, -117.0, 1,
            metadata = WaypointMetadata(color = "green"), sourceRouteId = "route-A", sourceRoutePointIndex = 0)
        val manual = live.copy(id = "manual", sourceRouteId = null, sourceRoutePointIndex = null)
        assertEquals(2, repo.restore(listOf(live, manual), emptyList()))
        repo.select(live.id)
        val incompatibleId = live.copy(name = "Old", lat = 45.0, sourceRouteId = "route-B", sourceRoutePointIndex = 8)
        val incompatibleVertex = live.copy(id = "stale", name = "Stale", lat = 50.0,
            metadata = WaypointMetadata(color = "red"))
        val claimedManual = manual.copy(sourceRouteId = "route-B", sourceRoutePointIndex = 0)
        val otherVertex = live.copy(id = "other", sourceRouteId = "route-B", sourceRoutePointIndex = 0)
        val imported = listOf(incompatibleId, incompatibleVertex, claimedManual, otherVertex)
        assertEquals(1, repo.restore(imported, emptyList()))
        assertEquals(listOf(live, manual, otherVertex), repo.waypoints.first())
        assertEquals(live, resolved(repo))
        assertEquals(0, repo.restore(imported, emptyList()))
    }

    @Test fun regenerationKeepsSelectedLegacyDuplicateAndPreservesOtherReferencedIds() = runBlocking {
        for (selected in listOf<String?>(null, "selected")) {
            val store = MemoryStore()
            val repo = WaypointRepository(store)
            val first = Waypoint("first", "First live", 34.0, -117.0, 1,
                sourceRouteId = "route-A", sourceRoutePointIndex = 0)
            val second = first.copy(id = "selected", name = "Selected live", metadata = WaypointMetadata(color = "red"))
            val otherRoute = first.copy(id = "other", sourceRouteId = "route-B")
            val otherDuplicate = otherRoute.copy(id = "other-duplicate")
            val incomplete = first.copy(id = "legacy-no-index", sourceRoutePointIndex = null)
            val manual = first.copy(id = "manual", sourceRouteId = null, sourceRoutePointIndex = null)
            val legacy = listOf(first, second, otherRoute, otherDuplicate, incomplete, manual)
            // Seed the persisted state produced by the old restore implementation.
            store.edit { prefs ->
                prefs[stringPreferencesKey("list")] =
                    JSONArray().also { a -> legacy.forEach { a.put(it.toWaypointJson()) } }.toString()
            }
            if (selected != null) repo.select(selected)
            assertEquals(0, repo.restore(listOf(first.copy(id = "incoming")), emptyList()))
            assertEquals(legacy, repo.waypoints.first())
            repo.replaceRouteWaypoints("route-A", "Regenerated", vertices, "Routes", 2)
            val after = repo.waypoints.first()
            val winner = if (selected == null) first else second
            val loser = if (selected == null) second else first
            assertEquals(winner.id, after.single { it.sourceRouteId == "route-A" && it.sourceRoutePointIndex == 0 }.id)
            assertEquals(winner.metadata, after.first { it.id == winner.id }.metadata)
            assertEquals(loser.copy(sourceRouteId = null, sourceRoutePointIndex = null), after.first { it.id == loser.id })
            assertEquals(incomplete.copy(sourceRouteId = null), after.first { it.id == incomplete.id })
            assertEquals(listOf(otherRoute, otherDuplicate), after.filter { it.sourceRouteId == "route-B" })
            assertEquals(manual, after.first { it.id == manual.id })
            assertTrue(legacy.all { old -> after.any { it.id == old.id } })
            if (selected == null) assertNull(resolved(repo)) else assertEquals(selected, resolved(repo)?.id)
            assertEquals(0, repo.restore(backupSnapshot(legacy), emptyList()))
            assertEquals(after, repo.waypoints.first())
        }
    }


    @Test fun staleSelectionCannotReplaceLiveTargetOrUndoDeletionMarker() = runBlocking {
        val store = MemoryStore()
        val repo = WaypointRepository(store)
        val a = repo.add(draft("A"), 1)
        val b = repo.add(draft("B"), 2)
        val stale = repo.waypoints.first().first { it.id == b }
        // The UI read B, but its deletion commits before the pending select(B).
        repo.delete(b)
        repo.select(stale.id)
        assertEquals(a, resolved(repo)?.id)
        repo.restore(listOf(stale), emptyList())
        assertEquals(a, resolved(repo)?.id)

        repo.select(b)
        repo.delete(b)
        repo.select(stale.id)
        assertNull(resolved(repo))
        val reopened = WaypointRepository(store)
        reopened.restore(listOf(stale), emptyList())
        assertNull(resolved(reopened))
        // Only a deliberate selection after the point exists again resumes it.
        reopened.select(b)
        assertEquals(b, resolved(reopened)?.id)
    }

}
