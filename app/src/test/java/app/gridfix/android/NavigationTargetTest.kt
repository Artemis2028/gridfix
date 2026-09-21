package app.gridfix.android

import app.gridfix.android.data.FolderInfo
import app.gridfix.android.data.NavigationTarget
import app.gridfix.android.data.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The selected waypoint is authoritative. Navigate must never guide to a
 * different point because the chosen one is hidden, in a hidden folder, or gone.
 */
class NavigationTargetTest {

    private fun wp(id: String, folder: String = "Base", visible: Boolean = true) =
        Waypoint(id = id, name = id, lat = 0.0, lon = 0.0, createdAt = 0L, folder = folder, visible = visible)

    private val a = wp("A")
    private val bHidden = wp("B", visible = false)
    private val cHiddenFolder = wp("C", folder = "Held")
    private val folders = listOf(FolderInfo("Base", true), FolderInfo("Held", false))

    @Test
    fun hiddenWaypointIsOfferedWhenSelected() {
        val list = NavigationTarget.navigable(listOf(a, bHidden, cHiddenFolder), folders, "B")
        assertEquals(listOf("A", "B"), list.map { it.id })
        assertEquals("B", NavigationTarget.resolve(list, "B")?.id)
    }

    @Test
    fun checkpointInHiddenFolderIsOfferedWhenSelected() {
        val list = NavigationTarget.navigable(listOf(a, bHidden, cHiddenFolder), folders, "C")
        assertEquals(listOf("A", "C"), list.map { it.id })
        assertEquals("C", NavigationTarget.resolve(list, "C")?.id)
    }

    @Test
    fun nothingSelectedRequiresChoosingATarget() {
        val list = NavigationTarget.navigable(listOf(bHidden, cHiddenFolder, a), folders, null)
        assertEquals(listOf("A"), list.map { it.id })
        assertNull(NavigationTarget.resolve(list, null))
    }

    @Test
    fun deletedSelectionIsNeverReplaced() {
        val list = NavigationTarget.navigable(listOf(a), folders, "GONE")
        assertNull(NavigationTarget.resolve(list, "GONE"))
    }

    @Test
    fun hiddenSelectionUnlistedIsNeverReplaced() {
        // A caller that filtered the selection out (or a stale ID) gets no target,
        // not the first visible one.
        assertNull(NavigationTarget.resolve(listOf(a), "B"))
    }

    @Test
    fun noFoldersMeansOnlyItemVisibilityApplies() {
        val list = NavigationTarget.navigable(listOf(a, bHidden, cHiddenFolder), emptyList(), null)
        assertEquals(listOf("A", "C"), list.map { it.id })
    }
}
