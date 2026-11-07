package org.example.project.features.reports.presentation.components.bottomsheet

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** UI review follow-up 7: the payments sheet's Highest/Lowest sorts compare decimal amounts as money. */
class PaymentRecordSortTest {
    private fun record(
        id: Long,
        amount: String,
        time: String = "2026-10-16T12:00:00",
    ) = StatisticsDto.PaymentRecordDto(
        id = id,
        billId = id,
        billNumber = "R-$id",
        paymentTypeDE = "Bar",
        amount = amount,
        currency = "EUR",
        paymentStatus = PaymentStatus.COMPLETED,
        paymentTime = time,
    )

    // Incoming order deliberately neither ascending nor descending, with a decimal on every row:
    // the old toLongOrNull() key was null for all of them and left exactly this order.
    private val payments = listOf(record(1, "9.90"), record(2, "16.90"), record(3, "0.50"), record(4, "100.05"))

    @Test
    fun highestPutsTheLargestDecimalAmountFirst() {
        assertEquals(listOf(4L, 2L, 1L, 3L), payments.sortedFor(StatisticsDto.StatisticsSortOption.HIGHEST).map { it.id })
    }

    @Test
    fun lowestPutsTheSmallestDecimalAmountFirst() {
        assertEquals(listOf(3L, 1L, 2L, 4L), payments.sortedFor(StatisticsDto.StatisticsSortOption.LOWEST).map { it.id })
    }

    @Test
    fun amountsCompareAsNumbersNotAsStrings() {
        // As strings "9.00" > "10.00".
        val sorted = listOf(record(1, "9.00"), record(2, "10.00")).sortedFor(StatisticsDto.StatisticsSortOption.HIGHEST)
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun aNegativeAmountSortsBelowZeroAndAnUnparseableOneCountsAsZero() {
        val sorted =
            listOf(record(1, "n/a"), record(2, "-5.00"), record(3, "0.01"))
                .sortedFor(StatisticsDto.StatisticsSortOption.LOWEST)
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun equalAmountsKeepTheirIncomingOrder() {
        val sorted =
            listOf(record(1, "5.00"), record(2, "5.0"), record(3, "5"))
                .sortedFor(StatisticsDto.StatisticsSortOption.HIGHEST)
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.id })
    }

    @Test
    fun timeSortsAreUnchanged() {
        val timed =
            listOf(
                record(1, "1.00", "2026-10-16T10:00:00"),
                record(2, "1.00", "2026-10-16T12:00:00"),
                record(3, "1.00", "2026-10-16T11:00:00"),
            )
        assertEquals(listOf(2L, 3L, 1L), timed.sortedFor(StatisticsDto.StatisticsSortOption.RECENT).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), timed.sortedFor(StatisticsDto.StatisticsSortOption.OLDEST).map { it.id })
    }
}
