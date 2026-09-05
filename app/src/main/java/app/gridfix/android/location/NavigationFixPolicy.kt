package app.gridfix.android.location

/** Monotonic freshness and accuracy rules for decisions that can trigger an alert or score. */
object NavigationFixPolicy {
    const val MAX_AGE_NANOS = 10_000_000_000L
    const val MAX_ACCURACY_METERS = 25f
    const val MAX_HEADING_AGE_NANOS = 2_000_000_000L

    fun isFresh(timestampNanos: Long, nowNanos: Long, maxAgeNanos: Long = MAX_AGE_NANOS): Boolean =
        timestampNanos > 0L && nowNanos >= timestampNanos && nowNanos - timestampNanos <= maxAgeNanos

    fun isUsable(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        timestampNanos: Long,
        nowNanos: Long,
        maxAccuracyMeters: Float = MAX_ACCURACY_METERS,
    ): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyMeters != null && accuracyMeters.isFinite() &&
        accuracyMeters >= 0f && accuracyMeters <= maxAccuracyMeters &&
        isFresh(timestampNanos, nowNanos)

    /** Require the reported accuracy circle to fit inside the arrival radius. */
    fun insideArrivalRadius(distanceMeters: Float, accuracyMeters: Float): Boolean =
        distanceMeters.isFinite() && distanceMeters >= 0f &&
            accuracyMeters.isFinite() && accuracyMeters >= 0f &&
            distanceMeters + accuracyMeters < 50f
}

/** Hysteresis survives temporary loss of a usable fix and re-arms when the target changes. */
class ArrivalAlertState {
    private var target: String? = null
    private var alerted = false

    fun update(targetId: String, distanceMeters: Float?, accuracyMeters: Float?): Boolean {
        if (target != targetId) {
            target = targetId
            alerted = false
        }
        if (distanceMeters == null || !distanceMeters.isFinite() || distanceMeters < 0f ||
            accuracyMeters == null || !accuracyMeters.isFinite() ||
            accuracyMeters < 0f || accuracyMeters > NavigationFixPolicy.MAX_ACCURACY_METERS
        ) return false
        if (distanceMeters > 150f) alerted = false
        if (!alerted && NavigationFixPolicy.insideArrivalRadius(distanceMeters, accuracyMeters)) {
            alerted = true
            return true
        }
        return false
    }
}

enum class GuideCue { UNAVAILABLE, ON_BEARING, LEFT, RIGHT }

/** No stale/missing heading can produce the same cue as being on bearing. */
fun guideCue(deviationDegrees: Float?): GuideCue = when {
    deviationDegrees == null || !deviationDegrees.isFinite() -> GuideCue.UNAVAILABLE
    kotlin.math.abs(deviationDegrees) <= 8f -> GuideCue.ON_BEARING
    deviationDegrees > 0f -> GuideCue.RIGHT
    else -> GuideCue.LEFT
}
