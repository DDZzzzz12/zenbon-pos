/**
 * Kassenbuch link lookup for the document feature.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.service

import org.example.project.core.domain.dto.document.DocumentDto

/**
 * How the document feature learns about Kassenbuch entries that reference a document.
 *
 * Declared here, implemented by the Kassenbuch feature. The document mapper previously queried
 * `KassenbuchHeaderTable` directly, which made a document-feature file depend on another
 * feature's schema; this interface inverts that dependency.
 *
 * The single abstract member returns the linked entry (the FK is 1:1, so there is at most one);
 * [countLinks] is derived from it so the two can never disagree.
 *
 * Implementations run inside the caller's transaction — call them from within `dbQuery`.
 */
fun interface DocumentKassenbuchLinkResolver {
    /** The Kassenbuch entry referencing [documentId], or null when none does. */
    fun findLinkedKassenbuch(documentId: Long): DocumentDto.LinkedKassenbuchSummary?

    /** How many Kassenbuch entries reference this document. */
    fun countLinks(documentId: Long): Int = if (findLinkedKassenbuch(documentId) != null) 1 else 0
}
