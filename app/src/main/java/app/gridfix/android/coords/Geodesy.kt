package app.gridfix.android.coords

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Distance and bearing on the WGS84 ellipsoid — Vincenty's inverse formula.
 *
 * This is the same algorithm `android.location.Location.distanceBetween` runs,
 * so replacing that call with this one moves no number the app displays: the
 * two agree to well under a millimetre, which [GoldenTest] proves against
 * vectors that also ship to the iOS port.
 *
 * Two reasons it is written out here rather than called from the framework:
 *
 *  - `Location.distanceBetween` is stubbed to a no-op in JVM unit tests, so
 *    every distance the app computes — course scoring, the arrival alert,
 *    route-card legs, pace counts, strip maps — was untestable in CI.
 *  - iOS needs the identical numbers. Two phones in one patrol reporting
 *    different ranges to the same point is the failure this prevents.
 *
 * A great-circle shortcut would be 0.3 to 0.5 % off — centimetres at the 25 m
 * course ring, but tens of metres on a long leg — so it is used only as the
 * fallback for the near-antipodal case that never converges, and which land
 * navigation never reaches.
 *
 * Pure Kotlin, no Android imports: this file moves to `:core` verbatim.
 */
object Geodesy {

    private const val A = 6378137.0
    private const val F = 1.0 / 298.257223563
    private const val B = A * (1.0 - F)
    private const val MEAN_RADIUS = 6371008.8

    /**
     * Geodesic distance in metres and initial bearing in degrees from true
     * north, returned as [distance, bearing]. The bearing is NOT normalised —
     * [Coordinates.navInfo] wraps it into 0..<360 on the way to the readout.
     */
    fun distanceAndBearing(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): DoubleArray {
        if (fromLat == toLat && fromLon == toLon) return doubleArrayOf(0.0, 0.0)

        val l = (toLon - fromLon) * PI / 180.0
        val u1 = atan((1.0 - F) * tan(fromLat * PI / 180.0))
        val u2 = atan((1.0 - F) * tan(toLat * PI / 180.0))
        val sinU1 = sin(u1); val cosU1 = cos(u1)
        val sinU2 = sin(u2); val cosU2 = cos(u2)

        var lambda = l
        var sinSigma = 0.0
        var cosSigma = 0.0
        var sigma = 0.0
        var cosSqAlpha = 0.0
        var cos2SigmaM = 0.0
        var converged = false

        for (iteration in 0 until 200) {
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)
            val p = cosU2 * sinLambda
            val q = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            sinSigma = sqrt(p * p + q * q)
            if (sinSigma == 0.0) return doubleArrayOf(0.0, 0.0)
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha
            cos2SigmaM =
                if (cosSqAlpha == 0.0) 0.0 else cosSigma - 2.0 * sinU1 * sinU2 / cosSqAlpha
            val c = F / 16.0 * cosSqAlpha * (4.0 + F * (4.0 - 3.0 * cosSqAlpha))
            val previous = lambda
            lambda = l + (1.0 - c) * F * sinAlpha *
                (sigma + c * sinSigma *
                    (cos2SigmaM + c * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)))
            if (abs(lambda - previous) < 1e-12) {
                converged = true
                break
            }
        }

        if (!converged) {
            return doubleArrayOf(
                greatCircleMeters(fromLat, fromLon, toLat, toLon),
                greatCircleBearing(fromLat, fromLon, toLat, toLon),
            )
        }

        val uSq = cosSqAlpha * (A * A - B * B) / (B * B)
        val bigA = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)))
        val bigB = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)))
        val deltaSigma = bigB * sinSigma * (
            cos2SigmaM + bigB / 4.0 * (
                cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM) -
                    bigB / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma) *
                    (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)
                )
            )
        val s = B * bigA * (sigma - deltaSigma)

        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val alpha1 = atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
        return doubleArrayOf(s, alpha1 * 180.0 / PI)
    }

    /**
     * Great-circle distance on a sphere of the WGS84 mean radius. Half a percent
     * off the ellipsoid, so it is only for the non-convergent fallback and for
     * cheap proximity sorting where that does not matter.
     */
    fun greatCircleMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dp = p2 - p1
        val dl = (lon2 - lon1) * PI / 180.0
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * MEAN_RADIUS * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    fun greatCircleBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dl = (lon2 - lon1) * PI / 180.0
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }
}
