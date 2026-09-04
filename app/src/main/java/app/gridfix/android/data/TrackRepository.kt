package app.gridfix.android.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gridfix.android.coords.Geodesy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val Context.trackStore by preferencesDataStore(
    name = "tracks",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Track metadata; the point log lives in files/tracks/<id>.txt (one point per line). */
data class TrackInfo(
    val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,        // 0 while recording
    val distanceM: Double,
    val pointCount: Int,
    val folder: String = DEFAULT_FOLDER,   // overlay folder, shares the eye switch with waypoints and graphics
)

/**
 * A logged fix. [alt] is metres above mean sea level, or [NO_ALTITUDE] when the
 * receiver gave no height: writing a real number there would put the leg at sea
 * level on every profile that reads it.
 */
data class TrackPoint(val lat: Double, val lon: Double, val time: Long, val alt: Double)

/** "this fix had no altitude" — omitted from GPX rather than written as a height. */
const val NO_ALTITUDE = -32768.0

fun Double.hasAltitude(): Boolean = this > NO_ALTITUDE + 1.0

class TrackRepository(private val context: Context) {

    private val listKey = stringPreferencesKey("list")

    val tracks: Flow<List<TrackInfo>> = context.trackStore.data.map { p ->
        decode(p[listKey] ?: "[]").sortedByDescending { it.startedAt }
    }

    suspend fun startTrack(nowMillis: Long): String {
        val id = UUID.randomUUID().toString()
        val info = TrackInfo(
            id = id,
            name = "Track " + dtgShort(nowMillis),
            startedAt = nowMillis,
            endedAt = 0L,
            distanceM = 0.0,
            pointCount = 0,
        )
        withContext(Dispatchers.IO) { pointsFile(context, id).parentFile?.mkdirs() }
        context.trackStore.edit { p ->
            p[listKey] = encode(decode(p[listKey] ?: "[]") + info)
        }
        return id
    }

    /** Close out a finished recording: compute stats from the point log. */
    suspend fun finishTrack(id: String, name: String?, nowMillis: Long) {
        val pts = readPoints(context, id)
        var dist = 0.0
        for (i in 1 until pts.size) {
            dist += Geodesy.distanceAndBearing(
                pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon
            )[0]
        }
        context.trackStore.edit { p ->
            p[listKey] = encode(
                decode(p[listKey] ?: "[]").map {
                    if (it.id == id) it.copy(
                        name = name?.trim()?.ifBlank { it.name } ?: it.name,
                        endedAt = nowMillis,
                        distanceM = dist,
                        pointCount = pts.size,
                    ) else it
                }
            )
        }
    }

    /** Create a finished track from imported points (GPX trk import). */
    suspend fun importTrack(name: String, points: List<TrackPoint>, nowMillis: Long): String {
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            val f = pointsFile(context, id)
            f.parentFile?.mkdirs()
            f.bufferedWriter().use { out ->
                for (p in points) {
                    out.write(
                        String.format(Locale.US, "%.7f %.7f %d %.1f\n", p.lat, p.lon, p.time, p.alt)
                    )
                }
            }
        }
        var dist = 0.0
        for (i in 1 until points.size) {
            dist += Geodesy.distanceAndBearing(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon
            )[0]
        }
        val start = points.firstOrNull()?.time?.takeIf { it > 0 } ?: nowMillis
        val end = points.lastOrNull()?.time?.takeIf { it > 0 } ?: nowMillis
        val info = TrackInfo(
            id = id,
            name = name.trim().ifBlank { "Imported track" },
            startedAt = start,
            endedAt = end,
            distanceM = dist,
            pointCount = points.size,
        )
        context.trackStore.edit { p ->
            p[listKey] = encode(decode(p[listKey] ?: "[]") + info)
        }
        return id
    }

    /** Restore one track from a backup, keeping its id; skipped when already present. */
    suspend fun restore(info: TrackInfo, points: List<TrackPoint>): Boolean {
        var added = false
        context.trackStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            if (current.none { it.id == info.id }) {
                val f = pointsFile(context, info.id)
                f.parentFile?.mkdirs()
                f.writeText(
                    points.joinToString("") {
                        String.format(Locale.US, "%.7f %.7f %d %.1f\n", it.lat, it.lon, it.time, it.alt)
                    }
                )
                p[listKey] = encode(current + info)
                added = true
            }
        }
        return added
    }

    suspend fun rename(id: String, name: String) {
        if (name.isBlank()) return
        context.trackStore.edit { p ->
            p[listKey] = encode(
                decode(p[listKey] ?: "[]").map {
                    if (it.id == id) it.copy(name = name.trim()) else it
                }
            )
        }
    }

    suspend fun setFolder(id: String, folder: String) {
        val clean = canonicalFolder(folder)
        context.trackStore.edit { p ->
            p[listKey] = encode(
                decode(p[listKey] ?: "[]").map {
                    if (it.id == id) it.copy(folder = clean) else it
                }
            )
        }
    }

    suspend fun delete(id: String) {
        context.trackStore.edit { p ->
            p[listKey] = encode(decode(p[listKey] ?: "[]").filterNot { it.id == id })
        }
        withContext(Dispatchers.IO) { pointsFile(context, id).delete() }
    }

    /** Move every track in [from] to [to] (folder rename, or emptying a folder into Base). */
    suspend fun renameFolder(from: String, to: String) {
        val target = canonicalFolder(to)
        if (from == target) return
        context.trackStore.edit { p ->
            p[listKey] = encode(decode(p[listKey] ?: "[]").map { if (it.folder == from) it.copy(folder = target) else it })
        }
    }

    /** Delete every track in [folder], point logs included. */
    suspend fun deleteFolder(folder: String) {
        val doomed = decode(context.trackStore.data.first()[listKey] ?: "[]").filter { it.folder == folder }
        for (t in doomed) delete(t.id)
    }

    /** Remove a recording that was discarded before saving. */
    suspend fun discard(id: String) = delete(id)

    /**
     * Heal recordings the OS killed mid-way: any track still open (endedAt == 0)
     * that is not the one currently recording is closed out from its point log
     * (the points are on disk — only the summary was lost), or removed when it
     * never logged a point.
     */
    suspend fun finalizeOrphans(activeId: String?) {
        val open = decode(context.trackStore.data.first()[listKey] ?: "[]")
            .filter { it.endedAt == 0L && it.id != activeId }
        for (t in open) {
            val pts = readPoints(context, t.id)
            if (pts.isEmpty()) {
                delete(t.id)
            } else {
                val last = pts.last().time
                finishTrack(t.id, null, if (last > t.startedAt) last else System.currentTimeMillis())
            }
        }
    }

    // One damaged record must not take the whole list with it: skip it, keep the rest.
    private fun decode(json: String): List<TrackInfo> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    TrackInfo(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        startedAt = o.optLong("startedAt"),
                        endedAt = o.optLong("endedAt"),
                        distanceM = o.optDouble("distanceM", 0.0),
                        pointCount = o.optInt("pointCount", 0),
                        folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                    )
                }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun encode(list: List<TrackInfo>): String {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("startedAt", t.startedAt)
                    .put("endedAt", t.endedAt)
                    .put("distanceM", t.distanceM)
                    .put("pointCount", t.pointCount)
                    .put("folder", t.folder)
            )
        }
        return arr.toString()
    }

    companion object {
        fun pointsFile(context: Context, id: String): File =
            File(File(context.filesDir, "tracks"), "$id.txt")

        /** Append one point; called from the recorder service's worker thread. */
        fun appendPoint(context: Context, id: String, lat: Double, lon: Double, time: Long, alt: Double) {
            runCatching {
                val f = pointsFile(context, id)
                f.parentFile?.mkdirs()
                f.appendText(
                    String.format(Locale.US, "%.7f %.7f %d %.1f\n", lat, lon, time, alt)
                )
            }
        }

        suspend fun readPoints(context: Context, id: String): List<TrackPoint> =
            withContext(Dispatchers.IO) {
                val f = pointsFile(context, id)
                if (!f.exists()) return@withContext emptyList()
                f.readLines().mapNotNull { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size < 3) return@mapNotNull null
                    runCatching {
                        TrackPoint(
                            lat = parts[0].toDouble(),
                            lon = parts[1].toDouble(),
                            time = parts[2].toLong(),
                            alt = parts.getOrNull(3)?.toDouble() ?: NO_ALTITUDE,
                        )
                    }.getOrNull()
                }
            }

        /** Minimal GPX 1.1 document for one track. */
        fun buildGpx(name: String, points: List<TrackPoint>): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"MGRS GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            sb.append("  <trk>\n    <name>").append(escapeXml(name)).append("</name>\n    <trkseg>\n")
            for (p in points) {
                val ele = if (p.alt.hasAltitude()) String.format(Locale.US, "<ele>%.1f</ele>", p.alt) else ""
                sb.append(
                    String.format(
                        Locale.US,
                        "      <trkpt lat=\"%.7f\" lon=\"%.7f\">%s<time>%s</time></trkpt>\n",
                        p.lat, p.lon, ele, sdf.format(Date(p.time)),
                    )
                )
            }
            sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
            return sb.toString()
        }

        private fun escapeXml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun dtgShort(timeMillis: Long): String {
            val sdf = SimpleDateFormat("ddHHmm'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(timeMillis))
        }
    }
}
