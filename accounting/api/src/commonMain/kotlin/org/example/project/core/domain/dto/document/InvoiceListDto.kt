package org.example.project.core.domain.dto.document

import kotlinx.serialization.Serializable

/**
 * The Rechnungen list, spanning two populations.
 *
 * A scan with no e-invoice payload has no invoice row at all, so *Zu erfassen* is document-keyed
 * while the other buckets are invoice-keyed. Collapsing the two would hide exactly the work an
 * operator opened the screen to do.
 *
 * **[RowDto.invoiceId] is the discriminator**, not the presence of [RowDto.documentId]. An
 * uncaptured row has a document and no invoice; a captured row has an invoice and *may also* carry
 * the document it was extracted from, so the list can offer a PDF preview without a second call. A
 * bare XRechnung or a hand-entered invoice simply has no document to carry.
 */
object InvoiceListDto {
    /**
     * The list spans two independent questions, which is why the counts do **not** sum to a total.
     *
     * `ZU_ERFASSEN` / `ZU_PRUEFEN` / `FERTIG` are the **capture** state — has a human extracted and
     * checked this invoice's content. `OFFEN` / `UEBERFAELLIG` / `UEBERZAHLT` are the **payment**
     * state — is it still owed, is it late, and was too much paid against it. One invoice is
     * routinely in one of each, and `UEBERFAELLIG` is a narrowing of `OFFEN` rather than a bucket
     * beside it. Do not "fix" the overlap.
     *
     * `UEBERZAHLT` is the negative half of `OFFEN`, not a narrowing of it: the two are disjoint,
     * because a residual cannot be above and below zero at once. It exists because the residual is
     * deliberately allowed to go negative, and without a bucket for that an over-allocated invoice
     * failed every `> 0` filter and left the screen with nothing said.
     */
    @Serializable
    enum class Bucket { ZU_ERFASSEN, ZU_PRUEFEN, FERTIG, OFFEN, UEBERFAELLIG, UEBERZAHLT }

    @Serializable
    data class RowDto(
        val bucket: Bucket,
        val documentId: Long? = null,
        val invoiceId: Long? = null,
        val filename: String? = null,
        val supplierName: String? = null,
        val invoiceNumber: String? = null,
        val issueDate: String? = null,
        /** Gross total as a decimal string. Money never crosses the wire as a number. */
        val grossTotal: String? = null,
        val currency: String = "EUR",
        val eInvoiceFormat: String? = null,
        val dueDate: String? = null,
        /** Residual still owed, as a decimal string. Null when nothing is owed or nothing is known. */
        val openAmount: String? = null,
        /**
         * The invoice has been cancelled (its Storno exists). Set on the cancelled original only,
         * never on the correction; lets a row in any bucket say "storniert".
         */
        val cancelled: Boolean = false,
        /**
         * Open and past its due day (due date strictly before today) — the same predicate as the
         * `UEBERFAELLIG` bucket, computed for every captured row so an overdue invoice listed under
         * *Zu prüfen* is still marked.
         */
        val overdue: Boolean = false,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<RowDto>,
        val total: Long,
        val bucketCounts: Map<String, Long> = emptyMap(),
        /**
         * What the three **payment** buckets are worth, keyed by [Bucket] name — decimal strings,
         * since money never crosses the wire as a number.
         *
         * Only `OFFEN`, `UEBERFAELLIG` and `UEBERZAHLT`. The capture buckets are deliberately absent
         * rather than present as `"0.00"`: `ZU_ERFASSEN` holds documents nothing has been parsed out
         * of yet, so it has no amount at all, and a zero there would read as "there is nothing worth
         * capturing" instead of "this question does not apply".
         *
         * `UEBERZAHLT` is a positive magnitude, not the negative residual it is stored as.
         */
        val bucketSums: Map<String, String> = emptyMap(),
    )
}
