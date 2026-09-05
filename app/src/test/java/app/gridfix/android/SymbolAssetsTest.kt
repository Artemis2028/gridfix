package app.gridfix.android

import app.gridfix.android.ui.NatoSymbols
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit picker offers every entry in NatoSymbols.functions + extended, and
 * draws each one by looking up "nato_<aff>_<key>" by name at runtime. A name
 * with no PNG behind it does not fail anything: resId() returns null and the
 * grid silently draws the flag fallback. That is how 96 of the 124 entries
 * shipped as placeholders from 0.9.16 to 0.9.30 - the build step that used to
 * produce them stopped running and nothing noticed.
 *
 * This test reads the resource directory straight off disk (Gradle runs JVM
 * tests with the module directory as the working directory), so it needs no
 * Android runtime and fails the build the moment a picker entry and its assets
 * disagree - in either direction.
 */
class SymbolAssetsTest {

    private val drawables = File("src/main/res/drawable-nodpi")
    private val manifest = File("symbol-manifest.tsv")

    private val pickerKeys: List<String>
        get() = (NatoSymbols.functions + NatoSymbols.extended).map { it.first }

    @Test
    fun everyPickerEntryHasAPngForEveryAffiliation() {
        assertTrue("expected the app module as working dir, got " + File(".").absolutePath, drawables.isDirectory)
        val missing = pickerKeys.flatMap { key ->
            NatoSymbols.affiliations.map { (aff, _) -> "nato_${aff}_$key.png" }
        }.filter { name ->
            val f = File(drawables, name)
            !f.isFile || f.length() == 0L
        }
        assertEquals("picker entries with no committed PNG: $missing", emptyList<String>(), missing)
    }

    @Test
    fun everyCommittedSymbolIsReachableFromThePicker() {
        val affs = NatoSymbols.affiliations.map { it.first }.toSet()
        val keys = pickerKeys.toSet()
        val orphans = (drawables.list() ?: emptyArray<String>())
            .filter { it.startsWith("nato_") && it.endsWith(".png") }
            .map { it.removePrefix("nato_").removeSuffix(".png") }
            .filter { name ->
                val aff = name.substringBefore('_')
                val key = name.substringAfter('_')
                aff !in affs || key !in keys
            }
        assertEquals("committed PNGs no picker entry can reach: $orphans", emptyList<String>(), orphans)
    }

    @Test
    fun manifestRowsMatchTheExtendedListInOrder() {
        assertTrue(manifest.isFile)
        val rows = manifest.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('\t') }
        assertEquals(NatoSymbols.extended.map { it.first }, rows)
    }

    @Test
    fun theCountThePickerAdvertisesIsTheCountOnDisk() {
        // 28 common + 96 extended, times four affiliations. The number the
        // workflow's input check and NatoSymbols' comment both quote.
        val onDisk = (drawables.list() ?: emptyArray<String>()).count { it.startsWith("nato_") && it.endsWith(".png") }
        assertEquals((pickerKeys.size * NatoSymbols.affiliations.size).toLong(), onDisk.toLong())
        assertEquals(496L, onDisk.toLong())
    }
}
