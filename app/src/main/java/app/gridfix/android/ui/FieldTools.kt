package app.gridfix.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.coords.SunMoon
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.DEFAULT_FOLDER
import app.gridfix.android.data.Waypoint
import app.gridfix.android.data.WaypointDraft
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import app.gridfix.android.location.Declination

/** Chooser for the map's field-tools button. */
@Composable
fun FieldToolsChooser(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Field tools") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "resection" to "Resection — find yourself from two known points",
                    "intersection" to "Intersection — plot a target from two observers",
                    "sunmoon" to "Sun & moon — BMNT, EENT, rise/set, illumination",
                    "declination" to "Declination diagram — true / grid / magnetic",
                    "los" to "Line of sight — can I see that point from here?",
                    "course" to "Practice course — timed land-nav points, scored",
                ).forEach { (key, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private data class RayPoint(val name: String, val lat: Double, val lon: Double)

/**
 * Resection (two azimuths TO known points locate you) and intersection (two
 * observers' azimuths locate a target) share one solver and one dialog.
 * Azimuths are entered in the user's angle unit and north reference and
 * converted per point; resection applies the back-azimuth automatically.
 */
@Composable
fun RayFixDialog(
    resection: Boolean,
    settings: AppSettings,
    bases: List<Waypoint>,
    myPosition: Pair<Double, Double>?,
    crosshair: Pair<Double, Double>,
    onSaveWaypoint: (WaypointDraft) -> Unit,
    onShowOnMap: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<RayPoint> = buildList {
        if (!resection && myPosition != null) add(RayPoint("My position", myPosition.first, myPosition.second))
        add(RayPoint("Crosshair", crosshair.first, crosshair.second))
        bases.forEach { add(RayPoint(it.name, it.lat, it.lon)) }
    }
    var sel1 by remember { mutableStateOf(0) }
    var sel2 by remember { mutableStateOf(if (options.size > 1) 1 else 0) }
    var az1 by remember { mutableStateOf("") }
    var az2 by remember { mutableStateOf("") }

    val angleLabel = if (settings.angleUnit == 1) "mils" else "deg"
    val refLetter = when (settings.northRef) {
        1 -> "M"
        2 -> "G"
        else -> "T"
    }

    fun trueBearing(point: RayPoint, degrees: Double): Double {
        val adjusted = when (settings.northRef) {
            1 -> degrees + Declination.at(settings, point.lat, point.lon)
            2 -> degrees + Coordinates.gridConvergence(point.lat, point.lon)
            else -> degrees
        }
        val asTrue = ((adjusted % 360.0) + 360.0) % 360.0
        return if (resection) (asTrue + 180.0) % 360.0 else asTrue
    }

    val p1 = options.getOrNull(sel1)
    val p2 = options.getOrNull(sel2)
    val a1 = Coordinates.parseAzimuth(az1, settings.angleUnit)
    val a2 = Coordinates.parseAzimuth(az2, settings.angleUnit)
    val bearing1 = if (p1 != null && a1 != null) trueBearing(p1, a1).takeIf { it.isFinite() } else null
    val bearing2 = if (p2 != null && a2 != null) trueBearing(p2, a2).takeIf { it.isFinite() } else null
    val fix = if (p1 != null && p2 != null && bearing1 != null && bearing2 != null && sel1 != sel2) {
        Coordinates.rayIntersection(
            p1.lat, p1.lon, bearing1,
            p2.lat, p2.lon, bearing2,
        )
    } else null
    // Angle of cut between the two rays: a fix is only as good as its geometry.
    // Doctrine wants roughly 30 to 150 degrees; outside that, small compass
    // errors move the fix a long way.
    val cutAngle = if (bearing1 != null && bearing2 != null) {
        val d = kotlin.math.abs(bearing1 - bearing2) % 360.0
        if (d > 180.0) 360.0 - d else d
    } else null
    val azimuthError = if (settings.angleUnit == 1) "Enter 0 to 6400 mils" else "Enter 0 to 360 degrees"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (resection) "Resection" else "Intersection") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (resection) {
                        "Shoot an azimuth to each of two known points; MGRS GPS runs the back-azimuths and plots your position."
                    } else {
                        "From two observation points, enter the azimuth to the target; MGRS GPS plots where they cross."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RayInputRow(
                    label = if (resection) "Known point 1" else "Observer 1",
                    options = options,
                    selected = sel1,
                    onSelect = { sel1 = it },
                    az = az1,
                    onAz = { az1 = it },
                    angleLabel = angleLabel,
                    refLetter = refLetter,
                    error = azimuthError.takeIf { az1.isNotBlank() && a1 == null },
                )
                RayInputRow(
                    label = if (resection) "Known point 2" else "Observer 2",
                    options = options,
                    selected = sel2,
                    onSelect = { sel2 = it },
                    az = az2,
                    onAz = { az2 = it },
                    angleLabel = angleLabel,
                    refLetter = refLetter,
                    error = azimuthError.takeIf { az2.isNotBlank() && a2 == null },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (fix != null) {
                    // 8-digit: a compass fix is a 10 m answer at best, never a 1 m one
                    Text(
                        (Coordinates.mgrs(fix.lat, fix.lon, 8)?.full ?: "—"),
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (cutAngle != null && (cutAngle < 30.0 || cutAngle > 150.0)) {
                        Text(
                            String.format(
                                java.util.Locale.US,
                                "Poor angle of cut (%.0f°) — pick points that cross closer to 90°",
                                cutAngle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Ranges: " + Coordinates.formatDistance(fix.dist1.toFloat(), settings.units) +
                            " / " + Coordinates.formatDistance(fix.dist2.toFloat(), settings.units),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Enter both azimuths from two different points. If the rays don't cross ahead of both points (within 100 km), no fix is shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = fix != null,
                    onClick = {
                        fix?.let { onShowOnMap(it.lat, it.lon) }
                        onDismiss()
                    },
                ) { Text("Show") }
                TextButton(
                    enabled = fix != null,
                    onClick = {
                        fix?.let {
                            onSaveWaypoint(
                                WaypointDraft(
                                    name = (if (resection) "RESECTION " else "TGT ") +
                                        Coordinates.dtg(System.currentTimeMillis()).take(7),
                                    lat = it.lat,
                                    lon = it.lon,
                                    folder = DEFAULT_FOLDER,
                                    symbol = "target",
                                    affiliation = if (resection) "none" else "hostile",
                                )
                            )
                        }
                        onDismiss()
                    },
                ) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RayInputRow(
    label: String,
    options: List<RayPoint>,
    selected: Int,
    onSelect: (Int) -> Unit,
    az: String,
    onAz: (String) -> Unit,
    angleLabel: String,
    refLetter: String,
    error: String?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.clickable { menuOpen = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrNull(selected)?.name ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = "Choose point",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = { Text(o.name) },
                        onClick = {
                            onSelect(i)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = az,
            onValueChange = onAz,
            label = { Text("Azimuth ($angleLabel $refLetter)") },
            isError = error != null,
            supportingText = if (error != null) ({ Text(error) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** Sun & moon planning table for the map-center location. */
@Composable
fun SunMoonDialog(lat: Double, lon: Double, onDismiss: () -> Unit) {
    var dayOffset by remember { mutableStateOf(0) }
    val cal = remember(dayOffset) {
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
    }
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val sun = remember(y, m, d, lat, lon) { SunMoon.sunTimes(y, m, d, lat, lon) }
    val moon = remember(y, m, d, lat, lon) { SunMoon.moonInfo(y, m, d, lat, lon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(String.format(Locale.US, "Sun & moon — %02d %s %d", d, MONTHS[m - 1], y))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { dayOffset-- }) { Text("◀ Prev") }
                    TextButton(onClick = { dayOffset = 0 }) { Text("Today") }
                    TextButton(onClick = { dayOffset++ }) { Text("Next ▶") }
                }
                Text(
                    "            LOCAL   ZULU",
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    "BMNT      " to sun.bmnt,
                    "Civil dawn" to sun.civilDawn,
                    "Sunrise   " to sun.sunrise,
                    "Sunset    " to sun.sunset,
                    "Civil dusk" to sun.civilDusk,
                    "EENT      " to sun.eent,
                ).forEach { (name, t) ->
                    Text(
                        "$name  ${SunMoon.formatLocal(t, y, m, d)}   ${SunMoon.formatZulu(t)}",
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    "${moon.phaseName} — ${moon.illuminationPct}% illumination",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Moonrise  " + (moon.rises.firstOrNull()?.let {
                        "${SunMoon.formatLocal(it, y, m, d)}   ${SunMoon.formatZulu(it)}"
                    } ?: "----"),
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Moonset   " + (moon.sets.firstOrNull()?.let {
                        "${SunMoon.formatLocal(it, y, m, d)}   ${SunMoon.formatZulu(it)}"
                    } ?: "----"),
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "For the map-center location. Moon times ±5 min.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private val MONTHS = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

/** The map-margin declination diagram, computed for the map center. */
@Composable
fun DeclinationDialog(lat: Double, lon: Double, override: Float? = null, onDismiss: () -> Unit) {
    val model = remember(lat, lon) { Declination.model(lat, lon) }
    val decl = override ?: model
    val conv = remember(lat, lon) { Coordinates.gridConvergence(lat, lon).toFloat() }
    val gm = decl - conv

    fun ew(v: Float): String = String.format(
        Locale.US, "%.1f° %s", abs(v), if (v >= 0) "E" else "W"
    )

    val lineColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Declination — map center") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                ) {
                    val ox = size.width / 2f
                    val oy = size.height - 8.dp.toPx()
                    val len = size.height - 24.dp.toPx()
                    val stroke = 2.dp.toPx()

                    fun arm(angleDeg: Float): Offset {
                        val a = Math.toRadians(angleDeg.toDouble() - 90.0)
                        return Offset(
                            ox + (len * cos(a)).toFloat(),
                            oy + (len * sin(a)).toFloat(),
                        )
                    }
                    // exaggerate small angles so the diagram reads; labels carry truth
                    fun display(v: Float): Float =
                        if (v == 0f) 0f else (if (v > 0) 1 else -1) * (8f + min(24f, abs(v) * 2f))

                    // True north: straight up, star at tip
                    val tn = arm(0f)
                    drawLine(lineColor, Offset(ox, oy), tn, stroke)
                    for (k in 0 until 5) {
                        val a = Math.toRadians(k * 72.0 - 90.0)
                        drawLine(
                            lineColor,
                            tn,
                            Offset(
                                tn.x + (7.dp.toPx() * cos(a)).toFloat(),
                                tn.y + (7.dp.toPx() * sin(a)).toFloat(),
                            ),
                            stroke * 0.7f,
                        )
                    }
                    // Grid north
                    val gn = arm(display(conv))
                    drawLine(lineColor, Offset(ox, oy), gn, stroke)
                    // Magnetic north: half arrowhead
                    val mn = arm(display(decl))
                    drawLine(accent, Offset(ox, oy), mn, stroke)
                    val maDir = Math.toRadians(display(decl).toDouble() - 90.0)
                    drawLine(
                        accent,
                        mn,
                        Offset(
                            mn.x + (10.dp.toPx() * cos(maDir + 2.6)).toFloat(),
                            mn.y + (10.dp.toPx() * sin(maDir + 2.6)).toFloat(),
                        ),
                        stroke,
                    )
                    drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(ox, oy), style = Stroke(stroke))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("★ TN", style = MaterialTheme.typography.labelMedium)
                    Text("GN", style = MaterialTheme.typography.labelMedium)
                    Text("MN", style = MaterialTheme.typography.labelMedium, color = accent)
                }
                Text(
                    "Magnetic declination  ${ew(decl)}\n" +
                        "Grid convergence      ${ew(conv)}\n" +
                        "G-M angle             " + String.format(Locale.US, "%.1f°", abs(gm)),
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (override != null) {
                        "Declination is set by hand in Settings (the phone's model says ${ew(model)} here). Diagram not to scale — use the printed values."
                    } else {
                        "Diagram not to scale — use the printed values. World Magnetic Model via Android; a map-sheet G-M angle can be pinned in Settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(0.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
