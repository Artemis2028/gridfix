package app.gridfix.android.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import java.util.PriorityQueue

/**
 * Douglas-Peucker on a recorded track, for turning a few thousand fixes into a
 * route a person can follow back.
 *
 * A uniform stride ("keep every twentieth point") is the obvious approach and the
 * wrong one: it cuts the corner off every switchback and re-entrant, which is the
 * part of the track you actually need. This keeps the points that carry the shape
 * and drops the ones sitting on a straight.
 *
 * Distances are computed in metres on a local flat-earth approximation, which is
 * accurate well past the length of any track you would walk.
 */
fun simplifyTrack(points: List<GeoVertex>, toleranceM: Double): List<GeoVertex> {
    if (points.size <= 2 || toleranceM <= 0.0) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    // metres per degree at this latitude; longitude shrinks towards the poles
    val latRad = Math.toRadians(points[0].lat)
    val mPerLat = 111_320.0
    val mPerLon = 111_320.0 * cos(latRad).coerceAtLeast(0.01)

    fun perpendicular(p: GeoVertex, a: GeoVertex, b: GeoVertex): Double {
        val px = (p.lon - a.lon) * mPerLon
        val py = (p.lat - a.lat) * mPerLat
        val bx = (b.lon - a.lon) * mPerLon
        val by = (b.lat - a.lat) * mPerLat
        val len = hypot(bx, by)
        if (len < 1e-9) return hypot(px, py)
        return abs(px * by - py * bx) / len
    }

    // Iterative, so a long track cannot blow the stack.
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.size - 1)
    while (stack.isNotEmpty()) {
        val (first, last) = stack.removeLast()
        if (last <= first + 1) continue
        var worst = 0.0
        var index = -1
        for (i in first + 1 until last) {
            val d = perpendicular(points[i], points[first], points[last])
            if (d > worst) {
                worst = d
                index = i
            }
        }
        if (index >= 0 && worst > toleranceM) {
            keep[index] = true
            stack.addLast(first to index)
            stack.addLast(index to last)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * Simplify the entire track into a route with a fixed vertex budget. Both ends
 * survive, and the largest remaining deviation anywhere on the track is split
 * first. If the budget is exhausted, the requested tolerance cannot be promised.
 * Never truncate a tolerance-only simplification: that loses the destination.
 */
fun simplifyTrackToBudget(
    points: List<GeoVertex>,
    toleranceM: Double,
    maxPoints: Int,
): List<GeoVertex> {
    require(maxPoints >= 2) { "A route must have room for both endpoints" }
    require(toleranceM.isFinite() && toleranceM >= 0.0) { "Tolerance must be finite and non-negative" }
    if (points.size <= 2) return points

    val mPerLat = 111_320.0
    val mPerLon = mPerLat * cos(Math.toRadians(points[0].lat)).coerceAtLeast(0.01)
    fun longitudeDelta(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    // Distance to the segment, rather than the infinite line, also preserves
    // an out-and-back turn that extends beyond the route's final point.
    fun distance(p: GeoVertex, a: GeoVertex, b: GeoVertex): Double {
        val px = longitudeDelta(a.lon, p.lon) * mPerLon
        val py = (p.lat - a.lat) * mPerLat
        val bx = longitudeDelta(a.lon, b.lon) * mPerLon
        val by = (b.lat - a.lat) * mPerLat
        val lengthSquared = bx * bx + by * by
        if (lengthSquared < 1e-18) return hypot(px, py)
        val t = ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    data class Segment(val first: Int, val last: Int, val split: Int, val error: Double)
    val segments = PriorityQueue<Segment>(
        compareByDescending<Segment> { it.error }.thenBy { it.split }
    )
    fun enqueue(first: Int, last: Int) {
        var worst = toleranceM
        var split = -1
        for (i in first + 1 until last) {
            val error = distance(points[i], points[first], points[last])
            if (error > worst) {
                worst = error
                split = i
            }
        }
        if (split >= 0) segments.add(Segment(first, last, split, worst))
    }

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    var kept = 2
    enqueue(0, points.lastIndex)
    while (kept < maxPoints && segments.isNotEmpty()) {
        val segment = segments.remove()
        keep[segment.split] = true
        kept++
        enqueue(segment.first, segment.split)
        enqueue(segment.split, segment.last)
    }
    return points.filterIndexed { i, _ -> keep[i] }
}
