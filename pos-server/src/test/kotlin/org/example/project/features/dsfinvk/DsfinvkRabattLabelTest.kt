package org.example.project.features.dsfinvk

import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * m11: a fractional discount percentage was truncated in the Rabatt line's ARTIKELTEXT.
 *
 * `((BigDecimal.ONE - discountFactor) * 100).toInt()` truncates toward zero, so a 7,5 % discount
 * exported „Rabatt 7%" while the Beleg handed to the customer said 7,5 %.
 *
 * Nothing reconciled wrong — STK_BR carried the correct *amount*, and every cross-file identity is
 * built on amounts, not labels. But ARTIKELTEXT is precisely what a Prüfer reads next to the
 * printed receipt when spot-checking a discount, and on that comparison the two fiscal documents
 * contradicted each other. A label that is merely decorative is still wrong when it is wrong.
 */
@DisplayName("Rabatt ARTIKELTEXT keeps fractional percentages (m11)")
class DsfinvkRabattLabelTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @Test
    fun `a fractional percentage is not truncated`() {
        assertEquals(
            "Rabatt 7,5%",
            service.rabattLabel(BigDecimal("0.925")),
            "toInt() truncated 7,5 to 7 while the printed Beleg said 7,5 %",
        )
    }

    @Test
    fun `a whole percentage carries no decimals`() {
        assertEquals(
            "Rabatt 20%",
            service.rabattLabel(BigDecimal("0.80")),
            "the common case must stay unchanged — 20 %, not 20,00 %",
        )
    }

    @Test
    fun `the separator is the German comma, as printed on the Beleg`() {
        // ARTIKELTEXT is Alpha, not Numeric, so DsfinvkCsvUtils.formatDecimal never rewrites it —
        // the comma has to be produced here or the label alone would read in dot notation while
        // every number beside it reads in comma notation.
        assertEquals("Rabatt 12,5%", service.rabattLabel(BigDecimal("0.875")))
    }

    @Test
    fun `a discount finer than the label can express rounds rather than truncating`() {
        // 7,555 % → 7,56 %. Two decimals is beyond any UI-enterable discount; what matters is that
        // the residue rounds instead of silently disappearing the way toInt() dropped it.
        assertEquals("Rabatt 7,56%", service.rabattLabel(BigDecimal("0.92445")))
    }
}
