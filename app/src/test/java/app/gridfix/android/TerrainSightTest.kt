package app.gridfix.android

import app.gridfix.android.map.SightStatus
import app.gridfix.android.map.TerrainSight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainSightTest {
    @Test
    fun `flat twenty kilometre path is masked with minimum clearance at midpoint`() {
        val distances = FloatArray(501) { it * 40f }
        val result = TerrainSight.lineOfSight(distances, FloatArray(501), 2f, 2f)!!

        assertEquals(SightStatus.MASKED, result.status)
        assertEquals(-3.886038f, result.minClearanceM, 0.00001f)
        assertEquals(10_000f, result.minClearanceDistM, 0f)
        assertEquals(5.886038f, result.effectiveTerrain[250], 0.00001f)
        assertEquals(2f, result.sightLine[250], 0f)
        assertTrue(result.blockIndex in 1 until 250)

        // The old subtraction had its MINIMUM near the endpoints, not at
        // the midpoint. 11.772 m is the midpoint error, not minGap's error.
        val oldMidpointGap = 2f + result.effectiveTerrain[250]
        assertEquals(11.772076f, oldMidpointGap - result.minClearanceM, 0.00001f)
        val oldMinimum = (1 until distances.lastIndex).minOf { 2f + result.effectiveTerrain[it] }
        assertTrue(oldMinimum < oldMidpointGap)
    }

    @Test
    fun `short flat path is visible and near horizon clearance is marginal`() {
        val near = TerrainSight.lineOfSight(floatArrayOf(0f, 500f, 1_000f), FloatArray(3), 2f, 2f)!!
        assertEquals(SightStatus.VISIBLE, near.status)
        assertEquals(1.985285f, near.minClearanceM, 0.00001f)

        val horizon = TerrainSight.lineOfSight(floatArrayOf(0f, 5_000f, 10_000f), FloatArray(3), 2f, 2f)!!
        assertEquals(SightStatus.VISIBLE, horizon.status)
        assertEquals(0.5284905f, horizon.minClearanceM, 0.00001f)
    }

    @Test
    fun `curvature correction is zero at both endpoints`() {
        val result = TerrainSight.lineOfSight(
            floatArrayOf(0f, 500f, 1_000f), floatArrayOf(100f, 110f, 120f), 2f, 3f,
        )!!
        assertEquals(100f, result.effectiveTerrain.first(), 0f)
        assertEquals(120f, result.effectiveTerrain.last(), 0f)
        assertEquals(102f, result.sightLine.first(), 0f)
        assertEquals(123f, result.sightLine.last(), 0f)
        assertEquals(SightStatus.VISIBLE, result.status)
    }

    @Test
    fun `known crest blocks path and required observer height clears sampled terrain`() {
        val distances = floatArrayOf(0f, 250f, 500f, 750f, 1_000f)
        val elevations = floatArrayOf(120f, 120f, 140f, 120f, 120f)
        val result = TerrainSight.lineOfSight(distances, elevations, 2f, 2f)!!
        assertEquals(SightStatus.MASKED, result.status)
        assertEquals(2, result.blockIndex)
        assertEquals(500f, result.minClearanceDistM, 0f)
        assertTrue(result.clearObserverHeight > 39f)
        val raised = TerrainSight.lineOfSight(distances, elevations, result.clearObserverHeight + 0.01f, 2f)!!
        assertEquals(SightStatus.VISIBLE, raised.status)
    }

    @Test
    fun `missing interior terrain makes otherwise clear path unknown`() {
        val result = TerrainSight.lineOfSight(
            floatArrayOf(0f, 250f, 500f, 750f, 1_000f),
            floatArrayOf(0f, 0f, Float.NaN, 0f, 0f), 2f, 2f,
        )!!
        assertEquals(SightStatus.UNKNOWN, result.status)
        assertEquals(-1, result.blockIndex)
        assertTrue(result.minClearanceM.isNaN())
        assertTrue(result.clearObserverHeight.isNaN())
        assertTrue(result.effectiveTerrain[2].isNaN())
    }

    @Test
    fun `all missing interior terrain is never visible`() {
        val result = TerrainSight.lineOfSight(
            floatArrayOf(0f, 500f, 1_000f), floatArrayOf(0f, Float.NaN, 0f), 2f, 2f,
        )!!
        assertEquals(SightStatus.UNKNOWN, result.status)
    }

    @Test
    fun `known obstruction remains masked despite a gap but gives no clearance promise`() {
        val result = TerrainSight.lineOfSight(
            floatArrayOf(0f, 250f, 500f, 750f, 1_000f),
            floatArrayOf(0f, Float.NaN, 20f, 0f, 0f), 2f, 2f,
        )!!
        assertEquals(SightStatus.MASKED, result.status)
        assertEquals(2, result.blockIndex)
        assertTrue(result.clearObserverHeight.isNaN())
        assertTrue(result.minClearanceM.isNaN())
    }

    @Test
    fun `missing endpoints cannot define a sight line`() {
        val distances = floatArrayOf(0f, 500f, 1_000f)
        assertNull(TerrainSight.lineOfSight(distances, floatArrayOf(Float.NaN, 0f, 0f), 2f, 2f))
        assertNull(TerrainSight.lineOfSight(distances, floatArrayOf(0f, 0f, Float.NaN), 2f, 2f))
        assertNull(TerrainSight.lineOfSight(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), 2f, 2f))
    }

    @Test
    fun `viewshed remains unknown beyond a missing sample even at a high crest`() {
        val ray = TerrainSight.Ray(2.0)
        assertEquals(TerrainSight.CLS_VISIBLE, ray.sample(100.0, 0.0))
        assertEquals(TerrainSight.CLS_UNKNOWN, ray.sample(200.0, null))
        assertEquals(TerrainSight.CLS_UNKNOWN, ray.sample(300.0, 0.0))
        assertEquals(TerrainSight.CLS_UNKNOWN, ray.sample(400.0, 100.0))
    }

    @Test
    fun `viewshed can still prove masking using a known crest beyond a gap`() {
        val ray = TerrainSight.Ray(2.0)
        ray.sample(100.0, null)
        assertEquals(TerrainSight.CLS_UNKNOWN, ray.sample(200.0, 20.0))
        assertEquals(TerrainSight.CLS_MASKED, ray.sample(300.0, 0.0))
    }

    @Test
    fun `viewshed uses tangent frame drop and masks beyond the horizon`() {
        val ray = TerrainSight.Ray(2.0)
        for (d in 100..19_900 step 100) ray.sample(d.toDouble(), 0.0)
        assertEquals(TerrainSight.CLS_MASKED, ray.sample(20_000.0, 0.0))
    }

    @Test
    fun `overlapping ray samples cannot overwrite an unknown cell with visible`() {
        assertEquals(TerrainSight.CLS_UNKNOWN, TerrainSight.mergeClasses(TerrainSight.CLS_UNKNOWN, TerrainSight.CLS_VISIBLE))
        assertEquals(TerrainSight.CLS_UNKNOWN, TerrainSight.mergeClasses(TerrainSight.CLS_VISIBLE, TerrainSight.CLS_UNKNOWN))
        assertEquals(TerrainSight.CLS_MASKED, TerrainSight.mergeClasses(TerrainSight.CLS_MASKED, TerrainSight.CLS_VISIBLE))
        assertEquals(TerrainSight.CLS_VISIBLE, TerrainSight.mergeClasses(TerrainSight.CLS_NONE, TerrainSight.CLS_VISIBLE))
    }
}
