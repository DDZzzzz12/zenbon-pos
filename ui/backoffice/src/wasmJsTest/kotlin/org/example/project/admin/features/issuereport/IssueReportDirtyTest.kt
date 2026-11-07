package org.example.project.admin.features.issuereport

import org.example.project.admin.features.issuereport.presentation.IssueReportState
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReportDirtyTest {
    @Test
    fun `an empty form is clean`() {
        assertFalse(IssueReportState().isDirty)
        assertFalse(IssueReportState(title = "  ", description = "").isDirty)
    }

    @Test
    fun `a typed title or description is dirty`() {
        assertTrue(IssueReportState(title = "Bug").isDirty)
        assertTrue(IssueReportState(description = "Details").isDirty)
    }

    @Test
    fun `a sent report is clean even though the text is still there`() {
        val created = IssueReportDto.CreatedDto(issueNumber = 1L, issueUrl = "https://example.invalid/1")
        assertFalse(IssueReportState(title = "Bug", description = "Details", created = created).isDirty)
    }
}
