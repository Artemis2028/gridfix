package app.gridfix.android.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataPackageSafetyTest {
    private val waypoint = """<gpx><wpt lat="34" lon="-117"><name>Camp</name></wpt></gpx>""".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun budget(entry: Long = 256, total: Long = 4096, entries: Int = 20) =
        ImportArchiveBudget(ImportArchiveLimits(entry, total, entries))

    @Test fun oversizedSupportedEntryRejectsTheEntireMixedPackage() {
        val archive = zip("good.gpx" to waypoint, "routes/oversized.gpx" to ByteArray(2048) { 32 })
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DataPackage.parse(archive.inputStream(), budget())
        }
        assertTrue(failure.message.orEmpty().contains("routes/oversized.gpx"))
        assertTrue(failure.message.orEmpty().contains("bytes"))
        assertTrue(failure.message.orEmpty().contains("256"))
    }

    @Test fun smallCompressedKmzCannotBypassItsDecompressedEntryLimit() {
        val kmz = zip("doc.kml" to ("<kml><!--" + " ".repeat(4096) + "--></kml>").toByteArray())
        assertTrue(kmz.size < 256)
        val archive = zip("good.gpx" to waypoint, "maps/nested.kmz" to kmz)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DataPackage.parse(archive.inputStream(), budget())
        }
        assertTrue(failure.message.orEmpty().contains("maps/nested.kmz!/doc.kml"))
        assertTrue(failure.message.orEmpty().contains("256"))
    }

    @Test fun ignoredEntriesAndNestedKmzShareTheAggregateBudget() {
        val kmz = zip("ignored.txt" to ByteArray(200))
        val archive = zip("notes.txt" to ByteArray(200), "nested.kmz" to kmz)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DataPackage.parse(archive.inputStream(), budget(entry = 512, total = 400))
        }
        assertTrue(failure.message.orEmpty().contains("nested.kmz!/ignored.txt"))
        assertTrue(failure.message.orEmpty().contains("400"))
    }

    @Test fun entriesAfterTheFirstKmlAreStillBounded() {
        val archive = zip("doc.kml" to "<kml/>".toByteArray(), "ignored.bin" to ByteArray(257))
        assertThrows(IllegalArgumentException::class.java) {
            InterchangeFiles.parseKmz(archive.inputStream(), budget(), "map.kmz")
        }
    }

    @Test fun entryLimitIncludesIgnoredEmptyFiles() {
        val archive = zip("a" to byteArrayOf(), "b" to byteArrayOf(), "c" to byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) {
            DataPackage.parse(archive.inputStream(), budget(entries = 2))
        }
    }

    @Test fun completePackageAtTheByteLimitsStillImports() {
        val archive = zip("good.gpx" to waypoint)
        val data = DataPackage.parse(archive.inputStream(), budget(waypoint.size.toLong(), waypoint.size.toLong()))
        assertEquals("Camp", data.waypoints.single().name)
    }
}
