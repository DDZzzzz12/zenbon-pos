/**
 * Kassenbuch-side implementation of the document feature's link lookup.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Resolves the Kassenbuch entry that references a document.
 *
 * The document mapper used to run this query itself; owning it here keeps the Kassenbuch schema
 * behind the Kassenbuch feature. `linked_document_id` is unique, so there is at most one match.
 *
 * Runs inside the caller's transaction — the caller is already inside `dbQuery`.
 */
class KassenbuchDocumentLinkResolver : DocumentKassenbuchLinkResolver {
    override fun findLinkedKassenbuch(documentId: Long): DocumentDto.LinkedKassenbuchSummary? =
        KassenbuchHeader
            .find { KassenbuchHeaderTable.linkedDocumentId eq documentId }
            .firstOrNull()
            ?.let { header ->
                DocumentDto.LinkedKassenbuchSummary(
                    headerId = header.id.value,
                    belegNr = header.belegNr,
                    belegtext = header.belegtext,
                    belegDatum = header.belegDatum,
                    totalAmount = header.totalAmount.toDecimalString(),
                )
            }
}
