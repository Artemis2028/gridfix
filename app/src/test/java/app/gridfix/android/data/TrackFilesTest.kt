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

    @Test fun timelessGpxPointDoesNotBecome1970() {
        val gpx = TrackRepository.buildGpx("Walk", listOf(point.copy(time = 0)))
        assertFalse(gpx.contains("<time>"))
        assertFalse(gpx.contains("1970"))
        assertTrue(gpx.contains("<ele>150.0</ele>"))
    }
}
