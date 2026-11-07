package org.example.project.features.issuereport

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RoutePermissions
import org.example.project.infrastructure.security.rules.IssueReportRules
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("IssueReportRules")
class IssueReportRulesTest {
    private fun cap(
        path: String,
        method: HttpMethod,
    ): Capability = (assertNotNull(IssueReportRules.rules[path to method], "$method $path") as Cap).capability

    @Test
    fun `filing needs CREATE and configuring needs MANAGE`() {
        assertEquals(Capability.ISSUE_REPORT_CREATE, cap("/api/issue-reports", HttpMethod.Post))
        assertEquals(Capability.ISSUE_REPORT_CREATE, cap("/api/issue-reports/preview", HttpMethod.Post))
        assertEquals(Capability.ISSUE_REPORT_MANAGE, cap("/api/issue-reports/settings", HttpMethod.Get))
        assertEquals(Capability.ISSUE_REPORT_MANAGE, cap("/api/issue-reports/settings", HttpMethod.Put))
        assertEquals(Capability.ISSUE_REPORT_MANAGE, cap("/api/issue-reports/token", HttpMethod.Put))
        assertEquals(Capability.ISSUE_REPORT_MANAGE, cap("/api/issue-reports/token", HttpMethod.Delete))
    }

    @Test
    fun `the module is registered`() {
        assertTrue(IssueReportRules in RoutePermissions.modules)
    }

    @Test
    fun `a manager may file but not configure`() {
        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        assertTrue(Capability.ISSUE_REPORT_CREATE in manager)
        assertFalse(Capability.ISSUE_REPORT_MANAGE in manager)
    }

    @Test
    fun `a cashier holds neither`() {
        val cashier = CapabilityBundles.forRole(EmployeeRole.CASHIER)
        assertFalse(Capability.ISSUE_REPORT_CREATE in cashier)
        assertFalse(Capability.ISSUE_REPORT_MANAGE in cashier)
    }

    @Test
    fun `an auditor holds neither`() {
        val auditor = assertNotNull(CapabilityBundles.forGroup("AUDITOR"))
        assertFalse(Capability.ISSUE_REPORT_CREATE in auditor)
        assertFalse(Capability.ISSUE_REPORT_MANAGE in auditor)
    }

    @Test
    fun `only an administrator holds MANAGE`() {
        assertTrue(Capability.ISSUE_REPORT_MANAGE in CapabilityBundles.forRole(EmployeeRole.ADMIN))
    }
}
