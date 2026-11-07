package org.example.project.admin.features.voucher

import org.example.project.admin.features.voucher.presentation.issuedTotals
import org.example.project.admin.features.voucher.presentation.redeemedFraction
import org.example.project.admin.features.voucher.presentation.redeemedTotals
import org.example.project.admin.features.voucher.presentation.runningBalances
import org.example.project.core.domain.dto.voucher.VoucherDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun voucher(
    id: Long,
    original: String,
    remaining: String,
) = VoucherDto.VoucherResponseDto(
    id = id,
    sessionId = null,
    voucherNumber = "G2026-000$id",
    employeeNumber = "07",
    orderItemId = null,
    originalValue = original,
    remainingValue = remaining,
    totalRedeemed = "0.00",
    isFullyRedeemed = false,
    createdAt = "2026-08-03T14:12:00",
    fullyRedeemedAt = null,
    status = "ACTIVE",
)

private fun redemption(
    amount: String,
    reversedBy: String? = null,
) = VoucherDto.VoucherRedemptionSummaryDto(
    voucherNumber = "G2026-0042",
    originalValue = "50.00",
    redeemedAmount = amount,
    remainingValue = "20.00",
    redeemedAt = "2026-08-05T10:00:00",
    redeemedBy = "03",
    billNumber = "1042",
    reversedByBillNumber = reversedBy,
)

private fun entry(
    id: Long,
    amount: String,
    at: String,
) = VoucherDto.VoucherRedemptionEntryDto(
    redemptionId = id,
    redeemedAmount = amount,
    redeemedAt = at,
    billNumber = "10$id",
    employeeNumber = "03",
    // Deliberately the server's wrong value; nothing must read it.
    remainingValueAfter = "999.99",
)

class VoucherAggregatesTest {
    @Test
    fun issuedTotals_sumsOriginalValuesExactly() {
        val totals = issuedTotals(listOf(voucher(1, "16.90", "0.00"), voucher(2, "33.10", "0.00")))
        assertEquals("50.00", totals.amount.toPlainString())
        assertEquals(2, totals.count)
    }

    @Test
    fun issuedTotals_emptyListIsZeroNotCrash() {
        val totals = issuedTotals(emptyList())
        assertEquals("0", totals.amount.toPlainString())
        assertEquals(0, totals.count)
    }

    @Test
    fun redeemedTotals_sumsRedeemedAmounts() {
        val totals = redeemedTotals(listOf(redemption("20.00"), redemption("10.00")))
        assertEquals("30.00", totals.amount.toPlainString())
        assertEquals(2, totals.count)
    }

    @Test
    fun redeemedTotals_skipsRedemptionsAStornoReversed() {
        // admin20 FAIL-B20: Z-Bon 35,30 € / 3 vs. the tile's 63,20 € / 5 — two were undone by a Zahlartwechsel.
        val totals =
            redeemedTotals(
                listOf(
                    redemption("20.00"),
                    redemption("17.90", reversedBy = "RE-261003-6"),
                    redemption("10.00", reversedBy = "RE-261003-49"),
                    redemption("15.30"),
                ),
            )
        assertEquals("35.30", totals.amount.toPlainString())
        assertEquals(2, totals.count)
    }

    @Test
    fun redeemedFraction_isSixtyPercentOfFifty() {
        assertEquals(0.6f, redeemedFraction("50.00", "20.00"), absoluteTolerance = 0.0001f)
    }

    @Test
    fun redeemedFraction_zeroOriginalDoesNotDivideByZero() {
        assertEquals(0f, redeemedFraction("0.00", "0.00"))
    }

    @Test
    fun redeemedFraction_unparseableInputDegradesToZero() {
        assertEquals(0f, redeemedFraction("not-a-number", "20.00"))
    }

    @Test
    fun runningBalances_walksFromOriginalIgnoringTheServersBrokenField() {
        // Server returns redeemedAt DESC. 50.00 redeemed 20.00 then 10.00.
        val history =
            listOf(
                entry(2, "10.00", "2026-08-06T12:00:00"),
                entry(1, "20.00", "2026-08-05T10:00:00"),
            )
        val balances = runningBalances("50.00", history)
        assertEquals("30.00", balances[1L]?.toPlainString())
        assertEquals("20.00", balances[2L]?.toPlainString())
        // The DTO claims 999.99 for both; if that leaked through, the assertions above would fail.
        assertTrue(balances.values.none { it.toPlainString() == "999.99" })
    }
}
