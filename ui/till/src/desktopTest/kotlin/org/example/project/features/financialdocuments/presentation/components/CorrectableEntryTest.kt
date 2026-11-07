package org.example.project.features.financialdocuments.presentation.components

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's "Korrektur" button must be offered only where `KassenbuchServiceImpl.createCorrection`
 * accepts the request (T5-5). The till used to offer it on a Geldtransit leg, which the server
 * refuses with 422 because the transfer has to be reversed as a whole.
 */
class CorrectableEntryTest {
    @Test
    fun `an ordinary entry is correctable`() {
        assertTrue(isCorrectableEntry(entry()))
    }

    @Test
    fun `a locked entry stays correctable - the Storno lands in the current period`() {
        assertTrue(isCorrectableEntry(entry(isLocked = true)))
    }

    @Test
    fun `a corrected re-entry stays correctable - corrections chain`() {
        assertTrue(isCorrectableEntry(entry(correctionRole = CorrectionRole.RE_ENTRY)))
    }

    @Test
    fun `a Geldtransit leg is not correctable on its own`() {
        assertFalse(isCorrectableEntry(entry(transferId = "b3a1c9e0-transfer")))
    }

    @Test
    fun `a leg of a reversed transfer is not correctable either`() {
        // A transfer reversal carries its own fresh transferId — still a transfer leg.
        assertFalse(isCorrectableEntry(entry(transferId = "reversal-pair", correctionRole = CorrectionRole.REVERSAL)))
    }

    @Test
    fun `a Storno, an already reversed entry and the Z-Bon aggregate are not correctable`() {
        assertFalse(isCorrectableEntry(entry(correctionRole = CorrectionRole.REVERSAL)))
        assertFalse(isCorrectableEntry(entry(alreadyReversed = true)))
        assertFalse(isCorrectableEntry(entry(isZbonAggregate = true)))
    }

    private fun entry(
        transferId: String? = null,
        correctionRole: CorrectionRole? = null,
        alreadyReversed: Boolean = false,
        isZbonAggregate: Boolean = false,
        isLocked: Boolean = false,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        KassenbuchDto.KassenbuchGroupedDetailDto(
            headerId = 1L,
            belegNr = "KB-1",
            entryType = KassenbuchEntryType.CASH_SALE,
            belegtext = "Test",
            belegDatum = "01.01.2026",
            waehrung = "EUR",
            totalAmount = "90.0000",
            currentBalance = "90.0000",
            employeeNumber = "EMP001",
            isLocked = isLocked,
            createdAt = "2026-01-01T10:00:00",
            bu = null,
            gegenkonto = null,
            kost1 = null,
            kost2 = null,
            nachricht = null,
            tseTransactionId = null,
            tseSignature = null,
            tseTransactionNumber = null,
            tseSignedAt = null,
            lineItems = emptyList(),
            hasLinkedDocuments = false,
            linkedDocumentCount = 0,
            correctionRole = correctionRole,
            alreadyReversed = alreadyReversed,
            isZbonAggregate = isZbonAggregate,
            transferId = transferId,
        )
}
