package app.gridfix.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.data.MilGpsSymbols
import app.gridfix.android.data.WaypointMetadata

/** MIL-STD-2525-style affiliation colors. */
object Affiliations {
    val all = listOf("none", "friendly", "hostile", "neutral", "unknown")

    fun label(key: String): String = when (key) {
        "friendly" -> "Friendly"
        "hostile" -> "Hostile"
        "neutral" -> "Neutral"
        "unknown" -> "Unknown"
        else -> "None"
    }

    fun color(key: String, fallback: Color): Color = when (key) {
        "friendly" -> Color(0xFF5BC8F5)   // crystal blue
        "hostile" -> Color(0xFFFF6B60)    // salmon red
        "neutral" -> Color(0xFF8FE38F)    // bamboo green
        "unknown" -> Color(0xFFF0E060)    // light yellow
        else -> fallback
    }
}

/**
 * Renders a waypoint marker:
 * - NATO unit keys (nato_*) render the bundled MIL-STD-2525B symbol image
 * - tactical task keys (task_*) render drawn task glyphs or letter badges
 * - shape keys render plain drawn shapes
 * - everything else renders a material icon
 * Non-NATO markers get an affiliation frame (friendly rectangle, hostile diamond,
 * neutral square, unknown circle) except tasks, which stand alone per doctrine.
 */
/** Night-vision red: luminance mapped onto the red channel, green/blue dropped. */
private val NightImageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.35f, 0.50f, 0.15f, 0f, 30f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)
private val NightRed = Color(0xFFFF3B30)

@Composable
fun WaypointMarker(
    symbol: String,
    affiliation: String,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    echelon: String = "",
    night: Boolean = false,
    rotation: Float = 0f,
    metadata: WaypointMetadata = WaypointMetadata(),
) {
    val importedColor = MilGpsSymbols.argb(metadata.color)?.let { Color(it) }
    MilGpsSymbols.decode(metadata.milgpsSymbolCode)?.let { imported ->
        MilGpsMarker(imported, if (night) NightRed else importedColor ?: MaterialTheme.colorScheme.primary, modifier, size)
        return
    }
    if (NatoSymbols.isNato(symbol)) {
        val context = LocalContext.current
        val res = NatoSymbols.resId(context, symbol)
        if (res != null) {
            Box(modifier.size(size)) {
                Image(
                    bitmap = NatoSymbols.bitmap(context, res),
                    contentDescription = NatoSymbols.label(symbol),
                    modifier = Modifier.fillMaxSize(),
                    colorFilter = if (night) NightImageFilter else null,
                    filterQuality = FilterQuality.High,
                )
                EchelonMarks(
                    echelon = echelon,
                    color = if (night) NightRed
                    else Affiliations.color(affiliation, MaterialTheme.colorScheme.primary),
                    halo = MaterialTheme.colorScheme.background,
                )
            }
            return
        }
    }

    val color = if (night) NightRed
    else importedColor ?: Affiliations.color(affiliation, MaterialTheme.colorScheme.primary)
    val isShape = WaypointSymbols.isShape(symbol)
    val isTask = WaypointSymbols.isTask(symbol)
    val taskLetter = if (isTask) WaypointSymbols.taskLetter(symbol) else null

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val frameStroke = Stroke(width = w * 0.07f)
            val glyphStroke = w * 0.08f

            if (!isTask) {
                when (affiliation) {
                    "friendly" -> drawRoundRect(
                        color = color,
                        topLeft = Offset(w * 0.04f, h * 0.20f),
                        size = Size(w * 0.92f, h * 0.60f),
                        cornerRadius = CornerRadius(w * 0.08f),
                        style = frameStroke,
                    )
                    "hostile" -> {
                        val p = Path().apply {
                            moveTo(w / 2f, h * 0.05f)
                            lineTo(w * 0.95f, h / 2f)
                            lineTo(w / 2f, h * 0.95f)
                            lineTo(w * 0.05f, h / 2f)
                            close()
                        }
                        drawPath(p, color, style = frameStroke)
                    }
                    "neutral" -> drawRect(
                        color = color,
                        topLeft = Offset(w * 0.10f, h * 0.10f),
                        size = Size(w * 0.80f, h * 0.80f),
                        style = frameStroke,
                    )
                    "unknown" -> drawCircle(
                        color = color,
                        radius = w * 0.44f,
                        style = frameStroke,
                    )
                }
            }

            if (isShape) {
                val s = w * 0.36f
                when (symbol) {
                    "dot" -> drawCircle(color, radius = s * 0.45f)
                    "square" -> drawRect(
                        color,
                        topLeft = Offset(w / 2f - s * 0.42f, h / 2f - s * 0.42f),
                        size = Size(s * 0.84f, s * 0.84f),
                    )
                    "triangle" -> {
                        val p = Path().apply {
                            moveTo(w / 2f, h / 2f - s * 0.55f)
                            lineTo(w / 2f + s * 0.58f, h / 2f + s * 0.42f)
                            lineTo(w / 2f - s * 0.58f, h / 2f + s * 0.42f)
                            close()
                        }
                        drawPath(p, color)
                    }
                    "diamond" -> {
                        val p = Path().apply {
                            moveTo(w / 2f, h / 2f - s * 0.6f)
                            lineTo(w / 2f + s * 0.6f, h / 2f)
                            lineTo(w / 2f, h / 2f + s * 0.6f)
                            lineTo(w / 2f - s * 0.6f, h / 2f)
                            close()
                        }
                        drawPath(p, color)
                    }
                    "cross" -> rotate(45f) {
                        val half = s * 0.6f
                        val t = s * 0.3f
                        drawLine(color, Offset(w / 2f - half, h / 2f), Offset(w / 2f + half, h / 2f), strokeWidth = t)
                        drawLine(color, Offset(w / 2f, h / 2f - half), Offset(w / 2f, h / 2f + half), strokeWidth = t)
                    }
                }
            }

            if (isTask && taskLetter == null) {
                rotate(rotation) {
                when (symbol) {
                    "task_block" -> {
                        drawLine(color, Offset(w * 0.08f, h * 0.5f), Offset(w * 0.72f, h * 0.5f), glyphStroke)
                        drawLine(color, Offset(w * 0.72f, h * 0.16f), Offset(w * 0.72f, h * 0.84f), glyphStroke)
                    }
                    "task_ambush" -> {
                        // FM 1-02.2: curved position arc with rear tick marks; the arrow
                        // rises from the arc's center of mass in the direction of fire.
                        val p0 = Offset(w * 0.10f, h * 0.76f)
                        val p1 = Offset(w * 0.90f, h * 0.76f)
                        val ccx = w * 0.5f
                        val ccy = h * 0.42f
                        val arc = Path().apply {
                            moveTo(p0.x, p0.y)
                            quadraticBezierTo(ccx, ccy, p1.x, p1.y)
                        }
                        drawPath(arc, color, style = Stroke(glyphStroke))
                        for (i in 1..6) {
                            val t = i / 7f
                            val mt = 1f - t
                            val x = mt * mt * p0.x + 2f * mt * t * ccx + t * t * p1.x
                            val y = mt * mt * p0.y + 2f * mt * t * ccy + t * t * p1.y
                            val dx = 2f * mt * (ccx - p0.x) + 2f * t * (p1.x - ccx)
                            val dy = 2f * mt * (ccy - p0.y) + 2f * t * (p1.y - ccy)
                            val len = hypot(dx, dy)
                            if (len > 0f) {
                                val nx = -dy / len
                                val ny = dx / len
                                drawLine(
                                    color,
                                    Offset(x, y),
                                    Offset(x + nx * h * 0.11f, y + ny * h * 0.11f),
                                    glyphStroke * 0.8f,
                                )
                            }
                        }
                        val apexY = 0.25f * p0.y + 0.5f * ccy + 0.25f * p1.y
                        drawLine(color, Offset(w * 0.5f, apexY), Offset(w * 0.5f, h * 0.14f), glyphStroke)
                        solidHead(Offset(w * 0.5f, h * 0.08f), -90f, w * 0.15f, color)
                    }
                    "task_sbf" -> {
                        // FM 1-02.2 support by fire: position baseline with an arm from each
                        // end toward the enemy (solid arrowheads) and short rear flank legs.
                        val baseY = h * 0.66f
                        drawLine(color, Offset(w * 0.16f, baseY), Offset(w * 0.84f, baseY), glyphStroke)
                        drawLine(color, Offset(w * 0.16f, baseY), Offset(w * 0.05f, h * 0.86f), glyphStroke)
                        drawLine(color, Offset(w * 0.84f, baseY), Offset(w * 0.95f, h * 0.86f), glyphStroke)
                        drawLine(color, Offset(w * 0.16f, baseY), Offset(w * 0.16f, h * 0.18f), glyphStroke)
                        drawLine(color, Offset(w * 0.84f, baseY), Offset(w * 0.84f, h * 0.18f), glyphStroke)
                        solidHead(Offset(w * 0.16f, h * 0.10f), -90f, w * 0.15f, color)
                        solidHead(Offset(w * 0.84f, h * 0.10f), -90f, w * 0.15f, color)
                    }
                    "task_fix" -> {
                        drawLine(color, Offset(w * 0.06f, h * 0.5f), Offset(w * 0.22f, h * 0.5f), glyphStroke)
                        var x = w * 0.22f
                        var up = true
                        while (x < w * 0.66f) {
                            val nx = x + w * 0.11f
                            drawLine(
                                color,
                                Offset(x, if (up) h * 0.5f else h * 0.32f),
                                Offset(nx, if (up) h * 0.32f else h * 0.5f),
                                glyphStroke,
                            )
                            x = nx
                            up = !up
                        }
                        drawLine(color, Offset(x, h * 0.5f), Offset(w * 0.86f, h * 0.5f), glyphStroke)
                        arrowHead(Offset(w * 0.92f, h * 0.5f), 0f, w * 0.15f, color, glyphStroke)
                    }
                    "task_secure" -> {
                        // FM 1-02.2: a circular arrow — the arc ENDS in the arrowhead
                        val r = w * 0.32f
                        drawArc(
                            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                            topLeft = Offset(w / 2f - r, h / 2f - r),
                            size = Size(2f * r, 2f * r),
                            style = Stroke(glyphStroke),
                        )
                        val end = Math.toRadians(240.0)
                        solidHead(
                            Offset(w / 2f + r * cos(end).toFloat(), h / 2f + r * sin(end).toFloat()),
                            240f + 90f, w * 0.15f, color,
                        )
                    }
                    "task_occupy" -> {
                        // Arrow enters the position; per doctrine its tip is a crossed (+) mark
                        drawCircle(color, radius = w * 0.30f, style = Stroke(glyphStroke))
                        drawLine(color, Offset(w * 0.05f, h * 0.95f), Offset(w * 0.45f, h * 0.55f), glyphStroke)
                        val t = w * 0.10f
                        drawLine(color, Offset(w * 0.45f - t, h * 0.55f - t), Offset(w * 0.45f + t, h * 0.55f + t), glyphStroke)
                        drawLine(color, Offset(w * 0.45f - t, h * 0.55f + t), Offset(w * 0.45f + t, h * 0.55f - t), glyphStroke)
                    }
                    "task_retain" -> {
                        // Arc with tick marks plus the entry arrow through the gap
                        val r = w * 0.26f
                        drawArc(
                            color, startAngle = -50f, sweepAngle = 280f, useCenter = false,
                            topLeft = Offset(w / 2f - r, h / 2f - r),
                            size = Size(2f * r, 2f * r),
                            style = Stroke(glyphStroke),
                        )
                        for (i in 0..9) {
                            val ang = Math.toRadians(-50.0 + 280.0 * i / 9.0)
                            drawLine(
                                color,
                                Offset(w / 2f + r * cos(ang).toFloat(), h / 2f + r * sin(ang).toFloat()),
                                Offset(w / 2f + r * 1.40f * cos(ang).toFloat(), h / 2f + r * 1.40f * sin(ang).toFloat()),
                                glyphStroke * 0.75f,
                            )
                        }
                        drawLine(color, Offset(w / 2f, h * 0.02f), Offset(w / 2f, h * 0.28f), glyphStroke)
                        solidHead(Offset(w / 2f, h * 0.34f), 90f, w * 0.13f, color)
                    }
                    "task_abf" -> {
                        // Attack by fire: single arrow with angled rear legs
                        drawLine(color, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.5f, h * 0.16f), glyphStroke)
                        solidHead(Offset(w * 0.5f, h * 0.10f), -90f, w * 0.15f, color)
                        drawLine(color, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.18f, h * 0.92f), glyphStroke)
                        drawLine(color, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.82f, h * 0.92f), glyphStroke)
                    }
                    "task_seize" -> {
                        // Circle with a curved arrow sweeping onto it
                        val cx = w * 0.66f
                        val cyy = h * 0.34f
                        drawCircle(color, radius = w * 0.16f, center = Offset(cx, cyy), style = Stroke(glyphStroke))
                        val ar = w * 0.40f
                        drawArc(
                            color, startAngle = 100f, sweepAngle = 130f, useCenter = false,
                            topLeft = Offset(cx - ar, cyy - ar),
                            size = Size(2f * ar, 2f * ar),
                            style = Stroke(glyphStroke),
                        )
                        val end = Math.toRadians(230.0)
                        solidHead(
                            Offset(cx + ar * cos(end).toFloat(), cyy + ar * sin(end).toFloat()),
                            230f + 90f, w * 0.14f, color,
                        )
                    }
                    "task_clear" -> {
                        drawLine(color, Offset(w * 0.86f, h * 0.18f), Offset(w * 0.86f, h * 0.82f), glyphStroke)
                        for (i in 0..2) {
                            val y = h * (0.26f + 0.24f * i)
                            drawLine(color, Offset(w * 0.86f, y), Offset(w * 0.24f, y), glyphStroke)
                            solidHead(Offset(w * 0.16f, y), 180f, w * 0.13f, color)
                        }
                    }
                    "task_destroy" -> {
                        drawLine(color, Offset(w * 0.16f, h * 0.16f), Offset(w * 0.84f, h * 0.84f), glyphStroke)
                        drawLine(color, Offset(w * 0.16f, h * 0.84f), Offset(w * 0.84f, h * 0.16f), glyphStroke)
                    }
                    "task_contain" -> {
                        // Arc over the enemy, arrows down at both ends
                        val r = w * 0.32f
                        val cyy = h * 0.52f
                        drawArc(
                            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                            topLeft = Offset(w / 2f - r, cyy - r),
                            size = Size(2f * r, 2f * r),
                            style = Stroke(glyphStroke),
                        )
                        drawLine(color, Offset(w / 2f - r, cyy), Offset(w / 2f - r, cyy + h * 0.16f), glyphStroke)
                        drawLine(color, Offset(w / 2f + r, cyy), Offset(w / 2f + r, cyy + h * 0.16f), glyphStroke)
                        solidHead(Offset(w / 2f - r, cyy + h * 0.22f), 90f, w * 0.12f, color)
                        solidHead(Offset(w / 2f + r, cyy + h * 0.22f), 90f, w * 0.12f, color)
                    }
                    "task_isolate" -> {
                        // Circle with outward teeth; the arc ends in an arrowhead
                        val r = w * 0.26f
                        drawArc(
                            color, startAngle = -70f, sweepAngle = 320f, useCenter = false,
                            topLeft = Offset(w / 2f - r, h / 2f - r),
                            size = Size(2f * r, 2f * r),
                            style = Stroke(glyphStroke),
                        )
                        for (i in 0..7) {
                            val ang = Math.toRadians(-60.0 + 300.0 * i / 7.0)
                            val tp = Path().apply {
                                moveTo(
                                    w / 2f + r * cos(ang - 0.10).toFloat(),
                                    h / 2f + r * sin(ang - 0.10).toFloat(),
                                )
                                lineTo(
                                    w / 2f + r * 1.35f * cos(ang).toFloat(),
                                    h / 2f + r * 1.35f * sin(ang).toFloat(),
                                )
                                lineTo(
                                    w / 2f + r * cos(ang + 0.10).toFloat(),
                                    h / 2f + r * sin(ang + 0.10).toFloat(),
                                )
                                close()
                            }
                            drawPath(tp, color)
                        }
                        val end = Math.toRadians(250.0)
                        solidHead(
                            Offset(w / 2f + r * cos(end).toFloat(), h / 2f + r * sin(end).toFloat()),
                            250f + 90f, w * 0.13f, color,
                        )
                    }
                    "task_disrupt" -> {
                        drawLine(color, Offset(w * 0.28f, h * 0.14f), Offset(w * 0.28f, h * 0.86f), glyphStroke)
                        val lens = floatArrayOf(0.66f, 0.86f, 0.66f)
                        for (i in 0..2) {
                            val y = h * (0.24f + 0.26f * i)
                            drawLine(color, Offset(w * 0.28f, y), Offset(w * lens[i], y), glyphStroke)
                            solidHead(Offset(w * (lens[i] + 0.06f), y), 0f, w * 0.12f, color)
                        }
                    }
                    "task_breach" -> {
                        drawLine(color, Offset(w * 0.32f, h * 0.12f), Offset(w * 0.32f, h * 0.38f), glyphStroke)
                        drawLine(color, Offset(w * 0.68f, h * 0.12f), Offset(w * 0.68f, h * 0.38f), glyphStroke)
                        drawLine(color, Offset(w * 0.32f, h * 0.62f), Offset(w * 0.32f, h * 0.88f), glyphStroke)
                        drawLine(color, Offset(w * 0.68f, h * 0.62f), Offset(w * 0.68f, h * 0.88f), glyphStroke)
                        drawLine(color, Offset(w * 0.06f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), glyphStroke)
                        solidHead(Offset(w * 0.90f, h * 0.5f), 0f, w * 0.14f, color)
                    }
                    "task_bypass" -> {
                        drawLine(color, Offset(w * 0.40f, h * 0.46f), Offset(w * 0.40f, h * 0.88f), glyphStroke)
                        drawLine(color, Offset(w * 0.60f, h * 0.46f), Offset(w * 0.60f, h * 0.88f), glyphStroke)
                        val p = Path().apply {
                            moveTo(w * 0.10f, h * 0.86f)
                            quadraticBezierTo(w * 0.5f, -h * 0.14f, w * 0.90f, h * 0.72f)
                        }
                        drawPath(p, color, style = Stroke(glyphStroke))
                        solidHead(Offset(w * 0.92f, h * 0.80f), 78f, w * 0.13f, color)
                    }
                    "task_penetrate" -> {
                        drawLine(color, Offset(w * 0.14f, h * 0.38f), Offset(w * 0.86f, h * 0.38f), glyphStroke)
                        drawLine(color, Offset(w * 0.5f, h * 0.92f), Offset(w * 0.5f, h * 0.20f), glyphStroke)
                        solidHead(Offset(w * 0.5f, h * 0.12f), -90f, w * 0.15f, color)
                    }
                }
                }
            }
        }
        if (!isShape && !isTask && symbol.isNotEmpty()) {
            Icon(
                WaypointSymbols.icon(symbol),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(size * 0.5f),
            )
        }
        if (taskLetter != null) {
            Text(
                taskLetter,
                color = color,
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.55f).sp,
            )
        }
        if (symbol == "task_contain") {
            // FM 1-02.2 contain carries "ENY" inside the arc; stays upright
            Text(
                "ENY",
                color = color,
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.20f).sp,
            )
        }
        if (!isTask) {
            EchelonMarks(echelon = echelon, color = color, halo = MaterialTheme.colorScheme.background)
        }
    }
}

/**
 * MIL-STD-2525-style echelon marks drawn along the top edge of the marker:
 * team = slashed circle, squad/section/platoon = 1–3 dots,
 * company/battalion/regiment = 1–3 bars, brigade = X.
 */
@Composable
private fun EchelonMarks(echelon: String, color: Color, halo: Color) {
    if (echelon.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val w = this.size.width
        val cy = w * 0.10f
        val s = w * 0.19f
        val stroke = s * 0.30f

        fun marks(count: Int, dot: Boolean) {
            val gap = if (dot) s * 0.78f else s * 0.62f
            val x0 = w / 2f - gap * (count - 1) / 2f
            for (i in 0 until count) {
                val x = x0 + gap * i
                if (dot) {
                    drawCircle(halo, radius = s * 0.30f + stroke * 0.5f, center = Offset(x, cy))
                    drawCircle(color, radius = s * 0.30f, center = Offset(x, cy))
                } else {
                    drawLine(halo, Offset(x, cy - s * 0.55f), Offset(x, cy + s * 0.55f), stroke * 1.9f)
                    drawLine(color, Offset(x, cy - s * 0.55f), Offset(x, cy + s * 0.55f), stroke)
                }
            }
        }

        when (echelon) {
            "tm" -> {
                drawCircle(halo, radius = s * 0.62f, center = Offset(w / 2f, cy), style = Stroke(stroke * 1.9f))
                drawCircle(color, radius = s * 0.62f, center = Offset(w / 2f, cy), style = Stroke(stroke))
                drawLine(
                    halo,
                    Offset(w / 2f - s * 0.85f, cy + s * 0.85f),
                    Offset(w / 2f + s * 0.85f, cy - s * 0.85f),
                    stroke * 1.9f,
                )
                drawLine(
                    color,
                    Offset(w / 2f - s * 0.85f, cy + s * 0.85f),
                    Offset(w / 2f + s * 0.85f, cy - s * 0.85f),
                    stroke,
                )
            }
            "sqd" -> marks(1, dot = true)
            "sec" -> marks(2, dot = true)
            "plt" -> marks(3, dot = true)
            "co" -> marks(1, dot = false)
            "bn" -> marks(2, dot = false)
            "rgt" -> marks(3, dot = false)
            "bde" -> xMarks(1, cy, s, stroke, color, halo)
            "div" -> xMarks(2, cy, s, stroke, color, halo)
            "corps" -> xMarks(3, cy, s, stroke, color, halo)
            "army" -> xMarks(4, cy, s, stroke, color, halo)
        }
    }
}

/** One or more X size indicators centered along the top edge (X / XX / XXX / XXXX). */
private fun DrawScope.xMarks(
    count: Int,
    cy: Float,
    s: Float,
    stroke: Float,
    color: Color,
    halo: Color,
) {
    val w = this.size.width
    val r = s * 0.55f
    val gap = s * 1.35f
    val x0 = w / 2f - gap * (count - 1) / 2f
    for (i in 0 until count) {
        val cx = x0 + gap * i
        listOf(
            Offset(cx - r, cy - r) to Offset(cx + r, cy + r),
            Offset(cx - r, cy + r) to Offset(cx + r, cy - r),
        ).forEach { (a, b) ->
            drawLine(halo, a, b, stroke * 1.9f)
            drawLine(color, a, b, stroke)
        }
    }
}

/** Solid (filled) arrowhead at [tip], pointing along [angleDeg] (0° = +x, clockwise). */
private fun DrawScope.solidHead(tip: Offset, angleDeg: Float, len: Float, color: Color) {
    val a = Math.toRadians(angleDeg.toDouble())
    val back = 150.0 * Math.PI / 180.0
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(
            tip.x + len * cos(a + back).toFloat(),
            tip.y + len * sin(a + back).toFloat(),
        )
        lineTo(
            tip.x + len * cos(a - back).toFloat(),
            tip.y + len * sin(a - back).toFloat(),
        )
        close()
    }
    drawPath(p, color)
}

/** Small open arrowhead at [tip], pointing along [angleDeg] (0° = +x, clockwise). */
private fun DrawScope.arrowHead(tip: Offset, angleDeg: Float, len: Float, color: Color, stroke: Float) {
    val a = Math.toRadians(angleDeg.toDouble())
    val back = 150.0 * Math.PI / 180.0
    val a1 = a + back
    val a2 = a - back
    drawLine(color, tip, Offset(tip.x + len * cos(a1).toFloat(), tip.y + len * sin(a1).toFloat()), stroke)
    drawLine(color, tip, Offset(tip.x + len * cos(a2).toFloat(), tip.y + len * sin(a2).toFloat()), stroke)
}
