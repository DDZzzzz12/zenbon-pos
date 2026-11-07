package org.example.project.admin.features.documents.presentation.capture

/**
 * The capture form as flat strings, mirroring `DocumentMetadataForm`'s shape and for the same reason:
 * every field defaults to `""` so a `null` from the server and a field the user typed into and then
 * cleared settle on the same value, and dirty-tracking never gets stuck comparing `null` to `""`.
 *
 * Only the fields the operator actually enters are here. Everything else the normalized model holds —
 * the seller address block, the VAT id, the subtotals, the payment terms — is **not** on the form and
 * is carried forward from the loaded aggregate by [toContentRequest]. Money stays a `String` the whole
 * way; it is parsed to a `:platform-money` `Decimal` for arithmetic and canonicalized at the wire boundary, and
 * never passes through a `Double`.
 *
 * [invoiceTypeCode] is the raw UNTDID 1001 code rather than an enum so a parsed invoice carrying some
 * other code (`389`, say) round-trips unchanged; the selector offers only 380/381/384, per the spec.
 */
internal data class InvoiceCaptureForm(
    val invoiceNumber: String = "",
    val issueDate: String = "",
    val grossTotal: String = "",
    val sellerName: String = "",
    val invoiceTypeCode: String = "380",
    val precedingInvoiceReference: String = "",
    val vatRows: List<VatRowForm> = listOf(VatRowForm()),
    val bpAccountNo: String = "",
    val accountNo: String = "",
    val bookingText: String = "",
) {
    /**
     * The gross attributable to row [index].
     *
     * A single-rate invoice takes the whole [grossTotal] — the operator types the amount once, and
     * `Σ(row gross) == grossTotal` then holds by construction rather than by validation. With two or
     * more rows the operator states the gross per rate and the sum rule becomes a real check
     * (`InvoiceCaptureField.VAT_ROW_SUM`), because DATEV rejects the entire import on a mismatch.
     */
    fun grossFor(index: Int): String = if (vatRows.size == 1) grossTotal else vatRows.getOrNull(index)?.gross.orEmpty()

    /** The three optional booking fields, as the dirty-scope comparison uses them. */
    val booking: Triple<String, String, String> get() = Triple(bpAccountNo, accountNo, bookingText)

    /**
     * Everything except booking — the scope that routes to the content endpoint. Compared field by field
     * with plain `==` on strings and an index loop over the rows: no data-class `equals`, no `List.equals`,
     * no `copy(...) == copy(...)`. The dirty check runs from the leave guard and the footer on every load, and
     * a structural `equals` over the row list was the suspect of a Kotlin/Wasm null-dereference on a stored
     * invoice (hardening; the exact cause was found by bisecting, not by reading).
     */
    fun contentEquals(other: InvoiceCaptureForm): Boolean =
        invoiceNumber == other.invoiceNumber &&
            issueDate == other.issueDate &&
            grossTotal == other.grossTotal &&
            sellerName == other.sellerName &&
            invoiceTypeCode == other.invoiceTypeCode &&
            precedingInvoiceReference == other.precedingInvoiceReference &&
            sameRows(other)

    /** The three booking strings, compared directly. */
    fun bookingEquals(other: InvoiceCaptureForm): Boolean = bpAccountNo == other.bpAccountNo && accountNo == other.accountNo && bookingText == other.bookingText

    /** Every field equal: content and booking. This is what "not dirty" means. */
    fun sameAs(other: InvoiceCaptureForm): Boolean = contentEquals(other) && bookingEquals(other)

    private fun sameRows(other: InvoiceCaptureForm): Boolean {
        if (vatRows.size != other.vatRows.size) return false
        for (index in vatRows.indices) {
            val mine = vatRows[index]
            val theirs = other.vatRows[index]
            if (mine.rate != theirs.rate || mine.gross != theirs.gross || mine.description != theirs.description) return false
        }
        return true
    }
}
