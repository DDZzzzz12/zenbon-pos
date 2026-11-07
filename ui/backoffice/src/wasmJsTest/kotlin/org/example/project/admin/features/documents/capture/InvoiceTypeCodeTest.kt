package org.example.project.admin.features.documents.capture

import org.example.project.admin.features.documents.presentation.capture.InvoiceTypeCode
import org.example.project.admin.features.documents.presentation.capture.invoiceTypeCodeLabelKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Clones `DocumentAppliedFilterChipsTest.everyAdvancedFieldHasALabelKey` — the totality guard is the whole
 * reason the label mapper is a pure `StringKey`-returning function rather than a `@Composable`
 * returning `String` like `processingStatusLabel`, which no test can reach.
 */
class InvoiceTypeCodeTest {
    @Test
    fun `every invoice type code has a label key`() {
        val distinct = InvoiceTypeCode.entries.map { invoiceTypeCodeLabelKey(it) }.toSet()
        assertEquals(InvoiceTypeCode.entries.size, distinct.size, "each code needs its own label")
    }

    @Test
    fun `fromCode maps the three offered codes and rejects anything else`() {
        assertEquals(InvoiceTypeCode.RECHNUNG, InvoiceTypeCode.fromCode("380"))
        assertEquals(InvoiceTypeCode.GUTSCHRIFT, InvoiceTypeCode.fromCode("381"))
        assertEquals(InvoiceTypeCode.KORREKTUR, InvoiceTypeCode.fromCode("384"))
        assertNull(InvoiceTypeCode.fromCode("389"), "a parsed code we do not offer must not be coerced")
        assertNull(InvoiceTypeCode.fromCode(""))
    }
}
