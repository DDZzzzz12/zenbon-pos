package org.example.project.core.domain.enums.document

import kotlinx.serialization.Serializable

/**
 * The document's workflow state.
 *
 * Its initial value comes from the e-invoice parse on upload — `Success` → [COMPLETED] (only once
 * the invoice is actually stored), `NotConformant` → [UNPROCESSABLE], `Failure` → [FAILED], while
 * `NoEInvoiceFound` writes nothing because [NONE] is the column default and the normal scanned-
 * invoice case. Its later values come from human action: an operator entering the document by hand
 * ([IN_PROGRESS]) and then confirming it ([REVIEWED]).
 *
 * [PENDING] and [PROCESSING] are vestigial queue states from the removed asynchronous pipeline —
 * structured parsing is synchronous. They are kept for possible future asynchronous document types.
 */
@Serializable
enum class ProcessingStatus {
    NONE,
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNPROCESSABLE,

    /** Entry started by an operator; the fields the DATEV export needs are not all present yet. */
    IN_PROGRESS,

    /** An operator has confirmed the invoice. Ready for the Buchungsstapel. */
    REVIEWED,
}
