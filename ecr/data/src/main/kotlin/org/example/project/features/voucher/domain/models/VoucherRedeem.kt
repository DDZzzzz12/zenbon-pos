package org.example.project.features.voucher.domain.models

import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import java.math.BigDecimal

/**
 * VoucherRedeem entity representing individual voucher redemption transactions.
 *
 * Tracks partial and full redemptions of vouchers, maintaining detailed audit
 * trail for financial compliance and customer service purposes.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Entity representing a single voucher redemption transaction.
 *
 * Records when and how much of a voucher was used, supporting partial
 * redemptions and maintaining comprehensive usage history.
 */
class VoucherRedeem(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<VoucherRedeem>(VoucherRedeemTable)

    /** The voucher being redeemed */
    var voucher by Voucher optionalReferencedOn VoucherRedeemTable.voucherId

    var payment by Payment referencedOn VoucherRedeemTable.paymentId

    /** Amount being redeemed in this transaction */
    var redeemAmount by VoucherRedeemTable.redeemAmount

    /** Timestamp when redemption occurred */
    var redeemedAt by VoucherRedeemTable.redeemedAt

    var voucherType by VoucherRedeemTable.voucherType

    var voucherNumber by VoucherRedeemTable.voucherNumber

    var zBon by ZBon optionalReferencedOn VoucherRedeemTable.zBonId

    /** The storno reverse bill that reversed this redemption */
    var reversedByBill by Bill optionalReferencedOn VoucherRedeemTable.reversedByBillId

    /**
     * Initializes redemption with current timestamp.
     *
     * Called automatically before redemption is persisted to database.
     */
    fun onCreate() {
        redeemedAt = currentTimeStamp()
    }

    /**
     * Validates redemption data for business rules compliance.
     *
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(redeemAmount > BigDecimal.ZERO) { "Redeem amount must be positive" }
        require(redeemAmount <= voucher?.remainingValue) {
            "Redeem amount $redeemAmount exceeds voucher remaining value ${voucher?.remainingValue}"
        }
    }

    /**
     * Returns a string representation of the voucher redemption.
     *
     * @return formatted string with key redemption information
     */
    override fun toString(): String =
        """
        VoucherRedeem(
            id=$id,
            voucherNumber='${voucher?.voucherNumber}',
            redeemAmount=$redeemAmount,
            redeemedAt=$redeemedAt,
        )
        """.trimIndent()
}
