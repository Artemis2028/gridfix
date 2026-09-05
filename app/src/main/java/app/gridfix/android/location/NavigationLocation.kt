package app.gridfix.android.location

import android.location.Location
import android.os.SystemClock

fun Location.isUsableForNavigation(
    nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    maxAccuracyM: Float = NavigationFixPolicy.MAX_ACCURACY_METERS,
): Boolean = NavigationFixPolicy.isUsable(
    latitude, longitude, if (hasAccuracy()) accuracy else null,
    elapsedRealtimeNanos, nowNanos, maxAccuracyM,
)
