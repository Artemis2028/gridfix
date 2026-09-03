package app.gridfix.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class AppSettings(
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = true,
    val mgrsDigits: Int = 10,     // 4, 6, 8, or 10
    val latLonFormat: Int = 1,    // 0 = DD, 1 = DDM, 2 = DMS
    val units: Int = 0,           // 0 = metric, 1 = imperial, 2 = nautical
    val angleUnit: Int = 0,       // 0 = degrees, 1 = NATO mils (6400)
    val northRef: Int = 0,        // 0 = true, 1 = magnetic, 2 = grid
    val pacePer100m: Int = 65,    // user's pace count per 100 m, for route cards
    val face: Int = 1,            // Position/Navigate face: 0 = Glance, 1 = Lensatic, 2 = Dial
    val orientation: Int = 0,     // 0 = follow the device, 1 = portrait, 2 = landscape, 3 = landscape flipped
    val declinationOverride: Float? = null,   // degrees, east positive; null = the phone's World Magnetic Model
    val disclaimerAccepted: Boolean = false,  // the "not a primary means of navigation" screen
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val MGRS_DIGITS = intPreferencesKey("mgrs_digits")
        val LATLON_FORMAT = intPreferencesKey("latlon_format")
        val UNITS = intPreferencesKey("units")
        val ANGLE_UNIT = intPreferencesKey("angle_unit")
        val NORTH_REF = intPreferencesKey("north_ref")
        val PACE_PER_100M = intPreferencesKey("pace_per_100m")
        val FACE = intPreferencesKey("face")
        val ORIENTATION = intPreferencesKey("orientation")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val DECL_MANUAL = booleanPreferencesKey("decl_manual")
        val DECL_VALUE = floatPreferencesKey("decl_value")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            nightMode = p[Keys.NIGHT_MODE] ?: false,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            mgrsDigits = p[Keys.MGRS_DIGITS] ?: 10,
            latLonFormat = p[Keys.LATLON_FORMAT] ?: 1,
            units = p[Keys.UNITS] ?: 0,
            angleUnit = p[Keys.ANGLE_UNIT] ?: 0,
            northRef = p[Keys.NORTH_REF] ?: 0,
            pacePer100m = p[Keys.PACE_PER_100M] ?: 65,
            face = (p[Keys.FACE] ?: 1).coerceIn(0, 2),
            orientation = (p[Keys.ORIENTATION] ?: 0).coerceIn(0, 3),
            declinationOverride = if (p[Keys.DECL_MANUAL] == true) (p[Keys.DECL_VALUE] ?: 0f).coerceIn(-180f, 180f) else null,
            disclaimerAccepted = p[Keys.DISCLAIMER] ?: false,
        )
    }

    /** Apply every setting at once (backup restore). */
    suspend fun applyAll(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.NIGHT_MODE] = s.nightMode
            p[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
            p[Keys.MGRS_DIGITS] = s.mgrsDigits
            p[Keys.LATLON_FORMAT] = s.latLonFormat
            p[Keys.UNITS] = s.units
            p[Keys.ANGLE_UNIT] = s.angleUnit
            p[Keys.NORTH_REF] = s.northRef
            p[Keys.PACE_PER_100M] = s.pacePer100m
            p[Keys.FACE] = s.face
            p[Keys.ORIENTATION] = s.orientation
            p[Keys.DISCLAIMER] = s.disclaimerAccepted
            p[Keys.DECL_MANUAL] = s.declinationOverride != null
            p[Keys.DECL_VALUE] = s.declinationOverride ?: 0f
        }
    }

    suspend fun setDisclaimerAccepted(value: Boolean) {
        context.dataStore.edit { it[Keys.DISCLAIMER] = value }
    }

    /** Manual declination in degrees (east positive), or null to follow the magnetic model. */
    suspend fun setDeclinationOverride(value: Float?) {
        context.dataStore.edit {
            it[Keys.DECL_MANUAL] = value != null
            if (value != null) it[Keys.DECL_VALUE] = value.coerceIn(-180f, 180f)
        }
    }

    suspend fun setNightMode(value: Boolean) {
        context.dataStore.edit { it[Keys.NIGHT_MODE] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    suspend fun setMgrsDigits(value: Int) {
        context.dataStore.edit { it[Keys.MGRS_DIGITS] = value }
    }

    suspend fun setLatLonFormat(value: Int) {
        context.dataStore.edit { it[Keys.LATLON_FORMAT] = value }
    }

    suspend fun setUnits(value: Int) {
        context.dataStore.edit { it[Keys.UNITS] = value }
    }

    suspend fun setAngleUnit(value: Int) {
        context.dataStore.edit { it[Keys.ANGLE_UNIT] = value }
    }

    suspend fun setNorthRef(value: Int) {
        context.dataStore.edit { it[Keys.NORTH_REF] = value }
    }

    suspend fun setPacePer100m(value: Int) {
        context.dataStore.edit { it[Keys.PACE_PER_100M] = value.coerceIn(30, 200) }
    }

    suspend fun setFace(value: Int) {
        context.dataStore.edit { it[Keys.FACE] = value.coerceIn(0, 2) }
    }

    suspend fun setOrientation(value: Int) {
        context.dataStore.edit { it[Keys.ORIENTATION] = value.coerceIn(0, 3) }
    }
}
