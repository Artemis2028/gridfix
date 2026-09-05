package app.gridfix.android

import app.gridfix.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MilGpsInteropTest {
    @Test fun `elevation uses a GPX decimal instead of scientific notation`() {
        val xml = InterchangeFiles.buildGpx(listOf(Waypoint("id", "Precise", 34.0, -117.0, 0L,
            metadata = WaypointMetadata(elevationMeters = 0.00001))), emptyList(), emptyList())
        assertTrue(xml.contains("<ele>0.000010</ele>") || xml.contains("<ele>0.00001</ele>"))
        assertEquals(0.00001, InterchangeFiles.parseGpx(xml.byteInputStream()).waypoints.single().metadata!!.elevationMeters!!, 0.0)
    }

    @Test fun `legacy GPX namespaces still import after namespace checks are added`() {
        for (namespace in listOf("", "http://www.topografix.com/GPX/1/0")) {
            val xml = """<gpx xmlns="$namespace"><wpt lat="34" lon="-117"><name>Legacy</name></wpt></gpx>"""
            assertEquals("Legacy", InterchangeFiles.parseGpx(xml.byteInputStream()).waypoints.single().name)
        }
    }

    private val colors = listOf("red", "orange", "yellow", "green", "cyan", "magenta")
    private val codes = listOf(0, 1000, 2100, 3200, 4112, 1106)
    private val whenRecorded = "2020-01-02T03:04:05Z"

    private fun parse(body: String) = InterchangeFiles.parseGpx("""
        <?xml version="1.0" encoding="utf-8" standalone="no"?>
        <gpx creator="MilGPS 7.22.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
             xmlns:m="https://milgps.com/gpx/v1" version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
        $body
        </gpx>
    """.trimIndent().trimStart().byteInputStream())

    private fun examples(): InterchangeFiles.ImportedData = parse(colors.indices.joinToString("\n") { index ->
        // Same-position points with varying attribute and extension order, synthetic coordinates.
        val position = if (index % 2 == 0) "lon=\"-117.12345\" lat=\"34.12345\"" else "lat=\"34.12345\" lon=\"-117.12345\""
        val extensions = listOf("<m:color>${colors[index]}</m:color>", "<m:symbolcode>${codes[index]}</m:symbolcode>")
            .let { if (index % 2 == 0) it.reversed() else it }.joinToString("")
        """<wpt $position><ele>${if (index == 0) 35 else 36}</ele><time>$whenRecorded</time>
            <name>P00${index + 1}</name><extensions>$extensions</extensions></wpt>"""
    })

    @Test fun `all six example appearances and measurements survive import`() {
        val data = examples()
        assertEquals(6, data.waypoints.size)
        assertTrue(data.tracks.isEmpty())
        data.waypoints.forEachIndexed { index, point ->
            assertEquals("P00${index + 1}", point.name)
            assertEquals(34.12345, point.lat, 0.0)
            assertEquals(-117.12345, point.lon, 0.0)
            assertEquals("none", point.affiliation)
            assertEquals(WaypointMetadata(colors[index], codes[index], if (index == 0) 35.0 else 36.0,
                Instant.parse(whenRecorded).toEpochMilli()), point.metadata)
        }
    }

    @Test fun `documented codes decode to shape and character without guessing affiliation`() {
        assertEquals(listOf(
            MilGpsSymbol(MilGpsShape.CROSS, null), MilGpsSymbol(MilGpsShape.CIRCLE, null),
            MilGpsSymbol(MilGpsShape.TRIANGLE, "0"), MilGpsSymbol(MilGpsShape.SQUARE, "!"),
            MilGpsSymbol(MilGpsShape.STAR, "C"), MilGpsSymbol(MilGpsShape.CIRCLE, "6"),
        ), codes.map(MilGpsSymbols::decode))
        assertEquals(MilGpsSymbol(MilGpsShape.SQUARE, "?"), MilGpsSymbols.decode(3201))
        assertEquals(MilGpsSymbol(MilGpsShape.CIRCLE, "Z"), MilGpsSymbols.decode(1135))
        assertEquals(3101, MilGpsSymbols.encode(MilGpsShape.SQUARE, "1"))
        assertEquals(4201, MilGpsSymbols.encode(MilGpsShape.STAR, "?"))
        assertEquals(0, MilGpsSymbols.encode(MilGpsShape.CROSS, "C"))
    }

    @Test fun `unsupported codes never become a made up symbol`() {
        listOf(null, -1, 100, 999, 1136, 1199, 4202, 5000, Int.MAX_VALUE).forEach {
            assertNull("Unexpectedly decoded $it", MilGpsSymbols.decode(it))
        }
    }

    @Test fun `GPX export and reimport preserve all six metadata records`() {
        val imported = examples().waypoints
        val stored = imported.mapIndexed { index, w -> Waypoint("id-$index", w.name, w.lat, w.lon,
            999L, w.folder, w.symbol, w.affiliation, metadata = w.metadata!!) }
        val xml = InterchangeFiles.buildGpx(stored, emptyList(), emptyList())
        assertEquals(imported, InterchangeFiles.parseGpx(xml.byteInputStream()).waypoints)
        assertFalse(xml.contains("1970-01-01T00:00:00.999")) // Import time is not the point's recording time.
    }

    @Test fun `metadata is reset between waypoints including invalid points`() {
        val data = parse("""
            <wpt lat="NaN" lon="0"><name>Bad</name><ele>500</ele><extensions><m:color>red</m:color><m:symbolcode>3200</m:symbolcode></extensions></wpt>
            <wpt lat="34" lon="-117"><name>Plain</name></wpt>
            <wpt lat="34" lon="-117"><name>Plain</name><extensions><m:color>orange</m:color></extensions></wpt>
        """)
        assertEquals(2, data.waypoints.size)
        assertEquals(WaypointMetadata(), data.waypoints[0].metadata)
        assertEquals(WaypointMetadata(color = "orange"), data.waypoints[1].metadata)
        assertEquals(listOf("Plain", "Plain"), data.waypoints.map { it.name })
    }

    @Test fun `namespace and direct parent determine extension meaning`() {
        val w = parse("""
            <wpt lat="34" lon="-117"><name>Correct</name><ele>36</ele><time>$whenRecorded</time>
            <extensions xmlns:other="urn:unrelated">
              <other:color>red</other:color><other:symbolcode>3200</other:symbolcode><other:name>Wrong</other:name>
              <other:time>1999-01-01T00:00:00Z</other:time><other:ele>999</other:ele>
              <other:wrapper><m:color>magenta</m:color></other:wrapper>
            </extensions></wpt>
        """).waypoints.single()
        assertEquals("Correct", w.name)
        assertEquals(WaypointMetadata(elevationMeters = 36.0, timestampMillis = Instant.parse(whenRecorded).toEpochMilli()), w.metadata)
    }

    @Test fun `waypoint altitude and time do not modify the preceding track`() {
        val data = parse("""
            <trk><trkseg><trkpt lat="34" lon="-117"><ele>8</ele><time>$whenRecorded</time></trkpt></trkseg></trk>
            <wpt lat="35" lon="-118"><ele>36</ele><time>2021-01-01T00:00:00Z</time><name>Camp</name></wpt>
        """)
        assertEquals(8.0, data.tracks.single().points.single().alt, 0.0)
        assertEquals(36.0, data.waypoints.single().metadata!!.elevationMeters!!, 0.0)
    }

    @Test fun `invalid optional measurements stay absent and valid epoch time stays zero`() {
        val data = parse("""
            <wpt lat="34" lon="-117"><ele>Infinity</ele><time>bad</time></wpt>
            <wpt lat="34" lon="-117"><ele>0</ele><time>1970-01-01T00:00:00Z</time></wpt>
        """)
        assertEquals(WaypointMetadata(), data.waypoints[0].metadata)
        assertEquals(WaypointMetadata(elevationMeters = 0.0, timestampMillis = 0L), data.waypoints[1].metadata)
    }

    @Test fun `unknown numeric symbol and color survive storage and GPX round trip`() {
        val metadata = WaypointMetadata("ultraviolet", 9876, -12.5, 123L)
        val stored = WaypointMetadata.fromJson(JSONObject(metadata.toJson().toString()))
        assertEquals(metadata, stored)
        assertNull(MilGpsSymbols.decode(stored.milgpsSymbolCode))
        assertNull(MilGpsSymbols.argb(stored.color))
        val xml = InterchangeFiles.buildGpx(listOf(Waypoint("id", "Unknown", 34.0, -117.0, 999L,
            metadata = stored)), emptyList(), emptyList())
        assertEquals(metadata, InterchangeFiles.parseGpx(xml.byteInputStream()).waypoints.single().metadata)
    }

    @Test fun `older records need no metadata migration and absence does not invent measurements`() {
        assertEquals(WaypointMetadata(), WaypointMetadata.fromJson(null))
        assertEquals(WaypointMetadata(), WaypointMetadata.fromJson(JSONObject()))
        val xml = InterchangeFiles.buildGpx(listOf(Waypoint("id", "Old", 34.0, -117.0, 999L)), emptyList(), emptyList())
        assertFalse(xml.contains("<ele>"))
        assertFalse(xml.contains("<time>"))
        assertFalse(xml.contains("<extensions>"))
    }

    @Test fun `GPX export escapes unexpected extension text`() {
        val m = WaypointMetadata(color = "red</milgps:color><name>Injected</name>", milgpsSymbolCode = 9876)
        val xml = InterchangeFiles.buildGpx(listOf(Waypoint("id", "Original", 34.0, -117.0, 0L,
            metadata = m)), emptyList(), emptyList())
        assertTrue(xml.contains("&lt;"))
        val w = InterchangeFiles.parseGpx(xml.byteInputStream()).waypoints.single()
        assertEquals("Original", w.name)
        assertEquals(m.color!!.lowercase(), w.metadata!!.color)
    }
}
