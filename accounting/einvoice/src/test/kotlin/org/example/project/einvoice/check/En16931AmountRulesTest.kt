package org.example.project.einvoice.check

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BR-CO-17 and BR-CO-16 on an invoice we write — the two rules the fiscal VAT split can trip. */
class En16931AmountRulesTest {
    /** 0.08 € at 7 % in its fiscal split: net 0.07, VAT 0.01 by subtraction. */
    private val fiscalSplit =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = "RG-1",
            issueDate = LocalDate(2026, 9, 26),
            currency = "EUR",
            seller = EInvoiceParty(name = "Zenbon GmbH", vatId = "DE123456789", country = "DE"),
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal("7.00"), BigDecimal("0.07"), BigDecimal("0.01"))),
            lines =
                listOf(
                    EInvoiceLine(
                        lineId = "1",
                        itemName = "Wasser",
                        quantity = BigDecimal.ONE,
                        unitCode = "C62",
                        netUnitPrice = BigDecimal("0.07"),
                        lineNetAmount = BigDecimal("0.07"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("7.00"),
                    ),
                ),
            lineNetTotal = BigDecimal("0.07"),
            taxExclusiveTotal = BigDecimal("0.07"),
            taxTotal = BigDecimal("0.01"),
            taxInclusiveTotal = BigDecimal("0.08"),
            prepaidAmount = BigDecimal("0.00"),
            amountDue = BigDecimal("0.08"),
        )

    @Test
    fun `a group whose VAT is not taxable times rate is refused under BR-CO-17`() {
        val problem = En16931AmountRules.check(fiscalSplit)
        assertTrue(problem?.contains("BR-CO-17") == true, "got: $problem")
    }

    @Test
    fun `the BR-CO-17 VAT with the cent given back as BT-114 passes`() {
        val valid =
            fiscalSplit.copy(
                vatBreakdown = fiscalSplit.vatBreakdown.map { it.copy(vatAmount = BigDecimal("0.00")) },
                taxTotal = BigDecimal("0.00"),
                taxInclusiveTotal = BigDecimal("0.07"),
                roundingAmount = BigDecimal("0.01"),
            )
        assertNull(En16931AmountRules.check(valid))
    }

    @Test
    fun `an amount due that ignores the rounding amount is refused under BR-CO-16`() {
        val wrongDue =
            fiscalSplit.copy(
                vatBreakdown = fiscalSplit.vatBreakdown.map { it.copy(vatAmount = BigDecimal("0.00")) },
                taxTotal = BigDecimal("0.00"),
                taxInclusiveTotal = BigDecimal("0.07"),
                roundingAmount = BigDecimal("0.01"),
                amountDue = BigDecimal("0.07"),
            )
        val problem = En16931AmountRules.check(wrongDue)
        assertTrue(problem?.contains("BR-CO-16") == true, "got: $problem")
    }

    @Test
    fun `BR-CO-17 rounds half up symmetrically, so a credit note's negated group passes`() {
        assertEquals(BigDecimal("0.01"), En16931AmountRules.vatOf(BigDecimal("0.05"), BigDecimal("10.00")))
        assertEquals(BigDecimal("-0.01"), En16931AmountRules.vatOf(BigDecimal("-0.05"), BigDecimal("10.00")))
    }

    // ---- BG-20 document-level allowances --------------------------------------------------------

    /** 100.00 at 19 % less a 10.00 net allowance: taxable 90.00, VAT 17.10. */
    private val withAllowance =
        fiscalSplit.copy(
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal("19.00"), BigDecimal("90.00"), BigDecimal("17.10"))),
            lines = fiscalSplit.lines.map { it.copy(netUnitPrice = BigDecimal("100.00"), lineNetAmount = BigDecimal("100.00"), vatRate = BigDecimal("19.00")) },
            allowancesCharges = listOf(EInvoiceAllowanceCharge(false, BigDecimal("10.00"), "S", BigDecimal("19.00"), reasonCode = "95")),
            lineNetTotal = BigDecimal("100.00"),
            allowanceTotal = BigDecimal("10.00"),
            taxExclusiveTotal = BigDecimal("90.00"),
            taxTotal = BigDecimal("17.10"),
            taxInclusiveTotal = BigDecimal("107.10"),
            amountDue = BigDecimal("107.10"),
        )

    @Test
    fun `a group whose lines less its allowances are its taxable amount passes`() {
        assertNull(En16931AmountRules.check(withAllowance))
    }

    @Test
    fun `allowances that do not sum to BT-107 are refused under BR-CO-11`() {
        val problem = En16931AmountRules.check(withAllowance.copy(allowanceTotal = BigDecimal("9.99"), taxExclusiveTotal = BigDecimal("90.01")))
        assertTrue(problem!!.contains("BR-CO-11"), problem)
    }

    @Test
    fun `a tax-exclusive total that is not BT-106 less BT-107 is refused under BR-CO-13`() {
        val problem = En16931AmountRules.check(withAllowance.copy(lineNetTotal = BigDecimal("100.01")))
        // BR-CO-10 speaks first when the line total disagrees with the lines; move the line too.
        val moved =
            En16931AmountRules.check(
                withAllowance.copy(
                    lineNetTotal = BigDecimal("100.01"),
                    lines = withAllowance.lines.map { it.copy(lineNetAmount = BigDecimal("100.01")) },
                ),
            )
        assertTrue(problem != null)
        assertTrue(moved!!.contains("BR-CO-13"), moved)
    }

    @Test
    fun `an allowance at a rate whose group did not absorb it is refused`() {
        val problem =
            En16931AmountRules.check(
                withAllowance.copy(allowancesCharges = withAllowance.allowancesCharges.map { it.copy(vatRate = BigDecimal("7.00")) }),
            )
        assertTrue(problem!!.contains("taxable amount (BT-116)"), problem)
    }

    @Test
    fun `a sub-cent allowance amount breaks BR-DEC`() {
        val problem =
            En16931AmountRules.check(
                withAllowance.copy(allowancesCharges = withAllowance.allowancesCharges.map { it.copy(amount = BigDecimal("10.001")) }),
            )
        assertTrue(problem!!.contains("BT-92"), problem)
    }
}
