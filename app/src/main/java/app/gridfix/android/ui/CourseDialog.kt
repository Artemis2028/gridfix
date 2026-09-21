package app.gridfix.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.CourseResult
import app.gridfix.android.data.CourseState
import app.gridfix.android.data.FolderInfo
import app.gridfix.android.data.Waypoint
import kotlinx.coroutines.delay
import java.util.Locale

fun courseTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    } else {
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

/**
 * Practice land-nav course: set one up from an existing folder or have the app
 * scatter random points around you, then find them in order. Points count as
 * found inside 25 m; splits are timed automatically. Results keep a log so
 * improvement is visible — the app is a coach, not just a scorekeeper.
 */
@Composable
fun CourseDialog(
    active: CourseState?,
    waypoints: List<Waypoint>,
    folders: List<FolderInfo>,
    history: List<CourseResult>,
    hasFix: Boolean,
    onStartFolder: (String) -> Unit,
    onStartRandom: (count: Int, radiusM: Int) -> Unit,
    onAbandon: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (active != null) {
        CourseProgress(active, waypoints, onAbandon, onDismiss)
        return
    }

    var mode by remember { mutableIntStateOf(0) }   // 0 = random, 1 = folder
    var count by remember { mutableIntStateOf(5) }
    var radius by remember { mutableIntStateOf(1000) }
    var folder by remember { mutableStateOf("") }
    val eligibleFolders = remember(folders, waypoints) {
        folders.map { it.name }.filter { f -> waypoints.count { it.folder == f } >= 2 }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Practice course") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        label = { Text("Random points") },
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        label = { Text("From a folder") },
                        enabled = eligibleFolders.isNotEmpty(),
                    )
                }
                if (mode == 0) {
                    Text("Points", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 4, 5, 6, 8).forEach { n ->
                            FilterChip(
                                selected = count == n,
                                onClick = { count = n },
                                label = { Text("$n") },
                            )
                        }
                    }
                    Text("Radius around you", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500 to "500 m", 1000 to "1 km", 2000 to "2 km").forEach { (r, label) ->
                            FilterChip(
                                selected = radius == r,
                                onClick = { radius = r },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        if (hasFix) {
                            "Points are scattered around your position and saved to a course folder. Find them in order — inside 25 m counts."
                        } else {
                            "Waiting for GPS — random courses are scattered around your position."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Folder (visited in list order)", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        eligibleFolders.forEach { f ->
                            FilterChip(
                                selected = folder == f,
                                onClick = { folder = f },
                                label = { Text(f) },
                            )
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("Practice log", style = MaterialTheme.typography.labelLarge)
                    history.take(8).forEach { r ->
                        Text(
                            "${courseTime(r.totalMillis)}  ·  ${r.points} pts  ·  ${r.name}",
                            fontFamily = MonoFamily,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (mode == 0) hasFix else folder.isNotEmpty(),
                onClick = {
                    if (mode == 0) onStartRandom(count, radius) else onStartFolder(folder)
                },
            ) { Text("Start course") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CourseProgress(
    active: CourseState,
    waypoints: List<Waypoint>,
    onAbandon: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now by produceState(initialValue = System.currentTimeMillis(), active) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val target = waypoints.firstOrNull { it.id == active.waypointIds.getOrNull(active.nextIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (active.paused) "Course paused" else "Course"} — ${active.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (active.paused) {
                    Text(
                        active.pauseReason.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Scoring and navigation are paused. Restore the missing checkpoints from a backup, or end this course. Elapsed time continues.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "CP ${active.nextIndex + 1} of ${active.waypointIds.size}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                target?.let { t ->
                    Text(
                        "${t.name}\n${Coordinates.mgrs(t.lat, t.lon, 8)?.full ?: ""}",
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Elapsed ${courseTime(now - active.startedAt)}",
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (active.foundAt.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("Splits", style = MaterialTheme.typography.labelLarge)
                    var prev = active.startedAt
                    active.foundAt.forEachIndexed { i, t ->
                        Text(
                            "CP ${i + 1}  ${courseTime(t - prev)}",
                            fontFamily = MonoFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        prev = t
                    }
                }
                if (!active.paused) {
                    Text(
                        "Navigate is locked to the current point — it advances by itself when you get inside 25 m.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (active.paused) "Close" else "Continue") }
        },
        dismissButton = {
            TextButton(onClick = onAbandon) { Text("End course") }
        },
    )
}

/** Shown once when the last point is found. */
@Composable
fun CourseSummaryDialog(result: CourseResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Course complete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    courseTime(result.totalMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${result.points} points — ${result.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                result.splitsMillis.forEachIndexed { i, s ->
                    Text(
                        "CP ${i + 1}  ${courseTime(s)}",
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
