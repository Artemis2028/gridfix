package app.gridfix.android.map

/**
 * Portable map descriptors — no osmdroid, no MapLibre, no Android imports.
 *
 * Slice 1 of the MapLibre/iOS rebuild: [MapSetup.BaseLayer] still owns the
 * osmdroid `ITileSource` and keeps shipping. New code (photo-map quad from
 * roadmap A, GeoTIFF import from roadmap C, offline packs from roadmap B)
 * targets these descriptors so the same model feeds `map-osmdroid` now and
 * `map-maplibre` (Android + Swift) next.
 *
 * KMP-ready by construction: pure Kotlin + java.io only. When `:core`
 * becomes `commonMain`, this file moves verbatim.
 */

/** Engine-agnostic base layer. Mirrors the fields MapScreen actually uses. */
data class BaseLayerDescriptor(
    val key: String,
    val label: String,
    val attribution: String,
    val maxDownloadZoom: Int,
    val bulkDownload: Boolean = false,
)

/** Calibrated image quad: roadmap A (photo-map) and C (GeoTIFF) share this. */
data class ImageQuad(
    val id: String,
    val name: String,
    /** 4 corners in order: TL, TR, BR, BL — lat/lon pairs. */
    val corners: List<Pair<Double, Double>>,
    val opacity: Float = 1f,
    val visible: Boolean = true,
) {
    init {
        require(corners.size == 4) { "ImageQuad needs exactly 4 corners" }
    }
}

/** Offline region request — replaces per-provider bulk-download flags. */
data class OfflinePack(
    val layerKey: String,
    val latNorth: Double,
    val latSouth: Double,
    val lonWest: Double,
    val lonEast: Double,
    val minZoom: Int,
    val maxZoom: Int,
)

/** Minimal projection contract both engines satisfy. */
interface MapProjection {
    fun toPixels(lat: Double, lon: Double): Pair<Float, Float>
    fun fromPixels(x: Float, y: Float): Pair<Double, Double>
}

/** Portable copy of the current catalog — no tile sources attached. */
fun MapSetup.descriptors(): List<BaseLayerDescriptor> =
    baseLayers.map {
        BaseLayerDescriptor(
            key = it.key,
            label = it.label,
            attribution = it.attribution,
            maxDownloadZoom = it.maxDownloadZoom,
            bulkDownload = it.bulkDownload,
        )
    }

fun MapSetup.descriptorFor(key: String): BaseLayerDescriptor =
    descriptors().firstOrNull { it.key == key } ?: descriptors().first()
