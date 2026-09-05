package app.gridfix.android.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gridfix.android.MainActivity
import app.gridfix.android.R
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PocketGuideState(val targetId: String, val targetName: String, val status: String)

/**
 * Owns live location, compass and haptics independently of the screen lifecycle.
 * Started only by a visible user action; no sticky restart with an obsolete target.
 */
class PocketGuideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var location: LocationTracker
    private lateinit var compass: CompassTracker
    private var wakeLock: PowerManager.WakeLock? = null
    private var target: Target? = null
    private var running = false
    private val arrival = ArrivalAlertState()
    private val vibrator by lazy { deviceVibrator(this) }

    private data class Target(val id: String, val name: String, val lat: Double, val lon: Double, val declination: Float?)

    override fun onCreate() {
        super.onCreate()
        location = LocationTracker(this)
        compass = CompassTracker(this, followDisplayRotation = false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pocket navigation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Live navigation with the screen locked; tap Stop to end" },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finishGuidance()
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent.getStringExtra("target_id")
        val lat = intent.getDoubleExtra("latitude", Double.NaN)
        val lon = intent.getDoubleExtra("longitude", Double.NaN)
        if (intent.action != ACTION_START || id.isNullOrBlank() || !lat.isFinite() || lat !in -90.0..90.0 ||
            !lon.isFinite() || lon !in -180.0..180.0
        ) {
            finishGuidance()
            stopSelf()
            return START_NOT_STICKY
        }
        target = Target(
            id, intent.getStringExtra("name") ?: "Waypoint", lat, lon,
            intent.getFloatExtra("declination", Float.NaN).takeIf { it.isFinite() },
        )
        val initial = PocketGuideState(id, target!!.name, "Waiting for a fresh location and heading")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, notification(initial), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification(initial))
            }
            if (vibrator?.hasVibrator() != true) error("No vibrator on this device")
            if (!running) {
                // Rotation-vector sensors are commonly non-wake-up sensors. A location
                // FGS alone does not keep the CPU/sensors and timed haptics running.
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gridfix:pocket-guide")
                    .apply { setReferenceCounted(false); acquire(60_000L) }
                location.start()
                compass.start()
                running = true
                scope.launch {
                    try {
                        guideLoop()
                    } catch (_: Exception) {
                        // Provider, sensor or wake-lock failures must stop guidance,
                        // not leave the notification and last direction running.
                    } finally {
                        if (running) {
                            _error.value = "Pocket guide stopped. Reopen navigation to start it again."
                            finishGuidance()
                            stopSelf()
                        }
                    }
                }
            }
            _error.value = null
            _active.value = initial
        } catch (_: Exception) {
            _error.value = "Pocket guide could not start. Check location permission and try again while the app is open."
            finishGuidance()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun guideLoop() {
        var nextCueAt = 0L
        var lastCue: GuideCue? = null
        var lastTarget: String? = null
        var lastRenewal = SystemClock.elapsedRealtime()
        while (scope.isActive && running) {
            val t = target ?: break
            val nowMs = SystemClock.elapsedRealtime()
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (nowMs - lastRenewal >= 30_000L) {
                // Bounded acquisition releases itself if this loop unexpectedly stalls.
                wakeLock?.acquire(60_000L)
                lastRenewal = nowMs
            }
            val loc = location.fix.value.location?.takeIf { it.isUsableForNavigation(nowNanos) }
            val sensor = compass.data.value
            val heading = when {
                loc == null -> null
                sensor.hasReading && sensor.accuracy > SensorManager.SENSOR_STATUS_ACCURACY_LOW &&
                    NavigationFixPolicy.isFresh(sensor.timestampNanos, nowNanos, NavigationFixPolicy.MAX_HEADING_AGE_NANOS) -> {
                    val dec = t.declination ?: Declination.model(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0)
                    (sensor.azimuthMagnetic + dec + 360f) % 360f
                }
                // A missing compass may use direction of travel, never its last heading.
                !sensor.hasSensor && loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5f -> loc.bearing
                else -> null
            }
            val nav = loc?.let { Coordinates.navInfo(it.latitude, it.longitude, t.lat, t.lon) }
            val deviation = if (nav != null && heading != null) ((nav.bearingTrue - heading + 540f) % 360f) - 180f else null
            val cue = guideCue(deviation)
            val arrived = arrival.update(t.id, nav?.distanceMeters, loc?.accuracy)
            val status = when {
                loc == null -> "Unavailable: waiting for an accurate, fresh location"
                heading == null -> "Unavailable: waiting for a reliable, fresh heading"
                cue == GuideCue.ON_BEARING -> "On bearing · one short pulse"
                cue == GuideCue.RIGHT -> "Turn right · two short taps"
                else -> "Turn left · one long pulse"
            }
            val state = PocketGuideState(t.id, t.name, status)
            if (state != _active.value) {
                _active.value = state
                runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(state)) }
            }
            if (cue != lastCue || lastTarget != t.id) {
                vibrator?.cancel()
                nextCueAt = 0L
                lastCue = cue
                lastTarget = t.id
            }
            if (arrived) {
                vibrate(longArrayOf(0, 400, 200, 400))
                nextCueAt = nowMs + 1800L
            } else if (nowMs >= nextCueAt) {
                vibrate(when (cue) {
                    GuideCue.UNAVAILABLE -> longArrayOf(0, 120, 120, 120, 120, 120)
                    GuideCue.ON_BEARING -> longArrayOf(0, 50)
                    GuideCue.RIGHT -> longArrayOf(0, 70, 90, 70)
                    GuideCue.LEFT -> longArrayOf(0, 300)
                })
                nextCueAt = nowMs + when {
                    cue == GuideCue.UNAVAILABLE || cue == GuideCue.ON_BEARING -> 4000L
                    abs(deviation ?: 0f) > 60f -> 800L
                    abs(deviation ?: 0f) > 25f -> 1300L
                    else -> 2000L
                }
            }
            delay(250L)
        }
    }

    private fun vibrate(pattern: LongArray) {
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun notification(state: PocketGuideState): Notification {
        val open = PendingIntent.getActivity(this, NOTIF_ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTIF_ID, Intent(this, PocketGuideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Pocket guide · ${state.targetName}")
            .setContentText(state.status)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop guide", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun finishGuidance() {
        running = false
        target = null
        scope.cancel()
        runCatching { location.stop() }
        runCatching { compass.stop() }
        runCatching { vibrator?.cancel() }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        _active.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        finishGuidance()
        location.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        finishGuidance()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL_ID = "gridfix_pocket_guide"
        private const val NOTIF_ID = 42
        private const val ACTION_START = "app.gridfix.android.guide.START"
        private const val ACTION_STOP = "app.gridfix.android.guide.STOP"
        private val _active = MutableStateFlow<PocketGuideState?>(null)
        val active: StateFlow<PocketGuideState?> = _active.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        /** Called from a visible screen after its permission flow, never from background work. */
        fun start(context: Context, waypoint: Waypoint, declinationOverride: Float?) {
            _error.value = null
            val intent = Intent(context, PocketGuideService::class.java).setAction(ACTION_START)
                .putExtra("target_id", waypoint.id).putExtra("name", waypoint.name)
                .putExtra("latitude", waypoint.lat).putExtra("longitude", waypoint.lon)
                .putExtra("declination", declinationOverride ?: Float.NaN)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                _error.value = "Pocket guide could not start. Open the app, check location permission, and try again."
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketGuideService::class.java))
        }
    }
}

internal fun deviceVibrator(context: Context): Vibrator? = runCatching {
    if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}.getOrNull()
