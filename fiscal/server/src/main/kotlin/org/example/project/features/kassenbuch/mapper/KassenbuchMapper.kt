/**
 * Mapper functions for Kassenbuch entity and DTO conversions.
 *
 * Provides bidirectional conversion between DATEV-compliant kassenbuch entities
 * and DTOs with proper field mapping and data transformation for API responses.
 * Handles German accounting field formats and business rule validations.
 *
 * Supports header/line-item structure for multi-VAT receipts.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.mapper

import kotlinx.datetime.number
import org.example.project.core.domain.dto.document.DocumentSummaryDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.VatRateNormalizer
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatVorzBetragForDatev
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * File-level logger for the mapper's one non-obvious lookup, the transfer counter-leg resolution.
 * The mapper is a set of top-level functions, so there is no class to name it after.
 */
private val mapperLogger = LoggerFactory.getLogger("KassenbuchMapper")

/**
 * Converts a KassenbuchHeader entity to a KassenbuchEntryResponseDto.
 *
 * Use this for detailed views and export operations where all fields are needed.
 * Includes TSE (Technical Security Element) data for German fiscal compliance.
 * Uses the first line item for amount/VAT fields.
 *
 * @receiver KassenbuchHeader entity to convert
 * @return KassenbuchEntryResponseDto for API response
 */
fun KassenbuchHeader.toKassenbuchEntryResponseDto(): KassenbuchDto.KassenbuchEntryResponseDto {
    // Extract TSE data if available
    val tse = tseTransaction

    // Use first line item for legacy single-entry response
    val firstLineItem = lineItems.firstOrNull()

    return KassenbuchDto.KassenbuchEntryResponseDto(
        id = id.value,
        shopId = shop.id.value,
        entryType = entryType,
        currentBalance = currentBalance.toDecimalString(),
        // DATEV Fields
        waehrung = waehrung,
        vorzBetrag = firstLineItem?.vorzBetrag?.toDecimalString() ?: totalAmount.toDecimalString(),
        belegNr = belegNr,
        belegDatum = belegDatum,
        belegtext = belegtext,
        // Legacy single-entry projection: the FIRST line's rate stands in for the whole entry, which
        // is only ever right for a single-line entry. On a Z-Bon closing whose only line is the
        // non-taxable Trinkgeld line (a day with a tip and no taxable turnover) this reads "0" —
        // the placeholder rate that line carries so the DATEV Buchungsstapel export can resolve an
        // account for it at all (see the tip line in ZBonServiceImpl, task P1.3a). It is imprecise
        // for exactly the reason it exists: a non-taxable tip is not zero-RATED turnover. Display
        // only — every fiscal consumer reads the line items, not this field.
        uStSatz = firstLineItem?.ustSatz,
        bu = bu,
        gegenkonto = gegenkonto,
        kost1 = kost1,
        kost2 = kost2,
        kostmenge = firstLineItem?.kostmenge?.toString(),
        skonto = firstLineItem?.skonto?.toString(),
        nachricht = nachricht,
        isLocked = isLocked,
        // System Fields
        employeeNumber = employee.employeeNumber,
        createdAt = createdAt.toString(),
        // TSE Integration Fields (KassenSichV compliance)
        tseTransactionId = tse?.id?.value,
        tseSignature = tse?.signature,
        tseTransactionNumber = tse?.transactionNumber,
        tseSignedAt = tse?.endTime?.toString(),
        // DATEV Export Fields
        datevAmountString = formatVorzBetragForDatev(firstLineItem?.vorzBetrag ?: totalAmount),
        datevDateString = getDatevDateString(),
        formattedBookingText = getFormattedBookingText(),
        canBeExported = canBeExported(),
        datevExportLine = toDatevExportLines().firstOrNull() ?: "",
        lateBookingReason = lateBookingReason,
    )
}

/**
 * Converts a KassenbuchHeader entity to a grouped summary DTO.
 *
 * Use this for list/card displays where grouped multi-VAT entries are shown.
 *
 * Reads lazy Exposed references (`cashBook`, `employee`, `lineItems`) and must therefore be called
 * inside `dbQuery {}` / `transaction {}`.
 *
 * @receiver KassenbuchHeader entity to convert
 * @param currency Shop's configured ISO 4217 code, resolved once per request by the calling
 *   service. Required rather than defaulted so a mapper can never silently fall back to "EUR".
 * @return KassenbuchGroupedSummaryDto for list display
 */
fun KassenbuchHeader.toGroupedSummaryDto(currency: String): KassenbuchDto.KassenbuchGroupedSummaryDto {
    // Convert line items to DTOs
    val lineItemDtos =
        lineItems.map { lineItem ->
            KassenbuchDto.KassenbuchEntryLineItemDto(
                id = lineItem.id.value,
                vorzBetrag = lineItem.vorzBetrag.toDecimalString(),
                ustSatz = lineItem.ustSatz,
                belegtext = lineItem.getEffectiveBelegtext(),
                kind = lineItem.kind,
                paymentTypeName = lineItem.paymentType?.name,
            )
        }

    return KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = id.value,
        // Lazy Exposed reference — like employee/lineItems below, this requires the ambient
        // transaction every caller of this mapper already provides.
        cashBookId = cashBook.id.value,
        belegNr = belegNr,
        entryType = entryType,
        belegtext = belegtext,
        belegDatum = belegDatum,
        waehrung = waehrung,
        // toPlainString(), never toString(): BigDecimal.toString() emits scientific notation
        // for small values ("1E-7"), which Decimal.parse rejects by design.
        totalAmount = totalAmount.toPlainString(),
        lineItems = lineItemDtos,
        currentBalance = currentBalance.toPlainString(),
        employeeNumber = employee.employeeNumber,
        isLocked = isLocked,
        createdAt = createdAt.toString(),
        hasLinkedDocuments = hasLinkedDocument(),
        linkedDocumentCount = getLinkedDocumentCount(),
        currency = currency,
    )
}

/**
 * Converts a KassenbuchHeader entity to a full grouped detail DTO.
 *
 * Use this for dialog displays where complete DATEV and TSE fields are needed.
 *
 * @receiver KassenbuchHeader entity to convert
 * @param currency Shop's configured ISO 4217 code, resolved once per request by the calling
 *   service. Required rather than defaulted so a mapper can never silently fall back to "EUR".
 * @param linkedDocument The linked document's summary (`KassenbuchDocuments.linkedDocumentOf`), or null.
 *   Required for the same reason: a default would silently drop the attachment from the detail.
 * @return KassenbuchGroupedDetailDto for detail dialog
 */
fun KassenbuchHeader.toGroupedDetailDto(
    currency: String,
    linkedDocument: DocumentSummaryDto?,
    alreadyReversed: Boolean = false,
): KassenbuchDto.KassenbuchGroupedDetailDto {
    // Extract TSE data if available
    val tse = tseTransaction

    // Convert line items to detail DTOs
    val lineItemDtos =
        lineItems.map { lineItem ->
            lineItem.toLineItemDetailDto()
        }

    return KassenbuchDto.KassenbuchGroupedDetailDto(
        headerId = id.value,
        belegNr = belegNr,
        entryType = entryType,
        belegtext = belegtext,
        belegDatum = belegDatum,
        waehrung = waehrung,
        // toPlainString(), never toString() — see toGroupedSummaryDto.
        totalAmount = totalAmount.toPlainString(),
        currentBalance = currentBalance.toPlainString(),
        employeeNumber = employee.employeeNumber,
        isLocked = isLocked,
        createdAt = createdAt.toString(),
        // Full DATEV fields
        bu = bu,
        gegenkonto = gegenkonto,
        kost1 = kost1,
        kost2 = kost2,
        nachricht = nachricht,
        // TSE fields
        tseTransactionId = tse?.id?.value,
        tseSignature = tse?.signature,
        tseTransactionNumber = tse?.transactionNumber,
        tseSignedAt = tse?.endTime?.toString(),
        // Line items
        lineItems = lineItemDtos,
        // Linked documents
        hasLinkedDocuments = hasLinkedDocument(),
        linkedDocumentCount = getLinkedDocumentCount(),
        linkedDocument = linkedDocument,
        correctsHeaderId = correctsHeader?.id?.value,
        correctsBelegNr = correctsHeader?.belegNr,
        correctionRole = correctionRole,
        alreadyReversed = alreadyReversed,
        isZbonAggregate = isZbonAggregate,
        lateBookingReason = lateBookingReason,
        currency = currency,
        // Transfer link — see counterpartyHeaderIdOrNull for what null means here.
        transferId = transferId,
        counterpartyHeaderId = counterpartyHeaderIdOrNull(),
        counterpartyBookName = counterpartyBook?.name,
    )
}

/**
 * The id of the other leg of this header's transfer, or `null` when there is no unambiguous one.
 *
 * A transfer is written as exactly two headers sharing one `transfer_id`
 * (`CashBookTransferServiceImpl.createTransfer`), so the sibling set is expected to hold exactly one
 * row. Both degenerate cases collapse to `null` on purpose rather than picking a row:
 * - **none** — the partner leg is missing, which is a data anomaly, not a link the UI may invent;
 * - **more than one** — the id no longer identifies a single movement of money, and guessing which
 *   leg to open would present one arbitrary half of it as *the* counter-booking.
 *
 * Both are logged at WARN because either means the transfer pairing is broken, and neither should
 * ever be reached in a healthy ledger.
 *
 * Reads the DB, so it inherits the same constraint as the rest of [toGroupedDetailDto]: callers are
 * already inside `dbQuery`/`transaction`.
 */
private fun KassenbuchHeader.counterpartyHeaderIdOrNull(): Long? {
    val transfer = transferId ?: return null
    val selfId = id.value
    val siblingIds =
        KassenbuchHeader
            .find { KassenbuchHeaderTable.transferId eq transfer }
            .map { it.id.value }
            .filter { it != selfId }

    if (siblingIds.size != 1) {
        mapperLogger.warn(
            "Transfer {} on header {} has {} counter-leg(s) ({}); reporting no counter-booking",
            transfer,
            selfId,
            siblingIds.size,
            siblingIds,
        )
        return null
    }
    return siblingIds.single()
}

/**
 * Converts a KassenbuchLineItem entity to a detail DTO.
 *
 * Includes all per-line fields and the DATEV export line.
 *
 * @receiver KassenbuchLineItem entity to convert
 * @return KassenbuchLineItemDetailDto for detail display
 */
fun KassenbuchLineItem.toLineItemDetailDto(): KassenbuchDto.KassenbuchLineItemDetailDto =
    KassenbuchDto.KassenbuchLineItemDetailDto(
        id = id.value,
        lineNumber = lineNumber,
        vorzBetrag = vorzBetrag.toDecimalString(),
        ustSatz = ustSatz,
        belegtext = belegtext,
        kostmenge = kostmenge?.toString(),
        skonto = skonto?.toString(),
        subBalance = subBalance.toDecimalString(),
        kind = kind,
        paymentTypeName = paymentType?.name,
        datevExportLine = toDatevExportLine(),
    )

/**
 * Converts a CreateKassenbuchEntryRequestDto with a specific EntryLineDto to KassenbuchData.
 *
 * This function is used for creating header data with shared DATEV fields.
 *
 * @receiver CreateKassenbuchEntryRequestDto to convert
 * @param firstLine The first entry line (for total amount calculation)
 * @param belegNr The shared document number for all entries in the group
 * @return KassenbuchData for header creation
 */

/**
 * Parses a signed document-amount string and flips it to cash-flow sign (spec §1.3), exactly in
 * [BigDecimal] — no floating-point op touches the value that later reaches TSE signing. Returns the
 * signed amount as a plain decimal string, the wire/carrier representation.
 */
private fun KassenbuchDto.CreateKassenbuchEntryRequestDto.signedAmount(amount: String): String {
    val parsed = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
    return (if (entryType.isIncoming) parsed else parsed.negate()).toPlainString()
}

@Suppress("DefaultLocale")
fun KassenbuchDto.CreateKassenbuchEntryRequestDto.toKassenbuchEntryData(
    firstLine: KassenbuchDto.EntryLineDto,
    belegNr: String,
): KassenbuchDto.KassenbuchData {
    val effectiveBelegDatum =
        belegDatum ?: run {
            val now = currentTimeStamp()
            String.format("%02d.%02d.%04d", now.day, now.month.number, now.year)
        }

    return KassenbuchDto.KassenbuchData(
        entryType = entryType,
        waehrung = waehrung,
        belegNr = belegNr,
        // Document sign -> cash-flow sign (spec §1.3), computed exactly in Decimal.
        vorzBetrag = signedAmount(firstLine.amount),
        belegDatum = effectiveBelegDatum,
        belegtext = belegtext,
        uStSatz = firstLine.vatRate?.let { VatRateNormalizer.normalize(it) },
        bu = bu,
        gegenkonto = gegenkonto,
        kost1 = kost1,
        kost2 = kost2,
        kostmenge = kostmenge?.toDoubleOrNull(),
        skonto = skonto?.toDoubleOrNull(),
        nachricht = nachricht,
        cashBookId = cashBookId,
    )
}

/**
 * Converts an EntryLineDto to LineItemData.
 *
 * This function creates line item data containing only line-specific fields
 * (amount, VAT rate, kind). Used for multi-line entries where each line
 * represents a different VAT rate / line kind within the same receipt.
 *
 * @receiver CreateKassenbuchEntryRequestDto for entry type information
 * @param line The entry line to convert
 * @return LineItemData for repository line item creation
 */
fun KassenbuchDto.CreateKassenbuchEntryRequestDto.toLineItemData(line: KassenbuchDto.EntryLineDto): KassenbuchDto.LineItemData =
    KassenbuchDto.LineItemData(
        vorzBetrag = signedAmount(line.amount),
        // store the canonical rate so mapping lookups need no historical variants
        ustSatz = line.vatRate?.let { VatRateNormalizer.normalize(it) },
        belegtext = line.belegtext,
        // header-level Kostmenge/Skonto fan out only to STANDARD (goods) lines (spec §2)
        kostmenge = if (line.kind == KassenbuchLineKind.STANDARD) kostmenge?.toDoubleOrNull() else null,
        skonto = if (line.kind == KassenbuchLineKind.STANDARD) skonto?.toDoubleOrNull() else null,
        kind = line.kind,
    )
