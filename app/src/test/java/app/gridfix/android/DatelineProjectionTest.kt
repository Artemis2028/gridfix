package app.gridfix.android

import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.routePolygonMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatelineProjectionTest {
    @Test
    fun `forced zone projection and inverse cross the dateline in both directions`() {
        for ((zone, longitude) in listOf(60 to -179.99, 1 to 179.99)) {
            val projected = Coordinates.utmForZone(1.0, longitude, zone, true)
            assertTrue(projected[0] in 100_000.0..900_000.0)
            val restored = Coordinates.utmInverse(projected[0], projected[1], zone, true)
            assertEquals(1.0, restored[0], 0.000001)
            assertTrue(restored[1] >= -180.0 && restored[1] < 180.0)
            assertEquals(longitude, restored[1], 0.000001)
        }
    }

    @Test
    fun `nearby dateline rays intersect instead of appearing billions of metres apart`() {
        for (reverse in listOf(false, true)) {
            val fix = if (reverse) {
                Coordinates.rayIntersection(0.0, -179.99, 315.0, 0.0, 179.99, 45.0)
            } else {
                Coordinates.rayIntersection(0.0, 179.99, 45.0, 0.0, -179.99, 315.0)
            }
            assertNotNull(fix)
            assertEquals(1575.839822, fix!!.dist1, 0.1)
            assertEquals(1575.839822, fix.dist2, 0.1)
            assertTrue(fix.lat in 0.0099..0.0102)
            assertTrue(fix.lon in -180.0..180.0)
            assertEquals(0.0, Coordinates.normalizeLongitude(fix.lon - 180.0), 0.0001)
            assertNotNull(Coordinates.mgrs(fix.lat, fix.lon, 8))
        }
    }

    @Test
    fun `dateline area measurement retains the dimensions of the same ordinary polygon`() {
        val corners = listOf(
            GeoVertex(0.001, 179.999), GeoVertex(0.001, -179.999),
            GeoVertex(-0.001, -179.999), GeoVertex(-0.001, 179.999),
        )
        val (perimeter, area) = routePolygonMetrics(corners)
        assertEquals(888.445831, perimeter, 0.01)
        assertEquals(49332.943265, area, 0.1)

        val ordinary = corners.map { GeoVertex(it.lat, Coordinates.normalizeLongitude(it.lon - 174.0)) }
        val ordinaryMetrics = routePolygonMetrics(ordinary)
        assertEquals(ordinaryMetrics.first, perimeter, 0.01)
        assertEquals(ordinaryMetrics.second, area, 0.1)
        val clockwise = routePolygonMetrics(corners.reversed())
        assertEquals(perimeter, clockwise.first, 0.01)
        assertEquals(area, clockwise.second, 0.1)
        val fromZoneOne = routePolygonMetrics(corners.drop(1) + corners.first())
        assertEquals(perimeter, fromZoneOne.first, 0.01)
        assertEquals(area, fromZoneOne.second, 0.1)
    }

    @Test
    fun `wrapping does not change NGA zone selection at ordinary and exception boundaries`() {
        for ((lat, lon) in listOf(36.0 to 45.0, 60.0 to 3.0, 60.0 to 12.0, 78.0 to 9.0, 78.0 to 21.0, 78.0 to 33.0)) {
            val standard = Coordinates.utm(lat, lon)!!
            val mgrs = Coordinates.mgrs(lat, lon, 8)!!
            assertEquals(mgrs.gzd.dropLast(1).toInt(), standard.zone)
            val forced = Coordinates.utmForZone(lat, lon, standard.zone, true)
            assertEquals(standard.easting.toDouble(), forced[0], 0.51)
            assertEquals(standard.northing.toDouble(), forced[1], 0.51)
            val restored = Coordinates.utmInverse(forced[0], forced[1], standard.zone, true)
            assertEquals(lat, restored[0], 0.000001)
            // The existing inverse's truncated series is ~0.03 m off at the
            // six-degree edge of a widened Svalbard zone.
            assertEquals(lon, restored[1], 0.000002)
        }
    }
}
