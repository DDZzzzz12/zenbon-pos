/**
 * Splits a Z-Bon's tips into what its tip block renders: the employee tips with their total, and the
 * owner tip on a line of its own.
 *
 * An EMPLOYEE tip (TrinkgeldAN) is not revenue; an OWNER tip (TrinkgeldAG) is taxed turnover that is
 * already inside the Z-Bon's revenue and VAT groups. The block used to list every summary with a tip,
 * the owner's included, under a total that (correctly) left the owner out — so the printed list did
 * not add up (QA emu14 NOTE-5: M001 4.00 + A001 4.00 + K001 2.00 listed, "Gesamt Trinkgeld 6.00").
 *
 * The printed Z-Bon, its PDF and both detail panels draw their tip block from this one function so
 * they cannot drift apart, the same way [partitionZBonKassenbuch] holds their Kassenbuch sections.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.money.Decimal

/**
 * The tip block of a Z-Bon.
 *
 * @property employeeTips The summaries with a non-zero employee tip, in input order
 * @property employeeTipTotal The Z-Bon's `totalEmployeeTips` — the sum of exactly [employeeTips];
 *   null when there are none
 * @property ownerTip The owner tip (`adminTip`), which is revenue; null when there is none
 */
data class ZBonTipSections(
    val employeeTips: List<ZBonDto.EmployeeZBonSummaryResponseDto>,
    val employeeTipTotal: String?,
    val ownerTip: String?,
) {
    /** True when the Z-Bon has no tip of either kind, so the block is left out. */
    val isEmpty: Boolean get() = employeeTips.isEmpty() && ownerTip == null
}

/**
 * Builds the tip block of [zBon]. A summary marked `ownerTip` is never listed: its tip is the
 * owner's and is shown once, as [ZBonTipSections.ownerTip].
 */
fun zBonTipSections(zBon: ZBonDto.ZBonResponseDto): ZBonTipSections {
    val employeeTips = zBon.employeeSummaries.orEmpty().filter { !it.ownerTip && it.amount.isNonZeroAmount() }
    return ZBonTipSections(
        employeeTips = employeeTips,
        employeeTipTotal = zBon.totalEmployeeTips?.takeIf { employeeTips.isNotEmpty() },
        ownerTip = zBon.adminTip?.takeIf { it.isNonZeroAmount() },
    )
}

// As a Decimal: "0.0000" is no tip either, which a string comparison with "0.00" missed.
private fun String.isNonZeroAmount(): Boolean = Decimal.parseOrNull(this)?.isZero == false
