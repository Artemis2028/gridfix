package app.gridfix.android

import app.gridfix.android.coords.Geodesy
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.InterchangeFiles
import app.gridfix.android.data.NO_ALTITUDE
import app.gridfix.android.data.TacGraphic
import app.gridfix.android.data.TrackInfo
import app.gridfix.android.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InterchangeFilesTest {
    private fun gpx(body: String) = InterchangeFiles.parseGpx(
        """<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">$body</gpx>""".byteInputStream(),
    )

    private fun kml(body: String) = InterchangeFiles.parseKml(
        """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>$body</Document></kml>""".byteInputStream(),
    )

    private fun info() = TrackInfo("track", "Walk & return", 0L, 1L, 0.0, 2)

    @Test
    fun `invalid GPX point cannot replace previous point metadata`() {
        val data = gpx("""
            <trk><name>Walk</name><trkseg>
              <trkpt lat="24.0" lon="54.0"><ele>100</ele><time>2026-09-04T08:00:00Z</time></trkpt>
              <trkpt lat="NaN" lon="54.1"><ele>999</ele><time>2026-09-04T09:00:00Z</time></trkpt>
              <trkpt lat="24.2" lon="54.2"/>
            </trkseg></trk>
        """.trimIndent())
        val points = data.tracks.single().points
        assertEquals(2, points.size)
        assertEquals(100.0, points.first().alt, 0.0)
        assertEquals(Instant.parse("2026-09-04T08:00:00Z").toEpochMilli(), points.first().time)
        assertEquals(NO_ALTITUDE, points.last().alt, 0.0)
        assertEquals(0L, points.last().time)
    }

    @Test
    fun `separate GPX segments never acquire a connecting leg`() {
        val data = gpx("""
            <trk><name>Interrupted walk</name>
              <trkseg><trkpt lat="24.0" lon="54.0"/><trkpt lat="24.001" lon="54.0"/></trkseg>
              <trkseg><trkpt lat="25.0" lon="55.0"/><trkpt lat="25.001" lon="55.0"/></trkseg>
            </trk>
        """.trimIndent())
        assertEquals(2, data.tracks.size)
        assertEquals(listOf("Interrupted walk — segment 1", "Interrupted walk — segment 2"), data.tracks.map { it.name })
        assertEquals(listOf(24.0, 24.001), data.tracks[0].points.map { it.lat })
        assertEquals(listOf(25.0, 25.001), data.tracks[1].points.map { it.lat })
    }

    @Test
    fun `a one point GPX segment is preserved and empty segments are ignored`() {
        val data = gpx("""
            <trk><name>Isolated fix</name><trkseg/>
              <trkseg><trkpt lat="24" lon="54"/></trkseg>
              <trkseg><trkpt lat="100" lon="54"/></trkseg>
            </trk>
        """.trimIndent())
        assertEquals("Isolated fix", data.tracks.single().name)
        assertEquals(1, data.tracks.single().points.size)
    }

    @Test
    fun `GPX numeric timezone offsets and fractional seconds are preserved`() {
        val data = gpx("""
            <trk><trkseg>
              <trkpt lat="24" lon="54"><time>2026-09-04T12:00:00.125+04:00</time></trkpt>
              <trkpt lat="24.1" lon="54"><time>2026-09-04T03:00:00.125-05:00</time></trkpt>
            </trkseg></trk>
        """.trimIndent())
        val expected = Instant.parse("2026-09-04T08:00:00.125Z").toEpochMilli()
        assertEquals(listOf(expected, expected), data.tracks.single().points.map { it.time })
    }

    @Test
    fun `invalid or partial GPX timestamps remain unknown`() {
        val data = gpx("""
            <trk><trkseg>
              <trkpt lat="24" lon="54"><time>2026-02-30T08:00:00Z</time></trkpt>
              <trkpt lat="24.1" lon="54"><time>2026-09-04T08:00:00Zgarbage</time></trkpt>
              <trkpt lat="24.2" lon="54"><time>2026-09-04T08:00:00</time></trkpt>
            </trkseg></trk>
        """.trimIndent())
        assertTrue(data.tracks.single().points.all { it.time == 0L })
    }

    @Test
    fun `GPX export omits unknown measurements instead of inventing epoch and sea level`() {
        val xml = InterchangeFiles.buildGpx(emptyList(), emptyList(), listOf(
            info() to listOf(TrackPoint(24.0, 54.0, 0L, NO_ALTITUDE)),
        ))
        assertFalse(xml.contains("<time>"))
        assertFalse(xml.contains("<ele>"))
        val restored = InterchangeFiles.parseGpx(xml.byteInputStream()).tracks.single()
        assertEquals("Walk & return", restored.name)
        assertEquals(0L, restored.points.single().time)
        assertEquals(NO_ALTITUDE, restored.points.single().alt, 0.0)
    }

    @Test
    fun `GPX export round trip preserves millisecond timestamps`() {
        val time = Instant.parse("2026-09-04T08:00:00.125Z").toEpochMilli()
        val point = TrackPoint(24.0, 54.0, time, 100.5)
        val xml = InterchangeFiles.buildGpx(emptyList(), emptyList(), listOf(info() to listOf(point)))
        assertEquals(point, InterchangeFiles.parseGpx(xml.byteInputStream()).tracks.single().points.single())
    }

    @Test
    fun `closed KML route retains its return to the start`() {
        val data = kml("""
            <Placemark><name>Out and back</name><LineString>
              <coordinates>54,24 54.1,24.1 54,24</coordinates>
            </LineString></Placemark>
        """.trimIndent())
        val points = data.lines.single().points
        assertEquals(3, points.size)
        assertEquals(points.first(), points.last())
    }

    @Test
    fun `KML polygon closure is removed only for polygon storage`() {
        val data = kml("""
            <Placemark><Polygon><outerBoundaryIs><LinearRing>
              <coordinates>54,24 54.1,24 54.1,24.1 54,24</coordinates>
            </LinearRing></outerBoundaryIs></Polygon></Placemark>
        """.trimIndent())
        assertEquals(3, data.areas.single().points.size)
        assertNotEquals(data.areas.single().points.first(), data.areas.single().points.last())
    }

    @Test
    fun `KML exports a range ring as a closed circular outline`() {
        val center = GeoVertex(24.0, 54.0)
        val edge = GeoVertex(24.01, 54.0)
        val ring = TacGraphic("ring", "1000 m ring", "ring", listOf(center, edge))
        val xml = InterchangeFiles.buildKml(emptyList(), listOf(ring), emptyList())
        val result = InterchangeFiles.parseKml(xml.byteInputStream())
        assertTrue(result.areas.isEmpty())
        val points = result.lines.single().points
        assertEquals(73, points.size)
        assertEquals(points.first(), points.last())
        val radius = Geodesy.greatCircleMeters(center.lat, center.lon, edge.lat, edge.lon)
        for (p in points) assertEquals(radius, Geodesy.greatCircleMeters(center.lat, center.lon, p.lat, p.lon), 0.02)
    }

    @Test
    fun `KML sector export includes the apex and sampled arc at the outer radius`() {
        val apex = GeoVertex(0.0, 0.0)
        val sector = TacGraphic("sector", "NE", "sector", listOf(apex, GeoVertex(0.01, 0.0), GeoVertex(0.0, 0.02)))
        val xml = InterchangeFiles.buildKml(emptyList(), listOf(sector), emptyList())
        val result = InterchangeFiles.parseKml(xml.byteInputStream())
        assertTrue(result.lines.isEmpty())
        val points = result.areas.single().points
        assertEquals(apex, points.first())
        assertTrue(points.size > 3)
        val radius = Geodesy.greatCircleMeters(0.0, 0.0, 0.0, 0.02)
        for (p in points.drop(1)) {
            assertTrue(p.lat >= 0.0 && p.lon >= 0.0)
            assertEquals(radius, Geodesy.greatCircleMeters(0.0, 0.0, p.lat, p.lon), 0.02)
        }
    }

    @Test
    fun `namespace prefixes do not hide GPX points`() {
        val data = InterchangeFiles.parseGpx("""
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg>
              <g:trkpt lat="24" lon="54"/>
            </g:trkseg></g:trk></g:gpx>
        """.trimIndent().byteInputStream())
        assertEquals(24.0, data.tracks.single().points.single().lat, 0.0)
    }
}
