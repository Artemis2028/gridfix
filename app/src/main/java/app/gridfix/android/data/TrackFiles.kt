package app.gridfix.android.data

import java.io.File
import java.io.RandomAccessFile
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID

/** IDs are also filenames. Reject aliases rather than normalizing untrusted paths. */
internal fun requireTrackId(id: String): String {
    require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
        "Invalid track ID"
    }
    return id
}

internal fun trackPointsFile(directory: File, id: String): File {
    requireTrackId(id)
    val root = directory.canonicalFile
    val file = File(root, "$id.txt")
    require(file.canonicalFile.parentFile == root) { "Track file is outside the tracks directory" }
    return file
}

internal fun trackPointLine(point: TrackPoint): String = String.format(
    Locale.US, "%.7f %.7f %d %.1f\n", point.lat, point.lon, point.time, point.alt,
)

/** Roll back a partial line if a write fails, and let the recorder report the failure. */
internal fun appendTrackPoint(file: File, point: TrackPoint) {
    val parent = requireNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Cannot create the tracks directory" }
    RandomAccessFile(file, "rw").use { out ->
        val previousLength = out.length()
        try {
            out.seek(previousLength)
            out.write(trackPointLine(point).toByteArray(Charsets.UTF_8))
        } catch (failure: Exception) {
            runCatching { out.setLength(previousLength) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}

/** Temporary files are owned by this attempt; pre-existing point files are never replaced. */
internal class StagedTrackFiles(private val directory: File) : Closeable {
    private val staged = LinkedHashMap<String, File>()
    private val created = ArrayList<File>()

    fun stage(id: String, points: List<TrackPoint>) {
        val target = trackPointsFile(directory, id)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the tracks directory" }
        check(id !in staged) { "Duplicate track ID" }
        val file = File.createTempFile("restore-", ".part", directory)
        staged[id] = file
        file.bufferedWriter().use { out -> points.forEach { out.write(trackPointLine(it)) } }
        check(!target.exists() || sameContents(target, file)) {
            "A different track file already exists for $id; it was not overwritten"
        }
    }

    fun publish(ids: List<String>) {
        for (id in ids) {
            val stage = requireNotNull(staged[id]) { "Track changed during restore; please retry" }
            val target = trackPointsFile(directory, id)
            // A process death can leave fully published points before metadata is
            // committed. Reattach only byte-identical content on an idempotent retry.
            // This file was not created by this attempt and must survive rollback.
            if (target.exists() && sameContents(target, stage)) continue
            Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                created.add(target)
                stage.inputStream().use { it.copyTo(out) }
            }
        }
    }

    fun rollback(failure: Exception) {
        for (file in created) {
            if (file.exists() && !file.delete()) failure.addSuppressed(
                java.io.IOException("Could not remove staged track ${file.name}")
            )
        }
        created.clear()
    }

    override fun close() { staged.values.forEach { it.delete() } }

    private fun sameContents(first: File, second: File): Boolean {
        if (!first.isFile || first.length() != second.length()) return false
        return first.inputStream().buffered().use { a ->
            second.inputStream().buffered().use compare@{ b ->
                while (true) {
                    val next = a.read()
                    if (next != b.read()) return@compare false
                    if (next == -1) return@compare true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
    }
}
