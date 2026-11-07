package org.example.project.admin.features.documents.presentation.capture

/**
 * BT-3 invoice type code (UNTDID 1001), restricted to the three the capture form offers.
 *
 * UNTDID 1001 has dozens of codes; the rest do not occur in this workflow and a long select would
 * make the common case slower to enter. This is a **capture** restriction, not a storage constraint:
 * [fromCode] returns `null` for anything else so a parsed invoice carrying another code keeps it —
 * the form renders the raw code read-only rather than silently rewriting it to `380`.
 *
 * A Gutschrift booked as a Rechnung carries the wrong sign in the accountant's ledger, which is why
 * the distinction is recorded at all. Deriving that sign is explicitly out of scope: the export emits
 * positive amounts and the direction stays the operator's decision via BU-Schlüssel and account.
 */
internal enum class InvoiceTypeCode(
    val code: String,
) {
    RECHNUNG("380"),
    GUTSCHRIFT("381"),
    KORREKTUR("384"),
    ;

    /** True for the two codes that require a BT-25 reference to the invoice being corrected. */
    val requiresPrecedingReference: Boolean get() = this != RECHNUNG

    companion object {
        fun fromCode(code: String): InvoiceTypeCode? = entries.firstOrNull { it.code == code }
    }
}
