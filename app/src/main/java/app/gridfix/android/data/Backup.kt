package app.gridfix.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Whole-app backup as one zip: a JSON snapshot of waypoints, folders,
 * graphics, settings, and the practice log, plus each track's point file.
 * Restore is idempotent — everything keeps its original id and anything
 * already on the device is skipped, so restoring twice never duplicates.
 */
object Backup {

    const val VERSION = 1

    suspend fun export(
        context: Context,
        out: OutputStream,
        waypoints: List<Waypoint>,
        folders: List<FolderInfo>,
        graphics: List<TacGraphic>,
        settings: AppSettings,
        tracks: List<TrackInfo>,
        courseHistory: List<CourseResult>,
        nowMillis: Long,
    ) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("app", "GridFix")
            .put("version", VERSION)
            .put("exportedAt", nowMillis)
        root.put("waypoints", JSONArray().also { a ->
            for (w in waypoints) a.put(
                JSONObject()
                    .put("id", w.id).put("name", w.name)
                    .put("lat", w.lat).put("lon", w.lon)
                    .put("createdAt", w.createdAt).put("folder", w.folder)
                    .put("symbol", w.symbol).put("affiliation", w.affiliation)
                    .put("echelon", w.echelon).put("designation", w.designation)
                    .put("kind", w.kind).put("rotation", w.rotation.toDouble())
                    .put("visible", w.visible)
            )
        })
        root.put("folders", JSONArray().also { a ->
            for (f in folders) a.put(JSONObject().put("name", f.name).put("visible", f.visible))
        })
        root.put("graphics", JSONArray().also { a ->
            for (g in graphics) {
                val pts = JSONArray()
                for (v in g.points) pts.put(JSONArray().put(v.lat).put(v.lon))
                a.put(
                    JSONObject()
                        .put("id", g.id).put("name", g.name).put("type", g.type)
                        .put("points", pts).put("folder", g.folder)
                        .put("affiliation", g.affiliation).put("createdAt", g.createdAt)
                        .put("echelon", g.echelon).put("visible", g.visible)
                )
            }
        })
        root.put("settings", JSONObject()
            .put("nightMode", settings.nightMode)
            .put("keepScreenOn", settings.keepScreenOn)
            .put("mgrsDigits", settings.mgrsDigits)
            .put("latLonFormat", settings.latLonFormat)
            .put("units", settings.units)
            .put("angleUnit", settings.angleUnit)
            .put("northRef", settings.northRef)
            .put("pacePer100m", settings.pacePer100m)
            .put("face", settings.face)
            .put("orientation", settings.orientation)
            .put("declinationOverride", settings.declinationOverride?.toDouble() ?: JSONObject.NULL)
            .put("disclaimerAccepted", settings.disclaimerAccepted)
        )
        root.put("tracks", JSONArray().also { a ->
            for (t in tracks) a.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name)
                    .put("startedAt", t.startedAt).put("endedAt", t.endedAt)
                    .put("distanceM", t.distanceM).put("pointCount", t.pointCount)
                    .put("folder", t.folder).put("visible", t.visible)
            )
        })
        root.put("courseHistory", JSONArray().also { a ->
            for (r in courseHistory) a.put(
                JSONObject()
                    .put("name", r.name).put("points", r.points)
                    .put("started", r.startedAt).put("total", r.totalMillis)
                    .put("splits", JSONArray(r.splitsMillis))
            )
        })

        require(listOf(waypoints.size, folders.size, graphics.size, tracks.size, courseHistory.size).all { it <= MAX_RECORDS }) {
            "Backup exceeds the supported record count"
        }
        requireUniqueIds(waypoints.map { it.id }, "waypoint")
        requireUniqueIds(graphics.map { it.id }, "graphic")
        requireUniqueIds(tracks.map { it.id }, "track")
        waypoints.forEach {
            requireCoordinates(it.lat, it.lon)
            require(it.rotation.isFinite()) { "Invalid waypoint rotation" }
        }
        require(graphics.sumOf { it.points.size.toLong() } <= MAX_POINTS) { "Too many graphic vertices" }
        graphics.forEach { graphic ->
            require(graphic.points.size >= GraphicTypes.minPoints(graphic.type)) { "Invalid graphic vertex count" }
            graphic.points.forEach { requireCoordinates(it.lat, it.lon) }
        }
        validateSettings(settings)
        courseHistory.forEach { validateCourseResult(it) }
        val manifest = root.toString(2).toByteArray(Charsets.UTF_8)
        val budget = BackupExportBudget().also { it.add("gridfix-backup.json", manifest.size.toLong()) }
        // Snapshot/stage the files before writing to the user's document provider.
        // This also bounds a recording that keeps growing during the backup.
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        val files = ArrayList<Pair<String, File>>()
        var pointCount = 0
        try {
            for (track in tracks) {
                requireTrackId(track.id)
                require(track.distanceM.isFinite() && track.distanceM >= 0 && track.pointCount >= 0) { "Invalid track metadata" }
                val points = TrackRepository.readPoints(context, track.id)
                require(points.isNotEmpty() || track.pointCount == 0) { "Missing points for track ${track.name}" }
                if (points.isEmpty()) continue
                pointCount += points.size
                require(pointCount <= MAX_POINTS) { "Backup exceeds the supported $MAX_POINTS track points" }
                val file = File(staging, "${track.id}.txt")
                file.bufferedWriter().use { writer ->
                    for (point in points) {
                        requireCoordinates(point.lat, point.lon)
                        require(point.time >= 0 && point.alt.isFinite()) { "Invalid point in track ${track.name}" }
                        writer.write(trackPointLine(point))
                    }
                }
                val entry = "tracks/${track.id}.txt"
                budget.add(entry, file.length())
                files.add(entry to file)
            }
            val zip = ZipOutputStream(out)
            zip.putNextEntry(ZipEntry("gridfix-backup.json"))
            zip.write(manifest)
            zip.closeEntry()
            for ((entry, file) in files) {
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        } finally {
            staging.deleteRecursively()
        }
    }

    data class RestoreResult(
        val waypoints: Int,
        val graphics: Int,
        val tracks: Int,
        val course: Int,
        val settingsApplied: Boolean,
        val failure: String? = null,
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            if (waypoints > 0) parts.add("$waypoints waypoints")
            if (graphics > 0) parts.add("$graphics graphics")
            if (tracks > 0) parts.add("$tracks tracks")
            if (course > 0) parts.add("$course course results")
            if (settingsApplied) parts.add("settings")
            if (failure != null) {
                val progress = if (parts.isEmpty()) "No records were added." else "Already restored: ${parts.joinToString(", ")}."
                return "Restore incomplete — $failure. $progress Existing records were not overwritten. After resolving the problem, retry the same backup to add the remaining records."
            }
            return if (parts.isEmpty()) "Nothing new to restore — everything was already here"
            else "Restored " + parts.joinToString(", ")
        }
    }

    suspend fun restore(
        input: InputStream,
        waypointRepo: WaypointRepository,
        graphicsRepo: GraphicsRepository,
        trackRepo: TrackRepository,
        settingsRepo: SettingsRepository,
        courseRepo: CourseRepository,
    ): RestoreResult = withContext(Dispatchers.IO) {
        // No repository is touched until the entire archive and every record pass validation.
        val plan = parse(input)
        var result = RestoreResult(0, 0, 0, 0, false)
        // Individual stores are atomic; the app does not have one database spanning
        // all stores. Report completed work explicitly if a later store write fails.
        withContext(NonCancellable) {
            try {
                result = result.copy(tracks = trackRepo.restoreAll(plan.tracks))
                result = result.copy(waypoints = waypointRepo.restore(plan.waypoints, plan.folders))
                result = result.copy(graphics = graphicsRepo.restore(plan.graphics))
                result = result.copy(course = courseRepo.restoreHistory(plan.course))
                plan.settings?.let {
                    settingsRepo.applyAll(it)
                    result = result.copy(settingsApplied = true)
                }
                result
            } catch (failure: Exception) {
                result.copy(failure = failure.message ?: "could not save the restored data")
            }
        }
    }

    internal data class RestorePlan(
        val waypoints: List<Waypoint>,
        val folders: List<FolderInfo>,
        val graphics: List<TacGraphic>,
        val tracks: List<Pair<TrackInfo, List<TrackPoint>>>,
        val settings: AppSettings?,
        val course: List<CourseResult>,
    )

    internal fun parse(input: InputStream): RestorePlan {
        val entries = readBackupEntries(input)
        val rootBytes = entries["gridfix-backup.json"]
            ?: throw IllegalArgumentException("not an MGRS GPS backup")
        val root = JSONObject(String(rootBytes, Charsets.UTF_8))
        require(root.getString("app") in setOf("GridFix", "MGRS GPS") && root.getInt("version") == VERSION) {
            "Unsupported backup format or version"
        }
        for (key in listOf("waypoints", "folders", "graphics", "tracks", "courseHistory")) {
            require(!root.has(key) || root.get(key) is JSONArray) { "Invalid $key section" }
            require((root.optJSONArray(key)?.length() ?: 0) <= MAX_RECORDS) { "Too many $key records" }
        }
        require(!root.has("settings") || root.get("settings") is JSONObject) { "Invalid settings section" }

        val wps = ArrayList<Waypoint>()
        root.optJSONArray("waypoints")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                wps.add(
                    Waypoint(
                        id = o.getString("id"), name = o.getString("name"),
                        lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                        createdAt = o.optLong("createdAt"),
                        folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                        symbol = o.optString("symbol", DEFAULT_SYMBOL),
                        affiliation = o.optString("affiliation", "none"),
                        echelon = o.optString("echelon", ""),
                        designation = o.optString("designation", ""),
                        kind = o.optString("kind", KIND_WP),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        visible = o.optBoolean("visible", true),
                    )
                )
            }
        }
        val folderInfos = ArrayList<FolderInfo>()
        root.optJSONArray("folders")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                folderInfos.add(FolderInfo(canonicalFolder(o.getString("name")), o.optBoolean("visible", true)))
            }
        }
        requireUniqueIds(wps.map { it.id }, "waypoint")
        wps.forEach {
            requireCoordinates(it.lat, it.lon)
            require(it.rotation.isFinite()) { "Invalid waypoint rotation" }
        }

        val gfx = ArrayList<TacGraphic>()
        var graphicPointCount = 0
        root.optJSONArray("graphics")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ptsArr = o.getJSONArray("points")
                graphicPointCount += ptsArr.length()
                require(graphicPointCount <= MAX_POINTS) { "Too many graphic vertices" }
                val pts = ArrayList<GeoVertex>()
                for (j in 0 until ptsArr.length()) {
                    val v = ptsArr.getJSONArray(j)
                    pts.add(GeoVertex(v.getDouble(0), v.getDouble(1)))
                }
                gfx.add(
                    TacGraphic(
                        id = o.getString("id"), name = o.getString("name"),
                        type = o.getString("type"), points = pts,
                        folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                        affiliation = o.optString("affiliation", "none"),
                        createdAt = o.optLong("createdAt"),
                        echelon = o.optString("echelon", ""),
                        visible = o.optBoolean("visible", true),
                    )
                )
            }
        }
        requireUniqueIds(gfx.map { it.id }, "graphic")
        gfx.forEach { graphic ->
            require(graphic.points.size in GraphicTypes.minPoints(graphic.type)..MAX_POINTS) { "Invalid graphic vertex count" }
            graphic.points.forEach { requireCoordinates(it.lat, it.lon) }
        }

        val restoredTracks = ArrayList<Pair<TrackInfo, List<TrackPoint>>>()
        var pointCount = 0
        root.optJSONArray("tracks")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val info = TrackInfo(
                    id = o.getString("id"), name = o.getString("name"),
                    startedAt = o.optLong("startedAt"), endedAt = o.optLong("endedAt"),
                    distanceM = o.optDouble("distanceM", 0.0),
                    pointCount = o.optInt("pointCount", 0),
                    folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                    visible = o.optBoolean("visible", true),
                )
                requireTrackId(info.id)
                require(info.distanceM.isFinite() && info.distanceM >= 0 && info.pointCount >= 0) { "Invalid track metadata" }
                val ptBytes = entries["tracks/${info.id}.txt"]
                require(ptBytes != null || info.pointCount == 0) { "Missing points for track ${info.name}" }
                val pts = ArrayList<TrackPoint>()
                if (ptBytes != null) {
                    String(ptBytes, Charsets.UTF_8).lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val parts = line.trim().split(Regex("\\s+"))
                        require(parts.size in 3..4) { "Malformed point in track ${info.name}" }
                        val point = TrackPoint(
                            lat = parts[0].toDouble(), lon = parts[1].toDouble(),
                            time = parts[2].toLong(), alt = parts.getOrNull(3)?.toDouble() ?: NO_ALTITUDE,
                        )
                        requireCoordinates(point.lat, point.lon)
                        require(point.time >= 0 && point.alt.isFinite()) { "Invalid point in track ${info.name}" }
                        require(++pointCount <= MAX_POINTS) { "Too many backup track points" }
                        pts.add(point)
                    }
                }
                var distance = 0.0
                for (j in 1 until pts.size) distance += app.gridfix.android.coords.Geodesy.distanceAndBearing(
                    pts[j - 1].lat, pts[j - 1].lon, pts[j].lat, pts[j].lon,
                )[0]
                restoredTracks.add(info.copy(pointCount = pts.size, distanceM = distance) to pts)
            }
        }

        requireUniqueIds(restoredTracks.map { it.first.id }, "track")
        val expectedTrackEntries = restoredTracks.map { "tracks/${it.first.id}.txt" }.toSet()
        require(entries.keys.none { it.startsWith("tracks/") && it !in expectedTrackEntries }) { "Track file is missing from the manifest" }

        val restoredSettings = root.optJSONObject("settings")?.let { s ->
                AppSettings(
                    nightMode = s.optBoolean("nightMode", false),
                    keepScreenOn = s.optBoolean("keepScreenOn", true),
                    mgrsDigits = s.optInt("mgrsDigits", 10),
                    latLonFormat = s.optInt("latLonFormat", 1),
                    units = s.optInt("units", 0),
                    angleUnit = s.optInt("angleUnit", 0),
                    northRef = s.optInt("northRef", 0),
                    pacePer100m = s.optInt("pacePer100m", 65),
                    face = s.optInt("face", 1),
                    orientation = s.optInt("orientation", 0),
                    declinationOverride = if (!s.has("declinationOverride") || s.isNull("declinationOverride")) null else s.getDouble("declinationOverride").toFloat(),
                    disclaimerAccepted = s.optBoolean("disclaimerAccepted", false),
                ).also { validateSettings(it) }
        }

        val results = ArrayList<CourseResult>()
        root.optJSONArray("courseHistory")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val splits = o.getJSONArray("splits")
                require(splits.length() <= MAX_RECORDS) { "Too many course splits" }
                results.add(
                    CourseResult(
                        name = o.getString("name"), points = o.getInt("points"),
                        startedAt = o.getLong("started"), totalMillis = o.getLong("total"),
                        splitsMillis = buildList {
                            for (j in 0 until splits.length()) add(splits.getLong(j))
                        },
                    )
                )
            }
        }
        results.forEach { validateCourseResult(it) }
        return RestorePlan(wps, folderInfos, gfx, restoredTracks, restoredSettings, results)
    }

    private const val MAX_RECORDS = BackupLimits.RECORDS
    private const val MAX_POINTS = BackupLimits.POINTS

    private fun validateCourseResult(result: CourseResult) {
        require(result.points >= 0 && result.startedAt >= 0 && result.totalMillis >= 0 &&
            result.splitsMillis.size <= MAX_RECORDS && result.splitsMillis.all { it >= 0 }) { "Invalid course result" }
    }

    private fun requireCoordinates(lat: Double, lon: Double) {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) { "Invalid coordinates in backup" }
    }

    private fun requireUniqueIds(ids: List<String>, kind: String) {
        require(ids.all { it.isNotBlank() && it.length <= 256 } && ids.distinct().size == ids.size) { "Duplicate or invalid $kind IDs" }
    }

    private fun validateSettings(s: AppSettings) {
        require(s.mgrsDigits in setOf(4, 6, 8, 10) && s.latLonFormat in 0..2 && s.units in 0..2 &&
            s.angleUnit in 0..1 && s.northRef in 0..2 && s.pacePer100m in 1..1000 &&
            s.face in 0..2 && s.orientation in 0..3 &&
            (s.declinationOverride == null || s.declinationOverride.isFinite() && s.declinationOverride in -180f..180f)) {
            "Invalid settings in backup"
        }
    }
}
