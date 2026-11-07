package org.example.project.features.document.domain.models

import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader

/**
 * The PDF document linked to a Kassenbuch entry, or null.
 *
 * An accounting extension rather than a member of [KassenbuchHeader]: the entry belongs to the ecr
 * area, documents to accounting above it, and a lower area may not reference a higher one. The entry
 * holds only the id ([KassenbuchHeader.linkedDocumentId]); this restores the entity navigation for
 * callers that may see accounting. Reading is a [Document.findById] (served from the entity cache like
 * the `optionalReferencedOn` it replaces); writing stores the document's id, flushing a pending
 * document first exactly as the reference delegate did.
 */
var KassenbuchHeader.linkedDocument: Document?
    get() = linkedDocumentId?.let { Document.findById(it) }
    set(value) {
        linkedDocumentId = value?.id?.value
    }

/** All documents linked to this entry as a set: the [linkedDocument], or empty. */
fun KassenbuchHeader.getLinkedDocuments(): Set<Document> = setOfNotNull(linkedDocument)
