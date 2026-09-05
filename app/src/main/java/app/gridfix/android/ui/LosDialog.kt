package app.gridfix.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.Waypoint
import app.gridfix.android.map.Terrain
import app.gridfix.android.map.SightStatus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Terrain profile chart shared by the LOS tool and the route card: filled
 * terrain, optional sight line, optional blocking-point marker, leg ticks.
 */
@Composable
fun ProfileChart(
    distances: FloatArray,
    elevations: FloatArray,
    modifier: Modifier = Modifier,
    sightLine: FloatArray? = null,
    blockIndex: Int = -1,
    legEnds: IntArray = intArrayOf(),
) {
    val primary = MaterialTheme.colorScheme.primary
    val fillColor = primary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val blockColor = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val n = distances.size
        if (n < 2) return@Canvas
        val total = distances.last().takeIf { it > 0f } ?: return@Canvas
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (e in elevations) if (!e.isNaN()) {
            if (e < lo) lo = e
            if (e > hi) hi = e
        }
        sightLine?.forEach { s ->
            if (s < lo) lo = s
            if (s > hi) hi = s
        }
        if (lo > hi) return@Canvas
        if (hi - lo < 10f) {
            val mid = (hi + lo) / 2f
            lo = mid - 5f
            hi = mid + 5f
        }
        val pad = (hi - lo) * 0.08f
        lo -= pad
        hi += pad
        fun x(i: Int) = distances[i] / total * size.width
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height

        // filled terrain (skip NaN gaps)
        var i = 0
        while (i < n) {
            while (i < n && elevations[i].isNaN()) i++
            if (i >= n) break
            val start = i
            val p = Path()
            p.moveTo(x(start), y(elevations[start]))
            var j = start + 1
            while (j < n && !elevations[j].isNaN()) {
                p.lineTo(x(j), y(elevations[j]))
                j++
            }
            val outline = Path().apply { addPath(p) }
            p.lineTo(x(j - 1), size.height)
            p.lineTo(x(start), size.height)
            p.close()
            drawPath(p, fillColor)
            drawPath(outline, primary, style = Stroke(width = 2.dp.toPx()))
            i = j
        }
        // leg boundaries
        for (le in legEnds) {
            if (le in 1 until n - 1) {
                drawLine(
                    gridColor,
                    Offset(x(le), 0f),
                    Offset(x(le), size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        // sight line + block marker
        sightLine?.let { s ->
            drawLine(
                primary,
                Offset(x(0), y(s.first())),
                Offset(x(n - 1), y(s.last())),
                strokeWidth = 2.dp.toPx(),
            )
            if (blockIndex in 0 until n && !elevations[blockIndex].isNaN()) {
                drawCircle(
                    blockColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x(blockIndex), y(elevations[blockIndex])),
                )
            }
        }
    }
}

/**
 * Line-of-sight check across the cached terrain: pick an observer and a
 * target, get VISIBLE, MASKED, or UNKNOWN with a profile sketch,
 * and the observer height that would clear the mask. Curvature and standard
 * refraction included.
 */
@Composable
fun LosDialog(
    settings: AppSettings,
    waypoints: List<Waypoint>,
    myPosition: Pair<Double, Double>?,
    crosshair: Pair<Double, Double>,
    onViewshed: (Terrain.Viewshed) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var obsMode by remember { mutableIntStateOf(if (myPosition != null) 0 else 1) }
    var tgtMode by remember { mutableIntStateOf(0) }
    var obsWp by remember { mutableStateOf<Waypoint?>(null) }
    var tgtWp by remember { mutableStateOf<Waypoint?>(null) }
    var obsMenu by remember { mutableStateOf(false) }
    var tgtMenu by remember { mutableStateOf(false) }
    var obsH by remember { mutableStateOf("2") }
    var tgtH by remember { mutableStateOf("2") }
    var computing by remember { mutableStateOf(false) }
    var radiusM by remember { mutableIntStateOf(2000) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Terrain.LosResult?>(null) }

    fun observerPoint(): Pair<Double, Double>? = when (obsMode) {
        0 -> myPosition
        1 -> crosshair
        else -> obsWp?.let { it.lat to it.lon }
    }

    fun targetPoint(): Pair<Double, Double>? = when (tgtMode) {
        0 -> crosshair
        else -> tgtWp?.let { it.lat to it.lon }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Line of sight") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Observer", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = obsMode == 0,
                        onClick = { obsMode = 0; result = null },
                        label = { Text("Me") },
                        enabled = myPosition != null,
                    )
                    FilterChip(
                        selected = obsMode == 1,
                        onClick = { obsMode = 1; result = null },
                        label = { Text("Crosshair") },
                    )
                    FilterChip(
                        selected = obsMode == 2,
                        onClick = { obsMode = 2; obsMenu = true; result = null },
                        label = { Text(obsWp?.name ?: "Waypoint…") },
                        enabled = waypoints.isNotEmpty(),
                    )
                }
                Box {
                    DropdownMenu(expanded = obsMenu, onDismissRequest = { obsMenu = false }) {
                        waypoints.take(40).forEach { w ->
                            DropdownMenuItem(
                                text = { Text(w.name) },
                                onClick = { obsWp = w; obsMenu = false; result = null },
                            )
                        }
                    }
                }

                Text("Target", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tgtMode == 0,
                        onClick = { tgtMode = 0; result = null },
                        label = { Text("Crosshair") },
                    )
                    FilterChip(
                        selected = tgtMode == 1,
                        onClick = { tgtMode = 1; tgtMenu = true; result = null },
                        label = { Text(tgtWp?.name ?: "Waypoint…") },
                        enabled = waypoints.isNotEmpty(),
                    )
                }
                Box {
                    DropdownMenu(expanded = tgtMenu, onDismissRequest = { tgtMenu = false }) {
                        waypoints.take(40).forEach { w ->
                            DropdownMenuItem(
                                text = { Text(w.name) },
                                onClick = { tgtWp = w; tgtMenu = false; result = null },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = obsH,
                        onValueChange = { obsH = it.filter { c -> c.isDigit() }.take(3); result = null },
                        label = { Text("Obs height m") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tgtH,
                        onValueChange = { tgtH = it.filter { c -> c.isDigit() }.take(3); result = null },
                        label = { Text("Tgt height m") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text("Shade radius (for the map overlay)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000 to "1 km", 2000 to "2 km", 4000 to "4 km").forEach { (r, label) ->
                        FilterChip(
                            selected = radiusM == r,
                            onClick = { radiusM = r },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Check = point-to-point answer here. Shade map = green seen, amber only a standing target, red masked, hatched unknown. Missing terrain prevents confirming visibility beyond it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                result?.let { r ->
                    val dist = Coordinates.formatDistance(r.profile.totalM, settings.units)
                    if (r.status == SightStatus.VISIBLE) {
                        // DEM cells are ~19 m and a few metres in error; a sight line that
                        // only just clears a crest deserves a warning, not a green light.
                        val marginal = !r.minClearanceM.isNaN() && r.minClearanceM < 5f
                        Text(
                            (if (marginal) "MARGINAL — " else "VISIBLE — ") + dist,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (marginal) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                        )
                        if (!r.minClearanceM.isNaN()) {
                            Text(
                                String.format(
                                    Locale.US,
                                    "Clears terrain by %.0f m at %s%s",
                                    r.minClearanceM,
                                    Coordinates.formatDistance(r.minClearanceDistM, settings.units),
                                    if (marginal) " — verify on the ground" else "",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFamily,
                            )
                        }
                    } else if (r.status == SightStatus.MASKED) {
                        Text(
                            "MASKED — $dist",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            String.format(
                                Locale.US,
                                "Known obstruction %s out (crest ≈ %.0f m MSL)\n%s",
                                Coordinates.formatDistance(r.blockDistM, settings.units),
                                r.profile.elevations.getOrElse(r.blockIndex) { Float.NaN },
                                Coordinates.mgrs(r.blockLat, r.blockLon, 8)?.full ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFamily,
                        )
                        if (r.clearObserverHeight in 0.5f..80f) {
                            Text(
                                String.format(
                                    Locale.US,
                                    "Would clear from ≈ %.0f m above observer ground",
                                    r.clearObserverHeight,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            "UNKNOWN — $dist",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Missing terrain could conceal an obstruction. Visibility cannot be confirmed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ProfileChart(
                        distances = r.profile.distancesM,
                        elevations = r.effectiveTerrain,
                        sightLine = r.sightLine,
                        blockIndex = r.blockIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "Obs %.0f m + %s m AGL → Tgt %.0f m + %s m AGL · curvature + refraction applied",
                            r.observerElev, r.observerHeight.toInt(), r.targetElev, r.targetHeight.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (r.profile.missing > 0) {
                        Text(
                            "${r.profile.missing} samples had no elevation data — download elevation for this area for a solid answer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (computing) {
                    Text(
                        "Sampling terrain…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !computing && observerPoint() != null && targetPoint() != null,
                onClick = {
                    val o = observerPoint() ?: return@TextButton
                    val t = targetPoint() ?: return@TextButton
                    computing = true
                    error = null
                    result = null
                    scope.launch {
                        val r = Terrain.lineOfSight(
                            context,
                            o.first, o.second, (obsH.toFloatOrNull() ?: 2f),
                            t.first, t.second, (tgtH.toFloatOrNull() ?: 2f),
                        )
                        computing = false
                        if (r == null) {
                            error = "No elevation data at the endpoints yet — view or download that terrain first."
                        } else {
                            result = r
                        }
                    }
                },
            ) { Text("Check") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !computing && observerPoint() != null,
                    onClick = {
                        val o = observerPoint() ?: return@TextButton
                        computing = true
                        error = null
                        scope.launch {
                            val vs = Terrain.viewshed(
                                context,
                                o.first, o.second,
                                (obsH.toFloatOrNull() ?: 2f),
                                radiusM.toDouble(),
                            )
                            computing = false
                            if (vs == null) {
                                error = "No elevation data at the observer yet — view or download that terrain first."
                            } else {
                                onViewshed(vs)
                                onDismiss()
                            }
                        }
                    },
                ) { Text("Shade map") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
