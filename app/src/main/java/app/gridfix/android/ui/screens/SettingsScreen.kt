package app.gridfix.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridfix.android.AppInfo
import app.gridfix.android.data.AppSettings
import app.gridfix.android.data.SettingsRepository
import app.gridfix.android.location.Declination
import app.gridfix.android.ui.ScreenOrientation
import app.gridfix.android.ui.faces.Face
import app.gridfix.android.ui.theme.LabelFamily
import app.gridfix.android.ui.theme.MonoFamily
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    settings: AppSettings,
    modelDeclination: Float? = null,   // the phone's magnetic model at the current fix, for the caption
    gridConvergence: Float? = null,    // true -> grid at the current fix; converts a typed G-M angle
    entitled: Boolean = false,
    onPreviewPaywall: () -> Unit = {},
    onOpenReference: () -> Unit = {},
    onBackup: (android.net.Uri, (String) -> Unit) -> Unit = { _, _ -> },
    onRestore: (android.net.Uri, (String) -> Unit) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var backupStatus by remember { mutableStateOf<String?>(null) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) onBackup(uri) { backupStatus = it } }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onRestore(uri) { backupStatus = it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------------- DISPLAY ----------------
        SectionHeader("Display", first = true)

        Setting("Position & Navigate face") {
            Segmented(
                options = Face.names,
                selected = settings.face,
            ) { index -> scope.launch { repo.setFace(index) } }
            Spacer(Modifier.height(6.dp))
            Text(
                when (settings.face) {
                    Face.GLANCE -> "Glance: the grid as two big numbers, one row of data, an arrow to the target."
                    Face.LENSATIC -> "Lensatic: the issued compass dial with your grid on the glass. Navigate sets the bezel to the azimuth — turn until the north arrow sits under the line."
                    else -> "Dial: a clean compass card that turns with you, with a needle to the target."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Setting("Orientation") {
            Segmented(
                options = ScreenOrientation.names,
                selected = settings.orientation,
            ) { index -> scope.launch { repo.setOrientation(index) } }
            Spacer(Modifier.height(6.dp))
            Text(
                when (settings.orientation) {
                    ScreenOrientation.PORTRAIT -> "Pinned upright, whatever the phone's auto-rotate says."
                    ScreenOrientation.LANDSCAPE -> "Pinned sideways for a phone worn on armour. Flipped turns it the other way up."
                    ScreenOrientation.LANDSCAPE_FLIPPED -> "Pinned sideways, the other way up. Landscape turns it back."
                    else -> "Follows the phone. The rotate icon in the top bar pins it from any screen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingSwitch(
            title = "Night mode",
            subtitle = "Red-on-black display to preserve night vision",
            checked = settings.nightMode,
        ) { scope.launch { repo.setNightMode(it) } }

        SettingSwitch(
            title = "Keep screen on",
            subtitle = "Prevent the display from sleeping while MGRS GPS is open",
            checked = settings.keepScreenOn,
        ) { scope.launch { repo.setKeepScreenOn(it) } }

        // ---------------- GRID & UNITS ----------------
        SectionHeader("Grid & units")

        Setting("MGRS precision") {
            Segmented(
                options = listOf("4", "6", "8", "10"),
                selected = when (settings.mgrsDigits) {
                    4 -> 0
                    6 -> 1
                    8 -> 2
                    else -> 3
                },
            ) { index -> scope.launch { repo.setMgrsDigits(listOf(4, 6, 8, 10)[index]) } }
        }

        Setting("Lat / Lon format") {
            Segmented(options = listOf("DD", "DDM", "DMS"), selected = settings.latLonFormat) { index ->
                scope.launch { repo.setLatLonFormat(index) }
            }
        }

        Setting("Units") {
            Segmented(options = listOf("Metric", "Imperial", "Nautical"), selected = settings.units) { index ->
                scope.launch { repo.setUnits(index) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Setting("Angle") {
                    Segmented(options = listOf("Degrees", "Mils"), selected = settings.angleUnit) { index ->
                        scope.launch { repo.setAngleUnit(index) }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Setting("North") {
                    Segmented(options = listOf("True", "Mag", "Grid"), selected = settings.northRef) { index ->
                        scope.launch { repo.setNorthRef(index) }
                    }
                }
            }
        }

        DeclinationSetting(
            override = settings.declinationOverride,
            model = modelDeclination,
            convergence = gridConvergence,
            angleUnit = settings.angleUnit,
            onChange = { v -> scope.launch { repo.setDeclinationOverride(v) } },
        )

        PaceSetting(
            current = settings.pacePer100m,
            onChange = { v -> scope.launch { repo.setPacePer100m(v) } },
        )

        // ---------------- DATA ----------------
        SectionHeader("Data")

        DataRow(
            title = "Field reference",
            subtitle = "Phonetic alphabet · grid reading · pace counts · contours · symbols",
            onClick = onOpenReference,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column {
            Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything — waypoints, units, graphics, tracks, settings, practice log — " +
                    "in one file you keep. Restoring never duplicates what's already here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(java.util.Date())
                    backupLauncher.launch("gridfix-backup-$stamp.zip")
                }) { Text("Back up now") }
                TextButton(onClick = {
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("Restore…") }
            }
            backupStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column {
            Text("MGRS GPS Pro", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    entitled -> "Subscription active."
                    app.gridfix.android.BuildConfig.DEBUG ->
                        "No subscription on this device — debug builds run unlocked."
                    else -> "No active subscription on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = {
                    app.gridfix.android.ui.openLink(context, app.gridfix.android.billing.BillingManager.MANAGE_URL)
                }) { Text("Manage subscription") }
                if (app.gridfix.android.BuildConfig.DEBUG) {
                    TextButton(onClick = onPreviewPaywall) { Text("Preview paywall") }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(
            "MGRS GPS " + app.gridfix.android.BuildConfig.VERSION_NAME + "\n" +
                "MGRS conversion by the NGA MGRS library (MIT license).\n" +
                "Elevation data: Terrarium tiles via AWS Open Data (Mapzen) — " +
                "SRTM, USGS 3DEP/NED, GMTED2010, ETOPO1.\n" +
                "Fonts: Saira Semi Condensed, Fira Mono, Antonio (SIL Open Font License).\n\n" +
                "Map data: OpenStreetMap contributors (ODbL), OpenTopoMap (CC-BY-SA), " +
                "USGS, MapTiler.\n" +
                "Map engine: osmdroid; QR codes: ZXing (Apache 2.0).\n\n" +
                "MGRS GPS is a training and recreation aid, not a primary means of navigation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { app.gridfix.android.ui.openLink(context, AppInfo.PRIVACY_URL) }) { Text("Privacy policy") }
            TextButton(onClick = { app.gridfix.android.ui.openLink(context, AppInfo.TERMS_URL) }) { Text("Terms") }
        }
    }
}

/** Amber section title with a rule above it (except the first). */
@Composable
private fun SectionHeader(title: String, first: Boolean = false) {
    Column {
        if (!first) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            title.uppercase(),
            fontFamily = LabelFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Small uppercase caption above a control. */
@Composable
private fun Setting(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label.uppercase(),
            fontFamily = LabelFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * Joined segmented control: the active segment fills amber, the rest are outlined.
 * Announced as a radio group (TalkBack reads the selected state), 48 dp touch targets.
 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    val on = MaterialTheme.colorScheme.primary
    val onText = MaterialTheme.colorScheme.onPrimary
    val offText = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, line)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val sel = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (sel) on else Color.Transparent)
                    .drawBehind {
                        if (index > 0) drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                    }
                    .selectable(selected = sel, role = Role.RadioButton) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    fontFamily = LabelFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    color = if (sel) onText else offText,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun DataRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Magnetic declination: the phone's World Magnetic Model, or an angle typed from the
 * map sheet / the order. Entered in degrees (or mils, per the angle unit) with an E/W
 * switch; always **stored** as declination, east-positive.
 *
 * The store has always held declination - true north to magnetic north - and every
 * azimuth is computed as `magnetic = true - declination`. But the field's hint told
 * the user to type "the G-M angle from your map sheet", and on a declination diagram
 * the G-M angle is *grid* north to magnetic north. The two differ by the grid
 * convergence: about 1.1 deg at Abu Dhabi, which is 19 mils, which is 38 m off at the
 * end of a 2 km leg, and approaches 2.5 deg at a zone edge in high latitudes. So a
 * soldier who read his sheet exactly right got every magnetic azimuth wrong.
 *
 * The fix is to ask which angle he has rather than guess, and convert on entry:
 * `declination = G-M + convergence`. Nothing about the stored format changes, so
 * existing settings and backups keep their meaning.
 */
@Composable
private fun DeclinationSetting(
    override: Float?,
    model: Float?,
    convergence: Float?,
    angleUnit: Int,
    onChange: (Float?) -> Unit,
) {
    val manual = override != null
    val mils = angleUnit == 1
    val conv = convergence ?: 0f
    var asGm by remember(manual) { mutableStateOf(true) }
    fun toStored(typed: Float): Float = if (asGm) typed + conv else typed
    fun fromStored(stored: Float): Float = if (asGm) stored - conv else stored
    fun toDisplay(deg: Float): String {
        val v = abs(deg)
        return if (mils) (v * 6400f / 360f).roundToInt().toString()
        else String.format(Locale.US, "%.1f", v)
    }
    // Keyed on the value too: a backup restore changes `override` without changing
    // `manual`, and the field would otherwise keep showing the old number. Keyed on
    // `asGm` as well, so switching which angle you are typing re-reads the same stored
    // declination in the other convention instead of silently reinterpreting it.
    var text by remember(manual, mils, override, asGm) {
        mutableStateOf(if (override != null) toDisplay(fromStored(override)) else "")
    }
    var east by remember(manual, override, asGm) {
        mutableStateOf(override == null || fromStored(override) >= 0f)
    }
    fun push(t: String, e: Boolean) {
        val v = t.toFloatOrNull() ?: return
        val deg = if (mils) v * 360f / 6400f else v
        if (deg in 0f..180f) onChange(toStored(if (e) deg else -deg))
    }
    Setting("Declination") {
        Segmented(options = listOf("Model", "Manual"), selected = if (manual) 1 else 0) { index ->
            if (index == 0) onChange(null)
            else onChange(model ?: 0f)
        }
        Spacer(Modifier.height(6.dp))
        if (manual) {
            Segmented(options = listOf("G-M angle", "Declination"), selected = if (asGm) 0 else 1) { index ->
                asGm = index == 0
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.filter { it.isDigit() || it == '.' }.take(6)
                        push(text, east)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    suffix = { Text(if (mils) "mils" else "°", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.width(128.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    Segmented(options = listOf("East", "West"), selected = if (east) 0 else 1) { index ->
                        east = index == 0
                        push(text, east)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            when {
                manual -> {
                    val stored = override ?: 0f
                    "G-M " + Declination.format(stored - conv, angleUnit) +
                        " → declination " + Declination.format(stored, angleUnit) +
                        (if (convergence == null) " (no fix yet — grid convergence taken as zero until you have one)"
                        else " here, using a grid convergence of " + Declination.format(conv, angleUnit)) +
                        (model?.let { ". The phone's model says " + Declination.format(it, angleUnit) } ?: "") +
                        ". Every azimuth, the compass faces and the route cards use the declination."
                }
                model != null -> "Phone's World Magnetic Model at your fix: " + Declination.format(model, angleUnit) +
                    ". Switch to Manual to type the G-M angle from your map sheet or the order — " +
                    "it is converted to declination using the grid convergence where you are."
                else -> "Phone's World Magnetic Model at your fix. Switch to Manual to type the G-M angle " +
                    "from your map sheet or the order — it is converted to declination using the grid convergence."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaceSetting(current: Int, onChange: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pace count per 100 m", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your paces for 100 m on flat ground — used on route cards. Walk a known 100 m to find it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isDigit() }.take(3)
                text.toIntOrNull()?.let { n -> if (n in 30..200) onChange(n) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
            suffix = { Text("paces", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.width(128.dp),
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
