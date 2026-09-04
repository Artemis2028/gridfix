package app.gridfix.android.coords

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Coordinates {

    data class MgrsParts(
        val gzd: String,
        val square: String,
        val easting: String,
        val northing: String,
        val full: String,
    )

    /** Lat/lon (WGS84) to MGRS at the requested precision (4/6/8/10 digits). */
    fun mgrs(lat: Double, lon: Double, digits: Int): MgrsParts? = runCatching {
        val mgrs = MGRS.from(Point.point(lon, lat))
        val gridType = when (digits) {
            4 -> GridType.KILOMETER
            6 -> GridType.HUNDRED_METER
            8 -> GridType.TEN_METER
            else -> GridType.METER
        }
        // The library formats with the device locale; Arabic/Persian/Bengali phones
        // would get native digits, which nothing downstream can read. Force ASCII.
        val full = asciiDigits(mgrs.coordinate(gridType)).uppercase(Locale.US)
        val match = Regex("^([0-9]{1,2}[A-Z])([A-Z]{2})([0-9]*)$").find(full)
        if (match != null) {
            val (gzd, square, num) = match.destructured
            val half = num.length / 2
            val e = num.substring(0, half)
            val n = num.substring(half)
            // Canonical written form keeps the group breaks: "18T VP 3808 9755"
            val spaced = if (num.isEmpty()) "$gzd $square" else "$gzd $square $e $n"
            MgrsParts(gzd, square, e, n, spaced)
        } else {
            // Polar regions (no zone number) or unexpected shape: show as-is
            MgrsParts(full, "", "", "", full)
        }
    }.getOrNull()

    private fun asciiDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            val d = Character.digit(c, 10)
            append(if (d in 0..9) '0' + d else c)
        }
    }

    data class UtmCoord(val zone: Int, val hemisphere: Char, val easting: Long, val northing: Long)

    /** Lat/lon (WGS84) to standard UTM. Valid for lat -80..84. */
    fun utm(lat: Double, lon: Double): UtmCoord? {
        if (lat < -80.0 || lat > 84.0) return null

        val zone = utmZone(lat, lon)

        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2.0 - f)
        val ep2 = e2 / (1.0 - e2)

        val latRad = Math.toRadians(lat)
        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val lonRad = Math.toRadians(lon)

        val n = a / sqrt(1.0 - e2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ep2 * cos(latRad).pow(2)
        val bigA = cos(latRad) * (lonRad - lonOrigin)

        val m = a * (
            (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0) * latRad -
                (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(2.0 * latRad) +
                (15.0 * e2 * e2 / 256.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(4.0 * latRad) -
                (35.0 * e2 * e2 * e2 / 3072.0) * sin(6.0 * latRad)
            )

        val easting = k0 * n * (
            bigA + (1.0 - t + c) * bigA.pow(3) / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * bigA.pow(5) / 120.0
            ) + 500000.0

        var northing = k0 * (
            m + n * tan(latRad) * (
                bigA.pow(2) / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * bigA.pow(4) / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * bigA.pow(6) / 720.0
                )
            )
        if (lat < 0) northing += 10000000.0

        return UtmCoord(
            zone = zone,
            hemisphere = if (lat >= 0) 'N' else 'S',
            easting = easting.roundToLong(),
            northing = northing.roundToLong(),
        )
    }

    fun formatUtm(u: UtmCoord?): String =
        if (u == null) "—" else "${u.zone}${u.hemisphere} ${u.easting}E ${u.northing}N"

    /** Lat/lon in the chosen format: 0 = DD, 1 = DDM, 2 = DMS. */
    fun formatLatLon(lat: Double, lon: Double, format: Int): String {
        fun one(value: Double, positive: Char, negative: Char): String {
            val hemi = if (value >= 0) positive else negative
            val v = abs(value)
            return when (format) {
                0 -> String.format(Locale.US, "%.5f° %c", v, hemi)
                2 -> {
                    // work in tenths of arc-seconds so carries propagate cleanly
                    val tenths = (v * 36000.0).roundToLong()
                    val d = tenths / 36000
                    val mm = (tenths % 36000) / 600
                    val s = (tenths % 600) / 10.0
                    String.format(Locale.US, "%d° %02d' %04.1f\" %c", d, mm, s, hemi)
                }
                else -> {
                    // work in thousandths of minutes
                    val milli = (v * 60000.0).roundToLong()
                    val d = milli / 60000
                    val mFull = (milli % 60000) / 1000.0
                    String.format(Locale.US, "%d° %06.3f' %c", d, mFull, hemi)
                }
            }
        }
        return one(lat, 'N', 'S') + "   " + one(lon, 'E', 'W')
    }

    /** Military date-time group in Zulu, e.g. 241435Z AUG 26 */
    fun dtg(timeMillis: Long): String {
        val sdf = SimpleDateFormat("ddHHmm'Z' MMM yy", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMillis)).uppercase(Locale.US)
    }

    fun formatAltitude(meters: Double, units: Int): String = when (units) {
        1 -> "${(meters * 3.28084).roundToLong()} ft"
        else -> "${meters.roundToLong()} m"
    }

    fun formatAccuracy(meters: Float, units: Int): String = when (units) {
        1 -> "±${(meters * 3.28084).roundToLong()} ft"
        else -> "±${meters.roundToLong()} m"
    }

    fun formatSpeed(metersPerSecond: Float, units: Int): String = when (units) {
        1 -> String.format(Locale.US, "%.1f mph", metersPerSecond * 2.23694)
        2 -> String.format(Locale.US, "%.1f kn", metersPerSecond * 1.94384)
        else -> String.format(Locale.US, "%.1f km/h", metersPerSecond * 3.6)
    }

    private fun utmZone(lat: Double, lon: Double): Int {
        var zone = (floor((lon + 180.0) / 6.0) + 1.0).toInt().coerceIn(1, 60)
        // Norway exception
        if (lat in 56.0..64.0 && lon in 3.0..12.0) zone = 32
        // Svalbard exceptions
        if (lat in 72.0..84.0) {
            zone = when {
                lon in 0.0..9.0 -> 31
                lon in 9.0..21.0 -> 33
                lon in 21.0..33.0 -> 35
                lon in 33.0..42.0 -> 37
                else -> zone
            }
        }
        return zone
    }

    /** Grid convergence angle (degrees): grid north minus true north for this UTM zone. */
    fun gridConvergence(lat: Double, lon: Double): Double {
        val zone = utmZone(lat, lon)
        val lonOrigin = ((zone - 1) * 6 - 180 + 3).toDouble()
        return Math.toDegrees(
            atan(tan(Math.toRadians(lon - lonOrigin)) * sin(Math.toRadians(lat)))
        )
    }

    private fun gridConvergenceForZone(lat: Double, lon: Double, zone: Int): Double {
        val lonOrigin = ((zone - 1) * 6 - 180 + 3).toDouble()
        return Math.toDegrees(
            atan(tan(Math.toRadians(lon - lonOrigin)) * sin(Math.toRadians(lat)))
        )
    }

    /** Result of a two-ray fix: the intersection plus the range from each observer. */
    data class RayFix(val lat: Double, val lon: Double, val dist1: Double, val dist2: Double)

    /**
     * Intersection of two rays given in TRUE bearings, solved in the UTM plane of
     * the first point's zone (grid bearings via per-point convergence). Null when
     * the rays are near-parallel, diverge, or the fix lands beyond 100 km — the
     * cases a map-reading instructor would also reject.
     */
    fun rayIntersection(
        lat1: Double, lon1: Double, bearing1True: Double,
        lat2: Double, lon2: Double, bearing2True: Double,
    ): RayFix? {
        val zone = utmZone(lat1, lon1)
        val north = lat1 >= 0
        val p1 = utmForZone(lat1, lon1, zone, north)
        val p2 = utmForZone(lat2, lon2, zone, north)
        val g1 = Math.toRadians(bearing1True - gridConvergenceForZone(lat1, lon1, zone))
        val g2 = Math.toRadians(bearing2True - gridConvergenceForZone(lat2, lon2, zone))
        val d1x = sin(g1)
        val d1y = cos(g1)
        val d2x = sin(g2)
        val d2y = cos(g2)
        val cross = d1x * d2y - d1y * d2x
        if (abs(cross) < 1e-6) return null
        val dx = p2[0] - p1[0]
        val dy = p2[1] - p1[1]
        val t = (dx * d2y - dy * d2x) / cross
        val s = (dx * d1y - dy * d1x) / cross
        if (t <= 0.0 || s <= 0.0 || t > 100_000.0 || s > 100_000.0) return null
        val ll = utmInverse(p1[0] + t * d1x, p1[1] + t * d1y, zone, north)
        return RayFix(ll[0], ll[1], t, s)
    }

    data class NavInfo(val distanceMeters: Float, val bearingTrue: Float)

    /**
     * Geodesic distance and initial true bearing between two points.
     *
     * Was `android.location.Location.distanceBetween`. That is Vincenty on
     * WGS84 and so is [Geodesy], to well under a millimetre — GoldenTest holds
     * both to the same vectors. The reason for the swap is that the framework
     * call is stubbed out in JVM unit tests, so every distance the app shows
     * was untestable in CI; and the iOS port needs the identical numbers.
     */
    fun navInfo(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): NavInfo {
        val d = Geodesy.distanceAndBearing(fromLat, fromLon, toLat, toLon)
        return NavInfo(d[0].toFloat(), (((d[1] % 360.0) + 360.0) % 360.0).toFloat())
    }

    fun formatDistance(meters: Float, units: Int): String = when (units) {
        1 -> {
            val feet = meters * 3.28084f
            if (feet < 1000f) String.format(Locale.US, "%.0f ft", feet)
            else String.format(Locale.US, "%.2f mi", meters / 1609.344f)
        }
        2 -> {
            if (meters < 1852f) String.format(Locale.US, "%.0f m", meters)
            else String.format(Locale.US, "%.2f NM", meters / 1852f)
        }
        else -> {
            if (meters < 1000f) String.format(Locale.US, "%.0f m", meters)
            else if (meters < 10000f) String.format(Locale.US, "%.2f km", meters / 1000f)
            else String.format(Locale.US, "%.1f km", meters / 1000f)
        }
    }

    /** Format an angle in degrees (0..360) as degrees or NATO mils per the angle-unit setting. */
    fun formatAngle(degrees: Float, angleUnit: Int): String = when (angleUnit) {
        1 -> {
            val mils = Math.round(degrees * 6400.0 / 360.0)
            String.format(Locale.US, "%d mils", ((mils % 6400) + 6400) % 6400)
        }
        else -> {
            val deg = Math.round(degrees.toDouble())
            String.format(Locale.US, "%03d°", ((deg % 360) + 360) % 360)
        }
    }

    /** Parse an MGRS string (spaces allowed, case-insensitive) to lat/lon. Null if invalid. */
    fun parseMgrs(text: String): Pair<Double, Double>? = runCatching {
        val cleaned = asciiDigits(text.trim()).uppercase(Locale.US).replace(" ", "")
        if (cleaned.isEmpty()) return null
        val mgrs = MGRS.parse(cleaned)
        // The library lands on the SW corner of the designated cell. Aim at the
        // centre instead: a 6-digit grid means "somewhere in this 100 m square",
        // and re-formatting a corner point can fall into the neighbouring cell.
        val digits = cleaned.dropWhile { it.isDigit() }.drop(1).drop(2).count { it.isDigit() }
        val cell = if (digits == 0) 100000.0 else 10.0.pow(5 - digits / 2)
        val utm = mgrs.toUTM()
        val north = utm.hemisphere.toString().uppercase(Locale.US).startsWith("N")
        val ll = utmInverse(utm.easting + cell / 2.0, utm.northing + cell / 2.0, utm.zone, north)
        ll[0] to ll[1]
    }.getOrNull()

    /**
     * Parse an MGRS string to the SW corner of its cell (no centre offset).
     * Roadmap A (photo-map calibration): control points are grid LINE
     * intersections = cell corners. Never use [parseMgrs] here — it aims at
     * the cell centre and would bake a half-cell error into the homography.
     */
    fun parseMgrsCorner(text: String): Pair<Double, Double>? = runCatching {
        val cleaned = asciiDigits(text.trim()).uppercase(Locale.US).replace(" ", "")
        if (cleaned.isEmpty()) return null
        val mgrs = MGRS.parse(cleaned)
        val utm = mgrs.toUTM()
        val north = utm.hemisphere.toString().uppercase(Locale.US).startsWith("N")
        val ll = utmInverse(utm.easting, utm.northing, utm.zone, north)
        ll[0] to ll[1]
    }.getOrNull()

    /**
     * Scale a typed grid line number to 5 digits ("45" -> 45000).
     * Control-point entry takes 2-5 digits per roadmap A; null otherwise.
     * Pure — shared with iOS calibration screen verbatim.
     */
    fun scaleGridLineNumber(raw: String): Double? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 2..5 || digits.length != raw.trim().length) return null
        return (digits + "0".repeat(5 - digits.length)).toDoubleOrNull()
    }

    // ---- Grid-overlay math: forced-zone UTM forward and the Snyder-series inverse ----
    // Validated by roundtrip against the forward formulas above: < 1 mm error worldwide,
    // < 7 mm out to 4.5 degrees from the central meridian (grid lines never go further).

    /** UTM easting/northing of a point projected in a SPECIFIC zone (for grid drawing). */
    fun utmForZone(lat: Double, lon: Double, zone: Int, north: Boolean): DoubleArray {
        val latRad = Math.toRadians(lat)
        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val lonRad = Math.toRadians(lon)
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2.0 - f)
        val ep2 = e2 / (1.0 - e2)
        val n = a / sqrt(1.0 - e2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ep2 * cos(latRad).pow(2)
        val bigA = cos(latRad) * (lonRad - lonOrigin)
        val m = a * (
            (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0) * latRad -
                (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(2.0 * latRad) +
                (15.0 * e2 * e2 / 256.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(4.0 * latRad) -
                (35.0 * e2 * e2 * e2 / 3072.0) * sin(6.0 * latRad)
            )
        val easting = k0 * n * (
            bigA + (1.0 - t + c) * bigA.pow(3) / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * bigA.pow(5) / 120.0
            ) + 500000.0
        var northing = k0 * (
            m + n * tan(latRad) * (
                bigA.pow(2) / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * bigA.pow(4) / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * bigA.pow(6) / 720.0
                )
            )
        if (!north) northing += 10000000.0
        return doubleArrayOf(easting, northing)
    }

    /** UTM easting/northing back to lat/lon (WGS84). Returns [lat, lon]. */
    fun utmInverse(easting: Double, northing: Double, zone: Int, north: Boolean): DoubleArray {
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2.0 - f)
        val ep2 = e2 / (1.0 - e2)
        val x = easting - 500000.0
        val y = if (north) northing else northing - 10000000.0
        val m = y / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))
        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
        val phi1 = mu +
            (3.0 * e1 / 2.0 - 27.0 * e1 * e1 * e1 / 32.0) * sin(2.0 * mu) +
            (21.0 * e1 * e1 / 16.0 - 55.0 * e1.pow(4) / 32.0) * sin(4.0 * mu) +
            (151.0 * e1 * e1 * e1 / 96.0) * sin(6.0 * mu) +
            (1097.0 * e1.pow(4) / 512.0) * sin(8.0 * mu)
        val sin1 = sin(phi1)
        val cos1 = cos(phi1)
        val tan1 = tan(phi1)
        val c1 = ep2 * cos1 * cos1
        val t1 = tan1 * tan1
        val n1 = a / sqrt(1.0 - e2 * sin1 * sin1)
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sin1 * sin1).pow(1.5)
        val d = x / (n1 * k0)
        val lat = phi1 - (n1 * tan1 / r1) * (
            d * d / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ep2) * d.pow(4) / 24.0 +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ep2 - 3.0 * c1 * c1) * d.pow(6) / 720.0
            )
        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val lon = lonOrigin + (
            d -
                (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0 +
                (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ep2 + 24.0 * t1 * t1) * d.pow(5) / 120.0
            ) / cos1
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /** MGRS latitude band letter for a latitude in -80..84, e.g. 'R' for Dubai. */
    fun bandLetter(lat: Double): Char {
        val letters = "CDEFGHJKLMNPQRSTUVWX"
        val idx = floor((lat + 80.0) / 8.0).toInt().coerceIn(0, 19)
        return letters[idx]
    }
}
