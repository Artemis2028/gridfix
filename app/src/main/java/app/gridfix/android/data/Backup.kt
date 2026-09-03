package app.gridfix.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
                        .put("echelon", g.echelon)
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
                    .put("folder", t.folder)
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

        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("gridfix-backup.json"))
        zip.write(root.toString(2).toByteArray())
        zip.closeEntry()
        for (t in tracks) {
            val pts = TrackRepository.readPoints(context, t.id)
            if (pts.isEmpty()) continue
            zip.putNextEntry(ZipEntry("tracks/${t.id}.txt"))
            zip.write(
                pts.joinToString("") {
                    String.format(Locale.US, "%.7f %.7f %d %.1f\n", it.lat, it.lon, it.time, it.alt)
                }.toByteArray()
            )
            zip.closeEntry()
        }
        zip.finish()
    }

    data class RestoreResult(
        val waypoints: Int,
        val graphics: Int,
        val tracks: Int,
        val course: Int,
        val settingsApplied: Boolean,
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            if (waypoints > 0) parts.add("$waypoints waypoints")
            if (graphics > 0) parts.add("$graphics graphics")
            if (tracks > 0) parts.add("$tracks tracks")
            if (course > 0) parts.add("$course course results")
            if (settingsApplied) parts.add("settings")
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
        val entries = HashMap<String, ByteArray>()
        val zin = ZipInputStream(input)
        var e: ZipEntry? = zin.nextEntry
        while (e != null) {
            val wanted = e.name == "gridfix-backup.json" || e.name.startsWith("tracks/")
            if (!e.isDirectory && wanted) {
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                var tooBig = false
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 64L * 1024 * 1024) { tooBig = true; break }
                    out.write(buf, 0, n)
                }
                if (!tooBig) entries[e.name] = out.toByteArray()
            }
            zin.closeEntry()
            e = zin.nextEntry
        }
        val rootBytes = entries["gridfix-backup.json"]
            ?: throw IllegalArgumentException("not an MGRS GPS backup")
        val root = JSONObject(String(rootBytes))

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
        val addedWp = waypointRepo.restore(wps, folderInfos)

        val gfx = ArrayList<TacGraphic>()
        root.optJSONArray("graphics")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ptsArr = o.getJSONArray("points")
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
                    )
                )
            }
        }
        val addedG = graphicsRepo.restore(gfx)

        var addedT = 0
        root.optJSONArray("tracks")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val info = TrackInfo(
                    id = o.getString("id"), name = o.getString("name"),
                    startedAt = o.optLong("startedAt"), endedAt = o.optLong("endedAt"),
                    distanceM = o.optDouble("distanceM", 0.0),
                    pointCount = o.optInt("pointCount", 0),
                    folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                )
                val ptBytes = entries["tracks/${info.id}.txt"] ?: continue
                val pts = String(ptBytes).lines().mapNotNull { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size < 3) return@mapNotNull null
                    runCatching {
                        TrackPoint(
                            lat = parts[0].toDouble(), lon = parts[1].toDouble(),
                            time = parts[2].toLong(),
                            alt = parts.getOrNull(3)?.toDouble() ?: NO_ALTITUDE,
                        )
                    }.getOrNull()
                }
                if (trackRepo.restore(info, pts)) addedT++
            }
        }

        var settingsApplied = false
        root.optJSONObject("settings")?.let { s ->
            settingsRepo.applyAll(
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
                    declinationOverride = if (s.isNull("declinationOverride")) null else s.optDouble("declinationOverride").toFloat(),
                    disclaimerAccepted = s.optBoolean("disclaimerAccepted", false),
                )
            )
            settingsApplied = true
        }

        val results = ArrayList<CourseResult>()
        root.optJSONArray("courseHistory")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val splits = o.getJSONArray("splits")
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
        val addedC = courseRepo.restoreHistory(results)

        RestoreResult(addedWp, addedG, addedT, addedC, settingsApplied)
    }
}
