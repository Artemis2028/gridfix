package app.gridfix.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.content.FileProvider
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.BuildConfig
import app.gridfix.android.billing.BillingManager
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.Backup
import app.gridfix.android.data.CourseRepository
import app.gridfix.android.data.CourseResult
import app.gridfix.android.data.DataPackage
import app.gridfix.android.data.DEFAULT_FOLDER
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.GraphicsRepository
import app.gridfix.android.data.simplifyTrack
import app.gridfix.android.data.InterchangeFiles
import app.gridfix.android.data.KIND_UNIT
import app.gridfix.android.data.SettingsRepository
import app.gridfix.android.data.TrackRepository
import app.gridfix.android.data.WaypointDraft
import app.gridfix.android.data.WaypointRepository
import app.gridfix.android.location.Declination
import app.gridfix.android.location.TrackRecorderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import kotlin.random.Random
import java.io.File
import app.gridfix.android.location.LocationTracker
import app.gridfix.android.ui.screens.MapScreen
import app.gridfix.android.ui.screens.NavigateScreen
import app.gridfix.android.ui.screens.PositionScreen
import app.gridfix.android.ui.screens.ReferenceScreen
import app.gridfix.android.ui.screens.SettingsScreen
import app.gridfix.android.ui.screens.WaypointsScreen
import app.gridfix.android.ui.theme.GridFixTheme
import app.gridfix.android.ui.theme.LabelFamily
import kotlinx.coroutines.launch

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridFixApp() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val billing = remember { BillingManager(context.applicationContext) }
    val entitlement by billing.state.collectAsStateWithLifecycle()
    var paywallPreview by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        billing.start()
        // Re-check on every return to the foreground: purchases made in the Play
        // Store app, pending purchases completing, and lapsed subscriptions.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) billing.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            billing.close()
        }
    }
    val waypointRepo = remember { WaypointRepository(context.applicationContext) }
    val waypoints by waypointRepo.waypoints.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedId by waypointRepo.selectedId.collectAsStateWithLifecycle(initialValue = null)
    val folders by waypointRepo.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val graphicsRepo = remember { GraphicsRepository(context.applicationContext) }
    val graphics by graphicsRepo.graphics.collectAsStateWithLifecycle(initialValue = emptyList())
    val trackRepo = remember { TrackRepository(context.applicationContext) }
    val tracks by trackRepo.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    var viewedTrackId by remember { mutableStateOf<String?>(null) }
    var mapFocus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val courseRepo = remember { CourseRepository(context.applicationContext) }
    val activeCourse by courseRepo.active.collectAsStateWithLifecycle(initialValue = null)
    val courseHistory by courseRepo.history.collectAsStateWithLifecycle(initialValue = emptyList())
    var courseOpen by remember { mutableStateOf(false) }
    var courseSummary by remember { mutableStateOf<CourseResult?>(null) }
    var summaryPending by remember { mutableStateOf(false) }

    // Automatic names: "Armor Brigade 1" for units, "Support by fire 1" for task symbols
    val unitNameFor: (String, String) -> String = { symbol, echelon ->
        if (WaypointSymbols.isTask(symbol)) {
            val base = WaypointSymbols.taskLabel(symbol)
            "$base ${waypoints.count { it.name.startsWith(base) } + 1}"
        } else {
            val func = NatoSymbols.functionLabel(symbol)
            val ech = if (echelon.isEmpty()) "" else " " + Echelons.label(echelon)
            val base = (func + ech).trim()
            val n = waypoints.count { it.kind == KIND_UNIT && it.name.startsWith(base) } + 1
            "$base $n"
        }
    }

    // Waypoints in visible ("active") overlays are the ones offered for navigation
    val navigableWaypoints = if (folders.isEmpty()) waypoints else {
        val visibleNames = folders.filter { it.visible }.map { it.name }.toSet()
        waypoints.filter { it.folder in visibleNames }
    }
    val scope = rememberCoroutineScope()
    val recordGate = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Location permission + shared tracker, hoisted so every screen can use the fix
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    // "Approximate" (coarse-only) is not enough for a GPS grid readout: the GPS
    // provider refuses it. Only a precise grant counts; coarse-only gets a hint.
    var approximateOnly by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        approximateOnly = !hasPermission &&
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val requestPermission = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }
    val tracker = remember { LocationTracker(context.applicationContext) }
    LaunchedEffect(Unit) {
        runCatching { trackRepo.finalizeOrphans(TrackRecorderService.active.value?.trackId) }
    }
    DisposableEffect(hasPermission) {
        if (hasPermission) tracker.start()
        onDispose { tracker.stop() }
    }
    val fix by tracker.fix.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Orientation lock: a phone worn sideways on armour stays sideways whatever
    // the phone's own auto-rotate says. The activity handles the config change
    // itself (manifest configChanges), so nothing is recreated.
    LaunchedEffect(settings.orientation) {
        context.hostActivity()?.requestedOrientation = ScreenOrientation.toActivityInfo(settings.orientation)
    }
    val landscape = isLandscape()

    // A viewed track in a hidden folder would draw nothing: gate the id once, for
    // the map and the list alike, and un-hide the folder when a track is viewed.
    val shownTrackId = viewedTrackId?.takeIf { id ->
        val f = tracks.firstOrNull { it.id == id }?.folder
        f == null || folders.firstOrNull { it.name == f }?.visible != false
    }

    val items = listOf(
        NavItem("position", "Position", Icons.Outlined.MyLocation),
        NavItem("navigate", "Navigate", Icons.Outlined.Explore),
        NavItem("map", "Map", Icons.Outlined.Map),
        NavItem("waypoints", "Waypoints", Icons.Outlined.Flag),
    )

    fun goTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Wait for the stored settings before deciding anything from them, so an
    // existing user never sees the first-run screen flash past on launch.
    var settingsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { repo.settings.first() }
        settingsLoaded = true
    }

    GridFixTheme(nightMode = settings.nightMode) {
        // What a phone GPS is and is not, once, before anything else.
        if (settingsLoaded && !settings.disclaimerAccepted) {
            DisclaimerScreen(onAccept = { scope.launch { repo.setDisclaimerAccepted(true) } })
            return@GridFixTheme
        }

        // Subscription gate. Debug builds (sideloaded field-test APKs) always
        // run unlocked; the Play release build requires GridFix Pro.
        if (paywallPreview) {
            PaywallScreen(billing, onClose = { paywallPreview = false })
            return@GridFixTheme
        }
        if (!BuildConfig.DEBUG) {
            when (entitlement) {
                BillingManager.State.CHECKING -> {
                    Box(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Checking your subscription…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    return@GridFixTheme
                }
                BillingManager.State.LOCKED -> {
                    PaywallScreen(billing)
                    return@GridFixTheme
                }
                BillingManager.State.ENTITLED -> Unit
            }
        }
        val onSubScreen = currentRoute == "settings" || currentRoute == "reference"
        val barActions: @Composable () -> Unit = {
            OrientationLockButton(settings.orientation) { value -> scope.launch { repo.setOrientation(value) } }
            IconButton(onClick = { scope.launch { repo.setNightMode(!settings.nightMode) } }) {
                Icon(
                    if (settings.nightMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = "Toggle night mode",
                )
            }
            if (!onSubScreen) {
                IconButton(onClick = {
                    navController.navigate("settings") { launchSingleTop = true }
                }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            }
        }
        val barTitle: @Composable () -> Unit = {
            Text(
                "MGRS GPS",
                fontFamily = LabelFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 4.sp,
                maxLines = 1,
            )
        }
        val backButton: @Composable () -> Unit = {
            if (onSubScreen) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // Sideways, a camera cutout sits on a long edge: keep the rail and the deck clear of it
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
            topBar = {
                if (landscape) {
                    // Sideways there is no vertical room to spare: a 48 dp bar
                    CompactTopBar(title = barTitle, navigationIcon = backButton, actions = barActions)
                } else {
                    CenterAlignedTopAppBar(
                        title = barTitle,
                        navigationIcon = backButton,
                        actions = { barActions() },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            },
            bottomBar = {
                // Blackout bar: black, a hairline on top, amber for the active tab,
                // condensed capitals - no indicator pill. Sideways it becomes a rail.
                if (!landscape) {
                    val rule = MaterialTheme.colorScheme.outline
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.drawBehind {
                            drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        },
                    ) {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = { goTo(item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = {
                                    Text(
                                        item.label.uppercase(),
                                        fontFamily = LabelFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.6.sp,
                                        maxLines = 1,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
            if (landscape) {
                val rule = MaterialTheme.colorScheme.outline
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.background,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier
                        .fillMaxHeight()
                        .drawBehind {
                            drawLine(rule, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                        },
                ) {
                    Spacer(Modifier.weight(1f))
                    items.forEach { item ->
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = { goTo(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = {
                                Text(
                                    item.label.uppercase(),
                                    fontFamily = LabelFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            NavHost(
                navController = navController,
                startDestination = "position",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                composable("position") {
                    if (!hasPermission) PermissionGate(requestPermission, approximateOnly)
                    else PositionScreen(
                        fix = fix,
                        settings = settings,
                        repo = repo,
                        onMark = {
                            fix.location?.let { loc ->
                                scope.launch {
                                    val id = waypointRepo.add(
                                        WaypointDraft(
                                            name = "MARK " + Coordinates.dtg(System.currentTimeMillis()).take(7),
                                            lat = loc.latitude,
                                            lon = loc.longitude,
                                            folder = DEFAULT_FOLDER,
                                            symbol = "target",
                                            affiliation = "none",
                                        ),
                                        System.currentTimeMillis(),
                                    )
                                    waypointRepo.select(id)
                                }
                            }
                        },
                    )
                }
                composable("navigate") {
                    if (!hasPermission) PermissionGate(requestPermission, approximateOnly)
                    else NavigateScreen(
                        fix = fix,
                        settings = settings,
                        waypoints = navigableWaypoints,
                        selectedId = selectedId,
                        onSelect = { id -> scope.launch { waypointRepo.select(id) } },
                    )
                }
                composable("map") {
                    MapScreen(
                        fix = fix,
                        settings = settings,
                        waypoints = waypoints,
                        folders = folders,
                        hasPermission = hasPermission,
                        onRequestPermission = requestPermission,
                        onAdd = { draft ->
                            scope.launch { waypointRepo.add(draft, System.currentTimeMillis()) }
                        },
                        onUpdate = { id, draft ->
                            scope.launch { waypointRepo.update(id, draft) }
                        },
                        onNavigateTo = { id ->
                            scope.launch { waypointRepo.select(id) }
                            goTo("navigate")
                        },
                        graphics = graphics,
                        onAddGraphic = { name, type, points, folder, affiliation, echelon ->
                            scope.launch {
                                // addFolder returns the stored spelling (case-insensitive match) and
                                // makes sure the overlay eye toggle exists
                                val f = waypointRepo.addFolder(folder)
                                graphicsRepo.add(name, type, points, f, affiliation, System.currentTimeMillis(), echelon)
                            }
                        },
                        onUpdateGraphic = { id, name, folder, affiliation, echelon ->
                            scope.launch {
                                val f = waypointRepo.addFolder(folder)
                                graphicsRepo.rename(id, name, f, affiliation, echelon)
                            }
                        },
                        onUpdateGraphicPoints = { id, points ->
                            scope.launch { graphicsRepo.updatePoints(id, points) }
                        },
                        onDeleteGraphic = { id -> scope.launch { graphicsRepo.delete(id) } },
                        onSaveRouteWps = { base, pts, folder ->
                            scope.launch {
                                val f = waypointRepo.addFolder(folder)
                                val prefix = "$base WP "
                                waypoints.filter { it.name.startsWith(prefix) }.forEach {
                                    waypointRepo.delete(it.id)
                                }
                                waypointRepo.addAll(
                                    pts.mapIndexed { i, v ->
                                        WaypointDraft(
                                            name = prefix + (i + 1),
                                            lat = v.lat,
                                            lon = v.lon,
                                            folder = f,
                                            symbol = "",
                                            affiliation = "none",
                                        )
                                    },
                                    System.currentTimeMillis(),
                                )
                            }
                        },
                        viewedTrackId = shownTrackId,
                        onRecordStart = {
                            if (recordGate.compareAndSet(false, true)) {
                                scope.launch {
                                    val id = trackRepo.startTrack(System.currentTimeMillis())
                                    val ok = runCatching {
                                        TrackRecorderService.start(context.applicationContext, id)
                                    }.isSuccess
                                    if (!ok) trackRepo.discard(id)
                                    recordGate.set(false)
                                }
                            }
                        },
                        onRecordStop = { name, discard ->
                            val id = TrackRecorderService.active.value?.trackId
                            TrackRecorderService.stop(context.applicationContext)
                            if (id != null) {
                                scope.launch {
                                    kotlinx.coroutines.delay(400)
                                    if (discard) {
                                        trackRepo.discard(id)
                                    } else {
                                        trackRepo.finishTrack(id, name, System.currentTimeMillis())
                                    }
                                }
                            }
                        },
                        focusAt = mapFocus,
                        onFocusConsumed = { mapFocus = null },
                        unitNameFor = unitNameFor,
                        courseStatus = activeCourse?.takeIf { !it.done }?.let { c ->
                            "CP ${c.nextIndex + 1}/${c.waypointIds.size} — course running"
                        },
                        onOpenCourse = { courseOpen = true },
                    )
                }
                composable("waypoints") {
                    WaypointsScreen(
                        fix = fix,
                        settings = settings,
                        waypoints = waypoints,
                        folders = folders,
                        onAdd = { draft ->
                            scope.launch { waypointRepo.add(draft, System.currentTimeMillis()) }
                        },
                        onUpdate = { id, draft ->
                            scope.launch { waypointRepo.update(id, draft) }
                        },
                        onDelete = { id -> scope.launch { waypointRepo.delete(id) } },
                        onAddFolder = { name -> scope.launch { waypointRepo.addFolder(name) } },
                        onSetFolderVisible = { name, visible ->
                            scope.launch { waypointRepo.setFolderVisible(name, visible) }
                        },
                        onRenameFolder = { from, to ->
                            scope.launch {
                                val target = waypointRepo.renameFolder(from, to)
                                if (target != from) {
                                    graphicsRepo.renameFolder(from, target)
                                    trackRepo.renameFolder(from, target)
                                }
                            }
                        },
                        onDeleteFolder = { name, deleteContents ->
                            scope.launch {
                                if (deleteContents) {
                                    if (tracks.any { it.id == viewedTrackId && it.folder == name }) viewedTrackId = null
                                    waypointRepo.deleteFolder(name, true)
                                    graphicsRepo.deleteFolder(name)
                                    trackRepo.deleteFolder(name)
                                } else {
                                    waypointRepo.deleteFolder(name, false)
                                    graphicsRepo.renameFolder(name, DEFAULT_FOLDER)
                                    trackRepo.renameFolder(name, DEFAULT_FOLDER)
                                }
                            }
                        },
                        onNavigateTo = { id ->
                            scope.launch { waypointRepo.select(id) }
                            goTo("navigate")
                        },
                        graphics = graphics,
                        onDeleteGraphic = { id -> scope.launch { graphicsRepo.delete(id) } },
                        onClearGraphics = { folder -> scope.launch { graphicsRepo.deleteFolder(folder) } },
                        tracks = tracks,
                        viewedTrackId = shownTrackId,
                        onViewTrack = { id ->
                            viewedTrackId = id
                            if (id != null) {
                                // Viewing a track in a hidden folder shows the folder again
                                val f = tracks.firstOrNull { it.id == id }?.folder
                                if (f != null && folders.firstOrNull { it.name == f }?.visible == false) {
                                    scope.launch { waypointRepo.setFolderVisible(f, true) }
                                }
                                goTo("map")
                            }
                        },
                        onDeleteTrack = { id ->
                            if (viewedTrackId == id) viewedTrackId = null
                            scope.launch { trackRepo.delete(id) }
                        },
                        onMoveTrack = { id, folder ->
                            scope.launch {
                                trackRepo.setFolder(id, waypointRepo.addFolder(folder))
                            }
                        },
                        onBacktrackTrack = { t ->
                            scope.launch {
                                val pts = TrackRepository.readPoints(context, t.id)
                                if (pts.size >= 2) {
                                    val reversed = pts.reversed()
                                    // Keep the shape, not every twentieth fix: a uniform
                                    // stride cuts the corner off every switchback and
                                    // ridgeline, which is exactly what you are following
                                    // back. Douglas-Peucker at 20 m keeps the turns and
                                    // drops the straights, then cap at the vertex limit.
                                    val dec = ArrayList(
                                        simplifyTrack(reversed.map { GeoVertex(it.lat, it.lon) }, 20.0)
                                    )
                                    val last = reversed.last()
                                    if (dec.last().lat != last.lat || dec.last().lon != last.lon) {
                                        dec.add(GeoVertex(last.lat, last.lon))
                                    }
                                    waypointRepo.addFolder(t.folder)
                                    graphicsRepo.add(
                                        ("Back " + t.name).take(20), "route",
                                        dec.take(64), t.folder, "none",
                                        System.currentTimeMillis(),
                                    )
                                    mapFocus = dec.first().lat to dec.first().lon
                                    goTo("map")
                                }
                            }
                        },
                        onShowOnMap = { w ->
                            mapFocus = w.lat to w.lon
                            goTo("map")
                        },
                        onShowGraphicOnMap = { g ->
                            if (g.points.isNotEmpty()) {
                                mapFocus = g.points.map { it.lat }.average() to
                                    g.points.map { it.lon }.average()
                                goTo("map")
                            }
                        },
                        unitNameFor = unitNameFor,
                        onImport = { data, onDone ->
                            scope.launch {
                                runCatching {
                                    val now = System.currentTimeMillis()
                                    waypointRepo.addAll(data.waypoints, now)
                                    for (l in data.lines) {
                                        val f = waypointRepo.addFolder(l.folder)
                                        graphicsRepo.add(l.name, "route", l.points, f, "none", now)
                                    }
                                    for (a in data.areas) {
                                        val f = waypointRepo.addFolder(a.folder)
                                        graphicsRepo.add(a.name, "aa", a.points, f, "none", now)
                                    }
                                    for (t in data.tracks) {
                                        trackRepo.importTrack(t.name, t.points, now)
                                    }
                                    onDone(data.summary())
                                }.getOrElse {
                                    onDone("Import failed — " + (it.message ?: "the file has bad data"))
                                }
                            }
                        },
                        onExport = { format, onDone ->
                            scope.launch {
                                runCatching {
                                    val trackData = tracks.map {
                                        it to TrackRepository.readPoints(context, it.id)
                                    }
                                    val fname = when (format) {
                                        "atak" -> "gridfix-datapackage.zip"
                                        else -> "gridfix-export.$format"
                                    }
                                    val file = withContext(Dispatchers.IO) {
                                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                                        File(dir, fname).apply {
                                            when (format) {
                                                "atak" -> outputStream().use { os ->
                                                    DataPackage.build(
                                                        os,
                                                        waypoints,
                                                        graphics.filter { it.type == "route" },
                                                        System.currentTimeMillis(),
                                                    )
                                                }
                                                "gpx" -> writeText(
                                                    InterchangeFiles.buildGpx(
                                                        waypoints,
                                                        graphics.filter { it.type == "route" },
                                                        trackData,
                                                    )
                                                )
                                                else -> writeText(
                                                    InterchangeFiles.buildKml(waypoints, graphics, trackData)
                                                )
                                            }
                                        }
                                    }
                                    val uri = FileProvider.getUriForFile(
                                        context, "app.gridfix.android.fileprovider", file
                                    )
                                    val send = android.content.Intent(
                                        android.content.Intent.ACTION_SEND
                                    ).apply {
                                        type = when (format) {
                                            "gpx" -> "application/gpx+xml"
                                            "atak" -> "application/zip"
                                            else -> "application/vnd.google-earth.kml+xml"
                                        }
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "Share export")
                                    )
                                    onDone(fname)
                                }.getOrElse { onDone(null) }
                            }
                        },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        repo,
                        settings,
                        modelDeclination = fix.location?.let { Declination.model(it.latitude, it.longitude) },
                        entitled = entitlement == BillingManager.State.ENTITLED,
                        onPreviewPaywall = { paywallPreview = true },
                        onOpenReference = {
                            navController.navigate("reference") { launchSingleTop = true }
                        },
                        onBackup = { uri, onDone ->
                            scope.launch {
                                runCatching {
                                    context.contentResolver.openOutputStream(uri)?.use { os ->
                                        Backup.export(
                                            context, os,
                                            waypoints, folders, graphics, settings,
                                            tracks, courseHistory,
                                            System.currentTimeMillis(),
                                        )
                                    }
                                    onDone(
                                        "Backed up ${waypoints.size} waypoints, ${graphics.size} graphics, ${tracks.size} tracks"
                                    )
                                }.getOrElse { onDone("Backup failed — ${it.message ?: "couldn't write the file"}") }
                            }
                        },
                        onRestore = { uri, onDone ->
                            scope.launch {
                                runCatching {
                                    val result = context.contentResolver.openInputStream(uri)?.use { ins ->
                                        Backup.restore(
                                            ins,
                                            waypointRepo, graphicsRepo, trackRepo, repo, courseRepo,
                                        )
                                    }
                                    onDone(result?.summary() ?: "Couldn't open that file")
                                }.getOrElse { onDone("Restore failed — is this an MGRS GPS backup zip?") }
                            }
                        },
                    )
                }
                composable("reference") { ReferenceScreen() }
            }
            }
        }

        if (courseOpen) {
            CourseDialog(
                active = activeCourse,
                waypoints = waypoints,
                folders = folders,
                history = courseHistory,
                hasFix = fix.location != null,
                onStartFolder = { f ->
                    scope.launch {
                        val ids = waypoints.filter { it.folder == f }.map { it.id }
                        if (ids.size >= 2) {
                            courseRepo.start(f, ids, System.currentTimeMillis())
                            ids.firstOrNull()?.let { waypointRepo.select(it) }
                        }
                    }
                },
                onStartRandom = { count, radiusM ->
                    val loc = fix.location
                    if (loc != null) {
                        scope.launch {
                            val folderName =
                                "Course " + Coordinates.dtg(System.currentTimeMillis()).take(7)
                            // The folder is created only once points exist: a tight radius
                            // can fail to place them, and an empty "Course DDHHMM" folder
                            // would be left behind on every failed attempt.
                            val pts = mutableListOf<Pair<Double, Double>>()
                            var attempts = 0
                            while (pts.size < count && attempts < 400) {
                                attempts++
                                val dist = 120.0 + Random.nextDouble() *
                                    (radiusM - 120.0).coerceAtLeast(1.0)
                                val brg = Random.nextDouble() * 360.0
                                val p = GeoPoint(loc.latitude, loc.longitude)
                                    .destinationPoint(dist, brg)
                                val separated = pts.all { (la, lo) ->
                                    Coordinates.navInfo(la, lo, p.latitude, p.longitude)
                                        .distanceMeters > 120f
                                }
                                if (separated) pts.add(p.latitude to p.longitude)
                            }
                            if (pts.size < 2) return@launch
                            waypointRepo.addFolder(folderName)
                            val ids = mutableListOf<String>()
                            pts.forEachIndexed { i, (la, lo) ->
                                ids.add(
                                    waypointRepo.add(
                                        WaypointDraft(
                                            name = "CP ${i + 1}",
                                            lat = la,
                                            lon = lo,
                                            folder = folderName,
                                            symbol = "target",
                                            affiliation = "none",
                                        ),
                                        System.currentTimeMillis(),
                                    )
                                )
                            }
                            if (ids.size >= 2) {
                                courseRepo.start(folderName, ids, System.currentTimeMillis())
                                ids.firstOrNull()?.let { waypointRepo.select(it) }
                            }
                        }
                    }
                },
                onAbandon = {
                    scope.launch { courseRepo.abandon() }
                    courseOpen = false
                },
                onDismiss = { courseOpen = false },
            )
        }
        courseSummary?.let { r ->
            CourseSummaryDialog(result = r) { courseSummary = null }
        }

        // Course engine: lock Navigate onto the current point; auto-advance and
        // buzz when the fix closes inside 25 m (accuracy permitting).
        LaunchedEffect(activeCourse?.nextIndex, activeCourse?.name) {
            val c = activeCourse ?: return@LaunchedEffect
            if (!c.done) {
                c.waypointIds.getOrNull(c.nextIndex)?.let { waypointRepo.select(it) }
            }
        }
        LaunchedEffect(
            fix.location?.latitude,
            fix.location?.longitude,
            activeCourse?.nextIndex,
            activeCourse?.name,
        ) {
            val c = activeCourse ?: return@LaunchedEffect
            val loc = fix.location ?: return@LaunchedEffect
            if (c.done) return@LaunchedEffect
            val target = waypoints.firstOrNull { it.id == c.waypointIds[c.nextIndex] }
                ?: return@LaunchedEffect
            val nav = Coordinates.navInfo(loc.latitude, loc.longitude, target.lat, target.lon)
            // "Inside 25 m" has to mean it: the fix's own error must fit inside the ring,
            // and a fix with no accuracy estimate at all cannot score a point.
            val acc = if (loc.hasAccuracy()) loc.accuracy else Float.POSITIVE_INFINITY
            if (nav.distanceMeters + acc < 25f) {
                courseRepo.markFound(System.currentTimeMillis())
                buzz(context)
                if (c.foundAt.size + 1 >= c.waypointIds.size) {
                    courseRepo.finish()
                    summaryPending = true
                }
            }
        }
        LaunchedEffect(courseHistory, summaryPending) {
            if (summaryPending && courseHistory.isNotEmpty()) {
                courseSummary = courseHistory.first()
                summaryPending = false
            }
        }
    }
}

/** A 48 dp app bar for landscape: title centred, back on the left, actions on the right. */
@Composable
private fun CompactTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .height(48.dp),
    ) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) { navigationIcon() }
        }
        Box(Modifier.align(Alignment.Center)) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            ) { title() }
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) { actions() }
        }
    }
}

/**
 * The rotate-lock action: amber when the orientation is pinned. Tap for the menu:
 * AUTO follows the phone; PORTRAIT / LANDSCAPE / LANDSCAPE FLIPPED pin it, for
 * a phone mounted sideways on armour either way up.
 */
@Composable
private fun OrientationLockButton(current: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val locked = current != ScreenOrientation.AUTO
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                if (locked) Icons.Outlined.ScreenLockRotation else Icons.Outlined.ScreenRotation,
                contentDescription = if (locked) "Orientation locked: ${ScreenOrientation.names[current]}" else "Orientation follows the phone",
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScreenOrientation.menuNames.forEachIndexed { index, label ->
                val sel = index == current
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontFamily = LabelFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.4.sp,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = if (sel) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        onSelect(index)
                        open = false
                    },
                )
            }
        }
    }
}

private fun buzz(context: android.content.Context) {
    runCatching {
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                as android.os.Vibrator
        }
        vib.vibrate(
            android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1)
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit, approximateOnly: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.MyLocation,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (approximateOnly) "Precise location needed" else "Location access needed",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (approximateOnly) {
                "Only approximate location was allowed. A grid readout needs the GPS chip — " +
                    "choose \"Precise\" when asked, or switch it on in the app's permission settings."
            } else {
                "MGRS GPS reads your position straight from the GPS chip. Everything stays on your phone — no account, no tracking, no internet needed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text(if (approximateOnly) "Allow precise location" else "Grant location access")
        }
        if (approximateOnly) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }
            }) { Text("Open app settings") }
        }
    }
}
