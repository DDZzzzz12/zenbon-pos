package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.components.documentListDate
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R6-3: the Belege list's "Datum" column rendered the server's ISO local date-time, which the narrow
 * column clipped to `2026-01-15T`. It now shows the same short date the rest of the console uses.
 */
class DocumentListDateTest {
    private fun item(
        date: String? = null,
        statementDate: String? = null,
    ) = DocumentDto.DocumentListItemDto(
        id = 1L,
        filename = "doc.pdf",
        fileType = "INVOICE",
        processingStatus = ProcessingStatus.COMPLETED,
        isActive = true,
        linkCount = 0,
        date = date,
        statementDate = statementDate,
    )

    @Test
    fun invoiceDate_isAGermanShortDate() {
        assertEquals("15.01.2026", documentListDate(item(date = "2026-01-15T00:00"), Language.GERMAN))
    }

    @Test
    fun statementDate_isTheFallback() {
        assertEquals("31.03.2026", documentListDate(item(statementDate = "2026-03-31T00:00"), Language.GERMAN))
    }

    @Test
    fun chinese_followsTheLanguageSetting() {
        assertEquals("2026年01月15日", documentListDate(item(date = "2026-01-15T00:00"), Language.CHINESE))
    }

    @Test
    fun noDate_isAnEmDash() {
        assertEquals("—", documentListDate(item(), Language.GERMAN))
    }
}
