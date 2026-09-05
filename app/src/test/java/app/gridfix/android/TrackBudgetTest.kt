package app.gridfix.android

import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.simplifyTrackToBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackBudgetTest {
    @Test
    fun `backtrack budget retains destination and ordered shape across the full recording`() {
        val points = (0..200).map { i ->
            GeoVertex(36.0 + i * 0.0001, 45.0 + if (i % 2 == 0) 0.0 else 0.001)
        }.reversed()
        val result = simplifyTrackToBudget(points, 2.0, 64)

        assertEquals(64, result.size)
        assertEquals(points.first(), result.first())
        assertEquals("Backtrack must reach the recording's origin", points.last(), result.last())
        val indices = result.map(points::indexOf)
        assertTrue(indices.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `small budget prioritizes the largest bend even near the end`() {
        val points = (0..100).map { i ->
            val north = when (i) {
                10 -> 0.001
                90 -> 0.01
                else -> 0.0
            }
            GeoVertex(north, i * 0.001)
        }
        val result = simplifyTrackToBudget(points, 1.0, 3)
        assertEquals(listOf(points.first(), points[90], points.last()), result)
    }

    @Test
    fun `collinear out-and-back preserves its turnaround`() {
        val points = listOf(GeoVertex(0.0, 0.0), GeoVertex(0.0, 0.02), GeoVertex(0.0, 0.01))
        assertEquals(points, simplifyTrackToBudget(points, 20.0, 64))
    }

    @Test
    fun `tolerance avoids filling the budget with straight-line noise`() {
        val points = (0..100).map { i -> GeoVertex(if (i % 2 == 0) 0.0 else 0.00001, i * 0.001) }
        assertEquals(listOf(points.first(), points.last()), simplifyTrackToBudget(points, 2.0, 64))
    }

    @Test
    fun `two point budget still spans the full track`() {
        val points = listOf(GeoVertex(36.0, 45.0), GeoVertex(36.01, 45.01), GeoVertex(36.02, 45.0))
        assertEquals(listOf(points.first(), points.last()), simplifyTrackToBudget(points, 0.0, 2))
        assertTrue(simplifyTrackToBudget(emptyList(), 0.0, 2).isEmpty())
    }
}
