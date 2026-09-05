package app.gridfix.android.data

/** Results are stored oldest first; [added] counts new results actually retained. */
internal data class CourseHistoryMerge(val results: List<CourseResult>, val added: Int)

internal fun mergeCourseHistory(
    existing: List<CourseResult>,
    incoming: List<CourseResult>,
    limit: Int = 30,
): CourseHistoryMerge {
    require(limit >= 0)
    fun key(result: CourseResult) = result.startedAt to result.name
    val existingKeys = existing.mapTo(HashSet()) { key(it) }
    val retained = (existing + incoming)
        .distinctBy { key(it) }
        .sortedWith(compareBy<CourseResult> { it.startedAt }.thenBy { it.name })
        .takeLast(limit)
    return CourseHistoryMerge(retained, retained.count { key(it) !in existingKeys })
}
