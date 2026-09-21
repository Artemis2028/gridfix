package app.gridfix.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportedTrackSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "f4ebbead-09c6-4c52-9391-7c430510f281"

    private fun gpx(body: String) = InterchangeFiles.parseGpx("<gpx>$body</gpx>".byteInputStream())

    private fun backup(info: TrackInfo, pointBytes: ByteArray): java.io.ByteArrayInputStream {
        val manifest = JSONObject().put("app", "GridFix").put("version", 1)
            .put("tracks", JSONArray().put(JSONObject().put("id", info.id).put("name", info.name)
                .put("startedAt", info.startedAt).put("endedAt", info.endedAt)
                .put("pointCount", info.pointCount).put("distanceM", info.distanceM)))
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("gridfix-backup.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("tracks/${info.id}.txt"))
            zip.write(pointBytes)
            zip.closeEntry()
        }
        return bytes.toByteArray().inputStream()
    }

    @Test fun preEpochTrackTimesBecomeUnknownWhileHistoricalWaypointMetadataSurvives() {
        val data = gpx("""
            <wpt lat="34" lon="-117"><time>1969-12-31T23:59:59Z</time></wpt>
            <trk><trkseg>
              <trkpt lat="34" lon="-117"><time>1969-12-31T23:59:59Z</time></trkpt>
              <trkpt lat="34.001" lon="-117"><time>1970-01-01T00:00:00+01:00</time></trkpt>
              <trkpt lat="34.002" lon="-117"><time>1970-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="34.003" lon="-117"><time>1970-01-01T00:00:00.001Z</time></trkpt>
            </trkseg></trk>
        """.trimIndent())
        assertEquals(-1000L, data.waypoints.single().metadata?.timestampMillis)
        assertEquals(listOf(0L, 0L, 0L, 1L), data.tracks.single().points.map { it.time })
    }

    @Test fun importedTrackFilesRoundTripThroughTheBackupParser() {
        val data = gpx("""
            <trk><name>Historical walk</name><trkseg>
              <trkpt lat="34" lon="-117"><time>1969-12-31T23:59:59Z</time></trkpt>
              <trkpt lat="34.001" lon="-117"><time>2026-09-04T12:00:00.125+04:00</time><ele>150.5</ele></trkpt>
            </trkseg></trk>
        """.trimIndent()).tracks.single()
        val prepared = prepareImportedTrack(data.name, data.points, 1234L, id)
        val root = folder.newFolder("tracks")
        StagedTrackFiles(root).use { stage ->
            stage.stage(id, prepared.second)
            stage.publish(listOf(id))
        }
        val restored = Backup.parse(backup(prepared.first, File(root, "$id.txt").readBytes())).tracks.single()
        assertEquals(prepared, restored)
        assertEquals(1234L, restored.first.startedAt)
        assertEquals(Instant.parse("2026-09-04T08:00:00.125Z").toEpochMilli(), restored.second.last().time)
    }

    @Test fun legacySavedNegativeTimesAreReadableAndBackedUpWithoutChangingTheOriginalLog() {
        val file = folder.newFile("$id.txt")
        val original = "34.0000000 -117.0000000 -1000 -32768.0\n" +
            "34.0010000 -117.0000000 ${Long.MIN_VALUE} 100.0\n" +
            "34.0020000 -117.0000000 0 100.5\n" +
            "34.0030000 -117.0000000 125 101.0\n"
        file.writeText(original)
        val info = TrackInfo(id, "Old GPX import", 1234L, 125L, 0.0, 4)
        val points = TrackRepository.readPoints(file)
        assertEquals(listOf(0L, 0L, 0L, 125L), points.map { it.time })
        val exportedPoints = points.joinToString("") { trackPointLine(it) }.toByteArray()
        val restored = Backup.parse(backup(info, exportedPoints)).tracks.single().second
        assertEquals(points, restored)
        assertEquals(original, file.readText())
        // Only local legacy reads normalize: an incoming archive containing a
        // negative log timestamp still fails validation before any restore writes.
        assertThrows(IllegalArgumentException::class.java) {
            Backup.parse(backup(info, file.readBytes()))
        }
    }

    @Test fun preparationNormalizesCallerSuppliedTimesWithoutInventingPointTimes() {
        val points = listOf(TrackPoint(34.0, -117.0, Long.MIN_VALUE, NO_ALTITUDE),
            TrackPoint(34.001, -117.0, 0L, 100.0))
        val prepared = prepareImportedTrack("  Walk  ", points, 1234L, id, " Imported ")
        assertEquals(listOf(0L, 0L), prepared.second.map { it.time })
        assertEquals(Long.MIN_VALUE, points.first().time)
        assertEquals("Walk", prepared.first.name)
        assertEquals("Imported", prepared.first.folder)
        assertEquals(1234L, prepared.first.startedAt)
        assertEquals(1234L, prepared.first.endedAt)
        assertTrue(prepared.first.distanceM > 100.0)
    }

    @Test fun invalidLatePointCannotProduceAStagingPlan() {
        val first = TrackPoint(34.0, -117.0, 1L, NO_ALTITUDE)
        for (bad in listOf(first.copy(lat = Double.NaN), first.copy(lon = 181.0),
            first.copy(lat = -91.0), first.copy(alt = Double.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) {
                prepareImportedTrack("Walk", listOf(first, bad), 1234L, id)
            }
        }
    }
}
