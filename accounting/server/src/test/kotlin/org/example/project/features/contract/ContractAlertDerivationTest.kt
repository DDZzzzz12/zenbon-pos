package org.example.project.features.contract

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.data.service.ContractAlert
import org.example.project.features.contract.data.service.ContractAlertRules
import org.example.project.features.contract.data.service.InstalmentFacts
import org.example.project.features.contract.data.service.deriveAlerts
import org.example.project.features.contract.domain.models.ContractAlertKind
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three things a standing obligation exists to catch: the money did not go out, the invoice did
 * not arrive, and 89,00 quietly became 129,00.
 *
 * Pure, so every case is a few lines. The one that matters most is the invoice-less contract, which
 * must never report a missing invoice — otherwise every rent contract flags every month and the
 * operator stops reading the alerts altogether.
 */
@DisplayName("Contract alert derivation")
class ContractAlertDerivationTest {
    private val today = LocalDate(2026, 6, 15)
    private val pastDue = LocalDate(2026, 5, 1)
    private val longPastDue = LocalDate(2020, 1, 1)
    private val notYetDue = LocalDate(2026, 7, 1)

    private fun rules(
        expectsInvoice: Boolean = true,
        expected: String? = "100.0000",
        pct: String? = null,
        abs: String? = null,
    ) = ContractAlertRules(
        expectsInvoice = expectsInvoice,
        expectedAmount = expected?.let { BigDecimal(it) },
        tolerancePct = pct?.let { BigDecimal(it) },
        toleranceAbs = abs?.let { BigDecimal(it) },
    )

    private fun instalment(
        due: LocalDate = pastDue,
        status: String = "OPEN",
        expected: String? = "100.0000",
        open: String? = "100.0000",
        invoiceTotal: String? = "100.0000",
        allocated: String? = null,
        hasInvoice: Boolean = invoiceTotal != null,
    ) = InstalmentFacts(
        id = 7L,
        dueDate = due,
        storedStatus = status,
        expectedAmount = expected?.let { BigDecimal(it) },
        openAmount = open?.let { BigDecimal(it) },
        hasInvoice = hasInvoice,
        invoiceTotal = invoiceTotal?.let { BigDecimal(it) },
        allocatedTotal = allocated?.let { BigDecimal(it) },
    )

    @Test
    fun `a linked invoice that states no total is not a missing invoice`() {
        // A hand-captured invoice often carries neither amount_due nor tax_inclusive_total. It is
        // still a document sitting right there, so reporting it as missing would send an operator
        // looking for something they already filed.
        val instalment = instalment(open = "100.0000", invoiceTotal = null, hasInvoice = true)

        val alerts = derive(rules(expectsInvoice = true), instalment)

        assertTrue(ContractAlertKind.INVOICE_MISSING !in kinds(alerts), kinds(alerts).toString())
        assertTrue(ContractAlertKind.AMOUNT_OFF_BAND !in kinds(alerts), "no total means nothing to compare")
        assertEquals(listOf(ContractAlertKind.PAYMENT_MISSING), kinds(alerts))
    }

    private fun derive(
        rules: ContractAlertRules,
        vararg instalments: InstalmentFacts,
    ) = deriveAlerts(rules = rules, instalments = instalments.toList(), asOf = today)

    private fun kinds(alerts: List<ContractAlert>) = alerts.map { it.kind }

    @Test
    fun `past due with a residual reports a missing payment`() {
        val alerts = derive(rules(), instalment(open = "100.0000"))
        assertEquals(listOf(ContractAlertKind.PAYMENT_MISSING), kinds(alerts))
        assertEquals(7L, alerts.single().instalmentId)
        assertEquals(pastDue, alerts.single().dueDate)
    }

    @Test
    fun `past due and settled reports nothing`() {
        assertEquals(emptyList(), derive(rules(), instalment(open = "0.0000")))
    }

    @Test
    fun `not yet due and unpaid reports nothing`() {
        val alerts = derive(rules(), instalment(due = notYetDue, open = "100.0000"))
        assertEquals(emptyList(), alerts, "a future instalment is not a problem")
    }

    @Test
    fun `an expected invoice that never arrived is reported`() {
        val alerts = derive(rules(expectsInvoice = true), instalment(open = "0.0000", invoiceTotal = null))
        assertEquals(listOf(ContractAlertKind.INVOICE_MISSING), kinds(alerts))
    }

    @Test
    fun `an invoice-less contract never reports a missing invoice however late`() {
        val instalment = instalment(due = longPastDue, open = "0.0000", invoiceTotal = null)
        val alerts = derive(rules(expectsInvoice = false), instalment)
        assertTrue(
            ContractAlertKind.INVOICE_MISSING !in kinds(alerts),
            "this is the case that would otherwise flag every rent contract every month",
        )
        assertEquals(emptyList(), alerts)
    }

    @Test
    fun `an invoice outside the band reports both figures`() {
        val instalment = instalment(due = notYetDue, open = "100.0000", invoiceTotal = "129.0000")
        val alert = derive(rules(expectsInvoice = true), instalment).single()
        assertEquals(ContractAlertKind.AMOUNT_OFF_BAND, alert.kind)
        assertEquals(0, BigDecimal("100.00").compareTo(alert.expectedAmount), "expected amount")
        assertEquals(0, BigDecimal("129.00").compareTo(alert.actualAmount), "actual amount")
    }

    @Test
    fun `an invoice-less contract is judged on what was actually allocated`() {
        val instalment = instalment(due = notYetDue, open = "0.0000", invoiceTotal = null, allocated = "129.0000")
        val alert = derive(rules(expectsInvoice = false), instalment).single()
        assertEquals(ContractAlertKind.AMOUNT_OFF_BAND, alert.kind)
        assertEquals(0, BigDecimal("129.00").compareTo(alert.actualAmount))
    }

    @Test
    fun `an amount inside the band reports nothing`() {
        val instalment = instalment(due = notYetDue, open = "0.0000", invoiceTotal = "105.0000")
        val alerts = derive(rules(expectsInvoice = true, pct = "10.00"), instalment)
        assertEquals(emptyList(), alerts, "a percentage band is set precisely so this stays quiet")
    }

    @Test
    fun `nothing to compare means no off-band alert`() {
        val instalment = instalment(open = "100.0000", invoiceTotal = null, allocated = null)
        val alerts = derive(rules(expectsInvoice = false), instalment)
        assertEquals(
            listOf(ContractAlertKind.PAYMENT_MISSING),
            kinds(alerts),
            "an instalment with no invoice and nothing allocated is not answerable on amount",
        )
    }

    @Test
    fun `a waived instalment reports nothing at all`() {
        val instalment = instalment(due = longPastDue, status = "WAIVED", open = "100.0000", invoiceTotal = null)
        assertEquals(emptyList(), derive(rules(expectsInvoice = true), instalment), "that is what waiving means")
    }

    @Test
    fun `one instalment can raise both a missing invoice and a missing payment`() {
        val alerts = derive(rules(expectsInvoice = true), instalment(open = "100.0000", invoiceTotal = null))
        assertTrue(ContractAlertKind.PAYMENT_MISSING in kinds(alerts))
        assertTrue(ContractAlertKind.INVOICE_MISSING in kinds(alerts))
        assertEquals(2, alerts.size, "both, not the first match")
    }

    @Test
    fun `every instalment of the contract is judged`() {
        val late = instalment(open = "100.0000")
        val future = instalment(due = notYetDue, open = "100.0000")
        assertEquals(listOf(ContractAlertKind.PAYMENT_MISSING), kinds(derive(rules(expectsInvoice = false), late, future)))
    }
}
