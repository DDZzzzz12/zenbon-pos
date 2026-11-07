package org.example.project.admin.features.allocations

import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowCounts
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStage
import org.example.project.admin.features.allocations.presentation.AllocationStripCounts
import org.example.project.core.domain.dto.allocation.AllocationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllocationStripCountsTest {
    private val base = InvoiceFlowCounts(counts = mapOf("ZU_ERFASSEN" to 3L, "ZU_PRUEFEN" to 5L, "OFFEN" to 0L, "FERTIG" to 0L))

    @Test
    fun theAssignTileCountsOpenPaymentsAndSumsTheirOpenAmounts() {
        val rows = listOf(queueRow(1, open = "100.0000"), queueRow(2, open = "50.5000"))
        val counts = AllocationStripCounts.of(base, AllocationDto.QueueFilter.NICHT_ZUGEORDNET, total = 2, rows = rows)
        assertEquals(2L, counts.countOf(InvoiceFlowStage.ASSIGN))
        assertEquals("150.5000", counts.sums["OFFEN"])
    }

    @Test
    fun aPartialPageGivesACountButNoSum() {
        val counts = AllocationStripCounts.of(base, AllocationDto.QueueFilter.NICHT_ZUGEORDNET, total = 10, rows = listOf(queueRow(1)))
        assertEquals(10L, counts.countOf(InvoiceFlowStage.ASSIGN))
        assertNull(counts.sums["OFFEN"], "a sum over a partial page would be wrong")
    }

    @Test
    fun theOtherStagesKeepTheInvoiceCountsAndDoneIsADashNotAZero() {
        val counts = AllocationStripCounts.of(base, AllocationDto.QueueFilter.NICHT_ZUGEORDNET, total = 1, rows = listOf(queueRow(1)))
        assertEquals(3L, counts.countOf(InvoiceFlowStage.CAPTURE))
        assertEquals(5L, counts.countOf(InvoiceFlowStage.REVIEW))
        assertNull(counts.countOf(InvoiceFlowStage.DONE))
    }

    @Test
    fun theSameSummaryFeedsEveryOtherScreen() {
        val payments =
            AllocationStripCounts.summaryOf(
                AllocationDto.QueueFilter.NICHT_ZUGEORDNET,
                2,
                listOf(queueRow(1, open = "10.0000"), queueRow(2, open = "5.0000")),
            )
        val counts = base.withOpenPayments(payments)
        assertEquals(2L, counts.countOf(InvoiceFlowStage.ASSIGN), "payments waiting, not open invoices")
        assertEquals("15.0000", counts.sums["OFFEN"])
        assertEquals(3L, counts.countOf(InvoiceFlowStage.CAPTURE), "the other tiles keep their invoice counts")
    }

    @Test
    fun aFailedOrForbiddenQueueIsADashNeverAZero() {
        val counts = base.withOpenPayments(null)
        assertNull(counts.countOf(InvoiceFlowStage.ASSIGN))
        assertNull(counts.sums["OFFEN"])
    }

    @Test
    fun unloadedCountsStayUnloaded() {
        assertNull(InvoiceFlowCounts().withOpenPayments(null).countOf(InvoiceFlowStage.CAPTURE))
        assertEquals(false, InvoiceFlowCounts().withOpenPayments(null).isLoaded)
    }

    @Test
    fun underTheAllFilterTheOpenCountIsUnknown() {
        val counts = AllocationStripCounts.of(base, AllocationDto.QueueFilter.ALLE, total = 52, rows = emptyList())
        assertNull(counts.countOf(InvoiceFlowStage.ASSIGN))
    }
}
