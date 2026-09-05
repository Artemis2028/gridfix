package app.gridfix.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupSafetyTest {
    @Test fun backupRestoresMilGpsMetadataWithoutChangingAffiliation() {
        val metadata = WaypointMetadata("red", 3200, 35.0, 123456789L)
        val root = manifest()
        root.getJSONArray("waypoints").getJSONObject(0).put("metadata", metadata.toJson())
        val restored = Backup.parse(backup(root)).waypoints.single()
        assertEquals(metadata, restored.metadata)
        assertEquals("none", restored.affiliation)
    }

    @Test fun oldBackupsRestoreWithEmptyOptionalMetadata() {
        assertEquals(WaypointMetadata(), Backup.parse(backup(manifest())).waypoints.single().metadata)
    }

    private val id = "f4ebbead-09c6-4c52-9391-7c430510f281"

    private fun manifest() = JSONObject().put("app", "GridFix").put("version", 1)
        .put("waypoints", JSONArray().put(JSONObject().put("id", "wp-1").put("name", "Camp")
            .put("lat", 34.0).put("lon", -117.0)))

    private fun track(trackId: String = id) = JSONObject().put("id", trackId).put("name", "Walk")
        .put("pointCount", 2).put("distanceM", 123456.0)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun backup(root: JSONObject, points: String? = null): ByteArrayInputStream = zip(
        *(listOf("gridfix-backup.json" to root.toString().toByteArray()) +
            if (points == null) emptyList() else listOf("tracks/$id.txt" to points.toByteArray())).toTypedArray()
    )

    @Test fun parsesWholeBackupAndRecomputesTrackStats() {
        val root = manifest().put("tracks", JSONArray().put(track()))
        val plan = Backup.parse(backup(root, "34 -117 1000 150\n34.001 -117 2000 -32768\n"))
        assertEquals("Camp", plan.waypoints.single().name)
        assertEquals(2, plan.tracks.single().first.pointCount)
        assertTrue(plan.tracks.single().first.distanceM in 100.0..120.0)
        assertEquals(NO_ALTITUDE, plan.tracks.single().second.last().alt, 0.0)
    }

    @Test fun malformedLateTrackRejectsPlanEvenWithValidWaypoints() {
        val root = manifest().put("tracks", JSONArray().put(track()))
        assertThrows(IllegalArgumentException::class.java) {
            Backup.parse(backup(root, "34 -117 1000 150\n999 -117 2000 150\n"))
        }
    }

    @Test fun malformedLateCourseCannotProduceAReadyRestorePlan() {
        val root = manifest().put("courseHistory", JSONArray().put(JSONObject()
            .put("name", "Course").put("points", 2).put("started", 1000).put("total", -1)
            .put("splits", JSONArray().put(10))))
        assertThrows(IllegalArgumentException::class.java) { Backup.parse(backup(root)) }
    }

    @Test fun rejectsManifestPathAliasAndMissingTrackPoints() {
        val alias = manifest().put("tracks", JSONArray().put(track("./$id")))
        assertThrows(IllegalArgumentException::class.java) { Backup.parse(backup(alias)) }
        val missing = manifest().put("tracks", JSONArray().put(track()))
        assertThrows(IllegalArgumentException::class.java) { Backup.parse(backup(missing)) }
    }

    @Test fun rejectsDuplicateTrackIdsInsteadOfDuplicatingOrReplacing() {
        val root = manifest().put("tracks", JSONArray().put(track()).put(track()))
        assertThrows(IllegalArgumentException::class.java) {
            Backup.parse(backup(root, "34 -117 1000 150\n34.001 -117 2000 150\n"))
        }
    }

    @Test fun rejectsUnknownVersionBeforePreparingRestore() {
        assertThrows(IllegalArgumentException::class.java) { Backup.parse(backup(manifest().put("version", 99))) }
    }

    @Test fun zipLimitsIncludeIgnoredEntriesAndAggregateBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            readBackupEntries(zip("notes" to ByteArray(12), "ignored" to ByteArray(12)), maxTotalBytes = 20, maxEntryBytes = 16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readBackupEntries(zip("notes" to ByteArray(17)), maxTotalBytes = 100, maxEntryBytes = 16)
        }
    }

    @Test fun rejectsAliasedArchivePaths() {
        for (path in listOf("tracks/./$id.txt", "tracks/../$id.txt", "/tracks/$id.txt", "tracks\\$id.txt")) {
            assertThrows(IllegalArgumentException::class.java) { readBackupEntries(zip(path to byteArrayOf())) }
        }
    }

    @Test fun partialFailureSummaryReportsCompletedWorkAndRetry() {
        val summary = Backup.RestoreResult(2, 0, 1, 0, false, "Storage full").summary()
        assertTrue(summary.startsWith("Restore incomplete"))
        assertTrue(summary.contains("2 waypoints"))
        assertTrue(summary.contains("1 tracks"))
        assertTrue(summary.contains("retry"))
    }

    @Test fun exportAppliesTheSameSizeAndCountLimitsAsRestore() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupExportBudget().add("gridfix-backup.json", BackupLimits.MANIFEST_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupExportBudget().add("tracks/$id.txt", BackupLimits.ENTRY_BYTES + 1)
        }
        val totalBudget = BackupExportBudget()
        totalBudget.add("gridfix-backup.json", 1)
        totalBudget.add("tracks/$id.txt", BackupLimits.ENTRY_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            totalBudget.add("tracks/other.txt", BackupLimits.ENTRY_BYTES)
        }
        val fileBudget = BackupExportBudget()
        repeat(BackupLimits.ENTRIES) { fileBudget.add("entry-$it", 1) }
        assertThrows(IllegalArgumentException::class.java) { fileBudget.add("extra", 1) }
    }
}
