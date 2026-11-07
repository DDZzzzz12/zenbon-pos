package org.example.project.einvoice.syntax

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.securexml.SecureXml
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The writer is proven the exact inverse of the trusted [CiiInvoiceMapper]: a document it writes must
 * parse back to the source [EInvoice] value-for-value, scale included. That round-trip is the
 * correctness oracle — it exercises paths, namespaces and the number/date lexical forms all at once.
 * The golden test additionally pins the serialised bytes so an accidental structural change is loud.
 */
class CiiInvoiceWriterTest {
    private val sample =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = "RG-2026-00001",
            issueDate = LocalDate(2026, 8, 18),
            currency = "EUR",
            seller =
                EInvoiceParty(
                    name = "Muster GmbH",
                    vatId = "DE123456789",
                    taxNumber = "12/345/67890",
                    street = "Musterstraße 1",
                    zip = "10115",
                    city = "Berlin",
                    country = "DE",
                ),
            buyer =
                EInvoiceParty(
                    name = "Kanzlei Maas",
                    vatId = "DE999999999",
                    street = "Anwaltsweg 5",
                    zip = "80331",
                    city = "München",
                    country = "DE",
                ),
            vatBreakdown =
                listOf(
                    EInvoiceVatBreakdown(
                        categoryCode = "S",
                        rate = BigDecimal("19.00"),
                        taxableAmount = BigDecimal("100.00"),
                        vatAmount = BigDecimal("19.00"),
                    ),
                ),
            lines =
                listOf(
                    EInvoiceLine(
                        lineId = "1",
                        itemName = "Beratung",
                        quantity = BigDecimal("1"),
                        unitCode = "HUR",
                        netUnitPrice = BigDecimal("100.00"),
                        lineNetAmount = BigDecimal("100.00"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("19.00"),
                    ),
                ),
            deliveryDate = LocalDate(2026, 8, 18),
            invoiceTypeCode = "380",
            lineNetTotal = BigDecimal("100.00"),
            taxExclusiveTotal = BigDecimal("100.00"),
            taxTotal = BigDecimal("19.00"),
            taxInclusiveTotal = BigDecimal("119.00"),
            amountDue = BigDecimal("119.00"),
            prepaidAmount = BigDecimal("0.00"),
        )

    @Test
    fun `written CII parses back to the source EInvoice`() {
        val xml = CiiInvoiceWriter.write(sample)
        val parsed = CiiInvoiceMapper.map(SecureXml.parse(xml))
        assertEquals(sample.invoiceNumber, parsed.invoiceNumber)
        assertEquals(sample.issueDate, parsed.issueDate)
        assertEquals(sample.currency, parsed.currency)
        assertEquals(sample.seller, parsed.seller)
        assertEquals(sample.buyer, parsed.buyer)
        assertEquals(sample.lines, parsed.lines)
        assertEquals(sample.vatBreakdown, parsed.vatBreakdown)
        assertEquals(sample.deliveryDate, parsed.deliveryDate)
        assertEquals(sample.lineNetTotal, parsed.lineNetTotal)
        assertEquals(sample.taxExclusiveTotal, parsed.taxExclusiveTotal)
        assertEquals(sample.taxTotal, parsed.taxTotal)
        assertEquals(sample.taxInclusiveTotal, parsed.taxInclusiveTotal)
        assertEquals(sample.prepaidAmount, parsed.prepaidAmount)
        assertEquals(sample.amountDue, parsed.amountDue)
        assertEquals(sample.invoiceTypeCode, parsed.invoiceTypeCode)
        assertNull(EInvoiceInvariants.check(parsed))
    }

    /** The stored `DECIMAL(19,4)` form of the same, cent-exact values — what the issue path hands in. */
    private val storedScale =
        sample.copy(
            vatBreakdown = sample.vatBreakdown.map { it.copy(taxableAmount = BigDecimal("100.0000"), vatAmount = BigDecimal("19.0000")) },
            lines = sample.lines.map { it.copy(netUnitPrice = BigDecimal("100.0000"), lineNetAmount = BigDecimal("100.0000")) },
            lineNetTotal = BigDecimal("100.0000"),
            taxExclusiveTotal = BigDecimal("100.0000"),
            taxTotal = BigDecimal("19.0000"),
            taxInclusiveTotal = BigDecimal("119.0000"),
            amountDue = BigDecimal("119.0000"),
            prepaidAmount = BigDecimal("0.0000"),
        )

    @Test
    fun `four-decimal stored amounts are written at the two decimals BR-DEC allows`() {
        val xml = String(CiiInvoiceWriter.write(storedScale), Charsets.UTF_8)

        listOf(
            "<ram:LineTotalAmount>100.00</ram:LineTotalAmount>",
            "<ram:CalculatedAmount>19.00</ram:CalculatedAmount>",
            "<ram:BasisAmount>100.00</ram:BasisAmount>",
            "<ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>",
            "<ram:TaxTotalAmount currencyID=\"EUR\">19.00</ram:TaxTotalAmount>",
            "<ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>",
            "<ram:TotalPrepaidAmount>0.00</ram:TotalPrepaidAmount>",
            "<ram:DuePayableAmount>119.00</ram:DuePayableAmount>",
        ).forEach { assertTrue(it in xml, "missing $it") }
        val moneyElements = "LineTotal|Calculated|Basis|TaxBasisTotal|TaxTotal|GrandTotal|TotalPrepaid|DuePayable"
        assertFalse(Regex(">-?\\d+\\.\\d{3,}</ram:($moneyElements)Amount>").containsMatchIn(xml))
        // BT-146 (the unit price) is exempt from BR-DEC and keeps the scale it was given.
        assertTrue("<ram:ChargeAmount>100.0000</ram:ChargeAmount>" in xml)
    }

    @Test
    fun `the two-decimal document parses back through the project's own parser to the same values`() {
        val parsed = CiiInvoiceMapper.map(SecureXml.parse(CiiInvoiceWriter.write(storedScale)))

        assertEquals(0, storedScale.taxInclusiveTotal!!.compareTo(parsed.taxInclusiveTotal))
        assertEquals(0, storedScale.taxTotal!!.compareTo(parsed.taxTotal))
        assertEquals(0, storedScale.lineNetTotal!!.compareTo(parsed.lineNetTotal))
        assertEquals(0, storedScale.amountDue!!.compareTo(parsed.amountDue))
        assertEquals(
            0,
            storedScale.lines
                .single()
                .lineNetAmount
                .compareTo(parsed.lines.single().lineNetAmount),
        )
        assertEquals("DE", parsed.seller.country)
        assertNull(EInvoiceInvariants.check(parsed))
    }

    @Test
    fun `a sub-cent monetary amount is refused rather than rounded`() {
        val subCent = sample.copy(lines = sample.lines.map { it.copy(lineNetAmount = BigDecimal("8.4034")) })
        val error = assertFailsWith<IllegalArgumentException> { CiiInvoiceWriter.write(subCent) }
        assertTrue("BT-131" in error.message.orEmpty())
    }

    @Test
    fun `a free-text country is refused - CountryID must be ISO 3166-1 alpha-2`() {
        val freeText = sample.copy(seller = sample.seller.copy(country = "Germany"))
        val error = assertFailsWith<IllegalArgumentException> { CiiInvoiceWriter.write(freeText) }
        assertTrue("Germany" in error.message.orEmpty())
    }

    /** 0.08 € at 7 %: BR-CO-17 VAT 0.00 on 0.07, so BT-112 is 0.07 and BT-114 gives the cent back. */
    private val withRounding =
        sample.copy(
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal("7.00"), BigDecimal("0.07"), BigDecimal("0.00"))),
            lines = sample.lines.map { it.copy(netUnitPrice = BigDecimal("0.07"), lineNetAmount = BigDecimal("0.07"), vatRate = BigDecimal("7.00")) },
            lineNetTotal = BigDecimal("0.07"),
            taxExclusiveTotal = BigDecimal("0.07"),
            taxTotal = BigDecimal("0.00"),
            roundingAmount = BigDecimal("0.01"),
            taxInclusiveTotal = BigDecimal("0.07"),
            prepaidAmount = BigDecimal("0.00"),
            amountDue = BigDecimal("0.08"),
        )

    @Test
    fun `BT-114 is written between the tax total and the grand total and parses back`() {
        val xml = String(CiiInvoiceWriter.write(withRounding), Charsets.UTF_8)
        assertTrue(
            Regex("</ram:TaxTotalAmount>\\s*<ram:RoundingAmount>0.01</ram:RoundingAmount>\\s*<ram:GrandTotalAmount>").containsMatchIn(xml),
            "CII sequence: TaxTotalAmount, RoundingAmount, GrandTotalAmount:\n$xml",
        )
        val parsed = CiiInvoiceMapper.map(SecureXml.parse(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(BigDecimal("0.01"), parsed.roundingAmount)
        assertEquals(BigDecimal("0.08"), parsed.amountDue)
        assertNull(
            org.example.project.einvoice.check.En16931AmountRules
                .check(parsed),
        )
    }

    @Test
    fun `no rounding amount writes no RoundingAmount element`() {
        assertFalse("RoundingAmount" in String(CiiInvoiceWriter.write(sample), Charsets.UTF_8))
        assertNull(CiiInvoiceMapper.map(SecureXml.parse(CiiInvoiceWriter.write(sample))).roundingAmount)
    }

    /** 100.00 at 19 % less a 10.00 net promotion allowance (BG-20): taxable 90.00, VAT 17.10. */
    private val withAllowance =
        sample.copy(
            vatBreakdown = listOf(EInvoiceVatBreakdown("S", BigDecimal("19.00"), BigDecimal("90.00"), BigDecimal("17.10"))),
            allowancesCharges =
                listOf(
                    EInvoiceAllowanceCharge(
                        isCharge = false,
                        amount = BigDecimal("10.00"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("19.00"),
                        reasonCode = "95",
                        reasonText = "Aktion: Happy Hour",
                    ),
                ),
            lineNetTotal = BigDecimal("100.00"),
            allowanceTotal = BigDecimal("10.00"),
            taxExclusiveTotal = BigDecimal("90.00"),
            taxTotal = BigDecimal("17.10"),
            taxInclusiveTotal = BigDecimal("107.10"),
            amountDue = BigDecimal("107.10"),
        )

    @Test
    fun `a document-level allowance and BT-107 round-trip through the parser`() {
        val parsed = CiiInvoiceMapper.map(SecureXml.parse(CiiInvoiceWriter.write(withAllowance)))

        assertEquals(withAllowance.allowancesCharges, parsed.allowancesCharges)
        assertEquals(BigDecimal("10.00"), parsed.allowanceTotal)
        assertNull(parsed.chargeTotal, "no charge, no ChargeTotalAmount")
        assertEquals(BigDecimal("90.00"), parsed.taxExclusiveTotal)
        assertEquals(BigDecimal("100.00"), parsed.lineNetTotal)
        assertNull(EInvoiceInvariants.check(parsed))
        assertNull(
            org.example.project.einvoice.check.En16931AmountRules
                .check(parsed),
        )
    }

    @Test
    fun `the allowance sits after the VAT groups and BT-107 between the line and the tax-basis totals`() {
        val xml = String(CiiInvoiceWriter.write(withAllowance), Charsets.UTF_8)

        assertTrue(
            Regex("</ram:ApplicableTradeTax>\\s*<ram:SpecifiedTradeAllowanceCharge>").containsMatchIn(xml),
            "CII sequence: ApplicableTradeTax, then SpecifiedTradeAllowanceCharge:\n$xml",
        )
        assertTrue(
            Regex(
                "<ram:SpecifiedTradeAllowanceCharge>\\s*<ram:ChargeIndicator>\\s*<udt:Indicator>false</udt:Indicator>\\s*</ram:ChargeIndicator>" +
                    "\\s*<ram:ActualAmount>10.00</ram:ActualAmount>\\s*<ram:ReasonCode>95</ram:ReasonCode>" +
                    "\\s*<ram:Reason>Aktion: Happy Hour</ram:Reason>\\s*<ram:CategoryTradeTax>\\s*<ram:TypeCode>VAT</ram:TypeCode>" +
                    "\\s*<ram:CategoryCode>S</ram:CategoryCode>\\s*<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>",
            ).containsMatchIn(xml),
            "TradeAllowanceChargeType sequence:\n$xml",
        )
        assertTrue(
            Regex("</ram:LineTotalAmount>\\s*<ram:AllowanceTotalAmount>10.00</ram:AllowanceTotalAmount>\\s*<ram:TaxBasisTotalAmount>")
                .containsMatchIn(xml),
            "CII sequence: LineTotalAmount, AllowanceTotalAmount, TaxBasisTotalAmount:\n$xml",
        )
    }

    @Test
    fun `a sub-cent allowance amount is refused rather than rounded`() {
        val subCent = withAllowance.copy(allowancesCharges = withAllowance.allowancesCharges.map { it.copy(amount = BigDecimal("10.0001")) })
        val error = assertFailsWith<IllegalArgumentException> { CiiInvoiceWriter.write(subCent) }
        assertTrue("BT-92" in error.message.orEmpty())
    }

    @Test
    fun `an invoice without allowances writes no allowance element`() {
        assertFalse("Allowance" in String(CiiInvoiceWriter.write(sample), Charsets.UTF_8))
    }

    @Test
    fun `golden CII is byte-stable`() {
        assertEquals(GOLDEN, String(CiiInvoiceWriter.write(sample), Charsets.UTF_8))
    }

    private companion object {
        // Serialised with a leading XML declaration and a trailing newline; trimIndent() drops the
        // final blank line, so the newline is re-appended to match the serialiser byte-for-byte.
        val GOLDEN =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100" xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100" xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
                <rsm:ExchangedDocumentContext>
                    <ram:GuidelineSpecifiedDocumentContextParameter>
                        <ram:ID>urn:cen.eu:en16931:2017</ram:ID>
                    </ram:GuidelineSpecifiedDocumentContextParameter>
                </rsm:ExchangedDocumentContext>
                <rsm:ExchangedDocument>
                    <ram:ID>RG-2026-00001</ram:ID>
                    <ram:TypeCode>380</ram:TypeCode>
                    <ram:IssueDateTime>
                        <udt:DateTimeString format="102">20260818</udt:DateTimeString>
                    </ram:IssueDateTime>
                </rsm:ExchangedDocument>
                <rsm:SupplyChainTradeTransaction>
                    <ram:IncludedSupplyChainTradeLineItem>
                        <ram:AssociatedDocumentLineDocument>
                            <ram:LineID>1</ram:LineID>
                        </ram:AssociatedDocumentLineDocument>
                        <ram:SpecifiedTradeProduct>
                            <ram:Name>Beratung</ram:Name>
                        </ram:SpecifiedTradeProduct>
                        <ram:SpecifiedLineTradeAgreement>
                            <ram:NetPriceProductTradePrice>
                                <ram:ChargeAmount>100.00</ram:ChargeAmount>
                            </ram:NetPriceProductTradePrice>
                        </ram:SpecifiedLineTradeAgreement>
                        <ram:SpecifiedLineTradeDelivery>
                            <ram:BilledQuantity unitCode="HUR">1</ram:BilledQuantity>
                        </ram:SpecifiedLineTradeDelivery>
                        <ram:SpecifiedLineTradeSettlement>
                            <ram:ApplicableTradeTax>
                                <ram:TypeCode>VAT</ram:TypeCode>
                                <ram:CategoryCode>S</ram:CategoryCode>
                                <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>
                            </ram:ApplicableTradeTax>
                            <ram:SpecifiedTradeSettlementLineMonetarySummation>
                                <ram:LineTotalAmount>100.00</ram:LineTotalAmount>
                            </ram:SpecifiedTradeSettlementLineMonetarySummation>
                        </ram:SpecifiedLineTradeSettlement>
                    </ram:IncludedSupplyChainTradeLineItem>
                    <ram:ApplicableHeaderTradeAgreement>
                        <ram:SellerTradeParty>
                            <ram:Name>Muster GmbH</ram:Name>
                            <ram:PostalTradeAddress>
                                <ram:PostcodeCode>10115</ram:PostcodeCode>
                                <ram:LineOne>Musterstraße 1</ram:LineOne>
                                <ram:CityName>Berlin</ram:CityName>
                                <ram:CountryID>DE</ram:CountryID>
                            </ram:PostalTradeAddress>
                            <ram:SpecifiedTaxRegistration>
                                <ram:ID schemeID="VA">DE123456789</ram:ID>
                            </ram:SpecifiedTaxRegistration>
                            <ram:SpecifiedTaxRegistration>
                                <ram:ID schemeID="FC">12/345/67890</ram:ID>
                            </ram:SpecifiedTaxRegistration>
                        </ram:SellerTradeParty>
                        <ram:BuyerTradeParty>
                            <ram:Name>Kanzlei Maas</ram:Name>
                            <ram:PostalTradeAddress>
                                <ram:PostcodeCode>80331</ram:PostcodeCode>
                                <ram:LineOne>Anwaltsweg 5</ram:LineOne>
                                <ram:CityName>München</ram:CityName>
                                <ram:CountryID>DE</ram:CountryID>
                            </ram:PostalTradeAddress>
                            <ram:SpecifiedTaxRegistration>
                                <ram:ID schemeID="VA">DE999999999</ram:ID>
                            </ram:SpecifiedTaxRegistration>
                        </ram:BuyerTradeParty>
                    </ram:ApplicableHeaderTradeAgreement>
                    <ram:ApplicableHeaderTradeDelivery>
                        <ram:ActualDeliverySupplyChainEvent>
                            <ram:OccurrenceDateTime>
                                <udt:DateTimeString format="102">20260818</udt:DateTimeString>
                            </ram:OccurrenceDateTime>
                        </ram:ActualDeliverySupplyChainEvent>
                    </ram:ApplicableHeaderTradeDelivery>
                    <ram:ApplicableHeaderTradeSettlement>
                        <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
                        <ram:ApplicableTradeTax>
                            <ram:CalculatedAmount>19.00</ram:CalculatedAmount>
                            <ram:TypeCode>VAT</ram:TypeCode>
                            <ram:BasisAmount>100.00</ram:BasisAmount>
                            <ram:CategoryCode>S</ram:CategoryCode>
                            <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>
                        </ram:ApplicableTradeTax>
                        <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                            <ram:LineTotalAmount>100.00</ram:LineTotalAmount>
                            <ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>
                            <ram:TaxTotalAmount currencyID="EUR">19.00</ram:TaxTotalAmount>
                            <ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>
                            <ram:TotalPrepaidAmount>0.00</ram:TotalPrepaidAmount>
                            <ram:DuePayableAmount>119.00</ram:DuePayableAmount>
                        </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                    </ram:ApplicableHeaderTradeSettlement>
                </rsm:SupplyChainTradeTransaction>
            </rsm:CrossIndustryInvoice>
            """.trimIndent() + "\n"
    }
}
