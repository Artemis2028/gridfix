package app.gridfix.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Curated MIL-STD-2525B land unit symbols, bundled as 32px renders
 * (from the standard symbol set) in res/drawable-nodpi.
 * Key format: nato_<affiliation>_<function>, e.g. nato_h_armor.
 */
object NatoSymbols {

    val functions = listOf(
        "inf" to "Infantry",
        "mechinf" to "Mech infantry",
        "armor" to "Armor",
        "recon" to "Recon",
        "armcav" to "Armored cavalry",
        "sniper" to "Sniper team",
        "arty" to "Artillery",
        "mortar" to "Mortar",
        "rocket" to "Rocket artillery",
        "airdef" to "Air defense",
        "sam" to "SAM",
        "antiarmor" to "Anti-armor",
        "engineer" to "Engineer",
        "avn" to "Aviation",
        "atkavn" to "Attack aviation",
        "uav" to "UAV",
        "medical" to "Medical",
        "supply" to "Supply",
        "trans" to "Transportation",
        "maint" to "Maintenance",
        "signal" to "Signal",
        "ew" to "Electronic warfare",
        "intel" to "Mil intelligence",
        "mp" to "Military police",
        "eod" to "EOD",
        "cbrn" to "CBRN",
        "hq" to "Headquarters",
        "unit" to "Unit",
    )

    /**
     * Extended symbol tree (M5), listed in the unit picker after `functions`.
     * Every key here has its four affiliation PNGs committed in drawable-nodpi
     * (app/symbol-manifest.tsv records the MIL-STD-2525B code each came from).
     *
     * They were not always committed. From 0.8.0 the build pulled them out of an
     * icon pack in the repo root on every run; 0.9.16 deleted the pack and made
     * that step conditional on "fewer than 112 symbols present" - which the 112
     * common ones satisfied, so it never ran again and 96 picker entries drew the
     * flag fallback for fourteen builds before anyone opened the grid. Symbols are
     * source now, and SymbolAssetsTest fails the build if a picker entry has no
     * PNG behind it.
     */
    val extended: List<Pair<String, String>> = listOf(
        "inf_abn" to "Airborne infantry",
        "inf_ifv" to "Mech infantry (IFV)",
        "inf_light" to "Light infantry",
        "inf_mot" to "Motorized infantry",
        "inf_naval" to "Naval infantry",
        "inf_mtn" to "Mountain infantry",
        "armor_abn" to "Airborne armor",
        "armor_hv" to "Heavy armor",
        "armor_lt" to "Light armor",
        "armor_med" to "Medium armor",
        "armor_rec" to "Armored recovery",
        "armor_whd" to "Wheeled armor",
        "aa_arm" to "Anti-armor (armored)",
        "aa_abn" to "Anti-armor (airborne)",
        "aa_dis" to "Anti-armor (dismounted)",
        "aa_mot" to "Anti-armor (motorized)",
        "recon_abn" to "Airborne recon",
        "recon_amph" to "Amphibious recon",
        "cav" to "Cavalry",
        "aircav" to "Air cavalry",
        "lrs" to "Long-range surveillance",
        "how" to "Howitzer battery",
        "how_sp" to "SP howitzer",
        "mlrs" to "MLRS",
        "arty_met" to "Artillery meteorology",
        "arty_svy" to "Artillery survey",
        "ta_arty" to "Target acquisition",
        "cbradar" to "Fire-finding radar",
        "anglico" to "ANGLICO",
        "ada_gun" to "ADA gun",
        "ada_radar" to "ADA radar",
        "sam_hv" to "SAM (heavy)",
        "sam_lt" to "SAM (light)",
        "sam_med" to "SAM (medium)",
        "ssm" to "Surface-to-surface missile",
        "ssm_str" to "SSM (strategic)",
        "ssm_tac" to "SSM (tactical)",
        "avn_fw" to "Fixed-wing aviation",
        "avn_rw" to "Rotary-wing aviation",
        "utilhelo" to "Utility helicopter",
        "vstol" to "VSTOL aviation",
        "eng_cbt" to "Combat engineer",
        "eng_cbt_h" to "Combat engineer (heavy)",
        "eng_cbt_l" to "Combat engineer (light)",
        "eng_cbt_m" to "Combat engineer (mech)",
        "eng_con" to "Construction engineer",
        "security" to "Security unit",
        "bio_recon" to "Biological recon",
        "decon" to "Decontamination",
        "nuc" to "Nuclear (CBRN)",
        "iw" to "Information warfare",
        "ci" to "Counterintelligence",
        "ipw" to "Interrogation (HUMINT)",
        "gsr" to "Ground surveillance radar",
        "sig_area" to "Area signal",
        "sig_ops" to "Signal operations",
        "admin" to "Administrative",
        "finance" to "Finance",
        "jag" to "Legal (JAG)",
        "labor" to "Labor",
        "mail" to "Mail & courier",
        "pubaff" to "Public affairs",
        "chaplain" to "Religious support",
        "persvc" to "Personnel services",
        "mwr" to "MWR",
        "rhu" to "Replacement holding",
        "dental" to "Dental",
        "medfac" to "Medical treatment facility",
        "vet" to "Veterinary",
        "sup1" to "Supply Class I (rations)",
        "sup2" to "Supply Class II (equipment)",
        "sup3" to "Supply Class III (POL)",
        "sup4" to "Supply Class IV (construction)",
        "sup5" to "Supply Class V (ammo)",
        "sup6" to "Supply Class VI (personal)",
        "sup7" to "Supply Class VII (end items)",
        "sup8" to "Supply Class VIII (medical)",
        "sup9" to "Supply Class IX (repair parts)",
        "laundry" to "Laundry & bath",
        "water" to "Water supply",
        "waterpur" to "Water purification",
        "aport" to "Aerial port",
        "mcc" to "Movement control",
        "rail" to "Railway",
        "seaport" to "Seaport",
        "maint_eo" to "Electro-optical maint",
        "maint_hv" to "Heavy maintenance",
        "ordnance" to "Ordnance",
        "recovery" to "Recovery",
        "sof_avn" to "SOF aviation",
        "sf" to "Special Forces",
        "ranger" to "Rangers",
        "psyop" to "PSYOP",
        "psyop_abn" to "PSYOP (airborne)",
        "seal" to "SEAL",
        "udt" to "UDT",
    )

    private val allLabels: Map<String, String> by lazy {
        (functions + extended).toMap()
    }

    /** Case-insensitive search over every function label (common + extended). */
    fun search(query: String): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return (functions + extended).filter { (key, label) ->
            label.lowercase().contains(q) || key.contains(q)
        }
    }

    val affiliations = listOf(
        "f" to "Friendly",
        "h" to "Hostile",
        "n" to "Neutral",
        "u" to "Unknown",
    )

    fun isNato(key: String): Boolean = key.startsWith("nato_")

    fun keysFor(aff: String): List<String> = functions.map { "nato_${aff}_${it.first}" }

    private fun funcPart(key: String): String =
        key.removePrefix("nato_").substringAfter("_")

    fun label(key: String): String {
        if (!isNato(key)) return key
        val affKey = key.removePrefix("nato_").substringBefore("_")
        val aff = affiliations.firstOrNull { it.first == affKey }?.second ?: affKey
        val func = allLabels[funcPart(key)] ?: funcPart(key)
        return "$aff $func"
    }

    /** Function-only label ("Infantry", "Air defense") for compact picker captions. */
    fun functionLabel(key: String): String {
        if (!isNato(key)) return key
        return allLabels[funcPart(key)] ?: funcPart(key)
    }

    // Symbol PNGs are looked up by name so the curated set can grow without a
    // hand-written map (496 = 124 functions x 4 affiliations). Ids are cached.
    private val idCache = HashMap<String, Int>()

    fun resId(context: Context, key: String): Int? {
        val cached = idCache[key]
        if (cached != null) return if (cached == 0) null else cached
        val id = context.resources.getIdentifier(key, "drawable", context.packageName)
        idCache[key] = id
        return if (id == 0) null else id
    }

    // The bundled renders have opaque black backgrounds; strip them to
    // transparent once per symbol so units sit directly on the map.
    private val bitmapCache = HashMap<Int, ImageBitmap>()

    fun bitmap(context: Context, resId: Int): ImageBitmap =
        bitmapCache.getOrPut(resId) {
            val src = BitmapFactory.decodeResource(context.resources, resId)
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            if (src !== out) src.recycle()
            val w = out.width
            val h = out.height
            val pixels = IntArray(w * h)
            out.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < 40 && g < 40 && b < 40) {
                    pixels[i] = 0x00000000
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out.asImageBitmap()
        }
}
