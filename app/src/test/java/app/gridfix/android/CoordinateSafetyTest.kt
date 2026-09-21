package app.gridfix.android

import app.gridfix.android.coords.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateSafetyTest {
    @Test
    fun `ray solver rejects every nonfinite input`() {
        val valid = doubleArrayOf(36.0, 45.0, 45.0, 36.0, 45.01, 315.0)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (index in valid.indices) {
                val input = valid.copyOf().apply { this[index] = invalid }
                assertNull("input $index = $invalid", Coordinates.rayIntersection(
                    input[0], input[1], input[2], input[3], input[4], input[5],
                ))
            }
        }
    }

    @Test
    fun `ray solver rejects observers outside its projection coverage`() {
        for ((lat, lon) in listOf(
            84.0001 to 45.0, -80.0001 to 45.0, 91.0 to 45.0,
            36.0 to 180.0001, 36.0 to -180.0001,
            Double.MAX_VALUE to 45.0, 36.0 to Double.MAX_VALUE,
        )) {
            assertNull(Coordinates.rayIntersection(lat, lon, 45.0, 36.0, 45.01, 315.0))
            assertNull(Coordinates.rayIntersection(36.0, 45.0, 45.0, lat, lon, 315.0))
        }
    }

    @Test
    fun `valid rays return finite usable coordinates and ranges`() {
        val fix = Coordinates.rayIntersection(36.0, 45.0, 45.0, 36.0, 45.01, 315.0)
        assertNotNull(fix)
        assertTrue(fix!!.lat.isFinite() && fix.lat in -80.0..84.0)
        assertTrue(fix.lon.isFinite() && fix.lon in -180.0..180.0)
        assertTrue(fix.dist1.isFinite() && fix.dist1 > 0.0 && fix.dist1 <= 100_000.0)
        assertTrue(fix.dist2.isFinite() && fix.dist2 > 0.0 && fix.dist2 <= 100_000.0)
        assertNotNull(Coordinates.mgrs(fix.lat, fix.lon, 8))
    }

    @Test
    fun `ray solver rejects an intersection beyond UTM coverage`() {
        // Both observers are supported, but their rays meet north of 84 N.
        assertNull(Coordinates.rayIntersection(84.0, 15.0, 45.0, 84.0, 15.01, 315.0))
    }

    @Test
    fun `azimuth entries reject nonfinite overflow and invalid values`() {
        for (text in listOf("", ".", "NaN", "Infinity", "-Infinity", "1e999", "1e99", "999999", "-1", "12..3")) {
            for (unit in listOf(0, 1)) assertNull("$text in unit $unit", Coordinates.parseAzimuth(text, unit))
        }
        assertNull(Coordinates.parseAzimuth("360.01", 0))
        assertNull(Coordinates.parseAzimuth("6400.01", 1))
    }

    @Test
    fun `degree and mil entries retain their intended bearings`() {
        assertEquals(0.0, Coordinates.parseAzimuth("0", 0)!!, 0.0)
        assertEquals(45.5, Coordinates.parseAzimuth(" 45.5 ", 0)!!, 0.0)
        assertEquals(360.0, Coordinates.parseAzimuth("360", 0)!!, 0.0)
        assertEquals(0.0, Coordinates.parseAzimuth("0", 1)!!, 0.0)
        assertEquals(45.0, Coordinates.parseAzimuth("800", 1)!!, 1e-12)
        assertEquals(180.0, Coordinates.parseAzimuth("3200", 1)!!, 1e-12)
        assertEquals(360.0, Coordinates.parseAzimuth("6400", 1)!!, 1e-12)
    }

    @Test
    fun `utm rejects invalid coordinates before rounding`() {
        for ((lat, lon) in listOf(
            Double.NaN to 45.0, 36.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 45.0, 36.0 to Double.NEGATIVE_INFINITY,
            84.0001 to 45.0, -80.0001 to 45.0, 36.0 to 180.0001, 36.0 to -180.0001,
        )) assertNull(Coordinates.utm(lat, lon))
    }
}
