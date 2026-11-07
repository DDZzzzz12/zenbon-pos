package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.formatGerman
import java.math.BigDecimal
import java.util.UUID

/**
 * The signed Kassendifferenz a drawer count books — ONE definition for the standalone count
 * (`KassenbuchServiceImpl.recordCashCount`) and the count booked inside the closing (F58,
 * [ClosingCashCountBooker]), so both book, document and sign the same entry.
 *
 * Signed, unlike the non-fiscal Kassensturz difference: a till difference IS an elektronische
 * Aufzeichnung under §146a. A matching drawer books nothing at all.
 */
internal object CashCountDifference {
    /**
     * The counted amount — 400 unless a non-negative decimal. A drawer cannot hold negative cash.
     */
    fun parseCounted(raw: String): BigDecimal {
        val counted =
            raw.toBigDecimalOrNull()
                ?: throw invalid("countedAmount is not a valid decimal: $raw")
        if (counted.signum() < 0) throw invalid("countedAmount must not be negative — a drawer cannot hold negative cash")
        return counted
    }

    /** CASH_SURPLUS for counted > expected, CASH_SHORTAGE below; call only for a non-zero [difference]. */
    fun entryType(difference: BigDecimal): KassenbuchEntryType =
        if (difference.signum() > 0) KassenbuchEntryType.CASH_SURPLUS else KassenbuchEntryType.CASH_SHORTAGE

    /** The Eigenbeleg's document id; persists a Document row, so it needs an ambient transaction. */
    suspend fun eigenbeleg(
        documents: KassenbuchDocuments,
        belegNr: String,
        belegDatum: String,
        difference: BigDecimal,
        description: String,
        operatorId: UUID,
    ): Long {
        val signPrefix = if (difference.signum() > 0) "+" else "-"
        return documents.createEigenbeleg(
            belegNr = belegNr,
            date = belegDatum,
            entryType = entryType(difference).displayName,
            amount = "$signPrefix${formatGerman(difference.abs())} EUR",
            description = description,
            employeeId = operatorId,
        )
    }

    fun request(
        difference: BigDecimal,
        belegDatum: String,
        description: String,
        documentId: Long,
    ): KassenbuchDto.CreateKassenbuchEntryRequestDto {
        val entryType = entryType(difference)
        return KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = entryType,
            // The Beleg total is validated positive; the entry TYPE carries the direction.
            lines =
                listOf(
                    KassenbuchDto.EntryLineDto(
                        kind = KassenbuchLineKind.STANDARD,
                        // STANDARD requires a rate, but a Kassendifferenz is not a supply. Harmless:
                        // KassenbuchTaxClassifier.effectiveVatRate returns ZERO for DifferenzSollIst
                        // regardless of what the line declares, because the GV_TYP is not rate-governed.
                        vatRate = "0",
                        amount = difference.abs().toPlainString(),
                        belegtext = null,
                    ),
                ),
            belegDatum = belegDatum,
            belegtext = "Kassenzählung: ${entryType.displayName}".take(60),
            nachricht = description.take(120),
            documentId = documentId,
        )
    }

    private fun invalid(detail: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
}
