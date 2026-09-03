package app.gridfix.android.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * GPX 1.1 and KML/KMZ interchange: the file formats land-nav and TAK users
 * actually trade. Import is tolerant (skips what it can't read); export writes
 * plain, widely-accepted documents. Everything is streamed with XmlPullParser —
 * no external dependencies.
 */
object InterchangeFiles {

    /** A coordinate attribute as a finite, in-range double, else NaN (rejects "NaN", "Infinity", 120). */
    private fun coord(raw: String?, limit: Double): Double {
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

        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        var wptLat = 0.0
        var wptLon = 0.0
        var inWpt = false
        var name = ""
        var trkName = ""
        var trkPts = ArrayList<TrackPoint>()
        var inTrk = false
        var rteName = ""
        var rtePts = ArrayList<GeoVertex>()
        var inRte = false
        var pendingTime = 0L
        var pendingEle = NO_ALTITUDE
        var textBuf = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuf = ""
                    when (parser.name) {
                        "wpt" -> {
                            inWpt = true
                            name = ""
                            wptLat = coord(parser.getAttributeValue(null, "lat"), 90.0)
                            wptLon = coord(parser.getAttributeValue(null, "lon"), 180.0)
                        }
                        "trk" -> {
                            inTrk = true
                            trkName = ""
                            trkPts = ArrayList()
                        }
                        "rte" -> {
                            inRte = true
                            rteName = ""
                            rtePts = ArrayList()
                        }
                        "trkpt" -> {
                            val lat = coord(parser.getAttributeValue(null, "lat"), 90.0)
                            val lon = coord(parser.getAttributeValue(null, "lon"), 180.0)
                            pendingTime = 0L
                            pendingEle = NO_ALTITUDE
                            if (!lat.isNaN() && !lon.isNaN() && inTrk) {
                                trkPts.add(TrackPoint(lat, lon, 0L, 0.0))
                            }
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
                    when (parser.name) {
                        "name" -> {
                            val t = textBuf.trim()
                            if (inWpt && name.isEmpty()) name = t
                            else if (inTrk && trkName.isEmpty()) trkName = t
                            else if (inRte && rteName.isEmpty()) rteName = t
                        }
                        "ele" -> pendingEle = textBuf.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: NO_ALTITUDE
                        "time" -> pendingTime = parseIsoTime(textBuf.trim())
                        "trkpt" -> {
                            if (inTrk && trkPts.isNotEmpty()) {
                                val last = trkPts.removeAt(trkPts.size - 1)
                                trkPts.add(last.copy(time = pendingTime, alt = pendingEle))
                            }
                        }
                        "wpt" -> {
                            if (inWpt && !wptLat.isNaN() && !wptLon.isNaN()) {
                                wps.add(
                                    WaypointDraft(
                                        name = name.ifBlank { "WP ${wps.size + 1}" },
                                        lat = wptLat,
                                        lon = wptLon,
                                        folder = IMPORT_FOLDER,
                                        symbol = "flag",
                                        affiliation = "none",
                                    )
                                )
                            }
                            inWpt = false
                        }
                        "trk" -> {
                            if (inTrk && trkPts.size >= 2) {
                                tracks.add(
                                    ImportedTrack(trkName.ifBlank { "Imported track" }, trkPts)
                                )
                            }
                            inTrk = false
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
        sb.append("<gpx version=\"1.1\" creator=\"MGRS GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in waypoints) {
            sb.append(
                String.format(
                    Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>\n",
                    w.lat, w.lon, escapeXml(w.name),
                )
            )
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
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        for ((info, pts) in tracks) {
            sb.append("  <trk><name>").append(escapeXml(info.name)).append("</name><trkseg>\n")
            for (p in pts) {
                val ele = if (p.alt.hasAltitude()) String.format(Locale.US, "<ele>%.1f</ele>", p.alt) else ""
                sb.append(
                    String.format(
                        Locale.US,
                        "    <trkpt lat=\"%.7f\" lon=\"%.7f\">%s<time>%s</time></trkpt>\n",
                        p.lat, p.lon, ele, sdf.format(Date(p.time)),
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

        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        val folderStack = ArrayDeque<String>()
        var awaitingFolderName = false
        var inPlacemark = false
        var pmName = ""
        var geomType = ""        // point / line / polygon
        var coordText = ""
        var inCoordinates = false
        var textBuf = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuf = ""
                    when (parser.name) {
                        "Folder" -> {
                            folderStack.addLast("")
                            awaitingFolderName = true
                        }
                        "Placemark" -> {
                            inPlacemark = true
                            pmName = ""
                            geomType = ""
                            coordText = ""
                        }
                        "Point" -> if (inPlacemark) geomType = "point"
                        "LineString" -> if (inPlacemark) geomType = "line"
                        "Polygon" -> if (inPlacemark) geomType = "polygon"
                        "coordinates" -> inCoordinates = true
                    }
                }
                XmlPullParser.TEXT -> textBuf += parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "name" -> {
                            val t = textBuf.trim()
                            if (inPlacemark) {
                                if (pmName.isEmpty()) pmName = t
                            } else if (awaitingFolderName && folderStack.isNotEmpty()) {
                                folderStack.removeLast()
                                folderStack.addLast(t)
                                awaitingFolderName = false
                            }
                        }
                        "coordinates" -> {
                            inCoordinates = false
                            if (inPlacemark && coordText.isEmpty()) coordText = textBuf
                        }
                        "Folder" -> {
                            if (folderStack.isNotEmpty()) folderStack.removeLast()
                            awaitingFolderName = false
                        }
                        "Placemark" -> {
                            if (inPlacemark) {
                                val folder = folderStack.lastOrNull { it.isNotBlank() } ?: IMPORT_FOLDER
                                val pts = parseKmlCoordinates(coordText)
                                when {
                                    geomType == "point" && pts.isNotEmpty() -> wps.add(
                                        WaypointDraft(
                                            name = pmName.ifBlank { "WP ${wps.size + 1}" },
                                            lat = pts[0].lat,
                                            lon = pts[0].lon,
                                            folder = folder,
                                            symbol = "flag",
                                            affiliation = "none",
                                        )
                                    )
                                    geomType == "line" && pts.size >= 2 -> lines.add(
                                        ImportedLine(pmName.ifBlank { "Imported line" }, folder, pts)
                                    )
                                    geomType == "polygon" && pts.size >= 3 -> areas.add(
                                        ImportedLine(pmName.ifBlank { "Imported area" }, folder, pts)
                                    )
                                }
                            }
                            inPlacemark = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return ImportedData(waypoints = wps, lines = lines, areas = areas)
    }

    /** KML coordinate lists are "lon,lat[,alt]" tuples separated by whitespace. */
    private fun parseKmlCoordinates(text: String): List<GeoVertex> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoVertex(lat, lon) else null
        }.let { pts ->
            // drop the duplicated closing vertex polygons carry
            if (pts.size >= 2 && pts.first() == pts.last()) pts.dropLast(1) else pts
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
                val closed = GraphicTypes.isArea(g.type) && g.points.size >= 3
                sb.append("<Placemark><name>")
                    .append(escapeXml(GraphicTypes.labelPrefix(g.type) + g.name))
                    .append("</name>")
                val coords = StringBuilder()
                for (p in g.points) {
                    coords.append(String.format(Locale.US, "%.7f,%.7f,0 ", p.lon, p.lat))
                }
                if (closed && g.points.isNotEmpty()) {
                    coords.append(
                        String.format(Locale.US, "%.7f,%.7f,0", g.points[0].lon, g.points[0].lat)
                    )
                }
                if (g.points.size == 1) {
                    // Point graphics (TRP, checkpoint, text) export as placemarks
                    sb.append(
                        String.format(
                            Locale.US, "<Point><coordinates>%.7f,%.7f,0</coordinates></Point>",
                            g.points[0].lon, g.points[0].lat,
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

    private fun parseIsoTime(text: String): Long = runCatching {
        val cleaned = text.replace(Regex("\\.\\d+"), "").removeSuffix("Z")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(cleaned)?.time ?: 0L
    }.getOrDefault(0L)

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
