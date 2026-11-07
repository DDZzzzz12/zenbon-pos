package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.utils.formatter.formatGerman
import java.math.BigDecimal
import java.util.UUID

/**
 * The Kassenbuch entry an employee-tip payout books, and its Eigenbeleg — ONE definition for the
 * payout after a closing (`KassenbuchServiceImpl.payOutEmployeeTip`) and the payout inside a closing
 * (F58, [ClosingTipPayoutBooker]), so the two book, document and sign the same thing.
 *
 * A CASH_WITHDRAWAL out of the FISCAL book (cashBookId = null), signed like any other withdrawal.
 * Its one line is EMPLOYEE_TIP, which is what makes it TrinkgeldAN (key 5) in DSFinV-K and books it
 * on the employee-tip DATEV account rather than the Barentnahme one. No VAT rate: a tip is not a
 * supply, and EMPLOYEE_TIP does not require one.
 */
internal object EmployeeTipPayoutEntry {
    val entryType = KassenbuchEntryType.CASH_WITHDRAWAL

    /** The only line kind the payout's request carries — passed as the server-internal kind set. */
    val systemLineKinds = setOf(KassenbuchLineKind.EMPLOYEE_TIP)

    fun belegtext(recipientName: String): String = "TA:Trinkgeld-Auszahlung $recipientName".take(60)

    /** The Eigenbeleg's document id; persists a Document row, so it needs an ambient transaction. */
    suspend fun eigenbeleg(
        documents: KassenbuchDocuments,
        belegNr: String,
        belegDatum: String,
        amount: BigDecimal,
        description: String,
        operatorId: UUID,
    ): Long =
        documents.createEigenbeleg(
            belegNr = belegNr,
            date = belegDatum,
            entryType = "Trinkgeld-Auszahlung",
            amount = "-${formatGerman(amount)} EUR",
            description = description,
            employeeId = operatorId,
        )

    fun request(
        amount: BigDecimal,
        recipientName: String,
        belegDatum: String,
        description: String,
        documentId: Long,
    ): KassenbuchDto.CreateKassenbuchEntryRequestDto =
        KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = entryType,
            lines =
                listOf(
                    KassenbuchDto.EntryLineDto(
                        kind = KassenbuchLineKind.EMPLOYEE_TIP,
                        vatRate = null,
                        amount = amount.toPlainString(),
                        belegtext = null,
                    ),
                ),
            belegDatum = belegDatum,
            belegtext = belegtext(recipientName),
            nachricht = description.take(120),
            documentId = documentId,
        )
}
