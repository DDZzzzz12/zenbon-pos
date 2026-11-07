package org.example.project.features.document.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.banking.model.BankStatement as ParsedBankStatement
import org.example.project.features.document.domain.models.BankStatement as StoredBankStatement

/**
 * Writes a parsed [ParsedBankStatement] into the normalized bank-statement model — the bridge
 * between the format-agnostic `:accounting-banking` parser and this project's persistence.
 *
 * The dependency runs one way only: this module may use `:accounting-banking`, never the reverse. The class
 * takes a repository and a model and nothing else — no Ktor, no Koin — so it can be exercised
 * directly from a test.
 *
 * The two `BankStatement` types share a simple name — the parsed model and the Exposed entity —
 * so both are imported under an alias here rather than one of them being spelled out fully at
 * every use.
 *
 * ### Amounts stay positive
 * Direction lives in the `credit_debit` column (`CREDIT` | `DEBIT`), matching ISO 20022's
 * `CdtDbtInd`. Nothing here negates a debit: a consumer that summed such a column would silently
 * subtract twice.
 *
 * ### Re-parsing replaces, it does not duplicate
 * Keyed on [DocumentRepository.getBankStatementBySourceDocument]: a statement already extracted
 * from the same source document is reused and its entries replaced. `source_document_id` is a
 * plain index, not unique, so without this a second parse of one upload would leave two statements
 * behind and every balance summed across them would double-count.
 *
 * ### Overlap is recorded, never resolved
 * A re-export covering a wider period is a different file against a different document, so neither
 * check above catches it. [StatementDuplicateMarker] runs last and records what repeats — it marks
 * and never removes, so a consumer must filter on `duplicate_of_transaction_id`.
 *
 * Requires an ambient transaction — it reads and writes entities directly, like the repository it
 * calls.
 */
class ParsedStatementWriter(
    private val repository: DocumentRepository,
) {
    private val duplicateMarker = StatementDuplicateMarker(repository)

    /**
     * Persists [statement] against [sourceDocumentId]. Returns the statement id, whether it was
     * created or replaced.
     */
    fun write(
        statement: ParsedBankStatement,
        sourceDocumentId: Long,
    ): Long {
        val existing = repository.getBankStatementBySourceDocument(sourceDocumentId)
        val statementId =
            existing?.also { applyHeader(it, statement) }?.id?.value
                ?: createHeader(statement, sourceDocumentId)

        repository.replaceTransactions(statementId, statement.transactions.map(::toRow))
        repository.getBankStatement(statementId)?.let { duplicateMarker.mark(it) }
        return statementId
    }

    /**
     * Kept adjacent to [applyHeader] so a term added to one is visibly missing from the other.
     * `sourceDocumentId` is identity rather than content and remains reachable only from here.
     */
    private fun createHeader(
        statement: ParsedBankStatement,
        sourceDocumentId: Long,
    ): Long =
        repository
            .createBankStatement(
                sourceDocumentId = sourceDocumentId,
                format = formatCode(statement.format),
                accountIban = statement.accountIban,
                accountBic = statement.accountBic,
                bankName = statement.bankName,
                accountNumber = statement.accountNumber,
                statementNumber = statement.statementNumber,
                legalSequenceNumber = statement.legalSequenceNumber,
                statementDate = statement.statementDate?.atStartOfDay(),
                periodStart = statement.periodStart?.atStartOfDay(),
                periodEnd = statement.periodEnd?.atStartOfDay(),
                openingBalance = statement.openingBalance,
                closingBalance = statement.closingBalance,
                currency = statement.currency,
            ).id
            .value

    /**
     * Replaces every parsed field on an existing row, including the nulls: the parse result is a
     * complete picture of the statement, so a term absent from a re-issued export is cleared rather
     * than left stale.
     */
    private fun applyHeader(
        entity: StoredBankStatement,
        statement: ParsedBankStatement,
    ) {
        entity.format = formatCode(statement.format)
        entity.accountIban = statement.accountIban
        entity.accountBic = statement.accountBic
        entity.bankName = statement.bankName
        entity.accountNumber = statement.accountNumber
        entity.statementNumber = statement.statementNumber
        entity.legalSequenceNumber = statement.legalSequenceNumber
        entity.statementDate = statement.statementDate?.atStartOfDay()
        entity.periodStart = statement.periodStart?.atStartOfDay()
        entity.periodEnd = statement.periodEnd?.atStartOfDay()
        entity.openingBalance = statement.openingBalance
        entity.closingBalance = statement.closingBalance
        entity.currency = statement.currency
    }

    /** The column's vocabulary, which is lower-case and not the enum's name. */
    private fun formatCode(format: BankStatementFormat): String =
        when (format) {
            BankStatementFormat.CAMT053 -> "camt053"
            BankStatementFormat.MT940 -> "mt940"
            BankStatementFormat.PDF -> "pdf"
            BankStatementFormat.MANUAL -> "manual"
        }

    private fun toRow(entry: BankTransaction) =
        DocumentRepository.BankTransactionRow(
            bookingDate = entry.bookingDate.atStartOfDay(),
            valueDate = entry.valueDate.atStartOfDay(),
            amount = entry.amount,
            currency = entry.currency,
            creditDebit = entry.creditDebit.name,
            status = entry.status,
            bankTxDomain = entry.bankTxDomain,
            bankTxFamily = entry.bankTxFamily,
            bankTxSubFamily = entry.bankTxSubFamily,
            accountServicerReference = entry.accountServicerReference,
            endToEndId = entry.endToEndId,
            mandateId = entry.mandateId,
            creditorId = entry.creditorId,
            counterpartyName = entry.counterpartyName,
            counterpartyIban = entry.counterpartyIban,
            counterpartyBic = entry.counterpartyBic,
            remittanceUnstructured = entry.remittanceUnstructured,
            remittanceStructuredRef = entry.remittanceStructuredRef,
            reversalIndicator = entry.reversalIndicator,
            rawEntry = entry.rawEntry,
        )

    /** The model carries dates as [LocalDate]; the columns are `datetime`, so midnight it is. */
    private fun LocalDate.atStartOfDay(): LocalDateTime = atTime(0, 0)
}
