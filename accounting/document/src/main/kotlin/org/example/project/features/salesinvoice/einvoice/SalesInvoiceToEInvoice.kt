package org.example.project.features.salesinvoice.einvoice

import org.example.project.core.utils.CountryCodeNormalizer
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceAllowanceCharge
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown

/**
 * Maps an issued [SalesInvoice] onto the syntax-neutral [EInvoice] model that [org.example.project
 * .einvoice.syntax.CiiInvoiceWriter] serializes.
 *
 * Pure: no DB access, no config, no PDF rendering — every value is read straight off the entities
 * the caller already loaded (mirrors [org.example.project.features.salesinvoice.domain.service
 * .SalesInvoicePdfData] / `SalesInvoiceServiceImpl.toPdfData()`, the PDF's equivalent read). The
 * seller block comes from the invoice's own `seller_*` snapshot columns, never live `shop_info` —
 * `sales_invoice` freezes them at issue time for exactly this reason (a shop that moves must not
 * rewrite an e-invoice issued two years ago). `shop_info` carries no seller-contact-email column, so
 * [EInvoiceParty.email] is left null on the seller side; the buyer side has its own `buyer_email`.
 *
 * Must run inside the caller's transaction: [SalesInvoice.precedingSalesInvoice] is a lazy Exposed
 * reference.
 */
object SalesInvoiceToEInvoice {
    fun map(
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
        allowanceCharges: List<SalesInvoiceAllowanceCharge> = invoice.allowanceCharges.toList(),
    ): EInvoice = EInvoiceCentRounding.apply(mapStored(invoice, lines, vatBreakdown, allowanceCharges))

    /** The invoice's stored values, unrounded — [EInvoiceCentRounding] then brings them to BR-DEC form. */
    private fun mapStored(
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
        allowanceCharges: List<SalesInvoiceAllowanceCharge>,
    ): EInvoice =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            // Nullable on the column (a DRAFT has no number yet); the mapper is only meaningful for
            // an issued invoice, where the number is always set.
            invoiceNumber = invoice.invoiceNumber.orEmpty(),
            issueDate = invoice.issueDate.date,
            currency = invoice.currency,
            seller = invoice.toSellerParty(),
            buyer = invoice.toBuyerParty(),
            vatBreakdown = vatBreakdown.map { it.toEInvoiceVatBreakdown() },
            lines = lines.map { it.toEInvoiceLine() },
            // BG-20/21: a promotion the till took off, as a document-level allowance (net, BT-92).
            allowancesCharges = allowanceCharges.sortedBy { it.position }.map { it.toEInvoiceAllowanceCharge() },
            dueDate = invoice.dueDate?.date,
            deliveryDate = invoice.serviceDate.date,
            paymentReference = invoice.paymentReference,
            paymentTermsText = invoice.paymentTermsText,
            // `380` commercial invoice, `381` credit note — carried verbatim, the column already
            // holds the UNTDID 1001 code the invoice was issued under.
            invoiceTypeCode = invoice.invoiceTypeCode,
            // Only meaningful for a 381/384; a 380 has no preceding invoice and this reads null.
            precedingInvoiceReference = invoice.precedingSalesInvoice?.invoiceNumber,
            lineNetTotal = invoice.lineNetTotal,
            allowanceTotal = invoice.allowanceTotal,
            chargeTotal = invoice.chargeTotal,
            taxExclusiveTotal = invoice.taxExclusiveTotal,
            taxTotal = invoice.taxTotal,
            taxInclusiveTotal = invoice.taxInclusiveTotal,
            amountDue = invoice.amountDue,
            prepaidAmount = invoice.prepaidAmount,
        )

    private fun SalesInvoice.toSellerParty() =
        EInvoiceParty(
            name = sellerName.orEmpty(),
            vatId = sellerVatId,
            taxNumber = sellerTaxNumber,
            street = sellerStreet,
            zip = sellerZip,
            city = sellerCity,
            country = isoCountry(sellerCountry),
        )

    private fun SalesInvoice.toBuyerParty() =
        EInvoiceParty(
            name = buyerName,
            vatId = buyerVatId,
            street = buyerStreet,
            zip = buyerZip,
            city = buyerCity,
            country = isoCountry(buyerCountry),
            email = buyerEmail,
        )

    /**
     * BT-40/BT-55 must be an ISO 3166-1 alpha-2 code, while `shop_info.shop_country` is free text
     * ("Germany" by the setup wizard's default). A recognised name becomes its code; anything else is
     * carried verbatim so the pre-issue check can name it and refuse, rather than this mapper
     * guessing a country.
     */
    private fun isoCountry(raw: String?): String? = CountryCodeNormalizer.toIso2(raw) ?: raw?.trim()?.ifBlank { null }

    private fun SalesInvoiceLine.toEInvoiceLine() =
        EInvoiceLine(
            lineId = lineNo.toString(),
            itemName = itemName,
            itemDescription = itemDescription,
            quantity = quantity,
            unitCode = unitCode,
            netUnitPrice = netUnitPrice,
            lineNetAmount = lineNetAmount,
            vatCategoryCode = vatCategoryCode,
            vatRate = vatRate,
        )

    private fun SalesInvoiceAllowanceCharge.toEInvoiceAllowanceCharge() =
        EInvoiceAllowanceCharge(
            isCharge = isCharge,
            amount = amount,
            vatCategoryCode = vatCategoryCode,
            vatRate = vatRate,
            baseAmount = baseAmount,
            percentage = percentage,
            reasonCode = reasonCode,
            reasonText = reasonText,
        )

    private fun SalesInvoiceVatBreakdown.toEInvoiceVatBreakdown() =
        EInvoiceVatBreakdown(
            categoryCode = vatCategoryCode,
            rate = vatRate,
            taxableAmount = taxableAmount,
            vatAmount = vatAmount,
            exemptionReasonText = exemptionReasonText,
        )
}
