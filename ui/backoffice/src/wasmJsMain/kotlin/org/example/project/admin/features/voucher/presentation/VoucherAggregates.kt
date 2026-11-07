package org.example.project.admin.features.voucher.presentation

import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.money.sum

/** A period figure: the money total and how many records produced it. */
internal data class PeriodTotals(
    val amount: Decimal,
    val count: Int,
)

/** Total face value issued in the loaded period. */
internal fun issuedTotals(vouchers: List<VoucherDto.VoucherResponseDto>): PeriodTotals =
    PeriodTotals(
        amount = vouchers.mapNotNull { Decimal.parseOrNull(it.originalValue) }.sum(),
        count = vouchers.size,
    )

/**
 * Total value redeemed in the loaded period — redemptions that **stand** only. One reversed by a
 * Storno or a Zahlartwechsel is still listed but no longer counts, exactly as the Z-Bon omits it
 * (admin20 FAIL-B20: the tile read 63,20 € / 5 against the Z-Bon's 35,30 € / 3).
 */
internal fun redeemedTotals(redemptions: List<VoucherDto.VoucherRedemptionSummaryDto>): PeriodTotals {
    val standing = redemptions.filter { it.stands }
    return PeriodTotals(
        amount = standing.mapNotNull { Decimal.parseOrNull(it.redeemedAmount) }.sum(),
        count = standing.size,
    )
}

/**
 * Fraction 0..1 of the original value already redeemed, for the progress bar. This is the only place
 * a money value becomes a Double, and it happens at the very last step via [Decimal.toDoubleLossy] —
 * a progress bar cannot consume anything else.
 *
 * Degrades to 0 rather than throwing on unparseable or non-positive input, matching
 * `formatMoneyForDisplay`'s never-throw contract.
 */
internal fun redeemedFraction(
    originalValue: String,
    remainingValue: String,
): Float {
    val original = Decimal.parseOrNull(originalValue) ?: return 0f
    val remaining = Decimal.parseOrNull(remainingValue) ?: return 0f
    if (original.isZero || original.isNegative) return 0f
    val redeemed = original - remaining
    if (redeemed.isNegative) return 0f
    val fraction = redeemed.divide(original, scale = 4, rounding = MoneyRounding.HALF_UP)
    return fraction.toDoubleLossy().toFloat().coerceIn(0f, 1f)
}

/**
 * Balance remaining after each redemption, keyed by `redemptionId`.
 *
 * The DTO's own `remainingValueAfter` is **not** used: `VoucherServiceImpl.kt:416` computes it as
 * `voucher.remainingValue + redemption.redeemAmount`, where `remainingValue` is the *current*
 * balance and therefore constant across the loop — every entry is wrong. On a 50 € voucher redeemed
 * 20 € then 10 € it reports 40 € and 30 € instead of 30 € and 20 €.
 *
 * [redemptions] normally arrives `redeemedAt DESC` from the server, but the walk does not lean on
 * that ordering holding forever: it sorts explicitly by [VoucherDto.VoucherRedemptionEntryDto.redeemedAt]
 * (an ISO-8601 string, so lexicographic order is chronological order) before subtracting from
 * `originalValue` oldest-first. If the server's ordering ever changes, this function still produces
 * correct balances instead of silently inverting them.
 */
internal fun runningBalances(
    originalValue: String,
    redemptions: List<VoucherDto.VoucherRedemptionEntryDto>,
): Map<Long, Decimal> {
    var balance = Decimal.parseOrNull(originalValue) ?: return emptyMap()
    val balances = mutableMapOf<Long, Decimal>()
    for (entry in redemptions.sortedBy { it.redeemedAt }) {
        val amount = Decimal.parseOrNull(entry.redeemedAmount) ?: continue
        balance -= amount
        balances[entry.redemptionId] = balance
    }
    return balances
}
