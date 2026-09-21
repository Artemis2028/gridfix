package app.gridfix.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    val runId: String = legacyCourseRunId(name, waypointIds, startedAt),
    val missingWaypointIds: List<String> = emptyList(),
) {
    val nextIndex: Int get() = foundAt.size
    val done: Boolean get() = foundAt.size >= waypointIds.size
    val paused: Boolean get() = missingWaypointIds.isNotEmpty()
    val pauseReason: String? get() {
        if (!paused) return null
        val numbers = waypointIds.mapIndexedNotNull { index, id ->
            (index + 1).takeIf { id in missingWaypointIds }
        }.joinToString(", ")
        return if (missingWaypointIds.size == 1) "Checkpoint $numbers is missing."
        else "Checkpoints $numbers are missing."
    }
}

/** Stable identity for an active course saved before run IDs were introduced. */
private fun legacyCourseRunId(name: String, waypointIds: List<String>, startedAt: Long): String =
    UUID.nameUUIDFromBytes("$startedAt\u0000$name\u0000${waypointIds.joinToString("\u0000")}".toByteArray(Charsets.UTF_8)).toString()

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
class CourseRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.courseStore)

    private object Keys {
        val ACTIVE = stringPreferencesKey("active")
        val HISTORY = stringPreferencesKey("history")
    }

    val active: Flow<CourseState?> = store.data.map { p ->
        decodeActive(p[Keys.ACTIVE] ?: "")
    }

    val history: Flow<List<CourseResult>> = store.data.map { p ->
        decodeHistory(p[Keys.HISTORY] ?: "[]")
    }

    suspend fun start(name: String, waypointIds: List<String>, nowMillis: Long): String {
        require(waypointIds.size >= 2 && waypointIds.all { it.isNotBlank() } && waypointIds.distinct().size == waypointIds.size)
        val state = CourseState(name, waypointIds.toList(), nowMillis, emptyList(), runId = UUID.randomUUID().toString())
        store.edit { it[Keys.ACTIVE] = encodeActive(state) }
        return state.runId
    }

    /** Persist a missing-checkpoint pause, or recover it when the same IDs return. */
    suspend fun validateWaypoints(expectedRunId: String, availableIds: Set<String>): CourseState? {
        var result: CourseState? = null
        store.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.runId != expectedRunId) return@edit
            val missing = CourseNavigationPolicy.resolve(cur, availableIds).missingWaypointIds
            result = cur.copy(missingWaypointIds = missing)
            if (result != cur) p[Keys.ACTIVE] = encodeActive(result!!)
        }
        return result
    }

    /** A stale score must never advance a different course or checkpoint. */
    suspend fun markFound(expectedRunId: String, expectedCheckpointId: String, nowMillis: Long): Boolean {
        var accepted = false
        store.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.runId != expectedRunId || cur.done || cur.paused ||
                cur.waypointIds.getOrNull(cur.nextIndex) != expectedCheckpointId
            ) return@edit
            p[Keys.ACTIVE] = encodeActive(cur.copy(foundAt = cur.foundAt + nowMillis))
            accepted = true
        }
        return accepted
    }

    /** Move the finished course into the results log and clear the active one. */
    suspend fun finish(expectedRunId: String): Boolean {
        var finished = false
        store.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.runId != expectedRunId || !cur.done || cur.paused) return@edit
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
            finished = true
        }
        return finished
    }

    suspend fun abandon(expectedRunId: String): Boolean {
        var abandoned = false
        store.edit { p ->
            val cur = decodeActive(p[Keys.ACTIVE] ?: "") ?: return@edit
            if (cur.runId != expectedRunId) return@edit
            p[Keys.ACTIVE] = ""
            abandoned = true
        }
        return abandoned
    }

    /** Merge backed-up results into the practice log (deduped by start time + name). */
    suspend fun restoreHistory(results: List<CourseResult>): Int {
        var added = 0
        store.edit { p ->
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

    private fun encodeActive(state: CourseState): String = JSONObject()
        .put("name", state.name)
        .put("ids", JSONArray(state.waypointIds))
        .put("started", state.startedAt)
        .put("found", JSONArray(state.foundAt))
        .put("run", state.runId)
        .put("missing", JSONArray(state.missingWaypointIds))
        .toString()

    private fun decodeActive(json: String): CourseState? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        val ids = o.getJSONArray("ids")
        val found = o.getJSONArray("found")
        val state = CourseState(
            name = o.getString("name"),
            waypointIds = buildList { for (i in 0 until ids.length()) add(ids.getString(i)) },
            startedAt = o.getLong("started"),
            foundAt = buildList { for (i in 0 until found.length()) add(found.getLong(i)) },
        )
        val missing = o.optJSONArray("missing")
        val missingIds = if (missing == null) emptySet() else buildSet {
            for (i in 0 until missing.length()) add(missing.getString(i))
        }
        state.copy(
            runId = o.optString("run").takeIf { it.isNotBlank() } ?: state.runId,
            missingWaypointIds = state.waypointIds.drop(state.nextIndex).filter { it in missingIds },
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
