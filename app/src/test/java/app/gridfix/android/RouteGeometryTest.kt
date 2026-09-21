package app.gridfix.android

import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.routeCardLegs
import app.gridfix.android.data.routeLegBearings
import app.gridfix.android.data.routeSketchCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    private val acrossDateline = listOf(GeoVertex(1.0, 179.99), GeoVertex(1.0, -179.99))

    @Test
    fun `magnetic forward and return bearings use the actual start and end locations`() {
        val locations = mutableListOf<GeoVertex>()
        val bearings = routeLegBearings(acrossDateline[0], acrossDateline[1], 1, null) { lat, lon ->
            locations += GeoVertex(lat, lon)
            if (lon > 0) 10f else -5f
        }
        assertEquals(acrossDateline, locations)
        assertEquals(79.999825, bearings.forward.toDouble(), 0.001)
        assertEquals(275.000175, bearings.reverse.toDouble(), 0.001)
        assertTrue(bearings.distanceMeters in 2200f..2250f)
    }

    @Test
    fun `grid references on opposite sides of a zone boundary belong to their respective endpoints`() {
        val bearings = routeLegBearings(GeoVertex(78.0, 8.99), GeoVertex(78.0, 9.01), 2, null) { _, _ ->
            error("Grid bearings must not request a magnetic model")
        }
        assertEquals(84.130193, bearings.forward.toDouble(), 0.01)
        assertEquals(275.869807, bearings.reverse.toDouble(), 0.01)
    }

    @Test
    fun `return bearing follows the reverse geodesic instead of adding 180 to the forward bearing`() {
        val bearings = routeLegBearings(GeoVertex(60.0, 0.0), GeoVertex(60.0, 30.0), 0, null) { _, _ ->
            error("True bearings must not request a magnetic model")
        }
        assertEquals(76.9357, bearings.forward.toDouble(), 0.02)
        assertEquals(283.0643, bearings.reverse.toDouble(), 0.02)
        assertTrue(kotlin.math.abs(bearings.reverse - ((bearings.forward + 180f) % 360f)) > 20f)
    }

    @Test
    fun `shared route card rows honor manual declination and recompute changed vertices`() {
        val settings = AppSettings(northRef = 1, declinationOverride = 10f, pacePer100m = 65)
        val row = routeCardLegs(acrossDateline, settings) { _, _ -> error("Manual override must bypass model") }.single()
        assertEquals("080°", row.azimuth)
        assertEquals("260°", row.backAzimuth)
        val changedReference = routeCardLegs(acrossDateline, settings.copy(declinationOverride = 20f)) { _, _ ->
            error("Manual override must bypass model")
        }.single()
        assertEquals("070°", changedReference.azimuth)
        assertEquals("250°", changedReference.backAzimuth)

        val changedPoints = routeCardLegs(
            listOf(acrossDateline.first(), GeoVertex(1.01, 179.99)), settings,
        ) { _, _ -> error("Manual override must bypass model") }.single()
        assertEquals("350°", changedPoints.azimuth)
        assertEquals("170°", changedPoints.backAzimuth)
        assertTrue(changedPoints.distanceMeters < row.distanceMeters)
    }

    @Test
    fun `ordinary equatorial route keeps its known bearings distance and pace count`() {
        val row = routeCardLegs(listOf(GeoVertex(0.0, 0.0), GeoVertex(0.0, 0.01)), AppSettings()) { _, _ ->
            error("True bearings must not request a magnetic model")
        }.single()
        assertEquals("090°", row.azimuth)
        assertEquals("270°", row.backAzimuth)
        assertEquals(1113.1949, row.distanceMeters.toDouble(), 0.01)
        assertEquals(724, row.paces)
        assertEquals(1, row.index)
    }

    @Test
    fun `PDF sketch preserves short dateline legs and their east west directions`() {
        val east = routeSketchCoordinates(acrossDateline)
        assertEquals(0.0, east[0].x, 0.0)
        assertTrue(east[1].x in 0.019..0.021)
        val west = routeSketchCoordinates(acrossDateline.reversed())
        assertTrue(west[1].x in -0.021..-0.019)

        val turns = routeSketchCoordinates(listOf(
            GeoVertex(0.0, 179.99), GeoVertex(0.01, -179.99), GeoVertex(0.02, 179.98),
        ))
        assertTrue(turns[1].x > turns[0].x)
        assertTrue(turns[2].x < turns[0].x)
        assertEquals(0.02, turns[2].y, 0.0)
        assertTrue(turns.maxOf { it.x } - turns.minOf { it.x } < 0.04)
    }

    @Test
    fun `ordinary PDF sketch preserves relative geometry and degenerate inputs`() {
        val sketch = routeSketchCoordinates(listOf(GeoVertex(0.0, 10.0), GeoVertex(0.0, 10.01)))
        assertEquals(0.01, sketch[1].x - sketch[0].x, 0.000001)
        assertEquals(0.0, sketch[1].y, 0.0)
        assertTrue(routeSketchCoordinates(emptyList()).isEmpty())
        assertEquals(0.0, routeSketchCoordinates(listOf(GeoVertex(45.0, 180.0))).single().x, 0.0)
    }
}
