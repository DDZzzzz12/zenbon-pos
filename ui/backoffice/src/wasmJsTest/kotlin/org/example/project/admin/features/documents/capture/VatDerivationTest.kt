package org.example.project.admin.features.documents.capture

import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import org.example.project.admin.features.documents.presentation.capture.deriveVatRow
import org.example.project.admin.features.documents.presentation.capture.toVatBreakdown
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mirrors `LegacyInvoiceSync.splitGross` operation for operation:
 *
 * ```
 * divisor = 1 + rate/100   scale MONEY_SCALE + 2 = 4, HALF_UP
 * net     = gross / divisor  scale MONEY_SCALE = 2, HALF_UP
 * vat     = gross − net      by subtraction
 * ```
 *
 * The subtraction is the point. The DATEV export re-adds `taxable + vat` to produce the ledger entry
 * amount, so a VAT computed independently (`gross * rate/(100+rate)`) can differ by a cent and move
 * money on the ledger. `taxable + vat == gross` must hold exactly, for every rate.
 */
class VatDerivationTest {
    @Test
    fun `nineteen percent on 238,00 splits the way the server splits it`() {
        val row = deriveVatRow(rate = "19", gross = "238,00")!!
        assertEquals("200.00", row.taxableAmount)
        assertEquals("38.00", row.vatAmount)
        assertEquals("19", row.vatRate)
        assertEquals("S", row.vatCategoryCode)
    }

    @Test
    fun `taxable plus vat equals gross exactly, including where the division does not`() {
        listOf("0,01", "0,03", "19,99", "100,00", "1234,57", "7,77").forEach { gross ->
            listOf("19", "7", "0").forEach { rate ->
                val row = deriveVatRow(rate, gross)!!
                val sum = Decimal.parse(row.taxableAmount) + Decimal.parse(row.vatAmount)
                assertEquals(
                    0,
                    sum.compareTo(Decimal.parse(gross.replace(",", "."))),
                    "taxable + vat must equal gross for rate=$rate gross=$gross",
                )
            }
        }
    }

    @Test
    fun `a zero rate is category Z and a positive rate is category S`() {
        assertEquals("Z", deriveVatRow("0", "100,00")!!.vatCategoryCode)
        assertEquals("S", deriveVatRow("0,01", "100,00")!!.vatCategoryCode)
    }

    @Test
    fun `an unparseable rate or gross derives nothing rather than guessing`() {
        assertNull(deriveVatRow("neunzehn", "238,00"))
        assertNull(deriveVatRow("19", ""))
    }

    @Test
    fun `a single-rate form takes the whole gross and a two-rate form takes each row's own`() {
        val single = InvoiceCaptureForm(grossTotal = "238,00", vatRows = listOf(VatRowForm(rate = "19")))
        assertEquals(listOf("200.00"), single.toVatBreakdown().map { it.taxableAmount })

        val mixed =
            InvoiceCaptureForm(
                grossTotal = "238,00",
                vatRows = listOf(VatRowForm(rate = "19", gross = "119,00"), VatRowForm(rate = "7", gross = "119,00")),
            )
        assertEquals(listOf("100.00", "111.21"), mixed.toVatBreakdown().map { it.taxableAmount })
        assertEquals(listOf("19.00", "7.79"), mixed.toVatBreakdown().map { it.vatAmount })
    }
}
