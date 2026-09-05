package app.gridfix.android

import app.gridfix.android.data.CourseResult
import app.gridfix.android.data.mergeCourseHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseHistoryTest {
    private fun result(start: Long, name: String = "Practice") =
        CourseResult(name, 1, start, 1000L, listOf(1000L))

    @Test
    fun `restoring an old backup cannot evict thirty more recent results`() {
        val recent = (100L..129L).map { result(it) }
        val restored = mergeCourseHistory(recent.reversed(), (1L..40L).map { result(it) })
        assertEquals(recent, restored.results)
        assertEquals("Discarded old results were not added", 0, restored.added)
    }

    @Test
    fun `new restored results displace only the oldest and count only retained additions`() {
        val recent = (100L..129L).map { result(it) }
        val restored = mergeCourseHistory(recent, listOf(result(1), result(131), result(130)))
        assertEquals((102L..131L).map { result(it) }, restored.results)
        assertEquals(2, restored.added)
    }

    @Test
    fun `duplicates in incoming backup and existing history merge once`() {
        val saved = result(10)
        val incoming = result(20)
        val restored = mergeCourseHistory(listOf(saved, saved), listOf(incoming, saved, incoming))
        assertEquals(listOf(saved, incoming), restored.results)
        assertEquals(1, restored.added)
        assertEquals(0, mergeCourseHistory(restored.results, listOf(incoming, saved)).added)
    }

    @Test
    fun `courses with identical start but different names remain distinct`() {
        val restored = mergeCourseHistory(listOf(result(10, "Alpha")), listOf(result(10, "Bravo")))
        assertEquals(2, restored.results.size)
        assertEquals(1, restored.added)
    }

    @Test
    fun `finishing a course normalizes an out-of-order legacy history`() {
        val restored = mergeCourseHistory(listOf(result(30), result(10), result(20)), listOf(result(40)), 3)
        assertEquals(listOf(result(20), result(30), result(40)), restored.results)
        assertEquals(1, restored.added)
    }
}
