package org.example.project.features.salesinvoice.einvoice

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParseResult
import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.check.En16931AmountRules
import org.example.project.einvoice.check.En16931RequiredFields
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.einvoice.syntax.CiiInvoiceWriter
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T9-4: an issued invoice stores money at four decimals and a from-bill line's net is derived at four
 * (`10.00 / 1.19 = 8.4034`). The e-invoice must carry two decimals (BR-DEC) AND keep its sums
 * (BR-CO-10, BR-S-08, BR-CO-16) — rounding each amount alone would lose a cent between the lines
 * and the fiscal split the receipt printed.
 */
class EInvoiceCentRoundingTest {
    private fun line(
        id: String,
        net: String,
        rate: String = "19.00",
        quantity: String = "1",
        lineNet: String = net,
    ) = EInvoiceLine(
        lineId = id,
        itemName = "Schnitzel $id",
        quantity = BigDecimal(quantity),
        unitCode = "C62",
        netUnitPrice = BigDecimal(net),
        lineNetAmount = BigDecimal(lineNet),
        vatCategoryCode = "S",
        vatRate = BigDecimal(rate),
    )

    /** Two 10.00 € dishes at 19 %, as the from-bill path stores them: lines 8.4034, fiscal split 16.81 / 3.19. */
    private val fromBill =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = "RG-2026-00017",
            issueDate = LocalDate(2026, 9, 20),
            currency = "EUR",
            seller = EInvoiceParty(name = "Zenbon GmbH", vatId = "DE123456789", street = "Hauptstr. 1", zip = "10115", city = "Berlin", country = "DE"),
            buyer = EInvoiceParty(name = "Kunde GmbH", street = "Weg 2", zip = "80331", city = "München", country = "DE"),
            vatBreakdown =
                listOf(
                    EInvoiceVatBreakdown(
                        categoryCode = "S",
                        rate = BigDecimal("19.00"),
                        taxableAmount = BigDecimal("16.8100"),
                        vatAmount = BigDecimal("3.1900"),
                    ),
                ),
            lines = listOf(line("1", "8.4034"), line("2", "8.4034")),
            invoiceTypeCode = "380",
            lineNetTotal = BigDecimal("16.8100"),
            taxExclusiveTotal = BigDecimal("16.8100"),
            taxTotal = BigDecimal("3.1900"),
            taxInclusiveTotal = BigDecimal("20.0000"),
            prepaidAmount = BigDecimal("20.0000"),
            amountDue = BigDecimal("0.0000"),
        )

    @Test
    fun `the stored form breaks BR-DEC and BR-CO-10, which is the defect`() {
        assertNotNull(En16931AmountRules.check(fromBill))
        assertFailsWith<IllegalArgumentException> { CiiInvoiceWriter.write(fromBill) }
    }

    @Test
    fun `rounded lines still add up to the fiscal split`() {
        val rounded = EInvoiceCentRounding.apply(fromBill)

        assertEquals(listOf(BigDecimal("8.41"), BigDecimal("8.40")), rounded.lines.map { it.lineNetAmount })
        assertEquals(BigDecimal("16.81"), rounded.lineNetTotal)
        assertEquals(BigDecimal("16.81"), rounded.vatBreakdown.single().taxableAmount)
        assertEquals(BigDecimal("3.19"), rounded.taxTotal)
        assertEquals(BigDecimal("20.00"), rounded.taxInclusiveTotal)
        assertEquals(BigDecimal("0.00"), rounded.amountDue)
        // A single unit's price follows its line, so quantity × price = line net on the page.
        assertEquals(rounded.lines.map { it.lineNetAmount }, rounded.lines.map { it.netUnitPrice })

        assertNull(EInvoiceInvariants.check(rounded))
        assertNull(En16931RequiredFields.check(rounded))
        assertNull(En16931AmountRules.check(rounded))
    }

    @Test
    fun `the QA case - 7 and 19 percent at stored scale - writes and parses back unchanged in value`() {
        val qa =
            fromBill.copy(
                vatBreakdown =
                    listOf(
                        EInvoiceVatBreakdown(
                            categoryCode = "S",
                            rate = BigDecimal("7.00"),
                            taxableAmount = BigDecimal("100.0000"),
                            vatAmount = BigDecimal("7.0000"),
                        ),
                        EInvoiceVatBreakdown(
                            categoryCode = "S",
                            rate = BigDecimal("19.00"),
                            taxableAmount = BigDecimal("100.0000"),
                            vatAmount = BigDecimal("19.0000"),
                        ),
                    ),
                lines = listOf(line("1", "100.0000", rate = "7.00"), line("2", "50.0000", quantity = "2", lineNet = "100.0000")),
                lineNetTotal = BigDecimal("200.0000"),
                taxExclusiveTotal = BigDecimal("200.0000"),
                taxTotal = BigDecimal("26.0000"),
                taxInclusiveTotal = BigDecimal("226.0000"),
                prepaidAmount = BigDecimal("0.0000"),
                amountDue = BigDecimal("226.0000"),
            )

        val rounded = EInvoiceCentRounding.apply(qa)
        val result = DefaultEInvoiceParser().parse(CiiInvoiceWriter.write(rounded), "application/xml")
        val parsed = (result as EInvoiceParseResult.Success).invoice

        assertEquals(BigDecimal("226.00"), parsed.taxInclusiveTotal)
        assertEquals(BigDecimal("26.00"), parsed.taxTotal)
        assertEquals(BigDecimal("226.00"), parsed.amountDue)
        assertEquals(listOf(BigDecimal("100.00"), BigDecimal("100.00")), parsed.lines.map { it.lineNetAmount })
        // BT-146 of a multi-unit line is exempt from BR-DEC and keeps its stored scale.
        assertEquals(BigDecimal("50.0000"), parsed.lines[1].netUnitPrice)
        assertNull(EInvoiceInvariants.check(parsed))
        assertNull(En16931AmountRules.check(parsed))
    }

    // --- BR-CO-17: VAT = taxable × rate, rounded — which the fiscal by-subtraction split can miss ---

    /** A one-group invoice as the from-bill path stores it: fiscal split by subtraction, three 4-decimal lines. */
    private fun fiscal(
        grossCents: Long,
        rate: String,
    ): EInvoice {
        val gross = BigDecimal.valueOf(grossCents, 2)
        val net = gross.divide(BigDecimal.ONE + BigDecimal(rate).movePointLeft(2), 2, RoundingMode.HALF_UP)
        val third = net.divide(BigDecimal(3), 4, RoundingMode.HALF_UP)
        val nets = listOf(third, third, net - third - third)
        return fromBill.copy(
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal(rate), net.setScale(4), (gross - net).setScale(4))),
            lines = nets.mapIndexed { i, n -> line("${i + 1}", n.toPlainString(), rate = rate) },
            lineNetTotal = net.setScale(4),
            taxExclusiveTotal = net.setScale(4),
            taxTotal = (gross - net).setScale(4),
            taxInclusiveTotal = gross.setScale(4),
            prepaidAmount = BigDecimal("0.0000"),
            amountDue = gross.setScale(4),
        )
    }

    private fun EInvoiceVatBreakdown.gross() = taxableAmount + vatAmount

    /**
     * True when some taxable amount t (in cents, 0 ≤ t ≤ gross) has t + round(t × rate) = gross — an
     * exhaustive search in integer arithmetic, independent of the BigDecimal code under test.
     */
    private fun brCo17CanReach(
        grossCents: Long,
        rate: String,
    ): Boolean {
        val basisPoints = BigDecimal(rate).movePointRight(2).longValueExact()
        // round-half-up(t × bp / 10 000) for t ≥ 0, in integers.
        return (0..grossCents).any { t -> t + (2 * t * basisPoints + 10_000) / 20_000 == grossCents }
    }

    @Test
    fun `the fiscal split of 0,08 at 7 percent breaks BR-CO-17 - the XML states BR-CO-17 VAT and gives the cent back as BT-114`() {
        val stored = fiscal(8, "7.00")
        // Fiscal: net 0.07, VAT 0.01 by subtraction — but 0.07 × 7 % = 0.0049 → 0.00.
        assertEquals(BigDecimal("0.0100"), stored.vatBreakdown.single().vatAmount)

        val rounded = EInvoiceCentRounding.apply(stored)
        val group = rounded.vatBreakdown.single()

        assertEquals(BigDecimal("0.07"), group.taxableAmount, "the taxable amount is the fiscal net")
        assertEquals(BigDecimal("0.00"), group.vatAmount, "BR-CO-17")
        assertEquals(BigDecimal("0.07"), rounded.taxInclusiveTotal, "BR-CO-15: BT-112 is the sum of the groups")
        assertEquals(BigDecimal("0.01"), rounded.roundingAmount, "BT-114 gives the cent back")
        assertEquals(BigDecimal("0.08"), rounded.amountDue, "BT-115 is still what the customer owes")
        assertTrue(!brCo17CanReach(8, "7.00"), "no split of 0.08 at 7 % satisfies BR-CO-17")
        assertNull(En16931AmountRules.check(rounded))
        assertNull(EInvoiceInvariants.check(rounded))
    }

    @Test
    fun `every gross from 0,01 to 200,00 at 7 and 19 percent satisfies BR-CO-17 and keeps what is owed`() {
        val unreachable = mutableMapOf("7.00" to 0, "19.00" to 0)
        for (rate in listOf("7.00", "19.00")) {
            for (cents in 1L..20_000L) {
                val gross = BigDecimal.valueOf(cents, 2)
                val stored = fiscal(cents, rate)
                val rounded = EInvoiceCentRounding.apply(stored)
                val group = rounded.vatBreakdown.single()
                val where = "gross $gross at $rate %"

                assertEquals(En16931AmountRules.vatOf(group.taxableAmount, group.rate), group.vatAmount, "BR-CO-17, $where")
                assertEquals(
                    0,
                    stored.vatBreakdown
                        .single()
                        .taxableAmount
                        .compareTo(group.taxableAmount),
                    "taxable = fiscal net, $where",
                )
                assertNull(En16931AmountRules.check(rounded), where)
                assertNull(EInvoiceInvariants.check(rounded), where)
                assertEquals(gross, rounded.amountDue, "BT-115 is what the customer owes, $where")
                if (group.gross().compareTo(gross) == 0) {
                    assertNull(rounded.roundingAmount, "no rounding amount when the gross is kept, $where")
                } else {
                    // Gross preservation is given up only where NO taxable amount reaches this gross.
                    unreachable[rate] = unreachable.getValue(rate) + 1
                    assertTrue(!brCo17CanReach(cents, rate), "the gross was reachable but not kept, $where")
                    assertEquals(BigDecimal("0.01"), (group.gross() - gross).abs(), "a conflict costs one cent, $where")
                    assertEquals(gross - group.gross(), rounded.roundingAmount, "BT-114 gives the cent back, $where")
                    assertEquals(group.gross(), rounded.taxInclusiveTotal, "BR-CO-15, $where")
                }
            }
        }
        // Exactly the grosses BR-CO-17 cannot reach (q/(1+q) of them) — computed independently.
        assertEquals(mapOf("7.00" to 1_308, "19.00" to 3_193), unreachable)
    }

    @Test
    fun `a credit note's negated groups are restated as the exact negation of the invoice's`() {
        for (cents in listOf(3L, 20L, 1999L, 12345L)) {
            val invoice = EInvoiceCentRounding.apply(fiscal(cents, "19.00"))
            val stored = fiscal(cents, "19.00")
            val credit =
                EInvoiceCentRounding.apply(
                    stored.copy(
                        invoiceTypeCode = "381",
                        vatBreakdown = stored.vatBreakdown.map { it.copy(taxableAmount = it.taxableAmount.negate(), vatAmount = it.vatAmount.negate()) },
                        lines = stored.lines.map { it.copy(lineNetAmount = it.lineNetAmount.negate()) },
                        lineNetTotal = stored.lineNetTotal?.negate(),
                        taxExclusiveTotal = stored.taxExclusiveTotal?.negate(),
                        taxTotal = stored.taxTotal?.negate(),
                        taxInclusiveTotal = stored.taxInclusiveTotal?.negate(),
                        amountDue = stored.amountDue?.negate(),
                    ),
                )
            assertEquals(
                invoice.vatBreakdown
                    .single()
                    .taxableAmount
                    .negate(),
                credit.vatBreakdown.single().taxableAmount,
            )
            assertEquals(
                invoice.vatBreakdown
                    .single()
                    .vatAmount
                    .negate(),
                credit.vatBreakdown.single().vatAmount,
            )
            assertEquals(invoice.roundingAmount?.negate(), credit.roundingAmount)
            assertNull(En16931AmountRules.check(credit))
        }
    }

    @Test
    fun `a restated invoice survives the round trip through the einvoice parser`() {
        var tripped = 0
        for (rate in listOf("7.00", "19.00")) {
            for (cents in 1L..5_000L) {
                val stored = fiscal(cents, rate)
                val rounded = EInvoiceCentRounding.apply(stored)
                val storedVat = stored.vatBreakdown.single().vatAmount
                if (rounded.vatBreakdown
                        .single()
                        .vatAmount
                        .compareTo(storedVat) == 0
                ) {
                    continue
                }
                tripped++
                val parsed = (DefaultEInvoiceParser().parse(CiiInvoiceWriter.write(rounded), "application/xml") as EInvoiceParseResult.Success).invoice
                val where = "gross ${BigDecimal.valueOf(cents, 2)} at $rate %"

                assertEquals(rounded.vatBreakdown.map { it.taxableAmount to it.vatAmount }, parsed.vatBreakdown.map { it.taxableAmount to it.vatAmount }, where)
                assertEquals(rounded.roundingAmount, parsed.roundingAmount, where)
                assertEquals(rounded.taxInclusiveTotal, parsed.taxInclusiveTotal, where)
                assertEquals(BigDecimal.valueOf(cents, 2), parsed.amountDue, where)
                assertEquals(rounded.lines.map { it.lineNetAmount }, parsed.lines.map { it.lineNetAmount }, where)
                assertNull(EInvoiceInvariants.check(parsed), where)
                assertNull(En16931AmountRules.check(parsed), where)
            }
        }
        assertTrue(tripped > 500, "the sweep must actually exercise restated groups, got $tripped")
    }

    /** The same two dishes with a 10 % promotion (2.00 off): the bill row 18.00 = 15.13 + 2.87, allowance net 1.68. */
    private val promoted =
        fromBill.copy(
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal("19.00"), BigDecimal("15.1300"), BigDecimal("2.8700"))),
            allowancesCharges =
                listOf(
                    EInvoiceAllowanceCharge(
                        isCharge = false,
                        amount = BigDecimal("1.6800"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("19.00"),
                        reasonCode = "95",
                        reasonText = "Aktion: Happy Hour",
                    ),
                ),
            lineNetTotal = BigDecimal("16.8100"),
            allowanceTotal = BigDecimal("1.6800"),
            taxExclusiveTotal = BigDecimal("15.1300"),
            taxTotal = BigDecimal("2.8700"),
            taxInclusiveTotal = BigDecimal("18.0000"),
            prepaidAmount = BigDecimal("18.0000"),
        )

    @Test
    fun `a promoted invoice's lines add up to the group plus its allowance, and BT-106 is BT-109 plus BT-107`() {
        val rounded = EInvoiceCentRounding.apply(promoted)

        assertNull(En16931AmountRules.check(rounded), "BR-DEC, BR-CO-10/11/13, BR-S-08 hold")
        assertEquals(BigDecimal("16.81"), rounded.lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineNetAmount })
        assertEquals(BigDecimal("16.81"), rounded.lineNetTotal)
        assertEquals(BigDecimal("1.68"), rounded.allowanceTotal)
        assertEquals(BigDecimal("1.68"), rounded.allowancesCharges.single().amount)
        assertEquals(BigDecimal("15.13"), rounded.taxExclusiveTotal)
        assertEquals(BigDecimal("18.00"), rounded.taxInclusiveTotal)
        assertEquals(BigDecimal("0.00"), rounded.amountDue)

        val parsed = DefaultEInvoiceParser().parse(CiiInvoiceWriter.write(rounded), "application/xml")
        assertTrue(parsed is EInvoiceParseResult.Success, "got $parsed")
        assertEquals(rounded.allowancesCharges, parsed.invoice.allowancesCharges)
        assertEquals(rounded.allowanceTotal, parsed.invoice.allowanceTotal)
    }

    @Test
    fun `an invoice without allowances is rounded exactly as before`() {
        val rounded = EInvoiceCentRounding.apply(fromBill)
        assertTrue(rounded.allowancesCharges.isEmpty())
        assertNull(rounded.allowanceTotal)
        assertEquals(BigDecimal("16.81"), rounded.lineNetTotal)
    }
}
