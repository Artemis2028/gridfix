package app.gridfix.android.data

data class CourseNavigationDecision(
    val locked: Boolean,
    val targetId: String?,
    val missingWaypointIds: List<String>,
)

/** Evaluate only stored course state and an authoritative waypoint snapshot. */
object CourseNavigationPolicy {
    fun resolve(course: CourseState?, availableIds: Set<String>): CourseNavigationDecision {
        if (course == null || course.done) return CourseNavigationDecision(false, null, emptyList())
        val remaining = course.waypointIds.drop(course.nextIndex)
        val missing = remaining.filter { it !in availableIds }
        return CourseNavigationDecision(
            locked = true,
            targetId = remaining.firstOrNull().takeIf { missing.isEmpty() },
            missingWaypointIds = missing,
        )
    }
}
