package app.gridfix.android.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ATAK mission data packages (tier 1-2 interop): a zip carrying a
 * MissionPackageManifest plus content files. Import reads Cursor-on-Target
 * (.cot) point events into waypoints (affiliation from the CoT type) and
 * hands any bundled GPX/KML/KMZ to the normal importers. Export writes one
 * CoT event per waypoint, routes as a GPX entry, and the manifest — a
 * package ATAK ingests directly.
 */
object DataPackage {

    // ---------------- Import ----------------

    fun parse(stream: InputStream): InterchangeFiles.ImportedData = parse(stream, ImportArchiveBudget())

    internal fun parse(stream: InputStream, budget: ImportArchiveBudget): InterchangeFiles.ImportedData {
        val wps = ArrayList<WaypointDraft>()
        val lines = ArrayList<InterchangeFiles.ImportedLine>()
        val areas = ArrayList<InterchangeFiles.ImportedLine>()
        val tracks = ArrayList<InterchangeFiles.ImportedTrack>()

        ZipInputStream(stream).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                val lower = name.lowercase(Locale.US)
                val wanted = !entry.isDirectory && (lower.endsWith(".cot") || lower.endsWith(".gpx") ||
                    lower.endsWith(".kml") || lower.endsWith(".kmz"))
                // Never return a partial package after silently dropping a supported
                // file. Ignored entries also consume the decompression budget.
                val bytes = budget.readEntry(zin, entry, name, wanted)
                if (bytes != null) {
                    when {
                        lower.endsWith(".cot") ->
                            parseCot(ByteArrayInputStream(bytes))?.let { wps.add(it) }
                        else -> {
                            val data = if (lower.endsWith(".kmz")) {
                                InterchangeFiles.parseKmz(ByteArrayInputStream(bytes), budget, name)
                            } else InterchangeFiles.parse(name, ByteArrayInputStream(bytes))
                            data?.let { d ->
                                wps.addAll(d.waypoints)
                                lines.addAll(d.lines)
                                areas.addAll(d.areas)
                                tracks.addAll(d.tracks)
                            }
                        }
                    }
                }
                zin.closeEntry()
            }
        }
        return InterchangeFiles.ImportedData(
            waypoints = wps,
            lines = lines,
            areas = areas,
            tracks = tracks,
        )
    }

    /** One CoT event file -> a waypoint draft (null when it has no position). */
    private fun parseCot(stream: InputStream): WaypointDraft? = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var lat: Double? = null
        var lon: Double? = null
        var type = ""
        var callsign = ""
        var uid = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "event" -> {
                        type = parser.getAttributeValue(null, "type") ?: ""
                        uid = parser.getAttributeValue(null, "uid") ?: ""
                    }
                    "point" -> {
                        // The same range check the GPX and KML paths already used. Without
                        // it a NaN in one .cot aborted the entire import with "Forbidden
                        // numeric value", and a lat of 500 was stored as written.
                        lat = InterchangeFiles.coord(parser.getAttributeValue(null, "lat"), 90.0)
                            .takeIf { it.isFinite() }
                        lon = InterchangeFiles.coord(parser.getAttributeValue(null, "lon"), 180.0)
                            .takeIf { it.isFinite() }
                    }
                    "contact" -> {
                        callsign = parser.getAttributeValue(null, "callsign") ?: callsign
                    }
                }
            }
            event = parser.next()
        }
        val la = lat ?: return null
        val lo = lon ?: return null
        if (la == 0.0 && lo == 0.0) return null
        // Routes (b-m-r), drawings (u-d-*) and chat/position reports are not markers
        if (type.startsWith("b-m-r") || type.startsWith("u-d-") || type.startsWith("b-t-")) return null
        WaypointDraft(
            name = callsign.ifBlank { uid.ifBlank { "ATAK point" } }.take(30),
            lat = la,
            lon = lo,
            folder = InterchangeFiles.IMPORT_FOLDER,
            symbol = "target",
            affiliation = affiliationFromCotType(type),
        )
    }.getOrNull()

    /** CoT type "a-f-G-U-C" -> affiliation from the second field. */
    private fun affiliationFromCotType(type: String): String {
        val parts = type.split("-")
        if (parts.size < 2 || parts[0] != "a") return "none"
        return when (parts[1].lowercase(Locale.US)) {
            "f", "a" -> "friendly"    // friend / assumed friend
            "h", "j", "k" -> "hostile" // hostile / joker / faker
            "n" -> "neutral"
            "u", "p", "s" -> "unknown" // unknown / pending / suspect
            else -> "none"
        }
    }

    // ---------------- Export ----------------

    private fun cotType(affiliation: String): String = when (affiliation) {
        "friendly" -> "a-f-G"
        "hostile" -> "a-h-G"
        "neutral" -> "a-n-G"
        else -> "a-u-G"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Write a complete mission package. Waypoints become individual CoT
     * events; routes ride along as one GPX entry ATAK can also read.
     */
    fun build(
        out: OutputStream,
        waypoints: List<Waypoint>,
        routes: List<TacGraphic>,
        nowMillis: Long,
    ) {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val time = sdf.format(Date(nowMillis))
        val stale = sdf.format(Date(nowMillis + 365L * 24 * 3600 * 1000))
        val pkgUid = "gridfix-$nowMillis"

        val zip = ZipOutputStream(out)
        val entries = ArrayList<String>()

        waypoints.forEachIndexed { i, w ->
            val entryName = "cot/gridfix-$i.cot"
            entries.add(entryName)
            // Stable per waypoint (no list index): a re-sent package updates the
            // same marker in ATAK instead of creating a duplicate.
            val uid = "GRIDFIX-" + w.id
            val cot = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<event version=\"2.0\" uid=\"").append(esc(uid))
                append("\" type=\"").append(if (w.kind == KIND_UNIT) cotType(w.affiliation) else "b-m-p-w")
                append("\" time=\"").append(time)
                append("\" start=\"").append(time)
                append("\" stale=\"").append(stale)
                append("\" how=\"h-g-i-g-o\">\n")
                append(
                    String.format(
                        Locale.US,
                        "  <point lat=\"%.7f\" lon=\"%.7f\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>\n",
                        w.lat, w.lon,
                    )
                )
                append("  <detail>\n")
                append("    <contact callsign=\"").append(esc(w.name)).append("\"/>\n")
                if (w.folder.isNotBlank()) {
                    append("    <remarks>").append(esc(w.folder)).append("</remarks>\n")
                }
                append("  </detail>\n")
                append("</event>\n")
            }
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(cot.toByteArray())
            zip.closeEntry()
        }

        if (routes.isNotEmpty()) {
            entries.add("routes.gpx")
            val gpx = InterchangeFiles.buildGpx(emptyList(), routes, emptyList())
            zip.putNextEntry(ZipEntry("routes.gpx"))
            zip.write(gpx.toByteArray())
            zip.closeEntry()
        }

        val manifest = buildString {
            append("<MissionPackageManifest version=\"2\">\n")
            append("  <Configuration>\n")
            append("    <Parameter name=\"uid\" value=\"").append(esc(pkgUid)).append("\"/>\n")
            append("    <Parameter name=\"name\" value=\"MGRS GPS export\"/>\n")
            append("  </Configuration>\n")
            append("  <Contents>\n")
            for (e in entries) {
                append("    <Content ignore=\"false\" zipEntry=\"").append(esc(e)).append("\"/>\n")
            }
            append("  </Contents>\n")
            append("</MissionPackageManifest>\n")
        }
        zip.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
        zip.write(manifest.toByteArray())
        zip.closeEntry()
        zip.finish()
    }

    fun buildBytes(
        waypoints: List<Waypoint>,
        routes: List<TacGraphic>,
        nowMillis: Long,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        build(bos, waypoints, routes, nowMillis)
        return bos.toByteArray()
    }
}
