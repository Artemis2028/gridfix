package app.gridfix.android

import app.gridfix.android.location.ArrivalAlertState
import app.gridfix.android.location.GuideCue
import app.gridfix.android.location.NavigationFixPolicy
import app.gridfix.android.location.guideCue
import org.junit.Assert.*
import org.junit.Test

class NavigationFixPolicyTest {
    private val now = 100_000_000_000L

    @Test fun `cached location expires without any new provider callback`() {
        val timestamp = now - NavigationFixPolicy.MAX_AGE_NANOS
        assertTrue(NavigationFixPolicy.isUsable(34.0, -117.0, 5f, timestamp, now))
        assertFalse(NavigationFixPolicy.isUsable(34.0, -117.0, 5f, timestamp, now + 1))
    }

    @Test fun `unknown future and previous-boot timestamps are rejected`() {
        assertFalse(NavigationFixPolicy.isFresh(0, now))
        assertFalse(NavigationFixPolicy.isFresh(-1, now))
        assertFalse(NavigationFixPolicy.isFresh(now + 1, now))
        assertFalse(NavigationFixPolicy.isFresh(200_000_000_000L, 10_000_000L))
    }

    @Test fun `missing approximate and invalid accuracy cannot trigger arrival or score`() {
        for (accuracy in listOf(null, 25.1f, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertFalse("accuracy=$accuracy", NavigationFixPolicy.isUsable(34.0, -117.0, accuracy, now, now))
        }
        assertTrue(NavigationFixPolicy.isUsable(34.0, -117.0, 25f, now, now))
    }

    @Test fun `invalid coordinates are rejected`() {
        for ((lat, lon) in listOf(91.0 to 0.0, -91.0 to 0.0, 0.0 to 181.0, 0.0 to -181.0, Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY)) {
            assertFalse(NavigationFixPolicy.isUsable(lat, lon, 5f, now, now))
        }
    }

    @Test fun `arrival includes horizontal uncertainty`() {
        val alerts = ArrivalAlertState()
        assertFalse(alerts.update("target", 40f, 20f))
        assertFalse(alerts.update("target", 30f, 20f))
        assertTrue(alerts.update("target", 29f, 20f))
        assertFalse(alerts.update("target", 10f, 5f))
    }

    @Test fun `stale and inaccurate updates cannot re-arm an arrival`() {
        val alerts = ArrivalAlertState()
        assertTrue(alerts.update("target", 10f, 5f))
        assertFalse(alerts.update("target", null, null))
        assertFalse(alerts.update("target", 200f, 100f))
        assertFalse(alerts.update("target", 10f, 5f))
        assertFalse(alerts.update("target", 151f, 5f))
        assertTrue(alerts.update("target", 10f, 5f))
    }

    @Test fun `switching targets re-arms even when returning to a previous target`() {
        val alerts = ArrivalAlertState()
        assertTrue(alerts.update("a", 10f, 5f))
        assertFalse(alerts.update("b", null, null))
        assertTrue(alerts.update("a", 10f, 5f))
    }

    @Test fun `compass freshness expires sooner than location freshness`() {
        val timestamp = now - 2_000_000_001L
        assertTrue(NavigationFixPolicy.isFresh(timestamp, now))
        assertFalse(NavigationFixPolicy.isFresh(timestamp, now, NavigationFixPolicy.MAX_HEADING_AGE_NANOS))
    }

    @Test fun `unavailable cannot be confused with on bearing`() {
        assertEquals(GuideCue.UNAVAILABLE, guideCue(null))
        assertEquals(GuideCue.UNAVAILABLE, guideCue(Float.NaN))
        assertEquals(GuideCue.UNAVAILABLE, guideCue(Float.POSITIVE_INFINITY))
        assertEquals(GuideCue.ON_BEARING, guideCue(0f))
        assertEquals(GuideCue.ON_BEARING, guideCue(-8f))
        assertEquals(GuideCue.ON_BEARING, guideCue(8f))
        assertEquals(GuideCue.RIGHT, guideCue(8.1f))
        assertEquals(GuideCue.LEFT, guideCue(-8.1f))
    }
}
