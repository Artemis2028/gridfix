package app.gridfix.android.ui.screens

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gridfix.android.data.reservedFolderHint
import app.gridfix.android.location.Declination
import app.gridfix.android.ui.isLandscape
import app.gridfix.android.ui.theme.LabelFamily
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Coordinates
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.FolderInfo
import app.gridfix.android.data.GeoVertex
import app.gridfix.android.data.GraphicTypes
import app.gridfix.android.data.KIND_UNIT
import app.gridfix.android.data.MapPrefs
import app.gridfix.android.data.MapPrefsData
import app.gridfix.android.data.TacGraphic
import app.gridfix.android.data.TrackRepository
import app.gridfix.android.data.Waypoint
import app.gridfix.android.data.WaypointDraft
import app.gridfix.android.location.FixData
import app.gridfix.android.location.TrackRecorderService
import app.gridfix.android.map.ContourOverlay
import app.gridfix.android.map.ControlMeasuresOverlay
import app.gridfix.android.map.Elevation
import app.gridfix.android.map.MapSetup
import app.gridfix.android.map.MgrsGridOverlay
import app.gridfix.android.map.TracksOverlay
import app.gridfix.android.ui.Affiliations
import app.gridfix.android.ui.DeclinationDialog
import app.gridfix.android.ui.FieldToolsChooser
import app.gridfix.android.ui.RayFixDialog
import app.gridfix.android.ui.RouteCardDialog
import app.gridfix.android.ui.SunMoonDialog
import app.gridfix.android.ui.WaypointDialog
import app.gridfix.android.ui.WaypointMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import java.io.File
import kotlin.math.hypot

/**
 * Geo → screen position for Compose overlays: projection pixels plus the map's
 * COMMITTED rotation only. (Projection.rotateAndScalePoint also bakes in the
 * transient pinch-zoom scale, which made markers swim during gestures.)
 */
private fun toScreenPoint(
    map: MapView,
    proj: org.osmdroid.views.Projection,
    gp: GeoPoint,
    out: android.graphics.Point,
) {
    proj.toPixels(gp, out)
    val deg = map.mapOrientation
    if (deg != 0f) {
        val rad = Math.toRadians(deg.toDouble())
        val cx = map.width / 2.0
        val cy = map.height / 2.0
        val dx = out.x - cx
        val dy = out.y - cy
        val c = Math.cos(rad)
        val s = Math.sin(rad)
        out.x = (cx + dx * c - dy * s).toInt()
        out.y = (cy + dx * s + dy * c).toInt()
    }
}

/** Perimeter (m) and enclosed area (m²) of a polygon, computed on the UTM plane. */
private fun polygonStats(points: List<GeoVertex>): Pair<Double, Double> {
    if (points.size < 2) return 0.0 to 0.0
    val zone = (((points[0].lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
    val north = points[0].lat >= 0.0
    val en = points.map { Coordinates.utmForZone(it.lat, it.lon, zone, north) }
    var perim = 0.0
    var area2 = 0.0
    for (i in en.indices) {
        val j = (i + 1) % en.size
        perim += hypot(en[j][0] - en[i][0], en[j][1] - en[i][1])
        area2 += en[i][0] * en[j][1] - en[j][0] * en[i][1]
    }
    return perim to kotlin.math.abs(area2) / 2.0
}

private fun formatArea(m2: Double): String = when {
    m2 < 10_000.0 -> String.format(java.util.Locale.US, "%.0f m²", m2)
    m2 < 1_000_000.0 -> String.format(java.util.Locale.US, "%.1f ha", m2 / 10_000.0)
    else -> String.format(java.util.Locale.US, "%.2f km²", m2 / 1_000_000.0)
}

private fun formatDist(m: Double): String =
    if (m < 1000.0) String.format(java.util.Locale.US, "%.0f m", m)
    else String.format(java.util.Locale.US, "%.2f km", m / 1000.0)

/** Mutable references shared between the AndroidView factory callbacks and Compose. */
private class MapHolder {
    var map: MapView? = null
    var grid: MgrsGridOverlay? = null
    var cm: ControlMeasuresOverlay? = null
    var tracks: TracksOverlay? = null
    var hillshade: org.osmdroid.views.overlay.TilesOverlay? = null
    var contours: ContourOverlay? = null
    var viewshed: app.gridfix.android.map.ViewshedOverlay? = null
    var visibleWps: List<Waypoint> = emptyList()
    var appliedLayer = ""
    var appliedNight: Boolean? = null
    var appliedGrid: Boolean? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MapScreen(
    fix: FixData,
    settings: AppSettings,
    waypoints: List<Waypoint>,
    folders: List<FolderInfo>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onAdd: (WaypointDraft) -> Unit,
    onUpdate: (id: String, draft: WaypointDraft) -> Unit,
    onNavigateTo: (String) -> Unit,
    graphics: List<TacGraphic>,
    onAddGraphic: (name: String, type: String, points: List<GeoVertex>, folder: String, affiliation: String, echelon: String) -> Unit,
    onUpdateGraphic: (id: String, name: String, folder: String, affiliation: String, echelon: String) -> Unit,
    onUpdateGraphicPoints: (id: String, points: List<GeoVertex>) -> Unit,
    onDeleteGraphic: (String) -> Unit,
    onSaveRouteWps: (base: String, points: List<GeoVertex>, folder: String) -> Unit = { _, _, _ -> },
    viewedTrackId: String?,
    onRecordStart: () -> Unit,
    onRecordStop: (name: String?, discard: Boolean) -> Unit,
    focusAt: Pair<Double, Double>?,
    onFocusConsumed: () -> Unit,
    unitNameFor: (symbol: String, echelon: String) -> String,
    courseStatus: String? = null,
    onOpenCourse: () -> Unit = {},
) {
    val context = LocalContext.current
    remember { MapSetup.init(context.applicationContext); true }
    val mapPrefs = remember { MapPrefs(context.applicationContext) }
    val prefsOrNull by mapPrefs.prefs.collectAsStateWithLifecycle(initialValue = null as MapPrefsData?)
    val p = prefsOrNull ?: return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val holder = remember { MapHolder() }

    // Waypoints and graphics in visible folders are drawn on the map
    val visibleWaypoints = remember(waypoints, folders) {
        if (folders.isEmpty()) waypoints else {
            val visible = folders.filter { it.visible }.map { it.name }.toSet()
            waypoints.filter { it.folder in visible }
        }
    }
    val visibleGraphics = remember(graphics, folders) {
        if (folders.isEmpty()) graphics else {
            val visible = folders.filter { it.visible }.map { it.name }.toSet()
            val known = folders.map { it.name }.toSet()
            graphics.filter { it.folder in visible || it.folder !in known }
        }
    }

    var cameraTick by remember { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var rulerAnchor by remember { mutableStateOf<GeoPoint?>(null) }
    var drawType by remember { mutableStateOf<String?>(null) }
    var drawAffiliation by remember { mutableStateOf("none") }
    var drawPoints by remember { mutableStateOf<List<GeoVertex>>(emptyList()) }
    var drawPickerOpen by remember { mutableStateOf(false) }
    var drawNameOpen by remember { mutableStateOf(false) }
    var measureOpen by remember { mutableStateOf(false) }
    var editingGraphic by remember { mutableStateOf<TacGraphic?>(null) }
    var editPointsFor by remember { mutableStateOf<TacGraphic?>(null) }
    var editPts by remember { mutableStateOf<List<GeoVertex>>(emptyList()) }
    var routeCardFor by remember { mutableStateOf<TacGraphic?>(null) }
    var routeWpOffer by remember { mutableStateOf<Triple<String, List<GeoVertex>, String>?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }
    var stopTrackOpen by remember { mutableStateOf(false) }
    var fieldToolsOpen by remember { mutableStateOf(false) }
    var fieldTool by remember { mutableStateOf<String?>(null) }
    var viewshedOn by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var viewedPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    val activeTrack by TrackRecorderService.active.collectAsStateWithLifecycle()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRecordStart() }

    LaunchedEffect(viewedTrackId) {
        viewedPoints = if (viewedTrackId == null) emptyList() else {
            TrackRepository.readPoints(context, viewedTrackId).map { it.lat to it.lon }
        }
    }

    // Crosshair elevation, refreshed shortly after the map stops moving
    var crossElevation by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(cameraTick) {
        delay(350)
        val c = holder.map?.mapCenter ?: return@LaunchedEffect
        crossElevation = Elevation.elevationAt(context, c.latitude, c.longitude)
    }
    var newWpAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var editingWp by remember { mutableStateOf<Waypoint?>(null) }
    var infoWp by remember { mutableStateOf<Waypoint?>(null) }
    var qrWp by remember { mutableStateOf<Waypoint?>(null) }
    var layersOpen by remember { mutableStateOf(false) }
    var downloadOpen by remember { mutableStateOf(false) }
    var gridInterval by remember { mutableStateOf("") }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var readoutHeightPx by remember { mutableIntStateOf(0) }
    val readoutHeightDp = with(LocalDensity.current) { readoutHeightPx.toDp() }
    var mbtilesFiles by remember { mutableStateOf(listMbtiles(context)) }

    val offlineName = if (p.baseLayer.startsWith("mbtiles:")) p.baseLayer.removePrefix("mbtiles:") else null
    val offlineFile = offlineName?.let { File(MapSetup.mbtilesDir(context), it) }?.takeIf { it.exists() }
    val layer = MapSetup.layerFor(p.baseLayer)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = importMbtiles(context, uri)
                mbtilesFiles = listMbtiles(context)
                importMessage = result.second
                if (result.first != null) {
                    mapPrefs.setBaseLayer("mbtiles:${result.first}")
                }
            }
        }
    }

    val landscape = isLandscape()
    // The seven tools and the amber action, shared by the bottom deck (portrait)
    // and the right-hand rail (worn sideways)
    val deckTools = listOf(
        DeckTool(Icons.Outlined.Layers, "Layers", false) { layersOpen = true },
        DeckTool(Icons.Outlined.MyLocation, "Locate", following) {
            if (!hasPermission) {
                onRequestPermission()
            } else {
                following = true
                fix.location?.let { loc ->
                    holder.map?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        },
        DeckTool(Icons.Outlined.Straighten, "Ruler", rulerAnchor != null) {
            rulerAnchor = if (rulerAnchor == null) {
                holder.map?.mapCenter?.let { GeoPoint(it.latitude, it.longitude) }
            } else null
        },
        DeckTool(Icons.Outlined.Timeline, "Draw", drawType != null) {
            if (drawType == null) drawPickerOpen = true
        },
        DeckTool(Icons.Outlined.Search, "Go to", false) { gotoOpen = true },
        DeckTool(Icons.Outlined.Calculate, "Tools", fieldTool != null) { fieldToolsOpen = true },
        DeckTool(Icons.Outlined.FiberManualRecord, if (activeTrack != null) "Stop" else "Record", activeTrack != null) {
            if (activeTrack != null) {
                stopTrackOpen = true
            } else if (!hasPermission) {
                onRequestPermission()
            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onRecordStart()
            }
        },
    )
    val addWaypointAtCrosshair: () -> Unit = {
        holder.map?.mapCenter?.let { c -> newWpAt = c.latitude to c.longitude }
    }

    Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxHeight()) {
        key(offlineFile?.absolutePath ?: "online") {
            var mapView by remember { mutableStateOf<MapView?>(null) }

            androidx.compose.ui.viewinterop.AndroidView(
                // The activity handles orientation itself (manifest configChanges), so a
                // rotation recreates nothing: Compose re-lays-out around this native view,
                // but the MapView keeps the geometry it was last measured at and paints at
                // it - over the navigation rail on one side, short of the window on the
                // other - until a touch happens to force a pass. That is what made the rail
                // "invisible until you tap where a button would be". clipToBounds keeps the
                // map inside its slot whatever size it thinks it is; the size callback makes
                // it re-measure and repaint the moment the slot changes.
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged {
                        holder.map?.let { m ->
                            m.requestLayout()
                            m.invalidate()
                        }
                    },
                onRelease = { view ->
                    // Runs for the exact MapView leaving the composition. When the
                    // layer key changes, the new map may already be in the holder —
                    // never detach or null that one.
                    view.onDetach()
                    if (holder.map === view) {
                        holder.map = null
                        holder.grid = null
                        holder.cm = null
                        holder.tracks = null
                        holder.hillshade = null
                        holder.contours = null
                        holder.viewshed = null
                        holder.appliedLayer = ""
                        holder.appliedNight = null
                        holder.appliedGrid = null
                    }
                },
                factory = { ctx ->
                    val map = MapView(ctx)
                    map.setMultiTouchControls(true)
                    map.isTilesScaledToDpi = true
                    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    map.setMinZoomLevel(3.0)
                    map.setMaxZoomLevel(21.5)
                    if (offlineFile != null) {
                        try {
                            val provider = OfflineTileProvider(SimpleRegisterReceiver(ctx), arrayOf(offlineFile))
                            map.tileProvider = provider
                            val sourceName = provider.archives.firstOrNull()?.tileSources?.firstOrNull()
                            if (sourceName != null) {
                                map.setTileSource(FileBasedTileSource.getSource(sourceName))
                            } else {
                                map.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
                            }
                            map.setUseDataConnection(false)
                        } catch (e: Exception) {
                            map.setTileSource(MapSetup.layerFor("topo").source)
                        }
                    } else {
                        map.setTileSource(MapSetup.layerFor(p.baseLayer).source)
                        holder.appliedLayer = p.baseLayer
                    }
                    val grid = MgrsGridOverlay(ctx.resources.displayMetrics.density)
                    grid.onIntervalLabel = { label -> gridInterval = label }
                    holder.grid = grid
                    map.overlays.add(
                        MapEventsOverlay(object : MapEventsReceiver {
                            /** Route vertices snap onto nearby visible waypoints. */
                            fun snappedVertex(gp: GeoPoint): GeoVertex {
                                if (drawType != "route") return GeoVertex(gp.latitude, gp.longitude)
                                val m = holder.map ?: return GeoVertex(gp.latitude, gp.longitude)
                                val tapPx = android.graphics.Point()
                                val wpPx = android.graphics.Point()
                                m.projection.toPixels(gp, tapPx)
                                val thresh = 30f * m.context.resources.displayMetrics.density
                                var best: Waypoint? = null
                                var bestD = thresh
                                for (w in holder.visibleWps) {
                                    m.projection.toPixels(GeoPoint(w.lat, w.lon), wpPx)
                                    val d = hypot(
                                        (tapPx.x - wpPx.x).toFloat(),
                                        (tapPx.y - wpPx.y).toFloat(),
                                    )
                                    if (d < bestD) {
                                        bestD = d
                                        best = w
                                    }
                                }
                                val hit = best ?: return GeoVertex(gp.latitude, gp.longitude)
                                notice = "Added ${hit.name} to route"
                                return GeoVertex(hit.lat, hit.lon)
                            }

                            /** Fixed-tap types (ring, sector, points, text) finish themselves. */
                            fun autoFinish() {
                                val dt = drawType ?: return
                                val fp = GraphicTypes.fixedPoints(dt) ?: return
                                if (drawPoints.size >= fp) drawNameOpen = true
                            }

                            override fun singleTapConfirmedHelper(gp: GeoPoint?): Boolean {
                                if (gp == null) return false
                                if (editPointsFor != null) return false
                                if (drawType != null) {
                                    drawPoints = drawPoints + snappedVertex(gp)
                                    holder.map?.invalidate()
                                    autoFinish()
                                    return true
                                }
                                if (rulerAnchor != null) {
                                    rulerAnchor = gp
                                    return true
                                }
                                // Tap near a control-measure graphic opens its editor
                                val m = holder.map
                                val cmo = holder.cm
                                if (m != null && cmo != null) {
                                    val px = android.graphics.Point()
                                    m.projection.toPixels(gp, px)
                                    val thresh = 26f * m.context.resources.displayMetrics.density
                                    var best: TacGraphic? = null
                                    var bestD = thresh
                                    for (g in cmo.graphics) {
                                        val d = cmo.distanceToGraphic(
                                            m.projection, g, px.x.toFloat(), px.y.toFloat()
                                        )
                                        if (d < bestD) {
                                            bestD = d
                                            best = g
                                        }
                                    }
                                    if (best != null) {
                                        editingGraphic = best
                                        return true
                                    }
                                }
                                return false
                            }

                            override fun longPressHelper(gp: GeoPoint?): Boolean {
                                if (gp != null) {
                                    if (drawType != null) {
                                        drawPoints = drawPoints + snappedVertex(gp)
                                        holder.map?.invalidate()
                                        autoFinish()
                                    } else {
                                        newWpAt = gp.latitude to gp.longitude
                                    }
                                    return true
                                }
                                return false
                            }
                        })
                    )
                    map.overlays.add(grid)
                    val cm = ControlMeasuresOverlay(ctx.resources.displayMetrics.density)
                    holder.cm = cm
                    map.overlays.add(cm)
                    val trk = TracksOverlay(ctx.resources.displayMetrics.density)
                    holder.tracks = trk
                    map.overlays.add(trk)
                    // Hybrid terrain: shadow-only hillshade, inserted under the grid on demand
                    val hsProvider = org.osmdroid.tileprovider.MapTileProviderBasic(ctx, MapSetup.hillshadeSource)
                    val hs = org.osmdroid.views.overlay.TilesOverlay(hsProvider, ctx).apply {
                        setColorFilter(MapSetup.hillshadeShadowFilter)
                        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                        loadingLineColor = android.graphics.Color.TRANSPARENT
                        isEnabled = false
                    }
                    // A second provider does not know the map's redraw handler by itself;
                    // without this, finished hillshade tiles wait for the next unrelated redraw.
                    runCatching { hsProvider.tileRequestCompleteHandlers.add(map.tileRequestCompleteHandler) }
                    holder.hillshade = hs
                    map.overlays.add(1, hs)   // under the grid; events overlay stays first
                    // Contours from cached elevation, drawn above shading but under the grid
                    val cont = ContourOverlay(
                        ctx.applicationContext,
                        ctx.resources.displayMetrics.density,
                        scope,
                    ) { map.postInvalidate() }
                    holder.contours = cont
                    map.overlays.add(2, cont)
                    val vs = app.gridfix.android.map.ViewshedOverlay()
                    holder.viewshed = vs
                    map.overlays.add(3, vs)
                    // Two-finger map rotation; north-reset button appears when turned
                    val rot = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(map)
                    rot.isEnabled = true
                    map.overlays.add(rot)
                    map.addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            cameraTick++
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            cameraTick++
                            return false
                        }
                    })
                    map.setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                following = false
                                v.performClick()
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                // track two-finger rotation live
                                if (ev.pointerCount >= 2) cameraTick++
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_POINTER_UP -> {
                                // pick up rotation changes when the gesture ends
                                cameraTick++
                            }
                        }
                        false
                    }
                    map.controller.setZoom(p.lastZoom)
                    map.controller.setCenter(GeoPoint(p.lastLat, p.lastLon))
                    holder.map = map
                    mapView = map
                    cameraTick++   // wake up projection-dependent composables
                    map
                },
                update = { map ->
                    if (offlineFile == null && holder.appliedLayer != p.baseLayer) {
                        map.setTileSource(MapSetup.layerFor(p.baseLayer).source)
                        holder.appliedLayer = p.baseLayer
                    }
                    if (holder.appliedNight != settings.nightMode) {
                        holder.appliedNight = settings.nightMode
                        map.overlayManager.tilesOverlay.setColorFilter(
                            if (settings.nightMode) MapSetup.nightTileFilter else null
                        )
                        map.invalidate()
                    }
                    holder.grid?.let { g ->
                        g.nightMode = settings.nightMode
                        g.lightLines = !settings.nightMode && p.baseLayer == "sat" && offlineFile == null
                        g.attribution = if (offlineFile != null) "MBTiles: ${offlineFile.name}" else layer.attribution
                        g.bottomInsetPx = readoutHeightPx.toFloat()
                        g.mapOrientation = map.mapOrientation
                        if (holder.appliedGrid != p.gridEnabled) {
                            holder.appliedGrid = p.gridEnabled
                            g.gridEnabled = p.gridEnabled
                            map.invalidate()
                        }
                    }
                    holder.hillshade?.isEnabled = p.hillshadeOverlay && offlineFile == null
                    holder.contours?.let { co ->
                        co.isEnabled = p.contourOverlay
                        co.nightMode = settings.nightMode
                        co.mapOrientation = map.mapOrientation
                        co.bottomInsetPx = readoutHeightPx.toFloat()
                    }
                    holder.cm?.let { c ->
                        val editing = editPointsFor
                        c.graphics = if (editing == null) visibleGraphics else visibleGraphics.map {
                            if (it.id == editing.id) it.copy(points = editPts) else it
                        }
                        c.selectedId = editingGraphic?.id ?: editing?.id
                        c.nightMode = settings.nightMode
                        c.lightLines = !settings.nightMode && p.baseLayer == "sat" && offlineFile == null
                        c.draftActive = drawType != null
                        c.draftType = if (drawType == "measure") "area" else drawType ?: "phase_line"
                        c.draftAffiliation = drawAffiliation
                        c.draftPoints = drawPoints
                    }
                    holder.tracks?.let { t ->
                        t.nightMode = settings.nightMode
                        t.activePoints = activeTrack?.points ?: emptyList()
                        t.viewedPoints = viewedPoints
                    }
                    holder.viewshed?.nightMode = settings.nightMode
                    holder.visibleWps = visibleWaypoints
                    map.invalidate()
                },
            )

            // Map lifecycle: resume/pause with the app, detach when leaving
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> holder.map?.onResume()
                        Lifecycle.Event.ON_PAUSE -> {
                            holder.map?.let { m ->
                                scope.launch {
                                    mapPrefs.setCamera(
                                        m.mapCenter.latitude, m.mapCenter.longitude, m.zoomLevelDouble
                                    )
                                }
                            }
                            holder.map?.onPause()
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    // MapView teardown happens in AndroidView(onRelease) for the exact view
                }
            }

            // Persist the camera shortly after movement stops
            LaunchedEffect(cameraTick) {
                delay(1500)
                mapView?.let { m ->
                    mapPrefs.setCamera(m.mapCenter.latitude, m.mapCenter.longitude, m.zoomLevelDouble)
                }
            }

            // Follow-me: recenter on every fix while enabled (touch cancels)
            LaunchedEffect(following, fix.location?.latitude, fix.location?.longitude) {
                val loc = fix.location
                if (following && loc != null) {
                    mapView?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                }
            }

            // A list row asked the map to jump somewhere (unit/waypoint "show on map")
            LaunchedEffect(focusAt, mapView) {
                val target = focusAt
                val m = mapView
                if (target != null && m != null) {
                    val z = if (m.zoomLevelDouble < 14.0) 15.0 else m.zoomLevelDouble
                    m.controller.animateTo(GeoPoint(target.first, target.second), z, null)
                    onFocusConsumed()
                }
            }

            // ---- Compose overlays positioned via the map projection ----
            @Suppress("UNUSED_EXPRESSION")
            cameraTick
            val map = mapView
            if (map != null) {
                val proj = map.projection
                val pxPoint = android.graphics.Point()
                val markerDp = 32.dp
                val markerPx = with(density) { markerDp.roundToPx() }
                val showNames = map.zoomLevelDouble >= 12.0

                // Own position + accuracy
                fix.location?.let { loc ->
                    val own = GeoPoint(loc.latitude, loc.longitude)
                    toScreenPoint(map, proj, own, pxPoint)
                    val ox = pxPoint.x
                    val oy = pxPoint.y
                    if (ox in -400..(map.width + 400) && oy in -400..(map.height + 400)) {
                        val east = own.destinationPoint(1000.0, 90.0)
                        toScreenPoint(map, proj, east, pxPoint)
                        val pxPerMeter = hypot(
                            (pxPoint.x - ox).toDouble(), (pxPoint.y - oy).toDouble()
                        ).toFloat() / 1000f
                        val accPx = (loc.accuracy * pxPerMeter).coerceAtMost(600f)
                        val primary = MaterialTheme.colorScheme.primary
                        val halo = if (settings.nightMode) androidx.compose.ui.graphics.Color.Black
                        else androidx.compose.ui.graphics.Color.White
                        Canvas(Modifier.fillMaxSize()) {
                            if (accPx > 8f) {
                                drawCircle(primary.copy(alpha = 0.10f), radius = accPx, center = Offset(ox.toFloat(), oy.toFloat()))
                                drawCircle(primary.copy(alpha = 0.35f), radius = accPx, center = Offset(ox.toFloat(), oy.toFloat()), style = Stroke(1.5.dp.toPx()))
                            }
                            drawCircle(halo, radius = 8.5.dp.toPx(), center = Offset(ox.toFloat(), oy.toFloat()))
                            drawCircle(primary, radius = 6.dp.toPx(), center = Offset(ox.toFloat(), oy.toFloat()))
                        }
                    }
                }

                // Ruler line: anchor -> crosshair
                rulerAnchor?.let { anchor ->
                    toScreenPoint(map, proj, anchor, pxPoint)
                    val ax = pxPoint.x.toFloat()
                    val ay = pxPoint.y.toFloat()
                    val secondary = MaterialTheme.colorScheme.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        drawLine(secondary, Offset(ax, ay), Offset(cx, cy), strokeWidth = 2.5.dp.toPx())
                        drawCircle(secondary, radius = 5.dp.toPx(), center = Offset(ax, ay))
                        drawCircle(secondary, radius = 3.dp.toPx(), center = Offset(ax, ay), style = Stroke(1.5.dp.toPx()))
                    }
                }

                // Waypoints
                visibleWaypoints.forEach { w ->
                    toScreenPoint(map, proj, GeoPoint(w.lat, w.lon), pxPoint)
                    val wx = pxPoint.x
                    val wy = pxPoint.y
                    if (wx in -markerPx..(map.width + markerPx) && wy in -markerPx..(map.height + markerPx)) {
                        // The outer Box is EXACTLY marker-sized and anchored so its
                        // center sits on the waypoint's screen position. The name label
                        // hangs below as an overflow child, so its width can never
                        // shift the symbol off its grid.
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(wx - markerPx / 2, wy - markerPx / 2) }
                                .size(markerDp),
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (drawType != null) {
                                            // Drawing: tapping a waypoint adds its exact
                                            // position as the next vertex, no popup
                                            drawPoints = drawPoints + GeoVertex(w.lat, w.lon)
                                            notice = "Added ${w.name}"
                                            holder.map?.invalidate()
                                        } else {
                                            infoWp = w
                                        }
                                    }
                            ) {
                                WaypointMarker(
                                    symbol = w.symbol,
                                    affiliation = w.affiliation,
                                    size = markerDp,
                                    echelon = w.echelon,
                                    night = settings.nightMode,
                                    rotation = w.rotation,
                                )
                            }
                            if (showNames) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .offset(y = markerDp + 2.dp)
                                        .wrapContentWidth(unbounded = true),
                                ) {
                                    Text(
                                        if (w.designation.isEmpty()) w.name else "${w.name} · ${w.designation}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = MonoFamily,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Vertex handles while editing a graphic's points
                editPointsFor?.let { eg ->
                    val handleDp = 24.dp
                    val handlePx = with(density) { handleDp.roundToPx() }
                    editPts.forEachIndexed { idx, v ->
                        toScreenPoint(map, proj, GeoPoint(v.lat, v.lon), pxPoint)
                        val hx = pxPoint.x
                        val hy = pxPoint.y
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(hx - handlePx / 2, hy - handlePx / 2) }
                                .size(handleDp)
                                .pointerInput(eg.id, idx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val m = holder.map ?: return@detectDragGestures
                                        val cur = editPts.getOrNull(idx) ?: return@detectDragGestures
                                        val sp = android.graphics.Point()
                                        toScreenPoint(m, m.projection, GeoPoint(cur.lat, cur.lon), sp)
                                        var tx = sp.x + dragAmount.x
                                        var ty = sp.y + dragAmount.y
                                        // toScreenPoint rotated by the map orientation about the
                                        // view centre; fromPixels expects unrotated coordinates.
                                        val deg = m.mapOrientation
                                        if (deg != 0f) {
                                            val rad = Math.toRadians(-deg.toDouble())
                                            val cx = m.width / 2.0
                                            val cy = m.height / 2.0
                                            val dx = tx - cx
                                            val dy = ty - cy
                                            tx = (cx + dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
                                            ty = (cy + dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
                                        }
                                        val ng = m.projection.fromPixels(tx.toInt(), ty.toInt())
                                        editPts = editPts.toMutableList().also {
                                            it[idx] = GeoVertex(ng.latitude, ng.longitude)
                                        }
                                        m.invalidate()
                                    }
                                }
                                .pointerInput(eg.id, idx) {
                                    detectTapGestures(onLongPress = {
                                        if (editPts.size > GraphicTypes.minPoints(eg.type)) {
                                            editPts = editPts.toMutableList().also { it.removeAt(idx) }
                                            holder.map?.invalidate()
                                        } else {
                                            notice = "Point kept — at the minimum for this graphic"
                                        }
                                    })
                                },
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .padding(5.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        CircleShape,
                                    )
                                    .border(
                                        1.5.dp,
                                        if (settings.nightMode) androidx.compose.ui.graphics.Color.Black
                                        else androidx.compose.ui.graphics.Color.White,
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        // Crosshair
        val crossColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
            val c = size.width / 2f
            val gap = 6.dp.toPx()
            val arm = 16.dp.toPx()
            val stroke = 2.dp.toPx()
            listOf(
                Offset(c - gap - arm, c) to Offset(c - gap, c),
                Offset(c + gap, c) to Offset(c + gap + arm, c),
                Offset(c, c - gap - arm) to Offset(c, c - gap),
                Offset(c, c + gap) to Offset(c, c + gap + arm),
            ).forEach { (a, b) ->
                drawLine(crossColor, a, b, strokeWidth = stroke)
            }
            drawCircle(crossColor, radius = 2.dp.toPx(), center = Offset(c, c))
        }

        // The crosshair grid, tap to copy, hold to share. Portrait: a pill heading the
        // top-left column. Sideways the vertical axis is the scarce one, so it becomes
        // a square block in the bottom-right corner, out of the way of the map.
        val gridReadout: @Composable (Boolean, org.osmdroid.api.IGeoPoint?) -> Unit = { landscape, center ->
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Column(
                Modifier
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            val c = holder.map?.mapCenter ?: return@combinedClickable
                            val full = Coordinates.mgrs(c.latitude, c.longitude, settings.mgrsDigits)?.full
                            if (full != null) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(full))
                                notice = "Copied $full"
                            }
                        },
                        onLongClick = {
                            val c = holder.map?.mapCenter ?: return@combinedClickable
                            val full = Coordinates.mgrs(c.latitude, c.longitude, settings.mgrsDigits)?.full
                                ?: return@combinedClickable
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "MGRS $full · " + Coordinates.dtg(System.currentTimeMillis()) + " · sent from MGRS GPS",
                                )
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Share position")
                                )
                            }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                val parts = center?.let { Coordinates.mgrs(it.latitude, it.longitude, settings.mgrsDigits) }
                if (landscape && parts != null && parts.easting.isNotEmpty()) {
                    // The wordmark lives here when the phone is on its side: the landscape
                    // app bar gives its 48 dp back to the map, and this block is already
                    // the one thing on screen that is always legible.
                    Text(
                        "MGRS GPS",
                        fontFamily = LabelFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        letterSpacing = 3.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    // Square block: zone and square on one line, the numbers under them
                    Text(
                        "${parts.gzd} ${parts.square}",
                        fontFamily = MonoFamily,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        "${parts.easting} ${parts.northing}",
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                } else {
                    Text(
                        if (parts == null) "—"
                        else if (parts.easting.isEmpty()) parts.full
                        else "${parts.gzd} ${parts.square} ${parts.easting} ${parts.northing}",
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                // Grid interval and crosshair elevation on their own line, so the grid
                // never pushes the pill under the north button on a narrow phone
                val detail = listOfNotNull(
                    gridInterval.takeIf { it.isNotEmpty() },
                    crossElevation?.let { "▲" + Coordinates.formatAltitude(it, settings.units) },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val loc = fix.location
                val declination = remember(
                    loc?.latitude?.let { (it * 10).toInt() },
                    loc?.longitude?.let { (it * 10).toInt() },
                    settings.declinationOverride,
                ) { Declination.at(settings, loc) }
                fun toRef(angleTrue: Float): Float = when (settings.northRef) {
                    1 -> (angleTrue - declination + 360f) % 360f
                    2 -> if (center == null) angleTrue else
                        (angleTrue - Coordinates.gridConvergence(center.latitude, center.longitude).toFloat() + 360f) % 360f
                    else -> angleTrue
                }
                val refLetter = when (settings.northRef) {
                    1 -> "M"
                    2 -> "G"
                    else -> "T"
                }
                val anchor = rulerAnchor
                // (what, measurement): one line in the portrait pill, two in the square block
                val line2 = when {
                    anchor != null && center != null -> {
                        val nav = Coordinates.navInfo(anchor.latitude, anchor.longitude, center.latitude, center.longitude)
                        "RULER" to (
                            Coordinates.formatDistance(nav.distanceMeters, settings.units) +
                                "  " + Coordinates.formatAngle(toRef(nav.bearingTrue), settings.angleUnit) + " " + refLetter +
                                "  back " + Coordinates.formatAngle(toRef((nav.bearingTrue + 180f) % 360f), settings.angleUnit)
                            )
                    }
                    loc != null && center != null -> {
                        val nav = Coordinates.navInfo(loc.latitude, loc.longitude, center.latitude, center.longitude)
                        "ME → CROSSHAIR" to (
                            Coordinates.formatDistance(nav.distanceMeters, settings.units) +
                                "  " + Coordinates.formatAngle(toRef(nav.bearingTrue), settings.angleUnit) + " " + refLetter
                            )
                    }
                    else -> "long-press the map" to "to drop a waypoint"
                }
                val line2Color = if (anchor != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                if (landscape) {
                    Text(line2.first, fontFamily = MonoFamily, fontSize = 11.sp, color = line2Color, maxLines = 1, softWrap = false)
                    Text(line2.second, fontFamily = MonoFamily, fontSize = 11.sp, color = line2Color, maxLines = 1, softWrap = false)
                } else {
                    Text(
                        // measurements get a double space between the fields; the hint reads as a sentence
                        if (anchor == null && (loc == null || center == null)) "${line2.first} ${line2.second}"
                        else "${line2.first}  ${line2.second}",
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = line2Color,
                        maxLines = 1,
                    )
                }
            }
        }
        }

        // Top-right: north indicator (tap to reset) and track recording
        @Suppress("UNUSED_EXPRESSION")
        cameraTick
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val orientation = holder.map?.mapOrientation ?: 0f
            val rotated = orientation > 0.5f || orientation < -0.5f
            MapButton(
                Icons.Outlined.Navigation,
                if (rotated) "Reset to north-up" else "North-up",
                rotated,
                iconRotation = orientation,
                enabled = rotated,
            ) {
                holder.map?.let { m ->
                    m.mapOrientation = 0f
                    m.invalidate()
                }
                cameraTick++
            }
        }

        // Status chips (download progress / import result / no-permission hint):
        // stacked under the grid pill, so they never sit on top of it
        @Suppress("UNUSED_EXPRESSION")
        cameraTick
        val center = holder.map?.mapCenter
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp, end = 72.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The grid pill heads this column in portrait, so chips stack under it
            // without measuring anything (no first-frame jump)
            if (!landscape) gridReadout(false, center)
            downloadStatus?.let { status ->
                StatusChip(status) { downloadStatus = null }
            }
            importMessage?.let { msg ->
                StatusChip(msg) { importMessage = null }
            }
            notice?.let { msg ->
                StatusChip(msg) { notice = null }
            }
            courseStatus?.let { cs ->
                StatusChip(cs) { onOpenCourse() }
            }
            if (viewshedOn) {
                StatusChip(
                    if (settings.nightMode) "Viewshed: faint seen · mid standing · bright masked — tap to clear"
                    else "Viewshed: green seen · amber standing · red masked — tap to clear"
                ) {
                    holder.viewshed?.data = null
                    viewshedOn = false
                    holder.map?.invalidate()
                }
            }
            activeTrack?.let { at ->
                val km = at.distanceM / 1000.0
                StatusChip(
                    if (km < 1.0) String.format(java.util.Locale.US, "● REC  %.0f m", at.distanceM)
                    else String.format(java.util.Locale.US, "● REC  %.2f km", km)
                ) { stopTrackOpen = true }
            }
            run {
                val loc = fix.location
                val mock = loc != null && (
                    if (android.os.Build.VERSION.SDK_INT >= 31) loc.isMock
                    else @Suppress("DEPRECATION") loc.isFromMockProvider
                    )
                val warning = when {
                    loc == null -> null
                    mock -> "MOCK LOCATION ACTIVE"
                    loc.hasAccuracy() && loc.accuracy > 50f ->
                        "GPS DEGRADED ±${loc.accuracy.toInt()} m — trust your pace count"
                    fix.satellitesUsed in 1..3 ->
                        "GPS WEAK — ${fix.satellitesUsed} satellites in fix"
                    else -> null
                }
                warning?.let { StatusChip(it) {} }
            }
            if (!hasPermission) {
                StatusChip("Location off — tap to enable") { onRequestPermission() }
            }
        }

        if (landscape) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = readoutHeightDp + 10.dp),   // clears the draw bar
            ) { gridReadout(true, center) }
        }

        // Scale bar, bottom-left, above the deck; sized from the map's own projection
        ScaleBar(
            metersPer100Px = run {
                val m = holder.map
                val proj = m?.projection
                if (m == null || proj == null || m.width <= 0) null else {
                    val y = m.height / 2
                    val a = proj.fromPixels(0, y)
                    val b = proj.fromPixels(100, y)
                    GeoPoint(a.latitude, a.longitude).distanceToAsDouble(GeoPoint(b.latitude, b.longitude))
                }
            },
            units = settings.units,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = readoutHeightDp + 12.dp),
        )

        // Bottom stack: draw-mode action bar (when drawing) above the tool deck
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { readoutHeightPx = it.height },
        ) {
        editPointsFor?.let { eg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Edit ${eg.name} · drag points · hold to delete",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (GraphicTypes.fixedPoints(eg.type) == null) {
                        TextButton(onClick = {
                            holder.map?.mapCenter?.let { c ->
                                editPts = editPts + GeoVertex(c.latitude, c.longitude)
                                holder.map?.invalidate()
                            }
                        }) { Text("+Point") }
                    }
                    TextButton(onClick = {
                        onUpdateGraphicPoints(eg.id, editPts)
                        editPointsFor = null
                        holder.map?.invalidate()
                    }) { Text("Save") }
                    TextButton(onClick = {
                        editPointsFor = null
                        holder.map?.invalidate()
                    }) { Text("✕") }
                }
            }
        }
        drawType?.let { dt ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chip = if (dt == "measure") {
                        if (drawPoints.size < 3) "Measure · tap ≥3 corners" else {
                            val (perim, area) = polygonStats(drawPoints)
                            "${formatArea(area)} · ${formatDist(perim)} perim"
                        }
                    } else {
                        "${GraphicTypes.label(dt)} · ${drawPoints.size} pts"
                    }
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = {
                        holder.map?.mapCenter?.let { c ->
                            drawPoints = drawPoints + GeoVertex(c.latitude, c.longitude)
                            holder.map?.invalidate()
                            val fp = GraphicTypes.fixedPoints(dt)
                            if (fp != null && drawPoints.size >= fp) drawNameOpen = true
                        }
                    }) { Text("+Point") }
                    TextButton(
                        enabled = drawPoints.isNotEmpty(),
                        onClick = {
                            drawPoints = drawPoints.dropLast(1)
                            holder.map?.invalidate()
                        },
                    ) { Text("Undo") }
                    TextButton(
                        enabled = drawPoints.size >= if (dt == "measure") 3 else GraphicTypes.minPoints(dt),
                        onClick = { if (dt == "measure") measureOpen = true else drawNameOpen = true },
                    ) { Text("Done") }
                    TextButton(onClick = {
                        drawType = null
                        drawPoints = emptyList()
                        holder.map?.invalidate()
                    }) { Text("✕") }
                }
            }
        }
        // Deck: the tools within thumb reach, one amber primary action
        if (!landscape) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        ) {
            val rule = MaterialTheme.colorScheme.outline
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).fillMaxHeight().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    deckTools.forEach { t ->
                        ToolCell(t.icon, t.label, t.active, Modifier.weight(1f).fillMaxHeight(), onClick = t.onClick)
                    }
                }
                // The primary action fills its whole block, edge to edge
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(64.dp)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = addWaypointAtCrosshair),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AddLocationAlt,
                        contentDescription = "Waypoint at crosshair",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        }
        }
    }
    // Worn sideways: the deck stands on the right of the map as a rail, so the
    // crosshair and the grid stay clear of it
    if (landscape) {
        val rule = MaterialTheme.colorScheme.outline
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(64.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
            ) {
                deckTools.forEach { t ->
                    ToolCell(t.icon, t.label, t.active, Modifier.weight(1f).fillMaxWidth(), compact = true, onClick = t.onClick)
                }
                Box(
                    Modifier
                        .weight(1.3f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = addWaypointAtCrosshair),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AddLocationAlt,
                        contentDescription = "Waypoint at crosshair",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
    }

    // ---- Dialogs ----

    if (layersOpen) {
        AlertDialog(
            onDismissRequest = { layersOpen = false },
            title = { Text("Map layers") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MapSetup.baseLayers.forEach { bl ->
                        LayerRow(
                            label = bl.label,
                            selected = p.baseLayer == bl.key && offlineFile == null,
                        ) {
                            scope.launch { mapPrefs.setBaseLayer(bl.key) }
                            layersOpen = false
                        }
                    }
                    mbtilesFiles.forEach { name ->
                        LayerRow(
                            label = name,
                            selected = offlineName == name,
                        ) {
                            scope.launch { mapPrefs.setBaseLayer("mbtiles:$name") }
                            layersOpen = false
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.GridOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("MGRS grid", Modifier.weight(1f))
                        Switch(
                            checked = p.gridEnabled,
                            onCheckedChange = { v -> scope.launch { mapPrefs.setGridEnabled(v) } },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Terrain shading")
                            Text(
                                "Hillshade shadows over any base layer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = p.hillshadeOverlay,
                            onCheckedChange = { v -> scope.launch { mapPrefs.setHillshadeOverlay(v) } },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Terrain,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Contour lines")
                            Text(
                                "From newer terrain data (3DEP/lidar in the US). Topo base maps print their own, older SRTM contours — small disagreements are normal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = p.contourOverlay,
                            onCheckedChange = { v -> scope.launch { mapPrefs.setContourOverlay(v) } },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import MBTiles file…")
                    }
                    if (offlineFile == null && layer.bulkDownload) {
                        TextButton(onClick = { layersOpen = false; downloadOpen = true }) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Download visible area for offline")
                        }
                    }
                    if (offlineFile == null && !layer.bulkDownload) {
                        Text(
                            "Offline: this basemap's terms allow the browse cache only — " +
                                "pan the area at the zooms you need and it stays on the phone " +
                                "(600 MB). Area download is available on USGS Topo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (offlineFile == null) {
                        TextButton(onClick = {
                            layersOpen = false
                            val bbox = holder.map?.boundingBox
                            if (bbox != null) {
                                downloadStatus = "Fetching elevation…"
                                scope.launch {
                                    val n = Elevation.prefetchArea(
                                        context,
                                        bbox.latNorth, bbox.latSouth,
                                        bbox.lonWest, bbox.lonEast,
                                    )
                                    downloadStatus =
                                        if (n > 0) "Elevation cached — $n tiles"
                                        else "Elevation fetch failed — check connection"
                                }
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Download elevation for this area")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { layersOpen = false }) { Text("Close") }
            },
        )
    }

    if (downloadOpen) {
        val map = holder.map
        if (map == null) {
            downloadOpen = false
        } else {
            val zMin = map.zoomLevelDouble.toInt().coerceAtLeast(3)
            val zMax = (zMin + 4).coerceAtMost(layer.maxDownloadZoom).coerceAtLeast(zMin)
            val bbox = map.boundingBox
            val tiles = remember(zMin, zMax) {
                runCatching { CacheManager(map).possibleTilesInArea(bbox, zMin, zMax) }.getOrDefault(0)
            }
            val tooBig = tiles > 12000
            AlertDialog(
                onDismissRequest = { downloadOpen = false },
                title = { Text("Download this area") },
                text = {
                    Text(
                        if (tooBig) {
                            "The current view needs about $tiles tiles at zoom $zMin–$zMax, which is too much for one download. Zoom in and try again."
                        } else {
                            "Save the visible area for offline use at zoom $zMin–$zMax — about $tiles tiles (≈${(tiles * 20) / 1024} MB) from the ${layer.label} basemap. Cached tiles are also kept automatically as you browse."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !tooBig && tiles > 0 && layer.bulkDownload,
                        onClick = {
                            downloadOpen = false
                            downloadStatus = "Starting download…"
                            try {
                                CacheManager(map).downloadAreaAsyncNoUI(
                                    context, bbox, zMin, zMax,
                                    object : CacheManager.CacheManagerCallback {
                                        override fun onTaskComplete() {
                                            downloadStatus = "Offline area saved"
                                        }

                                        override fun onTaskFailed(errors: Int) {
                                            downloadStatus = "Download done, $errors tiles failed"
                                        }

                                        override fun updateProgress(
                                            progress: Int,
                                            currentZoomLevel: Int,
                                            zoomMin: Int,
                                            zoomMax: Int,
                                        ) {
                                            downloadStatus = "Downloading… $progress / $tiles tiles"
                                        }

                                        override fun downloadStarted() {
                                            downloadStatus = "Downloading…"
                                        }

                                        override fun setPossibleTilesInArea(total: Int) {}
                                    },
                                )
                            } catch (e: Exception) {
                                downloadStatus = "Download failed to start"
                            }
                        },
                    ) { Text("Download") }
                },
                dismissButton = {
                    TextButton(onClick = { downloadOpen = false }) { Text("Cancel") }
                },
            )
        }
    }

    if (drawPickerOpen) {
        AlertDialog(
            onDismissRequest = { drawPickerOpen = false },
            title = { Text("Draw graphic") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GraphicTypes.all.forEach { (key, label, _) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    drawType = key
                                    drawPoints = emptyList()
                                    drawPickerOpen = false
                                    GraphicTypes.placeHint(key)?.let { notice = it }
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    Text(
                        "Measure area",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                drawType = "measure"
                                drawPoints = emptyList()
                                drawPickerOpen = false
                                notice = "Tap the corners of the area to measure"
                            }
                            .padding(vertical = 8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Color", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Affiliations.all.forEach { key ->
                            FilterChip(
                                selected = key == drawAffiliation,
                                onClick = { drawAffiliation = key },
                                label = { Text(Affiliations.label(key)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Then tap the map (or +Point at the crosshair) to place vertices, and Done to save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { drawPickerOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (drawNameOpen) {
        val dt = drawType
        if (dt == null) {
            drawNameOpen = false
        } else {
            var gName by remember(dt) { mutableStateOf("") }
            var gFolder by remember(dt) { mutableStateOf(app.gridfix.android.data.DEFAULT_FOLDER) }
            var gAff by remember(dt) { mutableStateOf(drawAffiliation) }
            var gEch by remember(dt) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { drawNameOpen = false },
                title = { Text("Save ${GraphicTypes.label(dt).lowercase()}") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = gName,
                            onValueChange = { gName = it.take(if (dt == "text") 40 else 20) },
                            label = { Text(if (dt == "text") "Text" else "Name / designation") },
                            placeholder = {
                                Text(
                                    when (dt) {
                                        "phase_line" -> "e.g. BLUE"
                                        "trp", "checkpoint" -> "e.g. 1"
                                        "lz", "pz" -> "e.g. ROBIN"
                                        "ring" -> "e.g. MG SECTOR"
                                        "text" -> "e.g. OP HERE"
                                        else -> "e.g. BRAVO"
                                    }
                                )
                            },
                            singleLine = true,
                        )
                        Text("Color", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Affiliations.all.forEach { key ->
                                FilterChip(
                                    selected = key == gAff,
                                    onClick = { gAff = key },
                                    label = { Text(Affiliations.label(key)) },
                                )
                            }
                        }
                        if (dt == "boundary") {
                            Text("Echelon (drawn on the line)", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                app.gridfix.android.ui.Echelons.all.forEach { (key, label) ->
                                    FilterChip(
                                        selected = key == gEch,
                                        onClick = { gEch = key },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                        Text("Folder", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            (folders.map { it.name } + app.gridfix.android.data.DEFAULT_FOLDER)
                                .distinct()
                                .forEach { f ->
                                    FilterChip(
                                        selected = f == gFolder,
                                        onClick = { gFolder = f },
                                        label = { Text(f) },
                                    )
                                }
                        }
                        OutlinedTextField(
                            value = gFolder,
                            onValueChange = { gFolder = it.take(24) },
                            label = { Text("Folder (type to create new)") },
                            singleLine = true,
                            supportingText = reservedFolderHint(gFolder)?.let { { Text(it) } },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val finalName = gName.trim().ifBlank { "${graphics.size + 1}" }
                        onAddGraphic(finalName, dt, drawPoints, gFolder, gAff, gEch)
                        if (dt == "route" && drawPoints.size >= 2) {
                            val base = if (finalName.all { it.isDigit() }) "Route $finalName" else finalName
                            routeWpOffer = Triple(base, drawPoints, gFolder)
                        }
                        drawNameOpen = false
                        drawType = null
                        drawPoints = emptyList()
                        holder.map?.invalidate()
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { drawNameOpen = false }) { Text("Keep drawing") }
                },
            )
        }
    }

    if (measureOpen && drawPoints.size >= 3) {
        val (perim, area) = polygonStats(drawPoints)
        AlertDialog(
            onDismissRequest = { measureOpen = false },
            title = { Text("Area measurement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatArea(area),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = MonoFamily,
                    )
                    Text(
                        "Perimeter ${formatDist(perim)} · ${drawPoints.size} corners",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MonoFamily,
                    )
                    Text(
                        "Computed on the UTM plane for this zone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    measureOpen = false
                    drawType = "area"
                    drawNameOpen = true
                }) { Text("Save as area") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { measureOpen = false }) { Text("Keep measuring") }
                    TextButton(onClick = {
                        measureOpen = false
                        drawType = null
                        drawPoints = emptyList()
                        holder.map?.invalidate()
                    }) { Text("Done") }
                }
            },
        )
    }

    editingGraphic?.let { g ->
        var gName by remember(g.id) { mutableStateOf(g.name) }
        var gFolder by remember(g.id) { mutableStateOf(g.folder) }
        var gAff by remember(g.id) { mutableStateOf(g.affiliation) }
        var gEch by remember(g.id) { mutableStateOf(g.echelon) }
        AlertDialog(
            onDismissRequest = { editingGraphic = null },
            title = { Text(GraphicTypes.label(g.type)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = gName,
                        onValueChange = { gName = it.take(20) },
                        label = { Text("Name / designation") },
                        singleLine = true,
                    )
                    Text("Color", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Affiliations.all.forEach { key ->
                            FilterChip(
                                selected = key == gAff,
                                onClick = { gAff = key },
                                label = { Text(Affiliations.label(key)) },
                            )
                        }
                    }
                    if (g.type == "boundary") {
                        Text("Echelon (drawn on the line)", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            app.gridfix.android.ui.Echelons.all.forEach { (key, label) ->
                                FilterChip(
                                    selected = key == gEch,
                                    onClick = { gEch = key },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    Text("Folder", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (folders.map { it.name } + g.folder + app.gridfix.android.data.DEFAULT_FOLDER)
                            .distinct()
                            .forEach { f ->
                                FilterChip(
                                    selected = f == gFolder,
                                    onClick = { gFolder = f },
                                    label = { Text(f) },
                                )
                            }
                    }
                    OutlinedTextField(
                        value = gFolder,
                        onValueChange = { gFolder = it.take(24) },
                        label = { Text("Folder (type to create new)") },
                        singleLine = true,
                        supportingText = reservedFolderHint(gFolder)?.let { { Text(it) } },
                    )
                    Text(
                        "${g.points.size} vertices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateGraphic(g.id, gName.trim().ifBlank { g.name }, gFolder, gAff, gEch)
                    editingGraphic = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (g.type == "route") {
                        TextButton(onClick = {
                            routeCardFor = g
                            editingGraphic = null
                        }) { Text("Route card") }
                        TextButton(onClick = {
                            val base = if (g.name.all { it.isDigit() }) "Route ${g.name}" else g.name
                            routeWpOffer = Triple(base, g.points, g.folder)
                            editingGraphic = null
                        }) { Text("Waypoints") }
                    }
                    TextButton(onClick = {
                        editPointsFor = g
                        editPts = g.points
                        editingGraphic = null
                    }) { Text("Points") }
                    TextButton(onClick = {
                        onDeleteGraphic(g.id)
                        editingGraphic = null
                    }) { Text("Delete") }
                    TextButton(onClick = { editingGraphic = null }) { Text("Close") }
                }
            },
        )
    }

    routeWpOffer?.let { (base, pts, folder) ->
        val prefix = "$base WP "
        val existing = waypoints.count { it.name.startsWith(prefix) }
        AlertDialog(
            onDismissRequest = { routeWpOffer = null },
            title = { Text("Navigate this route") },
            text = {
                Text(
                    "Save ${pts.size} waypoints ($prefix" + "1 to $prefix" + "${pts.size}) " +
                        "so each point can be a Navigate target?" +
                        if (existing > 0) {
                            "\n\nThis replaces the $existing existing \"$base WP\" waypoints."
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveRouteWps(base, pts, folder)
                    routeWpOffer = null
                    notice = "$prefix" + "1 to $prefix" + "${pts.size} saved - pick them in Navigate"
                }) { Text("Save waypoints") }
            },
            dismissButton = {
                TextButton(onClick = { routeWpOffer = null }) { Text("Not now") }
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

    if (fieldToolsOpen) {
        FieldToolsChooser(
            onPick = {
                if (it == "course") onOpenCourse() else fieldTool = it
                fieldToolsOpen = false
            },
            onDismiss = { fieldToolsOpen = false },
        )
    }

    fieldTool?.let { tool ->
        val c = holder.map?.mapCenter
        val crossLat = c?.latitude ?: p.lastLat
        val crossLon = c?.longitude ?: p.lastLon
        when (tool) {
            "resection", "intersection" -> RayFixDialog(
                resection = tool == "resection",
                settings = settings,
                bases = visibleWaypoints,
                myPosition = fix.location?.let { it.latitude to it.longitude },
                crosshair = crossLat to crossLon,
                onSaveWaypoint = { draft -> onAdd(draft) },
                onShowOnMap = { la, lo ->
                    following = false
                    holder.map?.controller?.animateTo(GeoPoint(la, lo))
                },
                onDismiss = { fieldTool = null },
            )
            "sunmoon" -> SunMoonDialog(crossLat, crossLon) { fieldTool = null }
            "declination" -> DeclinationDialog(crossLat, crossLon, settings.declinationOverride) { fieldTool = null }
            "los" -> app.gridfix.android.ui.LosDialog(
                settings = settings,
                waypoints = visibleWaypoints,
                myPosition = fix.location?.let { it.latitude to it.longitude },
                crosshair = crossLat to crossLon,
                onViewshed = { vs ->
                    holder.viewshed?.data = vs
                    viewshedOn = true
                    if (vs.missing > 500) {
                        notice = "Viewshed has data gaps — download elevation for this area"
                    }
                    holder.map?.invalidate()
                },
                onDismiss = { fieldTool = null },
            )
        }
    }

    if (gotoOpen) {
        // Same three-field layout as the waypoint editor: grid zone + square
        // prefilled from the crosshair (usually you stay in your own square, so
        // you only type the digits), full-grid paste splits itself, easting
        // auto-advances to northing at 5 digits.
        val centerParts = remember {
            holder.map?.mapCenter?.let { c ->
                Coordinates.mgrs(c.latitude, c.longitude, 10)
            }
        }
        var gzdSquare by remember {
            mutableStateOf(
                centerParts?.takeIf { it.square.isNotEmpty() }
                    ?.let { "${it.gzd} ${it.square}" } ?: ""
            )
        }
        var gotoEasting by remember { mutableStateOf("") }
        var gotoNorthing by remember { mutableStateOf("") }
        var gotoError by remember { mutableStateOf<String?>(null) }
        val gotoNorthingFocus = remember { FocusRequester() }
        val bigDigits = androidx.compose.ui.text.TextStyle(
            fontFamily = MonoFamily,
            fontSize = 18.sp,
        )
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("Go to grid") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = gzdSquare,
                        onValueChange = { v ->
                            val cleaned = v.uppercase(java.util.Locale.US)
                                .filter { it.isLetterOrDigit() || it == ' ' }
                            var handled = false
                            if (cleaned.replace(" ", "").length > 7) {
                                // A full grid was pasted — split it into the fields
                                val parsed = Coordinates.parseMgrs(cleaned)
                                if (parsed != null) {
                                    val parts = Coordinates.mgrs(parsed.first, parsed.second, 10)
                                    if (parts != null && parts.easting.isNotEmpty()) {
                                        gzdSquare = "${parts.gzd} ${parts.square}"
                                        gotoEasting = parts.easting
                                        gotoNorthing = parts.northing
                                        handled = true
                                    }
                                }
                            }
                            if (!handled) gzdSquare = cleaned.take(7)
                            gotoError = null
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
                            value = gotoEasting,
                            onValueChange = { v ->
                                gotoEasting = v.filter { it.isDigit() }.take(5)
                                gotoError = null
                                if (gotoEasting.length == 5) gotoNorthingFocus.requestFocus()
                            },
                            label = { Text("Easting") },
                            placeholder = { Text("23556") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = gotoNorthing,
                            onValueChange = { v ->
                                gotoNorthing = v.filter { it.isDigit() }.take(5)
                                gotoError = null
                            },
                            label = { Text("Northing") },
                            placeholder = { Text("97008") },
                            singleLine = true,
                            textStyle = bigDigits,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(gotoNorthingFocus),
                        )
                    }
                    gotoError?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        gzdSquare.isBlank() ->
                            gotoError = "Enter the grid zone and 100 km square (e.g. 39R TM)."
                        gotoEasting.isEmpty() || gotoEasting.length != gotoNorthing.length ->
                            gotoError = "Easting and northing need the same number of digits."
                        else -> {
                            val parsed = Coordinates.parseMgrs(gzdSquare + gotoEasting + gotoNorthing)
                            if (parsed == null) {
                                gotoError = "Couldn't read that grid — check the zone letters and digits."
                            } else {
                                val m = holder.map
                                if (m != null) {
                                    val z = if (m.zoomLevelDouble < 14.0) 15.0 else m.zoomLevelDouble
                                    m.controller.animateTo(GeoPoint(parsed.first, parsed.second), z, null)
                                }
                                following = false
                                gotoOpen = false
                            }
                        }
                    }
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { gotoOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (stopTrackOpen) {
        val at = activeTrack
        if (at == null) {
            stopTrackOpen = false
        } else {
            var trackName by remember(at.trackId) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { stopTrackOpen = false },
                title = { Text("Stop recording?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            Coordinates.formatDistance(at.distanceM.toFloat(), settings.units) +
                                " recorded so far.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = trackName,
                            onValueChange = { trackName = it.take(30) },
                            label = { Text("Track name (optional)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onRecordStop(trackName.trim().ifBlank { null }, false)
                        stopTrackOpen = false
                    }) { Text("Save track") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            onRecordStop(null, true)
                            stopTrackOpen = false
                        }) { Text("Discard") }
                        TextButton(onClick = { stopTrackOpen = false }) { Text("Keep going") }
                    }
                },
            )
        }
    }

    infoWp?.let { w ->
        val loc = fix.location
        AlertDialog(
            onDismissRequest = { infoWp = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaypointMarker(symbol = w.symbol, affiliation = w.affiliation, size = 30.dp, echelon = w.echelon, night = settings.nightMode, rotation = w.rotation)
                    Spacer(Modifier.width(10.dp))
                    Text(w.name)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        Coordinates.mgrs(w.lat, w.lon, 10)?.full ?: "",
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (w.designation.isNotEmpty()) {
                        Text(
                            w.designation,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Folder: ${w.folder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loc != null) {
                        val nav = Coordinates.navInfo(loc.latitude, loc.longitude, w.lat, w.lon)
                        Text(
                            Coordinates.formatDistance(nav.distanceMeters, settings.units) +
                                "  " + Coordinates.formatAngle(nav.bearingTrue, settings.angleUnit) + " T from you",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFamily,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    infoWp = null
                    onNavigateTo(w.id)
                }) { Text("Navigate") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { qrWp = w; infoWp = null }) { Text("QR") }
                    TextButton(onClick = { editingWp = w; infoWp = null }) { Text("Edit") }
                    TextButton(onClick = { infoWp = null }) { Text("Close") }
                }
            },
        )
    }

    qrWp?.let { w ->
        app.gridfix.android.ui.QrDialog(
            title = w.name,
            payload = app.gridfix.android.ui.geoUri(w.lat, w.lon, w.name),
            caption = Coordinates.mgrs(w.lat, w.lon, 10)?.full ?: "",
            onDismiss = { qrWp = null },
        )
    }

    newWpAt?.let { (lat, lon) ->
        WaypointDialog(
            initial = null,
            presetLat = lat,
            presetLon = lon,
            presetLabel = "Map position",
            folderNames = folders.map { it.name },
            defaultName = "WP " + (waypoints.size + 1),
            onConfirm = { draft ->
                onAdd(draft)
                newWpAt = null
            },
            onDismiss = { newWpAt = null },
            settings = settings,
            projectBases = waypoints,
            unitNameFor = unitNameFor,
        )
    }

    editingWp?.let { w ->
        WaypointDialog(
            initial = w,
            presetLat = null,
            presetLon = null,
            presetLabel = "Map position",
            folderNames = folders.map { it.name },
            defaultName = w.name,
            onConfirm = { draft ->
                onUpdate(w.id, draft)
                editingWp = null
            },
            onDismiss = { editingWp = null },
            settings = settings,
            projectBases = waypoints,
            unitNameFor = unitNameFor,
        )
    }

    // Auto-clear the copy/share notice
    LaunchedEffect(notice) {
        val n = notice
        if (n != null) {
            delay(3000)
            if (notice == n) notice = null
        }
    }

    // Auto-clear finished download status
    LaunchedEffect(downloadStatus) {
        val s = downloadStatus
        if (s != null && (s.startsWith("Offline area") || s.startsWith("Download done") || s.startsWith("Download failed") || s.startsWith("Elevation"))) {
            delay(5000)
            if (downloadStatus == s) downloadStatus = null
        }
    }
}

/**
 * Scale bar in the house style: a round length that fits ~120 dp, end ticks,
 * the label in mono. Metric / imperial / nautical follow the units setting.
 */
@Composable
private fun ScaleBar(metersPer100Px: Double?, units: Int, modifier: Modifier = Modifier) {
    if (metersPer100Px == null || metersPer100Px <= 0.0) return
    val density = LocalDensity.current
    val maxPx = with(density) { 120.dp.toPx() }
    val metersPerPx = metersPer100Px / 100.0
    val maxMeters = maxPx * metersPerPx
    // candidate lengths in the unit system, converted to metres
    val (label, meters) = when (units) {
        1 -> {
            val feet = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
            val miles = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
            val ft = feet.lastOrNull { it * 0.3048 <= maxMeters }
            val mi = miles.lastOrNull { it * 1609.344 <= maxMeters }
            when {
                mi != null -> (if (mi < 1.0) "0.5 mi" else "${mi.toInt()} mi") to mi * 1609.344
                ft != null -> "${ft.toInt()} ft" to ft * 0.3048
                else -> "50 ft" to 50.0 * 0.3048
            }
        }
        2 -> {
            val nm = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
            val m = listOf(10.0, 20.0, 50.0, 100.0)
            val n = nm.lastOrNull { it * 1852.0 <= maxMeters }
            val mm = m.lastOrNull { it <= maxMeters }
            when {
                n != null -> (if (n < 1.0) "$n NM" else "${n.toInt()} NM") to n * 1852.0
                mm != null -> "${mm.toInt()} m" to mm
                else -> "10 m" to 10.0
            }
        }
        else -> {
            val m = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0)
            val v = m.lastOrNull { it <= maxMeters } ?: 10.0
            (if (v >= 1000.0) "${(v / 1000.0).toInt()} km" else "${v.toInt()} m") to v
        }
    }
    val widthDp = with(density) { (meters / metersPerPx).toFloat().toDp() }
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                label,
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Canvas(Modifier.width(widthDp).height(6.dp)) {
                val w = 1.5.dp.toPx()
                drawLine(ink, Offset(0f, size.height - w / 2f), Offset(size.width, size.height - w / 2f), w)
                drawLine(ink, Offset(w / 2f, 0f), Offset(w / 2f, size.height), w)
                drawLine(ink, Offset(size.width - w / 2f, 0f), Offset(size.width - w / 2f, size.height), w)
            }
        }
    }
}

@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    iconRotation: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = iconRotation },
            )
        }
    }
}

/** A deck tool: what it shows, whether its mode is on, what a tap does. */
private class DeckTool(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

/**
 * One tool on the deck: a tiny label over the icon, amber when its mode is active.
 * The caller sizes the cell (weight in a Row for the bottom deck, in a Column for
 * the landscape rail); [compact] is the rail's smaller type and icon.
 */
@Composable
private fun ToolCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // "RECORD" at 11 sp with tracking is wider than a 38 dp cell on a 360 dp
        // phone: 10 sp, no tracking, never wrapped, allowed to overflow a hair
        Text(
            label.uppercase(java.util.Locale.US),
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = LabelFamily,
                fontSize = if (compact) 8.sp else 10.sp,
                lineHeight = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            ),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (compact) 20.dp else 28.dp),
        )
    }
}

@Composable
private fun StatusChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LayerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun listMbtiles(context: Context): List<String> =
    MapSetup.mbtilesDir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".mbtiles", ignoreCase = true) }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()

/**
 * Copy the picked document into the app's MBTiles folder and sanity-check it.
 * Returns (importedFileName or null, user message).
 */
private suspend fun importMbtiles(context: Context, uri: Uri): Pair<String?, String> =
    withContext(Dispatchers.IO) {
        var copied: File? = null
        try {
            var display = "imported.mbtiles"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.let { display = it }
                }
            }
            var name = display.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim()
            if (!name.endsWith(".mbtiles", ignoreCase = true)) name += ".mbtiles"
            val dir = MapSetup.mbtilesDir(context)
            var target = File(dir, name)
            var counter = 2
            while (target.exists()) {
                target = File(dir, name.removeSuffix(".mbtiles") + "-$counter.mbtiles")
                counter++
            }
            copied = target
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext null to "Couldn't open that file"
            input.use { stream ->
                target.outputStream().use { output -> stream.copyTo(output) }
            }
            // Sanity check: must be SQLite with tiles, and raster (not vector) format
            val db = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            var format = ""
            var hasTiles = false
            db.use { d ->
                d.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name='tiles'",
                    null,
                ).use { c -> hasTiles = c.moveToFirst() }
                runCatching {
                    d.rawQuery("SELECT value FROM metadata WHERE name='format'", null).use { c ->
                        if (c.moveToFirst()) format = c.getString(0) ?: ""
                    }
                }
            }
            if (!hasTiles) {
                target.delete()
                return@withContext null to "Not a usable MBTiles file (no tiles table)"
            }
            if (format.equals("pbf", ignoreCase = true)) {
                target.delete()
                return@withContext null to "Vector MBTiles aren't supported — use raster (png/jpg) tiles"
            }
            target.name to "Imported ${target.name}"
        } catch (e: Exception) {
            // A copy that never validated must not linger as a selectable "layer"
            runCatching { copied?.delete() }
            null to "Import failed: ${e.message ?: "unknown error"}"
        }
    }
