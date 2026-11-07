/**
 * Document Bank Statement Metadata Entity - Exposed DAO
 *
 * Domain model representing bank statement-specific metadata associated with a document.
 * Maps to the document_bank_statement_metadata table.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.DocumentBankStatementMetadataTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for bank statement-specific document metadata.
 *
 * Provides access to bank identification, statement period,
 * account balances, and extracted transaction data.
 */
class DocumentBankStatementMetadata(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<DocumentBankStatementMetadata>(DocumentBankStatementMetadataTable)

    /** Reference to the parent document */
    var document by Document referencedOn DocumentBankStatementMetadataTable.documentId

    /** Bank name */
    var bankName by DocumentBankStatementMetadataTable.bankName

    /** BIC/SWIFT code */
    var bic by DocumentBankStatementMetadataTable.bic

    /** Account IBAN */
    var iban by DocumentBankStatementMetadataTable.iban

    /** Legacy account number */
    var accountNumber by DocumentBankStatementMetadataTable.accountNumber

    /** Statement number (Auszugsnummer) */
    var statementNumber by DocumentBankStatementMetadataTable.statementNumber

    /** Statement date */
    var statementDate by DocumentBankStatementMetadataTable.statementDate

    /** Period start date */
    var periodStart by DocumentBankStatementMetadataTable.periodStart

    /** Period end date */
    var periodEnd by DocumentBankStatementMetadataTable.periodEnd

    /** Opening balance */
    var openingBalance by DocumentBankStatementMetadataTable.openingBalance

    /** Closing balance */
    var closingBalance by DocumentBankStatementMetadataTable.closingBalance

    /** Currency code */
    var currency by DocumentBankStatementMetadataTable.currency

    /** JSON array of extracted transaction lines */
    var transactionsJson by DocumentBankStatementMetadataTable.transactionsJson

    /**
     * Checks if this metadata has any meaningful data set.
     *
     * @return true if at least one field beyond currency is populated
     */
    fun hasData(): Boolean =
        bankName != null ||
            bic != null ||
            iban != null ||
            accountNumber != null ||
            statementNumber != null ||
            statementDate != null ||
            periodStart != null ||
            periodEnd != null ||
            openingBalance != null ||
            closingBalance != null

    /**
     * Formats a balance to a display string with 2 decimal places.
     *
     * @return Formatted closing balance string or null if not set
     */
    fun formatBalance(): String? = closingBalance?.setScale(2)?.toPlainString()

    override fun toString(): String = "DocumentBankStatementMetadata(id=$id, bank=$bankName, stmtNr=$statementNumber, closingBalance=$closingBalance)"
}
