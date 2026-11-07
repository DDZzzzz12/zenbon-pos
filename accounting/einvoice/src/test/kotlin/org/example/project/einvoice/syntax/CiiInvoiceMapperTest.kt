package org.example.project.einvoice.syntax

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.securexml.SecureXml
import org.example.project.securexml.XmlValueException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asserts against the real KoSIT XRechnung 3.0 CII instance in `corpus/`, never against invented
 * values. Amounts are compared with [BigDecimal.compareTo] where the sender's scale is incidental
 * (the fixture writes `336.9`, not `336.90`) and with [assertEquals] where the preserved scale is
 * itself the thing under test.
 */
class CiiInvoiceMapperTest {
    private fun corpusDoc(name: String) =
        SecureXml.parse(
            checkNotNull(javaClass.getResourceAsStream("/corpus/$name")).use { it.readBytes() },
        )

    private val invoice = CiiInvoiceMapper.map(corpusDoc("xrechnung-cii.xml"))

    @Test
    fun `maps the document header`() {
        assertEquals("123456XX", invoice.invoiceNumber)
        assertEquals(LocalDate.parse("2016-04-04"), invoice.issueDate)
        assertEquals("EUR", invoice.currency)
        assertEquals("04011000-12345-03", invoice.buyerReference)
        // BT-3: the fixture is an ordinary commercial invoice, UNTDID 1001 code 380.
        assertEquals("380", invoice.invoiceTypeCode)
        // Provisional until the profile-detection task overrides it from the guideline URN.
        assertEquals(EInvoiceFormat.ZUGFERD, invoice.format)
    }

    @Test
    fun `maps the seller`() {
        val seller = invoice.seller
        assertEquals("[Seller name]", seller.name)
        // Carried verbatim, embedded space and all — cleaning it would misreport what was sent.
        assertEquals("DE 123456789", seller.vatId)
        // This fixture declares only a schemeID='VA' registration, so no Steuernummer is present.
        assertNull(seller.taxNumber)
        assertEquals("[Seller address line 1]", seller.street)
        assertEquals("12345", seller.zip)
        assertEquals("[Seller city]", seller.city)
        assertEquals("DE", seller.country)
        assertEquals("seller@email.de", seller.email)
        assertEquals("+49 1234-5678", seller.phone)
    }

    @Test
    fun `maps every VAT breakdown group`() {
        assertEquals(1, invoice.vatBreakdown.size)
        val group = invoice.vatBreakdown.single()
        assertEquals("S", group.categoryCode)
        assertEquals(0, group.rate.compareTo(BigDecimal("7")))
        assertEquals(0, group.taxableAmount.compareTo(BigDecimal("314.86")))
        assertEquals(0, group.vatAmount.compareTo(BigDecimal("22.04")))
        assertNull(group.exemptionReasonCode)
        assertNull(group.exemptionReasonText)
    }

    @Test
    fun `does not mistake a line-level trade tax for a header VAT group`() {
        // The document carries three ram:ApplicableTradeTax elements: one per line plus one header
        // group. Only the header one has amounts, so an unqualified //ram:ApplicableTradeTax would
        // produce three groups, two of them with no taxable or VAT amount at all.
        val xml = corpusDoc("xrechnung-cii.xml")
        val allTradeTaxes = xml.getElementsByTagNameNS("*", "ApplicableTradeTax").length
        assertEquals(3, allTradeTaxes)
        assertEquals(1, invoice.vatBreakdown.size)
    }

    @Test
    fun `maps invoice lines with their unit code`() {
        assertEquals(2, invoice.lines.size)

        val first = invoice.lines[0]
        // ram:LineID is free text in this instance, not a numeric position.
        assertEquals("Zeitschrift [...]", first.lineId)
        assertEquals("Zeitschrift [...]", first.itemName)
        assertEquals("Zeitschrift Inland", first.itemDescription)
        assertEquals("246", first.sellerItemId)
        assertEquals("0721-880X", first.itemStandardId)
        assertEquals("XPP", first.unitCode)
        assertEquals(0, first.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, first.netUnitPrice.compareTo(BigDecimal("288.79")))
        assertEquals(0, first.lineNetAmount.compareTo(BigDecimal("288.79")))
        assertEquals("S", first.vatCategoryCode)
        assertEquals(0, first.vatRate.compareTo(BigDecimal("7")))

        val second = invoice.lines[1]
        assertEquals("Porto + Versandkosten", second.lineId)
        assertEquals("Porto + Versandkosten", second.itemName)
        assertNull(second.itemDescription)
        assertNull(second.sellerItemId)
        assertNull(second.itemStandardId)
        assertEquals("XPP", second.unitCode)
        assertEquals(0, second.netUnitPrice.compareTo(BigDecimal("26.07")))
        assertEquals(0, second.lineNetAmount.compareTo(BigDecimal("26.07")))
    }

    @Test
    fun `maps the buyer-assigned item id`() {
        // The corpus instance states no ram:BuyerAssignedID, so without this the path would be the
        // one mapped field no test could tell apart from a typo silently yielding null.
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        line =
                            """
                            <ram:IncludedSupplyChainTradeLineItem>
                                <ram:AssociatedDocumentLineDocument><ram:LineID>1</ram:LineID></ram:AssociatedDocumentLineDocument>
                                <ram:SpecifiedTradeProduct>
                                    <ram:Name>Ware</ram:Name>
                                    <ram:BuyerAssignedID>B-42</ram:BuyerAssignedID>
                                </ram:SpecifiedTradeProduct>
                                <ram:SpecifiedLineTradeAgreement>
                                    <ram:NetPriceProductTradePrice><ram:ChargeAmount>10.0000</ram:ChargeAmount></ram:NetPriceProductTradePrice>
                                </ram:SpecifiedLineTradeAgreement>
                                <ram:SpecifiedLineTradeDelivery>
                                    <ram:BilledQuantity unitCode="C62">2</ram:BilledQuantity>
                                </ram:SpecifiedLineTradeDelivery>
                                <ram:SpecifiedLineTradeSettlement>
                                    <ram:ApplicableTradeTax>
                                        <ram:CategoryCode>S</ram:CategoryCode>
                                        <ram:RateApplicablePercent>19</ram:RateApplicablePercent>
                                    </ram:ApplicableTradeTax>
                                    <ram:SpecifiedTradeSettlementLineMonetarySummation>
                                        <ram:LineTotalAmount>20.00</ram:LineTotalAmount>
                                    </ram:SpecifiedTradeSettlementLineMonetarySummation>
                                </ram:SpecifiedLineTradeSettlement>
                            </ram:IncludedSupplyChainTradeLineItem>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        val mappedLine = mapped.lines.single()
        assertEquals("B-42", mappedLine.buyerItemId)
        assertEquals("C62", mappedLine.unitCode)
        // BT-146 routinely carries four decimals; the sender's scale survives the mapping.
        assertEquals(BigDecimal("10.0000"), mappedLine.netUnitPrice)
    }

    @Test
    fun `maps the payment terms and settlement details`() {
        assertEquals("DE79000000001234567890", invoice.paymentIban)
        assertEquals("Zahlbar sofort ohne Abzug.", invoice.paymentTermsText)
        // The fixture states no due date and no BT-83 remittance reference.
        assertNull(invoice.dueDate)
        assertNull(invoice.paymentReference)
        // ram:ApplicableHeaderTradeDelivery is present but empty in this instance.
        assertNull(invoice.deliveryDate)
    }

    @Test
    fun `maps the document totals preserving the senders scale`() {
        assertEquals(BigDecimal("314.86"), invoice.lineNetTotal)
        assertEquals(BigDecimal("314.86"), invoice.taxExclusiveTotal)
        assertEquals(BigDecimal("22.04"), invoice.taxTotal)
        // Not zero-padded in the real file.
        assertEquals(BigDecimal("336.9"), invoice.taxInclusiveTotal)
        assertEquals(BigDecimal("336.9"), invoice.amountDue)
    }

    @Test
    fun `maps no document allowances or charges when the invoice declares none`() {
        assertTrue(invoice.allowancesCharges.isEmpty())
    }

    @Test
    fun `maps a document allowance and a document charge`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        settlementExtra =
                            """
                            <ram:SpecifiedTradeAllowanceCharge>
                                <ram:ChargeIndicator><udt:Indicator>false</udt:Indicator></ram:ChargeIndicator>
                                <ram:CalculationPercent>10</ram:CalculationPercent>
                                <ram:BasisAmount>100.00</ram:BasisAmount>
                                <ram:ActualAmount>10.00</ram:ActualAmount>
                                <ram:ReasonCode>95</ram:ReasonCode>
                                <ram:Reason>Rabatt</ram:Reason>
                                <ram:CategoryTradeTax>
                                    <ram:TypeCode>VAT</ram:TypeCode>
                                    <ram:CategoryCode>S</ram:CategoryCode>
                                    <ram:RateApplicablePercent>19</ram:RateApplicablePercent>
                                </ram:CategoryTradeTax>
                            </ram:SpecifiedTradeAllowanceCharge>
                            <ram:SpecifiedTradeAllowanceCharge>
                                <ram:ChargeIndicator><udt:Indicator>true</udt:Indicator></ram:ChargeIndicator>
                                <ram:ActualAmount>5.50</ram:ActualAmount>
                                <ram:ReasonCode>ABK</ram:ReasonCode>
                                <ram:Reason>Verpackung</ram:Reason>
                                <ram:CategoryTradeTax>
                                    <ram:TypeCode>VAT</ram:TypeCode>
                                    <ram:CategoryCode>S</ram:CategoryCode>
                                    <ram:RateApplicablePercent>19</ram:RateApplicablePercent>
                                </ram:CategoryTradeTax>
                            </ram:SpecifiedTradeAllowanceCharge>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )

        assertEquals(2, mapped.allowancesCharges.size)
        val allowance = mapped.allowancesCharges[0]
        assertEquals(false, allowance.isCharge)
        assertEquals(0, allowance.amount.compareTo(BigDecimal("10.00")))
        assertEquals(0, checkNotNull(allowance.baseAmount).compareTo(BigDecimal("100.00")))
        assertEquals(0, checkNotNull(allowance.percentage).compareTo(BigDecimal("10")))
        assertEquals("95", allowance.reasonCode)
        assertEquals("Rabatt", allowance.reasonText)
        assertEquals("S", allowance.vatCategoryCode)
        assertEquals(0, allowance.vatRate.compareTo(BigDecimal("19")))

        val charge = mapped.allowancesCharges[1]
        assertEquals(true, charge.isCharge)
        assertEquals(0, charge.amount.compareTo(BigDecimal("5.50")))
        assertNull(charge.baseAmount)
        assertNull(charge.percentage)
        assertEquals("ABK", charge.reasonCode)
    }

    @Test
    fun `maps the VAT exemption reason when one is stated`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        taxExtra =
                            """
                            <ram:ExemptionReason>Steuerschuldnerschaft des Leistungsempfaengers</ram:ExemptionReason>
                            <ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        val group = mapped.vatBreakdown.single()
        assertEquals("VATEX-EU-AE", group.exemptionReasonCode)
        assertEquals("Steuerschuldnerschaft des Leistungsempfaengers", group.exemptionReasonText)
    }

    @Test
    fun `maps a due date`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        settlementExtra =
                            """
                            <ram:SpecifiedTradePaymentTerms>
                                <ram:DueDateDateTime>
                                    <udt:DateTimeString format="102">20241231</udt:DateTimeString>
                                </ram:DueDateDateTime>
                            </ram:SpecifiedTradePaymentTerms>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals(LocalDate.parse("2024-12-31"), mapped.dueDate)
    }

    @Test
    fun `maps a delivery date`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        delivery =
                            """
                            <ram:ApplicableHeaderTradeDelivery>
                                <ram:ActualDeliverySupplyChainEvent>
                                    <ram:OccurrenceDateTime>
                                        <udt:DateTimeString format="102">20240115</udt:DateTimeString>
                                    </ram:OccurrenceDateTime>
                                </ram:ActualDeliverySupplyChainEvent>
                            </ram:ApplicableHeaderTradeDelivery>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals(LocalDate.parse("2024-01-15"), mapped.deliveryDate)
    }

    @Test
    fun `prefers the schemeID VA registration over the Steuernummer`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        sellerExtra =
                            """
                            <ram:SpecifiedTaxRegistration><ram:ID schemeID="FC">123/456/7890</ram:ID></ram:SpecifiedTaxRegistration>
                            <ram:SpecifiedTaxRegistration><ram:ID schemeID="VA">DE123456789</ram:ID></ram:SpecifiedTaxRegistration>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("DE123456789", mapped.seller.vatId)
        assertEquals("123/456/7890", mapped.seller.taxNumber)
    }

    @Test
    fun `reads an omitted rate on a reverse-charge group as zero`() {
        val mapped =
            CiiInvoiceMapper.map(SecureXml.parse(cii(taxCategory = "AE", taxRate = "").toByteArray()))
        assertEquals(
            0,
            mapped.vatBreakdown
                .single()
                .rate
                .compareTo(BigDecimal.ZERO),
        )
    }

    @Test
    fun `refuses an omitted rate on a standard-rated group instead of reading it as zero percent`() {
        assertFailsWith<EInvoiceMappingException> {
            CiiInvoiceMapper.map(SecureXml.parse(cii(taxRate = "").toByteArray()))
        }
    }

    @Test
    fun `rejects an invoice without an issue date rather than inventing one`() {
        val e =
            assertFailsWith<EInvoiceMappingException> {
                CiiInvoiceMapper.map(SecureXml.parse(cii(issueDate = "").toByteArray()))
            }
        assertTrue(e.message!!.contains("IssueDateTime"), e.message)
    }

    @Test
    fun `rejects an invoice without an invoice number`() {
        assertFailsWith<EInvoiceMappingException> {
            CiiInvoiceMapper.map(SecureXml.parse(cii(invoiceNumber = "").toByteArray()))
        }
    }

    @Test
    fun `rejects an invoice without a seller name`() {
        assertFailsWith<EInvoiceMappingException> {
            CiiInvoiceMapper.map(SecureXml.parse(cii(sellerName = "").toByteArray()))
        }
    }

    @Test
    fun `rejects a date that is not the YYYYMMDD format 102`() {
        assertFailsWith<XmlValueException> {
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(issueDate = """<udt:DateTimeString format="102">04.04.2016</udt:DateTimeString>""").toByteArray(),
                ),
            )
        }
    }

    @Test
    fun `rejects a garbled amount rather than reading it as zero`() {
        assertFailsWith<XmlValueException> {
            CiiInvoiceMapper.map(SecureXml.parse(cii(taxBasis = "1.234,56").toByteArray()))
        }
    }

    /**
     * A hand-authored regression guard for our XPath, **not** evidence the path is right: no
     * fixture in the corpus is a credit note, and none carries `ram:InvoiceReferencedDocument`.
     * The paths come from EN 16931 / CII D16B; this pins that we read the ones we meant to.
     */
    @Test
    fun `maps a credit note type code and its preceding invoice reference`() {
        val mapped =
            CiiInvoiceMapper.map(
                SecureXml.parse(
                    cii(
                        typeCode = "<ram:TypeCode>381</ram:TypeCode>",
                        settlementExtra =
                            """
                            <ram:InvoiceReferencedDocument>
                                <ram:IssuerAssignedID>RE-2024-0815</ram:IssuerAssignedID>
                            </ram:InvoiceReferencedDocument>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("381", mapped.invoiceTypeCode)
        assertEquals("RE-2024-0815", mapped.precedingInvoiceReference)
    }

    /**
     * `ram:TypeCode` is a reused element name in CII — the KoSIT fixture alone holds `58` under
     * `SpecifiedTradeSettlementPaymentMeans` and `VAT` under `ApplicableTradeTax`. A document that
     * states no BT-3 must read as absent, never as one of those.
     */
    @Test
    fun `leaves the type code null when the document states none`() {
        val mapped = CiiInvoiceMapper.map(SecureXml.parse(cii().toByteArray()))
        assertNull(mapped.invoiceTypeCode)
        assertNull(mapped.precedingInvoiceReference)
    }

    /** A minimal but structurally faithful CII skeleton, so each test can vary exactly one thing. */
    private fun cii(
        invoiceNumber: String = "<ram:ID>INV-1</ram:ID>",
        typeCode: String = "",
        issueDate: String = """<udt:DateTimeString format="102">20240101</udt:DateTimeString>""",
        sellerName: String = "<ram:Name>Acme GmbH</ram:Name>",
        sellerExtra: String = "",
        delivery: String = "<ram:ApplicableHeaderTradeDelivery/>",
        line: String = "",
        settlementExtra: String = "",
        taxBasis: String = "100.00",
        taxCategory: String = "S",
        taxRate: String = "<ram:RateApplicablePercent>19</ram:RateApplicablePercent>",
        taxExtra: String = "",
    ) = """
        <rsm:CrossIndustryInvoice
            xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
            xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
            xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
            <rsm:ExchangedDocument>
                $invoiceNumber
                $typeCode
                <ram:IssueDateTime>$issueDate</ram:IssueDateTime>
            </rsm:ExchangedDocument>
            <rsm:SupplyChainTradeTransaction>
                $line
                <ram:ApplicableHeaderTradeAgreement>
                    <ram:SellerTradeParty>
                        $sellerName
                        $sellerExtra
                    </ram:SellerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                $delivery
                <ram:ApplicableHeaderTradeSettlement>
                    <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
                    <ram:ApplicableTradeTax>
                        <ram:CalculatedAmount>19.00</ram:CalculatedAmount>
                        <ram:TypeCode>VAT</ram:TypeCode>
                        <ram:BasisAmount>$taxBasis</ram:BasisAmount>
                        <ram:CategoryCode>$taxCategory</ram:CategoryCode>
                        $taxRate
                        $taxExtra
                    </ram:ApplicableTradeTax>
                    $settlementExtra
                </ram:ApplicableHeaderTradeSettlement>
            </rsm:SupplyChainTradeTransaction>
        </rsm:CrossIndustryInvoice>
        """.trimIndent()
}
