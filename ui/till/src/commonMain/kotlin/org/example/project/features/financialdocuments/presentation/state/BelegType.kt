package org.example.project.features.financialdocuments.presentation.state

/**
 * Document type the user attaches to a Kassenbuch entry.
 *
 * Both options require uploading a file — auto-Eigenbeleg generation
 * was removed because every entry must reference an uploaded document.
 */
enum class BelegType {
    /** User uploads their own Eigenbeleg (receipt, Quittung, etc.). */
    UPLOAD_EIGENBELEG,

    /** User uploads an invoice; metadata may be filled manually or from a parsed e-invoice. */
    UPLOAD_INVOICE,
}
