package app.gridfix.android.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * GPX 1.1 and KML/KMZ interchange: the file formats land-nav and TAK users
 * actually trade. Import is tolerant (skips what it can't read); export writes
 * plain, widely-accepted documents. Everything is streamed with XmlPullParser —
 * no external dependencies.
 */
object InterchangeFiles {
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val MILGPS_NAMESPACE = "https://milgps.com/gpx/v1"
    private fun isGpxNamespace(namespace: String?): Boolean = namespace.isNullOrEmpty() ||
        namespace == GPX_NAMESPACE || namespace == "http://www.topografix.com/GPX/1/0"

    /**
     * A coordinate attribute as a finite, in-range double, else NaN (rejects "NaN",
     * "Infinity", 120). Internal rather than private so the CoT path in [DataPackage]
     * uses the same check instead of its own looser one.
     */
    internal fun coord(raw: String?, limit: Double): Double {
        val v = raw?.trim()?.toDoubleOrNull() ?: return Double.NaN
        return if (v.isFinite() && v >= -limit && v <= limit) v else Double.NaN
    }

    data class ImportedLine(val name: String, val folder: String, val points: List<GeoVertex>)
    data class ImportedTrack(val name: String, val points: List<TrackPoint>)

    data class ImportedData(
        val waypoints: List<WaypointDraft> = emptyList(),
        val lines: List<ImportedLine> = emptyList(),      // become route graphics
        val areas: List<ImportedLine> = emptyList(),      // become assembly-area graphics
        val tracks: List<ImportedTrack> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = waypoints.isEmpty() && lines.isEmpty() && areas.isEmpty() && tracks.isEmpty()

        fun summary(): String {
            val parts = mutableListOf<String>()
            if (waypoints.isNotEmpty()) parts.add("${waypoints.size} waypoints")
            if (lines.isNotEmpty()) parts.add("${lines.size} lines")
            if (areas.isNotEmpty()) parts.add("${areas.size} areas")
            if (tracks.isNotEmpty()) parts.add("${tracks.size} tracks")
            return if (parts.isEmpty()) "nothing importable found" else "Imported " + parts.joinToString(", ")
        }
    }

    const val IMPORT_FOLDER = "Imported"

    /** Route by file name; returns null when the format isn't recognized. */
    fun parse(fileName: String, stream: InputStream): ImportedData? {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".gpx") -> parseGpx(stream)
            lower.endsWith(".kml") -> parseKml(stream)
            lower.endsWith(".kmz") -> parseKmz(stream)
            // ATAK mission data packages (and generic zips of the above)
            lower.endsWith(".zip") || lower.endsWith(".dpk") -> DataPackage.parse(stream)
            else -> null
        }
    }

    // ---------------- GPX ----------------

    fun parseGpx(stream: InputStream): ImportedData {
        val wps = ArrayList<WaypointDraft>()
        val tracks = ArrayList<ImportedTrack>()
        val lines = ArrayList<ImportedLine>()

        val parser = newParser()
        parser.setInput(stream, null)

        var wptLat = 0.0
        var wptLon = 0.0
        var inWpt = false
        var wptDepth = -1
        var wptExtensionsDepth = -1
        var metadata = WaypointMetadata()
        var name = ""
        var trkName = ""
        var trkPts = ArrayList<TrackPoint>()
        var trkSegments = ArrayList<List<TrackPoint>>()
        var inTrk = false
        var trkDepth = -1
        var rteName = ""
        var rtePts = ArrayList<GeoVertex>()
        var inRte = false
        var pendingPoint: TrackPoint? = null
        var pointDepth = -1
        var textBuf = ""

        fun finishSegment() {
            if (trkPts.isNotEmpty()) trkSegments.add(trkPts)
            trkPts = ArrayList()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuf = ""
                    val isGpx = isGpxNamespace(parser.namespace)
                    when (if (isGpx) parser.name else "") {
                        "wpt" -> if (parser.depth == 2) {
                            inWpt = true
                            wptDepth = parser.depth
                            wptExtensionsDepth = -1
                            metadata = WaypointMetadata()
                            name = ""
                            wptLat = coord(parser.getAttributeValue(null, "lat"), 90.0)
                            wptLon = coord(parser.getAttributeValue(null, "lon"), 180.0)
                        }
                        "extensions" -> if (inWpt && parser.depth == wptDepth + 1) {
                            wptExtensionsDepth = parser.depth
                        }
                        "trk" -> {
                            inTrk = true
                            trkDepth = parser.depth
                            trkName = ""
                            trkPts = ArrayList()
                            trkSegments = ArrayList()
                        }
                        "trkseg" -> if (inTrk) finishSegment()
                        "rte" -> {
                            inRte = true
                            rteName = ""
                            rtePts = ArrayList()
                        }
                        "trkpt" -> {
                            pointDepth = parser.depth
                            val lat = coord(parser.getAttributeValue(null, "lat"), 90.0)
                            val lon = coord(parser.getAttributeValue(null, "lon"), 180.0)
                            pendingPoint = if (lat.isFinite() && lon.isFinite() && inTrk)
                                TrackPoint(lat, lon, 0L, NO_ALTITUDE) else null
                        }
                        "rtept" -> {
                            val lat = coord(parser.getAttributeValue(null, "lat"), 90.0)
                            val lon = coord(parser.getAttributeValue(null, "lon"), 180.0)
                            if (!lat.isNaN() && !lon.isNaN() && inRte) {
                                rtePts.add(GeoVertex(lat, lon))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> textBuf += parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val isGpx = isGpxNamespace(parser.namespace)
                    if (inWpt && wptExtensionsDepth == wptDepth + 1 &&
                        parser.depth == wptExtensionsDepth + 1 && parser.namespace == MILGPS_NAMESPACE
                    ) {
                        when (parser.name) {
                            "color" -> metadata = metadata.copy(color = textBuf.trim().lowercase(Locale.ROOT).ifBlank { null })
                            "symbolcode" -> metadata = metadata.copy(milgpsSymbolCode = textBuf.trim().toIntOrNull())
                        }
                    }
                    when (if (isGpx) parser.name else "") {
                        "name" -> {
                            val t = textBuf.trim()
                            if (inWpt && parser.depth == wptDepth + 1 && name.isEmpty()) name = t
                            else if (inTrk && parser.depth == trkDepth + 1 && trkName.isEmpty()) trkName = t
                            else if (inRte && rteName.isEmpty()) rteName = t
                        }
                        "ele" -> {
                            val altitude = textBuf.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                            if (inWpt && parser.depth == wptDepth + 1) metadata = metadata.copy(elevationMeters = altitude)
                            else if (parser.depth == pointDepth + 1) pendingPoint = pendingPoint?.copy(alt = altitude ?: NO_ALTITUDE)
                        }
                        "time" -> {
                            if (inWpt && parser.depth == wptDepth + 1) metadata = metadata.copy(timestampMillis = parseIsoTimeOrNull(textBuf.trim()))
                            else if (parser.depth == pointDepth + 1) pendingPoint = pendingPoint?.copy(time = parseIsoTime(textBuf.trim()))
                        }
                        "extensions" -> if (parser.depth == wptExtensionsDepth) wptExtensionsDepth = -1
                        "trkpt" -> {
                            pendingPoint?.let { trkPts.add(it) }
                            pendingPoint = null
                        }
                        "trkseg" -> if (inTrk) finishSegment()
                        "wpt" -> if (parser.depth == wptDepth) {
                            if (inWpt && !wptLat.isNaN() && !wptLon.isNaN()) {
                                wps.add(
                                    WaypointDraft(
                                        name = name.ifBlank { "WP ${wps.size + 1}" },
                                        lat = wptLat,
                                        lon = wptLon,
                                        folder = IMPORT_FOLDER,
                                        symbol = "flag",
                                        affiliation = "none",
                                        metadata = metadata,
                                    )
                                )
                            }
                            inWpt = false
                            wptDepth = -1
                            wptExtensionsDepth = -1
                        }
                        "trk" -> {
                            if (inTrk) {
                                finishSegment()
                                val baseName = trkName.ifBlank { "Imported track" }
                                trkSegments.forEachIndexed { index, points ->
                                    // Each GPX segment is a continuous recording. Keeping separate
                                    // tracks prevents a GPS outage becoming a made-up connecting leg.
                                    val segmentName = if (trkSegments.size == 1) baseName
                                        else "$baseName — segment ${index + 1}"
                                    tracks.add(ImportedTrack(segmentName, points))
                                }
                            }
                            inTrk = false
                            pendingPoint = null
                        }
                        "rte" -> {
                            if (inRte && rtePts.size >= 2) {
                                lines.add(
                                    ImportedLine(rteName.ifBlank { "Imported route" }, IMPORT_FOLDER, rtePts)
                                )
                            }
                            inRte = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return ImportedData(waypoints = wps, lines = lines, tracks = tracks)
    }

    fun buildGpx(
        waypoints: List<Waypoint>,
        routes: List<TacGraphic>,
        tracks: List<Pair<TrackInfo, List<TrackPoint>>>,
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MGRS GPS\" xmlns=\"$GPX_NAMESPACE\" xmlns:milgps=\"$MILGPS_NAMESPACE\">\n")
        for (w in waypoints) {
            sb.append(
                String.format(
                    Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">", w.lat, w.lon,
                )
            )
            val m = w.metadata
            m.elevationMeters?.takeIf { it.isFinite() }?.let {
                sb.append("<ele>").append(BigDecimal.valueOf(it).toPlainString()).append("</ele>")
            }
            m.timestampMillis?.let { sb.append("<time>${Instant.ofEpochMilli(it)}</time>") }
            sb.append("<name>").append(escapeXml(w.name)).append("</name>")
            if (m.color != null || m.milgpsSymbolCode != null) {
                sb.append("<extensions>")
                m.milgpsSymbolCode?.let { sb.append("<milgps:symbolcode>$it</milgps:symbolcode>") }
                m.color?.let { sb.append("<milgps:color>").append(escapeXml(it)).append("</milgps:color>") }
                sb.append("</extensions>")
            }
            sb.append("</wpt>\n")
        }
        for (r in routes) {
            sb.append("  <rte><name>").append(escapeXml(r.name)).append("</name>\n")
            for (p in r.points) {
                sb.append(
                    String.format(Locale.US, "    <rtept lat=\"%.7f\" lon=\"%.7f\"/>\n", p.lat, p.lon)
                )
            }
            sb.append("  </rte>\n")
        }
        for ((info, pts) in tracks) {
            sb.append("  <trk><name>").append(escapeXml(info.name)).append("</name><trkseg>\n")
            for (p in pts) {
                val ele = if (p.alt.hasAltitude()) String.format(Locale.US, "<ele>%.1f</ele>", p.alt) else ""
                val time = if (p.time != 0L) "<time>${Instant.ofEpochMilli(p.time)}</time>" else ""
                sb.append(
                    String.format(
                        Locale.US,
                        "    <trkpt lat=\"%.7f\" lon=\"%.7f\">%s%s</trkpt>\n",
                        p.lat, p.lon, ele, time,
                    )
                )
            }
            sb.append("  </trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    // ---------------- KML / KMZ ----------------

    fun parseKmz(stream: InputStream): ImportedData {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".kml")) {
                    return parseKml(zip)
                }
                entry = zip.nextEntry
            }
        }
        return ImportedData()
    }

    fun parseKml(stream: InputStream): ImportedData {
        val wps = ArrayList<WaypointDraft>()
        val lines = ArrayList<ImportedLine>()
        val areas = ArrayList<ImportedLine>()

        val parser = newParser()
        parser.setInput(stream, null)

        val folderStack = ArrayDeque<Pair<Int, String>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (if (isKmlNamespace(parser.namespace)) parser.name else "") {
                        "Folder" -> folderStack.addLast(parser.depth to "")
                        "name" -> if (folderStack.lastOrNull()?.first == parser.depth - 1) {
                            val depth = folderStack.removeLast().first
                            folderStack.addLast(depth to parser.nextText().trim())
                        }
                        "Placemark" -> {
                            val folder = folderStack.lastOrNull { it.second.isNotBlank() }?.second ?: IMPORT_FOLDER
                            val (name, geometries) = readKmlPlacemark(parser)
                            for (geometry in geometries) {
                                val pts = geometry.points
                                when {
                                    geometry.type == "Point" && pts.size == 1 -> wps.add(
                                        WaypointDraft(
                                            name = name.ifBlank { "WP ${wps.size + 1}" },
                                            lat = pts[0].lat,
                                            lon = pts[0].lon,
                                            folder = folder,
                                            symbol = "flag",
                                            affiliation = "none",
                                        )
                                    )
                                    geometry.type == "LineString" && pts.size >= 2 -> lines.add(
                                        ImportedLine(name.ifBlank { "Imported line" }, folder, pts)
                                    )
                                    geometry.type == "Polygon" && pts.size >= 3 -> areas.add(
                                        ImportedLine(name.ifBlank { "Imported area" }, folder, pts)
                                    )
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (isKmlNamespace(parser.namespace) && parser.name == "Folder" &&
                    folderStack.lastOrNull()?.first == parser.depth
                ) {
                    folderStack.removeLast()
                }
            }
            event = parser.next()
        }
        return ImportedData(waypoints = wps, lines = lines, areas = areas)
    }

    private data class KmlGeometry(val type: String, val points: List<GeoVertex>)

    private val kmlNamespaces = setOf(
        "http://www.opengis.net/kml/2.2", "http://earth.google.com/kml/2.0", "http://earth.google.com/kml/2.1",
        "http://earth.google.com/kml/2.2",
    )
    private fun isKmlNamespace(namespace: String?): Boolean = namespace.isNullOrEmpty() || namespace in kmlNamespaces

    /** Consume one element, visiting only its direct children. Each visitor consumes its child. */
    private fun readKmlChildren(parser: XmlPullParser, child: () -> Unit) {
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) return
            if (parser.eventType == XmlPullParser.START_TAG) child()
        }
    }

    private fun skipKmlElement(parser: XmlPullParser) {
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    private fun readKmlPlacemark(parser: XmlPullParser): Pair<String, List<KmlGeometry>> {
        var name = ""
        val geometries = ArrayList<KmlGeometry>()
        readKmlChildren(parser) {
            if (isKmlNamespace(parser.namespace) && parser.name == "name") name = parser.nextText().trim()
            else geometries.addAll(readKmlGeometry(parser))
        }
        return name to geometries
    }

    /** Each MultiGeometry child owns its coordinates; siblings can never exchange positions or types. */
    private fun readKmlGeometry(parser: XmlPullParser, nesting: Int = 0): List<KmlGeometry> {
        require(nesting <= 64) { "KML geometry nesting is too deep" }
        if (!isKmlNamespace(parser.namespace)) {
            skipKmlElement(parser)
            return emptyList()
        }
        val type = parser.name
        return when (type) {
            "MultiGeometry" -> buildList {
                readKmlChildren(parser) { addAll(readKmlGeometry(parser, nesting + 1)) }
            }
            "Point", "LineString" -> listOf(KmlGeometry(type, readKmlGeometryCoordinates(parser, false)))
            "Polygon" -> {
                var points = emptyList<GeoVertex>()
                readKmlChildren(parser) {
                    when (if (isKmlNamespace(parser.namespace)) parser.name else "") {
                        "outerBoundaryIs" -> readKmlChildren(parser) {
                            if (isKmlNamespace(parser.namespace) && parser.name == "LinearRing") {
                                points = readKmlGeometryCoordinates(parser, true)
                            } else skipKmlElement(parser)
                        }
                        // Our area model has one outline. Filling a hole would change the supplied area.
                        "innerBoundaryIs" -> throw IllegalArgumentException("KML polygons with holes are not supported")
                        else -> skipKmlElement(parser)
                    }
                }
                listOf(KmlGeometry(type, points))
            }
            else -> {
                skipKmlElement(parser)
                emptyList()
            }
        }
    }

    private fun readKmlGeometryCoordinates(parser: XmlPullParser, polygon: Boolean): List<GeoVertex> {
        var points = emptyList<GeoVertex>()
        readKmlChildren(parser) {
            if (isKmlNamespace(parser.namespace) && parser.name == "coordinates") {
                points = parseKmlCoordinates(parser.nextText(), polygon)
            } else skipKmlElement(parser)
        }
        return points
    }

    /** KML coordinate lists are "lon,lat[,alt]" tuples separated by whitespace. */
    private fun parseKmlCoordinates(text: String, polygon: Boolean): List<GeoVertex> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoVertex(lat, lon) else null
        }.let { pts ->
            // drop the duplicated closing vertex polygons carry
            if (polygon && pts.size >= 2 && pts.first() == pts.last()) pts.dropLast(1) else pts
        }

    fun buildKml(
        waypoints: List<Waypoint>,
        graphics: List<TacGraphic>,
        tracks: List<Pair<TrackInfo, List<TrackPoint>>>,
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        sb.append("<name>MGRS GPS export</name>\n")

        for ((folder, list) in waypoints.groupBy { it.folder }) {
            sb.append("<Folder><name>").append(escapeXml(folder)).append("</name>\n")
            for (w in list) {
                sb.append("<Placemark><name>").append(escapeXml(w.name)).append("</name>")
                sb.append(
                    String.format(
                        Locale.US, "<Point><coordinates>%.7f,%.7f,0</coordinates></Point>",
                        w.lon, w.lat,
                    )
                )
                sb.append("</Placemark>\n")
            }
            sb.append("</Folder>\n")
        }

        if (graphics.isNotEmpty()) {
            sb.append("<Folder><name>Graphics</name>\n")
            for (g in graphics) {
                val geometry = graphicExportGeometry(g) ?: continue
                val points = geometry.points
                val closed = geometry.area
                sb.append("<Placemark><name>")
                    .append(escapeXml(GraphicTypes.labelPrefix(g.type) + g.name))
                    .append("</name>")
                val coords = StringBuilder()
                for (p in points) {
                    coords.append(String.format(Locale.US, "%.7f,%.7f,0 ", p.lon, p.lat))
                }
                if (closed && points.first() != points.last()) {
                    coords.append(
                        String.format(Locale.US, "%.7f,%.7f,0", points[0].lon, points[0].lat)
                    )
                }
                if (points.size == 1) {
                    // Point graphics (TRP, checkpoint, text) export as placemarks
                    sb.append(
                        String.format(
                            Locale.US, "<Point><coordinates>%.7f,%.7f,0</coordinates></Point>",
                            points[0].lon, points[0].lat,
                        )
                    )
                } else if (closed) {
                    sb.append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
                        .append(coords.toString().trim())
                        .append("</coordinates></LinearRing></outerBoundaryIs></Polygon>")
                } else {
                    sb.append("<LineString><tessellate>1</tessellate><coordinates>")
                        .append(coords.toString().trim())
                        .append("</coordinates></LineString>")
                }
                sb.append("</Placemark>\n")
            }
            sb.append("</Folder>\n")
        }

        if (tracks.isNotEmpty()) {
            sb.append("<Folder><name>Tracks</name>\n")
            for ((info, pts) in tracks) {
                sb.append("<Placemark><name>").append(escapeXml(info.name)).append("</name>")
                sb.append("<LineString><tessellate>1</tessellate><coordinates>")
                for (p in pts) {
                    sb.append(String.format(Locale.US, "%.7f,%.7f,0 ", p.lon, p.lat))
                }
                sb.append("</coordinates></LineString></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }

        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    // ---------------- helpers ----------------

    private fun parseIsoTimeOrNull(text: String): Long? = runCatching {
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseIsoTime(text: String): Long = parseIsoTimeOrNull(text) ?: 0L

    private fun newParser(): XmlPullParser = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }.newPullParser()

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
