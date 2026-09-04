package app.gridfix.android.platform

import app.gridfix.android.data.GraphicTypes
import app.gridfix.android.data.TacGraphic
import java.util.Locale

/**
 * Portable GeoJSON for tactical graphics - pure Kotlin, no Android imports.
 *
 * This is the interchange shape MapLibre consumes on both platforms (a GeoJSON
 * source plus line/fill/symbol layers) and the shape a future "import overlay"
 * feature would read. The geometry rule mirrors `InterchangeFiles.buildKml`
 * exactly, and GoldenVectors.graphicGeometry holds the same table for the Swift
 * port, so the two cannot answer differently for the same graphic.
 *
 * Moves to `:core` verbatim once [TacGraphic] does.
 */

/**
 * Point / LineString / Polygon for this graphic's vertices, or null when there
 * is nothing to draw.
 *
 * Null matters. A graphic with no vertices has no valid GeoJSON geometry at
 * all; emitting a Point with an empty coordinate array produces a document that
 * fails to parse, and one bad feature takes the whole layer down with it.
 */
fun graphicGeometryKind(type: String, vertexCount: Int): String? = when {
    vertexCount <= 0 -> null
    vertexCount == 1 -> "Point"
    GraphicTypes.isArea(type) && vertexCount >= 3 -> "Polygon"
    else -> "LineString"
}

private fun lonLat(lat: Double, lon: Double): String =
    String.format(Locale.US, "[%.7f,%.7f]", lon, lat)

/**
 * One GeoJSON Feature for this graphic, or null when it has no geometry.
 * Properties carry the metadata the style layers filter on.
 */
fun TacGraphic.toGeoJsonFeature(): String? {
    val kind = graphicGeometryKind(type, points.size) ?: return null
    val geometry = when (kind) {
        "Point" -> {
            val p = points.first()
            "{\"type\":\"Point\",\"coordinates\":${lonLat(p.lat, p.lon)}}"
        }
        "Polygon" -> {
            // A GeoJSON ring must close: repeat the first vertex last.
            val ring = points + points.first()
            val coords = ring.joinToString(",") { lonLat(it.lat, it.lon) }
            "{\"type\":\"Polygon\",\"coordinates\":[[$coords]]}"
        }
        else -> {
            val coords = points.joinToString(",") { lonLat(it.lat, it.lon) }
            "{\"type\":\"LineString\",\"coordinates\":[$coords]}"
        }
    }
    return "{\"type\":\"Feature\",\"geometry\":$geometry,\"properties\":{" +
        "\"id\":${jsonStr(id)}," +
        "\"name\":${jsonStr(name)}," +
        "\"tacType\":${jsonStr(type)}," +
        "\"folder\":${jsonStr(folder)}," +
        "\"affiliation\":${jsonStr(affiliation)}," +
        "\"echelon\":${jsonStr(echelon)}}}"
}

/** A whole overlay as one FeatureCollection. Graphics with no geometry drop out. */
fun List<TacGraphic>.toGeoJson(): String =
    "{\"type\":\"FeatureCollection\",\"features\":[" +
        mapNotNull { it.toGeoJsonFeature() }.joinToString(",") +
        "]}"

/**
 * JSON string escaping. Every control character has to go: a raw tab or newline
 * inside a JSON string is invalid, and a user is perfectly capable of pasting
 * one into a graphic name.
 */
internal fun jsonStr(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}
