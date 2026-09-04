package app.gridfix.android.map

import kotlin.math.ceil

/**
 * Portable grid geometry — no Canvas, no Projection, no osmdroid.
 *
 * Slice 3 of the MapLibre/iOS rebuild. [MgrsGridOverlay] (606 lines) currently
 * computes interval + sampling + drawing in one `draw()` pass. New code —
 * MapLibre line layers on Android + Swift — consumes this model instead:
 *
 *   bbox -> [chooseGridInterval] -> sample UTM lines via `Coordinates`
 *   -> List<GridLine> / List<GridLabel> -> engine renders.
 *
 * The overlay keeps shipping untouched; the cutover moves sampling here
 * function by function. KMP-ready: pure Kotlin, moves to `:core` verbatim.
 */

/** One grid polyline in lat/lon — engine projects it. */
data class GridLine(
    val points: List<Pair<Double, Double>>,
    val heavy: Boolean = false,
    /** GZD boundary vs 100 km square vs metre grid. */
    val kind: Kind = Kind.METRE,
) {
    enum class Kind { GZD, SQUARE, METRE }
}

/** Edge label anchored at a lat/lon. */
data class GridLabel(
    val text: String,
    val lat: Double,
    val lon: Double,
)

/** Interval chooser — mirrors MgrsGridOverlay.draw() spacing rule. */
fun chooseGridInterval(metersPerPixel: Double, density: Float): Int {
    if (metersPerPixel <= 0.0 || density <= 0f) return 0
    val minSpacing = 48f * density
    return intArrayOf(10, 100, 1000, 10000, 100000)
        .firstOrNull { it / metersPerPixel >= minSpacing } ?: 0
}

/**
 * Every multiple of [interval] lying in `[min, max]`, ascending.
 *
 * This exists because the overlay had no pure seam to test, and a real bug lived in
 * that gap for months. Both grid passes shared bounds floored to the *fine* interval,
 * then stepped from that unaligned start, rounded each value to the nearest multiple,
 * and terminated on the *unrounded* loop variable. At the 10 km level that rounded a
 * 100 km line in the far half of the view down to one off-screen and ended the loop
 * before reaching the real one, so about half of the positions where a 100 km line was
 * visible simply did not draw it. Starting on the first multiple at or after [min]
 * removes the rounding and the asymmetry at the same time.
 *
 * [guard] caps the result so a degenerate viewport cannot allocate without bound; the
 * overlay passes its own guard so the drawing cost stays exactly where it was.
 */
fun gridLineValues(min: Double, max: Double, interval: Int, guard: Int = 90): LongArray {
    if (interval <= 0 || guard <= 0) return LongArray(0)
    if (!min.isFinite() || !max.isFinite() || max < min) return LongArray(0)
    val firstIndex = ceil(min / interval)
    if (!firstIndex.isFinite() || firstIndex > Long.MAX_VALUE / interval.toDouble()) return LongArray(0)
    var value = firstIndex.toLong() * interval.toLong()
    val out = ArrayList<Long>()
    while (value <= max && out.size < guard) {
        out.add(value)
        value += interval.toLong()
    }
    return out.toLongArray()
}

fun gridIntervalLabel(interval: Int): String = when (interval) {
    0 -> "GZD"
    100000 -> "100 km"
    10000 -> "10 km"
    1000 -> "1 km"
    100 -> "100 m"
    else -> "10 m"
}
