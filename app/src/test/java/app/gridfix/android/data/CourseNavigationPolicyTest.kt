package app.gridfix.android.data

import org.junit.Assert.*
import org.junit.Test

class CourseNavigationPolicyTest {
    private val course = CourseState("Practice", listOf("cp1", "cp2", "cp3"), 100, emptyList())

    @Test fun activeCourseLocksNavigationToCurrentCheckpoint() {
        val available = setOf("unrelated", "cp3", "cp2", "cp1")
        val first = CourseNavigationPolicy.resolve(course, available)
        assertTrue(first.locked)
        assertEquals("cp1", first.targetId)
        assertTrue(first.missingWaypointIds.isEmpty())

        val second = CourseNavigationPolicy.resolve(course.copy(foundAt = listOf(200)), available)
        assertTrue(second.locked)
        assertEquals("cp2", second.targetId)
    }

    @Test fun missingFutureCheckpointBlocksNavigationBeforeItIsReached() {
        val decision = CourseNavigationPolicy.resolve(course, setOf("cp1", "cp2", "unrelated"))
        assertTrue(decision.locked)
        assertNull(decision.targetId)
        assertEquals(listOf("cp3"), decision.missingWaypointIds)
    }

    @Test fun missingCurrentCheckpointNeverSkipsToAnAvailableLaterPoint() {
        val decision = CourseNavigationPolicy.resolve(course, setOf("cp2", "cp3"))
        assertTrue(decision.locked)
        assertNull(decision.targetId)
        assertEquals(listOf("cp1"), decision.missingWaypointIds)
    }

    @Test fun deletingVisitedCheckpointsDoesNotBlockRemainingCourse() {
        val decision = CourseNavigationPolicy.resolve(course.copy(foundAt = listOf(200)), setOf("cp2", "cp3"))
        assertTrue(decision.locked)
        assertEquals("cp2", decision.targetId)
        assertTrue(decision.missingWaypointIds.isEmpty())
    }

    @Test fun authoritativeRestorationRecoversPolicyWithoutWaitingForPersistedPause() {
        val paused = course.copy(missingWaypointIds = listOf("cp1", "cp3"))
        val decision = CourseNavigationPolicy.resolve(paused, setOf("cp1", "cp2", "cp3"))
        assertEquals("cp1", decision.targetId)
        assertTrue(decision.missingWaypointIds.isEmpty())
    }

    @Test fun absentAndCompletedCoursesReleaseNavigationLock() {
        for (state in listOf(null, course.copy(foundAt = listOf(200, 300, 400)))) {
            val decision = CourseNavigationPolicy.resolve(state, emptySet())
            assertFalse(decision.locked)
            assertNull(decision.targetId)
            assertTrue(decision.missingWaypointIds.isEmpty())
        }
    }
}
