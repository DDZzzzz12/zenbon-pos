package org.example.project.features.salesinvoice

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.BillInvoiceFacts
import org.example.project.features.salesinvoice.domain.service.BillInvoiceLineFact
import org.example.project.features.salesinvoice.domain.service.BillInvoicePromotionFact
import org.example.project.features.salesinvoice.domain.service.BillInvoiceVatFact
import org.example.project.features.salesinvoice.domain.service.BillToInvoiceMapper
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillToInvoiceMapperTest {
    private val paidAt = LocalDateTime(2026, 8, 16, 19, 30)

    private val seller =
        InvoiceSeller(
            name = "Zum Goldenen Hirschen",
            vatId = "DE123456789",
            taxNumber = "12/345/67890",
            street = "Hauptstr. 1",
            zip = "10115",
            city = "Berlin",
            country = "DE",
        )

    private val buyer =
        InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun facts(
        lines: List<BillInvoiceLineFact> =
            listOf(
                BillInvoiceLineFact(1L, "Wiener Schnitzel", BigDecimal("23.8000"), BigDecimal("0.1900"), "V1"),
                BillInvoiceLineFact(2L, "Apfelschorle", BigDecimal("4.5000"), BigDecimal("0.1900"), "V1"),
            ),
        vatRows: List<BillInvoiceVatFact> =
            listOf(
                BillInvoiceVatFact(
                    BigDecimal("0.1900"),
                    BigDecimal("23.7815"),
                    BigDecimal("4.5185"),
                    BigDecimal("28.3000"),
                    vatId = "V1",
                ),
            ),
        billAmount: String = "28.3000",
    ) = BillInvoiceFacts(
        billId = 42L,
        billNumber = "RE-260816-7",
        billAmount = BigDecimal(billAmount),
        paidAt = paidAt,
        kind = BillKind.SALE,
        isCancelled = false,
        isTraining = false,
        hasLiveInvoice = false,
        lines = lines,
        vatRows = vatRows,
        tse = null,
        seller = seller,
        ownerTip = BigDecimal.ZERO,
        promotionAmount = BigDecimal.ZERO,
        promotions = emptyList(),
    )

    @Test
    fun `the VAT breakdown is copied verbatim and converted to percent units`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        assertEquals(1, content.vatRows.size)
        val row = content.vatRows.single()
        assertEquals(0, BigDecimal("19.00").compareTo(row.vatRatePercent), "0.19 stored must become 19.00")
        assertEquals(0, BigDecimal("23.7815").compareTo(row.taxableAmount), "copied, not recomputed")
        assertEquals(0, BigDecimal("4.5185").compareTo(row.vatAmount), "copied, not recomputed")
        Unit
    }

    @Test
    fun `each line derives VAT by subtraction so net plus vat is exactly the gross`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        for (line in content.lines) {
            assertEquals(
                0,
                line.netAmount.add(line.vatAmount).compareTo(line.grossAmount),
                "line ${line.lineNo}: net + vat must equal gross exactly",
            )
            assertEquals(0, BigDecimal("19.00").compareTo(line.vatRatePercent))
            assertEquals(0, BigDecimal.ONE.compareTo(line.quantity), "an order item row is one unit")
        }
        Unit
    }

    @Test
    fun `lines are numbered from one, in order`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        assertEquals(listOf(1, 2), content.lines.map { it.lineNo })
        assertEquals(listOf("Wiener Schnitzel", "Apfelschorle"), content.lines.map { it.itemName })
        Unit
    }

    @Test
    fun `the invoice total is the fiscal split's total, not the sum of derived line VAT`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        assertEquals(0, BigDecimal("28.3000").compareTo(content.taxInclusiveTotal))
        assertEquals(0, BigDecimal("4.5185").compareTo(content.taxTotal))
        assertEquals(0, BigDecimal("23.7815").compareTo(content.lineNetTotal))
        Unit
    }

    @Test
    fun `lines that do not sum to the fiscal split are refused rather than balanced`() {
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    facts(
                        lines =
                            listOf(
                                BillInvoiceLineFact(1L, "Nur ein Teil", BigDecimal("10.0000"), BigDecimal("0.1900"), "V1"),
                            ),
                    ),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH, error.code)
        Unit
    }

    @Test
    fun `a bill with no lines cannot be invoiced`() {
        val error =
            assertFailsWith<PosException> { BillToInvoiceMapper.toDraftContent(facts(lines = emptyList()), buyer) }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        Unit
    }

    @Test
    fun `the payment note names the Kassenbeleg and the payment date`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        assertEquals(
            "Betrag dankend erhalten am 16.08.2026, Beleg RE-260816-7",
            content.paymentNote,
            "the invoice must carry the receipt reference on its face",
        )
        Unit
    }

    @Test
    fun `a bill tendered wholly on account is not acknowledged as paid`() {
        val content = BillToInvoiceMapper.toDraftContent(facts().copy(receivableTender = BigDecimal("28.3000")), buyer)

        assertEquals(
            "Leistung gemäß Beleg RE-260816-7 vom 16.08.2026. Der Rechnungsbetrag von 28,30 € ist offen und zur Zahlung fällig.",
            content.paymentNote,
            "an open receivable must say it is due, never 'dankend erhalten'",
        )
        Unit
    }

    @Test
    fun `a split tender acknowledges only the paid part and states the open rest`() {
        val content = BillToInvoiceMapper.toDraftContent(facts().copy(receivableTender = BigDecimal("12.6000")), buyer)

        assertEquals(
            "Teilbetrag von 15,70 € dankend erhalten am 16.08.2026, Beleg RE-260816-7. " +
                "Der offene Betrag von 12,60 € ist zur Zahlung fällig.",
            content.paymentNote,
        )
        Unit
    }

    @Test
    fun `the service date is the bill's payment time, not today`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)

        assertEquals(paidAt, content.serviceDate)
        Unit
    }

    @Test
    fun `a zero-rated line gets net equal to gross and no VAT`() {
        val content =
            BillToInvoiceMapper.toDraftContent(
                facts(
                    lines = listOf(BillInvoiceLineFact(1L, "Gutschein", BigDecimal("10.0000"), BigDecimal.ZERO, "V3")),
                    vatRows =
                        listOf(
                            BillInvoiceVatFact(
                                BigDecimal.ZERO,
                                BigDecimal("10.0000"),
                                BigDecimal.ZERO,
                                BigDecimal("10.0000"),
                                vatId = "V3",
                            ),
                        ),
                    billAmount = "10.0000",
                ),
                buyer,
            )

        val line = content.lines.single()
        assertEquals(0, BigDecimal("10.0000").compareTo(line.netAmount))
        assertEquals(0, BigDecimal.ZERO.compareTo(line.vatAmount))
        assertEquals(0, BigDecimal.ZERO.compareTo(line.vatRatePercent))
        Unit
    }

    @Test
    fun `the TSE block is carried through, and null stays null`() {
        assertNull(BillToInvoiceMapper.toDraftContent(facts(), buyer).tse, "an unsigned bill has no block to carry")
        Unit
    }

    // ------------------------------------------------------------------------------------------
    // OWNER tip: taxable revenue on the Kassenbeleg, never part of the Ausgangsrechnung
    // ------------------------------------------------------------------------------------------

    /** A bill VAT row exactly as `BillServiceImpl.createBillVat` writes it for [total] at [rate]. */
    private fun billRow(
        rate: String,
        total: String,
        vatId: String,
    ): BillInvoiceVatFact {
        val gross = BigDecimal(total)
        val net = gross.divide(BigDecimal.ONE + BigDecimal(rate), 2, java.math.RoundingMode.HALF_UP)
        return BillInvoiceVatFact(BigDecimal(rate), net, gross - net, gross, vatId)
    }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual.toPlainString()}")

    @Test
    fun `an owner tip at one rate is taken out of the VAT row - the invoice is the meal`() {
        // Meal 28.30 at 19 % + owner tip 2.00 folded in = one bill row of 30.30.
        val content =
            BillToInvoiceMapper.toDraftContent(
                facts(vatRows = listOf(billRow("0.1900", "30.30", vatId = "V1"))).copy(ownerTip = BigDecimal("2.00")),
                buyer,
            )

        val row = content.vatRows.single()
        assertMoney("23.78", row.taxableAmount, "net = 28.30 / 1.19 HALF_UP")
        assertMoney("4.52", row.vatAmount, "VAT by subtraction: 28.30 - 23.78")
        assertMoney("28.30", content.taxInclusiveTotal, "the invoice is the meal, without the tip")
        assertMoney("23.78", content.lineNetTotal, "BT-109")
        assertMoney("4.52", content.taxTotal, "BT-110")
        assertMoney(
            content.taxInclusiveTotal.toPlainString(),
            content.lineNetTotal + content.taxTotal,
            "§14 totals are internally consistent",
        )
        Unit
    }

    @Test
    fun `an owner tip over 19 and 7 percent comes out of each row by the shared split`() {
        // Items: 10.00 @ 19 % (vat 1), 5.00 @ 7 % (vat 2). Owner tip 2.00, weights 10:5 ->
        // 1.33 / 0.67 (largest remainder), exactly what the bill folded into its rows.
        val content =
            BillToInvoiceMapper.toDraftContent(
                facts(
                    lines =
                        listOf(
                            BillInvoiceLineFact(1L, "Schnitzel", BigDecimal("10.0000"), BigDecimal("0.1900"), "V1"),
                            BillInvoiceLineFact(2L, "Brot", BigDecimal("5.0000"), BigDecimal("0.0700"), "V2"),
                        ),
                    vatRows = listOf(billRow("0.1900", "11.33", "V1"), billRow("0.0700", "5.67", "V2")),
                    billAmount = "15.0000",
                ).copy(ownerTip = BigDecimal("2.00")),
                buyer,
            )

        val byRate = content.vatRows.associateBy { it.vatRatePercent.toPlainString() }
        assertMoney("8.40", byRate.getValue("19.00").taxableAmount, "19 %: 10.00 / 1.19")
        assertMoney("1.60", byRate.getValue("19.00").vatAmount, "19 %: 10.00 - 8.40")
        assertMoney("4.67", byRate.getValue("7.00").taxableAmount, "7 %: 5.00 / 1.07")
        assertMoney("0.33", byRate.getValue("7.00").vatAmount, "7 %: 5.00 - 4.67")
        assertMoney("15.00", content.taxInclusiveTotal, "the meal")
        assertMoney("15.00", content.lineNetTotal + content.taxTotal, "net + VAT == gross")
        Unit
    }

    @Test
    fun `a bill whose rows do not hold the recomputed tip share is refused, naming the rate`() {
        // The tip sits wholly in the 19 % row; the shared split puts 0.67 of it at 7 %.
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    facts(
                        lines =
                            listOf(
                                BillInvoiceLineFact(1L, "Schnitzel", BigDecimal("10.0000"), BigDecimal("0.1900"), "V1"),
                                BillInvoiceLineFact(2L, "Brot", BigDecimal("5.0000"), BigDecimal("0.0700"), "V2"),
                            ),
                        vatRows = listOf(billRow("0.1900", "12.00", "V1"), billRow("0.0700", "5.00", "V2")),
                        billAmount = "15.0000",
                    ).copy(ownerTip = BigDecimal("2.00")),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE, error.code)
        assertTrue(error.message!!.contains("at 7 %"), error.message)
        Unit
    }

    @Test
    fun `a pre-apportionment bill whose owner tip is a zero-rate row is refused, not misinvoiced`() {
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    facts(
                        vatRows = listOf(billRow("0.1900", "28.30", "V1"), billRow("0", "2.00", "V9")),
                    ).copy(ownerTip = BigDecimal("2.00")),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE, error.code)
        Unit
    }

    // ---- Promotions as EN 16931 document-level allowances (BG-20) ----------------------------------

    private fun twoRateLines(
        at19: String = "10.0000",
        at7: String = "5.0000",
    ) = listOf(
        BillInvoiceLineFact(1L, "Schnitzel", BigDecimal(at19), BigDecimal("0.1900"), "V1"),
        BillInvoiceLineFact(2L, "Brot", BigDecimal(at7), BigDecimal("0.0700"), "V2"),
    )

    private fun promoted(
        lines: List<BillInvoiceLineFact>,
        vatRows: List<BillInvoiceVatFact>,
        promotions: List<BillInvoicePromotionFact>,
    ): BillInvoiceFacts {
        val items = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.grossAmount }
        return facts(lines = lines, vatRows = vatRows, billAmount = items.toPlainString()).copy(
            promotionAmount = promotions.fold(BigDecimal.ZERO) { acc, p -> acc + p.deductedAmount },
            promotions = promotions,
        )
    }

    private fun billWide(
        amount: String,
        id: Long = 7L,
        name: String = "10 % auf alles",
    ) = BillInvoicePromotionFact(id, name, BigDecimal(amount), orderItemId = null)

    @Test
    fun `a bill-wide promotion at one rate becomes one allowance - rows copied, lines at the price charged`() {
        // 23.80 + 4.50 = 28.30 at 19 %, 10 % off = 2.83 -> the bill signed one row of 25.47.
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(facts().lines, listOf(billRow("0.1900", "25.47", "V1")), listOf(billWide("2.83"))),
                buyer,
            )

        val row = content.vatRows.single()
        assertMoney("21.40", row.taxableAmount, "the bill row's net, copied")
        assertMoney("4.07", row.vatAmount, "the bill row's VAT, copied")
        assertMoney("28.30", content.lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.grossAmount }, "lines at finalPrice")
        val allowance = content.allowances.single()
        assertEquals("Aktion: 10 % auf alles", allowance.reason)
        assertEquals("95", allowance.reasonCode)
        assertEquals(7L, allowance.billPromotionId)
        assertMoney("2.83", allowance.grossAmount, "what the till took off")
        assertMoney("2.38", allowance.amount, "BT-92 net = net(28.30) 23.78 - row net 21.40")
        assertMoney("0.45", allowance.vatAmount, "VAT by subtraction")
        assertMoney("19.00", allowance.vatRatePercent, "BT-96 percent units")
        assertMoney("23.78", content.lineNetTotal, "BT-106 = BT-109 + BT-107")
        assertMoney("2.38", content.allowanceTotal!!, "BT-107")
        assertMoney("21.40", content.taxExclusiveTotal, "BT-109 = the rows' net")
        assertMoney("25.47", content.taxInclusiveTotal, "BT-112 = the bill")
        Unit
    }

    @Test
    fun `a bill-wide promotion over 19 and 7 percent is apportioned exactly as the bill signed it`() {
        // 10.00 @ 19 % and 5.00 @ 7 %, 1.50 off: largest remainder 1.00 / 0.50.
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(twoRateLines(), listOf(billRow("0.1900", "9.00", "V1"), billRow("0.0700", "4.50", "V2")), listOf(billWide("1.50"))),
                buyer,
            )

        val byRate = content.allowances.associateBy { it.vatRatePercent.toPlainString() }
        assertMoney("1.00", byRate.getValue("19.00").grossAmount, "19 % share")
        assertMoney("0.84", byRate.getValue("19.00").amount, "net(10.00) 8.40 - net(9.00) 7.56")
        assertMoney("0.50", byRate.getValue("7.00").grossAmount, "7 % share")
        assertMoney("0.46", byRate.getValue("7.00").amount, "net(5.00) 4.67 - net(4.50) 4.21")
        assertEquals(setOf("Aktion: 10 % auf alles"), content.allowances.map { it.reason }.toSet())
        assertMoney("13.50", content.taxInclusiveTotal, "the bill")
        assertMoney("1.30", content.allowanceTotal!!, "BT-107")
        Unit
    }

    @Test
    fun `an item-specific promotion is an allowance at its item's rate only`() {
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(
                    twoRateLines(),
                    listOf(billRow("0.1900", "10.00", "V1"), billRow("0.0700", "4.00", "V2")),
                    listOf(BillInvoicePromotionFact(9L, "Brot gratis-ish", BigDecimal("1.00"), orderItemId = 2L)),
                ),
                buyer,
            )

        val allowance = content.allowances.single()
        assertEquals("Aktion: Brot gratis-ish", allowance.reason)
        assertEquals(9L, allowance.billPromotionId)
        assertMoney("7.00", allowance.vatRatePercent, "the item's rate")
        assertMoney("0.93", allowance.amount, "net(5.00) 4.67 - net(4.00) 3.74")
        assertMoney("0.07", allowance.vatAmount, "1.00 - 0.93")
        assertMoney("14.00", content.taxInclusiveTotal, "the bill")
        Unit
    }

    @Test
    fun `several bill-wide promotions share one allowance per rate, naming them all`() {
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(
                    facts().lines,
                    listOf(billRow("0.1900", "25.30", "V1")),
                    listOf(billWide("2.00", id = 7L, name = "A"), billWide("1.00", id = 8L, name = "B")),
                ),
                buyer,
            )

        val allowance = content.allowances.single()
        assertEquals("Aktionen: A, B", allowance.reason)
        assertNull(allowance.billPromotionId, "no single bill_promotion row behind a shared allowance")
        assertMoney("3.00", allowance.grossAmount, "the bill apportioned their sum")
        Unit
    }

    @Test
    fun `an owner tip on a promoted bill is split by the post-promotion weights the bill used`() {
        // 10.00 @ 19 %, 10.00 @ 7 %; 5.00 off the 7 % item. Post-promotion 10.00 : 5.00, so the owner
        // tip of 3.00 went in as 2.00 / 1.00 — the line gross (10 : 10) would say 1.50 / 1.50.
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(
                    twoRateLines(at7 = "10.0000"),
                    listOf(billRow("0.1900", "12.00", "V1"), billRow("0.0700", "6.00", "V2")),
                    listOf(BillInvoicePromotionFact(9L, "Halber Preis", BigDecimal("5.00"), orderItemId = 2L)),
                ).copy(ownerTip = BigDecimal("3.00")),
                buyer,
            )

        val byRate = content.vatRows.associateBy { it.vatRatePercent.toPlainString() }
        assertMoney("8.40", byRate.getValue("19.00").taxableAmount, "19 % meal 10.00")
        assertMoney("4.67", byRate.getValue("7.00").taxableAmount, "7 % meal 5.00")
        assertMoney("15.00", content.taxInclusiveTotal, "the meal after the promotion, never the tip")
        val allowance = content.allowances.single()
        assertMoney("5.00", allowance.grossAmount, "the item promotion")
        assertMoney("4.68", allowance.amount, "net(10.00) 9.35 - meal net 4.67")
        Unit
    }

    @Test
    fun `a promotion on an item that is not on the bill is refused as not reconcilable`() {
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    promoted(
                        twoRateLines(),
                        listOf(billRow("0.1900", "10.00", "V1"), billRow("0.0700", "4.00", "V2")),
                        listOf(BillInvoicePromotionFact(9L, "Weg", BigDecimal("1.00"), orderItemId = 99L)),
                    ),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
        assertTrue(error.message!!.contains("item 99"), error.message)
        Unit
    }

    @Test
    fun `rows that do not match the stored promotions are refused, naming the rate`() {
        // The whole 1.50 sits in the 19 % row; the shared split puts 0.50 of it at 7 %.
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    promoted(twoRateLines(), listOf(billRow("0.1900", "8.50", "V1"), billRow("0.0700", "5.00", "V2")), listOf(billWide("1.50"))),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
        assertTrue(error.message!!.contains("at 7 %"), error.message)
        Unit
    }

    @Test
    fun `deductions that do not add up to the bill's promotion amount are refused`() {
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    promoted(twoRateLines(), listOf(billRow("0.1900", "9.00", "V1"), billRow("0.0700", "4.50", "V2")), listOf(billWide("1.50")))
                        .copy(promotionAmount = BigDecimal("2.00")),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
        Unit
    }

    @Test
    fun `a bill without a promotion has no allowance and no allowance total`() {
        val content = BillToInvoiceMapper.toDraftContent(facts(), buyer)
        assertTrue(content.allowances.isEmpty())
        assertNull(content.allowanceTotal, "no zero BT-107 on an invoice without a promotion")
        assertMoney(content.lineNetTotal.toPlainString(), content.taxExclusiveTotal, "BT-106 == BT-109")
        Unit
    }

    @Test
    fun `a key over-deducted below zero is refused - no negative BT-116`() {
        // Two item-specific promotions of 8.00 on one 10.00 item: the bill signed a -6.00 row at 19 %.
        val error =
            assertFailsWith<PosException> {
                BillToInvoiceMapper.toDraftContent(
                    promoted(
                        twoRateLines(at7 = "20.0000"),
                        listOf(billRow("0.1900", "-6.00", "V1"), billRow("0.0700", "20.00", "V2")),
                        listOf(
                            BillInvoicePromotionFact(9L, "A", BigDecimal("8.00"), orderItemId = 1L),
                            BillInvoicePromotionFact(10L, "B", BigDecimal("8.00"), orderItemId = 1L),
                        ),
                    ),
                    buyer,
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
        assertTrue(error.message!!.contains("at 19 %") && error.message!!.contains("negative"), error.message)
        Unit
    }

    @Test
    fun `a bill-wide promotion that deducted nothing is not named in the allowance`() {
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(
                    facts().lines,
                    listOf(billRow("0.1900", "26.30", "V1")),
                    listOf(billWide("2.00", id = 7L, name = "A"), billWide("0.00", id = 8L, name = "B")),
                ),
                buyer,
            )

        val allowance = content.allowances.single()
        assertEquals("Aktion: A", allowance.reason)
        assertEquals(7L, allowance.billPromotionId, "the one promotion that deducted is the source")
        Unit
    }

    @Test
    fun `a capped bill-wide promotion that took the whole bill reconciles to a zero invoice`() {
        // 23.00 at 19 %, a legacy 150 % promotion capped to 23.00: the bill signed a 0.00 row.
        val content =
            BillToInvoiceMapper.toDraftContent(
                promoted(
                    listOf(BillInvoiceLineFact(1L, "Menü", BigDecimal("23.0000"), BigDecimal("0.1900"), "V1")),
                    listOf(billRow("0.1900", "0.00", "V1")),
                    listOf(billWide("23.00")),
                ),
                buyer,
            )

        val allowance = content.allowances.single()
        assertMoney("23.00", allowance.grossAmount, "the whole line")
        assertMoney("19.33", allowance.amount, "= the line net 23.00 / 1.19")
        assertMoney("3.67", allowance.vatAmount, "VAT by subtraction")
        assertMoney("0", content.taxInclusiveTotal, "BT-112")
        assertMoney("0", content.taxExclusiveTotal, "BT-109")
        assertMoney("19.33", content.lineNetTotal, "BT-106 = the allowance")
        Unit
    }
}
