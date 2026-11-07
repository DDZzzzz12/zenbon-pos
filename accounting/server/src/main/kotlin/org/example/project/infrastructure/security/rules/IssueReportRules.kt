package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Problem reporting — `IssueReportRoutes.kt`.
 *
 * Filing (and previewing) a report needs [Capability.ISSUE_REPORT_CREATE]. Everything that reveals
 * or changes where reports go, or stores the credential used to send them, needs
 * [Capability.ISSUE_REPORT_MANAGE]: a manager who may file a report must not be able to repoint
 * the token.
 */
object IssueReportRules : RuleModule {
    override val name = "Problem reports"

    override val pathPrefixes = listOf("/api/issue-reports")

    override val allowedCapabilities = setOf(Capability.ISSUE_REPORT_CREATE, Capability.ISSUE_REPORT_MANAGE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/issue-reports" to HttpMethod.Post) to Cap(Capability.ISSUE_REPORT_CREATE),
            ("/api/issue-reports/preview" to HttpMethod.Post) to Cap(Capability.ISSUE_REPORT_CREATE),
            ("/api/issue-reports/settings" to HttpMethod.Get) to Cap(Capability.ISSUE_REPORT_MANAGE),
            ("/api/issue-reports/settings" to HttpMethod.Put) to Cap(Capability.ISSUE_REPORT_MANAGE),
            ("/api/issue-reports/token" to HttpMethod.Put) to Cap(Capability.ISSUE_REPORT_MANAGE),
            ("/api/issue-reports/token" to HttpMethod.Delete) to Cap(Capability.ISSUE_REPORT_MANAGE),
        )
}
