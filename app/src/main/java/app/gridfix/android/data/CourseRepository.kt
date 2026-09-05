package app.gridfix.android.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.courseStore by preferencesDataStore(
    name = "course",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** A practice land-nav course in progress: ordered waypoints + split times. */
data class CourseState(
    val name: String,
    val waypointIds: List<String>,
    val startedAt: Long,
    val foundAt: List<Long>,   // one entry per point already found, in order
) {
    val nextIndex: Int get() = foundAt.size
    val done: Boolean get() = foundAt.size >= waypointIds.size
}

/** A finished course, kept for the practice log. */
data class CourseResult(
    val name: String,
    val points: Int,
    val startedAt: Long,
    val totalMillis: Long,
    val splitsMillis: List<Long>,
)

/**
 * Practice course engine state. The course itself is ordinary waypoints (in
 * their own folder); this store tracks order, progress, and the results log.
 */
class CourseRepository(private val context: Context) {

    private object Keys {
        val ACTIVE = stringPreferencesKey("active")
        val HISTORY = stringPreferencesKey("history")
    }

    val active: Flow<CourseState?> = context.courseStore.data.map { p ->
        decodeActive(p[Keys.ACTIVE] ?: "")
    }

    val history: Flow<List<CourseResult>> = context.courseStore.data.map { p ->
        decodeHistory(p[Keys.HISTORY] ?: "[]")
    }

    suspend fun start(name: String, waypointIds: List<String>, nowMillis: Long) {
        val o = JSONObject()
            .put("name", name)
            .put("ids", JSONArray(waypointIds))
            .put("started", nowMillis)
            .put("found", JSONArray())
        context.courseStore.edit { it[Keys.ACTIVE] = o.toString() }
    }

    /** Record the next point as found; returns the updated state. */
    suspend fun markFound(nowMillis: Long) {
        context.courseStore.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.done) return@edit
            val o = JSONObject()
                .put("name", cur.name)
                .put("ids", JSONArray(cur.waypointIds))
                .put("started", cur.startedAt)
                .put("found", JSONArray(cur.foundAt + nowMillis))
            p[Keys.ACTIVE] = o.toString()
        }
    }

    /** Move the finished course into the results log and clear the active one. */
    suspend fun finish() {
        context.courseStore.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.foundAt.isNotEmpty()) {
                val splits = buildList {
                    var prev = cur.startedAt
                    for (t in cur.foundAt) {
                        add(t - prev)
                        prev = t
                    }
                }
                val result = CourseResult(
                    name = cur.name,
                    points = cur.waypointIds.size,
                    startedAt = cur.startedAt,
                    totalMillis = cur.foundAt.last() - cur.startedAt,
                    splitsMillis = splits,
                )
                val merged = mergeCourseHistory(decodeHistory(p[Keys.HISTORY] ?: "[]"), listOf(result))
                p[Keys.HISTORY] = encodeHistory(merged.results)
            }
            p[Keys.ACTIVE] = ""
        }
    }

    suspend fun abandon() {
        context.courseStore.edit { it[Keys.ACTIVE] = "" }
    }

    /** Merge backed-up results into the practice log (deduped by start time + name). */
    suspend fun restoreHistory(results: List<CourseResult>): Int {
        var added = 0
        context.courseStore.edit { p ->
            val merged = mergeCourseHistory(decodeHistory(p[Keys.HISTORY] ?: "[]"), results)
            added = merged.added
            p[Keys.HISTORY] = encodeHistory(merged.results)
        }
        return added
    }

    private fun encodeHistory(results: List<CourseResult>): String = JSONArray().apply {
        for (r in results) {
            put(JSONObject()
                .put("name", r.name)
                .put("points", r.points)
                .put("started", r.startedAt)
                .put("total", r.totalMillis)
                .put("splits", JSONArray(r.splitsMillis)))
        }
    }.toString()

    private fun decodeActive(json: String): CourseState? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        val ids = o.getJSONArray("ids")
        val found = o.getJSONArray("found")
        CourseState(
            name = o.getString("name"),
            waypointIds = buildList { for (i in 0 until ids.length()) add(ids.getString(i)) },
            startedAt = o.getLong("started"),
            foundAt = buildList { for (i in 0 until found.length()) add(found.getLong(i)) },
        )
    }.getOrNull()

    private fun decodeHistory(json: String): List<CourseResult> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val splits = o.getJSONArray("splits")
                    CourseResult(
                        name = o.getString("name"),
                        points = o.getInt("points"),
                        startedAt = o.getLong("started"),
                        totalMillis = o.getLong("total"),
                        splitsMillis = buildList {
                            for (j in 0 until splits.length()) add(splits.getLong(j))
                        },
                    )
                }.getOrNull()?.let { add(it) }
            }
        }.sortedByDescending { it.startedAt }   // also correct legacy restore order
    }
}
