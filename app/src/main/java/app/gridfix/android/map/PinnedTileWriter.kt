package app.gridfix.android.map

import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.InputStream

/**
 * Tile writer for a deliberate "download this area": every tile it stores gets
 * a far-future expiry instead of the server's, so the browse cache (which trims
 * in expiry order) evicts these last, and it reports no tile as already present
 * so the download re-fetches and re-pins tiles that browsing had cached with a
 * short expiry.
 *
 * It shares osmdroid's single cache database with the map's own writer, so
 * ordinary browsing — of any provider, on any layer, at the same time — keeps
 * honouring the provider's own headers. Only tiles that pass through this
 * writer are pinned, and only public-domain layers are offered for download.
 */
class PinnedTileWriter(private val ttlMs: Long) : SqlTileWriter() {

    override fun exists(pTileSource: ITileSource, pMapTileIndex: Long): Boolean = false

    override fun saveFile(
        pTileSourceInfo: ITileSource,
        pMapTileIndex: Long,
        pStream: InputStream,
        pExpirationTime: Long?,
    ): Boolean = super.saveFile(pTileSourceInfo, pMapTileIndex, pStream, System.currentTimeMillis() + ttlMs)
}
