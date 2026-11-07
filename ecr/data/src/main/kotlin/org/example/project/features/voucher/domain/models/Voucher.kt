package org.example.project.features.voucher.domain.models

import org.example.project.core.domain.enums.voucher.VoucherInvalidationSource
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import java.math.BigDecimal

/**
 * Voucher entity representing gift vouchers and store credit in the POS system.
 *
 * Manages voucher lifecycle including creation, redemption tracking, and value management.
 * Supports partial redemptions through associated VoucherRedeem records and maintains
 * audit trail for financial compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Voucher entity representing gift vouchers and store credit.
 *
 * Tracks voucher value, redemptions, and associated payments while maintaining
 * comprehensive audit trail for financial reporting and compliance.
 */
class Voucher(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Voucher>(VoucherTable)

    /** Reference to the order associated with this voucher (nullable for legacy vouchers) */
    var orderItem by OrderItem referencedOn VoucherTable.orderItem

    /** Unique voucher number for customer identification */
    var voucherNumber by VoucherTable.voucherNumber

    /** Original value of the voucher when created */
    var originalValue by VoucherTable.originalValue

    /** Current remaining value available for redemption */
    var remainingValue by VoucherTable.remainingValue

    /** Timestamp when voucher was created */
    var createdAt by VoucherTable.createdAt

    /** Timestamp when voucher was fully redeemed (nullable) */
    var fullyRedeemedAt by VoucherTable.fullyRedeemedAt

    var zBon by ZBon optionalReferencedOn VoucherTable.zBonId

    var status by VoucherTable.status
    var invalidationReason by VoucherTable.invalidationReason
    var invalidatedAt by VoucherTable.invalidatedAt
    var invalidationSource by VoucherTable.invalidationSource

    /** List of redemption records for this voucher */
    val redeems by VoucherRedeem optionalReferrersOn VoucherRedeemTable.voucherId

    /**
     * Initializes voucher with creation timestamp and sets initial values.
     *
     * Called automatically before voucher is persisted to database.
     * Sets up initial state and audit timestamps.
     */
    fun onCreate() {
        val now = currentTimeStamp()
        createdAt = now
        remainingValue = originalValue
    }

    /**
     * Adds a redemption record to this voucher and updates remaining value.
     *
     * @param voucherRedeem The redemption record to add
     * @throws IllegalArgumentException if redemption amount exceeds remaining value
     */
    fun addRedeem(voucherRedeem: VoucherRedeem) {
        require(voucherRedeem.redeemAmount <= remainingValue) {
            "Redemption amount ${voucherRedeem.redeemAmount} exceeds remaining value $remainingValue"
        }

        voucherRedeem.voucher = this
        remainingValue = remainingValue.subtract(voucherRedeem.redeemAmount)

        // Mark as fully redeemed if no value remains
        if (remainingValue.compareTo(BigDecimal.ZERO) == 0 && fullyRedeemedAt == null) {
            fullyRedeemedAt = currentTimeStamp()
        }
    }

    /**
     * Checks if voucher is fully redeemed.
     *
     * @return true if voucher has no remaining value, false otherwise
     */
    val isFullyRedeemed: Boolean
        get() = remainingValue.compareTo(BigDecimal.ZERO) == 0

    /**
     * Gets the total amount redeemed from this voucher.
     *
     * @return sum of all redemption amounts
     */
    val totalRedeemed: BigDecimal
        get() = originalValue.subtract(remainingValue)

    /**
     * Validates voucher data for business rules compliance.
     *
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(voucherNumber.isNotBlank()) { "Voucher number cannot be blank" }
        require(originalValue > BigDecimal.ZERO) { "Original value must be positive" }
        require(remainingValue >= BigDecimal.ZERO) { "Remaining value cannot be negative" }
        require(remainingValue <= originalValue) { "Remaining value cannot exceed original value" }
    }

    fun invalidate(
        reason: String,
        source: VoucherInvalidationSource,
    ) {
        if (status == VoucherStatus.INVALIDATED) {
            throw IllegalStateException("Voucher $voucherNumber is already invalidated")
        }
        if (remainingValue.compareTo(originalValue) != 0) {
            throw IllegalStateException("Cannot invalidate voucher $voucherNumber: it has been partially redeemed")
        }

        status = VoucherStatus.INVALIDATED
        invalidationReason = reason
        invalidatedAt = currentTimeStamp()
        invalidationSource = source
        remainingValue = BigDecimal.ZERO
    }

    /**
     * Returns a string representation of the voucher.
     *
     * @return formatted string with key voucher information
     */
    override fun toString(): String =
        """
        Voucher(
            id=$id,
            voucherNumber='$voucherNumber',
            originalValue=$originalValue,
            remainingValue=$remainingValue,
            isFullyRedeemed=$isFullyRedeemed,
            createdAt=$createdAt
        )
        """.trimIndent()
}
