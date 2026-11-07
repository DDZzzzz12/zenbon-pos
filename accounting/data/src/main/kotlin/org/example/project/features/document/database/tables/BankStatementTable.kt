package org.example.project.features.document.database.tables

import org.example.project.features.bank.database.tables.BankAccountTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A bank statement. The **root** — its entries live in [BankStatementTransactionTable].
 *
 * [sourceDocumentId] is nullable for the same reason [InvoiceTable]'s is: a statement can be
 * entered manually or arrive without a stored file. [format] records how it arrived.
 */
object BankStatementTable : LongIdTable("bank_statement", "id") {
    val sourceDocumentId = optReference("source_document_id", DocumentTable, onDelete = ReferenceOption.SET_NULL)

    /**
     * The resolved own-account. **Nullable, and NULL is normal**: `:accounting-banking` writes the statement on
     * upload before resolution has run, keeping the bank-asserted IBAN in [accountIban]. A NULL here
     * means "not yet assigned to an account" — a work item, not a defect.
     */
    val bankAccountId =
        optReference("bank_account_id", BankAccountTable, onDelete = ReferenceOption.SET_NULL)

    /** `camt053` | `mt940` | `pdf` | `manual`. */
    val format = varchar("format", 20).default("manual")

    val accountIban = varchar("account_iban", 34).nullable()
    val accountBic = varchar("account_bic", 11).nullable()
    val bankName = varchar("bank_name", 255).nullable()
    val accountNumber = varchar("account_number", 50).nullable()
    val statementNumber = varchar("statement_number", 50).nullable()
    val legalSequenceNumber = varchar("legal_sequence_number", 50).nullable()
    val statementDate = datetime("statement_date").nullable()
    val periodStart = datetime("period_start").nullable()
    val periodEnd = datetime("period_end").nullable()
    val openingBalance = decimal("opening_balance", 19, 4).nullable()
    val closingBalance = decimal("closing_balance", 19, 4).nullable()
    val currency = varchar("currency", 3).default("EUR")

    /**
     * The earliest already-stored statement for this account whose period overlaps this one. Null
     * when none overlaps.
     *
     * Overlapping statements are stored verbatim — nothing is merged or removed. This is a hint
     * recorded at ingest so a consumer can tell which rows are repeats without recomputing.
     */
    val overlappingStatementId =
        optReference("overlapping_statement_id", BankStatementTable, onDelete = ReferenceOption.SET_NULL)

    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_bank_statement_source_document_id", false, sourceDocumentId)
        index("idx_bank_statement_bank_account_id", false, bankAccountId)
        index("idx_bank_statement_account_iban", false, accountIban)
        index("idx_bank_statement_statement_date", false, statementDate)
        index("idx_bank_statement_overlapping_statement_id", false, overlappingStatementId)
    }
}
