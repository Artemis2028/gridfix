package app.gridfix.android.map

import android.content.Context
import android.graphics.Bitmap
import app.gridfix.android.data.GeoVertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Terrain analysis on the cached elevation data: line-of-sight between two
 * points and elevation profiles along routes. Uses the same Terrarium tiles
 * as the crosshair readout (~10-30 m source data), so anything works offline
 * once the area's elevation has been viewed or downloaded.
 *
 * Line of sight applies the standard earth-curvature + atmospheric-refraction
 * correction (effective earth radius x 4/3): terrain between the endpoints is
 * raised above their chord by d1*d2 / (2 * Re), which makes ~1.7 m eyes see ~5.4 km
 * to a sea-level horizon.
 */
object Terrain {

    private const val EARTH_R = TerrainSight.EARTH_R

    data class Profile(
        val distancesM: FloatArray,   // cumulative along-path distance per sample
        val elevations: FloatArray,   // metres MSL; Float.NaN where no data
        val legEndIndex: IntArray,    // sample index where each leg ends (route profiles)
        val missing: Int,             // samples with no elevation data
    ) {
        val totalM: Float get() = if (distancesM.isEmpty()) 0f else distancesM.last()

        /** Total climb and descent, ignoring jitter under [threshold] metres. */
        fun gainLoss(threshold: Float = 1.5f): Pair<Float, Float> {
            var gain = 0f
            var loss = 0f
            var anchor = Float.NaN
            for (e in elevations) {
                if (e.isNaN()) continue
                if (anchor.isNaN()) {
                    anchor = e
                    continue
                }
                val d = e - anchor
                if (d >= threshold) {
                    gain += d
                    anchor = e
                } else if (d <= -threshold) {
                    loss -= d
                    anchor = e
                }
            }
            return gain to loss
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Sample elevations along a multi-leg path, ~[stepM] apart, capped at [maxSamples]. */
    suspend fun profile(
        context: Context,
        points: List<GeoVertex>,
        stepM: Double = 25.0,
        maxSamples: Int = 600,
    ): Profile? {
        if (points.size < 2) return null
        val legLens = DoubleArray(points.size - 1)
        var total = 0.0
        for (i in 0 until points.size - 1) {
            legLens[i] = haversineM(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
            total += legLens[i]
        }
        if (total <= 0.0) return null
        val step = max(stepM, total / maxSamples)

        val dists = ArrayList<Float>()
        val elevs = ArrayList<Float>()
        val legEnds = ArrayList<Int>()
        var missing = 0
        var walked = 0.0

        suspend fun sampleAt(lat: Double, lon: Double, dist: Double) {
            dists.add(dist.toFloat())
            val e = Elevation.elevationAt(context, lat, lon)
            if (e == null || !e.isFinite()) {
                missing++
                elevs.add(Float.NaN)
            } else {
                elevs.add(e.toFloat())
            }
        }

        sampleAt(points[0].lat, points[0].lon, 0.0)
        for (i in 0 until points.size - 1) {
            val len = legLens[i]
            if (len > 0.0) {
                var d = step
                while (d < len) {
                    val t = d / len
                    sampleAt(
                        points[i].lat + (points[i + 1].lat - points[i].lat) * t,
                        points[i].lon + (points[i + 1].lon - points[i].lon) * t,
                        walked + d,
                    )
                    d += step
                }
            }
            walked += len
            sampleAt(points[i + 1].lat, points[i + 1].lon, walked)
            legEnds.add(dists.size - 1)
        }
        return Profile(
            distancesM = dists.toFloatArray(),
            elevations = elevs.toFloatArray(),
            legEndIndex = legEnds.toIntArray(),
            missing = missing,
        )
    }

    data class LosResult(
        val status: SightStatus,
        val profile: Profile,
        val observerElev: Float,      // ground MSL at observer
        val targetElev: Float,        // ground MSL at target
        val observerHeight: Float,    // metres above ground
        val targetHeight: Float,
        val blockIndex: Int,          // first known blocking sample (-1 when none is known)
        val blockDistM: Float,
        val blockLat: Double,
        val blockLon: Double,
        val clearObserverHeight: Float, // min observer height (m AGL); NaN when terrain is incomplete
        /** Smallest gap between the sight line and terrain (m); the margin of a VISIBLE result. */
        val minClearanceM: Float,
        /** Along-path distance (m) of that tightest point. */
        val minClearanceDistM: Float,
        /** Sight-line height (curvature-corrected frame) per sample, for drawing. */
        val sightLine: FloatArray,
        /** Terrain in the same curvature-corrected frame, for drawing. */
        val effectiveTerrain: FloatArray,
    )

    const val CLS_NONE = TerrainSight.CLS_NONE
    const val CLS_VISIBLE = TerrainSight.CLS_VISIBLE
    const val CLS_MARGINAL = TerrainSight.CLS_MARGINAL
    const val CLS_MASKED = TerrainSight.CLS_MASKED
    const val CLS_UNKNOWN = TerrainSight.CLS_UNKNOWN

    // Day palette: traffic-light. Night palette: red-family intensity ramp
    // (faint dark red = seen, mid = standing only, bright red = masked), so
    // the shade stays inside the night mode's red-light discipline instead of
    // washing dark adaptation with green and amber.
    private val DAY_PALETTE = intArrayOf(0, 0x4600C853, 0x55FFD600, 0x50FF1744, 0x66808080)
    private val NIGHT_PALETTE = intArrayOf(0, 0x307F231D, 0x48C42D24, 0x60FF3B30, 0x60FF3B30)

    /**
     * A computed viewshed raster: what the observer can see out to [radiusM].
     * Cell classes: VISIBLE (even a low target is seen), MARGINAL (only a
     * standing man / vehicle-height target is seen — partial defilade),
     * MASKED (full defilade for a 3 m target). Missing terrain and cells whose
     * visibility depends on it are UNKNOWN and hatched. Colors are applied via [bitmapFor], so
     * the same computed shade renders with the day or night palette.
     */
    data class Viewshed(
        val classes: IntArray,
        val gridN: Int,
        val latN: Double,
        val latS: Double,
        val lonW: Double,
        val lonE: Double,
        val obsLat: Double,
        val obsLon: Double,
        val radiusM: Float,
        val missing: Int,
    ) {
        private var cached: Bitmap? = null
        private var cachedNight = false

        fun bitmapFor(night: Boolean): Bitmap {
            val c = cached
            if (c != null && cachedNight == night) return c
            val palette = if (night) NIGHT_PALETTE else DAY_PALETTE
            val px = IntArray(classes.size) { i ->
                // Hatching distinguishes uncertainty from known masking in both palettes.
                if (classes[i] == CLS_UNKNOWN && ((i % gridN + i / gridN) % 4 < 2)) 0
                else palette[classes[i]]
            }
            val bmp = Bitmap.createBitmap(px, gridN, gridN, Bitmap.Config.ARGB_8888)
            cached = bmp
            cachedNight = night
            return bmp
        }
    }

    /**
     * Radial-sweep viewshed from one observer. Same curvature/refraction model
     * as [lineOfSight] (observer-centric drop d²/2Re). Low-target threshold
     * 0.5 m AGL, standing/vehicle threshold 3 m AGL.
     */
    suspend fun viewshed(
        context: Context,
        obsLat: Double,
        obsLon: Double,
        observerHeight: Float,
        radiusM: Double,
        gridN: Int = 192,
        rays: Int = 768,
    ): Viewshed? = withContext(Dispatchers.Default) {
        val obsGround = Elevation.elevationAt(context, obsLat, obsLon)
            ?: return@withContext null
        if (!obsGround.isFinite()) return@withContext null
        val eye = obsGround + observerHeight
        val kx = cos(Math.toRadians(obsLat))
        val dLat = radiusM / 111319.49
        val dLon = radiusM / (111319.49 * kx)
        val latN = obsLat + dLat
        val latS = obsLat - dLat
        val lonW = obsLon - dLon
        val lonE = obsLon + dLon

        val classes = IntArray(gridN * gridN)
        val cell = 2.0 * radiusM / gridN
        val step = cell * 0.7
        val samples = ceil(radiusM / step).toInt()
        var missing = 0

        for (r in 0 until rays) {
            val az = 2.0 * Math.PI * r / rays
            val sinA = sin(az)
            val cosA = cos(az)
            val ray = TerrainSight.Ray(eye)
            var d = step
            for (s in 0 until samples) {
                if (d > radiusM) break
                val plat = obsLat + (d * cosA) / 111319.49
                val plon = obsLon + (d * sinA) / (111319.49 * kx)
                val px = (((plon - lonW) / (lonE - lonW)) * gridN).toInt()
                val py = (((latN - plat) / (latN - latS)) * gridN).toInt()
                val e = Elevation.elevationAt(context, plat, plon)
                if (e == null || !e.isFinite()) missing++
                val cls = ray.sample(d, e)
                if (px in 0 until gridN && py in 0 until gridN) {
                    val index = py * gridN + px
                    classes[index] = TerrainSight.mergeClasses(classes[index], cls)
                }
                d += step
            }
        }
        // observer's own cell reads visible
        classes[(gridN / 2) * gridN + gridN / 2] = CLS_VISIBLE

        Viewshed(
            classes = classes,
            gridN = gridN,
            latN = latN, latS = latS, lonW = lonW, lonE = lonE,
            obsLat = obsLat, obsLon = obsLon,
            radiusM = radiusM.toFloat(),
            missing = missing,
        )
    }

    /**
     * Line of sight observer -> target. Heights are metres above ground.
     * Returns null when elevation data is missing at either endpoint.
     */
    suspend fun lineOfSight(
        context: Context,
        obsLat: Double, obsLon: Double, observerHeight: Float,
        tgtLat: Double, tgtLon: Double, targetHeight: Float,
    ): LosResult? {
        val prof = profile(
            context,
            listOf(GeoVertex(obsLat, obsLon), GeoVertex(tgtLat, tgtLon)),
            stepM = 20.0,
            maxSamples = 500,
        ) ?: return null
        val analysis = TerrainSight.lineOfSight(
            prof.distancesM, prof.elevations, observerHeight, targetHeight,
        ) ?: return null
        val obsGround = prof.elevations.first()
        val tgtGround = prof.elevations.last()
        val blockIdx = analysis.blockIndex
        val blockT = if (blockIdx >= 0) prof.distancesM[blockIdx] / prof.totalM else 0f
        return LosResult(
            status = analysis.status,
            profile = prof,
            observerElev = obsGround,
            targetElev = tgtGround,
            observerHeight = observerHeight,
            targetHeight = targetHeight,
            blockIndex = blockIdx,
            blockDistM = if (blockIdx >= 0) prof.distancesM[blockIdx] else 0f,
            blockLat = obsLat + (tgtLat - obsLat) * blockT,
            blockLon = obsLon + (tgtLon - obsLon) * blockT,
            clearObserverHeight = analysis.clearObserverHeight,
            minClearanceM = analysis.minClearanceM,
            minClearanceDistM = analysis.minClearanceDistM,
            sightLine = analysis.sightLine,
            effectiveTerrain = analysis.effectiveTerrain,
        )
    }
}
