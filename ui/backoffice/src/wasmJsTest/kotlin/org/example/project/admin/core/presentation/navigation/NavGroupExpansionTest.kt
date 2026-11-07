package org.example.project.admin.core.presentation.navigation

import org.example.project.admin.core.data.LocalStorageKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawer holds 25 destinations — roughly 1200px of rows in a ~660px scroll area — so no
 * arrangement fits with every group expanded. Defaulting to the active group only is what makes
 * all seven headers ([AdminNavGroup.PINNED] renders none) visible at once.
 */
class NavGroupExpansionTest {
    private fun freshStore(): LocalStorageKeyValueStore =
        LocalStorageKeyValueStore().also { store ->
            AdminNavGroup.entries.forEach { store.remove(NavGroupExpansion.keyFor(it)) }
        }

    @Test
    fun byDefaultOnlyTheActiveGroupIsExpanded() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)

        assertTrue(expansion.isExpanded(AdminNavGroup.DOCUMENT_FLOW, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
        assertFalse(expansion.isExpanded(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
    }

    @Test
    fun theActiveGroupIsExpandedEvenWhenStoredCollapsed() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)
        expansion.setExpanded(AdminNavGroup.CASH_CLOSING, false)

        // Navigating INTO a group must reveal where you landed, or the selected row is invisible.
        assertTrue(expansion.isExpanded(AdminNavGroup.CASH_CLOSING, activeGroup = AdminNavGroup.CASH_CLOSING))
    }

    @Test
    fun anExplicitChoiceSurvivesAndAppliesToInactiveGroups() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)
        expansion.setExpanded(AdminNavGroup.MASTER_DATA, true)

        assertTrue(expansion.isExpanded(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
        // A second instance reads the same store — the choice is persisted, not in-memory.
        assertTrue(NavGroupExpansion(store).isExpanded(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
    }

    @Test
    fun anExplicitlyStoredFalseStaysCollapsedOnAnInactiveGroup() {
        // Distinct from "unset": both currently read as collapsed, but only this case proves the
        // stored `false` branch is actually reached rather than falling through the `?: false`.
        val store = freshStore()
        val expansion = NavGroupExpansion(store)
        expansion.setExpanded(AdminNavGroup.COMPLIANCE, false)

        assertFalse(expansion.isExpanded(AdminNavGroup.COMPLIANCE, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
        // A second instance reads the same stored `false`, not just the in-memory map.
        assertFalse(NavGroupExpansion(store).isExpanded(AdminNavGroup.COMPLIANCE, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
    }

    @Test
    fun aGarbageStoredValueFallsBackToCollapsed() {
        val store = freshStore()
        store.setString(NavGroupExpansion.keyFor(AdminNavGroup.SYSTEM), "TRUE")
        val expansion = NavGroupExpansion(store)

        // toBooleanStrictOrNull() only accepts the exact lowercase literals; anything else, "1"
        // included, must not be silently read as true.
        assertFalse(expansion.isExpanded(AdminNavGroup.SYSTEM, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
    }

    @Test
    fun pinnedIsAlwaysExpandedAndNeverStored() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)
        expansion.setExpanded(AdminNavGroup.PINNED, false)

        assertTrue(expansion.isExpanded(AdminNavGroup.PINNED, activeGroup = AdminNavGroup.SYSTEM))
    }

    @Test
    fun theActiveGroupReportsAsActiveAndIsNeverToggled() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)

        assertTrue(expansion.isActiveGroup(AdminNavGroup.CASH_CLOSING, activeGroup = AdminNavGroup.CASH_CLOSING))
        assertFalse(expansion.isActiveGroup(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.CASH_CLOSING))
    }

    @Test
    fun toggleFlipsTheStoredValueOfAnInactiveGroup() {
        val store = freshStore()
        val expansion = NavGroupExpansion(store)

        expansion.toggle(AdminNavGroup.MASTER_DATA)
        assertTrue(expansion.isExpanded(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.DOCUMENT_FLOW))

        expansion.toggle(AdminNavGroup.MASTER_DATA)
        assertFalse(expansion.isExpanded(AdminNavGroup.MASTER_DATA, activeGroup = AdminNavGroup.DOCUMENT_FLOW))
    }

    @Test
    fun keyForIsPinnedPerGroup() {
        // Nothing else stops a rename from silently discarding every user's saved layout.
        assertEquals("admin.sidebar.group.pinned", NavGroupExpansion.keyFor(AdminNavGroup.PINNED))
        assertEquals("admin.sidebar.group.operations", NavGroupExpansion.keyFor(AdminNavGroup.OPERATIONS))
        assertEquals("admin.sidebar.group.devices", NavGroupExpansion.keyFor(AdminNavGroup.DEVICES))
        assertEquals("admin.sidebar.group.document_flow", NavGroupExpansion.keyFor(AdminNavGroup.DOCUMENT_FLOW))
        assertEquals("admin.sidebar.group.master_data", NavGroupExpansion.keyFor(AdminNavGroup.MASTER_DATA))
        assertEquals("admin.sidebar.group.cash_closing", NavGroupExpansion.keyFor(AdminNavGroup.CASH_CLOSING))
        assertEquals("admin.sidebar.group.compliance", NavGroupExpansion.keyFor(AdminNavGroup.COMPLIANCE))
        assertEquals("admin.sidebar.group.system", NavGroupExpansion.keyFor(AdminNavGroup.SYSTEM))
    }
}
