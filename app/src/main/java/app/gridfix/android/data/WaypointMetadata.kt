package app.gridfix.android.data

import org.json.JSONObject
import java.util.Locale

/** Optional interchange fields; independent of a waypoint's tactical affiliation. */
data class WaypointMetadata(
    val color: String? = null,
    val milgpsSymbolCode: Int? = null,
    val elevationMeters: Double? = null,
    val timestampMillis: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        color?.let { put("color", it) }
        milgpsSymbolCode?.let { put("milgpsSymbolCode", it) }
        elevationMeters?.takeIf { it.isFinite() }?.let { put("elevationMeters", it) }
        timestampMillis?.let { put("timestampMillis", it) }
    }

    companion object {
        fun fromJson(json: JSONObject?): WaypointMetadata = WaypointMetadata(
            color = json?.opt("color")?.takeIf { it is String }?.toString()
                ?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
            milgpsSymbolCode = json?.opt("milgpsSymbolCode")?.toString()?.toIntOrNull(),
            elevationMeters = json?.opt("elevationMeters")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() },
            timestampMillis = json?.opt("timestampMillis")?.toString()?.toLongOrNull(),
        )
    }
}

enum class MilGpsShape(val label: String) { CROSS("Cross"), CIRCLE("Circle"), TRIANGLE("Triangle"), SQUARE("Square"), STAR("Star") }

data class MilGpsSymbol(val shape: MilGpsShape, val character: String?)

/** Documented by https://milgps.com/userguide/frequently-asked-questions/csv-format/ */
object MilGpsSymbols {
    val colors = listOf("red", "orange", "yellow", "green", "blue", "cyan", "magenta")
    val characters = ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() } + listOf("!", "?"))

    fun decode(code: Int?): MilGpsSymbol? {
        if (code == null || code < 0) return null
        val shape = MilGpsShape.entries.getOrNull(code / 1000) ?: return null
        val suffix = code % 1000
        if (shape == MilGpsShape.CROSS && suffix != 0) return null
        val character = when (suffix) {
            0 -> null
            in 100..135 -> characters[suffix - 100]
            200 -> "!"
            201 -> "?"
            else -> return null
        }
        return MilGpsSymbol(shape, character)
    }

    fun encode(shape: MilGpsShape, character: String?): Int {
        require(character == null || character in characters)
        val suffix = when {
            shape == MilGpsShape.CROSS || character == null -> 0
            character == "!" -> 200
            character == "?" -> 201
            else -> 100 + characters.indexOf(character)
        }
        return shape.ordinal * 1000 + suffix
    }

    /** RGB values are presentation colors, never affiliation classifications. */
    fun argb(color: String?): Long? = when (color?.lowercase(Locale.ROOT)) {
        "red" -> 0xFFFF0000
        "orange" -> 0xFFFF9500
        "yellow" -> 0xFFFFFF00
        "green" -> 0xFF00DD00
        "blue" -> 0xFF0080FF
        "cyan" -> 0xFF00DDDD
        "magenta" -> 0xFFEE00EE
        else -> null
    }
}
