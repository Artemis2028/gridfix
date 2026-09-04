package app.gridfix.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.gridfix.android.ui.theme.LabelFamily
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.ui.faces.northRefLetter
import app.gridfix.android.ui.faces.toNorthRef
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.DEFAULT_FOLDER
import app.gridfix.android.data.canonicalFolder
import app.gridfix.android.data.reservedFolderHint
import app.gridfix.android.data.FolderInfo
import app.gridfix.android.data.GraphicTypes
import app.gridfix.android.data.InterchangeFiles
import app.gridfix.android.data.KIND_UNIT
import app.gridfix.android.data.TacGraphic
import app.gridfix.android.data.TrackInfo
import app.gridfix.android.data.TrackRepository
import app.gridfix.android.data.Waypoint
import app.gridfix.android.data.WaypointDraft
import app.gridfix.android.location.FixData
import app.gridfix.android.ui.Affiliations
import app.gridfix.android.ui.Echelons
import app.gridfix.android.ui.NatoSymbols
import app.gridfix.android.ui.RouteCardDialog
import app.gridfix.android.ui.WaypointDialog
import app.gridfix.android.ui.WaypointMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.gridfix.android.location.Declination

@Composable
fun WaypointsScreen(
    fix: FixData,
    settings: AppSettings,
    waypoints: List<Waypoint>,
    folders: List<FolderInfo>,
    onAdd: (WaypointDraft) -> Unit,
    onUpdate: (id: String, draft: WaypointDraft) -> Unit,
    onDelete: (String) -> Unit,
    onNavigateTo: (String) -> Unit,
    onAddFolder: (String) -> Unit,
    onSetFolderVisible: (name: String, visible: Boolean) -> Unit,
    onSetWaypointVisible: (id: String, visible: Boolean) -> Unit = { _, _ -> },
    onSetGraphicVisible: (id: String, visible: Boolean) -> Unit = { _, _ -> },
    onSetTrackVisible: (id: String, visible: Boolean) -> Unit = { _, _ -> },
    onRenameFolder: (from: String, to: String) -> Unit = { _, _ -> },
    onDeleteFolder: (name: String, deleteContents: Boolean) -> Unit = { _, _ -> },
    graphics: List<TacGraphic>,
    onDeleteGraphic: (String) -> Unit,
    onClearGraphics: (folder: String) -> Unit,
    tracks: List<TrackInfo>,
    viewedTrackId: String?,
    onViewTrack: (String?) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onMoveTrack: (id: String, folder: String) -> Unit = { _, _ -> },
    onBacktrackTrack: (TrackInfo) -> Unit,
    onShowOnMap: (Waypoint) -> Unit,
    onShowGraphicOnMap: (TacGraphic) -> Unit,
    unitNameFor: (symbol: String, echelon: String) -> String,
    onImport: (InterchangeFiles.ImportedData, onDone: (String) -> Unit) -> Unit,
    onExport: (format: String, onDone: (String?) -> Unit) -> Unit,
)
{
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Waypoint?>(null) }
    var deleteCandidate by remember { mutableStateOf<Waypoint?>(null) }
    var deleteGraphicCandidate by remember { mutableStateOf<TacGraphic?>(null) }
    var deleteTrackCandidate by remember { mutableStateOf<TrackInfo?>(null) }
    var clearFolderCandidate by remember { mutableStateOf<String?>(null) }
    var routeCardFor by remember { mutableStateOf<TacGraphic?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var ioMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var display = "file"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx)?.let { display = it }
                }
                val data = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            InterchangeFiles.parse(display, stream)
                        }
                    }.getOrNull()
                }
                when {
                    data == null -> ioMessage = "Couldn't read $display — GPX, KML, KMZ, or ATAK zip expected"
                    data.isEmpty -> ioMessage = "Nothing importable in $display"
                    else -> onImport(data) { summary -> ioMessage = summary }
                }
            }
        }
    }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameFolderOpen by remember { mutableStateOf(false) }
    var deleteFolderOpen by remember { mutableStateOf(false) }
    val loc = fix.location
    val byFolder = waypoints.groupBy { it.folder }
    val graphicsByFolder = graphics.groupBy { it.folder }

    fun shareGpx(track: TrackInfo) {
        scope.launch {
            runCatching {
                val points = TrackRepository.readPoints(context, track.id)
                val gpx = TrackRepository.buildGpx(track.name, points)
                val safe = track.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "track" }
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    File(dir, "$safe.gpx").apply { writeText(gpx) }
                }
                val uri = FileProvider.getUriForFile(
                    context, "app.gridfix.android.fileprovider", file
                )
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(send, "Share GPX track")
                )
            }
        }
    }

    // ---- Cards view: ALL or one folder; each shows its WAYPOINTS / GRAPHICS / TRACKS ----
    // The chosen tab survives a trip to the map ("Show on map") and a rotation
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var moveTrackCandidate by remember { mutableStateOf<TrackInfo?>(null) }
    val folderNames = folders.map { it.name }
    val activeFilter = if (filter == FILTER_ALL || filter in folderNames) filter else FILTER_ALL
    val declination = remember(
        loc?.latitude?.let { (it * 10).toInt() },
        loc?.longitude?.let { (it * 10).toInt() },
        settings.declinationOverride,
    ) { Declination.at(settings, loc) }
    val convergence = if (loc == null) 0f else Coordinates.gridConvergence(loc.latitude, loc.longitude).toFloat()
    val refLetter = northRefLetter(settings.northRef)
    fun distanceTo(w: Waypoint): Float =
        if (loc == null) Float.MAX_VALUE else Coordinates.navInfo(loc.latitude, loc.longitude, w.lat, w.lon).distanceMeters
    val currentFolder = folders.firstOrNull { it.name == activeFilter }
    // ALL lists everything: the eye is a map switch, and every card carries its folder.
    // Distance is bucketed to 25 m so cards do not reshuffle under the finger on GPS noise.
    val listed: List<Waypoint> = (
        if (currentFolder == null) waypoints
        else byFolder[currentFolder.name].orEmpty()
        ).let { list ->
            if (loc != null) list.sortedWith(compareBy({ (distanceTo(it) / 25f).toInt() }, { it.name })) else list
        }
    val listedGraphics: List<TacGraphic> =
        if (currentFolder == null) graphics else graphicsByFolder[currentFolder.name].orEmpty()
    val listedTracks: List<TrackInfo> =
        if (currentFolder == null) tracks else tracks.filter { it.folder == currentFolder.name }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Folder tabs
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterTab("All", activeFilter == FILTER_ALL) { filter = FILTER_ALL }
                folders.forEach { f ->
                    FilterTab(f.name, activeFilter == f.name, dim = !f.visible) { filter = f.name }
                }
                FilterTab("Folder", false, icon = Icons.Outlined.CreateNewFolder) { newFolderOpen = true }
            }

            // Caption: what is listed; the folder's one eye switch for the map; import / export
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = buildList {
                    if (currentFolder == null && loc != null && listed.isNotEmpty()) add("By distance")
                    add("${listed.size} waypoints")
                    if (listedGraphics.isNotEmpty()) add("${listedGraphics.size} graphics")
                    if (listedTracks.isNotEmpty()) add("${listedTracks.size} tracks")
                }
                Text(
                    parts.joinToString(" · ").uppercase(Locale.US),
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                currentFolder?.let { f ->
                    IconButton(onClick = { onSetFolderVisible(f.name, !f.visible) }) {
                        Icon(
                            if (f.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (f.visible) "Hide this folder on the map" else "Show this folder on the map",
                            tint = if (f.visible) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Base is permanent; every other folder can be renamed or removed
                    if (f.name != DEFAULT_FOLDER) {
                        var folderMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { folderMenu = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = "Folder options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename folder…") },
                                    onClick = { folderMenu = false; renameFolderOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete folder…") },
                                    onClick = { folderMenu = false; deleteFolderOpen = true },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(
                        Icons.Outlined.FileUpload,
                        contentDescription = "Import GPX/KML/KMZ",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { exportOpen = true }) {
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = "Export",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ioMessage?.let { msg ->
                    item(key = "io-message") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ioMessage = null },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFamily,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                if (currentFolder != null && !currentFolder.visible) {
                    item(key = "hidden-hint") {
                        Text(
                            "${currentFolder.name} is hidden on the map — its waypoints, graphics and tracks come back with the eye.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- WAYPOINTS ----
                if (listed.isNotEmpty()) {
                    item(key = "sec-wp") { SectionLabel("Waypoints · ${listed.size}") }
                    items(listed, key = { it.id }) { w ->
                        SwipeRow(
                            visible = w.visible,
                            onToggleVisible = { onSetWaypointVisible(w.id, !w.visible) },
                            onDelete = { deleteCandidate = w },
                        ) {
                        WaypointCard(
                            w = w,
                            loc = loc,
                            settings = settings,
                            declination = declination,
                            convergence = convergence,
                            refLetter = refLetter,
                            showFolder = currentFolder == null,
                            onClick = { if (w.kind == KIND_UNIT) onShowOnMap(w) else onNavigateTo(w.id) },
                            onNavigate = { onNavigateTo(w.id) },
                            onEdit = { editing = w; dialogOpen = true },
                            onDelete = { deleteCandidate = w },
                            onShowOnMap = { onShowOnMap(w) },
                        )
                        }
                    }
                }

                // ---- GRAPHICS (drawings, routes, areas) ----
                if (listedGraphics.isNotEmpty()) {
                    item(key = "sec-g") { SectionLabel("Graphics · ${listedGraphics.size}") }
                    items(listedGraphics, key = { "g-" + it.id }) { g ->
                        SwipeRow(
                            visible = g.visible,
                            onToggleVisible = { onSetGraphicVisible(g.id, !g.visible) },
                            onDelete = { deleteGraphicCandidate = g },
                        ) {
                        GraphicRow(
                            g = g,
                            night = settings.nightMode,
                            folderLabel = if (currentFolder == null) g.folder else null,
                            onClick = { onShowGraphicOnMap(g) },
                            onCard = if (g.type == "route") {
                                { routeCardFor = g }
                            } else null,
                            onDelete = { deleteGraphicCandidate = g },
                        )
                        }
                    }
                    if (currentFolder != null && listedGraphics.size > 1) {
                        item(key = "clear-${currentFolder.name}") {
                            TextButton(onClick = { clearFolderCandidate = currentFolder.name }) {
                                Text("Clear ${listedGraphics.size} graphics in ${currentFolder.name}")
                            }
                        }
                    }
                }

                // ---- TRACKS ----
                if (listedTracks.isNotEmpty()) {
                    item(key = "sec-t") { SectionLabel("Tracks · ${listedTracks.size}") }
                    items(listedTracks, key = { "t-" + it.id }) { t ->
                        SwipeRow(
                            visible = t.visible,
                            onToggleVisible = { onSetTrackVisible(t.id, !t.visible) },
                            onDelete = { deleteTrackCandidate = t },
                        ) {
                        TrackRow(
                            t = t,
                            settings = settings,
                            viewed = t.id == viewedTrackId,
                            folderLabel = if (currentFolder == null) t.folder else null,
                            onView = { onViewTrack(if (t.id == viewedTrackId) null else t.id) },
                            onShare = { shareGpx(t) },
                            onDelete = { deleteTrackCandidate = t },
                            onBacktrack = { onBacktrackTrack(t) },
                            onMove = { moveTrackCandidate = t },
                        )
                        }
                    }
                }

                if (listed.isEmpty() && listedGraphics.isEmpty() && listedTracks.isEmpty()) {
                    item(key = "empty-hint") {
                        Text(
                            if (currentFolder == null) {
                                "Nothing saved yet — tap + to mark your position or enter a grid; drawings and recorded tracks land here too."
                            } else {
                                "Nothing in ${currentFolder.name} yet — tap + to add a waypoint, or draw on the map and pick this folder."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editing = null; dialogOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add waypoint")
        }
    }

    moveTrackCandidate?.let { t ->
        AlertDialog(
            onDismissRequest = { moveTrackCandidate = null },
            title = { Text("Move ${t.name} to…") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    folders.forEach { f ->
                        TextButton(
                            onClick = {
                                onMoveTrack(t.id, f.name)
                                moveTrackCandidate = null
                            },
                            enabled = f.name != t.folder,
                        ) { Text(if (f.name == t.folder) "${f.name}  (current)" else f.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveTrackCandidate = null }) { Text("Cancel") }
            },
        )
    }

    if (dialogOpen) {
        WaypointDialog(
            initial = editing,
            presetLat = loc?.latitude,
            presetLon = loc?.longitude,
            presetLabel = "Current position",
            folderNames = folders.map { it.name },
            defaultName = "WP " + (waypoints.size + 1),
            onConfirm = { draft ->
                val target = editing
                if (target == null) onAdd(draft) else onUpdate(target.id, draft)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false },
            settings = settings,
            projectBases = waypoints,
            unitNameFor = unitNameFor,
        )
    }

    if (newFolderOpen) {
        var folderName by remember { mutableStateOf("") }
        val hint = reservedFolderHint(folderName)
        AlertDialog(
            onDismissRequest = { newFolderOpen = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    supportingText = hint?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) onAddFolder(folderName.trim())
                    newFolderOpen = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newFolderOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (renameFolderOpen && currentFolder != null && currentFolder.name != DEFAULT_FOLDER) {
        val f = currentFolder
        var newName by remember(f.name) { mutableStateOf(f.name) }
        val hint = reservedFolderHint(newName)
        val existing = folders.firstOrNull { it.name != f.name && it.name.equals(newName.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { renameFolderOpen = false },
            title = { Text("Rename ${f.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        supportingText = (hint ?: existing?.let { "Merges into ${it.name}" })?.let { { Text(it) } },
                    )
                    Text(
                        "Waypoints, graphics and tracks in the folder move with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != f.name,
                    onClick = {
                        val target = existing?.name ?: newName.trim()
                        onRenameFolder(f.name, target)
                        filter = canonicalFolder(target)
                        renameFolderOpen = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameFolderOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (deleteFolderOpen && currentFolder != null && currentFolder.name != DEFAULT_FOLDER) {
        val f = currentFolder
        val nW = byFolder[f.name].orEmpty().size
        val nG = graphicsByFolder[f.name].orEmpty().size
        val nT = tracks.count { it.folder == f.name }
        val contents = listOfNotNull(
            nW.takeIf { it > 0 }?.let { "$it waypoint" + (if (it == 1) "" else "s") },
            nG.takeIf { it > 0 }?.let { "$it graphic" + (if (it == 1) "" else "s") },
            nT.takeIf { it > 0 }?.let { "$it track" + (if (it == 1) "" else "s") },
        )
        AlertDialog(
            onDismissRequest = { deleteFolderOpen = false },
            title = { Text("Delete ${f.name}?") },
            text = {
                Text(
                    if (contents.isEmpty()) "The folder is empty."
                    else "It holds ${contents.joinToString(", ")}. Keep them in $DEFAULT_FOLDER, or delete them with the folder."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (contents.isNotEmpty()) {
                        TextButton(onClick = {
                            onDeleteFolder(f.name, false)
                            filter = FILTER_ALL
                            deleteFolderOpen = false
                        }) { Text("Keep in $DEFAULT_FOLDER") }
                    }
                    TextButton(onClick = {
                        onDeleteFolder(f.name, true)
                        filter = FILTER_ALL
                        deleteFolderOpen = false
                    }) { Text(if (contents.isEmpty()) "Delete" else "Delete all", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderOpen = false }) { Text("Cancel") }
            },
        )
    }

    routeCardFor?.let { r ->
        RouteCardDialog(
            route = r,
            settings = settings,
            onDismiss = { routeCardFor = null },
        )
    }

    if (exportOpen) {
        AlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text("Export") },
            text = {
                Text(
                    "GPX carries waypoints, routes, and tracks (widest app support). " +
                        "KML carries everything including drawn graphics (Google Earth, ATAK). " +
                        "ATAK PKG is a mission data package — waypoints as CoT plus routes, " +
                        "imported directly by ATAK/CivTAK.",
                )
            },
            confirmButton = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        exportOpen = false
                        onExport("gpx") { r -> ioMessage = r?.let { "Sharing $it" } ?: "Export failed" }
                    }) { Text("GPX") }
                    TextButton(onClick = {
                        exportOpen = false
                        onExport("kml") { r -> ioMessage = r?.let { "Sharing $it" } ?: "Export failed" }
                    }) { Text("KML") }
                    TextButton(onClick = {
                        exportOpen = false
                        onExport("atak") { r -> ioMessage = r?.let { "Sharing $it" } ?: "Export failed" }
                    }) { Text("ATAK PKG") }
                }
            },
            dismissButton = {
                TextButton(onClick = { exportOpen = false }) { Text("Cancel") }
            },
        )
    }

    deleteTrackCandidate?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTrackCandidate = null },
            title = { Text("Delete ${t.name}?") },
            text = { Text("The recorded track and its point log will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTrack(t.id)
                    deleteTrackCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTrackCandidate = null }) { Text("Cancel") }
            },
        )
    }

    deleteGraphicCandidate?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteGraphicCandidate = null },
            title = { Text("Delete ${g.name.ifBlank { GraphicTypes.label(g.type) }}?") },
            text = { Text("This drawn graphic will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGraphic(g.id)
                    deleteGraphicCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteGraphicCandidate = null }) { Text("Cancel") }
            },
        )
    }

    clearFolderCandidate?.let { fname ->
        val count = graphicsByFolder[fname].orEmpty().size
        AlertDialog(
            onDismissRequest = { clearFolderCandidate = null },
            title = { Text("Clear $fname?") },
            text = { Text("All $count drawn graphics in this folder will be removed permanently. Waypoints are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearGraphics(fname)
                    clearFolderCandidate = null
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearFolderCandidate = null }) { Text("Cancel") }
            },
        )
    }

    deleteCandidate?.let { w ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${w.name}?") },
            text = { Text("This waypoint will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(w.id)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One list row with the two swipe actions, shared by waypoints, graphics and tracks.
 *
 * Drag right-to-left to uncover an eye and toggle whether the item is drawn on the map;
 * drag left-to-right to uncover a red bin, which asks before deleting. **Neither gesture
 * removes the card** - the row always springs back and the action is what changes, so the
 * list never animates something away that has not been confirmed.
 *
 * Hand-rolled on `draggable` rather than Material's `SwipeToDismissBox` on purpose: that
 * component's signature has changed shape across material3 versions, nothing in this
 * sandbox can compile against the one this project pins (1.2.1 via Compose BOM
 * 2024.06.00), and a build spent finding that out costs more than these thirty lines.
 * `draggable` has been stable for years.
 */
@Composable
private fun SwipeRow(
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val trigger = with(density) { 84.dp.toPx() }   // how far to commit to the action
    val limit = with(density) { 112.dp.toPx() }    // as far as the card will travel
    val dragState = rememberDraggableState { delta ->
        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-limit, limit)) }
    }
    val pulled = offsetX.value
    val deleting = pulled > 0f

    Box(Modifier.fillMaxWidth()) {
        // The action under the card, on whichever side it has been pulled away from.
        if (pulled != 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        if (deleting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = if (deleting) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    // The icon shows what the swipe will DO, not the current state:
                    // a visible item offers to hide, a hidden one offers to show.
                    if (deleting) Icons.Outlined.Delete
                    else if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = if (deleting) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val travelled = offsetX.value
                        when {
                            travelled <= -trigger -> onToggleVisible()
                            travelled >= trigger -> onDelete()
                        }
                        offsetX.animateTo(0f)
                    },
                ),
        ) {
            // A hidden item stays in the list, dimmed, so it can be found and shown again.
            Box(Modifier.alpha(if (visible) 1f else 0.4f)) { content() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        fontFamily = LabelFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TrackRow(
    t: TrackInfo,
    settings: AppSettings,
    viewed: Boolean,
    folderLabel: String?,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onBacktrack: () -> Unit,
    onMove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Map,
                contentDescription = if (viewed) "Hide from map" else "Show on map",
                tint = if (viewed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.US)
                val durMin = if (t.endedAt > t.startedAt) (t.endedAt - t.startedAt) / 60000 else 0
                Text(
                    Coordinates.formatDistance(t.distanceM.toFloat(), settings.units) +
                        "  ·  ${durMin} min  ·  " + sdf.format(Date(t.startedAt)) +
                        if (viewed) "  ·  ON MAP" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = if (viewed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                folderLabel?.let { FolderTag(it) }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Track options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Back-track route") },
                        leadingIcon = { Icon(Icons.Outlined.Timeline, contentDescription = null) },
                        onClick = { menuOpen = false; onBacktrack() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share GPX") },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to folder…") },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        onClick = { menuOpen = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTag(text: String) {
    Text(
        text.uppercase(Locale.US),
        fontFamily = LabelFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun GraphicRow(
    g: TacGraphic,
    night: Boolean,
    folderLabel: String?,
    onClick: () -> Unit,
    onCard: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Timeline,
                contentDescription = null,
                tint = if (night) Color(0xFFFF3B30)
                else Affiliations.color(g.affiliation, MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    g.name.ifBlank { GraphicTypes.label(g.type) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${GraphicTypes.label(g.type)} · ${g.points.size} points",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                folderLabel?.let { FolderTag(it) }
            }
            if (onCard != null) {
                Row(
                    Modifier
                        .clickable(onClick = onCard)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "ROUTE CARD",
                        fontFamily = LabelFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete graphic",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val FILTER_ALL = "\u0000all"

/** One tab in the filter row: outlined when idle, amber-filled when selected, dimmed for hidden folders. */
@Composable
private fun FilterTab(
    label: String,
    selected: Boolean,
    dim: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .height(44.dp)
            .border(1.dp, if (selected) fill else line)
            .background(if (selected) fill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            label.uppercase(Locale.US),
            fontFamily = LabelFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                dim -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun WaypointCard(
    w: Waypoint,
    loc: android.location.Location?,
    settings: AppSettings,
    declination: Float,
    convergence: Float,
    refLetter: String,
    showFolder: Boolean,
    onClick: () -> Unit,
    onNavigate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                WaypointMarker(symbol = w.symbol, affiliation = w.affiliation, size = 36.dp, echelon = w.echelon, night = settings.nightMode, rotation = w.rotation)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    Coordinates.mgrs(w.lat, w.lon, 8)?.full
                        ?: String.format(Locale.US, "%.5f, %.5f", w.lat, w.lon),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val detail = if (w.kind == KIND_UNIT) {
                    val ech = Echelons.label(w.echelon).takeIf { w.echelon.isNotEmpty() }
                    listOfNotNull(
                        NatoSymbols.label(w.symbol),
                        ech,
                        w.designation.takeIf { it.isNotEmpty() },
                        w.folder.takeIf { showFolder },
                    ).joinToString(" · ")
                } else if (showFolder) w.folder else ""
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail.uppercase(Locale.US),
                        fontFamily = LabelFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (loc != null) {
                    val nav = Coordinates.navInfo(loc.latitude, loc.longitude, w.lat, w.lon)
                    Text(
                        Coordinates.formatDistance(nav.distanceMeters, settings.units),
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        Coordinates.formatAngle(toNorthRef(nav.bearingTrue, settings.northRef, declination, convergence), settings.angleUnit) + " " + refLetter,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    "NAVIGATE",
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onNavigate)
                        .padding(top = 6.dp, bottom = 2.dp),
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Waypoint options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Show on map") },
                        leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
                        onClick = { menuOpen = false; onShowOnMap() },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}
