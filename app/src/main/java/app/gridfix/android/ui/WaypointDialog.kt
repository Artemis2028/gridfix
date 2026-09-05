package app.gridfix.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.DEFAULT_FOLDER
import app.gridfix.android.data.KIND_UNIT
import app.gridfix.android.data.KIND_WP
import app.gridfix.android.data.Waypoint
import app.gridfix.android.data.WaypointDraft
import app.gridfix.android.data.WaypointMetadata
import app.gridfix.android.data.reservedFolderHint
import org.osmdroid.util.GeoPoint
import java.util.Locale
import app.gridfix.android.location.Declination

/** Wrapped grid of symbol tiles — the browse-everything unit picker. */
@Composable
fun SymbolGrid(
    keys: List<String>,
    selected: String,
    affiliation: String,
    labelFor: ((String) -> String)? = null,
    night: Boolean = false,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(5).forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowKeys.forEach { key ->
                    val isSelected = key == selected
                    Column(
                        modifier = Modifier.width(58.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { onSelect(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            WaypointMarker(symbol = key, affiliation = affiliation, size = 32.dp, night = night)
                        }
                        labelFor?.let { f ->
                            Text(
                                f(key),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SymbolRow(
    keys: List<String>,
    selected: String,
    affiliation: String,
    labelFor: ((String) -> String)? = null,
    night: Boolean = false,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { key ->
            val isSelected = key == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    WaypointMarker(symbol = key, affiliation = affiliation, size = 32.dp, night = night)
                }
                labelFor?.let { f ->
                    Text(
                        f(key),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Create/edit dialog for a waypoint, shared by the Waypoints screen (preset =
 * current GPS position) and the Map screen (preset = crosshair position).
 */
@Composable
fun WaypointDialog(
    initial: Waypoint?,
    presetLat: Double?,
    presetLon: Double?,
    presetLabel: String,
    folderNames: List<String>,
    defaultName: String,
    onConfirm: (WaypointDraft) -> Unit,
    onDismiss: () -> Unit,
    settings: AppSettings,
    projectBases: List<Waypoint> = emptyList(),
    unitNameFor: (symbol: String, echelon: String) -> String = { _, _ -> defaultName },
) {
    val night = settings.nightMode
    val baseParts = remember(initial) {
        when {
            initial != null -> Coordinates.mgrs(initial.lat, initial.lon, 10)
            presetLat != null && presetLon != null -> Coordinates.mgrs(presetLat, presetLon, 10)
            else -> null
        }
    }

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var folder by remember(initial) { mutableStateOf(initial?.folder ?: DEFAULT_FOLDER) }
    var symbol by remember(initial) { mutableStateOf(initial?.symbol ?: "flag") }
    var affiliation by remember(initial) { mutableStateOf(initial?.affiliation ?: "none") }
    var echelon by remember(initial) { mutableStateOf(initial?.echelon ?: "") }
    var designation by remember(initial) { mutableStateOf(initial?.designation ?: "") }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: KIND_WP) }
    var rotation by remember(initial) { mutableStateOf(initial?.rotation ?: 0f) }
    var metadata by remember(initial) { mutableStateOf(initial?.metadata ?: WaypointMetadata()) }
    fun chooseNativeSymbol(key: String) {
        symbol = key
        metadata = metadata.copy(color = null, milgpsSymbolCode = null)
    }
    // 0 = preset position, 1 = MGRS entry, 2 = project from a known point
    var posMode by remember(initial) { mutableStateOf(if (initial == null && presetLat != null) 0 else 1) }
    var projBaseId by remember(initial) { mutableStateOf<String?>(null) }
    var azText by remember(initial) { mutableStateOf("") }
    var distText by remember(initial) { mutableStateOf("") }
    var projMenuOpen by remember { mutableStateOf(false) }
    val northingFocus = remember { FocusRequester() }
    var gzdSquare by remember(initial) {
        mutableStateOf(baseParts?.let { "${it.gzd} ${it.square}" } ?: "")
    }
    var easting by remember(initial) { mutableStateOf(baseParts?.easting ?: "") }
    var northing by remember(initial) { mutableStateOf(baseParts?.northing ?: "") }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    val bigDigits = LocalTextStyle.current.copy(
        fontFamily = MonoFamily,
        fontSize = 22.sp,
        textAlign = TextAlign.Center,
    )

    // Projection base + live result, shared by the fields and the confirm button
    val projBase: Triple<String, Double, Double>? = when {
        projBaseId != null -> projectBases.firstOrNull { it.id == projBaseId }
            ?.let { Triple(it.name, it.lat, it.lon) }
        presetLat != null && presetLon != null -> Triple(presetLabel, presetLat, presetLon)
        else -> projectBases.firstOrNull()?.let { Triple(it.name, it.lat, it.lon) }
    }
    val refLetter = when (settings.northRef) {
        1 -> "magnetic"
        2 -> "grid"
        else -> "true"
    }
    val angleLabel = if (settings.angleUnit == 1) "mils" else "degrees"
    val projResult: Pair<Double, Double>? = run {
        val base = projBase ?: return@run null
        val az = azText.toFloatOrNull() ?: return@run null
        val dist = distText.toFloatOrNull() ?: return@run null
        if (dist <= 0f || dist > 200000f) return@run null
        val deg = if (settings.angleUnit == 1) az * 360f / 6400f else az
        val decl = Declination.at(settings, base.second, base.third)
        val conv = Coordinates.gridConvergence(base.second, base.third).toFloat()
        val trueDeg = when (settings.northRef) {
            1 -> deg + decl
            2 -> deg + conv
            else -> deg
        }
        val dest = GeoPoint(base.second, base.third)
            .destinationPoint(dist.toDouble(), (((trueDeg % 360f) + 360f) % 360f).toDouble())
        dest.latitude to dest.longitude
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    initial == null && kind == KIND_UNIT -> "New unit"
                    initial == null -> "New waypoint"
                    kind == KIND_UNIT -> "Edit unit"
                    else -> "Edit waypoint"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == KIND_WP,
                        onClick = {
                            kind = KIND_WP
                            if (symbol.startsWith("nato_")) symbol = "flag"
                        },
                        label = { Text("Waypoint") },
                    )
                    FilterChip(
                        selected = kind == KIND_UNIT,
                        onClick = {
                            kind = KIND_UNIT
                            metadata = metadata.copy(color = null, milgpsSymbolCode = null)
                            if (!symbol.startsWith("nato_")) symbol = "nato_f_unit"
                        },
                        label = { Text("Unit") },
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = {
                        Text(
                            when {
                                kind == KIND_UNIT -> unitNameFor(symbol, echelon)
                                WaypointSymbols.isTask(symbol) -> unitNameFor(symbol, "")
                                else -> defaultName
                            }
                        )
                    },
                    singleLine = true,
                )

                if (kind == KIND_WP) {
                    if (metadata.color != null || metadata.milgpsSymbolCode != null) {
                        MilGpsMarkerEditor(metadata, night) { metadata = it }
                    }
                    WaypointMetadataText(metadata)
                    Text("Affiliation", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Affiliations.all.forEach { key ->
                            FilterChip(
                                selected = key == affiliation,
                                onClick = {
                                    affiliation = key
                                    if (key != "none") metadata = metadata.copy(color = null, milgpsSymbolCode = null)
                                },
                                label = { Text(Affiliations.label(key)) },
                            )
                        }
                    }

                    Text("Symbol", style = MaterialTheme.typography.labelLarge)
                    SymbolRow(
                        keys = WaypointSymbols.all,
                        selected = symbol,
                        affiliation = affiliation,
                        labelFor = { WaypointSymbols.label(it) },
                        night = night,
                    ) { chooseNativeSymbol(it) }

                    Text("Tactical task", style = MaterialTheme.typography.labelLarge)
                    SymbolRow(
                        keys = WaypointSymbols.tasks,
                        selected = symbol,
                        affiliation = affiliation,
                        labelFor = { WaypointSymbols.taskLabel(it) },
                        night = night,
                    ) { chooseNativeSymbol(it) }

                    if (WaypointSymbols.isTask(symbol) && WaypointSymbols.taskLetter(symbol) == null) {
                        Text(
                            "Direction of fire — ${rotation.toInt()}°",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            WaypointMarker(
                                symbol = symbol,
                                affiliation = affiliation,
                                size = 40.dp,
                                night = night,
                                rotation = rotation,
                            )
                            Slider(
                                value = rotation,
                                onValueChange = { rotation = (it.toInt() % 360).toFloat() },
                                valueRange = 0f..359f,
                            )
                        }
                    }
                } else {
                    // One affiliation choice up top drives the whole grid — no
                    // repeated per-affiliation rows.
                    val unitAffs = listOf("friendly", "hostile", "neutral", "unknown")
                    val affChar = when (affiliation) {
                        "hostile" -> "h"
                        "neutral" -> "n"
                        "unknown" -> "u"
                        else -> "f"
                    }
                    Text("Affiliation", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        unitAffs.forEach { key ->
                            FilterChip(
                                selected = (affiliation == key) || (affiliation !in unitAffs && key == "friendly"),
                                onClick = {
                                    affiliation = key
                                    if (NatoSymbols.isNato(symbol)) {
                                        val func = symbol.removePrefix("nato_").substringAfter("_")
                                        val c = when (key) {
                                            "hostile" -> "h"
                                            "neutral" -> "n"
                                            "unknown" -> "u"
                                            else -> "f"
                                        }
                                        symbol = "nato_${c}_$func"
                                    }
                                },
                                label = { Text(Affiliations.label(key)) },
                            )
                        }
                    }

                    Text("Echelon", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Echelons.all.forEach { (key, label) ->
                            FilterChip(
                                selected = key == echelon,
                                onClick = { echelon = key },
                                label = { Text(label) },
                            )
                        }
                    }

                    Text("Unit symbol", style = MaterialTheme.typography.labelLarge)
                    var unitQuery by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = unitQuery,
                        onValueChange = { unitQuery = it.take(30) },
                        label = {
                            Text("Search ${NatoSymbols.functions.size + NatoSymbols.extended.size} unit types")
                        },
                        placeholder = { Text("e.g. mortar, airborne, supply") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val unitResults = remember(unitQuery) {
                        if (unitQuery.isBlank()) NatoSymbols.functions + NatoSymbols.extended
                        else NatoSymbols.search(unitQuery)
                    }
                    if (unitResults.isEmpty()) {
                        Text(
                            "No matching unit types.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SymbolGrid(
                            keys = unitResults.map { "nato_${affChar}_${it.first}" },
                            selected = symbol,
                            affiliation = if (affiliation in unitAffs) affiliation else "friendly",
                            labelFor = { NatoSymbols.functionLabel(it) },
                            night = night,
                        ) {
                            symbol = it
                            if (affiliation !in unitAffs) affiliation = "friendly"
                        }
                    }
                    OutlinedTextField(
                        value = designation,
                        onValueChange = { designation = it.take(24) },
                        label = { Text("Unit designation (optional)") },
                        placeholder = { Text("e.g. A/1-502") },
                        singleLine = true,
                    )
                }

                Text("Folder", style = MaterialTheme.typography.labelLarge)
                if (folderNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        folderNames.forEach { f ->
                            FilterChip(
                                selected = f == folder,
                                onClick = { folder = f },
                                label = { Text(f) },
                            )
                        }
                    }
                }
                val folderHint = reservedFolderHint(folder)
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Folder (type to create new)") },
                    singleLine = true,
                    supportingText = folderHint?.let { { Text(it) } },
                )

                Text("Position", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = posMode == 0,
                        onClick = { posMode = 0 },
                        label = { Text(presetLabel) },
                        enabled = presetLat != null,
                    )
                    FilterChip(
                        selected = posMode == 1,
                        onClick = { posMode = 1 },
                        label = { Text("MGRS grid") },
                    )
                    FilterChip(
                        selected = posMode == 2,
                        onClick = { posMode = 2 },
                        label = { Text("Project") },
                        enabled = presetLat != null || projectBases.isNotEmpty(),
                    )
                }
                if (posMode == 1) {
                    OutlinedTextField(
                        value = gzdSquare,
                        onValueChange = { v ->
                            val cleaned = v.uppercase(Locale.US)
                                .filter { it.isLetterOrDigit() || it == ' ' }
                            var handled = false
                            if (cleaned.replace(" ", "").length > 7) {
                                // A full grid was pasted — split it into the fields
                                val parsed = Coordinates.parseMgrs(cleaned)
                                if (parsed != null) {
                                    val parts = Coordinates.mgrs(parsed.first, parsed.second, 10)
                                    if (parts != null && parts.easting.isNotEmpty()) {
                                        gzdSquare = "${parts.gzd} ${parts.square}"
                                        easting = parts.easting
                                        northing = parts.northing
                                        handled = true
                                    }
                                }
                            }
                            if (!handled) gzdSquare = cleaned.take(7)
                            error = null
                        },
                        label = { Text("Grid zone (or paste a full grid)") },
                        placeholder = { Text("39R TM") },
                        singleLine = true,
                        textStyle = bigDigits,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = easting,
                            onValueChange = { v ->
                                easting = v.filter { it.isDigit() }.take(5)
                                error = null
                                if (easting.length == 5) northingFocus.requestFocus()
                            },
                            label = { Text("Easting") },
                            placeholder = { Text("23559") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = northing,
                            onValueChange = { v ->
                                northing = v.filter { it.isDigit() }.take(5)
                                error = null
                            },
                            label = { Text("Northing") },
                            placeholder = { Text("96991") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(northingFocus),
                        )
                    }
                } else if (posMode == 2) {
                    Row(
                        modifier = Modifier.clickable { projMenuOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "From: " + (projBase?.first ?: "no point available"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = "Choose base point",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DropdownMenu(
                            expanded = projMenuOpen,
                            onDismissRequest = { projMenuOpen = false },
                        ) {
                            if (presetLat != null && presetLon != null) {
                                DropdownMenuItem(
                                    text = { Text(presetLabel) },
                                    onClick = { projBaseId = null; projMenuOpen = false },
                                )
                            }
                            projectBases.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.name) },
                                    onClick = { projBaseId = b.id; projMenuOpen = false },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = azText,
                            onValueChange = { v ->
                                azText = v.filter { it.isDigit() || it == '.' }.take(6)
                                error = null
                            },
                            label = { Text("Azimuth ($angleLabel $refLetter)") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = distText,
                            onValueChange = { v ->
                                distText = v.filter { it.isDigit() }.take(6)
                                error = null
                            },
                            label = { Text("Distance (m)") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        projResult?.let {
                            "→ " + (Coordinates.mgrs(it.first, it.second, 10)?.full ?: "—")
                        } ?: "Enter azimuth and distance from the base point.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (presetLat != null && presetLon != null) {
                    Text(
                        "$presetLabel: " + (Coordinates.mgrs(presetLat, presetLon, 10)?.full ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalName = name.ifBlank {
                    when {
                        kind == KIND_UNIT -> unitNameFor(symbol, echelon)
                        WaypointSymbols.isTask(symbol) -> unitNameFor(symbol, "")
                        else -> defaultName
                    }
                }
                val finalFolder = folder.ifBlank { DEFAULT_FOLDER }
                // A unit's affiliation comes from its chosen symbol frame
                val finalAffiliation = if (kind == KIND_UNIT) {
                    when (symbol.split("_").getOrNull(1)) {
                        "f" -> "friendly"
                        "h" -> "hostile"
                        "n" -> "neutral"
                        else -> "unknown"
                    }
                } else affiliation
                val finalEchelon = if (kind == KIND_UNIT) echelon else ""
                val finalDesignation = if (kind == KIND_UNIT) designation.trim() else ""
                val finalRotation =
                    if (kind == KIND_WP && WaypointSymbols.isTask(symbol) &&
                        WaypointSymbols.taskLetter(symbol) == null
                    ) rotation else 0f
                fun confirmAt(lat: Double, lon: Double) {
                    onConfirm(
                        WaypointDraft(
                            finalName, lat, lon, finalFolder, symbol, finalAffiliation,
                            finalEchelon, finalDesignation, kind, finalRotation,
                            metadata = metadata,
                        )
                    )
                }
                when {
                    posMode == 0 && presetLat != null && presetLon != null ->
                        confirmAt(presetLat, presetLon)
                    posMode == 2 -> {
                        val r = projResult
                        if (r == null) {
                            error = "Projection needs a base point, azimuth, and distance (max 200 km)."
                        } else {
                            confirmAt(r.first, r.second)
                        }
                    }
                    easting.isEmpty() || easting.length != northing.length ->
                        error = "Easting and northing need the same number of digits."
                    else -> {
                        val parsed = Coordinates.parseMgrs(gzdSquare + easting + northing)
                        if (parsed == null) {
                            error = "Couldn't read that grid — check the zone letters and digits."
                        } else {
                            confirmAt(parsed.first, parsed.second)
                        }
                    }
                }
            }) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
