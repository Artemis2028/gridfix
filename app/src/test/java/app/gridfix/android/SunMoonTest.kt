package app.gridfix.android

import app.gridfix.android.coords.SunMoon
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Light data drives movement planning; the ordering of the events is the invariant. */
class SunMoonTest {

    @Test
    fun `twilight brackets the day in the right order`() {
        // Abu Dhabi, midsummer
        val t = SunMoon.sunTimes(2026, 6, 21, 24.4539, 54.3773)
        val bmnt = t.bmnt
        val sr = t.sunrise
        val ss = t.sunset
        val eent = t.eent
        assertNotNull(bmnt); assertNotNull(sr); assertNotNull(ss); assertNotNull(eent)
        assertTrue("BMNT must precede sunrise", bmnt!! < sr!!)
        assertTrue("sunrise must precede sunset", sr < ss!!)
        assertTrue("sunset must precede EENT", ss < eent!!)
    }

    @Test
    fun `zulu formatting is four digits and a Z`() {
        val s = SunMoon.formatZulu(5.5)
        assertTrue("unexpected '$s'", Regex("^\\d{4}Z$").matches(s))
        assertTrue(s.startsWith("0530"))
    }

    @Test
    fun `a polar day reports no sunrise rather than a wrong one`() {
        // Longyearbyen in midsummer: the sun does not set.
        val t = SunMoon.sunTimes(2026, 6, 21, 78.22, 15.65)
        assertTrue("polar day should have no sunrise/sunset", t.sunrise == null || t.sunset == null)
    }
}
