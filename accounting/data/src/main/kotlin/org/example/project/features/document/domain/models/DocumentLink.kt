/**
 * Document Link Entity - Exposed DAO
 *
 * Domain model representing a link between a document and a BankStatement
 * for payment reconciliation. Maps to the document_links table.
 *
 * Note: Kassenbuch / document links are now handled via direct FK on KassenbuchHeaderTable,
 * so this entity only manages Document / BankStatement relationships.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.DocumentLinkTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for document links (Document / BankStatement).
 *
 * Provides object-oriented access to document relationships
 * with audit tracking and referential integrity.
 *
 * Usage:
 * ```kotlin
 * val link = DocumentLink.new {
 *     document = someDocument
 *     bankStatementDocument = statement
 *     linkedBy = "EMP001"
 *     linkedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
 *     notes = "Bank payment for document INV-2025-001"
 * }
 * ```
 */
class DocumentLink(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<DocumentLink>(DocumentLinkTable)

    /** Document being linked. Required. */
    var document by Document referencedOn DocumentLinkTable.documentId

    /** Bank statement document being linked. Required. Type must be BANK_STATEMENT. */
    var bankStatementDocument by Document referencedOn DocumentLinkTable.bankStatementDocumentId

    /** Employee number who created the link */
    var linkedBy by DocumentLinkTable.linkedBy

    /** Timestamp when the link was created */
    var linkedAt by DocumentLinkTable.linkedAt

    /** Optional notes about why these documents are linked */
    var notes by DocumentLinkTable.notes

    /**
     * Validates that the link has correct document types.
     *
     * @return true if bank statement is a bank statement type
     */
    fun isValid(): Boolean = bankStatementDocument.isBankStatement()

    /**
     * Checks if any linked document has been deleted (soft delete).
     *
     * @return true if any linked document is inactive
     */
    fun hasDeletedDocuments(): Boolean = !document.isActive || !bankStatementDocument.isActive

    /**
     * Gets a human-readable description of this link.
     *
     * @return String describing the link with connected documents
     */
    fun getDescription(): String = "Document ${document.filename} ↔ Bank Statement ${bankStatementDocument.filename}"

    override fun toString(): String =
        "DocumentLink(id=$id, " +
            "document=${document.id.value}, " +
            "bankStatement=${bankStatementDocument.id.value}, " +
            "linkedBy=$linkedBy)"
}
