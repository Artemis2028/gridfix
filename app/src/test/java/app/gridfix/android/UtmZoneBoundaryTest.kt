package app.gridfix.android

import app.gridfix.android.coords.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed expectations at the NGA Norway/Svalbard boundaries, not just interior samples. */
class UtmZoneBoundaryTest {
    private val epsilon = 0.000001

    private fun assertZone(lat: Double, lon: Double, expected: Int) {
        val utm = Coordinates.utm(lat, lon)
        assertNotNull("UTM missing at $lat, $lon", utm)
        assertEquals("UTM zone at $lat, $lon", expected, utm!!.zone)
        val mgrs = Coordinates.mgrs(lat, lon, 8)
        assertNotNull("MGRS missing at $lat, $lon", mgrs)
        assertEquals("MGRS zone at $lat, $lon", expected, mgrs!!.gzd.dropLast(1).toInt())
    }

    @Test
    fun `norway longitude boundaries belong to the eastern zone`() {
        for ((lon, west, east) in listOf(Triple(3.0, 31, 32), Triple(12.0, 32, 33))) {
            assertZone(60.0, lon - epsilon, west)
            assertZone(60.0, lon, east)
            assertZone(60.0, lon + epsilon, east)
        }
    }

    @Test
    fun `norway latitude boundaries belong to the northern band`() {
        assertZone(56.0 - epsilon, 4.0, 31)
        assertZone(56.0, 4.0, 32)
        assertZone(56.0 + epsilon, 4.0, 32)
        assertZone(64.0 - epsilon, 4.0, 32)
        assertZone(64.0, 4.0, 31)
        assertZone(64.0 + epsilon, 4.0, 31)
    }

    @Test
    fun `svalbard longitude boundaries belong to the eastern zone`() {
        for ((lon, west, east) in listOf(
            Triple(0.0, 30, 31), Triple(9.0, 31, 33), Triple(21.0, 33, 35),
            Triple(33.0, 35, 37), Triple(42.0, 37, 38),
        )) {
            assertZone(78.0, lon - epsilon, west)
            assertZone(78.0, lon, east)
            assertZone(78.0, lon + epsilon, east)
        }
    }

    @Test
    fun `svalbard starts at 72 north and includes the UTM northern limit`() {
        for ((lon, south, north) in listOf(
            Triple(7.0, 32, 31), Triple(10.0, 32, 33),
            Triple(22.0, 34, 35), Triple(34.0, 36, 37),
        )) {
            assertZone(72.0 - epsilon, lon, south)
            assertZone(72.0, lon, north)
            assertZone(72.0 + epsilon, lon, north)
            assertZone(84.0 - epsilon, lon, north)
            assertZone(84.0, lon, north)
            assertNull(Coordinates.utm(84.0 + epsilon, lon))
        }
    }

    @Test
    fun `grid bearing at a svalbard boundary uses the MGRS zone`() {
        // 78 N, 9 E belongs to 33X. Using zone 31 changes convergence by 11.74 degrees.
        assertEquals(-5.869811163, Coordinates.gridConvergence(78.0, 9.0), 0.000001)
        assertEquals(-5.869811163, Coordinates.gridConvergence(78.0, 21.0), 0.000001)
        assertEquals(-5.869811163, Coordinates.gridConvergence(78.0, 33.0), 0.000001)
    }
}
