package app.gridfix.android

import app.gridfix.android.data.DEFAULT_FOLDER
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.NO_ALTITUDE
import app.gridfix.android.data.canonicalFolder
import app.gridfix.android.data.matchFolder
import app.gridfix.android.data.hasAltitude
import app.gridfix.android.data.reservedFolderHint
import app.gridfix.android.data.simplifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder naming decides what shows on the map. The 0.9.9 migration collapsed the
 * two legacy folders into Base and 0.9.14 made matching case-insensitive; both
 * are easy to undo by accident.
 */
class FoldersTest {

    @Test
    fun `legacy folders collapse into Base`() {
        assertEquals(DEFAULT_FOLDER, canonicalFolder("Waypoints"))
        assertEquals(DEFAULT_FOLDER, canonicalFolder("Graphics"))
        assertEquals(DEFAULT_FOLDER, canonicalFolder(""))
        assertEquals(DEFAULT_FOLDER, canonicalFolder("   "))
        assertEquals(DEFAULT_FOLDER, canonicalFolder(null))
    }

    @Test
    fun `ordinary names are kept, trimmed`() {
        assertEquals("Recon", canonicalFolder("Recon"))
        assertEquals("Recon", canonicalFolder("  Recon  "))
        assertEquals("OBJ Falcon", canonicalFolder("OBJ Falcon"))
    }

    @Test
    fun `matching is case insensitive against what already exists`() {
        val known = listOf(DEFAULT_FOLDER, "Recon", "OBJ Falcon")
        assertEquals("Recon", matchFolder(known, "recon"))
        assertEquals("Recon", matchFolder(known, "RECON"))
        assertEquals("OBJ Falcon", matchFolder(known, "obj falcon"))
        // A genuinely new name keeps the spelling the operator typed
        assertEquals("Phase Line Blue", matchFolder(known, "Phase Line Blue"))
    }

    @Test
    fun `the reserved names are explained, ordinary ones are not`() {
        assertNotNull(reservedFolderHint("Waypoints"))
        assertNotNull(reservedFolderHint("Graphics"))
        assertNull(reservedFolderHint("Recon"))
        assertNull(reservedFolderHint(""))
        assertNull(reservedFolderHint(DEFAULT_FOLDER))
    }
}

/** The recorded-track helpers: altitude sentinel and shape-preserving decimation. */
class TrackHelpersTest {

    @Test
    fun `a missing altitude is not sea level`() {
        assertTrue("sentinel must be far outside any real height", NO_ALTITUDE < -1000.0)
        assertTrue("the sentinel is not an altitude", !NO_ALTITUDE.hasAltitude())
        assertTrue("sea level is a real altitude", 0.0.hasAltitude())
        assertTrue(612.0.hasAltitude())
    }

    @Test
    fun `simplify keeps the corners and drops the straights`() {
        // A dogleg: two long straights meeting at a sharp turn. Everything on the
        // straights can go; the turn must survive or a backtrack cuts the corner.
        val pts = ArrayList<GeoVertex>()
        for (i in 0..20) pts.add(GeoVertex(36.0, 45.0 + i * 0.0005))
        for (i in 1..20) pts.add(GeoVertex(36.0 + i * 0.0005, 45.01))
        val out = simplifyTrack(pts, 20.0)
        assertTrue("should shed most of the straights, kept ${out.size}", out.size < 8)
        assertEquals("must keep the start", pts.first(), out.first())
        assertEquals("must keep the end", pts.last(), out.last())
        assertTrue("must keep the corner", out.contains(pts[20]))
    }

    @Test
    fun `simplify leaves short tracks alone`() {
        val pts = listOf(GeoVertex(36.0, 45.0), GeoVertex(36.001, 45.001))
        assertEquals(2, simplifyTrack(pts, 20.0).size)
    }
}
