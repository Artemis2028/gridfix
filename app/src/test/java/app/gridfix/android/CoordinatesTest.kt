package app.gridfix.android

import app.gridfix.android.coords.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field math is the part of this app that must never drift quietly: a wrong
 * grid looks exactly like a right one. These run on the JVM in CI on every build.
 */
class CoordinatesTest {

    // ---- MGRS round trip -------------------------------------------------

    @Test
    fun `mgrs round trips at every precision`() {
        val places = listOf(
            24.4539 to 54.3773,      // Abu Dhabi
            36.1699 to -115.1398,    // Las Vegas
            -33.8688 to 151.2093,    // Sydney, southern hemisphere
            51.5074 to -0.1278,      // London, straddling the prime meridian
            -1.2921 to 36.8219,      // Nairobi, near the equator
        )
        for ((lat, lon) in places) {
            for (digits in listOf(4, 6, 8, 10)) {
                val first = Coordinates.mgrs(lat, lon, digits)
                assertNotNull("no MGRS for $lat,$lon at $digits", first)
                val parsed = Coordinates.parseMgrs(first!!.full)
                assertNotNull("could not parse ${first.full}", parsed)
                val again = Coordinates.mgrs(parsed!!.first, parsed.second, digits)
                assertEquals(
                    "round trip changed the grid at $digits digits",
                    first.full,
                    again?.full,
                )
            }
        }
    }

    @Test
    fun `single digit zones keep their leading zero handling`() {
        // Zone 1, western Aleutians: the GZD is "1" not "01", and must survive a round trip.
        val g = Coordinates.mgrs(52.0, -177.0, 8)
        assertNotNull(g)
        assertTrue("unexpected GZD ${g!!.gzd}", g.gzd.startsWith("1"))
        val parsed = Coordinates.parseMgrs(g.full)
        assertNotNull(parsed)
        assertEquals(g.full, Coordinates.mgrs(parsed!!.first, parsed.second, 8)?.full)
    }

    // ---- Zone exceptions -------------------------------------------------

    @Test
    fun `norway 32V exception`() {
        // South-west Norway: zone 32 is widened, so 59N 6E is 32V, not 31V.
        assertEquals("32V", Coordinates.mgrs(59.0, 6.0, 8)?.gzd)
    }

    @Test
    fun `svalbard zone exceptions`() {
        assertEquals("31X", Coordinates.mgrs(78.0, 5.0, 8)?.gzd)
        assertEquals("33X", Coordinates.mgrs(78.0, 15.0, 8)?.gzd)
        assertEquals("35X", Coordinates.mgrs(78.0, 25.0, 8)?.gzd)
        assertEquals("37X", Coordinates.mgrs(78.0, 35.0, 8)?.gzd)
    }

    // ---- Cell centre, not the SW corner ----------------------------------

    @Test
    fun `parse lands on the cell centre`() {
        // A 4-digit grid is a 1 km square. Parsing must aim at the middle of it:
        // if someone "fixes" this back to the SW corner, re-formatting can fall
        // into the neighbouring cell, and this test is why it must not happen.
        //
        // Asserted with tolerance rather than an exact "500": the point makes a
        // round trip through our Snyder inverse and then NGA's own forward
        // projection, and the two agree to about a metre, not to the millimetre.
        // A corner would read 000 and a centre reads 499-501, so a metre of slack
        // costs nothing and keeps the test from failing for the wrong reason.
        val parsed = Coordinates.parseMgrs("33UXP0000")
        assertNotNull(parsed)
        val fine = Coordinates.mgrs(parsed!!.first, parsed.second, 10)
        assertNotNull(fine)
        val eastingInCell = fine!!.easting.takeLast(3).toInt()
        val northingInCell = fine.northing.takeLast(3).toInt()
        assertTrue(
            "easting ${fine.easting} is not a cell centre (got $eastingInCell m into the cell)",
            eastingInCell in 400..600,
        )
        assertTrue(
            "northing ${fine.northing} is not a cell centre (got $northingInCell m into the cell)",
            northingInCell in 400..600,
        )
    }

    @Test
    fun `parse accepts spaces and lower case`() {
        val a = Coordinates.parseMgrs("33U XP 0000 0000")
        val b = Coordinates.parseMgrs("33uxp00000000")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!.first, b!!.first, 1e-9)
        assertEquals(a.second, b.second, 1e-9)
    }

    @Test
    fun `rubbish does not parse`() {
        assertNull(Coordinates.parseMgrs("not a grid"))
        assertNull(Coordinates.parseMgrs(""))
    }

    // ---- Angle formatting wrap -------------------------------------------

    @Test
    fun `angles wrap instead of showing 360`() {
        assertEquals("000°", Coordinates.formatAngle(359.5f, 0))
        assertEquals("000°", Coordinates.formatAngle(360f, 0))
        assertEquals("359°", Coordinates.formatAngle(359.4f, 0))
        assertEquals("045°", Coordinates.formatAngle(45f, 0))
        assertEquals("0 mils", Coordinates.formatAngle(360f, 1))
        assertEquals("800 mils", Coordinates.formatAngle(45f, 1))
    }

    @Test
    fun `negative angles normalise`() {
        assertEquals("270°", Coordinates.formatAngle(-90f, 0))
        assertEquals("4800 mils", Coordinates.formatAngle(-90f, 1))
    }

    // ---- Ray intersection ------------------------------------------------

    @Test
    fun `converging rays give a fix ahead of both observers`() {
        val fix = Coordinates.rayIntersection(36.0, 45.0, 45.0, 36.0, 45.01, 315.0)
        assertNotNull("converging rays should intersect", fix)
        assertTrue("fix should be north of both observers", fix!!.lat > 36.0)
        assertTrue(fix.dist1 > 0.0 && fix.dist2 > 0.0)
    }

    @Test
    fun `parallel rays give no fix`() {
        assertNull(Coordinates.rayIntersection(36.0, 45.0, 90.0, 36.01, 45.0, 90.0))
    }

    @Test
    fun `a crossing behind the observers gives no fix`() {
        // Both rays point away from each other: they only "cross" behind.
        assertNull(Coordinates.rayIntersection(36.0, 45.0, 225.0, 36.0, 45.01, 135.0))
    }

    @Test
    fun `a crossing beyond 100 km gives no fix`() {
        // Almost parallel: the crossing is far outside any believable compass shot.
        assertNull(Coordinates.rayIntersection(36.0, 45.0, 1.0, 36.0, 45.01, 0.5))
    }

    // ---- Grid convergence -------------------------------------------------

    @Test
    fun `convergence is zero on the central meridian and grows towards the edge`() {
        // Zone 38 runs 42E-48E, central meridian 45E.
        assertEquals(0.0, Coordinates.gridConvergence(36.0, 45.0), 1e-6)
        val east = Coordinates.gridConvergence(36.0, 47.5)
        val west = Coordinates.gridConvergence(36.0, 42.5)
        assertTrue("east of the CM should be positive, was $east", east > 0.5)
        assertTrue("west of the CM should be negative, was $west", west < -0.5)
    }

    // ---- Corner parse (roadmap A: photo-map control points) ----------------

    @Test
    fun `corner parse lands half a cell south-west of the centre parse`() {
        // A 4-digit grid is a 1 km cell, so the centre sits 500 m east and
        // 500 m north of the corner. Compared in UTM so no geodesic is needed.
        val corner = Coordinates.parseMgrsCorner("33UXP1234")
        val centre = Coordinates.parseMgrs("33UXP1234")
        assertNotNull(corner)
        assertNotNull(centre)
        val cu = Coordinates.utm(corner!!.first, corner.second)!!
        val mu = Coordinates.utm(centre!!.first, centre.second)!!
        assertEquals(cu.zone.toLong(), mu.zone.toLong())
        assertEquals(500.0, (mu.easting - cu.easting).toDouble(), 2.0)
        assertEquals(500.0, (mu.northing - cu.northing).toDouble(), 2.0)
    }

    @Test
    fun `corner parse sits on the grid lines it names`() {
        // 33UXP1234 names easting line 12000 and northing line 34000 inside
        // the 100 km square. The round trip is good to about a metre.
        val corner = Coordinates.parseMgrsCorner("33U XP 12 34")
        assertNotNull(corner)
        val u = Coordinates.utm(corner!!.first, corner.second)!!
        assertEquals(12000.0, (u.easting % 100000L).toDouble(), 2.0)
        assertEquals(34000.0, (u.northing % 100000L).toDouble(), 2.0)
    }

    @Test
    fun `corner parse rejects junk the same way the centre parse does`() {
        assertNull(Coordinates.parseMgrsCorner(""))
        assertNull(Coordinates.parseMgrsCorner("not a grid"))
    }

    // ---- Grid line number scaling ------------------------------------------

    @Test
    fun `grid line numbers scale to five digits`() {
        assertEquals(45000.0, Coordinates.scaleGridLineNumber("45")!!, 0.0)
        assertEquals(45600.0, Coordinates.scaleGridLineNumber("456")!!, 0.0)
        assertEquals(45670.0, Coordinates.scaleGridLineNumber("4567")!!, 0.0)
        assertEquals(45678.0, Coordinates.scaleGridLineNumber("45678")!!, 0.0)
        assertEquals(0.0, Coordinates.scaleGridLineNumber("00")!!, 0.0)
        assertEquals(45000.0, Coordinates.scaleGridLineNumber(" 45 ")!!, 0.0)
    }

    @Test
    fun `grid line numbers reject the shapes a control point cannot use`() {
        assertNull(Coordinates.scaleGridLineNumber(""))
        assertNull(Coordinates.scaleGridLineNumber("4"))
        assertNull(Coordinates.scaleGridLineNumber("456789"))
        assertNull(Coordinates.scaleGridLineNumber("4a5"))
        assertNull(Coordinates.scaleGridLineNumber("45.6"))
        assertNull(Coordinates.scaleGridLineNumber("-45"))
    }
}
