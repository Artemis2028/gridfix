package app.gridfix.android.map

import android.content.Context
import app.gridfix.android.AppInfo
import app.gridfix.android.BuildConfig
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.File

/** One selectable online base layer. */
data class BaseLayer(
    val key: String,
    val label: String,
    val source: ITileSource,
    val attribution: String,
    /** Highest zoom worth bulk-downloading from this provider. */
    val maxDownloadZoom: Int,
    /**
     * Whether the provider's usage terms permit pre-downloading an area. Only
     * public-domain USGS does; the others allow the ordinary browse cache only.
     */
    val bulkDownload: Boolean = false,
    /** Typical compressed tile size, for the download-size estimate only. */
    val bytesPerTile: Int = 20 * 1024,
)

object MapSetup {

    private var initialized = false

    /** One-time osmdroid configuration: cache location, size, and the required user agent. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val base = File(context.filesDir, "osmdroid")
        base.mkdirs()
        val tiles = File(base, "tiles")
        tiles.mkdirs()
        Configuration.getInstance().apply {
            osmdroidBasePath = base
            osmdroidTileCache = tiles
            userAgentValue = AppInfo.userAgent(BuildConfig.VERSION_NAME)
            tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
            // The browse cache honours each provider's own Cache-Control header
            // (Esri's Location Platform terms, 3.1(d)(6)(A), allow caching only
            // "as permitted by the caching headers"). osmdroid still draws an
            // expired tile while offline; it only asks the server again once it can.
            // Deliberate USGS area downloads are pinned with [pinDownloadExpiry].
            expirationExtendedDuration = 0L
        }
    }

    /**
     * Ten years: the expiry [PinnedTileWriter] stamps on every tile of a USGS
     * area download, so they never expire and are the last thing the cache trim
     * would evict (it deletes in expiry order). Nothing else is affected: the
     * writer is per-download, not a global policy.
     */
    const val DOWNLOAD_TTL_MS = 10L * 365 * 24 * 60 * 60 * 1000

    fun downloadWriter(): PinnedTileWriter = PinnedTileWriter(DOWNLOAD_TTL_MS)

    /**
     * Zoom range for "download this area" on [layer] from the map's current zoom:
     * five levels from the current one, both ends held inside what the source
     * serves and what the layer's terms allow. Overzoomed past the source's top
     * level (the map allows 21.5; USGS stops at 16), the range starts at the top
     * tile level that actually exists, not at the on-screen zoom.
     */
    fun downloadZoomRange(currentZoom: Double, layer: BaseLayer): IntRange {
        val top = minOf(layer.maxDownloadZoom, layer.source.maximumZoomLevel)
        val zMin = currentZoom.toInt().coerceIn(3, top)
        val zMax = (zMin + 4).coerceAtMost(top)
        return zMin..zMax
    }

    /** {z}/{y}/{x} ArcGIS tile scheme, optionally with a `?token=` suffix. */
    private fun arcgisSource(
        name: String,
        base: String,
        maxZoom: Int,
        tileSize: Int,
        attribution: String,
        token: String = "",
    ) = object : OnlineTileSourceBase(name, 0, maxZoom, tileSize, "", arrayOf(base), attribution) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl +
                MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) +
                (if (token.isEmpty()) "" else "?token=$token")
    }

    // ---- USGS The National Map (public domain; the only layers offered for area download) ----

    private const val USGS_ATTR = "USGS The National Map"

    private val usgsTopo = arcgisSource(
        "USGSTopo",
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/",
        16, 256, USGS_ATTR,
    )

    /** NAIP orthoimagery over the US, served to zoom 16 (~2.4 m/px). */
    private val usgsImagery = arcgisSource(
        "USGSImagery",
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/",
        16, 256, USGS_ATTR,
    )

    // ---- Esri ArcGIS Location Platform (keyed; the key is injected at build time from a CI secret) ----
    //
    // Terms: ArcGIS Location Platform Agreement (E204, Nov 2025). Revenue-generating
    // apps are permitted (3.1(c)(1)); an attribution statement naming Esri and its
    // licensors must be affixed to basemap output (3.1(d)(4)); caching only as the
    // HTTP headers allow (3.1(d)(6)(A)) — see [init]. Static basemap tiles are 512 px;
    // osmdroid normalises the on-screen size when tiles are scaled to DPI.

    private const val ESRI_STATIC =
        "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/"
    private const val ESRI_IMAGE = "https://ibasemaps-api.arcgis.com/arcgis/rest/services/"
    private const val ESRI_STREETS_ATTR = "Powered by Esri · © Esri, TomTom, Garmin, OpenStreetMap contributors"
    private const val ESRI_IMAGERY_ATTR = "Powered by Esri · Esri, Maxar, Earthstar Geographics"
    private const val ESRI_HILLSHADE_ATTR = "Powered by Esri · Esri, USGS, NASA"

    /** True on builds made with the ESRI_KEY secret present. */
    val hasEsri: Boolean get() = BuildConfig.ESRI_KEY.isNotBlank()

    private val esriStreets by lazy {
        arcgisSource("EsriStreets", ESRI_STATIC + "arcgis/streets/static/tile/", 22, 512, ESRI_STREETS_ATTR, BuildConfig.ESRI_KEY)
    }
    private val esriOutdoor by lazy {
        arcgisSource("EsriOutdoor", ESRI_STATIC + "arcgis/outdoor/static/tile/", 22, 512, ESRI_STREETS_ATTR, BuildConfig.ESRI_KEY)
    }
    private val esriImagery by lazy {
        arcgisSource("EsriWorldImagery", ESRI_IMAGE + "World_Imagery/MapServer/tile/", 19, 256, ESRI_IMAGERY_ATTR, BuildConfig.ESRI_KEY)
    }
    private val esriHillshade by lazy {
        arcgisSource("EsriHillshade", ESRI_IMAGE + "Elevation/World_Hillshade/MapServer/tile/", 16, 256, ESRI_HILLSHADE_ATTR, BuildConfig.ESRI_KEY)
    }

    // ---- Community fallback (DEBUG builds without the key only) ----

    private val communityImagery = arcgisSource(
        "EsriWorldImagery",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
        19, 256, "Esri, Maxar, Earthstar Geographics",
    )
    private val communityHillshade = arcgisSource(
        "EsriHillshade",
        "https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/",
        16, 256, "Esri, USGS, NASA",
    )

    private val usgsLayers = listOf(
        BaseLayer(
            key = "usgs",
            label = "USGS Topo (US only)",
            source = usgsTopo,
            attribution = USGS_ATTR,
            maxDownloadZoom = 15,
            bulkDownload = true,
            bytesPerTile = 32 * 1024,
        ),
        BaseLayer(
            key = "usgs_img",
            label = "USGS Imagery (US only)",
            source = usgsImagery,
            attribution = USGS_ATTR,
            maxDownloadZoom = 16,
            bulkDownload = true,
            bytesPerTile = 24 * 1024,
        ),
    )

    /**
     * Base layers. With an Esri key (every store build) Streets, Topographic,
     * Satellite and Hillshade come from ArcGIS Location Platform under its
     * agreement; the two USGS layers are public domain and are the only ones
     * offered for area download. Without a key (a developer build missing the
     * secret) the app falls back to the public community servers so the map
     * still works.
     *
     * That fallback is DEBUG-ONLY. The OpenStreetMap Foundation's tile servers are
     * a volunteer-funded resource with a usage policy that a paid app has no
     * business leaning on, and the same goes for the unkeyed ArcGIS endpoints.
     * A release build that somehow shipped without a key gets the USGS layers only.
     */
    private val allLayers: List<BaseLayer> = if (hasEsri) listOf(
        BaseLayer(
            key = "streets",
            label = "Streets",
            source = esriStreets,
            attribution = ESRI_STREETS_ATTR,
            maxDownloadZoom = 16,
        ),
        BaseLayer(
            key = "topo",
            label = "Topographic",
            source = esriOutdoor,
            attribution = ESRI_STREETS_ATTR,
            maxDownloadZoom = 15,
        ),
        BaseLayer(
            key = "sat",
            label = "Satellite",
            source = esriImagery,
            attribution = ESRI_IMAGERY_ATTR,
            maxDownloadZoom = 17,
        ),
    ) + usgsLayers + BaseLayer(
        key = "hillshade",
        label = "Hillshade",
        source = esriHillshade,
        attribution = ESRI_HILLSHADE_ATTR,
        maxDownloadZoom = 14,
    ) else listOf(
        BaseLayer(
            key = "streets",
            label = "Streets",
            source = TileSourceFactory.MAPNIK,
            attribution = "© OpenStreetMap contributors",
            maxDownloadZoom = 16,
        ),
        BaseLayer(
            key = "topo",
            label = "Topographic",
            source = TileSourceFactory.OpenTopo,
            attribution = "© OpenStreetMap contributors, SRTM — style © OpenTopoMap (CC-BY-SA)",
            maxDownloadZoom = 15,
        ),
        BaseLayer(
            key = "sat",
            label = "Satellite",
            source = communityImagery,
            attribution = "Esri, Maxar, Earthstar Geographics",
            maxDownloadZoom = 17,
        ),
    ) + usgsLayers + BaseLayer(
        key = "hillshade",
        label = "Hillshade",
        source = communityHillshade,
        attribution = "Esri, USGS, NASA",
        maxDownloadZoom = 14,
    )

    /**
     * The layers the app actually offers. In a release build with no Esri key
     * that is the USGS layers alone rather than the community servers.
     */
    val baseLayers: List<BaseLayer> =
        if (hasEsri || BuildConfig.DEBUG) allLayers
        else allLayers.filter { it.bulkDownload }.ifEmpty { allLayers.take(1) }

    fun layerFor(key: String): BaseLayer =
        baseLayers.firstOrNull { it.key == key } ?: baseLayers.first()

    /** Folder where imported MBTiles files live. */
    fun mbtilesDir(context: Context): File =
        File(context.filesDir, "mbtiles").apply { mkdirs() }

    /** The hillshade tile source, reused by the hybrid shadow overlay. */
    val hillshadeSource: ITileSource get() = if (hasEsri) esriHillshade else communityHillshade

    /**
     * Hybrid-terrain blend: hillshade tiles become shadow-only — white turns
     * transparent, dark slopes darken whatever base layer sits beneath.
     */
    val hillshadeShadowFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                -0.235f, -0.235f, -0.235f, 0f, 180f,
            )
        )
    )

    /**
     * Night-vision tile filter: collapses the basemap to shades of red on black,
     * matching the app's red-on-black night theme.
     */
    val nightTileFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.42f, 0.34f, 0.14f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}
