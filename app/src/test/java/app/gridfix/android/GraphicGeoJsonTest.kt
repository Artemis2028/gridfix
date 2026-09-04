package app.gridfix.android

import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.GraphicTypes
import app.gridfix.android.data.TacGraphic
import app.gridfix.android.platform.graphicGeometryKind
import app.gridfix.android.platform.toGeoJson
import app.gridfix.android.platform.toGeoJsonFeature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tactical graphics as GeoJSON — the shape MapLibre consumes on both platforms.
 *
 * The geometry rule has to match the KML export exactly, because the same
 * graphic exported two ways and coming back as two different shapes is a data
 * bug the user only discovers after a round trip through someone else's app.
 * The same table drives the Swift port through [GoldenVectors.graphicGeometry].
 */
class GraphicGeoJsonTest {

    private fun g(
        type: String,
        n: Int,
        name: String = "OBJ FALCON",
        folder: String = "Base",
    ) = TacGraphic(
        id = "g-$type-$n",
        name = name,
        type = type,
        points = (0 until n).map { GeoVertex(24.45 + it * 0.001, 54.37 + it * 0.001) },
        folder = folder,
        affiliation = "friend",
        echelon = "co",
    )

    // ---- The rule, against the shared fixture ------------------------------

    @Test
    fun `geometry kind matches the shared fixture for every type`() {
        for (v in GoldenVectors.graphicGeometry) {
            val expected = if (v.kind == "none") null else v.kind
            assertEquals(
                "${v.type} with ${v.vertexCount} vertices",
                expected,
                graphicGeometryKind(v.type, v.vertexCount),
            )
        }
    }

    @Test
    fun `the fixture agrees with GraphicTypes about which types are areas`() {
        // Two lists of area types that drift apart would close the wrong
        // graphics into polygons, silently.
        val fromApp = GraphicTypes.all.map { it.first }.filter { GraphicTypes.isArea(it) }.toSet()
        assertEquals(GoldenVectors.areaTypes, fromApp)
    }

    @Test
    fun `geometry kind matches the KML exporter exactly`() {
        // buildKml: one vertex is a Point, an area type with 3+ closes into a
        // Polygon, everything else is a LineString.
        for (t in GraphicTypes.all.map { it.first }) {
            for (n in 1..6) {
                val closed = GraphicTypes.isArea(t) && n >= 3
                val kml = when {
                    n == 1 -> "Point"
                    closed -> "Polygon"
                    else -> "LineString"
                }
                assertEquals("$t/$n", kml, graphicGeometryKind(t, n))
            }
        }
    }

    @Test
    fun `a graphic with no vertices produces no feature at all`() {
        // An empty coordinate array is not valid GeoJSON, and one bad feature
        // takes the whole layer down rather than just itself.
        assertNull(graphicGeometryKind("phase_line", 0))
        assertNull(g("phase_line", 0).toGeoJsonFeature())
        assertNull(g("objective", 0).toGeoJsonFeature())
    }

    // ---- The documents themselves ------------------------------------------

    @Test
    fun `a point graphic is a GeoJSON Point in longitude latitude order`() {
        val f = JSONObject(g("trp", 1).toGeoJsonFeature()!!)
        val geom = f.getJSONObject("geometry")
        assertEquals("Point", geom.getString("type"))
        val c = geom.getJSONArray("coordinates")
        assertEquals(54.37, c.getDouble(0), 1e-6)
        assertEquals(24.45, c.getDouble(1), 1e-6)
    }

    @Test
    fun `a line graphic keeps its vertices in order`() {
        val f = JSONObject(g("phase_line", 4).toGeoJsonFeature()!!)
        val geom = f.getJSONObject("geometry")
        assertEquals("LineString", geom.getString("type"))
        val c = geom.getJSONArray("coordinates")
        assertEquals(4L, c.length().toLong())
        assertEquals(54.37, c.getJSONArray(0).getDouble(0), 1e-6)
        assertEquals(54.373, c.getJSONArray(3).getDouble(0), 1e-6)
    }

    @Test
    fun `an area graphic closes its ring`() {
        // A GeoJSON polygon ring must repeat its first vertex last, and the
        // coordinates must be nested one level deeper than a line's.
        val f = JSONObject(g("objective", 4).toGeoJsonFeature()!!)
        val geom = f.getJSONObject("geometry")
        assertEquals("Polygon", geom.getString("type"))
        val rings = geom.getJSONArray("coordinates")
        assertEquals(1L, rings.length().toLong())
        val ring = rings.getJSONArray(0)
        assertEquals(5L, ring.length().toLong())
        assertEquals(ring.getJSONArray(0).getDouble(0), ring.getJSONArray(4).getDouble(0), 1e-9)
        assertEquals(ring.getJSONArray(0).getDouble(1), ring.getJSONArray(4).getDouble(1), 1e-9)
    }

    @Test
    fun `an area type with only two vertices stays a line`() {
        val f = JSONObject(g("objective", 2).toGeoJsonFeature()!!)
        assertEquals("LineString", f.getJSONObject("geometry").getString("type"))
    }

    @Test
    fun `properties carry what the style layers filter on`() {
        val f = JSONObject(g("boundary", 2).toGeoJsonFeature()!!)
        val p = f.getJSONObject("properties")
        assertEquals("g-boundary-2", p.getString("id"))
        assertEquals("OBJ FALCON", p.getString("name"))
        assertEquals("boundary", p.getString("tacType"))
        assertEquals("Base", p.getString("folder"))
        assertEquals("friend", p.getString("affiliation"))
        assertEquals("co", p.getString("echelon"))
    }

    // ---- Escaping ----------------------------------------------------------

    @Test
    fun `a name with quotes and control characters still parses`() {
        // A user can paste anything into a name field. A raw tab or newline
        // inside a JSON string is invalid and would break the whole overlay.
        val nasty = "OBJ \"FALCON\"\tone\ntwo\\three\r\u0001"
        val f = JSONObject(g("objective", 3, name = nasty).toGeoJsonFeature()!!)
        assertEquals(nasty, f.getJSONObject("properties").getString("name"))
    }

    @Test
    fun `a folder name with a quote survives the round trip`() {
        val f = JSONObject(g("nai", 3, folder = "OBJ \"Falcon\"").toGeoJsonFeature()!!)
        assertEquals("OBJ \"Falcon\"", f.getJSONObject("properties").getString("folder"))
    }

    // ---- Collections --------------------------------------------------------

    @Test
    fun `a whole overlay is one FeatureCollection`() {
        val all = listOf(
            g("phase_line", 3), g("objective", 4), g("trp", 1),
            g("phase_line", 0),          // no geometry: must drop out entirely
        )
        val doc = JSONObject(all.toGeoJson())
        assertEquals("FeatureCollection", doc.getString("type"))
        val features: JSONArray = doc.getJSONArray("features")
        assertEquals(3L, features.length().toLong())
        val kinds = (0 until features.length()).map {
            features.getJSONObject(it).getJSONObject("geometry").getString("type")
        }
        assertEquals(listOf("LineString", "Polygon", "Point"), kinds)
    }

    @Test
    fun `an empty overlay is still a valid document`() {
        val doc = JSONObject(emptyList<TacGraphic>().toGeoJson())
        assertEquals("FeatureCollection", doc.getString("type"))
        assertEquals(0L, doc.getJSONArray("features").length().toLong())
    }

    @Test
    fun `every graphic type produces a document that parses`() {
        for (t in GraphicTypes.all.map { it.first }) {
            val min = GraphicTypes.minPoints(t)
            val feature = g(t, min).toGeoJsonFeature()
            assertNotNull("$t produced no feature at its minimum vertex count", feature)
            val f = JSONObject(feature!!)
            assertTrue(f.getJSONObject("geometry").has("coordinates"))
        }
    }
}
