/**
 * Document Bank Statement Metadata Table Definition
 *
 * Stores bank statement-specific metadata extracted via OCR or manual entry.
 * Applicable to BANK_STATEMENT type documents only.
 *
 * Relationship: One-to-One with DocumentTable (required, unique)
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for bank statement-specific document metadata.
 *
 * Stores bank identification, statement period, account balances,
 * and extracted transaction lines.
 */
object DocumentBankStatementMetadataTable : LongIdTable("document_bank_statement_metadata", "id") {
    /** Reference to the parent document (one-to-one relationship) */
    val documentId = reference("pdf_document_id", DocumentTable).uniqueIndex()

    /** Bank name (e.g. Sparkasse, Commerzbank) */
    val bankName = varchar("bank_name", 255).nullable()

    /** BIC/SWIFT code */
    val bic = varchar("bic", 11).nullable()

    /** Account IBAN */
    val iban = varchar("iban", 34).nullable()

    /** Legacy account number */
    val accountNumber = varchar("account_number", 50).nullable()

    /** Statement number (Auszugsnummer) */
    val statementNumber = varchar("statement_number", 50).nullable()

    /** Statement date (Auszugsdatum) */
    val statementDate = datetime("statement_date").nullable()

    /** Period start date (Zeitraum von) */
    val periodStart = datetime("period_start").nullable()

    /** Period end date (Zeitraum bis) */
    val periodEnd = datetime("period_end").nullable()

    /** Opening balance (Anfangssaldo) */
    val openingBalance = decimal("opening_balance", 15, 2).nullable()

    /** Closing balance (Endsaldo/Schlusssaldo) */
    val closingBalance = decimal("closing_balance", 15, 2).nullable()

    /** Currency code (default EUR) */
    val currency = varchar("currency", 3).default("EUR")

    /** JSON array of extracted transaction lines */
    val transactionsJson = text("transactions_json").nullable()
}
