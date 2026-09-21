package app.gridfix.android.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class ImportArchiveLimits(
    val entryBytes: Long = 32L * 1024 * 1024,
    val totalBytes: Long = 128L * 1024 * 1024,
    val entries: Int = 2048,
)

/** One budget follows an entire package, including decompressed members of nested KMZs. */
internal class ImportArchiveBudget(private val limits: ImportArchiveLimits = ImportArchiveLimits()) {
    private var totalBytes = 0L
    private var entries = 0

    fun readEntry(zip: ZipInputStream, entry: ZipEntry, name: String, retain: Boolean): ByteArray? {
        require(++entries <= limits.entries) {
            "Package has too many entries at '$name' (limit ${limits.entries})"
        }
        require(entry.size <= limits.entryBytes) {
            "Package entry '$name' is too large (${entry.size} bytes; limit ${limits.entryBytes} bytes)"
        }
        val out = if (retain) ByteArrayOutputStream() else null
        val buffer = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            size += count
            totalBytes += count
            require(size <= limits.entryBytes) {
                "Package entry '$name' is too large (at least $size bytes; limit ${limits.entryBytes} bytes)"
            }
            require(totalBytes <= limits.totalBytes) {
                "Package is too large while reading '$name' ($totalBytes decompressed bytes; limit ${limits.totalBytes} bytes)"
            }
            out?.write(buffer, 0, count)
        }
        return out?.toByteArray()
    }
}
