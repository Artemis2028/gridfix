package app.gridfix.android.map

/** Confidence in visibility through the sampled terrain, excluding vegetation and structures. */
enum class SightStatus { VISIBLE, MASKED, UNKNOWN }

/** Pure terrain calculations shared by point-to-point LOS and radial viewsheds. */
internal object TerrainSight {
    const val EARTH_R = 6371008.8
    const val EFFECTIVE_R = EARTH_R * 4.0 / 3.0
    const val CLS_NONE = 0
    const val CLS_VISIBLE = 1
    const val CLS_MARGINAL = 2
    const val CLS_MASKED = 3
    const val CLS_UNKNOWN = 4

    data class Result(
        val status: SightStatus,
        val blockIndex: Int,
        val clearObserverHeight: Float,
        val minClearanceM: Float,
        val minClearanceDistM: Float,
        val sightLine: FloatArray,
        val effectiveTerrain: FloatArray,
    )

    /**
     * Use the chord joining the endpoint ground levels as the height frame.
     * The Earth rises ABOVE that chord by d1*d2/(2*Re); it must be added to
     * terrain, unlike the subtraction used in an observer's tangent frame.
     * Missing endpoint elevations cannot define a sight line and return null.
     */
    fun lineOfSight(
        distancesM: FloatArray,
        elevations: FloatArray,
        observerHeight: Float,
        targetHeight: Float,
    ): Result? {
        val n = elevations.size
        if (n < 2 || distancesM.size != n) return null
        val totalD = distancesM.last().toDouble()
        if (!totalD.isFinite() || totalD <= 0.0 || distancesM.first() != 0f ||
            distancesM.any { !it.isFinite() || it < 0f || it > totalD } ||
            (1 until n).any { distancesM[it] < distancesM[it - 1] } ||
            !elevations.first().isFinite() || !elevations.last().isFinite() ||
            !observerHeight.isFinite() || !targetHeight.isFinite()
        ) return null

        val obsGround = elevations.first()
        val a = obsGround + observerHeight
        val b = elevations.last() + targetHeight
        val sight = FloatArray(n)
        val effective = FloatArray(n)
        val clearance = 0.5f
        var blockIndex = -1
        var missing = false
        var required = 0f
        var minGap = Float.POSITIVE_INFINITY
        var minGapDist = 0f
        for (i in 0 until n) {
            val d = distancesM[i].toDouble()
            val t = (d / totalD).toFloat()
            sight[i] = a + (b - a) * t
            effective[i] = if (elevations[i].isFinite()) {
                (elevations[i] + d * (totalD - d) / (2.0 * EFFECTIVE_R)).toFloat()
            } else Float.NaN
            if (i == 0 || i == n - 1) continue
            if (!effective[i].isFinite()) {
                missing = true
                continue
            }
            val gap = sight[i] - effective[i]
            if (gap < minGap) {
                minGap = gap
                minGapDist = distancesM[i]
            }
            if (gap < clearance && blockIndex == -1) blockIndex = i
            if (t < 1f) {
                val need = (effective[i] + clearance - b * t) / (1f - t) - obsGround
                if (need > required) required = need
            }
        }
        return Result(
            status = when {
                blockIndex >= 0 -> SightStatus.MASKED
                missing -> SightStatus.UNKNOWN
                else -> SightStatus.VISIBLE
            },
            blockIndex = blockIndex,
            // A known blocker proves masking despite a gap, but neither the
            // minimum clearance nor a height that clears the path is known.
            clearObserverHeight = if (missing) Float.NaN else required.coerceAtLeast(0f),
            minClearanceM = if (!missing && minGap.isFinite()) minGap else Float.NaN,
            minClearanceDistM = if (missing) Float.NaN else minGapDist,
            sightLine = sight,
            effectiveTerrain = effective,
        )
    }

    /** One outward sweep. Unknown terrain may hide an obstruction at any later distance. */
    class Ray(private val eye: Double) {
        private var maxSlope = Double.NEGATIVE_INFINITY
        private var hasGap = false

        fun sample(distanceM: Double, elevation: Double?): Int {
            require(distanceM.isFinite() && distanceM > 0.0)
            if (elevation == null || !elevation.isFinite()) {
                hasGap = true
                return CLS_UNKNOWN
            }
            // Here the frame is tangent to the Earth at the observer: distant
            // ground drops below it. Do not use the LOS chord-frame sign here.
            val terrain = elevation - distanceM * distanceM / (2.0 * EFFECTIVE_R)
            val low = (terrain + 0.5 - eye) / distanceM
            val standing = (terrain + 3.0 - eye) / distanceM
            val result = when {
                standing < maxSlope -> CLS_MASKED
                hasGap -> CLS_UNKNOWN
                low >= maxSlope -> CLS_VISIBLE
                else -> CLS_MARGINAL
            }
            maxSlope = maxOf(maxSlope, (terrain - eye) / distanceM)
            return result
        }
    }

    /** Multiple rays can touch one raster cell; never paint over uncertainty with green. */
    fun mergeClasses(previous: Int, incoming: Int): Int = maxOf(previous, incoming)
}
