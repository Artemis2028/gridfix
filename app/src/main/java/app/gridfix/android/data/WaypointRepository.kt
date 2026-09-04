package app.gridfix.android.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private val Context.wpStore by preferencesDataStore(
    name = "waypoints",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

const val DEFAULT_FOLDER = "Base"

/**
 * Folder names as stored. Before 0.9.9 waypoints defaulted to "Waypoints" and drawn
 * graphics to "Graphics"; both collapse into the one base folder that now holds
 * waypoints, graphics and tracks together, so one eye switch clears the map.
 */
fun canonicalFolder(raw: String?): String = when (raw?.trim().orEmpty()) {
    "", "Waypoints", "Graphics" -> DEFAULT_FOLDER
    else -> raw!!.trim()
}

/** A one-line warning for folder name fields when the typed name will not be kept as typed. */
fun reservedFolderHint(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty() || t == DEFAULT_FOLDER) return null
    return if (canonicalFolder(t) == DEFAULT_FOLDER) "\"$t\" is a reserved name — this goes into $DEFAULT_FOLDER" else null
}

/**
 * The stored spelling of a folder: "recon" and "Recon" are one folder. [known] is
 * every folder name currently in use; the first case-insensitive match wins.
 */
fun matchFolder(known: Iterable<String>, raw: String?): String {
    val clean = canonicalFolder(raw)
    return known.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
}
const val DEFAULT_SYMBOL = "flag"

data class Waypoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val folder: String = DEFAULT_FOLDER,
    val symbol: String = DEFAULT_SYMBOL,
    val affiliation: String = "none",
    val echelon: String = "",       // "", tm, sqd, sec, plt, co, bn, rgt, bde
    val designation: String = "",   // free-text unit designation amplifier
    val kind: String = KIND_WP,     // KIND_WP (navigation point) or KIND_UNIT (plotted unit)
    val rotation: Float = 0f,       // degrees clockwise from north; direction of fire for task symbols
    /**
     * Drawn on the map, or held but hidden. Independent of the folder's eye: a
     * hidden folder hides everything in it, and this hides one item inside a folder
     * that is otherwise shown. Defaults true, so every existing record and every
     * backup written before 0.9.26 reads back visible.
     */
    val visible: Boolean = true,
)

const val KIND_WP = "wp"
const val KIND_UNIT = "unit"

/** Everything needed to create or update a waypoint. */
data class WaypointDraft(
    val name: String,
    val lat: Double,
    val lon: Double,
    val folder: String,
    val symbol: String,
    val affiliation: String,
    val echelon: String = "",
    val designation: String = "",
    val kind: String = KIND_WP,
    val rotation: Float = 0f,
)

/** A waypoint folder ("overlay"): can exist empty, and can be toggled visible/hidden. */
data class FolderInfo(val name: String, val visible: Boolean = true)

class WaypointRepository(private val context: Context) {

    private val listKey = stringPreferencesKey("list")
    private val selectedKey = stringPreferencesKey("selected")
    private val foldersKey = stringPreferencesKey("folders")

    val waypoints: Flow<List<Waypoint>> = context.wpStore.data.map { p ->
        decode(p[listKey] ?: "[]")
    }

    val selectedId: Flow<String?> = context.wpStore.data.map { p -> p[selectedKey] }

    /** Stored folders unioned with any folder referenced by a waypoint: Base first, then by name. */
    val folders: Flow<List<FolderInfo>> = context.wpStore.data.map { p ->
        val stored = decodeFolders(p[foldersKey] ?: "[]")
        val referenced = decode(p[listKey] ?: "[]").map { it.folder }
        val names = (stored.map { it.name } + referenced + DEFAULT_FOLDER).distinct()
        names.map { n -> stored.firstOrNull { it.name == n } ?: FolderInfo(n) }
            .sortedWith(compareBy({ it.name != DEFAULT_FOLDER }, { it.name.lowercase(Locale.US) }))
    }

    /** Every folder name in use right now (stored entries and waypoint references). */
    private fun knownNames(p: androidx.datastore.preferences.core.Preferences): List<String> =
        (decodeFolders(p[foldersKey] ?: "[]").map { it.name } + decode(p[listKey] ?: "[]").map { it.folder } + DEFAULT_FOLDER).distinct()

    /** The spelling this name will be stored under (case-insensitive match against existing folders). */
    suspend fun resolveFolder(raw: String): String =
        matchFolder(knownNames(context.wpStore.data.first()), raw)

    /** Create a folder (or match an existing one, ignoring case). Returns the stored name. */
    suspend fun addFolder(name: String): String {
        if (name.isBlank()) return DEFAULT_FOLDER
        var stored = canonicalFolder(name)
        context.wpStore.edit { p ->
            stored = matchFolder(knownNames(p), name)
            p[foldersKey] = encodeFolders(
                upsertFolder(decodeFolders(p[foldersKey] ?: "[]"), stored, null)
            )
        }
        return stored
    }

    suspend fun setFolderVisible(name: String, visible: Boolean) {
        context.wpStore.edit { p ->
            p[foldersKey] = encodeFolders(
                upsertFolder(decodeFolders(p[foldersKey] ?: "[]"), name, visible)
            )
        }
    }

    /**
     * Rename a folder, moving its waypoints. Renaming onto an existing folder (any
     * case) merges into it. Base cannot be renamed. Returns the name now in use.
     */
    suspend fun renameFolder(from: String, to: String): String {
        if (from == DEFAULT_FOLDER || to.isBlank()) return from
        var target = canonicalFolder(to)
        context.wpStore.edit { p ->
            val stored = decodeFolders(p[foldersKey] ?: "[]")
            target = matchFolder(knownNames(p).filter { it != from }, to)
            if (target == from) return@edit
            val src = stored.firstOrNull { it.name == from }
            val merged = stored.filterNot { it.name == from }.toMutableList()
            mergeFolder(merged, FolderInfo(target, src?.visible ?: true))
            p[foldersKey] = encodeFolders(merged)
            p[listKey] = encode(decode(p[listKey] ?: "[]").map { if (it.folder == from) it.copy(folder = target) else it })
        }
        return target
    }

    /**
     * Remove a folder: its waypoints are deleted with it, or moved to Base when
     * [deleteContents] is false. Base cannot be deleted.
     */
    suspend fun deleteFolder(name: String, deleteContents: Boolean) {
        if (name == DEFAULT_FOLDER) return
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            val kept = if (deleteContents) current.filterNot { it.folder == name }
            else current.map { if (it.folder == name) it.copy(folder = DEFAULT_FOLDER) else it }
            p[listKey] = encode(kept)
            p[foldersKey] = encodeFolders(decodeFolders(p[foldersKey] ?: "[]").filterNot { it.name == name })
            val sel = p[selectedKey]
            if (sel != null && kept.none { it.id == sel }) p.remove(selectedKey)
        }
    }

    suspend fun add(draft: WaypointDraft, nowMillis: Long): String {
        val newId = UUID.randomUUID().toString()
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            val wp = Waypoint(
                id = newId,
                name = draft.name,
                lat = draft.lat,
                lon = draft.lon,
                createdAt = nowMillis,
                folder = matchFolder(knownNames(p), draft.folder),
                symbol = draft.symbol.ifBlank { DEFAULT_SYMBOL },
                affiliation = draft.affiliation,
                echelon = draft.echelon,
                designation = draft.designation,
                kind = draft.kind,
                rotation = draft.rotation,
            )
            p[listKey] = encode(current + wp)
            p[foldersKey] = encodeFolders(
                upsertFolder(decodeFolders(p[foldersKey] ?: "[]"), wp.folder, null)
            )
            if (p[selectedKey] == null) {
                p[selectedKey] = wp.id
            }
        }
        return newId
    }

    /** Bulk add (imports): one datastore write however many points arrive. */
    suspend fun addAll(drafts: List<WaypointDraft>, nowMillis: Long) {
        if (drafts.isEmpty()) return
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            var folders = decodeFolders(p[foldersKey] ?: "[]")
            val known = knownNames(p).toMutableList()
            val added = drafts.map { draft ->
                val f = matchFolder(known, draft.folder)
                if (f !in known) known.add(f)
                folders = upsertFolder(folders, f, null)
                Waypoint(
                    id = UUID.randomUUID().toString(),
                    name = draft.name,
                    lat = draft.lat,
                    lon = draft.lon,
                    createdAt = nowMillis,
                    folder = f,
                    symbol = draft.symbol.ifBlank { DEFAULT_SYMBOL },
                    affiliation = draft.affiliation,
                    echelon = draft.echelon,
                    designation = draft.designation,
                    kind = draft.kind,
                    rotation = draft.rotation,
                )
            }
            p[listKey] = encode(current + added)
            p[foldersKey] = encodeFolders(folders)
        }
    }

    /**
     * Merge a backup: waypoints keep their original ids and ids already on the
     * device are skipped, so restoring twice never duplicates. Folder entries
     * merge by name (existing visibility wins). Returns how many were added.
     */
    suspend fun restore(imported: List<Waypoint>, importedFolders: List<FolderInfo>): Int {
        var added = 0
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            val ids = current.map { it.id }.toSet()
            val fresh = imported.filter { it.id !in ids }
            added = fresh.size
            p[listKey] = encode(current + fresh)
            val merged = decodeFolders(p[foldersKey] ?: "[]").toMutableList()
            val known = merged.map { it.name }.toSet()
            // folders new to this device come in with their backed-up visibility; existing ones keep theirs
            for (f in importedFolders.map { it.copy(name = canonicalFolder(it.name)) }) {
                if (f.name !in known) mergeFolder(merged, f)
            }
            var folders: List<FolderInfo> = merged
            for (w in fresh) folders = upsertFolder(folders, w.folder, null)
            p[foldersKey] = encodeFolders(folders)
        }
        return added
    }

    suspend fun update(id: String, draft: WaypointDraft) {
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            val folder = matchFolder(knownNames(p), draft.folder)
            p[listKey] = encode(
                current.map {
                    if (it.id == id) it.copy(
                        name = draft.name,
                        lat = draft.lat,
                        lon = draft.lon,
                        folder = folder,
                        symbol = draft.symbol.ifBlank { DEFAULT_SYMBOL },
                        affiliation = draft.affiliation,
                        echelon = draft.echelon,
                        designation = draft.designation,
                        kind = draft.kind,
                        rotation = draft.rotation,
                    ) else it
                }
            )
            p[foldersKey] = encodeFolders(
                upsertFolder(decodeFolders(p[foldersKey] ?: "[]"), folder, null)
            )
        }
    }

    suspend fun delete(id: String) {
        context.wpStore.edit { p ->
            val current = decode(p[listKey] ?: "[]")
            p[listKey] = encode(current.filterNot { it.id == id })
            if (p[selectedKey] == id) {
                p.remove(selectedKey)
            }
        }
    }

    /**
     * Show or hide one waypoint on the map without deleting it, independently of its
     * folder's eye. The list still shows it, marked hidden.
     */
    suspend fun setVisible(id: String, visible: Boolean) {
        context.wpStore.edit { p ->
            p[listKey] = encode(decode(p[listKey] ?: "[]").map { if (it.id == id) it.copy(visible = visible) else it })
        }
    }

    suspend fun select(id: String) {
        context.wpStore.edit { p -> p[selectedKey] = id }
    }

    // One damaged record must not take the whole list with it: skip it, keep the rest.
    private fun decode(json: String): List<Waypoint> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                runCatching {
                    Waypoint(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        createdAt = o.optLong("createdAt"),
                        folder = canonicalFolder(o.optString("folder", DEFAULT_FOLDER)),
                        symbol = o.optString("symbol", DEFAULT_SYMBOL),
                        affiliation = o.optString("affiliation", "none"),
                        echelon = o.optString("echelon", ""),
                        designation = o.optString("designation", ""),
                        kind = o.optString(
                            "kind",
                            // migrate pre-0.5 data: NATO symbols were always units
                            if (o.optString("symbol", "").startsWith("nato_")) KIND_UNIT else KIND_WP,
                        ),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        visible = o.optBoolean("visible", true),
                    )
                }.getOrNull()?.let { add(it) }
            }
        }
    }

    /** Add or update a folder entry; visible == null keeps the existing visibility. */
    private fun upsertFolder(list: List<FolderInfo>, name: String, visible: Boolean?): List<FolderInfo> {
        val existing = list.firstOrNull { it.name == name }
        return when {
            existing == null -> list + FolderInfo(name, visible ?: true)
            visible == null -> list
            else -> list.map { if (it.name == name) it.copy(visible = visible) else it }
        }
    }

    private fun decodeFolders(json: String): List<FolderInfo> = runCatching {
        val arr = JSONArray(json)
        val out = ArrayList<FolderInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            mergeFolder(out, FolderInfo(canonicalFolder(o.getString("name")), o.optBoolean("visible", true)))
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Add a folder entry, merging by canonical name. Legacy "Waypoints" and "Graphics"
     * both map to Base: if either was visible, Base is visible - nothing a user could
     * see before an upgrade disappears from the map because of the merge.
     */
    private fun mergeFolder(list: MutableList<FolderInfo>, f: FolderInfo) {
        val idx = list.indexOfFirst { it.name == f.name }
        if (idx < 0) list.add(f)
        else if (f.visible && !list[idx].visible) list[idx] = list[idx].copy(visible = true)
    }

    private fun encodeFolders(list: List<FolderInfo>): String {
        val arr = JSONArray()
        for (f in list) {
            arr.put(JSONObject().put("name", f.name).put("visible", f.visible))
        }
        return arr.toString()
    }

    private fun encode(list: List<Waypoint>): String {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("name", w.name)
                    .put("lat", w.lat)
                    .put("lon", w.lon)
                    .put("createdAt", w.createdAt)
                    .put("folder", w.folder)
                    .put("symbol", w.symbol)
                    .put("affiliation", w.affiliation)
                    .put("echelon", w.echelon)
                    .put("designation", w.designation)
                    .put("kind", w.kind)
                    .put("rotation", w.rotation.toDouble())
                    .put("visible", w.visible)
            )
        }
        return arr.toString()
    }
}
