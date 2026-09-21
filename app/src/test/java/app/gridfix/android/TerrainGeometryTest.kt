package app.gridfix.android

import app.gridfix.android.map.prefetchTerrainTiles
import app.gridfix.android.map.terrainDistanceM
import app.gridfix.android.map.terrainPositionAt
import app.gridfix.android.map.terrainTileCoverage
import app.gridfix.android.map.terrainTileX
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainGeometryTest {
    @Test
    fun `short dateline path samples local terrain instead of crossing Greenwich`() {
        val distance = terrainDistanceM(0.0, 179.99, 0.0, -179.99)
        assertEquals(2223.9016, distance, 0.01)
        for (i in 0..100) {
            val fraction = i / 100.0
            val (lat, lon) = terrainPositionAt(0.0, 179.99, 0.0, -179.99, fraction)
            assertEquals(0.0, lat, 1e-9)
            assertTrue("Every sample must stay at the dateline: $lon", abs(lon) >= 179.989999)
            assertEquals(distance * fraction, terrainDistanceM(0.0, 179.99, lat, lon), 0.001)
        }
    }

    @Test
    fun `reverse dateline traversal follows the same arc`() {
        val eastward = terrainPositionAt(20.0, 179.99, 20.01, -179.99, 0.25)
        val westward = terrainPositionAt(20.01, -179.99, 20.0, 179.99, 0.75)
        assertEquals(eastward.first, westward.first, 1e-8)
        assertEquals(eastward.second, westward.second, 1e-8)
    }

    @Test
    fun `high latitude profile follows great circle and obstruction fraction uses the same distance`() {
        val startLat = 70.0
        val startLon = -30.0
        val endLat = 70.0
        val endLon = 30.0
        val total = terrainDistanceM(startLat, startLon, endLat, endLon)
        val midpoint = terrainPositionAt(startLat, startLon, endLat, endLon, 0.5)
        assertTrue("The great circle bends north of the parallel", midpoint.first > 72.0)
        assertEquals(0.0, midpoint.second, 1e-8)
        assertEquals(total / 2, terrainDistanceM(startLat, startLon, midpoint.first, midpoint.second), 0.001)
        val obstruction = terrainPositionAt(startLat, startLon, endLat, endLon, 0.37)
        assertEquals(total * 0.37, terrainDistanceM(startLat, startLon, obstruction.first, obstruction.second), 0.001)
    }

    @Test
    fun `coincident and antipodal endpoints stay finite`() {
        val same = terrainPositionAt(24.0, 54.0, 24.0, 54.0, 0.5)
        assertEquals(24.0, same.first, 1e-8)
        assertEquals(54.0, same.second, 1e-8)
        val antipodal = terrainPositionAt(0.0, 0.0, 0.0, 180.0, 0.5)
        assertTrue(antipodal.first.isFinite() && antipodal.second.isFinite())
        assertEquals(90.0, antipodal.first, 1e-8)
    }

    @Test
    fun `dateline box covers only its four edge tiles`() {
        val coverage = terrainTileCoverage(0.01, -0.01, 179.99, -179.99, 13)
        assertEquals(listOf(8191..8191, 0..0), coverage.columns)
        assertEquals(4095..4096, coverage.rows)
        assertEquals(4L, coverage.count)
    }

    @Test
    fun `lookups wrap longitudes and full world coverage never duplicates a column`() {
        assertEquals(terrainTileX(-179.99, 13), terrainTileX(180.01, 13), 1e-8)
        assertEquals(terrainTileX(-180.0, 13), terrainTileX(180.0, 13), 0.0)
        val coverage = terrainTileCoverage(85.0, -85.0, -180.0, 180.0, 13)
        assertEquals(listOf(0..8191), coverage.columns)
        assertTrue(coverage.rows.first >= 0 && coverage.rows.last <= 8191)
    }

    @Test
    fun `oversized elevation area is rejected without requesting any tiles`() = runBlocking {
        val coverage = terrainTileCoverage(40.0, 39.0, -105.0, -104.0, 13)
        assertEquals(720L, coverage.count)
        var requests = 0
        val result = prefetchTerrainTiles(coverage) { _, _ -> requests++; true }
        assertEquals(0, requests)
        assertEquals(720L, result.expected)
        assertEquals(720L, result.omitted)
        assertTrue(result.tooLarge)
        assertFalse(result.complete)
    }

    @Test
    fun `partial fetch reports missing tiles and still attempts the complete allowed area`() = runBlocking {
        val coverage = terrainTileCoverage(0.01, -0.01, 179.99, -179.99, 13)
        val requested = mutableSetOf<Pair<Int, Int>>()
        val result = prefetchTerrainTiles(coverage) { x, y ->
            assertTrue("Each wrapped tile is requested once", requested.add(x to y))
            x != 0 || y != 4095
        }
        assertEquals(setOf(8191 to 4095, 8191 to 4096, 0 to 4095, 0 to 4096), requested)
        assertEquals(4L, result.expected)
        assertEquals(3, result.cached)
        assertEquals(1, result.failed)
        assertEquals(0L, result.omitted)
        assertFalse(result.complete)
        assertFalse(result.tooLarge)
    }

    @Test
    fun `only complete successful coverage reports complete`() = runBlocking {
        val coverage = terrainTileCoverage(0.01, -0.01, 179.99, -179.99, 13)
        val success = prefetchTerrainTiles(coverage) { _, _ -> true }
        assertEquals(4, success.cached)
        assertEquals(0, success.failed)
        assertTrue(success.complete)
        val failure = prefetchTerrainTiles(coverage) { _, _ -> false }
        assertEquals(0, failure.cached)
        assertEquals(4, failure.failed)
        assertFalse(failure.complete)
        val empty = prefetchTerrainTiles(terrainTileCoverage(-1.0, 1.0, 0.0, 1.0, 13)) { _, _ -> true }
        assertEquals(0L, empty.expected)
        assertFalse(empty.complete)
    }
}
