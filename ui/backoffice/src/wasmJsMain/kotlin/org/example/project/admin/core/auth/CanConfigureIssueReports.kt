package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Reading or changing where problem reports go, and storing the GitHub token used to send them —
 * every `/api/issue-reports/settings` and `/token` route. ADMIN only: a manager who may *file* a
 * report must not be able to repoint it or swap the credential.
 */
val TokenStore.canConfigureIssueReports: Boolean
    get() = holds(Capability.ISSUE_REPORT_MANAGE)
