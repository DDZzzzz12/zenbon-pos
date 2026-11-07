package org.example.project.admin.features.invoices

import org.example.project.admin.features.invoices.presentation.InvoicesState
import org.example.project.admin.features.invoices.presentation.invoicesPaymentSummaryItems
import org.example.project.admin.features.invoices.presentation.invoicesResultCount
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.ui.components.KpiSummaryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoicesListSummaryTest {
    private fun rows(n: Int) = List(n) { InvoiceListDto.RowDto(bucket = InvoiceListDto.Bucket.ZU_PRUEFEN, invoiceId = it.toLong()) }

    @Test
    fun theCountIsExactWhenTheBucketFitsOnePage() {
        val state = InvoicesState(bucket = InvoiceListDto.Bucket.ZU_PRUEFEN, items = rows(3), bucketCounts = mapOf("ZU_PRUEFEN" to 3L))
        assertEquals("3", invoicesResultCount(state))
    }

    @Test
    fun theCountIsTheServersExactBucketCountEvenBeyondThePage() {
        val state = InvoicesState(bucket = InvoiceListDto.Bucket.ZU_PRUEFEN, items = rows(100), bucketCounts = mapOf("ZU_PRUEFEN" to 112L))
        assertEquals("112", invoicesResultCount(state))
    }

    @Test
    fun theCountFollowsTheAppliedBucketNotTheRowsStillOnScreen() {
        // Right after a bucket switch the previous bucket's rows are still in state.
        val state = InvoicesState(bucket = InvoiceListDto.Bucket.FERTIG, items = rows(3), bucketCounts = mapOf("ZU_PRUEFEN" to 3L, "FERTIG" to 7L))
        assertEquals("7", invoicesResultCount(state))
    }

    @Test
    fun anEmptyBucketCountsZero() {
        assertEquals("0", invoicesResultCount(InvoicesState()))
    }

    @Test
    fun theSummaryNamesThePaymentBucketsInTileOrderWithTheirSums() {
        val state =
            InvoicesState(
                bucketSums = mapOf("OFFEN" to "120.50", "UEBERFAELLIG" to "20.00", "UEBERZAHLT" to "0.00"),
                currency = "EUR",
            )
        val items = invoicesPaymentSummaryItems(state, label = { it.name }, money = { amount, currency -> "$amount $currency" })
        assertEquals(
            listOf(
                KpiSummaryItem("OFFEN", "120.50 EUR"),
                KpiSummaryItem("UEBERFAELLIG", "20.00 EUR"),
                KpiSummaryItem("UEBERZAHLT", "0.00 EUR"),
            ),
            items,
        )
    }

    @Test
    fun aMissingSumIsNotAvailableNeverZero() {
        val state = InvoicesState(bucketSums = mapOf("OFFEN" to "5.00"))
        val items = invoicesPaymentSummaryItems(state, label = { it.name }, money = { amount, _ -> amount })
        assertEquals(listOf(KpiSummaryItem("OFFEN", "5.00"), KpiSummaryItem("UEBERFAELLIG", null), KpiSummaryItem("UEBERZAHLT", null)), items)
    }

    @Test
    fun noSumAtAllMeansNoSummary() {
        assertTrue(invoicesPaymentSummaryItems(InvoicesState(), label = { it.name }, money = { amount, _ -> amount }).isEmpty())
    }
}
