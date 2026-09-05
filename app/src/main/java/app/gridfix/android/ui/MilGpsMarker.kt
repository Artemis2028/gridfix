package app.gridfix.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridfix.android.data.MilGpsShape
import app.gridfix.android.data.MilGpsSymbol
import app.gridfix.android.data.MilGpsSymbols
import app.gridfix.android.data.WaypointMetadata
import app.gridfix.android.ui.theme.MonoFamily
import java.time.Instant
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun MilGpsMarker(symbol: MilGpsSymbol, color: Color, modifier: Modifier, size: Dp) {
    Box(modifier.size(size).semantics {
        contentDescription = "${symbol.shape.label} ${symbol.character.orEmpty()} marker"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val path = Path()
            when (symbol.shape) {
                MilGpsShape.CIRCLE -> path.addOval(Rect(w * .10f, h * .10f, w * .90f, h * .90f))
                MilGpsShape.SQUARE -> path.addRect(Rect(w * .12f, h * .12f, w * .88f, h * .88f))
                MilGpsShape.TRIANGLE -> {
                    path.moveTo(w * .5f, h * .08f)
                    path.lineTo(w * .94f, h * .88f)
                    path.lineTo(w * .06f, h * .88f)
                    path.close()
                }
                MilGpsShape.CROSS -> {
                    val points = listOf(.41f to .10f, .59f to .10f, .59f to .41f,
                        .90f to .41f, .90f to .59f, .59f to .59f, .59f to .90f,
                        .41f to .90f, .41f to .59f, .10f to .59f, .10f to .41f, .41f to .41f)
                    points.forEachIndexed { i, (x, y) ->
                        if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
                    }
                    path.close()
                }
                MilGpsShape.STAR -> {
                    repeat(10) { i ->
                        val angle = -Math.PI / 2 + i * Math.PI / 5
                        val radius = if (i % 2 == 0) .46f else .25f
                        val x = w * (.5f + cos(angle).toFloat() * radius)
                        val y = h * (.5f + sin(angle).toFloat() * radius)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                }
            }
            drawPath(path, color)
            drawPath(path, Color.Black.copy(alpha = .65f), style = Stroke(w * .055f))
        }
        symbol.character?.let { character ->
            Text(character, fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
                fontSize = (size.value * .43f).sp, lineHeight = (size.value * .47f).sp,
                color = Color.Black, maxLines = 1,
                modifier = Modifier.offset(y = if (symbol.shape == MilGpsShape.TRIANGLE) size * .09f else 0.dp))
        }
    }
}

/** Editing the imported appearance doesn't change its tactical affiliation. */
@Composable
internal fun MilGpsMarkerEditor(metadata: WaypointMetadata, night: Boolean, onChange: (WaypointMetadata) -> Unit) {
    val decoded = MilGpsSymbols.decode(metadata.milgpsSymbolCode)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Imported marker", style = MaterialTheme.typography.labelLarge)
        WaypointMarker("flag", "none", size = 46.dp, night = night, metadata = metadata)
        if (metadata.milgpsSymbolCode != null && decoded == null) {
            Text("This imported symbol is unavailable. Its original code is kept when exporting.",
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            MarkerChoice("Color", metadata.color ?: "Default", listOf("Default") + MilGpsSymbols.colors) {
                onChange(metadata.copy(color = it.takeUnless { value -> value == "Default" }))
            }
            MarkerChoice("Shape", decoded?.shape?.label ?: "Choose", MilGpsShape.entries.map { it.label }) { label ->
                val shape = MilGpsShape.entries.first { it.label == label }
                onChange(metadata.copy(milgpsSymbolCode = MilGpsSymbols.encode(shape, decoded?.character)))
            }
            MarkerChoice("Icon", decoded?.character ?: "None", listOf("None") + MilGpsSymbols.characters,
                enabled = decoded != null && decoded.shape != MilGpsShape.CROSS) {
                decoded?.let { symbol -> onChange(metadata.copy(milgpsSymbolCode =
                    MilGpsSymbols.encode(symbol.shape, it.takeUnless { value -> value == "None" }))) }
            }
        }
        TextButton(onClick = { onChange(metadata.copy(color = null, milgpsSymbolCode = null)) }) {
            Text("Use Gridfix symbols")
        }
    }
}

@Composable
private fun MarkerChoice(label: String, current: String, choices: List<String>, enabled: Boolean = true, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("$label: $current") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { open = false; onChoose(choice) }) }
        }
    }
}

@Composable
fun WaypointMetadataText(metadata: WaypointMetadata) {
    metadata.elevationMeters?.let { Text(String.format(Locale.US, "Elevation: %.1f m", it), style = MaterialTheme.typography.bodySmall) }
    metadata.timestampMillis?.let { Text("Recorded: ${Instant.ofEpochMilli(it)}", style = MaterialTheme.typography.bodySmall) }
}
