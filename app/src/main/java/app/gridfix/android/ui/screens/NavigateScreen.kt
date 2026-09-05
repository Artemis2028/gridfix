package app.gridfix.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.Waypoint
import app.gridfix.android.location.CompassData
import app.gridfix.android.location.CompassTracker
import app.gridfix.android.location.FixData
import app.gridfix.android.location.ArrivalAlertState
import app.gridfix.android.location.NavigationFixPolicy
import app.gridfix.android.location.PocketGuideService
import app.gridfix.android.location.deviceVibrator
import app.gridfix.android.location.isUsableForNavigation
import app.gridfix.android.ui.WaypointMarker
import app.gridfix.android.ui.faces.DialNavigateFace
import app.gridfix.android.ui.faces.DialNavigateInstrument
import app.gridfix.android.ui.faces.DistanceHero
import app.gridfix.android.ui.faces.Face
import app.gridfix.android.ui.faces.FaceCells
import app.gridfix.android.ui.faces.GlanceArrow
import app.gridfix.android.ui.faces.GlanceNavigateFace
import app.gridfix.android.ui.faces.dialNavigateHint
import app.gridfix.android.ui.faces.facePalette
import app.gridfix.android.ui.faces.northRefLetter
import app.gridfix.android.ui.faces.steerText
import app.gridfix.android.ui.faces.toNorthRef
import app.gridfix.android.ui.isLandscape
import app.gridfix.android.ui.theme.LabelFamily
import app.gridfix.android.ui.theme.MonoFamily
import kotlin.math.abs
import app.gridfix.android.location.Declination

@Composable
fun NavigateScreen(
    fix: FixData,
    settings: AppSettings,
    waypoints: List<Waypoint>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val compass = remember { CompassTracker(context.applicationContext) }
    LifecycleStartEffect(Unit) {
        compass.start()
        onStopOrDispose { compass.stop() }
    }
    val compassData by compass.data.collectAsStateWithLifecycle()
    val landscape = isLandscape()

    // Expire a stopped provider's last fix even when no new location is emitted.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var freshnessTickNanos by remember { mutableStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                freshnessTickNanos = SystemClock.elapsedRealtimeNanos()
                kotlinx.coroutines.delay(1000L)
            }
        }
    }
    val nowNanos = maxOf(freshnessTickNanos, SystemClock.elapsedRealtimeNanos())
    val loc = fix.location?.takeIf { it.isUsableForNavigation(nowNanos) }
    val target = waypoints.firstOrNull { it.id == selectedId } ?: waypoints.firstOrNull()

    // Magnetic declination: the manual G-M angle if one is set, else the phone's
    // World Magnetic Model, refreshed when we move ~10 km
    val declination = remember(
        loc?.latitude?.let { (it * 10).toInt() },
        loc?.longitude?.let { (it * 10).toInt() },
        settings.declinationOverride,
    ) { Declination.at(settings, loc) }
    val convergence = if (loc == null) 0f else {
        Coordinates.gridConvergence(loc.latitude, loc.longitude).toFloat()
    }

    // Heading: compass sensor preferred, GPS course as fallback while moving
    val headingTrue: Float? = when {
        loc != null && compassData.hasSensor && compassData.hasReading &&
            compassData.accuracy > SensorManager.SENSOR_STATUS_ACCURACY_LOW &&
            NavigationFixPolicy.isFresh(compassData.timestampNanos, nowNanos, NavigationFixPolicy.MAX_HEADING_AGE_NANOS) ->
            (compassData.azimuthMagnetic + declination + 360f) % 360f
        !compassData.hasSensor && loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5f ->
            (loc.bearing + 360f) % 360f
        else -> null
    }

    fun toRef(angleTrue: Float): Float = toNorthRef(angleTrue, settings.northRef, declination, convergence)
    val refLetter = northRefLetter(settings.northRef)

    val nav = if (loc != null && target != null) {
        Coordinates.navInfo(loc.latitude, loc.longitude, target.lat, target.lon)
    } else null

    // The service owns pocket guidance, including arrival, while the screen is locked.
    val guideState by PocketGuideService.active.collectAsStateWithLifecycle()
    val guideError by PocketGuideService.error.collectAsStateWithLifecycle()
    val hapticGuide = guideState != null
    var notificationWarning by remember { mutableStateOf<String?>(null) }
    fun startGuide() {
        target?.let { PocketGuideService.start(context, it, settings.declinationOverride) }
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationWarning = if (granted) null else "Notifications are off. Return here to stop the pocket guide."
        startGuide()
    }
    val toggleGuide: () -> Unit = {
        if (hapticGuide) PocketGuideService.stop(context)
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startGuide()
    }
    // Explicit target/declination changes update the already running guide. Do not
    // stop it when this composable leaves the foreground: screen lock is supported.
    LaunchedEffect(target, settings.declinationOverride) {
        if (PocketGuideService.active.value != null) {
            if (target == null) PocketGuideService.stop(context) else startGuide()
        }
    }

    val arrival = remember { ArrivalAlertState() }
    LaunchedEffect(loc?.elapsedRealtimeNanos, target?.id, hapticGuide) {
        if (hapticGuide) return@LaunchedEffect
        val t = target ?: return@LaunchedEffect
        if (arrival.update(t.id, nav?.distanceMeters, loc?.accuracy)) {
            runCatching {
                deviceVibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            }
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }.getOrNull()
            try {
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                kotlinx.coroutines.delay(700)
            } finally {
                tone?.release()
            }
        }
    }

    val deviation: Float? = if (nav != null && headingTrue != null) {
        ((nav.bearingTrue - headingTrue + 540f) % 360f) - 180f
    } else null

    if (waypoints.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Flag,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("No waypoints yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Create your first waypoint in the Waypoints tab, then come back here to navigate to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val palette = facePalette(settings.nightMode)

    // Everything the faces show, in the user's north reference and units. The
    // reference letter rides in the cell labels so a mils value stays one number.
    val headingRef = headingTrue?.let { toRef(it) }
    val targetRef = nav?.let { toRef(it.bearingTrue) }
    val headingText = headingRef?.let { Coordinates.formatAngle(it, settings.angleUnit) + " " + refLetter } ?: "—"
    val distanceText = if (nav != null) Coordinates.formatDistance(nav.distanceMeters, settings.units) else "—"
    val speed = loc?.takeIf { it.hasSpeed() && it.speed > 0.4f }?.speed
    val etaText = when {
        nav != null && nav.distanceMeters < 50f -> "HERE"
        nav != null && speed != null -> {
            val secs = (nav.distanceMeters / speed).toInt()
            if (secs >= 3600) {
                String.format(java.util.Locale.US, "%d:%02d h", secs / 3600, (secs % 3600) / 60)
            } else {
                String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60)
            }
        }
        else -> "—"
    }
    val cells = listOf(
        "Azimuth · $refLetter" to (targetRef?.let { Coordinates.formatAngle(it, settings.angleUnit) } ?: "—"),
        "Back az · $refLetter" to (targetRef?.let { Coordinates.formatAngle((it + 180f) % 360f, settings.angleUnit) } ?: "—"),
        "Time to go" to etaText,
    )
    val relBearing = if (nav != null && headingTrue != null) (nav.bearingTrue - headingTrue + 360f) % 360f else null
    val steerLine = "HDG $headingText · ${steerText(deviation, settings.angleUnit)}"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Read here: the constraints scope is not reachable from inside the Column below
        val viewport = maxHeight
        // 16 dp of padding each side; the dial must fit inside it
        val dialSize = if (landscape) {
            (viewport - 40.dp).coerceIn(160.dp, 300.dp)
        } else {
            (maxWidth - 62.dp).coerceAtMost(if (settings.face == Face.LENSATIC) 360.dp else 330.dp)
        }
        val density = LocalDensity.current
        val faceDensity = Density(density.density, density.fontScale.coerceAtMost(1.15f))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (landscape) {
                // Instrument on the left, target / distance / cells on the right
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewport - 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalDensity provides faceDensity) {
                        Box(Modifier.size(dialSize), contentAlignment = Alignment.Center) {
                            when (settings.face) {
                                Face.GLANCE -> GlanceArrow(relBearing, palette, dialSize)
                                else -> DialNavigateInstrument(
                                    p = palette,
                                    style = settings.face,
                                    dialSize = dialSize,
                                    headingRef = headingRef,
                                    targetRef = targetRef,
                                    headingText = headingText,
                                    deviation = deviation,
                                    angleUnit = settings.angleUnit,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        TargetSelector(target, waypoints, settings, subtle, onSelect)
                        Spacer(Modifier.height(6.dp))
                        CompositionLocalProvider(LocalDensity provides faceDensity) {
                            DistanceHero(distanceText, palette, numeralSize = 56.sp, numeralLine = 60.sp, unitSize = 16.sp, display = settings.face == Face.GLANCE)
                            Spacer(Modifier.height(8.dp))
                            FaceCells(cells, palette)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (settings.face == Face.LENSATIC) dialNavigateHint(settings.face) else steerLine,
                            fontFamily = if (settings.face == Face.LENSATIC) LabelFamily else MonoFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            color = if (settings.face != Face.LENSATIC && deviation != null && abs(deviation) <= 3f) palette.lume else palette.muted,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = hapticGuide,
                                onClick = toggleGuide,
                                label = { Text(if (hapticGuide) "HAPTIC GUIDE ON" else "HAPTIC GUIDE") },
                            )
                        }
                    }
                }
                NavigateHints(loc, compassData, hapticGuide, listOfNotNull(guideState?.status, guideError, notificationWarning).joinToString("\n").ifEmpty { null }, subtle)
            } else {
                TargetSelector(target, waypoints, settings, subtle, onSelect, centered = true)

                Spacer(Modifier.height(12.dp))

                // ---- The face: arrow, lensatic dial, or clean card ----
                CompositionLocalProvider(LocalDensity provides faceDensity) {
                    when (settings.face) {
                        Face.GLANCE -> GlanceNavigateFace(
                            p = palette,
                            relBearing = relBearing,
                            distance = distanceText,
                            cells = cells,
                            headingLine = steerLine,
                        )
                        else -> DialNavigateFace(
                            p = palette,
                            style = settings.face,
                            dialSize = dialSize,
                            headingRef = headingRef,
                            targetRef = targetRef,
                            headingText = headingText,
                            deviation = deviation,
                            angleUnit = settings.angleUnit,
                            distance = distanceText,
                            cells = cells,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Eyes-free aid: haptic azimuth guide
                FilterChip(
                    selected = hapticGuide,
                    onClick = toggleGuide,
                    label = { Text(if (hapticGuide) "HAPTIC GUIDE ON" else "HAPTIC GUIDE") },
                )
                NavigateHints(loc, compassData, hapticGuide, listOfNotNull(guideState?.status, guideError, notificationWarning).joinToString("\n").ifEmpty { null }, subtle)
            }
        }
    }
}

/** The target picker: marker, name, drop-down of every navigable waypoint, and its grid underneath. */
@Composable
private fun TargetSelector(
    target: Waypoint?,
    waypoints: List<Waypoint>,
    settings: AppSettings,
    subtle: androidx.compose.ui.graphics.Color,
    onSelect: (String) -> Unit,
    centered: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Box {
            Row(
                modifier = Modifier.clickable { menuOpen = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WaypointMarker(
                    symbol = target?.symbol ?: "flag",
                    affiliation = target?.affiliation ?: "none",
                    size = 32.dp,
                    echelon = target?.echelon ?: "",
                    night = settings.nightMode,
                )
                Spacer(Modifier.width(8.dp))
                Text(target?.name ?: "Select target", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Change target", tint = subtle)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                waypoints.forEach { w ->
                    DropdownMenuItem(
                        text = { Text(w.name) },
                        leadingIcon = {
                            WaypointMarker(symbol = w.symbol, affiliation = w.affiliation, size = 26.dp, echelon = w.echelon, night = settings.nightMode)
                        },
                        onClick = {
                            onSelect(w.id)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        target?.let { t ->
            val parts = Coordinates.mgrs(t.lat, t.lon, 8)
            Text(
                parts?.full ?: "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = subtle,
            )
        }
    }
}

/** Below the face: what the haptic guide means, and why a heading or fix may be missing. */
@Composable
private fun NavigateHints(
    loc: android.location.Location?,
    compassData: CompassData,
    hapticGuide: Boolean,
    guideStatus: String?,
    subtle: androidx.compose.ui.graphics.Color,
) {
    if (hapticGuide) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Works with the screen locked. Keep the phone’s top edge pointing ahead. One short pulse = on bearing · two taps = RIGHT · long pulse = LEFT · three taps = unavailable · two long pulses = arrival. Silence does not confirm a bearing.",
            style = MaterialTheme.typography.bodySmall,
            color = subtle,
            textAlign = TextAlign.Center,
        )
    }
    if (guideStatus != null) {
        Spacer(Modifier.height(6.dp))
        Text(guideStatus, style = MaterialTheme.typography.bodySmall, color = subtle, textAlign = TextAlign.Center)
    }
    if (loc == null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Waiting for a fresh location accurate to 25 m or better…",
            style = MaterialTheme.typography.bodyMedium,
            color = subtle,
        )
    }
    if (!compassData.hasSensor) {
        Spacer(Modifier.height(12.dp))
        Text(
            "No compass sensor on this device — heading uses your direction of travel while moving.",
            style = MaterialTheme.typography.bodySmall,
            color = subtle,
            textAlign = TextAlign.Center,
        )
    } else if (compassData.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "Compass needs calibration — move your phone in a figure-8 a few times.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Center,
            )
        }
    } else if (!compassData.hasReading) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Waiting for the compass — the instrument is dimmed until it has a heading.",
            style = MaterialTheme.typography.bodySmall,
            color = subtle,
            textAlign = TextAlign.Center,
        )
    }
}
