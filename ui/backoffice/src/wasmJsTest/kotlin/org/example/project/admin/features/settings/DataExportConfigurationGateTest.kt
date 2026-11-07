package org.example.project.admin.features.settings

import org.example.project.admin.features.settings.presentation.sections.showsDatevConfiguration
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Datenexport shows the DATEV configuration only to a principal holding `CONFIG_WRITE`. Its sidebar
 * entry is `DSFINVK_EXPORT`, which the auditor group holds; before this gate the page started a
 * ViewModel that loaded configuration the auditor may not read, surfacing 403 dialogs (T5-5).
 */
class DataExportConfigurationGateTest {
    @Test
    fun dsfinvkExportAloneDoesNotShowTheConfiguration() {
        assertFalse(showsDatevConfiguration(SetAccessPolicy(setOf(Capability.DSFINVK_EXPORT, Capability.ACCOUNTING_READ))))
    }

    @Test
    fun configWriteShowsTheConfiguration() {
        assertTrue(showsDatevConfiguration(SetAccessPolicy(setOf(Capability.CONFIG_WRITE))))
    }

    @Test
    fun managerAndAdminKeepTheConfiguration() {
        assertTrue(showsDatevConfiguration(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.MANAGER))))
        assertTrue(showsDatevConfiguration(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.ADMIN))))
    }

    @Test
    fun theAuditorReachesThePageButNotTheConfiguration() {
        val auditor = requireNotNull(CapabilityBundles.forGroup("AUDITOR")) { "no AUDITOR bundle" }
        assertTrue(Capability.DSFINVK_EXPORT in auditor, "the auditor must still reach Datenexport")
        assertFalse(showsDatevConfiguration(SetAccessPolicy(auditor)))
    }
}
