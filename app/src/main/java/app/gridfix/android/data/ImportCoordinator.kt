package app.gridfix.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A fully validated interchange import. Preparing it never changes a repository. */
internal data class ImportPlan(
    val waypoints: List<Waypoint>,
    val folders: List<FolderInfo>,
    val graphics: List<TacGraphic>,
    val tracks: List<Pair<TrackInfo, List<TrackPoint>>>,
)

internal data class ImportResult(
    val tracks: Int = 0,
    val waypoints: Int = 0,
    val graphics: Int = 0,
    val failure: String? = null,
) {
    fun summary(): String {
        val added = buildList {
            if (waypoints > 0) add("$waypoints waypoints")
            if (graphics > 0) add("$graphics graphics")
            if (tracks > 0) add("$tracks tracks")
        }
        if (failure != null) {
            val progress = if (added.isEmpty()) "No new records were confirmed saved."
                else "Saved so far: ${added.joinToString(", ")}."
            return "Import incomplete — $failure. $progress Retry the same unchanged file to add the remaining items safely."
        }
        return if (added.isEmpty()) "Nothing new to import — these records are already here"
            else "Imported ${added.joinToString(", ")}. Existing records were kept."
    }
}

/**
 * A content-derived namespace makes an unchanged file safe to retry, including
 * after process death between stores. The field order and identity version below
 * are a persistent format: changing either requires a migration strategy.
 *
 * Each original kind/ordinal has a separate ID, so identical entries inside one
 * file remain distinct. Reimporting an unchanged file keeps edits to records
 * already present; missing records are restored. Changed/reordered parsed data
 * is a different import. Identity never depends on the clock or local folders.
 */
internal object ImportCoordinator {
    fun prepare(
        data: InterchangeFiles.ImportedData,
        nowMillis: Long,
        knownFolders: Iterable<String> = emptyList(),
    ): ImportPlan {
        require(nowMillis >= 0) { "Invalid import time" }
        require(data.waypoints.size <= BackupLimits.RECORDS &&
            data.lines.size.toLong() + data.areas.size <= BackupLimits.RECORDS &&
            data.tracks.size <= BackupLimits.RECORDS) { "Too many imported records" }
        require(data.lines.sumOf { it.points.size.toLong() } +
            data.areas.sumOf { it.points.size.toLong() } <= BackupLimits.POINTS &&
            data.tracks.sumOf { it.points.size.toLong() } <= BackupLimits.POINTS) {
            "Too many imported points"
        }

        val namespace = fingerprint(data)
        fun id(kind: String, index: Int) = UUID.nameUUIDFromBytes(
            "gridfix-import-v1:$namespace:$kind:$index".toByteArray(Charsets.UTF_8)
        ).toString()
        val names = knownFolders.map(::canonicalFolder).toMutableList()
        val importedFolders = linkedSetOf<String>()
        fun folder(raw: String): String = matchFolder(names, raw).also {
            if (it !in names) names.add(it)
            importedFolders.add(it)
        }

        val waypoints = data.waypoints.mapIndexed { index, draft ->
            requireCoordinates(draft.lat, draft.lon, "waypoint ${index + 1}")
            require(draft.rotation.isFinite()) { "Invalid rotation in waypoint ${index + 1}" }
            require(draft.metadata?.elevationMeters?.isFinite() != false) {
                "Invalid elevation in waypoint ${index + 1}"
            }
            Waypoint(
                id = id("waypoint", index), name = draft.name, lat = draft.lat, lon = draft.lon,
                createdAt = nowMillis, folder = folder(draft.folder),
                symbol = draft.symbol.ifBlank { DEFAULT_SYMBOL }, affiliation = draft.affiliation,
                echelon = draft.echelon, designation = draft.designation, kind = draft.kind,
                rotation = draft.rotation, metadata = draft.metadata ?: WaypointMetadata(),
            )
        }
        fun graphic(line: InterchangeFiles.ImportedLine, kind: String, index: Int, type: String): TacGraphic {
            require(line.points.size >= GraphicTypes.minPoints(type)) { "Too few points in $kind ${index + 1}" }
            line.points.forEach { requireCoordinates(it.lat, it.lon, "$kind ${index + 1}") }
            return TacGraphic(
                id = id(kind, index), name = line.name.trim(), type = type,
                points = line.points.toList(), folder = folder(line.folder), createdAt = nowMillis,
            )
        }
        val graphics = data.lines.mapIndexed { i, line -> graphic(line, "line", i, "route") } +
            data.areas.mapIndexed { i, area -> graphic(area, "area", i, "aa") }
        val tracks = data.tracks.mapIndexed { index, track ->
            prepareImportedTrack(
                track.name, track.points, nowMillis, id = id("track", index),
                folder = folder(DEFAULT_FOLDER),
            )
        }
        return ImportPlan(waypoints, importedFolders.map(::FolderInfo), graphics, tracks)
    }

    /**
     * Stores are individually atomic, not a cross-store transaction. Stage/publish
     * the entire track batch first; later failures report confirmed progress.
     * A cancellation is propagated and never translated into an import error.
     * Once a phase starts, finish that phase and record its count before honoring
     * cancellation. No rollback deletes previously imported or user-edited data.
     */
    suspend fun persist(
        plan: ImportPlan,
        restoreTracks: suspend (List<Pair<TrackInfo, List<TrackPoint>>>) -> Int,
        restoreWaypoints: suspend (List<Waypoint>, List<FolderInfo>) -> Int,
        restoreGraphics: suspend (List<TacGraphic>) -> Int,
    ): ImportResult {
        var result = ImportResult()
        var phase = "tracks"
        try {
            coroutineContext.ensureActive()
            if (plan.tracks.isNotEmpty()) withContext(NonCancellable) {
                result = result.copy(tracks = restoreTracks(plan.tracks))
            }
            coroutineContext.ensureActive()
            phase = "waypoints and folders"
            if (plan.waypoints.isNotEmpty() || plan.folders.isNotEmpty()) withContext(NonCancellable) {
                result = result.copy(waypoints = restoreWaypoints(plan.waypoints, plan.folders))
            }
            coroutineContext.ensureActive()
            phase = "graphics"
            if (plan.graphics.isNotEmpty()) withContext(NonCancellable) {
                result = result.copy(graphics = restoreGraphics(plan.graphics))
            }
            coroutineContext.ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return result.copy(failure = "could not save $phase: ${failure.message ?: "storage error"}")
        }
    }

    private fun requireCoordinates(lat: Double, lon: Double, record: String) {
        require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0) {
            "Invalid coordinates in $record"
        }
    }

    /** Length-prefixed UTF-8 and explicit optional fields avoid ambiguous concatenation/JSON ordering. */
    private fun fingerprint(data: InterchangeFiles.ImportedData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            fun string(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            fun metadata(value: WaypointMetadata?) {
                out.writeBoolean(value != null)
                if (value == null) return
                out.writeBoolean(value.color != null)
                value.color?.let(::string)
                out.writeBoolean(value.milgpsSymbolCode != null)
                value.milgpsSymbolCode?.let(out::writeInt)
                out.writeBoolean(value.elevationMeters != null)
                value.elevationMeters?.let(out::writeDouble)
                out.writeBoolean(value.timestampMillis != null)
                value.timestampMillis?.let(out::writeLong)
            }
            string("gridfix-import-v1")
            out.writeInt(data.waypoints.size)
            data.waypoints.forEach {
                string(it.name); out.writeDouble(it.lat); out.writeDouble(it.lon)
                string(it.folder); string(it.symbol); string(it.affiliation)
                string(it.echelon); string(it.designation); string(it.kind)
                out.writeFloat(it.rotation); metadata(it.metadata)
            }
            fun lines(values: List<InterchangeFiles.ImportedLine>) {
                out.writeInt(values.size)
                values.forEach {
                    string(it.name); string(it.folder); out.writeInt(it.points.size)
                    it.points.forEach { point -> out.writeDouble(point.lat); out.writeDouble(point.lon) }
                }
            }
            lines(data.lines)
            lines(data.areas)
            out.writeInt(data.tracks.size)
            data.tracks.forEach {
                string(it.name); out.writeInt(it.points.size)
                it.points.forEach { point ->
                    out.writeDouble(point.lat); out.writeDouble(point.lon)
                    out.writeLong(point.time); out.writeDouble(point.alt)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
