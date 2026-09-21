package app.gridfix.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.TacGraphic
import app.gridfix.android.data.routeCardLegs
import java.util.Locale
import app.gridfix.android.location.Declination

/**
 * Route card for a route graphic: per-leg azimuth (in the chosen north
 * reference and angle unit), back-azimuth, distance, and pace count, with a
 * shareable plain-text version. The classic land-nav route card, computed
 * instead of penciled.
 */
@Composable
fun RouteCardDialog(
    route: TacGraphic,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val refLetter = when (settings.northRef) {
        1 -> "M"
        2 -> "G"
        else -> "T"
    }

    val legs = remember(route.id, route.points, settings.northRef, settings.angleUnit, settings.units,
        settings.pacePer100m, settings.declinationOverride) {
        routeCardLegs(route.points, settings) { lat, lon -> Declination.model(lat, lon) }
    }
    val totalMeters = legs.sumOf { it.distanceMeters.toDouble() }.toFloat()
    val totalPaces = legs.sumOf { it.paces }
    val startGrid = route.points.firstOrNull()?.let {
        Coordinates.mgrs(it.lat, it.lon, 8)?.full
    } ?: "—"

    fun shareText(): String {
        val sb = StringBuilder()
        sb.append("GRIDFIX ROUTE CARD — ").append(route.name.uppercase(Locale.US)).append('\n')
        sb.append("START ").append(startGrid).append('\n')
        for (l in legs) {
            sb.append(
                String.format(
                    Locale.US,
                    "LEG %d: %s %s / back %s — %s — %d paces → %s%n",
                    l.index, l.azimuth, refLetter, l.backAzimuth, l.distance, l.paces, l.toGrid,
                )
            )
        }
        sb.append(
            String.format(
                Locale.US,
                "TOTAL %s — %d paces%n",
                Coordinates.formatDistance(totalMeters, settings.units), totalPaces,
            )
        )
        sb.append("Azimuth from leg start; back-azimuth from leg end.\n")
        sb.append("(north ").append(refLetter)
            .append(" · pace ").append(settings.pacePer100m).append("/100m · ")
            .append(Coordinates.dtg(System.currentTimeMillis())).append(")")
        return sb.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route card — ${route.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "START  $startGrid",
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Elevation profile along the route, from the cached terrain data
                var profile by remember(route.id, route.points) {
                    androidx.compose.runtime.mutableStateOf<app.gridfix.android.map.Terrain.Profile?>(null)
                }
                androidx.compose.runtime.LaunchedEffect(route.id, route.points) {
                    profile = app.gridfix.android.map.Terrain.profile(context, route.points)
                }
                profile?.let { pr ->
                    val valid = pr.elevations.count { !it.isNaN() }
                    if (valid >= 2) {
                        val (gain, loss) = pr.gainLoss()
                        var lo = Float.MAX_VALUE
                        var hi = -Float.MAX_VALUE
                        for (e in pr.elevations) if (!e.isNaN()) {
                            if (e < lo) lo = e
                            if (e > hi) hi = e
                        }
                        Text(
                            String.format(
                                java.util.Locale.US,
                                "CLIMB ↑%.0f m  ↓%.0f m   ·   %.0f–%.0f m MSL",
                                gain, loss, lo, hi,
                            ),
                            fontFamily = MonoFamily,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ProfileChart(
                            distances = pr.distancesM,
                            elevations = pr.elevations,
                            legEnds = pr.legEndIndex,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                        )
                        if (pr.missing > pr.elevations.size / 10) {
                            Text(
                                "Profile has gaps — download elevation for this area to fill it in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                legs.forEach { l ->
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${l.index}",
                                fontFamily = MonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${l.azimuth} $refLetter   back ${l.backAzimuth}",
                                    fontFamily = MonoFamily,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${l.distance}   ${l.paces} paces   → ${l.toGrid}",
                                    fontFamily = MonoFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    "TOTAL  " + Coordinates.formatDistance(totalMeters, settings.units) +
                        "   $totalPaces paces",
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "North: $refLetter · pace ${settings.pacePer100m}/100 m\n" +
                        "Azimuth from leg start; back-azimuth from leg end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText())
                }
                runCatching {
                    context.startActivity(Intent.createChooser(send, "Share route card"))
                }
            }) { Text("Share") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    // Printable strip map: overview sketch + full leg table
                    val pdf = StripMapPdf.build(context, route, settings)
                    if (pdf != null) {
                        runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "app.gridfix.android.fileprovider",
                                pdf,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share strip map PDF"))
                        }
                    }
                }) { Text("PDF") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
