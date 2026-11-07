package org.example.project.admin.features.documents

import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentAppliedFilter
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.components.advancedFieldLabelKey
import org.example.project.admin.features.documents.presentation.components.documentAppliedFilters
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentAppliedFilterChipsTest {
    @Test
    fun everyAdvancedFieldHasALabelKey() {
        // A field added without a label key would render an empty chip. Pin the mapping.
        assertEquals(
            listOf(
                AdminStringKeys.DocumentsFieldVendor,
                AdminStringKeys.DocumentsFieldInvoiceNumber,
                AdminStringKeys.DocumentsFieldMinAmount,
                AdminStringKeys.DocumentsFieldMaxAmount,
                AdminStringKeys.DocumentsFieldBank,
                AdminStringKeys.DocumentsFieldStatementNumber,
                AdminStringKeys.DocumentsFieldUploadedBy,
            ),
            DocumentSearchFilter.AdvancedField.entries.map { advancedFieldLabelKey(it) },
        )
    }

    @Test
    fun appliedFiltersFollowFieldOrderThenStatusOrderThenInactive() {
        val applied =
            DocumentSearchFilter(
                anchor = LocalDate(2026, 10, 21),
                searchTerm = "ignored",
                fileType = "INVOICE",
                uploadedBy = "admin",
                vendorName = "Metro",
                processingStatuses = setOf(ProcessingStatus.FAILED, ProcessingStatus.NONE),
                includeInactive = true,
            )
        assertEquals(
            listOf(
                DocumentAppliedFilter.Advanced(DocumentSearchFilter.AdvancedField.VENDOR_NAME, "Metro"),
                DocumentAppliedFilter.Advanced(DocumentSearchFilter.AdvancedField.UPLOADED_BY, "admin"),
                DocumentAppliedFilter.Status(ProcessingStatus.NONE),
                DocumentAppliedFilter.Status(ProcessingStatus.FAILED),
                DocumentAppliedFilter.IncludeInactive,
            ),
            documentAppliedFilters(applied),
        )
    }

    @Test
    fun noPanelFilterMeansNoChips() {
        // Search, type and period are not panel filters: they never produce a removable chip.
        val applied = DocumentSearchFilter(anchor = LocalDate(2026, 10, 21), searchTerm = "x", fileType = "INVOICE")
        assertTrue(documentAppliedFilters(applied).isEmpty())
    }
}
