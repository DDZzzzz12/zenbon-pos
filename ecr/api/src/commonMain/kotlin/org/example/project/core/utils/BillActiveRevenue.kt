package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.money.Decimal

/*
 * Which receipts on a list count as revenue. A receipt LIST shows every Beleg — a Storno and the
 * cancelled original it reverses are fiscal records too — but a revenue figure computed beside it
 * must count ACTIVE bills only: neither a Storno nor a cancelled original. The pair cancels out and
 * both drop out; a Zahlartwechsel's Neubuchung counts.
 *
 * The client twin of the server's `activeBillOp` (StatisticsRepositoryImpl) and
 * `DrawerCashDerivation.activeBills` (the Z-Bon): `deletedBillId` null and `deletingReason` null.
 * The relation flags are read too, so a bill from a server that derives relations but omits the raw
 * columns is still classified correctly.
 *
 * A receivable clearing (`BillKind.RECEIVABLE_CLEARING`) is active — it is a real receipt and its
 * money came in — but it is not revenue: it collects an outgoing invoice whose turnover was booked
 * when the invoice was issued. [countsAsRevenue] leaves it out, as the server's `revenueBillOp` and
 * `DrawerCashDerivation.saleBills` (the Z-Bon's Umsatz) do.
 */

/** True when this bill counts as revenue: not a Storno, not a cancelled original. */
fun BillDto.BillResponseDto.isActiveBill(): Boolean =
    deletedBillId == null &&
        deletingReason == null &&
        relations.kind != BillKind.STORNO &&
        !relations.isCancelled

/**
 * True when this bill is turnover: an [isActiveBill] receipt that is neither a receivable clearing
 * nor a Schulungsbeleg (a training receipt is listed, never revenue — the server's `TrainingFilter`).
 */
fun BillDto.BillResponseDto.countsAsRevenue(): Boolean = isActiveBill() && relations.kind != BillKind.RECEIVABLE_CLEARING && !isTraining

/**
 * Σ `paidAmount` of the [countsAsRevenue] receipts — the revenue of a receipt list that may hold
 * Stornos, cancelled originals and receivable clearings. An unparseable amount counts as zero.
 */
fun List<BillDto.BillResponseDto>.activeBillsRevenue(): Decimal =
    filter { it.countsAsRevenue() }
        .fold(Decimal.ZERO) { acc, bill -> acc + (Decimal.parseOrNull(bill.paidAmount) ?: Decimal.ZERO) }
