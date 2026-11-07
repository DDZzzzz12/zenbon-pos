package org.example.project.core.domain.dto.voucher

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.money.Decimal
import org.example.project.money.MoneyException

/**
 * Container object for all Voucher-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and analytics
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object VoucherDto {
    /**
     * Response DTO for Voucher data.
     *
     * Used for API responses containing voucher information including
     * value tracking, redemption status, and associated order details.
     */
    @Serializable
    data class VoucherResponseDto(
        val id: Long,
        val sessionId: Long?,
        val voucherNumber: String,
        val employeeNumber: String,
        val orderItemId: Long?,
        val originalValue: String, // BigDecimal serialized as String
        val remainingValue: String, // BigDecimal serialized as String
        val totalRedeemed: String, // BigDecimal serialized as String
        val isFullyRedeemed: Boolean,
        val createdAt: String, // LocalDateTime serialized as String
        val fullyRedeemedAt: String?, // Nullable LocalDateTime serialized as String
        val status: String, // VoucherStatus as String
        val invalidationReason: String? = null,
        val invalidatedAt: String? = null,
        val invalidationSource: String? = null, // VoucherInvalidationSource as String
        /**
         * ISO 4217 code the voucher values are denominated in. Supplied by the server from shop
         * config (`ConfigKey.DEFAULT_CURRENCY`), resolved once per request by the service. The
         * default only keeps non-server construction sites compiling.
         */
        val currency: String = "EUR",
        /**
         * The bill a counter sale was settled on — the till prints the customer's receipt from it
         * (`POST /api/v1/bills/{billId}/print`). Null for a table-session voucher, which is billed
         * later with its table, for every read endpoint, and from a server that predates it.
         */
        val billId: Long? = null,
    ) {
        companion object
    }

    @Serializable
    data class VoucherSummeryDto(
        val id: Long,
        val voucherNumber: String,
        val originalValue: String,
        val employeeNumber: String,
    )

    /**
     * Request DTO for creating a new Voucher.
     *
     * Supports two modes:
     * 1. Counter sale: sold directly at the counter, no table involved (sessionId = null).
     *    The server opens a session on the virtual VOUCHER table and settles it immediately
     *    through the normal bill path, which is why a payment type is mandatory here.
     * 2. Table session: added to an existing table order (sessionId provided) and settled
     *    together with that table's bill.
     */
    @Serializable
    data class CreateVoucherRequestDto(
        /** Session ID for adding the voucher to an existing table order. Null for a counter sale. */
        val sessionId: Long? = null,
        /** Original value of the voucher */
        val originalValue: String, // BigDecimal as String for JSON serialization
        /** Payment type ID (required for a counter sale, unused for a table session) */
        val paymentTypeId: Long? = null,
        /** Tip amount (optional, for quick sale) */
        val tipAmount: String? = null,
    ) {
        /**
         * Validates the create voucher request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate original value format
            val value =
                try {
                    Decimal.parse(originalValue)
                } catch (e: MoneyException) {
                    throw IllegalArgumentException("Original value must be a valid decimal number")
                }
            require(value > Decimal.ZERO) { "Original value must be positive" }

            // Validate tip amount if provided
            tipAmount?.let {
                val tip =
                    try {
                        Decimal.parse(it)
                    } catch (e: MoneyException) {
                        throw IllegalArgumentException("Tip amount must be a valid decimal number")
                    }
                require(!tip.isNegative) { "Tip amount cannot be negative" }
                // A voucher added to a table is paid with that table's bill, which carries its own
                // tip; a tip here would have nowhere to go and be silently dropped.
                require(sessionId == null || tip.isZero) { "A tip is only accepted on a counter sale" }
            }

            // Counter sale: with no session the voucher settles immediately, so it needs a payment type
            if (sessionId == null) {
                require(paymentTypeId != null) { "Payment type is required for direct voucher sale" }
            }
        }
    }

    /**
     * Response DTO for voucher statistics.
     *
     * Used for API responses containing voucher analytics
     * and summary information.
     */
    @Serializable
    data class VoucherStatisticsDto(
        val totalVouchers: Long,
        val activeVouchers: Long,
        val fullyRedeemedVouchers: Long,
        val totalValueIssued: String, // BigDecimal serialized as String
        val totalValueRedeemed: String, // BigDecimal serialized as String
        val totalValueRemaining: String, // BigDecimal serialized as String
        val invalidatedVouchers: Long,
        val totalValueInvalidated: String, // BigDecimal serialized as String
        /**
         * ISO 4217 code the value totals are denominated in. Supplied by the server from shop
         * config (`ConfigKey.DEFAULT_CURRENCY`), resolved once per request by the service. The
         * default only keeps non-server construction sites compiling.
         */
        val currency: String = "EUR",
    ) {
        companion object
    }

    /**
     * Data class representing voucher statistics summary.
     */
    @Serializable
    data class VoucherStatistics(
        val totalVouchers: Long,
        val activeVouchers: Long,
        val fullyRedeemedVouchers: Long,
        val totalValueIssued: String,
        val totalValueRedeemed: String,
        val totalValueRemaining: String,
        val invalidatedVouchers: Long,
        val totalValueInvalidated: String,
    )

    /**
     * Request DTO for redeeming a voucher.
     *
     * Used for API requests when redeeming a voucher as payment.
     * Includes the voucher number, amount to redeem, and source type
     * for proper tracking and validation.
     */
    @Serializable
    data class RedeemVoucherRequestDto(
        /** Voucher number to redeem (e.g., "G2025-0001") */
        val voucherNumber: String,
        /** Amount to redeem from the voucher (BigDecimal as String) */
        val redeemedAmount: String,
        /** Source type of the voucher (INTERNAL, LEGACY, EXTERNAL, etc.) */
        val voucherSource: VoucherSourceType,
    ) {
        /**
         * Validates the redeem voucher request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(voucherNumber.isNotBlank()) { "Voucher number is required" }

            val amount =
                try {
                    Decimal.parse(redeemedAmount)
                } catch (e: MoneyException) {
                    throw IllegalArgumentException("Redeemed amount must be a valid decimal number")
                }
            require(amount > Decimal.ZERO) { "Redeemed amount must be positive" }
        }
    }

    /**
     * Response DTO for voucher redemption operations.
     *
     * Used for API responses when returning voucher redemption information.
     * Contains redemption details and remaining voucher value.
     */
    @Serializable
    data class RedeemVoucherResponse(
        val id: Long,
        val voucherNumber: String,
        val voucherSource: VoucherSourceType,
        val redeemedAmount: String, // BigDecimal as String
        val remainingValue: String?, // BigDecimal as String
        val redeemedAt: String, // LocalDateTime as String
        val billId: Long,
        /**
         * The payment row this redemption settles — lets the till pair a voucher leg with its
         * payment (the Zahlartwechsel dialog prefills the bill's tender from it). Null from a server
         * that predates it.
         */
        val paymentId: Long? = null,
    ) {
        companion object
    }

    /**
     * Request DTO for voucher validation.
     *
     * Used for API requests to validate voucher status and
     * check if it can be redeemed for a specific amount.
     */
    @Serializable
    data class ValidateVoucherRequestDto(
        val voucherNumber: String,
        val requestedAmount: String? = null, // Optional amount to validate against
    ) {
        /**
         * Validates the voucher validation request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(voucherNumber.isNotBlank()) { "Voucher number is required" }

            requestedAmount?.let { amount ->
                try {
                    val amountDouble = amount.toDouble()
                    require(amountDouble > 0.0) { "Requested amount must be positive" }
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Requested amount must be a valid decimal number")
                }
            }
        }
    }

    /**
     * Response DTO for voucher validation operations.
     *
     * Used for API responses containing voucher validation results
     * including status, remaining balance, and redemption eligibility.
     */
    @Serializable
    data class ValidateVoucherResponseDto(
        val isValid: Boolean,
        val voucherNumber: String,
        val isActive: Boolean,
        val remainingValue: String, // BigDecimal as String
        val originalValue: String, // BigDecimal as String
        val canRedeemRequested: Boolean, // If specific amount was requested
        val validationMessage: String,
        val expiryDate: String? = null, // Optional expiry date as String
    ) {
        companion object
    }

    /**
     * Response DTO for voucher redemption history.
     *
     * Used for API responses containing the complete redemption
     * history of a specific voucher.
     */
    @Serializable
    data class VoucherRedemptionHistoryDto(
        val voucherId: Long,
        val voucherNumber: String,
        val redemptions: List<VoucherRedemptionEntryDto>,
        /**
         * ISO 4217 code the redemption amounts are denominated in. Supplied by the server from
         * shop config (`ConfigKey.DEFAULT_CURRENCY`), resolved once per request by the service.
         * The default only keeps non-server construction sites compiling.
         */
        val currency: String = "EUR",
    ) {
        companion object
    }

    /**
     * Individual redemption entry in voucher history.
     *
     * Represents a single redemption transaction for a voucher.
     */
    @Serializable
    data class VoucherRedemptionEntryDto(
        val redemptionId: Long,
        val redeemedAmount: String, // BigDecimal as String
        val redeemedAt: String, // LocalDateTime as String
        val billNumber: String?,
        val employeeNumber: String,
        val remainingValueAfter: String, // BigDecimal as String
    ) {
        companion object
    }

    /**
     * Response DTO for voucher redemption summary.
     *
     * Used for API responses when returning summarized redemption information
     * for a specific time period. Contains the voucher details and redemption
     * transaction information.
     */
    @Serializable
    data class VoucherRedemptionSummaryDto(
        val voucherNumber: String,
        val originalValue: String, // BigDecimal as String
        val redeemedAmount: String, // BigDecimal as String
        val remainingValue: String, // BigDecimal as String
        val redeemedAt: String, // LocalDateTime as String
        val redeemedBy: String, // Employee number who processed the redemption
        val billNumber: String,
        /**
         * ISO 4217 code the value amounts are denominated in. Supplied by the server from shop
         * config (`ConfigKey.DEFAULT_CURRENCY`), resolved once per request by the service. The
         * default only keeps non-server construction sites compiling.
         */
        val currency: String = "EUR",
        /**
         * The Storno bill that reversed this redemption — a plain Storno or the Storno half of a
         * Zahlartwechsel — or null while the redemption stands. admin20 FAIL-B20: a reversed
         * redemption is still listed (it happened) but no longer counts, matching the Z-Bon.
         */
        val reversedByBillNumber: String? = null,
        /** Why [reversedByBillNumber] was booked; [StornoReason.ZAHLARTWECHSEL] for a payment change. */
        val reversalReason: StornoReason? = null,
    ) {
        /** True while no Storno has reversed this redemption; the server's `VoucherRedeemTable.standing()`. */
        val stands: Boolean
            get() = reversedByBillNumber == null

        companion object
    }
}
