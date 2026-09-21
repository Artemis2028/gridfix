package app.gridfix.android.location

import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketGuideUpdatesTest {
    private fun waypoint(id: String, lat: Double = 24.0) = Waypoint(
        id = id, name = id, lat = lat, lon = 54.0, createdAt = 1L,
    )

    private class Sources(scope: CoroutineScope) {
        val waypoints = MutableSharedFlow<List<Waypoint>>(replay = 1)
        val settings = MutableSharedFlow<AppSettings>(replay = 1)
        val active = MutableSharedFlow<String?>(replay = 1)
        val updates = Channel<PocketGuideUpdate>(Channel.UNLIMITED)
        val job = scope.launch {
            pocketGuideUpdates(waypoints, settings, active).collect { updates.send(it) }
        }

        suspend fun ready() {
            waypoints.subscriptionCount.first { it > 0 }
            settings.subscriptionCount.first { it > 0 }
            active.subscriptionCount.first { it > 0 }
        }

        suspend fun next(): PocketGuideUpdate = withTimeout(1_000) { updates.receive() }
    }

    @Test
    fun `active guide waits for actual waypoints and settings after activity recreation`() = runBlocking {
        val sources = Sources(this)
        try {
            sources.ready()
            sources.active.emit("A")
            sources.settings.emit(AppSettings(declinationOverride = 7f))
            assertTrue(sources.updates.tryReceive().isFailure)

            val point = waypoint("A")
            sources.waypoints.emit(listOf(point))
            val update = sources.next()
            assertEquals("A", update.expectedTargetId)
            assertEquals(point, update.waypoint)
            assertEquals(7f, update.declinationOverride)
        } finally {
            sources.job.cancelAndJoin()
        }
    }

    @Test
    fun `saved manual declination arrives before any update and later changes propagate`() = runBlocking {
        val sources = Sources(this)
        try {
            sources.ready()
            sources.active.emit("A")
            sources.waypoints.emit(listOf(waypoint("A")))
            assertTrue(sources.updates.tryReceive().isFailure)

            sources.settings.emit(AppSettings(declinationOverride = 12f))
            assertEquals(12f, sources.next().declinationOverride)
            sources.settings.emit(AppSettings(declinationOverride = -4f))
            assertEquals(-4f, sources.next().declinationOverride)
            sources.settings.emit(AppSettings(declinationOverride = null))
            assertNull(sources.next().declinationOverride)
        } finally {
            sources.job.cancelAndJoin()
        }
    }

    @Test
    fun `real waypoint edits and deletion target only the guide being observed`() = runBlocking {
        val sources = Sources(this)
        try {
            sources.ready()
            sources.active.emit("A")
            sources.settings.emit(AppSettings())
            sources.waypoints.emit(listOf(waypoint("A"), waypoint("B")))
            sources.next()

            val moved = waypoint("A", lat = 25.0)
            sources.waypoints.emit(listOf(moved, waypoint("B")))
            assertEquals(moved, sources.next().waypoint)

            sources.waypoints.emit(listOf(waypoint("B")))
            val deletion = sources.next()
            assertEquals("A", deletion.expectedTargetId)
            assertNull(deletion.waypoint)

            sources.active.emit("B")
            val newGuide = sources.next()
            assertEquals("B", newGuide.expectedTargetId)
            assertEquals(waypoint("B"), newGuide.waypoint)
            // The old deletion carries A's identity, never the new guide's B.
            assertTrue(deletion.expectedTargetId != newGuide.expectedTargetId)
        } finally {
            sources.job.cancelAndJoin()
        }
    }
}
