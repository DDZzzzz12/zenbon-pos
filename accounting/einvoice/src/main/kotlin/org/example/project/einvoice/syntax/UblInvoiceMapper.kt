package org.example.project.einvoice.syntax

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.einvoice.xml.XmlNamespaces
import org.example.project.securexml.XPathReader
import org.example.project.securexml.XmlValueException
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.math.BigDecimal

/**
 * Maps an OASIS UBL 2.1 invoice DOM onto the syntax-neutral [EInvoice] model. UBL is the syntax
 * UBL-flavoured XRechnung and Peppol BIS Billing share, expressing the same EN 16931 semantics as
 * [CiiInvoiceMapper] does in CII.
 *
 * Pure: XML in, model out. It performs no conformance checking and asserts no arithmetic invariant
 * — a later stage owns both. Its only refusals concern terms it cannot represent: a term the model
 * declares non-null but the document omits raises [EInvoiceMappingException], and a term that is
 * present but unreadable raises [XmlValueException] (via [XPathReader.decimal], or directly for a
 * date). Neither is ever papered over with a placeholder, exactly as in [CiiInvoiceMapper].
 *
 * Both UBL document types are handled. Every expression starts at the wildcard document element
 * rather than naming `ubl:Invoice`, and the line and quantity expressions are unions, because a credit note roots at
 * `CreditNote` and states `cac:CreditNoteLine` / `cbc:CreditedQuantity` where an invoice states
 * `cac:InvoiceLine` / `cbc:InvoicedQuantity`. [org.example.project.einvoice.detect.EInvoiceSyntaxDetector]
 * already routes the credit-note namespace here, so an invoice-only mapper would silently return a
 * credit note with no lines at all. Credit-note amounts are carried exactly as sent — UBL states
 * them positive, and negating them is a booking decision, not a mapping one.
 */
object UblInvoiceMapper {
    private val read = XPathReader(XmlNamespaces.ublContext())

    private const val ROOT = "/*"
    private const val SELLER = "$ROOT/cac:AccountingSupplierParty/cac:Party"
    private const val BUYER = "$ROOT/cac:AccountingCustomerParty/cac:Party"
    private const val TOTALS = "$ROOT/cac:LegalMonetaryTotal"
    private const val TAX_TOTAL = "$ROOT/cac:TaxTotal"
    private const val PAYMENT_MEANS = "$ROOT/cac:PaymentMeans"
    private const val LINE = "$ROOT/cac:InvoiceLine | $ROOT/cac:CreditNoteLine"
    private const val LINE_QUANTITY = "cbc:InvoicedQuantity | cbc:CreditedQuantity"

    /**
     * BT-3. A union for the same reason as [LINE]: a credit note states `cbc:CreditNoteTypeCode`
     * where an invoice states `cbc:InvoiceTypeCode`, and `EInvoiceSyntaxDetector` routes both roots
     * here. A UBL document carries exactly one of the two, and `XPathConstants.STRING` over a union
     * node-set returns the first node in document order — so the union reads whichever is present.
     */
    private const val TYPE_CODE = "$ROOT/cbc:InvoiceTypeCode | $ROOT/cbc:CreditNoteTypeCode"

    /** BT-25. */
    private const val PRECEDING_INVOICE =
        "$ROOT/cac:BillingReference/cac:InvoiceDocumentReference/cbc:ID"

    /** `cac:TaxScheme/cbc:ID` values: UNCL5153 `VAT` for the VAT ID, `FC` for the German Steuernummer. */
    private const val SCHEME_VAT = "VAT"
    private const val SCHEME_TAX_NUMBER = "FC"

    /**
     * UNCL5305 categories that carry no VAT by definition. A sender may omit `cbc:Percent` on these
     * rather than writing `0`, so an absent rate there is a zero rate, not a gap. On any other
     * category an absent rate is a gap and is refused — silently reading it as 0 % would understate
     * the VAT on a standard-rated line.
     */
    private val ZERO_RATE_CATEGORIES = setOf("Z", "E", "AE", "K", "G", "O")

    fun map(document: Document): EInvoice =
        EInvoice(
            format = EInvoiceFormat.UBL,
            invoiceNumber = requiredString(document, "$ROOT/cbc:ID"),
            issueDate = requiredDate(document, "$ROOT/cbc:IssueDate"),
            currency = requiredString(document, "$ROOT/cbc:DocumentCurrencyCode"),
            seller = mapSeller(document),
            buyer = mapBuyer(document),
            vatBreakdown = read.elements(document, "$TAX_TOTAL/cac:TaxSubtotal").map(::mapVatGroup),
            lines = read.elements(document, LINE).map(::mapLine),
            allowancesCharges = read.elements(document, "$ROOT/cac:AllowanceCharge").map(::mapAllowanceCharge),
            dueDate = date(document, "$ROOT/cbc:DueDate"),
            deliveryDate = date(document, "$ROOT/cac:Delivery/cbc:ActualDeliveryDate"),
            buyerReference = read.string(document, "$ROOT/cbc:BuyerReference"),
            paymentIban = read.string(document, "$PAYMENT_MEANS/cac:PayeeFinancialAccount/cbc:ID"),
            paymentReference = read.string(document, "$PAYMENT_MEANS/cbc:PaymentID"),
            paymentTermsText = read.string(document, "$ROOT/cac:PaymentTerms/cbc:Note"),
            invoiceTypeCode = read.string(document, TYPE_CODE),
            precedingInvoiceReference = read.string(document, PRECEDING_INVOICE),
            lineNetTotal = read.decimal(document, "$TOTALS/cbc:LineExtensionAmount"),
            taxExclusiveTotal = read.decimal(document, "$TOTALS/cbc:TaxExclusiveAmount"),
            // BT-110, the tax total. Not cbc:TaxInclusiveAmount, which is BT-112 and is mapped below.
            taxTotal = read.decimal(document, "$TAX_TOTAL/cbc:TaxAmount"),
            taxInclusiveTotal = read.decimal(document, "$TOTALS/cbc:TaxInclusiveAmount"),
            amountDue = read.decimal(document, "$TOTALS/cbc:PayableAmount"),
            prepaidAmount = read.decimal(document, "$TOTALS/cbc:PrepaidAmount"),
            roundingAmount = read.decimal(document, "$TOTALS/cbc:PayableRoundingAmount"),
        )

    /**
     * `name` is BT-27, the seller's registered legal name, which UBL states as
     * `cac:PartyLegalEntity/cbc:RegistrationName`. `cac:PartyName/cbc:Name` is BT-28, the optional
     * trading name, and is only the fallback: taking it first would put a shop's trading style where
     * the ledger expects the legal entity, and would disagree with CII's `ram:Name` on the very same
     * business case.
     *
     * The tax registrations are filtered by their `cac:TaxScheme/cbc:ID` rather than taken
     * positionally, for the same reason [CiiInvoiceMapper] filters by `schemeID`: a seller routinely
     * declares both, and an unfiltered `cac:PartyTaxScheme/cbc:CompanyID` would put the Steuernummer
     * into the VAT-ID field whenever it happens to come first.
     *
     * `email` is BT-43, the seller contact's address, falling back to BT-34, the party's electronic
     * address (`cbc:EndpointID`) — the same preference [CiiInvoiceMapper] applies.
     */
    private fun mapSeller(document: Document): EInvoiceParty {
        val legalName = "$SELLER/cac:PartyLegalEntity/cbc:RegistrationName"
        val address = "$SELLER/cac:PostalAddress"
        val contact = "$SELLER/cac:Contact"
        return EInvoiceParty(
            name =
                read.string(document, legalName)
                    ?: read.string(document, "$SELLER/cac:PartyName/cbc:Name")
                    ?: throw EInvoiceMappingException(legalName),
            vatId = read.string(document, companyIdOfScheme(SCHEME_VAT)),
            taxNumber = read.string(document, companyIdOfScheme(SCHEME_TAX_NUMBER)),
            street = read.string(document, "$address/cbc:StreetName"),
            zip = read.string(document, "$address/cbc:PostalZone"),
            city = read.string(document, "$address/cbc:CityName"),
            country = read.string(document, "$address/cac:Country/cbc:IdentificationCode"),
            phone = read.string(document, "$contact/cbc:Telephone"),
            email =
                read.string(document, "$contact/cbc:ElectronicMail")
                    ?: read.string(document, "$SELLER/cbc:EndpointID"),
        )
    }

    private fun companyIdOfScheme(scheme: String) = "$SELLER/cac:PartyTaxScheme[cac:TaxScheme/cbc:ID='$scheme']/cbc:CompanyID"

    /**
     * BG-7 buyer, mirroring [mapSeller] off `cac:AccountingCustomerParty`. Returns null when no buyer
     * party is present, so the same business case reads identically to [CiiInvoiceMapper].
     */
    private fun mapBuyer(document: Document): EInvoiceParty? {
        val legalName = "$BUYER/cac:PartyLegalEntity/cbc:RegistrationName"
        val name =
            read.string(document, legalName)
                ?: read.string(document, "$BUYER/cac:PartyName/cbc:Name")
                ?: return null
        val address = "$BUYER/cac:PostalAddress"
        val contact = "$BUYER/cac:Contact"
        return EInvoiceParty(
            name = name,
            vatId = read.string(document, "$BUYER/cac:PartyTaxScheme[cac:TaxScheme/cbc:ID='$SCHEME_VAT']/cbc:CompanyID"),
            taxNumber = read.string(document, "$BUYER/cac:PartyTaxScheme[cac:TaxScheme/cbc:ID='$SCHEME_TAX_NUMBER']/cbc:CompanyID"),
            street = read.string(document, "$address/cbc:StreetName"),
            zip = read.string(document, "$address/cbc:PostalZone"),
            city = read.string(document, "$address/cbc:CityName"),
            country = read.string(document, "$address/cac:Country/cbc:IdentificationCode"),
            phone = read.string(document, "$contact/cbc:Telephone"),
            email =
                read.string(document, "$contact/cbc:ElectronicMail")
                    ?: read.string(document, "$BUYER/cbc:EndpointID"),
        )
    }

    /**
     * Reads only `cac:TaxSubtotal`, which is where the taxable and VAT amounts live. `cac:TaxCategory`
     * also occurs on every line (under `cac:Item/cac:ClassifiedTaxCategory`) and on every allowance,
     * carrying a category and rate but no amounts, so the fully qualified header path matters here as
     * much as it does in CII.
     */
    private fun mapVatGroup(group: Node): EInvoiceVatBreakdown {
        val category = "cac:TaxCategory"
        val categoryCode = requiredString(group, "$category/cbc:ID")
        return EInvoiceVatBreakdown(
            categoryCode = categoryCode,
            rate = rateOf(group, "$category/cbc:Percent", categoryCode),
            taxableAmount = requiredDecimal(group, "cbc:TaxableAmount"),
            vatAmount = requiredDecimal(group, "cbc:TaxAmount"),
            exemptionReasonCode = read.string(group, "$category/cbc:TaxExemptionReasonCode"),
            exemptionReasonText = read.string(group, "$category/cbc:TaxExemptionReason"),
        )
    }

    /** UBL states the line's VAT category on the item, not on a line-level settlement group as CII does. */
    private fun mapLine(line: Node): EInvoiceLine {
        val item = "cac:Item"
        val taxCategory = "$item/cac:ClassifiedTaxCategory"
        val categoryCode = requiredString(line, "$taxCategory/cbc:ID")
        return EInvoiceLine(
            lineId = requiredString(line, "cbc:ID"),
            itemName = requiredString(line, "$item/cbc:Name"),
            quantity = requiredDecimal(line, LINE_QUANTITY),
            unitCode = read.attribute(line, LINE_QUANTITY, "unitCode") ?: throw EInvoiceMappingException("$LINE_QUANTITY/@unitCode"),
            netUnitPrice = requiredDecimal(line, "cac:Price/cbc:PriceAmount"),
            lineNetAmount = requiredDecimal(line, "cbc:LineExtensionAmount"),
            vatCategoryCode = categoryCode,
            vatRate = rateOf(line, "$taxCategory/cbc:Percent", categoryCode),
            itemDescription = read.string(line, "$item/cbc:Description"),
            sellerItemId = read.string(line, "$item/cac:SellersItemIdentification/cbc:ID"),
            buyerItemId = read.string(line, "$item/cac:BuyersItemIdentification/cbc:ID"),
            itemStandardId = read.string(line, "$item/cac:CommodityClassification/cbc:ItemClassificationCode"),
        )
    }

    private fun mapAllowanceCharge(item: Node): EInvoiceAllowanceCharge {
        val tax = "cac:TaxCategory"
        val categoryCode = requiredString(item, "$tax/cbc:ID")
        return EInvoiceAllowanceCharge(
            isCharge = isTrue(read.string(item, "cbc:ChargeIndicator")),
            amount = requiredDecimal(item, "cbc:Amount"),
            vatCategoryCode = categoryCode,
            vatRate = rateOf(item, "$tax/cbc:Percent", categoryCode),
            baseAmount = read.decimal(item, "cbc:BaseAmount"),
            percentage = read.decimal(item, "cbc:MultiplierFactorNumeric"),
            reasonCode = read.string(item, "cbc:AllowanceChargeReasonCode"),
            reasonText = read.string(item, "cbc:AllowanceChargeReason"),
        )
    }

    /** `cbc:ChargeIndicator` is an `xs:boolean`, whose lexical space includes `1`/`0` as well as `true`/`false`. */
    private fun isTrue(raw: String?) = raw == "true" || raw == "1"

    private fun rateOf(
        node: Node,
        expression: String,
        categoryCode: String,
    ): BigDecimal =
        read.decimal(node, expression)
            ?: BigDecimal.ZERO.takeIf { categoryCode in ZERO_RATE_CATEGORIES }
            ?: throw EInvoiceMappingException(expression, "VAT rate is missing on a taxed category \"$categoryCode\"")

    private fun requiredString(
        node: Node,
        expression: String,
    ): String = read.string(node, expression) ?: throw EInvoiceMappingException(expression)

    private fun requiredDecimal(
        node: Node,
        expression: String,
    ): BigDecimal = read.decimal(node, expression) ?: throw EInvoiceMappingException(expression)

    private fun requiredDate(
        node: Node,
        expression: String,
    ): LocalDate = date(node, expression) ?: throw EInvoiceMappingException(expression)

    /**
     * UBL writes dates as plain `xs:date`, i.e. ISO `YYYY-MM-DD` — unlike CII, which writes `CCYYMMDD`
     * behind a `format="102"` code. Anything else is refused as [XmlValueException] rather than
     * coerced, the same way [XPathReader.decimal] refuses a garbled amount. An `xs:date` may carry a
     * timezone offset (`2016-04-04+02:00`), which is stripped: it shifts no calendar day, and
     * [LocalDate] cannot hold it.
     */
    private fun date(
        node: Node,
        expression: String,
    ): LocalDate? {
        val raw = read.string(node, expression) ?: return null
        val calendarDay = raw.take(10)
        return runCatching { LocalDate.parse(calendarDay) }.getOrElse { throw XmlValueException(expression, raw, it) }
    }
}
