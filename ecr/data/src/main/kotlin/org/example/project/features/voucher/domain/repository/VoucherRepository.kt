/**
 * Repository interface for Voucher data operations.
 *
 * Defines the contract for Voucher data access operations. Returns plain entities
 * or nullable types; exceptions propagate naturally to the service layer.
 * Supports CRUD operations, redemption management, and voucher-specific queries
 * for gift voucher and store credit functionality.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.domain.repository

import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.voucher.domain.models.Voucher
import java.math.BigDecimal

/**
 * Repository interface for Voucher operations.
 *
 * Provides data access methods for voucher management including
 * CRUD operations, redemption tracking, balance management,
 * and voucher lifecycle operations.
 */
interface VoucherRepository {
    /**
     * Retrieve all vouchers with optional pagination and filtering.
     *
     * @param limit Maximum number of vouchers to return (null = all)
     * @param offset Number of vouchers to skip (default = 0)
     * @param includeRedeemed Include fully redeemed vouchers (default = true)
     * @return List of vouchers
     */
    fun getAllVouchers(
        limit: Int? = null,
        offset: Int = 0,
        includeRedeemed: Boolean = true,
    ): List<Voucher>

    /**
     * Retrieve a voucher by its ID.
     *
     * @param id Voucher ID to search for
     * @return Voucher entity or null if not found
     */
    fun getVoucherById(id: Long): Voucher?

    /**
     * Retrieve a voucher by its voucher number.
     *
     * @param voucherNumber Voucher number to search for
     * @return Voucher entity or null if not found
     */
    fun getVoucherByNumber(voucherNumber: String): Voucher?

    /**
     * Retrieve active vouchers (with remaining balance).
     *
     * @param limit Maximum number of vouchers to return (null = all)
     * @param offset Number of vouchers to skip (default = 0)
     * @return List of active vouchers
     */
    fun getActiveVouchers(
        limit: Int? = null,
        offset: Int = 0,
    ): List<Voucher>

    /**
     * Search vouchers by voucher number pattern.
     *
     * @param searchTerm Search term to match against voucher numbers
     * @param limit Maximum number of vouchers to return (null = all)
     * @param offset Number of vouchers to skip (default = 0)
     * @return List of matching vouchers
     */
    fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Voucher>

    /**
     * Create a new voucher.
     *
     * @param voucherNumber Unique voucher number
     * @param originalValue Original voucher value
     * @return Created voucher entity
     */
    fun createVoucher(
        voucherNumber: String,
        originalValue: BigDecimal,
    ): Voucher

    /**
     * Check if voucher number exists.
     *
     * @param voucherNumber Voucher number to check
     * @param excludeId Voucher ID to exclude from check (for updates)
     * @return true if exists, false otherwise
     */
    fun voucherNumberExists(
        voucherNumber: String,
        excludeId: Long? = null,
    ): Boolean

    /**
     * Get total voucher statistics.
     *
     * @return Voucher statistics summary
     */
    fun getVoucherStatistics(employeeId: String?): VoucherDto.VoucherStatistics

    /**
     * Get voucher redemption history by voucher ID.
     *
     * @param voucherId Voucher ID to get redemption history for
     * @return List of voucher redemptions
     */
    fun getVoucherRedemptionHistory(voucherId: Long): List<org.example.project.features.voucher.domain.models.VoucherRedeem>

    /**
     * Creates a new voucher for a counter sale (sold directly, no table session).
     *
     * This method handles creating the voucher with order item reference.
     * Session and bill creation are handled by the service layer.
     *
     * @param voucherNumber Generated unique voucher number
     * @param voucherValue The value of the voucher
     * @param orderItemId The order item ID associated with this voucher
     * @return Created voucher entity
     */
    fun createVoucherWithOrderItem(
        voucherNumber: String,
        voucherValue: BigDecimal,
        orderItemId: Long,
    ): Voucher

    /**
     * Count vouchers created in a specific year.
     *
     * Used for generating sequential voucher numbers within a year.
     *
     * @param year Year to count vouchers for (e.g., 2025)
     * @return Voucher count for the year
     */
    fun countVouchersByYear(year: Int): Long

    /**
     * Get vouchers created within a specific date range.
     *
     * @param startDate Start of date range (LocalDateTime)
     * @param endDate End of date range (LocalDateTime)
     * @param employee Optional employee number to filter by
     * @return List of vouchers created in the date range
     */
    fun getVouchersCreatedInRange(
        startDate: kotlinx.datetime.LocalDateTime,
        endDate: kotlinx.datetime.LocalDateTime,
        employee: Employee? = null,
    ): List<Voucher>

    /**
     * Get voucher redemptions within a specific date range.
     *
     * @param startDate Start of date range (LocalDateTime)
     * @param endDate End of date range (LocalDateTime)
     * @param employee Optional employee number to filter by who processed the redemption
     * @return List of voucher redemptions in the date range
     */
    fun getVoucherRedemptionsInRange(
        startDate: kotlinx.datetime.LocalDateTime,
        endDate: kotlinx.datetime.LocalDateTime,
        employee: Employee? = null,
    ): List<org.example.project.features.voucher.domain.models.VoucherRedeem>
}
