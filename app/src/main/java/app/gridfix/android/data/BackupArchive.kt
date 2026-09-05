package app.gridfix.android.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object BackupLimits {
    const val TOTAL_BYTES = 32L * 1024 * 1024
    const val ENTRY_BYTES = 16L * 1024 * 1024
    const val MANIFEST_BYTES = 4L * 1024 * 1024
    const val ENTRIES = 512
    const val RECORDS = 10_000
    const val POINTS = 250_000
}

/** Used while staging an export so every successfully written backup is readable. */
internal class BackupExportBudget {
    private var total = 0L
    private var entries = 0
    fun add(name: String, size: Long) {
        require(++entries <= BackupLimits.ENTRIES && size <= BackupLimits.ENTRY_BYTES &&
            (name != "gridfix-backup.json" || size <= BackupLimits.MANIFEST_BYTES)) {
            "Backup exceeds the supported size limit (32 MB total, 16 MB per track, 4 MB manifest, 512 files)"
        }
        total += size
        require(total <= BackupLimits.TOTAL_BYTES) { "Backup exceeds the supported 32 MB total size limit" }
    }
}

/** Bounds apply to every decompressed entry, including files we do not import. */
internal fun readBackupEntries(
    input: InputStream,
    maxTotalBytes: Long = BackupLimits.TOTAL_BYTES,
    maxEntryBytes: Long = BackupLimits.ENTRY_BYTES,
    maxManifestBytes: Long = BackupLimits.MANIFEST_BYTES,
    maxEntries: Int = BackupLimits.ENTRIES,
): Map<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    val names = HashSet<String>()
    var total = 0L
    ZipInputStream(input).use { zip ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            require(names.size < maxEntries && names.add(entry.name)) { "Too many or duplicate backup entries" }
            require(!entry.name.startsWith('/') && '\\' !in entry.name &&
                entry.name.split('/').none { it == "." || it == ".." }) { "Invalid backup entry path" }
            val wanted = !entry.isDirectory && (entry.name == "gridfix-backup.json" || entry.name.startsWith("tracks/"))
            if (wanted && entry.name.startsWith("tracks/")) {
                require(entry.name.endsWith(".txt")) { "Invalid track filename" }
                val id = entry.name.removePrefix("tracks/").removeSuffix(".txt")
                requireTrackId(id)
            }
            val bytes = if (wanted) ByteArrayOutputStream() else null
            var size = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                size += count
                total += count
                require(size <= maxEntryBytes && total <= maxTotalBytes) { "Backup exceeds the supported size limit" }
                require(entry.name != "gridfix-backup.json" || size <= maxManifestBytes) { "Backup manifest exceeds the supported size limit" }
                bytes?.write(buffer, 0, count)
            }
            if (bytes != null) entries[entry.name] = bytes.toByteArray()
            zip.closeEntry()
        }
    }
    return entries
}
