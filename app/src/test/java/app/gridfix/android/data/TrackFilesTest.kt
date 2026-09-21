package app.gridfix.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TrackFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "f4ebbead-09c6-4c52-9391-7c430510f281"
    private val secondId = "69917c4a-4c9f-4e28-b484-ce706a991a6e"
    private val point = TrackPoint(34.0, -117.0, 1000, 150.0)

    @Test fun rejectsFilenameAliasesBeforeExistingFileCanBeTouched() {
        val root = folder.newFolder("tracks")
        val saved = File(root, "$id.txt").apply { writeText("original") }
        for (alias in listOf("./$id", "../$id", "/$id", id.uppercase(), "1-1-1-1-1")) {
            assertThrows(IllegalArgumentException::class.java) { trackPointsFile(root, alias) }
        }
        assertEquals("original", saved.readText())
    }

    @Test fun failedAppendPropagatesInsteadOfReportingSuccess() {
        val blockedParent = folder.newFile("not-a-directory")
        assertThrows(IllegalStateException::class.java) { appendTrackPoint(File(blockedParent, "$id.txt"), point) }
    }

    @Test fun successfulAppendPreservesAllPreviousPoints() {
        val file = File(folder.newFolder("tracks"), "$id.txt")
        appendTrackPoint(file, point)
        appendTrackPoint(file, point.copy(time = 2000))
        assertEquals(listOf(trackPointLine(point).trim(), trackPointLine(point.copy(time = 2000)).trim()), file.readLines())
    }

    @Test fun stagingNeverOverwritesOrphanedTrack() {
        val root = folder.newFolder("tracks")
        val existing = File(root, "$id.txt").apply { writeText("orphaned original") }
        StagedTrackFiles(root).use { stage ->
            assertThrows(IllegalStateException::class.java) { stage.stage(id, listOf(point)) }
        }
        assertEquals("orphaned original", existing.readText())
    }

    @Test fun publishCollisionRollsBackOnlyFilesCreatedByThisAttempt() {
        val root = folder.newFolder("tracks")
        StagedTrackFiles(root).use { stage ->
            stage.stage(id, listOf(point))
            stage.stage(secondId, listOf(point))
            val existing = File(root, "$secondId.txt").apply { writeText("concurrent original") }
            val failure = assertThrows(IOException::class.java) { stage.publish(listOf(id, secondId)) }
            stage.rollback(failure)
            assertFalse(File(root, "$id.txt").exists())
            assertEquals("concurrent original", existing.readText())
        }
        assertEquals(listOf("$secondId.txt"), root.list()!!.toList())
    }

    @Test fun metadataFailureCanRollBackPublishedFiles() {
        val root = folder.newFolder("tracks")
        StagedTrackFiles(root).use { stage ->
            stage.stage(id, listOf(point))
            stage.publish(listOf(id))
            assertTrue(File(root, "$id.txt").exists())
            stage.rollback(IOException("metadata write failed"))
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun identicalOrphanCanBeReattachedWithoutReplacingIt() {
        val root = folder.newFolder("tracks")
        val orphan = File(root, "$id.txt").apply { writeText(trackPointLine(point)) }
        StagedTrackFiles(root).use { stage ->
            stage.stage(id, listOf(point))
            stage.publish(listOf(id))
            stage.rollback(IOException("metadata failed again"))
        }
        assertEquals(trackPointLine(point), orphan.readText())
        assertEquals(listOf("$id.txt"), root.list()!!.toList())
    }

    @Test fun interruptedStagingDoesNotCreateAFinalFileOrBlockRetry() {
        val root = folder.newFolder("tracks")
        // Durable state left by a process killed partway through writing its private stage.
        val partial = File(root, "restore-interrupted.part").apply { writeText("34.000") }
        assertFalse(File(root, "$id.txt").exists())
        StagedTrackFiles(root).use { retry ->
            retry.stage(id, listOf(point))
            retry.publish(listOf(id))
        }
        assertEquals(trackPointLine(point), File(root, "$id.txt").readText())
        assertEquals("34.000", partial.readText())
    }

    @Test fun interruptedBatchPublicationCanBeRetriedWithoutLosingCompletedTrack() {
        val root = folder.newFolder("tracks")
        val secondPoints = listOf(point, point.copy(time = 2000))
        // Simulate death after the first final filename appears, before publishing
        // the second or committing metadata. No rollback runs for that process.
        StagedTrackFiles(root).use { interrupted ->
            interrupted.stage(id, listOf(point))
            interrupted.stage(secondId, secondPoints)
            interrupted.publish(listOf(id))
            assertEquals(trackPointLine(point), File(root, "$id.txt").readText())
            assertFalse(File(root, "$secondId.txt").exists())
            StagedTrackFiles(root).use { retry ->
                retry.stage(id, listOf(point))
                retry.stage(secondId, secondPoints)
                retry.publish(listOf(id, secondId))
                // A later metadata failure may remove only this retry's publication.
                retry.rollback(IOException("metadata still unavailable"))
            }
            assertEquals(trackPointLine(point), File(root, "$id.txt").readText())
            assertFalse(File(root, "$secondId.txt").exists())
        }
        StagedTrackFiles(root).use { retry ->
            retry.stage(id, listOf(point))
            retry.stage(secondId, secondPoints)
            retry.publish(listOf(id, secondId))
        }
        assertEquals(trackPointLine(point), File(root, "$id.txt").readText())
        assertEquals(secondPoints.joinToString("") { trackPointLine(it) }, File(root, "$secondId.txt").readText())
        assertEquals(setOf("$id.txt", "$secondId.txt"), root.list()!!.toSet())
    }

    @Test fun mismatchingPartialFinalFileIsNeverOverwritten() {
        val root = folder.newFolder("tracks")
        // Legacy copies or unrelated files cannot be claimed as this attempt's
        // own work merely because their bytes prefix the desired track.
        val partial = File(root, "$id.txt").apply { writeText("34.000") }
        StagedTrackFiles(root).use { retry ->
            assertThrows(IllegalStateException::class.java) { retry.stage(id, listOf(point)) }
        }
        assertEquals("34.000", partial.readText())
    }

    @Test fun timelessGpxPointDoesNotBecome1970() {
        val gpx = TrackRepository.buildGpx("Walk", listOf(point.copy(time = 0)))
        assertFalse(gpx.contains("<time>"))
        assertFalse(gpx.contains("1970"))
        assertTrue(gpx.contains("<ele>150.0</ele>"))
    }
}
