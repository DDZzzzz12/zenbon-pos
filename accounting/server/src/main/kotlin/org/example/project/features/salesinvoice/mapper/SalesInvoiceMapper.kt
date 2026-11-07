package org.example.project.features.salesinvoice.mapper

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.utils.formatter.toExactDecimalString

/**
 * Entity → wire. **Requires an ambient transaction**: the line and VAT collections are lazy.
 *
 * Every amount goes out through [toExactDecimalString], never `.toDouble()` and never the scale-2
 * [org.example.project.utils.formatter.toDecimalString]: these columns are `DECIMAL(19,4)` and
 * pinning scale 2 on the wire would discard sub-cent precision the database holds — the defect the
 * `:platform-money` rules exist to prevent. The client parses to a `Decimal` and formats for display.
 */
fun SalesInvoice.toDto(): SalesInvoiceDto.ResponseDto =
    SalesInvoiceDto.ResponseDto(
        id = id.value,
        invoiceNumber = invoiceNumber,
        status = status.name,
        revenueOrigin = revenueOrigin.name,
        invoiceTypeCode = invoiceTypeCode,
        precedingInvoiceId = precedingSalesInvoice?.id?.value,
        billId = billId?.value,
        debtorId = debtor?.id?.value,
        buyerName = buyerName,
        buyerVatId = buyerVatId,
        buyerStreet = buyerStreet,
        buyerZip = buyerZip,
        buyerCity = buyerCity,
        buyerCountry = buyerCountry,
        buyerEmail = buyerEmail,
        sellerName = sellerName,
        sellerVatId = sellerVatId,
        sellerTaxNumber = sellerTaxNumber,
        issueDate = issueDate.toString(),
        serviceDate = serviceDate.toString(),
        dueDate = dueDate?.toString(),
        currency = currency,
        lineNetTotal = lineNetTotal?.toExactDecimalString(),
        taxTotal = taxTotal?.toExactDecimalString(),
        taxInclusiveTotal = taxInclusiveTotal?.toExactDecimalString(),
        prepaidAmount = prepaidAmount?.toExactDecimalString(),
        amountDue = amountDue?.toExactDecimalString(),
        openAmount = openAmount?.toExactDecimalString(),
        notes = notes,
        paymentTermsText = paymentTermsText,
        // The booking row is the decisions layer: the editor reads the revenue account and BU back
        // from here, not from the debtor — master data is editable, what 4d will post is not.
        revenueAccountNo = booking.firstOrNull()?.revenueAccountNo,
        buCode = booking.firstOrNull()?.buCode,
        documentId = documentId?.value,
        issuedAt = issuedAt?.toString(),
        cancelledAt = cancelledAt?.toString(),
        lines =
            lines.sortedBy { it.lineNo }.map {
                SalesInvoiceDto.LineDto(
                    lineNo = it.lineNo,
                    itemName = it.itemName,
                    itemDescription = it.itemDescription,
                    quantity = it.quantity.toExactDecimalString(),
                    grossUnitPrice = it.grossUnitPrice.toExactDecimalString(),
                    netUnitPrice = it.netUnitPrice.toExactDecimalString(),
                    netAmount = it.lineNetAmount.toExactDecimalString(),
                    vatAmount = it.vatAmount.toExactDecimalString(),
                    grossAmount = it.lineGrossAmount.toExactDecimalString(),
                    vatRate = it.vatRate.toExactDecimalString(),
                )
            },
        vatBreakdown =
            vatBreakdown.map {
                SalesInvoiceDto.VatRowDto(
                    vatRate = it.vatRate.toExactDecimalString(),
                    vatCategoryCode = it.vatCategoryCode,
                    taxableAmount = it.taxableAmount.toExactDecimalString(),
                    vatAmount = it.vatAmount.toExactDecimalString(),
                )
            },
        taxExclusiveTotal = taxExclusiveTotal?.toExactDecimalString(),
        allowanceTotal = allowanceTotal?.toExactDecimalString(),
        allowances =
            allowanceCharges.sortedBy { it.position }.map {
                SalesInvoiceDto.AllowanceChargeDto(
                    position = it.position,
                    isCharge = it.isCharge,
                    amount = it.amount.toExactDecimalString(),
                    vatAmount = it.vatAmount.toExactDecimalString(),
                    grossAmount = it.grossAmount.toExactDecimalString(),
                    vatCategoryCode = it.vatCategoryCode,
                    vatRate = it.vatRate.toExactDecimalString(),
                    reason = it.reasonText,
                    reasonCode = it.reasonCode,
                )
            },
    )

/** The list projection: no lines, no VAT rows — the list never shows them and they cost a query each. */
fun SalesInvoice.toListItemDto(): SalesInvoiceDto.ListItemDto =
    SalesInvoiceDto.ListItemDto(
        id = id.value,
        invoiceNumber = invoiceNumber,
        status = status.name,
        invoiceTypeCode = invoiceTypeCode,
        issueDate = issueDate.toString(),
        buyerName = buyerName,
        currency = currency,
        taxInclusiveTotal = taxInclusiveTotal?.toExactDecimalString(),
        openAmount = openAmount?.toExactDecimalString(),
        hasDocument = documentId != null,
    )
