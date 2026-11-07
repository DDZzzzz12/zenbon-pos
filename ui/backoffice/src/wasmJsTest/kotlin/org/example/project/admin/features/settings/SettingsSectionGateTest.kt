package org.example.project.admin.features.settings

import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Geschäftsdaten is offered only with `SHOP_INFO_MANAGE`, what `ShopInfoRules` puts on every
 * back-office `/api/shop-info` read. A manager without it saw an empty form and a 403 toast
 * (QA admin19 NOTE-h).
 */
class SettingsSectionGateTest {
    @Test
    fun shopData_needsShopInfoManage() {
        assertFalse(SettingsSection.SHOP_DATA.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE))))
        assertTrue(SettingsSection.SHOP_DATA.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE, Capability.SHOP_INFO_MANAGE))))
    }

    @Test
    fun managerBundle_matchesTheServerOnShopData() {
        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        assertEquals(
            Capability.SHOP_INFO_MANAGE in manager,
            SettingsSection.SHOP_DATA in SettingsSection.visibleTo(SetAccessPolicy(manager)),
        )
    }

    @Test
    fun admin_seesEverySection() {
        val admin = SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.ADMIN))
        assertEquals(SettingsSection.entries, SettingsSection.visibleTo(admin))
    }

    @Test
    fun ungatedSections_stayVisible() {
        val visible = SettingsSection.visibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE)))
        assertEquals(SettingsSection.entries - SettingsSection.SHOP_DATA - SettingsSection.ISSUE_REPORTS, visible)
    }

    /** `IssueReportRules` puts every settings/token route behind ISSUE_REPORT_MANAGE, ADMIN only. */
    @Test
    fun issueReports_needIssueReportManage_whichAManagerLacks() {
        assertFalse(SettingsSection.ISSUE_REPORTS.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE, Capability.ISSUE_REPORT_CREATE))))
        assertTrue(SettingsSection.ISSUE_REPORTS.isVisibleTo(SetAccessPolicy(setOf(Capability.ISSUE_REPORT_MANAGE))))
        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        assertFalse(SettingsSection.ISSUE_REPORTS in SettingsSection.visibleTo(SetAccessPolicy(manager)))
    }
}
