/**
 * Document Link Table Definition
 *
 * Database table for managing relationships between documents and BankStatement documents
 * for payment reconciliation. Both document references are required.
 *
 * Storage Architecture:
 * - Links connect: Document / BankStatement
 * - Both document references are required (non-nullable)
 * - Unique constraint prevents duplicate links between same document pair
 * - Soft delete pattern via deletion in linked documents (isActive check)
 * - Audit trail via linkedBy and linkedAt fields
 *
 * Note: Kassenbuch / document links are handled via direct FK on KassenbuchHeaderTable.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for document links.
 *
 * Links documents to BankStatement documents for payment reconciliation.
 * Includes audit fields and performance indexes for common query patterns.
 */
object DocumentLinkTable : LongIdTable("document_links", "id") {
    /** Reference to the document being linked. Required. */
    val documentId = reference("document_id", DocumentTable)

    /** Reference to the bank statement document (DocumentType.BANK_STATEMENT). Required. */
    val bankStatementDocumentId = reference("bank_statement_document_id", DocumentTable)

    /** Employee number who created the link */
    val linkedBy = varchar("linked_by", 100)

    /** Timestamp when the link was created */
    val linkedAt = datetime("linked_at")

    /** Optional notes about why these documents are linked */
    val notes = text("notes").nullable()

    init {
        // Prevent duplicate links between document and bank statement
        uniqueIndex(documentId, bankStatementDocumentId)

        // Performance indexes for common query patterns
        index(false, documentId)
        index(false, bankStatementDocumentId)
        index(false, linkedBy)
        index(false, linkedAt)
    }
}
