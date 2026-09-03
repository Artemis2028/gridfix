package app.gridfix.android.map

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

fun gridIntervalLabel(interval: Int): String = when (interval) {
    0 -> "GZD"
    100000 -> "100 km"
    10000 -> "10 km"
    1000 -> "1 km"
    100 -> "100 m"
    else -> "10 m"
}
