package org.example.project.core.domain.dto.issuereport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IssueReportDtoTest {
    @Test
    fun setTokenRequestNeverPrintsTheToken() {
        val secret = "github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val dto = IssueReportDto.SetTokenRequestDto(secret)
        assertFalse(dto.toString().contains(secret), dto.toString())
        assertFalse(dto.toString().contains(secret.takeLast(6)), dto.toString())
        // Redaction is display only: the wire value is intact.
        assertEquals(secret, dto.token)
    }
}
