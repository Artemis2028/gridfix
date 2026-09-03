package app.gridfix.android.map

import java.io.File

/**
 * Elevation storage seam — pure JVM, no Context, no Bitmap.
 *
 * [Elevation] currently hard-codes `File(context.filesDir, "dem")` with an
 * in-memory cap of 12 bitmaps and no on-disk cap (P1: prefetch 400 tiles x
 * N boxes fills the device; osmdroid trims at 500 MB, dem does not).
 *
 * This file adds the portable policy next to it without changing behavior:
 * osmdroid keeps calling `Elevation` untouched. The MapLibre adapter and the
 * iOS port implement [ElevationStore] with their own sandbox dir and call
 * [trimOldest] after prefetch.
 *
 * KMP-ready: move verbatim to `:core` when it becomes `commonMain`.
 */
interface ElevationStore {
    val dir: File

    fun fileFor(z: Int, x: Int, y: Int): File =
        File(dir, "${z}_${x}_$y.png")
}

/** Delete oldest PNGs beyond [maxFiles]. Returns files removed. */
fun ElevationStore.trimOldest(maxFiles: Int = 400): Int {
    val pngs = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
        ?.sortedBy { it.lastModified() } ?: return 0
    if (pngs.size <= maxFiles) return 0
    var removed = 0
    for (f in pngs.take(pngs.size - maxFiles)) {
        if (runCatching { f.delete() }.getOrDefault(false)) removed++
    }
    return removed
}

/** Default on-disk cap shared by Android + iOS until tuned on device. */
const val ELEVATION_MAX_CACHED_TILES = 400
