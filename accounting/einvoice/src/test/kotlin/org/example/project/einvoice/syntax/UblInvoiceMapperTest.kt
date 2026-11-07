package org.example.project.einvoice.syntax

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.securexml.SecureXml
import org.example.project.securexml.XmlValueException
import org.w3c.dom.Document
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asserts against the real KoSIT XRechnung 3.0 UBL instance in `corpus/`, never against invented
 * values. Amounts are compared with [BigDecimal.compareTo] where the sender's scale is incidental
 * (the fixture writes `336.9`, not `336.90`) and with [assertEquals] where the preserved scale is
 * itself the thing under test.
 *
 * The corpus carries the *same business case* (KoSIT 01.01a) in both syntaxes, so the last test
 * here maps both fixtures and requires the two mappers to agree — see
 * `maps the same business case identically to the CII mapper`.
 */
class UblInvoiceMapperTest {
    private fun corpusDoc(name: String) =
        SecureXml.parse(
            checkNotNull(javaClass.getResourceAsStream("/corpus/$name")).use { it.readBytes() },
        )

    private val invoice = UblInvoiceMapper.map(corpusDoc("xrechnung-ubl.xml"))

    @Test
    fun `maps the document header`() {
        assertEquals("123456XX", invoice.invoiceNumber)
        assertEquals(LocalDate.parse("2016-04-04"), invoice.issueDate)
        assertEquals("EUR", invoice.currency)
        assertEquals("04011000-12345-03", invoice.buyerReference)
        // BT-3: the KoSIT fixture is an ordinary commercial invoice, UNTDID 1001 code 380.
        assertEquals("380", invoice.invoiceTypeCode)
        assertEquals(EInvoiceFormat.UBL, invoice.format)
    }

    @Test
    fun `maps the seller`() {
        val seller = invoice.seller
        // BT-27, the registered legal name. The fixture also states a BT-28 trading name,
        // "[Seller trading name]", which must not win here.
        assertEquals("[Seller name]", seller.name)
        // Carried verbatim, embedded space and all — cleaning it would misreport what was sent.
        assertEquals("DE 123456789", seller.vatId)
        // This fixture declares only a VAT tax scheme, so no Steuernummer is present.
        assertNull(seller.taxNumber)
        assertEquals("[Seller address line 1]", seller.street)
        assertEquals("12345", seller.zip)
        assertEquals("[Seller city]", seller.city)
        assertEquals("DE", seller.country)
        assertEquals("seller@email.de", seller.email)
        assertEquals("+49 1234-5678", seller.phone)
    }

    @Test
    fun `takes the registered legal name over the trading name`() {
        val xml = corpusDoc("xrechnung-ubl.xml")
        // Both are present in the instance and they differ, so the preference is observable.
        assertEquals("[Seller name]", textOf(xml, "RegistrationName"))
        assertEquals("[Seller trading name]", textOf(xml, "Name"))
        assertEquals("[Seller name]", invoice.seller.name)
    }

    @Test
    fun `falls back to the party name when no legal entity name is stated`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(sellerName = "<cac:PartyName><cbc:Name>Acme Handel</cbc:Name></cac:PartyName>").toByteArray(),
                ),
            )
        assertEquals("Acme Handel", mapped.seller.name)
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
    fun `does not mistake a line item tax category for a header VAT group`() {
        // UBL states the line VAT on the item, as cac:ClassifiedTaxCategory. Both element names end
        // in "TaxCategory", so a sloppy //*[TaxCategory] style read would invent one breakdown group
        // per line with no taxable or VAT amount at all.
        val xml = corpusDoc("xrechnung-ubl.xml")
        assertEquals(1, xml.getElementsByTagNameNS("*", "TaxCategory").length)
        assertEquals(2, xml.getElementsByTagNameNS("*", "ClassifiedTaxCategory").length)
        assertEquals(1, invoice.vatBreakdown.size)
    }

    @Test
    fun `maps invoice lines with their unit code`() {
        assertEquals(2, invoice.lines.size)

        val first = invoice.lines[0]
        // cbc:ID is free text in this instance, not a numeric position.
        assertEquals("Zeitschrift [...]", first.lineId)
        assertEquals("Zeitschrift [...]", first.itemName)
        assertEquals("Zeitschrift Inland", first.itemDescription)
        assertEquals("246", first.sellerItemId)
        assertEquals("0721-880X", first.itemStandardId)
        assertNull(first.buyerItemId)
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
        // The corpus instance states no cac:BuyersItemIdentification, so without this the path would
        // be the one mapped field no test could tell apart from a typo silently yielding null.
        val mapped = UblInvoiceMapper.map(SecureXml.parse(ubl(line = invoiceLine(buyerItemId = "B-42")).toByteArray()))
        val mappedLine = mapped.lines.single()
        assertEquals("B-42", mappedLine.buyerItemId)
        assertEquals("C62", mappedLine.unitCode)
        // BT-146 routinely carries four decimals; the sender's scale survives the mapping.
        assertEquals(BigDecimal("10.0000"), mappedLine.netUnitPrice)
        assertEquals(0, mappedLine.quantity.compareTo(BigDecimal("2")))
    }

    /**
     * A hand-authored regression guard for our XPath, **not** evidence the path is right: no
     * fixture in this repo is a `CreditNote` document, and none carries `cac:BillingReference`.
     * `cbc:CreditNoteTypeCode` is UBL 2.1's name for BT-3 on a credit note; this pins that the
     * union expression reads it rather than returning null on the root we already route here.
     */
    @Test
    fun `maps a credit note's type code and preceding invoice reference`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        root = "CreditNote",
                        typeCode = "<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>",
                        documentExtra =
                            """
                            <cac:BillingReference>
                                <cac:InvoiceDocumentReference>
                                    <cbc:ID>RE-2024-0815</cbc:ID>
                                </cac:InvoiceDocumentReference>
                            </cac:BillingReference>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("381", mapped.invoiceTypeCode)
        assertEquals("RE-2024-0815", mapped.precedingInvoiceReference)
    }

    @Test
    fun `leaves the type code null when the document states none`() {
        val mapped = UblInvoiceMapper.map(SecureXml.parse(ubl().toByteArray()))
        assertNull(mapped.invoiceTypeCode)
        assertNull(mapped.precedingInvoiceReference)
    }

    @Test
    fun `maps a credit note's lines and credited quantity`() {
        // EInvoiceSyntaxDetector routes the CreditNote namespace here, so an Invoice-only mapper
        // would return a credit note with no lines at all rather than failing.
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        root = "CreditNote",
                        line =
                            """
                            <cac:CreditNoteLine>
                                <cbc:ID>1</cbc:ID>
                                <cbc:CreditedQuantity unitCode="KGM">3</cbc:CreditedQuantity>
                                <cbc:LineExtensionAmount>30.00</cbc:LineExtensionAmount>
                                <cac:Item>
                                    <cbc:Name>Ruecksendung</cbc:Name>
                                    <cac:ClassifiedTaxCategory>
                                        <cbc:ID>S</cbc:ID>
                                        <cbc:Percent>19</cbc:Percent>
                                    </cac:ClassifiedTaxCategory>
                                </cac:Item>
                                <cac:Price><cbc:PriceAmount>10.00</cbc:PriceAmount></cac:Price>
                            </cac:CreditNoteLine>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        val creditLine = mapped.lines.single()
        assertEquals("1", creditLine.lineId)
        assertEquals("Ruecksendung", creditLine.itemName)
        assertEquals("KGM", creditLine.unitCode)
        assertEquals(0, creditLine.quantity.compareTo(BigDecimal("3")))
        // Carried as sent: UBL states credit-note amounts positive, and negating them is a booking
        // decision rather than a mapping one.
        assertEquals(0, creditLine.lineNetAmount.compareTo(BigDecimal("30.00")))
        assertEquals("INV-1", mapped.invoiceNumber)
    }

    @Test
    fun `maps the payment terms and settlement details`() {
        assertEquals("DE79000000001234567890", invoice.paymentIban)
        assertEquals("Zahlbar sofort ohne Abzug.", invoice.paymentTermsText)
        // The fixture states no due date and no BT-83 remittance reference.
        assertNull(invoice.dueDate)
        assertNull(invoice.paymentReference)
        // No cac:Delivery block at all in this instance.
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
    fun `reads the tax total from the tax total block and not from the tax-inclusive amount`() {
        // BT-110 is cac:TaxTotal/cbc:TaxAmount; cbc:TaxInclusiveAmount is BT-112. The fixture carries
        // two cbc:TaxAmount elements — the document one and the subtotal one — and they agree here,
        // but the tax-inclusive total does not, which is what pins the distinction.
        val xml = corpusDoc("xrechnung-ubl.xml")
        assertEquals(2, xml.getElementsByTagNameNS("*", "TaxAmount").length)
        assertEquals(BigDecimal("22.04"), invoice.taxTotal)
        assertNotEquals(invoice.taxInclusiveTotal, invoice.taxTotal)
        assertEquals(
            0,
            invoice.taxTotal!!.compareTo(
                invoice.vatBreakdown
                    .single()
                    .vatAmount,
            ),
        )
    }

    @Test
    fun `maps no document allowances or charges when the invoice declares none`() {
        assertTrue(invoice.allowancesCharges.isEmpty())
    }

    @Test
    fun `maps a document allowance and a document charge`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        documentExtra =
                            """
                            <cac:AllowanceCharge>
                                <cbc:ChargeIndicator>false</cbc:ChargeIndicator>
                                <cbc:AllowanceChargeReasonCode>95</cbc:AllowanceChargeReasonCode>
                                <cbc:AllowanceChargeReason>Rabatt</cbc:AllowanceChargeReason>
                                <cbc:MultiplierFactorNumeric>10</cbc:MultiplierFactorNumeric>
                                <cbc:Amount>10.00</cbc:Amount>
                                <cbc:BaseAmount>100.00</cbc:BaseAmount>
                                <cac:TaxCategory>
                                    <cbc:ID>S</cbc:ID>
                                    <cbc:Percent>19</cbc:Percent>
                                </cac:TaxCategory>
                            </cac:AllowanceCharge>
                            <cac:AllowanceCharge>
                                <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
                                <cbc:AllowanceChargeReasonCode>ABK</cbc:AllowanceChargeReasonCode>
                                <cbc:AllowanceChargeReason>Verpackung</cbc:AllowanceChargeReason>
                                <cbc:Amount>5.50</cbc:Amount>
                                <cac:TaxCategory>
                                    <cbc:ID>S</cbc:ID>
                                    <cbc:Percent>19</cbc:Percent>
                                </cac:TaxCategory>
                            </cac:AllowanceCharge>
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
        assertEquals("Verpackung", charge.reasonText)
    }

    @Test
    fun `maps the VAT exemption reason when one is stated`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        taxCategory = "AE",
                        taxRate = "<cbc:Percent>0</cbc:Percent>",
                        taxExtra =
                            """
                            <cbc:TaxExemptionReasonCode>VATEX-EU-AE</cbc:TaxExemptionReasonCode>
                            <cbc:TaxExemptionReason>Steuerschuldnerschaft des Leistungsempfaengers</cbc:TaxExemptionReason>
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
            UblInvoiceMapper.map(SecureXml.parse(ubl(dueDate = "<cbc:DueDate>2024-12-31</cbc:DueDate>").toByteArray()))
        assertEquals(LocalDate.parse("2024-12-31"), mapped.dueDate)
    }

    @Test
    fun `maps a delivery date`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        documentExtra = "<cac:Delivery><cbc:ActualDeliveryDate>2024-01-15</cbc:ActualDeliveryDate></cac:Delivery>",
                    ).toByteArray(),
                ),
            )
        assertEquals(LocalDate.parse("2024-01-15"), mapped.deliveryDate)
    }

    @Test
    fun `maps the remittance information`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        documentExtra =
                            """
                            <cac:PaymentMeans>
                                <cbc:PaymentMeansCode>58</cbc:PaymentMeansCode>
                                <cbc:PaymentID>RF18 5390 0754 7034</cbc:PaymentID>
                                <cac:PayeeFinancialAccount><cbc:ID>DE02120300000000202051</cbc:ID></cac:PayeeFinancialAccount>
                            </cac:PaymentMeans>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("RF18 5390 0754 7034", mapped.paymentReference)
        assertEquals("DE02120300000000202051", mapped.paymentIban)
    }

    @Test
    fun `prefers the VAT tax scheme over the Steuernummer`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        sellerExtra =
                            """
                            <cac:PartyTaxScheme>
                                <cbc:CompanyID>123/456/7890</cbc:CompanyID>
                                <cac:TaxScheme><cbc:ID>FC</cbc:ID></cac:TaxScheme>
                            </cac:PartyTaxScheme>
                            <cac:PartyTaxScheme>
                                <cbc:CompanyID>DE123456789</cbc:CompanyID>
                                <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>
                            </cac:PartyTaxScheme>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("DE123456789", mapped.seller.vatId)
        assertEquals("123/456/7890", mapped.seller.taxNumber)
    }

    @Test
    fun `prefers the contact email BT-43 over the party endpoint BT-34`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(
                        sellerExtra =
                            """
                            <cbc:EndpointID schemeID="EM">endpoint@example.de</cbc:EndpointID>
                            <cac:Contact><cbc:ElectronicMail>contact@example.de</cbc:ElectronicMail></cac:Contact>
                            """.trimIndent(),
                    ).toByteArray(),
                ),
            )
        assertEquals("contact@example.de", mapped.seller.email)
    }

    @Test
    fun `falls back to the party endpoint when no contact email is stated`() {
        val mapped =
            UblInvoiceMapper.map(
                SecureXml.parse(
                    ubl(sellerExtra = """<cbc:EndpointID schemeID="EM">endpoint@example.de</cbc:EndpointID>""").toByteArray(),
                ),
            )
        assertEquals("endpoint@example.de", mapped.seller.email)
    }

    @Test
    fun `reads an omitted rate on a reverse-charge group as zero`() {
        val mapped = UblInvoiceMapper.map(SecureXml.parse(ubl(taxCategory = "AE", taxRate = "").toByteArray()))
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
            UblInvoiceMapper.map(SecureXml.parse(ubl(taxRate = "").toByteArray()))
        }
    }

    @Test
    fun `rejects an invoice without an issue date rather than inventing one`() {
        val e =
            assertFailsWith<EInvoiceMappingException> {
                UblInvoiceMapper.map(SecureXml.parse(ubl(issueDate = "").toByteArray()))
            }
        assertTrue(e.message!!.contains("IssueDate"), e.message)
    }

    @Test
    fun `rejects an invoice without an invoice number`() {
        assertFailsWith<EInvoiceMappingException> {
            UblInvoiceMapper.map(SecureXml.parse(ubl(invoiceNumber = "").toByteArray()))
        }
    }

    @Test
    fun `rejects an invoice without any seller name`() {
        assertFailsWith<EInvoiceMappingException> {
            UblInvoiceMapper.map(SecureXml.parse(ubl(sellerName = "").toByteArray()))
        }
    }

    @Test
    fun `rejects a date that is not an ISO calendar date`() {
        assertFailsWith<XmlValueException> {
            UblInvoiceMapper.map(SecureXml.parse(ubl(issueDate = "<cbc:IssueDate>04.04.2016</cbc:IssueDate>").toByteArray()))
        }
    }

    @Test
    fun `rejects a garbled amount rather than reading it as zero`() {
        assertFailsWith<XmlValueException> {
            UblInvoiceMapper.map(SecureXml.parse(ubl(taxBasis = "1.234,56").toByteArray()))
        }
    }

    @Test
    fun `maps the same business case identically to the CII mapper`() {
        // corpus/xrechnung-cii.xml and corpus/xrechnung-ubl.xml are the same KoSIT business case in
        // the two syntaxes, so the syntax-neutral model they produce must agree in everything except
        // the format marker. Two independently written mappers agreeing field for field is far
        // stronger evidence than either suite alone.
        val fromCii = CiiInvoiceMapper.map(corpusDoc("xrechnung-cii.xml"))

        assertEquals(fromCii.invoiceNumber, invoice.invoiceNumber)
        assertEquals(fromCii.issueDate, invoice.issueDate)
        assertEquals(fromCii.currency, invoice.currency)
        assertEquals(fromCii.buyerReference, invoice.buyerReference)
        assertEquals(fromCii.seller, invoice.seller)
        assertEquals(fromCii.vatBreakdown, invoice.vatBreakdown)
        assertEquals(fromCii.lines, invoice.lines)
        assertEquals(fromCii.paymentIban, invoice.paymentIban)
        assertEquals(fromCii.paymentTermsText, invoice.paymentTermsText)
        assertEquals(fromCii.lineNetTotal, invoice.lineNetTotal)
        assertEquals(fromCii.taxExclusiveTotal, invoice.taxExclusiveTotal)
        assertEquals(fromCii.taxTotal, invoice.taxTotal)
        assertEquals(fromCii.taxInclusiveTotal, invoice.taxInclusiveTotal)
        assertEquals(fromCii.amountDue, invoice.amountDue)

        // Everything, in one assertion: only the syntax marker may differ.
        assertEquals(fromCii.copy(format = EInvoiceFormat.UBL), invoice)
    }

    private fun textOf(
        document: Document,
        localName: String,
    ) = document
        .getElementsByTagNameNS("*", localName)
        .item(0)
        .textContent
        .trim()

    private fun invoiceLine(buyerItemId: String) =
        """
        <cac:InvoiceLine>
            <cbc:ID>1</cbc:ID>
            <cbc:InvoicedQuantity unitCode="C62">2</cbc:InvoicedQuantity>
            <cbc:LineExtensionAmount>20.00</cbc:LineExtensionAmount>
            <cac:Item>
                <cbc:Name>Ware</cbc:Name>
                <cac:BuyersItemIdentification><cbc:ID>$buyerItemId</cbc:ID></cac:BuyersItemIdentification>
                <cac:ClassifiedTaxCategory>
                    <cbc:ID>S</cbc:ID>
                    <cbc:Percent>19</cbc:Percent>
                </cac:ClassifiedTaxCategory>
            </cac:Item>
            <cac:Price><cbc:PriceAmount>10.0000</cbc:PriceAmount></cac:Price>
        </cac:InvoiceLine>
        """.trimIndent()

    /** A minimal but structurally faithful UBL skeleton, so each test can vary exactly one thing. */
    private fun ubl(
        root: String = "Invoice",
        invoiceNumber: String = "<cbc:ID>INV-1</cbc:ID>",
        issueDate: String = "<cbc:IssueDate>2024-01-01</cbc:IssueDate>",
        dueDate: String = "",
        typeCode: String = "",
        sellerName: String = "<cac:PartyLegalEntity><cbc:RegistrationName>Acme GmbH</cbc:RegistrationName></cac:PartyLegalEntity>",
        sellerExtra: String = "",
        line: String = "",
        documentExtra: String = "",
        taxBasis: String = "100.00",
        taxCategory: String = "S",
        taxRate: String = "<cbc:Percent>19</cbc:Percent>",
        taxExtra: String = "",
    ) = """
        <ubl:$root xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:$root-2"
                   xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                   xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
            $invoiceNumber
            $issueDate
            $dueDate
            $typeCode
            <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
            <cac:AccountingSupplierParty>
                <cac:Party>
                    $sellerName
                    $sellerExtra
                </cac:Party>
            </cac:AccountingSupplierParty>
            $documentExtra
            <cac:TaxTotal>
                <cbc:TaxAmount>19.00</cbc:TaxAmount>
                <cac:TaxSubtotal>
                    <cbc:TaxableAmount>$taxBasis</cbc:TaxableAmount>
                    <cbc:TaxAmount>19.00</cbc:TaxAmount>
                    <cac:TaxCategory>
                        <cbc:ID>$taxCategory</cbc:ID>
                        $taxRate
                        $taxExtra
                    </cac:TaxCategory>
                </cac:TaxSubtotal>
            </cac:TaxTotal>
            $line
        </ubl:$root>
        """.trimIndent()
}
