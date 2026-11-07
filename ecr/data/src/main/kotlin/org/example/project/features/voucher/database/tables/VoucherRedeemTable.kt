package org.example.project.features.voucher.database.tables

import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed ORM table definition for VoucherRedeem entity.
 *
 * Tracks individual voucher redemption transactions, supporting partial
 * redemptions and maintaining audit trail for voucher usage history.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed ORM table for tracking voucher redemptions.
 *
 * Records each time a voucher is used (partially or fully) to track
 * redemption history and maintain accurate remaining balances.
 */
object VoucherRedeemTable : LongIdTable("voucher_redeem") {
    /** Reference to the voucher being redeemed */
    val voucherId = reference("voucher_id", VoucherTable, onDelete = ReferenceOption.CASCADE).nullable()

    val paymentId = reference("payment_id", PaymentTable)

    /** Amount being redeemed in this transaction */
    val redeemAmount = decimal("redeem_amount", 10, 2)

    /** Timestamp when redemption occurred */
    val redeemedAt = datetime("redeemed_at")

    val voucherType = enumerationByName("voucher_type", 40, VoucherSourceType::class)

    val voucherNumber = varchar("voucher_number", 50)

    val zBonId = reference("zbon_id", ZBonTable).nullable()

    /** Set when this redemption was fiscally undone: the storno reverse bill that refunded it */
    val reversedByBillId = reference("reversed_by_bill_id", BillTable).nullable()

    init {
        index("idx_voucher_redeem_reversed_by_bill_id", false, reversedByBillId)
    }

    /**
     * The single definition of a redemption that **stands**: not undone by a Storno or by the Storno
     * half of a Zahlartwechsel. The Z-Bon assigns only these to a closing, and the voucher
     * statistics count only these (admin20 FAIL-B20: the Gutscheine tiles counted reversed ones and
     * disagreed with the Z-Bon). The voucher list carries the same
     * verdict per row as `VoucherRedemptionSummaryDto.reversedByBillNumber` / `stands`.
     */
    fun standing(): Op<Boolean> = reversedByBillId.isNull()
}
