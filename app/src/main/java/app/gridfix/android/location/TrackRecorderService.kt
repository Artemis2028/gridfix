package app.gridfix.android.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gridfix.android.MainActivity
import app.gridfix.android.R
import app.gridfix.android.data.NO_ALTITUDE
import app.gridfix.android.data.TrackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Live state of the current recording, observed by the map UI. */
data class ActiveTrack(
    val trackId: String,
    val startedAt: Long,
    val points: List<Pair<Double, Double>>,
    val distanceM: Double,
)

/**
 * Foreground service that logs GPS points to the active track file while the
 * user moves — screen off included. Points are filtered to >= 5 m spacing.
 * Everything stays on-device; the notification is Android's required indicator
 * that location is in use.
 */
class TrackRecorderService : Service() {

    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private var worker: HandlerThread? = null
    private var trackId: String? = null
    private var lastPoint: Location? = null
    private var distance = 0.0
    private var points = ArrayList<Pair<Double, Double>>()
    private var startedAt = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val id = trackId ?: return
            // Reject junk fixes outright, but never let the fix's own error become the
            // step length: under canopy a 25 m CEP would demand a 25 m move before any
            // point is kept, and a whole dogleg inside that circle would simply not exist.
            // Spacing is a fixed 5 m, with a time fallback so a slow move still records.
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
            val last = lastPoint
            if (last != null) {
                val d = loc.distanceTo(last)
                val gap = loc.elapsedRealtimeNanos - last.elapsedRealtimeNanos
                val stale = gap > MIN_INTERVAL_NANOS
                if (d < MIN_STEP_M && !(stale && d > 1f)) return
                distance += d
            }
            lastPoint = loc
            points.add(loc.latitude to loc.longitude)
            if (points.size > MAX_LIVE_POINTS) {
                // keep the live polyline bounded; the file holds the full log
                points = ArrayList(points.takeLast(MAX_LIVE_POINTS / 2))
            }
            TrackRepository.appendPoint(
                this@TrackRecorderService, id,
                loc.latitude, loc.longitude, loc.time,
                // GPX <ele> is metres above mean sea level, and the map's contours are
                // MSL too, so store the converted height when the phone can give one.
                // NO_ALTITUDE when it cannot: writing 0.0 would plot the whole leg at
                // sea level, which is a worse lie than leaving the elevation out.
                loc.bestAltitude() ?: NO_ALTITUDE,
            )
            _active.value = ActiveTrack(id, startedAt, ArrayList(points), distance)
            updateNotification()
        }

        @Deprecated("Legacy callback, required for API 26-29 devices")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Sticky restart after the OS killed the process: the in-memory
            // recording is gone. Stop cleanly; the app closes out the track
            // from its point log on next launch (TrackRepository.finalizeOrphans).
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_TRACK_ID) ?: return START_NOT_STICKY
                if (trackId != null) return START_STICKY
                trackId = id
                startedAt = System.currentTimeMillis()
                distance = 0.0
                points = ArrayList()
                lastPoint = null
                _active.value = ActiveTrack(id, startedAt, emptyList(), 0.0)

                createChannel()
                val notif = buildNotification("Recording — 0 m")
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ServiceCompat.startForeground(
                            this, NOTIF_ID, notif,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                        )
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowed / SecurityException: the app lost
                    // foreground or location permission between tap and start. Bail out
                    // without a half-started recording.
                    trackId = null
                    _active.value = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                val thread = HandlerThread("track-recorder").also { it.start() }
                worker = thread
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 2000L, 0f, listener, thread.looper
                    )
                } catch (_: Exception) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun stopRecording() {
        if (trackId == null) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        worker?.quitSafely()
        worker = null
        trackId = null
        _active.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Track recording", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shown while MGRS GPS records your track" }
            )
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("MGRS GPS — recording track")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val text = if (distance < 1000) {
            String.format(Locale.US, "Recording — %.0f m", distance)
        } else {
            String.format(Locale.US, "Recording — %.2f km", distance / 1000.0)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Handler(Looper.getMainLooper()).post {
            runCatching { manager.notify(NOTIF_ID, buildNotification(text)) }
        }
    }

    companion object {
        private const val CHANNEL_ID = "gridfix_tracking"
        private const val NOTIF_ID = 41
        private const val ACTION_START = "app.gridfix.android.track.START"
        private const val ACTION_STOP = "app.gridfix.android.track.STOP"
        private const val EXTRA_TRACK_ID = "track_id"
        private const val MAX_LIVE_POINTS = 4000

        /** A fix worse than this is not worth logging at all. */
        private const val MAX_ACCURACY_M = 30f

        /** Minimum move between logged points. A step, not the fix's error circle. */
        private const val MIN_STEP_M = 5f

        /** ...but log anyway after this long, so a slow or stationary leg still exists. */
        private const val MIN_INTERVAL_NANOS = 15_000_000_000L

        private val _active = MutableStateFlow<ActiveTrack?>(null)
        val active: StateFlow<ActiveTrack?> = _active.asStateFlow()

        fun start(context: Context, trackId: String) {
            val i = Intent(context, TrackRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRACK_ID, trackId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackRecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
