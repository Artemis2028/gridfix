package app.gridfix.android.map

import android.content.Context
import app.gridfix.android.AppInfo
import app.gridfix.android.BuildConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * Elevation from the open Mapzen/AWS Terrarium terrain tiles
 * (s3.amazonaws.com/elevation-tiles-prod). Elevation is encoded per pixel as
 * (R*256 + G + B/256) - 32768 metres. Tiles are cached on disk, so any terrain
 * looked at once keeps its elevation offline; prefetchArea pulls a whole
 * visible box deliberately.
 */
object Elevation {

    const val ZOOM = 13   // ~19 m sample grid; source data ~10-30 m (DTED2-class)
    private val memory = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 12
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "dem").apply { mkdirs() }

    /**
     * Terrain tiles are permanent once fetched, so without a ceiling a few area
     * prefetches quietly fill the device (osmdroid caps its own map tiles at 600 MB;
     * this had no cap at all). Oldest tiles go first, and only every so often, since
     * listing the directory during a viewshed sweep would cost more than it saves.
     */
    private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024
    private var lastTrimAt = 0L
    private const val TRIM_INTERVAL_MS = 10 * 60_000L

    private fun trimCache(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now
        runCatching {
            val files = cacheDir(context).listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= CACHE_LIMIT_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= CACHE_LIMIT_BYTES) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        }
    }

    private fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    private fun tileY(lat: Double, z: Int): Double {
        val latRad = Math.toRadians(lat)
        return (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * (1 shl z)
    }

    /** Elevation in metres, or null when unavailable (no data yet / ocean tile miss). */
    suspend fun elevationAt(context: Context, lat: Double, lon: Double): Double? =
        withContext(Dispatchers.IO) {
            if (lat > 85.0 || lat < -85.0) return@withContext null
            val xF = tileX(lon, ZOOM)
            val yF = tileY(lat, ZOOM)
            val x = floor(xF).toInt()
            val y = floor(yF).toInt()
            val bmp = tileBitmap(context, ZOOM, x, y) ?: return@withContext null
            val px = ((xF - x) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val py = ((yF - y) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val elev = (r * 256 + g + b / 256.0) - 32768.0
            if (elev < -11000 || elev > 9000) null else elev
        }

    /** A whole Terrarium tile bitmap (from cache or network) — the contour overlay samples these. */
    suspend fun tile(context: Context, z: Int, x: Int, y: Int): Bitmap? =
        withContext(Dispatchers.IO) { tileBitmap(context, z, x, y) }

    /** Fetch every elevation tile covering the box; returns tiles now cached. */
    suspend fun prefetchArea(
        context: Context,
        latNorth: Double, latSouth: Double, lonWest: Double, lonEast: Double,
    ): Int = withContext(Dispatchers.IO) {
        val x0 = floor(tileX(lonWest, ZOOM)).toInt()
        val x1 = floor(tileX(lonEast, ZOOM)).toInt()
        val y0 = floor(tileY(latNorth, ZOOM)).toInt()
        val y1 = floor(tileY(latSouth, ZOOM)).toInt()
        var ok = 0
        var count = 0
        for (x in minOf(x0, x1)..maxOf(x0, x1)) {
            for (y in minOf(y0, y1)..maxOf(y0, y1)) {
                count++
                if (count > 400) return@withContext ok   // sanity cap ~ a division sector
                if (tileBitmap(context, ZOOM, x, y) != null) ok++
            }
        }
        ok
    }

    @Synchronized
    private fun cached(key: String): Bitmap? = memory[key]

    @Synchronized
    private fun remember(key: String, bmp: Bitmap) {
        memory[key] = bmp
    }

    // Tiles that failed to download recently: skip them for a while instead of
    // re-opening a connection per sample (a viewshed offline is ~100k samples).
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RETRY_AFTER_MS = 60_000L

    private fun tileBitmap(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val key = "$z/$x/$y"
        cached(key)?.let { return it }
        val file = File(cacheDir(context), "${z}_${x}_$y.png")
        if (!file.exists()) {
            val lastFail = failedAt[key]
            if (lastFail != null && System.currentTimeMillis() - lastFail < RETRY_AFTER_MS) return null
            val ok = runCatching {
                val url = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", AppInfo.userAgent(BuildConfig.VERSION_NAME))
                try {
                    // Only a real PNG tile gets cached: a captive-portal page or an
                    // error body must never be stored as elevation data.
                    if (conn.responseCode != 200) return@runCatching false
                    val type = conn.contentType ?: ""
                    if (!type.startsWith("image/png")) return@runCatching false
                    // Unique temp name: concurrent callers (crosshair, contours, viewshed)
                    // must not publish each other's half-written files.
                    val tmp = File.createTempFile("dem", ".part", file.parentFile)
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.absolutePath, probe)
                    if (probe.outWidth != 256 || probe.outHeight != 256) {
                        tmp.delete()
                        return@runCatching false
                    }
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        // another caller won the race; use whatever it published
                        return@runCatching file.exists()
                    }
                    true
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (!ok) {
                failedAt[key] = System.currentTimeMillis()
                return null
            }
            failedAt.remove(key)
            trimCache(context)
        }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: run {
                file.delete()
                return null
            }
        remember(key, bmp)
        return bmp
    }
}
