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
 * Maps a UN/CEFACT Cross Industry Invoice DOM onto the syntax-neutral [EInvoice] model. CII is the
 * syntax ZUGFeRD, Factur-X and CII-flavoured XRechnung all share, so one mapper serves all three.
 *
 * Pure: XML in, model out. It performs no conformance checking and asserts no arithmetic invariant
 * — a later stage owns both. Its only refusals concern terms it cannot represent: a term the model
 * declares non-null but the document omits raises [EInvoiceMappingException], and a term that is
 * present but unreadable raises [XmlValueException] (via [XPathReader.decimal], or directly for a
 * date). Neither is ever papered over with a placeholder, because a fabricated invoice number or a
 * `1970-01-01` issue date would be indistinguishable downstream from something the supplier sent.
 *
 * [EInvoice.format] is set to [EInvoiceFormat.ZUGFERD] provisionally; profile detection reads the
 * guideline URN and overrides it.
 */
object CiiInvoiceMapper {
    private val read = XPathReader(XmlNamespaces.ciiContext())

    private const val ROOT = "/rsm:CrossIndustryInvoice"
    private const val TRANSACTION = "$ROOT/rsm:SupplyChainTradeTransaction"
    private const val DOC = "$ROOT/rsm:ExchangedDocument"
    private const val AGREEMENT = "$TRANSACTION/ram:ApplicableHeaderTradeAgreement"
    private const val DELIVERY = "$TRANSACTION/ram:ApplicableHeaderTradeDelivery"
    private const val SETTLEMENT = "$TRANSACTION/ram:ApplicableHeaderTradeSettlement"
    private const val TOTALS = "$SETTLEMENT/ram:SpecifiedTradeSettlementHeaderMonetarySummation"
    private const val SELLER = "$AGREEMENT/ram:SellerTradeParty"
    private const val LINE = "$TRANSACTION/ram:IncludedSupplyChainTradeLineItem"
    private const val PAYMENT_TERMS = "$SETTLEMENT/ram:SpecifiedTradePaymentTerms"

    /** `udt:DateTimeString/@format`: UN/EDIFACT 2379 code 102, i.e. `CCYYMMDD`. */
    private const val DATE_FORMAT_CCYYMMDD = "102"

    /**
     * UNCL5305 categories that carry no VAT by definition. CII omits `ram:RateApplicablePercent`
     * on these rather than writing `0`, so an absent rate there is a zero rate, not a gap. On any
     * other category an absent rate is a gap and is refused — silently reading it as 0 % would
     * understate the VAT on a standard-rated line.
     */
    private val ZERO_RATE_CATEGORIES = setOf("Z", "E", "AE", "K", "G", "O")

    fun map(document: Document): EInvoice =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = requiredString(document, "$DOC/ram:ID"),
            issueDate = requiredDate(document, "$DOC/ram:IssueDateTime/udt:DateTimeString"),
            currency = requiredString(document, "$SETTLEMENT/ram:InvoiceCurrencyCode"),
            seller = mapSeller(document),
            buyer = mapBuyer(document),
            vatBreakdown = read.elements(document, "$SETTLEMENT/ram:ApplicableTradeTax").map(::mapVatGroup),
            lines = read.elements(document, LINE).map(::mapLine),
            allowancesCharges = read.elements(document, "$SETTLEMENT/ram:SpecifiedTradeAllowanceCharge").map(::mapAllowanceCharge),
            dueDate = date(document, "$PAYMENT_TERMS/ram:DueDateDateTime/udt:DateTimeString"),
            deliveryDate = date(document, "$DELIVERY/ram:ActualDeliverySupplyChainEvent/ram:OccurrenceDateTime/udt:DateTimeString"),
            buyerReference = read.string(document, "$AGREEMENT/ram:BuyerReference"),
            paymentIban = read.string(document, "$SETTLEMENT/ram:SpecifiedTradeSettlementPaymentMeans/ram:PayeePartyCreditorFinancialAccount/ram:IBANID"),
            paymentReference = read.string(document, "$SETTLEMENT/ram:PaymentReference"),
            paymentTermsText = read.string(document, "$PAYMENT_TERMS/ram:Description"),
            // BT-3. Fully qualified on purpose: `ram:TypeCode` is reused across CII — `58` under
            // `SpecifiedTradeSettlementPaymentMeans`, `VAT` under `ApplicableTradeTax` — so an
            // unqualified `//ram:TypeCode` would silently read a payment-means code instead.
            invoiceTypeCode = read.string(document, "$DOC/ram:TypeCode"),
            // BT-25.
            precedingInvoiceReference =
                read.string(document, "$SETTLEMENT/ram:InvoiceReferencedDocument/ram:IssuerAssignedID"),
            prepaidAmount = read.decimal(document, "$TOTALS/ram:TotalPrepaidAmount"),
            lineNetTotal = read.decimal(document, "$TOTALS/ram:LineTotalAmount"),
            allowanceTotal = read.decimal(document, "$TOTALS/ram:AllowanceTotalAmount"),
            chargeTotal = read.decimal(document, "$TOTALS/ram:ChargeTotalAmount"),
            taxExclusiveTotal = read.decimal(document, "$TOTALS/ram:TaxBasisTotalAmount"),
            taxTotal = read.decimal(document, "$TOTALS/ram:TaxTotalAmount"),
            taxInclusiveTotal = read.decimal(document, "$TOTALS/ram:GrandTotalAmount"),
            amountDue = read.decimal(document, "$TOTALS/ram:DuePayableAmount"),
            roundingAmount = read.decimal(document, "$TOTALS/ram:RoundingAmount"),
        )

    /**
     * The tax registrations are filtered by `schemeID` rather than taken positionally: a seller
     * routinely declares both, and an unfiltered `ram:SpecifiedTaxRegistration/ram:ID` would put
     * the Steuernummer (`FC`) into the VAT-ID field whenever it happens to come first.
     *
     * `email` is BT-43, the seller contact's address, falling back to BT-34, the party's electronic
     * address, when no contact block is given.
     */
    private fun mapSeller(document: Document): EInvoiceParty {
        val address = "$SELLER/ram:PostalTradeAddress"
        val contact = "$SELLER/ram:DefinedTradeContact"
        return EInvoiceParty(
            name = requiredString(document, "$SELLER/ram:Name"),
            vatId = read.string(document, "$SELLER/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']"),
            taxNumber = read.string(document, "$SELLER/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='FC']"),
            street = read.string(document, "$address/ram:LineOne"),
            zip = read.string(document, "$address/ram:PostcodeCode"),
            city = read.string(document, "$address/ram:CityName"),
            country = read.string(document, "$address/ram:CountryID"),
            phone = read.string(document, "$contact/ram:TelephoneUniversalCommunication/ram:CompleteNumber"),
            email =
                read.string(document, "$contact/ram:EmailURIUniversalCommunication/ram:URIID")
                    ?: read.string(document, "$SELLER/ram:URIUniversalCommunication/ram:URIID"),
        )
    }

    /**
     * BG-7 buyer party. Mirrors [mapSeller] exactly — same `schemeID='VA'`/`'FC'` filtering and the
     * same contact/email fallback — but off `ram:BuyerTradeParty`. Returns null when the document
     * carries no buyer block: an absent BG-7 is a legitimate omission, not a mapping failure, so it
     * must not raise the way a missing seller name would.
     */
    private fun mapBuyer(document: Document): EInvoiceParty? {
        val buyer = "$AGREEMENT/ram:BuyerTradeParty"
        val name = read.string(document, "$buyer/ram:Name") ?: return null
        val address = "$buyer/ram:PostalTradeAddress"
        val contact = "$buyer/ram:DefinedTradeContact"
        return EInvoiceParty(
            name = name,
            vatId = read.string(document, "$buyer/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']"),
            taxNumber = read.string(document, "$buyer/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='FC']"),
            street = read.string(document, "$address/ram:LineOne"),
            zip = read.string(document, "$address/ram:PostcodeCode"),
            city = read.string(document, "$address/ram:CityName"),
            country = read.string(document, "$address/ram:CountryID"),
            phone = read.string(document, "$contact/ram:TelephoneUniversalCommunication/ram:CompleteNumber"),
            email =
                read.string(document, "$contact/ram:EmailURIUniversalCommunication/ram:URIID")
                    ?: read.string(document, "$buyer/ram:URIUniversalCommunication/ram:URIID"),
        )
    }

    /**
     * Reads only the header groups. `ram:ApplicableTradeTax` also exists once per line, under
     * `ram:SpecifiedLineTradeSettlement`, carrying a category and rate but no amounts — so the
     * caller must pass the fully qualified header path. An unqualified `//ram:ApplicableTradeTax`
     * would invent one breakdown group per line with no taxable or VAT amount at all.
     */
    private fun mapVatGroup(group: Node): EInvoiceVatBreakdown {
        val categoryCode = requiredString(group, "ram:CategoryCode")
        return EInvoiceVatBreakdown(
            categoryCode = categoryCode,
            rate = rateOf(group, "ram:RateApplicablePercent", categoryCode),
            taxableAmount = requiredDecimal(group, "ram:BasisAmount"),
            vatAmount = requiredDecimal(group, "ram:CalculatedAmount"),
            exemptionReasonCode = read.string(group, "ram:ExemptionReasonCode"),
            exemptionReasonText = read.string(group, "ram:ExemptionReason"),
        )
    }

    private fun mapLine(line: Node): EInvoiceLine {
        val product = "ram:SpecifiedTradeProduct"
        val settlement = "ram:SpecifiedLineTradeSettlement"
        val quantity = "ram:SpecifiedLineTradeDelivery/ram:BilledQuantity"
        val categoryCode = requiredString(line, "$settlement/ram:ApplicableTradeTax/ram:CategoryCode")
        return EInvoiceLine(
            lineId = requiredString(line, "ram:AssociatedDocumentLineDocument/ram:LineID"),
            itemName = requiredString(line, "$product/ram:Name"),
            quantity = requiredDecimal(line, quantity),
            unitCode = read.attribute(line, quantity, "unitCode") ?: throw EInvoiceMappingException("$quantity/@unitCode"),
            netUnitPrice = requiredDecimal(line, "ram:SpecifiedLineTradeAgreement/ram:NetPriceProductTradePrice/ram:ChargeAmount"),
            lineNetAmount = requiredDecimal(line, "$settlement/ram:SpecifiedTradeSettlementLineMonetarySummation/ram:LineTotalAmount"),
            vatCategoryCode = categoryCode,
            vatRate = rateOf(line, "$settlement/ram:ApplicableTradeTax/ram:RateApplicablePercent", categoryCode),
            itemDescription = read.string(line, "$product/ram:Description"),
            sellerItemId = read.string(line, "$product/ram:SellerAssignedID"),
            buyerItemId = read.string(line, "$product/ram:BuyerAssignedID"),
            itemStandardId = read.string(line, "$product/ram:DesignatedProductClassification/ram:ClassCode"),
        )
    }

    private fun mapAllowanceCharge(item: Node): EInvoiceAllowanceCharge {
        val tax = "ram:CategoryTradeTax"
        val categoryCode = requiredString(item, "$tax/ram:CategoryCode")
        return EInvoiceAllowanceCharge(
            isCharge = isTrue(read.string(item, "ram:ChargeIndicator/udt:Indicator")),
            amount = requiredDecimal(item, "ram:ActualAmount"),
            vatCategoryCode = categoryCode,
            vatRate = rateOf(item, "$tax/ram:RateApplicablePercent", categoryCode),
            baseAmount = read.decimal(item, "ram:BasisAmount"),
            percentage = read.decimal(item, "ram:CalculationPercent"),
            reasonCode = read.string(item, "ram:ReasonCode"),
            reasonText = read.string(item, "ram:Reason"),
        )
    }

    /** `udt:Indicator` is an `xs:boolean`, whose lexical space includes `1`/`0` as well as `true`/`false`. */
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
     * CII writes dates as `CCYYMMDD` with `format="102"`. A different format code means the digits
     * mean something else — `101` is `YYMMDD`, `610` is `CCYYMM` — so reading them positionally
     * would yield a plausible but wrong date. Both an unknown format code and an unparseable value
     * are refused as [XmlValueException], the same way [XPathReader.decimal] refuses a garbled
     * amount.
     */
    private fun date(
        node: Node,
        expression: String,
    ): LocalDate? {
        val raw = read.string(node, expression) ?: return null
        val format = read.attribute(node, expression, "format")
        if (format != null && format != DATE_FORMAT_CCYYMMDD) {
            throw XmlValueException("$expression/@format", format)
        }
        return runCatching {
            require(raw.length == 8 && raw.all(Char::isDigit)) { "expected CCYYMMDD" }
            LocalDate(raw.substring(0, 4).toInt(), raw.substring(4, 6).toInt(), raw.substring(6, 8).toInt())
        }.getOrElse { throw XmlValueException(expression, raw, it) }
    }
}
