package app.gridfix.android

import app.gridfix.android.map.ELEVATION_MAX_CACHED_TILES
import app.gridfix.android.map.ElevationStore
import app.gridfix.android.map.GridLine
import app.gridfix.android.map.ImageQuad
import app.gridfix.android.map.chooseGridInterval
import app.gridfix.android.map.gridIntervalLabel
import app.gridfix.android.map.trimOldest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The engine-agnostic models the MapLibre and iOS ports build on. These have no
 * Canvas and no osmdroid, so they are the first part of the map that CI can
 * actually check — the overlay itself is only ever proven on a screen.
 */
class PortableModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- Grid interval: must stay in step with MgrsGridOverlay.draw() ------

    @Test
    fun `interval is the finest grid that still clears the minimum spacing`() {
        // 48 dp of screen per line: at density 2 that is 96 px.
        assertEquals(10L, chooseGridInterval(metersPerPixel = 0.05, density = 2f).toLong())
        assertEquals(100L, chooseGridInterval(metersPerPixel = 1.0, density = 2f).toLong())
        assertEquals(1000L, chooseGridInterval(metersPerPixel = 10.0, density = 2f).toLong())
        assertEquals(10000L, chooseGridInterval(metersPerPixel = 100.0, density = 2f).toLong())
        assertEquals(100000L, chooseGridInterval(metersPerPixel = 1000.0, density = 2f).toLong())
    }

    @Test
    fun `zoomed all the way out the metre grid gives way to GZD`() {
        assertEquals(0L, chooseGridInterval(metersPerPixel = 5000.0, density = 2f).toLong())
    }

    @Test
    fun `a denser screen asks for a coarser interval at the same zoom`() {
        val coarse = chooseGridInterval(metersPerPixel = 2.0, density = 1f)
        val dense = chooseGridInterval(metersPerPixel = 2.0, density = 4f)
        assertTrue("density should never make the grid finer", dense >= coarse)
    }

    @Test
    fun `nonsense input does not crash the chooser`() {
        assertEquals(0L, chooseGridInterval(metersPerPixel = 0.0, density = 2f).toLong())
        assertEquals(0L, chooseGridInterval(metersPerPixel = -1.0, density = 2f).toLong())
        assertEquals(0L, chooseGridInterval(metersPerPixel = 1.0, density = 0f).toLong())
    }

    @Test
    fun `every interval the chooser can return has a label`() {
        // Guards the two lists drifting apart: a new interval with no label
        // would silently render as "10 m" on the readout.
        val named = mapOf(
            0 to "GZD", 100000 to "100 km", 10000 to "10 km",
            1000 to "1 km", 100 to "100 m", 10 to "10 m",
        )
        val seen = mutableSetOf<Int>()
        var mpp = 0.01
        while (mpp < 100000.0) {
            seen += chooseGridInterval(mpp, 2f)
            mpp *= 1.2
        }
        for (i in seen) {
            assertEquals("interval $i", named.getValue(i), gridIntervalLabel(i))
        }
        assertTrue("chooser should exercise more than one interval", seen.size > 3)
    }

    // ---- Calibrated image quad (roadmap A / C) -----------------------------

    @Test
    fun `an image quad needs exactly four corners`() {
        val ok = ImageQuad(
            id = "a", name = "sketch",
            corners = listOf(1.0 to 1.0, 1.0 to 2.0, 2.0 to 2.0, 2.0 to 1.0),
        )
        assertEquals(4L, ok.corners.size.toLong())
        assertEquals(1f, ok.opacity, 0f)
        assertTrue(ok.visible)
        for (bad in listOf(0, 1, 3, 5)) {
            val corners = List(bad) { 0.0 to 0.0 }
            var threw = false
            try {
                ImageQuad(id = "b", name = "bad", corners = corners)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("$bad corners should be rejected", threw)
        }
    }

    @Test
    fun `a grid line defaults to the metre grid and is not heavy`() {
        val line = GridLine(points = listOf(0.0 to 0.0, 0.0 to 1.0))
        assertEquals(GridLine.Kind.METRE, line.kind)
        assertTrue(!line.heavy)
    }

    // ---- Elevation cache trim ---------------------------------------------

    private fun store(root: File) = object : ElevationStore {
        override val dir: File = root
    }

    @Test
    fun `trim removes the oldest tiles beyond the cap`() {
        val dir = temp.newFolder("dem")
        val s = store(dir)
        for (i in 0 until 10) {
            val f = s.fileFor(12, i, 0)
            f.writeBytes(ByteArray(4))
            f.setLastModified(1_000_000_000L + i * 60_000L)
        }
        assertEquals(4L, s.trimOldest(maxFiles = 6).toLong())
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(6L, left.size.toLong())
        assertTrue("oldest should be gone", "12_0_0.png" !in left)
        assertTrue("newest should stay", "12_9_0.png" in left)
    }

    @Test
    fun `trim is a no-op under the cap and on a missing directory`() {
        val dir = temp.newFolder("dem2")
        val s = store(dir)
        assertEquals(0L, s.trimOldest(maxFiles = 400).toLong())
        s.fileFor(10, 1, 1).writeBytes(ByteArray(1))
        assertEquals(0L, s.trimOldest(maxFiles = 400).toLong())
        assertEquals(0L, store(File(dir, "nope")).trimOldest().toLong())
    }

    @Test
    fun `trim ignores files that are not tiles`() {
        val dir = temp.newFolder("dem3")
        val s = store(dir)
        for (i in 0 until 5) s.fileFor(9, i, 0).writeBytes(ByteArray(1))
        File(dir, "notes.txt").writeText("keep me")
        assertEquals(3L, s.trimOldest(maxFiles = 2).toLong())
        assertTrue(File(dir, "notes.txt").exists())
    }

    @Test
    fun `tile paths are stable and the default cap is what the prefetch assumes`() {
        val dir = temp.newFolder("dem4")
        assertEquals("12_345_678.png", store(dir).fileFor(12, 345, 678).name)
        assertEquals(400L, ELEVATION_MAX_CACHED_TILES.toLong())
    }
}
