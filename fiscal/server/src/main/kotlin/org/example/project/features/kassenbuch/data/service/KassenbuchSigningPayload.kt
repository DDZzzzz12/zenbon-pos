package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.utils.builder.tse.KassenbuchProcessDataBuilder
import org.example.project.utils.formatter.toBigDecimal

/**
 * What a single (non-aggregate) Kassenbuch entry signs on the TSE — the processType and processData
 * pair. Extracted from `KassenbuchServiceImpl.createSignedEntry` so the in-closing tip payout (F58),
 * booked inside the Z-Bon's transaction, signs byte-identically to the payout booked through the
 * ordinary path: two copies of KassenSichV-signed logic would drift apart.
 */
internal object KassenbuchSigningPayload {
    data class Payload(
        val processType: String,
        val processData: String,
    )

    /**
     * Round-3 TSER-2: a cash-moving entry (BON_TYP „Beleg") entspricht einem Kassenbeleg and signs as
     * Kassenbeleg-V1 (Beleg^<buckets>^<payments>), not the proprietary SonstigerVorgang string.
     * Balance snapshots (OPENING/CLOSING → AVSonstige) legitimately stay SonstigerVorgang.
     *
     * Round-4 TSER-1 (Anhang I p.119/120): the Bar tender is the SIGNED cash flow, summed over the SAME
     * signed [lineItems] the buckets use, so „die Summen der Umsätze [stimmen] mit denen der Zahlungen
     * überein" (p.116) with zero rounding drift. Round-7 C1: [correctedEntryType] (a CORRECTION_*'s
     * corrected entry) decides each line's GV_TYP, which decides whether the declared rate governs.
     */
    fun of(
        headerData: KassenbuchDto.KassenbuchData,
        correctedEntryType: KassenbuchEntryType?,
        lineItems: List<KassenbuchDto.LineItemData>,
    ): Payload {
        val total = lineItems.sumOf { toBigDecimal(it.vorzBetrag) }
        val vorgangstyp = KassenbuchDsfinvkMapping.getBonTyp(headerData.entryType)
        if (vorgangstyp == DsfinvkExportDto.ReceiptTypes.BELEG) {
            return Payload(
                processType = TseTransactionService.PROCESS_TYPE_RECEIPT,
                processData =
                    KassenbuchProcessDataBuilder.buildKassenbelegV1(
                        vorgangstyp = vorgangstyp,
                        entryType = headerData.entryType,
                        correctedEntryType = correctedEntryType,
                        lineItems = lineItems,
                        netCashAmount = total,
                        totalNonCashWithdrawals = null,
                    ),
            )
        }
        val referenceId = KassenbuchProcessDataBuilder.generateReferenceId(headerData.belegNr)
        return Payload(
            processType = TseTransactionService.PROCESS_TYPE_OTHER,
            processData =
                KassenbuchProcessDataBuilder.buildProcessData(
                    entryType = headerData.entryType,
                    description = "$referenceId-${headerData.belegtext}",
                    totalNonCashWithdrawals = null,
                    netCashAmount = total,
                    lineItems = lineItems,
                ),
        )
    }
}
