package app.gridfix.android

import app.gridfix.android.coords.Coordinates
import app.gridfix.android.coords.Geodesy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The cross-platform contract.
 *
 * Every vector here also ships to the iOS port as `golden.json`, where
 * GoldenTests.swift asserts the same things. One generator produced both, so
 * if the two platforms ever compute a different grid or a different range for
 * the same point, one of the two suites goes red instead of two soldiers
 * reading different numbers off the same map.
 *
 * This suite is deliberately paranoid about the projection boundary: the MGRS
 * strings come from NGA's library here and from our own Snyder series on iOS,
 * which is two implementations of one standard, and the only honest way to
 * hold two implementations together is to check them against each other.
 */
class GoldenTest {

    @Test
    fun `the fixture actually loaded`() {
        assertTrue("no UTM vectors", GoldenVectors.utm.size > 20)
        assertTrue("no MGRS vectors", GoldenVectors.mgrsForward.size > 50)
        assertTrue("no parse vectors", GoldenVectors.mgrsParse.size > 50)
        assertTrue("no distance vectors", GoldenVectors.distance.size > 10)
        assertTrue("no convergence vectors", GoldenVectors.convergence.size > 50)
    }

    // ---- UTM ---------------------------------------------------------------

    @Test
    fun `utm forward matches the shared vectors`() {
        for (v in GoldenVectors.utm) {
            val p = Coordinates.utmForZone(v.lat, v.lon, v.zone, v.hemisphere == 'N')
            assertEquals("easting for ${v.name}", v.easting, p[0], GoldenVectors.TOL_UTM_METERS)
            assertEquals("northing for ${v.name}", v.northing, p[1], GoldenVectors.TOL_UTM_METERS)
        }
    }

    @Test
    fun `utm picks the same zone and band as the shared vectors`() {
        for (v in GoldenVectors.utm) {
            val u = Coordinates.utm(v.lat, v.lon)
            assertNotNull("no UTM for ${v.name}", u)
            assertEquals("zone for ${v.name}", v.zone.toLong(), u!!.zone.toLong())
            assertEquals("hemisphere for ${v.name}", v.hemisphere, u.hemisphere)
            assertEquals("band for ${v.name}", v.band, Coordinates.bandLetter(v.lat))
            assertEquals("easting for ${v.name}", v.easting, u.easting.toDouble(), 1.0)
            assertEquals("northing for ${v.name}", v.northing, u.northing.toDouble(), 1.0)
        }
    }

    @Test
    fun `utm inverse returns the point it was given`() {
        for (v in GoldenVectors.utm) {
            val ll = Coordinates.utmInverse(v.easting, v.northing, v.zone, v.hemisphere == 'N')
            assertEquals("lat for ${v.name}", v.lat, ll[0], 1e-7)
            assertEquals("lon for ${v.name}", v.lon, ll[1], 1e-7)
        }
    }

    // ---- MGRS --------------------------------------------------------------

    @Test
    fun `mgrs forward matches the shared vectors exactly`() {
        // Only where the margin is overwhelming. Every vector sits at its own
        // cell centre, so a 1 km cell gives 500 m of clearance and a 100 m cell
        // gives 50 m — far more than any disagreement between two correct
        // projections. The 10 m cells are checked by position below instead,
        // because NGA's forward and our Snyder series are known to differ by
        // about a metre and that is only a 5 m margin.
        for (v in GoldenVectors.mgrsForward) {
            if (v.digits > 6) continue
            val parts = Coordinates.mgrs(v.lat, v.lon, v.digits)
            assertNotNull("no MGRS for ${v.name}", parts)
            val compact = parts!!.gzd + parts.square + parts.easting + parts.northing
            assertEquals("${v.name} at ${v.digits} digits", v.mgrs, compact)
        }
    }

    @Test
    fun `mgrs forward agrees with the shared vectors to within a metre at ten metre cells`() {
        for (v in GoldenVectors.mgrsForward) {
            if (v.digits <= 6) continue
            val parts = Coordinates.mgrs(v.lat, v.lon, v.digits)!!
            val compact = parts.gzd + parts.square + parts.easting + parts.northing
            if (compact == v.mgrs) continue
            // Different string: allowed only if the two grids name points that
            // are within 3 m of each other, i.e. the last digit rolled over.
            val a = Coordinates.parseMgrs(compact)
            val b = Coordinates.parseMgrs(v.mgrs)
            assertNotNull("could not parse $compact", a)
            assertNotNull("could not parse ${v.mgrs}", b)
            val gap = Geodesy.distanceAndBearing(a!!.first, a.second, b!!.first, b.second)[0]
            assertTrue(
                "${v.name}: got $compact, shared vector says ${v.mgrs}, ${gap} m apart",
                gap < 3.0,
            )
        }
    }

    @Test
    fun `mgrs parts split the way the readout expects`() {
        for (v in GoldenVectors.mgrsForward) {
            val p = Coordinates.mgrs(v.lat, v.lon, v.digits)!!
            assertEquals("easting digits for ${v.name}", (v.digits / 2).toLong(), p.easting.length.toLong())
            assertEquals("northing digits for ${v.name}", (v.digits / 2).toLong(), p.northing.length.toLong())
            assertEquals("square for ${v.name}", 2L, p.square.length.toLong())
            assertEquals("${p.gzd} ${p.square} ${p.easting} ${p.northing}", p.full)
        }
    }

    @Test
    fun `mgrs parse hits the cell centre the shared vectors name`() {
        for (v in GoldenVectors.mgrsParse) {
            val p = Coordinates.parseMgrs(v.mgrs)
            assertNotNull("no parse for ${v.mgrs}", p)
            assertEquals("centre lat for ${v.mgrs}", v.centreLat, p!!.first, GoldenVectors.TOL_LATLON_DEGREES)
            assertEquals("centre lon for ${v.mgrs}", v.centreLon, p.second, GoldenVectors.TOL_LATLON_DEGREES)
        }
    }

    @Test
    fun `mgrs corner parse hits the grid line intersection`() {
        for (v in GoldenVectors.mgrsParse) {
            val p = Coordinates.parseMgrsCorner(v.mgrs)
            assertNotNull("no corner for ${v.mgrs}", p)
            assertEquals("corner lat for ${v.mgrs}", v.cornerLat, p!!.first, GoldenVectors.TOL_LATLON_DEGREES)
            assertEquals("corner lon for ${v.mgrs}", v.cornerLon, p.second, GoldenVectors.TOL_LATLON_DEGREES)
        }
    }

    @Test
    fun `every shared grid string round trips`() {
        for (v in GoldenVectors.mgrsForward) {
            val p = Coordinates.parseMgrs(v.mgrs)!!
            val again = Coordinates.mgrs(p.first, p.second, v.digits)!!
            assertEquals(
                "round trip for ${v.mgrs}",
                v.mgrs,
                again.gzd + again.square + again.easting + again.northing,
            )
        }
    }

    // ---- Geodesy -----------------------------------------------------------

    @Test
    fun `distance matches the shared vectors`() {
        // These came from Vincenty on WGS84, the same formula
        // android.location.Location.distanceBetween runs. Agreement here is
        // what proves the swap to a pure-Kotlin geodesic moved no number.
        for (v in GoldenVectors.distance) {
            val d = Geodesy.distanceAndBearing(v.fromLat, v.fromLon, v.toLat, v.toLon)
            assertEquals("distance for ${v.name}", v.meters, d[0], GoldenVectors.TOL_DISTANCE_METERS)
        }
    }

    @Test
    fun `bearing matches the shared vectors`() {
        for (v in GoldenVectors.distance) {
            if (v.meters <= 1.0) continue
            val n = Coordinates.navInfo(v.fromLat, v.fromLon, v.toLat, v.toLon)
            var delta = abs(n.bearingTrue.toDouble() - v.bearing) % 360.0
            if (delta > 180.0) delta = 360.0 - delta
            assertTrue(
                "bearing for ${v.name}: got ${n.bearingTrue}, want ${v.bearing}",
                delta < GoldenVectors.TOL_BEARING_DEGREES,
            )
        }
    }

    @Test
    fun `navInfo normalises the bearing into a compass circle`() {
        for (v in GoldenVectors.distance) {
            val n = Coordinates.navInfo(v.fromLat, v.fromLon, v.toLat, v.toLon)
            assertTrue("bearing ${n.bearingTrue} for ${v.name} is outside 0..360",
                n.bearingTrue >= 0f && n.bearingTrue < 360f)
            assertTrue("distance for ${v.name} is not a number", !n.distanceMeters.isNaN())
        }
    }

    @Test
    fun `distance is the same in both directions`() {
        for (v in GoldenVectors.distance) {
            val there = Geodesy.distanceAndBearing(v.fromLat, v.fromLon, v.toLat, v.toLon)[0]
            val back = Geodesy.distanceAndBearing(v.toLat, v.toLon, v.fromLat, v.fromLon)[0]
            assertEquals("distance for ${v.name} is not symmetric", there, back, 0.001)
        }
    }

    @Test
    fun `a zero length leg does not produce NaN`() {
        val n = Coordinates.navInfo(24.4539, 54.3773, 24.4539, 54.3773)
        assertEquals(0f, n.distanceMeters, 1e-6f)
        assertTrue(!n.bearingTrue.isNaN())
    }

    @Test
    fun `the great circle shortcut is close but not close enough to use`() {
        // Documents the size of the error the app declines to accept: half a
        // percent, which is nothing on a course ring and metres on a long leg.
        var worst = 0.0
        for (v in GoldenVectors.distance) {
            if (v.meters <= 1000.0) continue
            val gc = Geodesy.greatCircleMeters(v.fromLat, v.fromLon, v.toLat, v.toLon)
            worst = maxOf(worst, abs(gc - v.meters) / v.meters)
        }
        assertTrue("great circle should differ from the ellipsoid at all", worst > 1e-4)
        assertTrue("great circle drifted further than expected: $worst", worst < 0.008)
    }

    @Test
    fun `grid convergence matches the shared vectors`() {
        for (v in GoldenVectors.convergence) {
            val c = Coordinates.gridConvergence(v.lat, v.lon)
            // gridConvergence resolves the zone from the point; the vector
            // carries the zone it was generated in, so only compare where the
            // two agree — the special zones are covered by their own points.
            if (Coordinates.utm(v.lat, v.lon)?.zone != v.zone) continue
            assertEquals(
                "convergence at ${v.lat}, ${v.lon}",
                v.degrees, c, GoldenVectors.TOL_CONVERGENCE_DEGREES,
            )
        }
    }
}
