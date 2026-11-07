package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Filing a problem report (`POST /api/issue-reports` and `/preview`) — what the user menu's
 * "Report a problem" entry and the dialog's actions are gated on. Held by MANAGER and ADMIN.
 */
val TokenStore.canReportProblems: Boolean
    get() = holds(Capability.ISSUE_REPORT_CREATE)
