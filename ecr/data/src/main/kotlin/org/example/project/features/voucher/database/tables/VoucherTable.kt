package org.example.project.features.voucher.database.tables

import org.example.project.core.domain.enums.voucher.VoucherInvalidationSource
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed ORM table definition for Voucher entity.
 *
 * Represents vouchers in the POS system with original and remaining values,
 * tracking redemptions and payment associations. Inherits from TransactionTable
 * for common transaction fields and audit tracking.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed ORM table definition for vouchers.
 *
 * Extends TransactionTable to inherit common transaction fields while adding
 * voucher-specific fields for value tracking, redemption management, and
 */
object VoucherTable : LongIdTable("voucher") {
    /** Order item reference - nullable to support legacy vouchers created outside system */
    val orderItem = reference("order_item_id", OrderItemTable)

    val voucherNumber = varchar("voucher_number", 50).uniqueIndex()

    /** Original value of the voucher when created */
    val originalValue = decimal("original_value", 10, 2)

    /** Current remaining value available for redemption */
    val remainingValue = decimal("remaining_value", 10, 2)

    /** Timestamp when voucher was created */
    val createdAt = datetime("created_at")

    /** Timestamp when voucher was fully redeemed (nullable) */
    val fullyRedeemedAt = datetime("fully_redeemed_at").nullable()

    val zBonId = reference("zbon_id", ZBonTable).nullable()

    val status = enumerationByName<VoucherStatus>("status", 20).default(VoucherStatus.ACTIVE)
    val invalidationReason = varchar("invalidation_reason", 1000).nullable()
    val invalidatedAt = datetime("invalidated_at").nullable()
    val invalidationSource = enumerationByName<VoucherInvalidationSource>("invalidation_source", 30).nullable()
}
