package app.gridfix.android.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridfix.android.ui.theme.MonoFamily
import app.gridfix.android.coords.Phonetic

/**
 * Pocket field reference: the lookup tables and reminders a land navigator
 * reaches for — phonetic alphabet, grid reading, pace counts, contours,
 * north references, and symbology. All offline, all static.
 */
@Composable
fun ReferenceScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "FIELD REFERENCE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )

        RefSection("Phonetic alphabet", expandedAtStart = true) {
            val letters = Phonetic.letters.entries.toList()
            val half = (letters.size + 1) / 2
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    letters.take(half).forEach { (c, w) -> MonoRow("$c", w) }
                }
                Column(Modifier.weight(1f)) {
                    letters.drop(half).forEach { (c, w) -> MonoRow("$c", w) }
                }
            }
            Spacer(Modifier.height(8.dp))
            val digits = Phonetic.digits.entries.toList()
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    digits.take(5).forEach { (c, w) -> MonoRow("$c", w) }
                }
                Column(Modifier.weight(1f)) {
                    digits.drop(5).forEach { (c, w) -> MonoRow("$c", w) }
                }
            }
        }

        RefSection("Sending a grid by radio") {
            Body(
                "Announce \"grid\", then spell every character phonetically, pausing " +
                    "between groups. Example for 18T VP 3808 9755:"
            )
            Spacer(Modifier.height(6.dp))
            Mono("\"Grid " + Phonetic.mgrs("18T VP 3808 9755").replace(" · ", ", ") + "\"")
            Spacer(Modifier.height(6.dp))
            Body(
                "The Position tab shows your own grid spelled out and can speak it " +
                    "aloud — read it straight off the screen under pressure."
            )
        }

        RefSection("Reading an MGRS grid") {
            Body(
                "A full grid has three parts: the grid zone designator (18T), the " +
                    "100 km square (VP), then an even count of digits — the first half is " +
                    "the easting, the second half the northing. Read RIGHT, then UP: " +
                    "easting digits first, northing second."
            )
            Spacer(Modifier.height(6.dp))
            MonoRow("4-digit", "1 km precision — a grid square")
            MonoRow("6-digit", "100 m — a building cluster")
            MonoRow("8-digit", "10 m — a single building")
            MonoRow("10-digit", "1 m — a doorway")
        }

        RefSection("Pace count") {
            Body(
                "Your pace count is how many paces (every OTHER footfall — count each " +
                    "time the same foot strikes) you take over 100 m. Most people fall " +
                    "between 55 and 75. Calibrate on a measured 100 m course, walked " +
                    "three times with your normal load; set the average in Settings and " +
                    "route cards will print paces per leg."
            )
            Spacer(Modifier.height(6.dp))
            MonoRow("Uphill / soft ground", "count goes UP")
            MonoRow("Downhill / road", "count goes DOWN")
            MonoRow("Night / fog / rain", "count goes UP")
            MonoRow("With rucksack", "count goes UP")
            Spacer(Modifier.height(6.dp))
            Body("Drop a pace bead (or pebble to a pocket) every 100 m; 10 beads = 1 km.")
        }

        RefSection("Contours & terrain shapes") {
            Body(
                "Contour lines join points of equal elevation. The interval (CI, shown " +
                    "bottom-right when the contour layer is on) is the height step between " +
                    "lines: close together = steep, far apart = gentle. Every 5th line is " +
                    "heavier and labeled."
            )
            Spacer(Modifier.height(6.dp))
            MonoRow("Hilltop", "closed rings, highest inside")
            MonoRow("Ridge", "U/V shapes pointing DOWNHILL")
            MonoRow("Valley/draw", "U/V shapes pointing UPHILL")
            MonoRow("Saddle", "hourglass between two highs")
            MonoRow("Spur", "short ridge off a hillside")
            MonoRow("Depression", "closed rings with tick marks")
            MonoRow("Cliff", "lines touching or merging")
        }

        RefSection("North references & declination") {
            Body(
                "TRUE north: the geographic pole — what the stars give you. GRID north: " +
                    "the map's vertical grid lines. MAGNETIC north: where the compass " +
                    "needle points. The angle from GRID to MAGNETIC is the G-M angle, the " +
                    "one printed in the declination diagram on your sheet. The angle from " +
                    "TRUE to MAGNETIC is the declination. They are not the same: they differ " +
                    "by the grid convergence, which is also on the sheet. Both change with " +
                    "location and time."
            )
            Spacer(Modifier.height(6.dp))
            MonoRow("Magnetic → True", "ADD east declination (subtract west)")
            MonoRow("True → Grid", "subtract grid convergence")
            MonoRow("Declination", "G-M angle + grid convergence")
            Spacer(Modifier.height(6.dp))
            Body(
                "MGRS GPS computes both from the World Magnetic Model for wherever you " +
                    "stand — the Declination card in Field tools draws the diagram. Pick " +
                    "your working reference (True/Magnetic/Grid) in Settings and every " +
                    "azimuth in the app follows it."
            )
        }

        RefSection("Symbols & affiliation") {
            MonoRow("BLUE rectangle", "friendly")
            MonoRow("RED diamond", "hostile")
            MonoRow("GREEN square", "neutral")
            MonoRow("YELLOW clover", "unknown")
            Spacer(Modifier.height(6.dp))
            Body(
                "Echelon marks sit above the unit frame: dots for squad/section, " +
                    "vertical bars for company (|) and battalion (||), X for brigade. In " +
                    "night mode everything renders red-on-black, so hostile graphics are " +
                    "marked ENY instead of by color — per FM 1-02.2 practice."
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Training aid — verify against your unit's SOP and current doctrine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefSection(
    title: String,
    expandedAtStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(expandedAtStart) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(14.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = MonoFamily,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MonoRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            left,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            right,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}
