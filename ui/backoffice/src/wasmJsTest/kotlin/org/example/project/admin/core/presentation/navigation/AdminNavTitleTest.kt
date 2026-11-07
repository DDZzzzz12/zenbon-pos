package org.example.project.admin.core.presentation.navigation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * What the phone top bar calls a screen. The bar used to read "Einstellungen" on every settings
 * section and was blank on every detail screen; these pin the pure halves of that decision (the
 * `NavDestination` match itself needs a real graph and is covered in the browser pass).
 */
class AdminNavTitleTest {
    private val manager = SetAccessPolicy(setOf(Capability.CONFIG_WRITE))

    @Test
    fun everyDetailRouteHasATitleParentInTheSameGroup() {
        AdminNavItem.DETAIL_ROUTE_GROUPS.forEach { (route, group) ->
            val parent = AdminNavItem.DETAIL_ROUTE_PARENTS[route]
            assertNotNull(parent, "${route.simpleName} has no title parent")
            assertEquals(group, parent.group, "${route.simpleName}: title parent is in another group")
        }
    }

    @Test
    fun firstTerminalSetupIsTitledAsTerminals() {
        assertEquals(AdminNavItem.TERMINALS, AdminNavItem.DETAIL_ROUTE_PARENTS[FirstTerminalSetupRoute::class])
    }

    @Test
    fun settingsTitleIsTheSectionNotTheTree() {
        val title = SettingsSection.titleKeyFor(SettingsSection.VAT_MAPPING.name, manager)
        assertEquals(SettingsSection.VAT_MAPPING.labelKey, title)
        assertNotEquals(AdminNavItem.SETTINGS.labelKey, title)
    }

    @Test
    fun everyVisibleSectionHasItsOwnDistinctTitle() {
        val all = SetAccessPolicy(Capability.entries.toSet())
        val titles = SettingsSection.visibleTo(all).map { SettingsSection.titleKeyFor(it.name, all) }
        assertEquals(SettingsSection.visibleTo(all).map { it.labelKey }, titles)
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun aHiddenOrUnknownSectionIsTitledLikeTheFallbackScreenShowsIt() {
        // SettingsScreen renders BusinessRulesSection for a section the principal may not open.
        val withoutShopInfo = SetAccessPolicy(setOf(Capability.CONFIG_WRITE))
        assertEquals(
            SettingsSection.BUSINESS_RULES.labelKey,
            SettingsSection.titleKeyFor(SettingsSection.SHOP_DATA.name, withoutShopInfo),
        )
        assertEquals(SettingsSection.BUSINESS_RULES.labelKey, SettingsSection.titleKeyFor("NO_SUCH_SECTION", manager))
        assertEquals(SettingsSection.BUSINESS_RULES.labelKey, SettingsSection.titleKeyFor(null, manager))
    }

    @Test
    fun aGatedItemIsNotTitledForAPrincipalWhoCannotOpenIt() {
        val without = SetAccessPolicy(setOf(Capability.REPORT_READ))
        val with = SetAccessPolicy(setOf(Capability.REPORT_ALL_READ))
        assertEquals(null, AdminNavItem.titleKeyForMatch(without) { it == DailyReportRoute::class })
        assertEquals(AdminNavItem.DAILY_REPORT.labelKey, AdminNavItem.titleKeyForMatch(with) { it == DailyReportRoute::class })
    }

    @Test
    fun aDetailScreenIsNotTitledByAGatedParentTheCallerCannotSee() {
        val parent = AdminNavItem.DETAIL_ROUTE_PARENTS.entries.first { it.value.requiredCapability != null }
        val required = requireNotNull(parent.value.requiredCapability)
        assertEquals(null, AdminNavItem.titleKeyForMatch(SetAccessPolicy(Capability.entries.toSet() - required)) { it == parent.key })
        // A detail may carry its own title (DETAIL_ROUTE_TITLES), still behind its parent's gate.
        val expected = AdminNavItem.DETAIL_ROUTE_TITLES[parent.key] ?: parent.value.labelKey
        assertEquals(expected, AdminNavItem.titleKeyForMatch(SetAccessPolicy(Capability.entries.toSet())) { it == parent.key })
    }

    /** The phone top bar names the floor-plan editor itself, not "Tische" above a second "Grundriss" header. */
    @Test
    fun floorPlanEditorIsTitledGrundriss() {
        assertEquals(
            AdminStringKeys.FloorPlanTitle,
            AdminNavItem.titleKeyForMatch(SetAccessPolicy(Capability.entries.toSet())) { it == FloorPlanEditorRoute::class },
        )
    }
}
