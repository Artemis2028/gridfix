package app.gridfix.android.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FixData(
    val location: Location? = null,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val gpsEnabled: Boolean = true,
)

/**
 * Thin wrapper over the platform LocationManager.
 * Uses GPS as the primary provider with a network-location fallback,
 * and reports GNSS satellite counts.
 */
class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Android 14+ can convert the GPS ellipsoid height to height above mean sea
    // level (what the map's contours use). The geoid lookup does disk I/O the
    // first time, so it runs off the main thread and the fix is re-emitted when done.
    private val mslExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "msl-altitude").apply { isDaemon = true } }
    private var closed = false

    private fun addMslAltitude(loc: Location) {
        if (closed || Build.VERSION.SDK_INT < 34 || !loc.hasAltitude()) return
        mslExecutor.execute {
            if (MslConverter.add(appContext, loc)) {
                // Same fix, now with MSL fields: a copy so the state flow sees a new value
                _fix.update { cur -> if (cur.location === loc) cur.copy(location = Location(loc)) else cur }
            }
        }
    }

    private val _fix = MutableStateFlow(FixData())
    val fix: StateFlow<FixData> = _fix.asStateFlow()

    // One flag per provider rather than one for the tracker: start() has to be able to
    // pick up a provider that was switched on after the first call.
    private var gpsRequested = false
    private var networkRequested = false
    private var gnssRegistered = false

    // Full interface implementation (not a SAM lambda): on API 26–29 devices the
    // framework still calls the legacy callbacks, which have no platform defaults there.
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (closed) return
            _fix.update { current ->
                val old = current.location
                val accept = old == null ||
                    loc.provider == LocationManager.GPS_PROVIDER ||
                    old.provider != LocationManager.GPS_PROVIDER ||
                    loc.elapsedRealtimeNanos - old.elapsedRealtimeNanos > 10_000_000_000L
                if (accept) current.copy(location = loc) else current
            }
            addMslAltitude(loc)
        }

        @Deprecated("Legacy callback, required for API 26-29 devices")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                _fix.update { it.copy(gpsEnabled = true) }
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                _fix.update { it.copy(gpsEnabled = false) }
            }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            _fix.update { it.copy(satellitesUsed = used, satellitesVisible = status.satelliteCount) }
        }
    }

    /**
     * Begin (or resume) listening. Safe to call repeatedly: each provider is tracked
     * separately and only requested if it is not already running.
     *
     * The old version set one `started` flag on the first call and returned early ever
     * after, and only asked for GPS if the provider happened to be enabled at that
     * moment. So: open the app with Location off, read the message telling you to turn
     * it on, turn it on, come back - and GPS was never requested again for the life of
     * the process. If the network provider was up, the app quietly ran on Wi-Fi and cell
     * fixes, which in open desert is no fix at all. The listener's onProviderEnabled
     * could not rescue it either, being registered against the network provider only.
     *
     * GPS is now requested whether or not the provider reports enabled. That is allowed
     * from API 26: it simply delivers nothing while the provider is off and starts
     * delivering when it comes on, which removes the "was it on at launch" question
     * rather than answering it.
     *
     * Each provider is still requested independently, because an "Approximate"
     * (coarse-only) grant makes the GPS request throw and that must not also cost us
     * the network provider and the GNSS status callback.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (closed) return
        val looper = Looper.getMainLooper()
        if (!gpsRequested) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, listener, looper
                )
                gpsRequested = true
            }
        }
        if (!networkRequested) {
            runCatching {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, looper
                    )
                    networkRequested = true
                }
            }
        }
        if (!gnssRegistered) {
            runCatching {
                locationManager.registerGnssStatusCallback(gnssCallback, Handler(looper))
                gnssRegistered = true
            }
        }
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        runCatching {
            val now = SystemClock.elapsedRealtimeNanos()
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { NavigationFixPolicy.isFresh(it.elapsedRealtimeNanos, now) }
                .maxByOrNull { it.elapsedRealtimeNanos }
            _fix.update {
                val current = it.location
                val seed = last?.takeIf { current == null || it.elapsedRealtimeNanos > current.elapsedRealtimeNanos }
                it.copy(location = seed ?: current, gpsEnabled = gpsEnabled)
            }
            last?.let { addMslAltitude(it) }
        }
    }

    fun stop() {
        if (!gpsRequested && !networkRequested && !gnssRegistered) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        if (gnssRegistered) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssCallback)
            } catch (_: SecurityException) {
            }
        }
        gpsRequested = false
        networkRequested = false
        gnssRegistered = false
    }

    /** Release the altitude worker when an owner is destroyed rather than merely stopped. */
    fun close() {
        closed = true
        stop()
        mslExecutor.shutdownNow()
    }
}

/** Kept in its own class so devices below Android 14 never load the converter type. */
@androidx.annotation.RequiresApi(34)
internal object MslConverter {
    private val converter = android.location.altitude.AltitudeConverter()

    /** True when [loc] now carries an MSL altitude. */
    @Synchronized
    fun add(context: Context, loc: Location): Boolean {
        if (loc.hasMslAltitude()) return true
        return runCatching { converter.addMslAltitudeToLocation(context, loc) }.isSuccess && loc.hasMslAltitude()
    }
}
