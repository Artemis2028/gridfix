package app.gridfix.android.map

import kotlinx.coroutines.flow.StateFlow

/**
 * User-map repository shape (roadmap A photo-map, roadmap C GeoTIFF) -
 * interface and models only, no storage wired yet.
 *
 * A user map is a calibrated image: four control points (grid-line
 * intersections = cell corners, see `Coordinates.parseMgrsCorner`) locate the
 * image pixels as an [ImageQuad]; both engines render it the same way
 * (osmdroid: perspective `drawBitmap(matrix)`; MapLibre: an image source over
 * the quad). Source files live in `filesDir/usermaps/<id>.bin` as opaque bytes
 * - never re-encoded, so a GeoTIFF's georeferencing survives - and the
 * downsampled render copy (2048 px, RGB_565) is derived at draw time.
 *
 * [ImageQuad] is in this package today and moves to `:core` with the rest of
 * the portable models; the import here follows it then, not before.
 */
data class UserMap(
    val id: String,
    val name: String,
    /** File name under filesDir/usermaps/. Opaque source bytes. */
    val fileName: String,
    val quad: ImageQuad,
)

interface UserMapStore {
    val maps: StateFlow<List<UserMap>>
    suspend fun add(map: UserMap)
    suspend fun setVisible(id: String, visible: Boolean)
    suspend fun setOpacity(id: String, opacity: Float)
    suspend fun delete(id: String)
}
