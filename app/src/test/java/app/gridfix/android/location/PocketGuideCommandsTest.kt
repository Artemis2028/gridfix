package app.gridfix.android.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketGuideCommandsTest {
    @Test
    fun `rapid explicit selections B then C can both advance the same run from A`() {
        val commands = PocketGuideCommands()
        commands.start(1, "A")
        assertTrue(commands.update(1, 1, "A", "B", retarget = true))
        assertTrue(commands.update(1, 2, "A", "C", retarget = true))
        assertTrue(commands.canStop(1, 3, "C"))
        assertFalse(commands.canStop(1, 3, "B"))
    }

    @Test
    fun `late retarget and old repository commands cannot undo a newer target`() {
        val commands = PocketGuideCommands()
        commands.start(1, "A")
        assertTrue(commands.update(1, 3, "A", "C", retarget = true))
        assertFalse(commands.update(1, 2, "A", "B", retarget = true))
        assertFalse(commands.update(1, 4, "A", "A", retarget = false))
        assertFalse(commands.canStop(1, 5, "A"))
        assertTrue(commands.update(1, 6, "C", "C", retarget = false))
    }

    @Test
    fun `stopped or restarted guide rejects every command from its previous run`() {
        val commands = PocketGuideCommands()
        commands.start(1, "A")
        commands.clear()
        assertFalse(commands.update(1, 1, "A", "B", retarget = true))
        commands.start(2, "A")
        assertFalse(commands.update(1, 2, "A", "C", retarget = true))
        assertFalse(commands.canStop(1, 3, "A"))
        assertTrue(commands.update(2, 4, "A", "A", retarget = false))
    }

    @Test
    fun `newer edit or revert rejects an older queued update or deletion`() {
        val commands = PocketGuideCommands()
        commands.start(1, "A")
        assertTrue(commands.update(1, 2, "A", "A", retarget = false))
        assertFalse(commands.update(1, 1, "A", "A", retarget = false))
        assertFalse(commands.canStop(1, 1, "A"))
        assertTrue(commands.canStop(1, 3, "A"))
    }
}
