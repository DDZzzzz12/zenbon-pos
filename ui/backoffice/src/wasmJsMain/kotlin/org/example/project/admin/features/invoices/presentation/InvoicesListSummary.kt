package org.example.project.admin.features.invoices.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.ui.components.KpiSummaryItem
import org.example.project.ui.components.filter.pluralResultKey

/**
 * The capture axis: has a human extracted and checked this invoice's content.
 *
 * Listed explicitly rather than derived from the enum's order, so adding a bucket forces a decision
 * about which question it answers instead of silently joining whichever row it happens to fall in.
 */
internal val CAPTURE_BUCKETS =
    listOf(
        InvoiceListDto.Bucket.ZU_ERFASSEN,
        InvoiceListDto.Bucket.ZU_PRUEFEN,
        InvoiceListDto.Bucket.FERTIG,
    )

/** The payment axis: is it still owed, is it late, and was too much paid against it. */
internal val PAYMENT_BUCKETS =
    listOf(
        InvoiceListDto.Bucket.OFFEN,
        InvoiceListDto.Bucket.UEBERFAELLIG,
        InvoiceListDto.Bucket.UEBERZAHLT,
    )

/**
 * The result line's count: the server's exact count of the applied bucket (the figure its chip shows),
 * not the rows on the one 100-row page, so it is never weakened to an "N+" lower bound and is right
 * even while the previous bucket's rows are still on screen.
 */
internal fun invoicesResultCount(state: InvoicesState): String = state.countOf(state.bucket).toString()

/** "Offen: 1 Rechnung" vs "Offen: 2 Rechnungen": the line's key follows the same exact count. */
internal fun invoicesResultLineKey(state: InvoicesState): StringKey =
    pluralResultKey(state.countOf(state.bucket), AdminStringKeys.InvoiceBucketResultLineOne, AdminStringKeys.InvoiceBucketResultLine)

/**
 * The collapsed KPI line's figures: what the three payment buckets are worth, in tile order. All three
 * are current balances, never a period figure. A bucket the server sent no sum for is "not available"
 * (null, drawn as `—`), never zero; with no sum at all there is no summary, as there are no tiles.
 */
internal fun invoicesPaymentSummaryItems(
    state: InvoicesState,
    label: (InvoiceListDto.Bucket) -> String,
    money: (amount: String, currency: String) -> String,
): List<KpiSummaryItem> {
    if (PAYMENT_BUCKETS.all { state.sumOf(it) == null }) return emptyList()
    return PAYMENT_BUCKETS.map { bucket -> KpiSummaryItem(label(bucket), state.sumOf(bucket)?.let { money(it, state.currency) }) }
}
