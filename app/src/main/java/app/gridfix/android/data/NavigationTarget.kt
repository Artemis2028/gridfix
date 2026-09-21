package app.gridfix.android.data

/**
 * Who Navigate is allowed to guide to, and which point it guides to.
 *
 * The rule that matters: an explicitly selected waypoint ID is authoritative.
 * Navigate never swaps it for a different point because the selected one is
 * hidden, in a hidden folder, or gone. A hidden selection is offered anyway
 * (the user asked for it by name); a missing selection shows as "no target"
 * until the user picks again. An absent selection also means no target: merely
 * importing, restoring or deleting points must not choose a new destination.
 */
object NavigationTarget {

    /** Visible waypoints in visible folders, plus the selected one even if hidden. */
    fun navigable(waypoints: List<Waypoint>, folders: List<FolderInfo>, selectedId: String?): List<Waypoint> {
        val hiddenFolders = folders.filter { !it.visible }.map { it.name }.toSet()
        return waypoints.filter { w ->
            w.id == selectedId || (w.visible && w.folder !in hiddenFolders)
        }
    }

    /**
     * The point to guide to. A selected ID that is not in [candidates] yields
     * null — never another waypoint.
     */
    fun resolve(candidates: List<Waypoint>, selectedId: String?): Waypoint? =
        selectedId?.let { id -> candidates.firstOrNull { it.id == id } }
}
