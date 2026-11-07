package org.example.project.einvoice.syntax

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.einvoice.xml.XmlNamespaces
import org.example.project.securexml.SecureXml
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.math.BigDecimal

/**
 * Builds a UN/CEFACT Cross Industry Invoice DOM from the syntax-neutral [EInvoice] model — the exact
 * inverse of [CiiInvoiceMapper]. Every element path, namespace and lexical form here is chosen to be
 * the one the mapper reads, so a written document parses back to the source invoice unchanged. That
 * round-trip is the writer's correctness oracle: [CiiInvoiceMapper] is the shipped, trusted parser,
 * so a document it maps back to the identical [EInvoice] is structurally valid EN 16931 CII.
 *
 * Unit prices, quantities and rates are written with [BigDecimal.toPlainString], preserving the scale
 * the caller supplied, so the parser's scale-preserving `BigDecimal(raw)` reproduces them exactly.
 * **Monetary amounts** (BT-106 … BT-117, BT-131) are written at exactly two decimals, as EN 16931
 * BR-DEC demands; an amount carrying a sub-cent value is **refused**, never rounded here — rounding
 * amounts one by one breaks the BR-CO sums, so the caller must hand over amounts already reconciled
 * at two decimals. `CountryID` must be an ISO 3166-1 alpha-2 code for the same reason. Dates are
 * written as `CCYYMMDD` with `format="102"`, the only form [CiiInvoiceMapper] accepts.
 *
 * The document is built with [SecureXml.newDocument] and serialised with [SecureXml.serialize]; this
 * module must never construct its own parser factory. Children are appended in a fixed order so the
 * serialised bytes are deterministic.
 */
object CiiInvoiceWriter {
    /** EN 16931 COMFORT guideline URN (BT-24). */
    private const val GUIDELINE_URN = "urn:cen.eu:en16931:2017"

    /** `udt:DateTimeString/@format`: UN/EDIFACT 2379 code 102, i.e. `CCYYMMDD`. */
    private const val DATE_FORMAT_CCYYMMDD = "102"

    fun write(invoice: EInvoice): ByteArray {
        val doc = SecureXml.newDocument()
        val root = doc.createElementNS(XmlNamespaces.RSM, "rsm:CrossIndustryInvoice")
        root.setAttributeNS(XMLNS, "xmlns:rsm", XmlNamespaces.RSM)
        root.setAttributeNS(XMLNS, "xmlns:ram", XmlNamespaces.RAM)
        root.setAttributeNS(XMLNS, "xmlns:udt", XmlNamespaces.UDT)
        doc.appendChild(root)

        writeContext(doc, root)
        writeExchangedDocument(doc, root, invoice)
        writeTransaction(doc, root, invoice)

        return SecureXml.serialize(doc)
    }

    private fun writeContext(
        doc: Document,
        root: Element,
    ) {
        val context = elem(doc, root, "rsm:ExchangedDocumentContext")
        val guideline = elem(doc, context, "ram:GuidelineSpecifiedDocumentContextParameter")
        text(doc, guideline, "ram:ID", GUIDELINE_URN)
    }

    private fun writeExchangedDocument(
        doc: Document,
        root: Element,
        invoice: EInvoice,
    ) {
        val document = elem(doc, root, "rsm:ExchangedDocument")
        text(doc, document, "ram:ID", invoice.invoiceNumber)
        invoice.invoiceTypeCode?.let { text(doc, document, "ram:TypeCode", it) }
        val issue = elem(doc, document, "ram:IssueDateTime")
        writeDate(doc, issue, invoice.issueDate)
    }

    private fun writeTransaction(
        doc: Document,
        root: Element,
        invoice: EInvoice,
    ) {
        val transaction = elem(doc, root, "rsm:SupplyChainTradeTransaction")
        invoice.lines.forEach { writeLine(doc, transaction, it) }
        writeAgreement(doc, transaction, invoice)
        writeDelivery(doc, transaction, invoice)
        writeSettlement(doc, transaction, invoice)
    }

    private fun writeLine(
        doc: Document,
        transaction: Element,
        line: EInvoiceLine,
    ) {
        val item = elem(doc, transaction, "ram:IncludedSupplyChainTradeLineItem")

        val lineDocument = elem(doc, item, "ram:AssociatedDocumentLineDocument")
        text(doc, lineDocument, "ram:LineID", line.lineId)

        val product = elem(doc, item, "ram:SpecifiedTradeProduct")
        line.sellerItemId?.let { text(doc, product, "ram:SellerAssignedID", it) }
        line.buyerItemId?.let { text(doc, product, "ram:BuyerAssignedID", it) }
        text(doc, product, "ram:Name", line.itemName)
        line.itemDescription?.let { text(doc, product, "ram:Description", it) }
        line.itemStandardId?.let {
            val classification = elem(doc, product, "ram:DesignatedProductClassification")
            text(doc, classification, "ram:ClassCode", it)
        }

        val agreement = elem(doc, item, "ram:SpecifiedLineTradeAgreement")
        val price = elem(doc, agreement, "ram:NetPriceProductTradePrice")
        text(doc, price, "ram:ChargeAmount", line.netUnitPrice.toPlainString())

        val delivery = elem(doc, item, "ram:SpecifiedLineTradeDelivery")
        val quantity = text(doc, delivery, "ram:BilledQuantity", line.quantity.toPlainString())
        quantity.setAttribute("unitCode", line.unitCode)

        val settlement = elem(doc, item, "ram:SpecifiedLineTradeSettlement")
        val tax = elem(doc, settlement, "ram:ApplicableTradeTax")
        text(doc, tax, "ram:TypeCode", "VAT")
        text(doc, tax, "ram:CategoryCode", line.vatCategoryCode)
        text(doc, tax, "ram:RateApplicablePercent", line.vatRate.toPlainString())
        val summation = elem(doc, settlement, "ram:SpecifiedTradeSettlementLineMonetarySummation")
        text(doc, summation, "ram:LineTotalAmount", amount(line.lineNetAmount, "BT-131"))
    }

    private fun writeAgreement(
        doc: Document,
        transaction: Element,
        invoice: EInvoice,
    ) {
        val agreement = elem(doc, transaction, "ram:ApplicableHeaderTradeAgreement")
        invoice.buyerReference?.let { text(doc, agreement, "ram:BuyerReference", it) }
        writeParty(doc, agreement, "ram:SellerTradeParty", invoice.seller)
        invoice.buyer?.let { writeParty(doc, agreement, "ram:BuyerTradeParty", it) }
    }

    private fun writeParty(
        doc: Document,
        parent: Element,
        qname: String,
        party: EInvoiceParty,
    ) {
        val partyElement = elem(doc, parent, qname)
        text(doc, partyElement, "ram:Name", party.name)
        if (party.street != null || party.zip != null || party.city != null || party.country != null) {
            val address = elem(doc, partyElement, "ram:PostalTradeAddress")
            party.zip?.let { text(doc, address, "ram:PostcodeCode", it) }
            party.street?.let { text(doc, address, "ram:LineOne", it) }
            party.city?.let { text(doc, address, "ram:CityName", it) }
            party.country?.let { text(doc, address, "ram:CountryID", countryCode(it)) }
        }
        party.vatId?.let { writeTaxRegistration(doc, partyElement, "VA", it) }
        party.taxNumber?.let { writeTaxRegistration(doc, partyElement, "FC", it) }
    }

    private fun writeTaxRegistration(
        doc: Document,
        party: Element,
        schemeId: String,
        value: String,
    ) {
        val registration = elem(doc, party, "ram:SpecifiedTaxRegistration")
        val id = text(doc, registration, "ram:ID", value)
        id.setAttribute("schemeID", schemeId)
    }

    private fun writeDelivery(
        doc: Document,
        transaction: Element,
        invoice: EInvoice,
    ) {
        val delivery = elem(doc, transaction, "ram:ApplicableHeaderTradeDelivery")
        invoice.deliveryDate?.let {
            val event = elem(doc, delivery, "ram:ActualDeliverySupplyChainEvent")
            val occurrence = elem(doc, event, "ram:OccurrenceDateTime")
            writeDate(doc, occurrence, it)
        }
    }

    private fun writeSettlement(
        doc: Document,
        transaction: Element,
        invoice: EInvoice,
    ) {
        val settlement = elem(doc, transaction, "ram:ApplicableHeaderTradeSettlement")
        invoice.paymentReference?.let { text(doc, settlement, "ram:PaymentReference", it) }
        text(doc, settlement, "ram:InvoiceCurrencyCode", invoice.currency)
        invoice.vatBreakdown.forEach { writeVatGroup(doc, settlement, it) }
        // CII sequence: SpecifiedTradeAllowanceCharge follows ApplicableTradeTax (and the absent
        // BillingSpecifiedPeriod) and precedes SpecifiedTradePaymentTerms.
        invoice.allowancesCharges.forEach { writeAllowanceCharge(doc, settlement, it) }
        writePaymentTerms(doc, settlement, invoice)
        writeTotals(doc, settlement, invoice)
    }

    private fun writeVatGroup(
        doc: Document,
        settlement: Element,
        group: EInvoiceVatBreakdown,
    ) {
        val tax = elem(doc, settlement, "ram:ApplicableTradeTax")
        text(doc, tax, "ram:CalculatedAmount", amount(group.vatAmount, "BT-117"))
        text(doc, tax, "ram:TypeCode", "VAT")
        group.exemptionReasonText?.let { text(doc, tax, "ram:ExemptionReason", it) }
        text(doc, tax, "ram:BasisAmount", amount(group.taxableAmount, "BT-116"))
        text(doc, tax, "ram:CategoryCode", group.categoryCode)
        group.exemptionReasonCode?.let { text(doc, tax, "ram:ExemptionReasonCode", it) }
        text(doc, tax, "ram:RateApplicablePercent", group.rate.toPlainString())
    }

    /**
     * BG-20 / BG-21 in the `ram:TradeAllowanceChargeType` sequence: ChargeIndicator, CalculationPercent
     * (BT-94/101), BasisAmount (BT-93/100), ActualAmount (BT-92/99), ReasonCode (BT-98/105), Reason
     * (BT-97/104), CategoryTradeTax (BT-95/102, BT-96/103).
     */
    private fun writeAllowanceCharge(
        doc: Document,
        settlement: Element,
        allowance: EInvoiceAllowanceCharge,
    ) {
        val item = elem(doc, settlement, "ram:SpecifiedTradeAllowanceCharge")
        val indicator = elem(doc, item, "ram:ChargeIndicator")
        text(doc, indicator, "udt:Indicator", allowance.isCharge.toString())
        allowance.percentage?.let { text(doc, item, "ram:CalculationPercent", it.toPlainString()) }
        val term = if (allowance.isCharge) "BT-99" else "BT-92"
        allowance.baseAmount?.let { text(doc, item, "ram:BasisAmount", amount(it, if (allowance.isCharge) "BT-100" else "BT-93")) }
        text(doc, item, "ram:ActualAmount", amount(allowance.amount, term))
        allowance.reasonCode?.let { text(doc, item, "ram:ReasonCode", it) }
        allowance.reasonText?.let { text(doc, item, "ram:Reason", it) }
        val tax = elem(doc, item, "ram:CategoryTradeTax")
        text(doc, tax, "ram:TypeCode", "VAT")
        text(doc, tax, "ram:CategoryCode", allowance.vatCategoryCode)
        text(doc, tax, "ram:RateApplicablePercent", allowance.vatRate.toPlainString())
    }

    private fun writePaymentTerms(
        doc: Document,
        settlement: Element,
        invoice: EInvoice,
    ) {
        if (invoice.paymentTermsText == null && invoice.dueDate == null) return
        val terms = elem(doc, settlement, "ram:SpecifiedTradePaymentTerms")
        invoice.paymentTermsText?.let { text(doc, terms, "ram:Description", it) }
        invoice.dueDate?.let {
            val dueDate = elem(doc, terms, "ram:DueDateDateTime")
            writeDate(doc, dueDate, it)
        }
    }

    private fun writeTotals(
        doc: Document,
        settlement: Element,
        invoice: EInvoice,
    ) {
        val totals = elem(doc, settlement, "ram:SpecifiedTradeSettlementHeaderMonetarySummation")
        invoice.lineNetTotal?.let { text(doc, totals, "ram:LineTotalAmount", amount(it, "BT-106")) }
        // CII sequence: ChargeTotalAmount precedes AllowanceTotalAmount; both before TaxBasisTotalAmount.
        invoice.chargeTotal?.let { text(doc, totals, "ram:ChargeTotalAmount", amount(it, "BT-108")) }
        invoice.allowanceTotal?.let { text(doc, totals, "ram:AllowanceTotalAmount", amount(it, "BT-107")) }
        invoice.taxExclusiveTotal?.let { text(doc, totals, "ram:TaxBasisTotalAmount", amount(it, "BT-109")) }
        invoice.taxTotal?.let {
            val taxTotal = text(doc, totals, "ram:TaxTotalAmount", amount(it, "BT-110"))
            taxTotal.setAttribute("currencyID", invoice.currency)
        }
        // CII sequence: RoundingAmount sits between TaxTotalAmount and GrandTotalAmount.
        invoice.roundingAmount?.let { text(doc, totals, "ram:RoundingAmount", amount(it, "BT-114")) }
        invoice.taxInclusiveTotal?.let { text(doc, totals, "ram:GrandTotalAmount", amount(it, "BT-112")) }
        invoice.prepaidAmount?.let { text(doc, totals, "ram:TotalPrepaidAmount", amount(it, "BT-113")) }
        invoice.amountDue?.let { text(doc, totals, "ram:DuePayableAmount", amount(it, "BT-115")) }
    }

    private fun writeDate(
        doc: Document,
        parent: Element,
        date: LocalDate,
    ) {
        val dateTime = text(doc, parent, "udt:DateTimeString", formatCcyymmdd(date))
        dateTime.setAttribute("format", DATE_FORMAT_CCYYMMDD)
    }

    /** `CCYYMMDD`, the lexical form [CiiInvoiceMapper] parses for `format="102"`. */
    private fun formatCcyymmdd(date: LocalDate): String =
        buildString {
            append(date.year.toString().padStart(4, '0'))
            @Suppress("DEPRECATION")
            append(date.monthNumber.toString().padStart(2, '0'))
            @Suppress("DEPRECATION")
            append(date.dayOfMonth.toString().padStart(2, '0'))
        }

    /**
     * A BR-DEC monetary amount: two decimals exactly. `100.0000` is written `100.00`; `8.4034` throws,
     * because rounding it here could silently break a sum the caller reconciled.
     */
    private fun amount(
        value: BigDecimal,
        term: String,
    ): String {
        require(value.stripTrailingZeros().scale() <= MONEY_SCALE) {
            "$term ${value.toPlainString()} has more than $MONEY_SCALE decimals; EN 16931 BR-DEC allows $MONEY_SCALE"
        }
        return value.setScale(MONEY_SCALE).toPlainString()
    }

    /** BT-40/BT-55: an ISO 3166-1 alpha-2 code (BR-CL-14). A free-text name like "Germany" is refused. */
    private fun countryCode(value: String): String {
        require(ISO_ALPHA_2.matches(value)) { "CountryID must be an ISO 3166-1 alpha-2 code such as DE, got '$value'" }
        return value
    }

    /** Creates a prefixed element in the namespace its prefix binds to, appends it, and returns it. */
    private fun elem(
        doc: Document,
        parent: Element,
        qname: String,
    ): Element {
        val element = doc.createElementNS(namespaceOf(qname), qname)
        parent.appendChild(element)
        return element
    }

    private fun text(
        doc: Document,
        parent: Element,
        qname: String,
        value: String,
    ): Element {
        val element = elem(doc, parent, qname)
        element.textContent = value
        return element
    }

    private fun namespaceOf(qname: String): String =
        when (qname.substringBefore(':')) {
            "rsm" -> XmlNamespaces.RSM
            "ram" -> XmlNamespaces.RAM
            "udt" -> XmlNamespaces.UDT
            else -> throw IllegalArgumentException("Unknown namespace prefix in \"$qname\"")
        }

    private const val XMLNS = "http://www.w3.org/2000/xmlns/"

    private const val MONEY_SCALE = 2

    private val ISO_ALPHA_2 = Regex("^[A-Z]{2}$")
}
