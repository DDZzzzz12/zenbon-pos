package org.example.project.core.domain.enums.kassenbuch

import org.example.project.core.domain.enums.datev.DatevMappingSide
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which side of a Z-Bon a cash-book entry counts on.
 *
 * `ZBonServiceImpl.bucketKassenbuchTotals` split entries purely on the arithmetic sign, with one
 * carve-out for Geldtransit. So a `CORRECTION_IN` — the storno leg of a correction to a cash
 * *expense*, and therefore a positive amount — was reported as cash **takings**. Measured on
 * 2026-09-20: a 12,34 € expense, its correction and a 15,00 € re-booking left Z-Bon 139 reporting
 * `cash_revenue = 81.74` where the day's actual takings were 69,40 € (E2E-2026-09-20 #10). The
 * closing balance was right — the two errors cancel — which is exactly why it never showed up as a
 * till difference.
 *
 * The codebase already disagreed with itself: [KassenbuchEntryType.mappingSide] books
 * `CORRECTION_IN` on the **expense** side for DATEV. Where that mapping has an opinion, it now
 * decides the Z-Bon bucket too.
 */
class ZBonBucketTest {
    @Test
    fun `a correction is booked the same way for DATEV and the Z-Bon`() {
        assertEquals(
            ZBonBucket.EXPENSE,
            KassenbuchEntryType.CORRECTION_IN.zBonBucket(isNegative = false),
            "a positive CORRECTION_IN reverses an expense; it is not takings",
        )
        assertEquals(
            ZBonBucket.REVENUE,
            KassenbuchEntryType.CORRECTION_OUT.zBonBucket(isNegative = true),
            "a negative CORRECTION_OUT reverses takings; it is not an expense",
        )
    }

    @Test
    fun `sales and customer payments are takings`() {
        assertEquals(ZBonBucket.REVENUE, KassenbuchEntryType.CASH_SALE.zBonBucket(isNegative = false))
        assertEquals(ZBonBucket.REVENUE, KassenbuchEntryType.CASH_PAYMENT.zBonBucket(isNegative = false))
        assertEquals(ZBonBucket.REVENUE, KassenbuchEntryType.REFUND.zBonBucket(isNegative = true))
    }

    @Test
    fun `a cash expense is an expense`() {
        assertEquals(ZBonBucket.EXPENSE, KassenbuchEntryType.CASH_EXPENSE.zBonBucket(isNegative = true))
    }

    @Test
    fun `Geldtransit keeps its own GV_TYP, as it always did`() {
        assertEquals(ZBonBucket.NEUTRAL, KassenbuchEntryType.CASH_TRANSFER_IN.zBonBucket(isNegative = false))
        assertEquals(ZBonBucket.NEUTRAL, KassenbuchEntryType.CASH_TRANSFER_OUT.zBonBucket(isNegative = true))
    }

    /**
     * Deliberately unchanged. Whether a Bareinlage or a Kassendifferenz belongs in a Z-Bon's
     * reported takings is a product decision — `ZBonGeldtransitTest` asserts today that a
     * `CASH_DEPOSIT` counts as `cashRevenue` — so the types DATEV leaves unmapped keep the old
     * sign split rather than being silently redefined by a defect fix.
     */
    @Test
    fun `types DATEV leaves unmapped still follow the sign`() {
        assertEquals(ZBonBucket.REVENUE, KassenbuchEntryType.CASH_DEPOSIT.zBonBucket(isNegative = false))
        assertEquals(ZBonBucket.EXPENSE, KassenbuchEntryType.CASH_WITHDRAWAL.zBonBucket(isNegative = true))
        assertEquals(ZBonBucket.EXPENSE, KassenbuchEntryType.CASH_SHORTAGE.zBonBucket(isNegative = true))
        assertEquals(ZBonBucket.REVENUE, KassenbuchEntryType.CASH_SURPLUS.zBonBucket(isNegative = false))
    }

    /**
     * The partition must be total: every entry type lands in exactly one bucket at either sign, so
     * the closing balance still sums every header and cannot silently drop one.
     */
    @Test
    fun `every entry type has a bucket at either sign`() {
        KassenbuchEntryType.entries.forEach { type ->
            type.zBonBucket(isNegative = true)
            type.zBonBucket(isNegative = false)
        }
    }

    @Test
    fun `wherever DATEV has a side, the Z-Bon agrees with it regardless of sign`() {
        KassenbuchEntryType.entries
            .filter { it.mappingSide != null }
            .forEach { type ->
                val expected =
                    if (type.mappingSide == DatevMappingSide.INCOME) ZBonBucket.REVENUE else ZBonBucket.EXPENSE
                assertEquals(expected, type.zBonBucket(isNegative = true), "$type (negative)")
                assertEquals(expected, type.zBonBucket(isNegative = false), "$type (positive)")
            }
    }
}
