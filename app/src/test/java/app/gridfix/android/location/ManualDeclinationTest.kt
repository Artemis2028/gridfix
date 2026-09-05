package app.gridfix.android.location

import org.junit.Assert.*
import org.junit.Test

class ManualDeclinationTest {
    @Test fun gridMagneticEntryNeedsAConvergence() {
        assertNull(manualDeclination("5", true, false, true, null))
        assertNull(manualDeclination("5", true, false, true, Float.NaN))
        assertEquals(6.1f, manualDeclination("5", true, false, true, 1.1f)!!, 0.0001f)
    }
    @Test fun trueDeclinationDoesNotNeedALocation() {
        assertEquals(-5f, manualDeclination("5", false, false, false, null)!!, 0.0001f)
        assertEquals(5.625f, manualDeclination("100", true, true, false, null)!!, 0.0001f)
    }
    @Test fun incompleteOrOutOfRangeEntriesAreNotSaved() {
        for (text in listOf("", ".", "NaN", "Infinity", "181", "-1")) {
            assertNull(manualDeclination(text, true, false, false, null))
        }
    }
}
