/**
 * Implementation of Voucher repository using Exposed ORM.
 *
 * Provides data access operations for Voucher entities. Returns plain entities;
 * exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.voucher.domain.repository.VoucherRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal

/**
 * Exposed ORM implementation of VoucherRepository.
 *
 * Handles all database operations for Voucher entities including
 * CRUD operations, redemption tracking, balance management,
 * and validation.
 */
class VoucherRepositoryImpl : VoucherRepository {
    override fun getAllVouchers(
        limit: Int?,
        offset: Int,
        includeRedeemed: Boolean,
    ): List<Voucher> {
        var query = Voucher.all()

        // Filter out fully redeemed if requested
        if (!includeRedeemed) {
            query = Voucher.find { VoucherTable.fullyRedeemedAt.isNull() }
        }

        // Apply pagination
        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(offset.toLong())
        } else if (limit != null) {
            query = query.limit(count = limit)
        }

        return query.toList()
    }

    override fun getVoucherById(id: Long): Voucher? = Voucher.findById(id)

    override fun getVoucherByNumber(voucherNumber: String): Voucher? = Voucher.find { VoucherTable.voucherNumber eq voucherNumber }.firstOrNull()

    override fun getActiveVouchers(
        limit: Int?,
        offset: Int,
    ): List<Voucher> {
        var query =
            Voucher.find {
                (VoucherTable.remainingValue greater BigDecimal.ZERO) and
                    VoucherTable.fullyRedeemedAt.isNull() and
                    (VoucherTable.status eq VoucherStatus.ACTIVE)
            }

        // Apply pagination
        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(offset.toLong())
        } else if (limit != null) {
            query = query.limit(count = limit)
        }

        return query.toList()
    }

    override fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int?,
        offset: Int,
    ): List<Voucher> {
        var query =
            Voucher.find {
                VoucherTable.voucherNumber.lowerCase() like "%${searchTerm.lowercase()}%"
            }

        // Apply pagination
        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(offset.toLong())
        } else if (limit != null) {
            query = query.limit(count = limit)
        }

        return query.toList()
    }

    override fun createVoucher(
        voucherNumber: String,
        originalValue: BigDecimal,
    ): Voucher =
        Voucher.new {
            this.voucherNumber = voucherNumber
            this.originalValue = originalValue
            this.remainingValue = originalValue
            this.createdAt = currentTimeStamp()
        }

    override fun voucherNumberExists(
        voucherNumber: String,
        excludeId: Long?,
    ): Boolean {
        val query =
            if (excludeId != null) {
                Voucher.find {
                    (VoucherTable.voucherNumber eq voucherNumber) and (VoucherTable.id neq excludeId)
                }
            } else {
                Voucher.find { VoucherTable.voucherNumber eq voucherNumber }
            }

        return query.count() > 0
    }

    override fun getVoucherStatistics(employeeId: String?): VoucherDto.VoucherStatistics {
        val totalVouchers = Voucher.count()
        // "Active" means outstanding liability: still ACTIVE *and* not yet fully redeemed. Counting
        // only `fullyRedeemedAt IS NULL` used to include invalidated vouchers, so a storno'd voucher
        // inflated the count while contributing nothing to totalValueRemaining below — the count and
        // the value disagreed about what "active" meant. The two predicates must stay identical.
        val activeVouchers =
            Voucher
                .find { (VoucherTable.status eq VoucherStatus.ACTIVE) and VoucherTable.fullyRedeemedAt.isNull() }
                .count()
        val fullyRedeemedVouchers = Voucher.find { VoucherTable.fullyRedeemedAt.isNotNull() }.count()
        val invalidatedVouchers = Voucher.find { VoucherTable.status eq VoucherStatus.INVALIDATED }.count()

        // Calculate value aggregates
        val totalValueIssued =
            VoucherTable
                .select(VoucherTable.originalValue.sum())
                .firstOrNull()
                ?.get(VoucherTable.originalValue.sum()) ?: BigDecimal.ZERO

        // Same predicate as activeVouchers above, so the count and the value describe one set. A
        // fully-redeemed ACTIVE voucher carries a zero remaining balance, so excluding it does not
        // move this sum — it only stops the two figures being derived from different populations.
        val totalValueRemaining =
            VoucherTable
                .select(VoucherTable.remainingValue.sum())
                .where { (VoucherTable.status eq VoucherStatus.ACTIVE) and VoucherTable.fullyRedeemedAt.isNull() }
                .firstOrNull()
                ?.get(VoucherTable.remainingValue.sum()) ?: BigDecimal.ZERO

        val totalValueInvalidated =
            VoucherTable
                .select(VoucherTable.originalValue.sum())
                .where { VoucherTable.status eq VoucherStatus.INVALIDATED }
                .firstOrNull()
                ?.get(VoucherTable.originalValue.sum()) ?: BigDecimal.ZERO

        val totalValueRedeemed = totalValueIssued.subtract(totalValueRemaining).subtract(totalValueInvalidated)

        return VoucherDto.VoucherStatistics(
            totalVouchers = totalVouchers,
            activeVouchers = activeVouchers,
            fullyRedeemedVouchers = fullyRedeemedVouchers,
            totalValueIssued = totalValueIssued.toDecimalString(),
            totalValueRedeemed = totalValueRedeemed.toDecimalString(),
            totalValueRemaining = totalValueRemaining.toDecimalString(),
            invalidatedVouchers = invalidatedVouchers,
            totalValueInvalidated = totalValueInvalidated.toDecimalString(),
        )
    }

    override fun getVoucherRedemptionHistory(voucherId: Long): List<VoucherRedeem> =
        VoucherRedeem
            .find { VoucherRedeemTable.voucherId eq voucherId }
            .orderBy(VoucherRedeemTable.redeemedAt to SortOrder.DESC)
            .toList()

    override fun createVoucherWithOrderItem(
        voucherNumber: String,
        voucherValue: BigDecimal,
        orderItemId: Long,
    ): Voucher {
        // Find order item
        val orderItem =
            OrderItem.findById(orderItemId)
                ?: throw NoSuchElementException("Order item not found: $orderItemId")

        // Check if voucher number already exists
        val existing = Voucher.find { VoucherTable.voucherNumber eq voucherNumber }.firstOrNull()
        if (existing != null) {
            throw IllegalArgumentException("Voucher number already exists: $voucherNumber")
        }

        // Create voucher
        return Voucher.new {
            this.voucherNumber = voucherNumber
            this.orderItem = orderItem
            this.originalValue = voucherValue
            this.remainingValue = voucherValue
            this.createdAt = currentTimeStamp()
        }
    }

    override fun countVouchersByYear(year: Int): Long {
        // Calculate start and end of the year
        val startOfYear = kotlinx.datetime.LocalDateTime(year, 1, 1, 0, 0, 0, 0)
        val endOfYear = kotlinx.datetime.LocalDateTime(year + 1, 1, 1, 0, 0, 0, 0)

        // Count vouchers created within the year
        return Voucher
            .find {
                VoucherTable.createdAt greaterEq startOfYear and
                    (VoucherTable.createdAt less endOfYear)
            }.count()
    }

    override fun getVouchersCreatedInRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
    ): List<Voucher> {
        // Base query: vouchers created in date range
        val query =
            Voucher.find {
                VoucherTable.createdAt greaterEq startDate and
                    (VoucherTable.createdAt lessEq endDate)
            }

        // Filter by employee if provided
        // `employee` is already the resolved TARGET: `resolveTargetEmployee` hands down null for
        // "everyone" and a specific employee otherwise. Re-deriving the same decision from that
        // employee's ROLE second-guessed it — and got it backwards for anyone whose role outranks
        // their capabilities, handing them every row after the service had scoped them to their own.
        return if (employee != null) {
            query.filter { voucher ->
                // Get employee number from orderItem -> order -> employee
                voucher.orderItem.getEmployee() == employee
            }
        } else {
            query.toList()
        }
    }

    override fun getVoucherRedemptionsInRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
    ): List<VoucherRedeem> {
        // Base query: redemptions in date range
        val query =
            VoucherRedeem.find {
                VoucherRedeemTable.redeemedAt greaterEq startDate and
                    (VoucherRedeemTable.redeemedAt lessEq endDate)
            }

        // Filter by employee if provided
        // `employee` is already the resolved TARGET: `resolveTargetEmployee` hands down null for
        // "everyone" and a specific employee otherwise. Re-deriving the same decision from that
        // employee's ROLE second-guessed it — and got it backwards for anyone whose role outranks
        // their capabilities, handing them every row after the service had scoped them to their own.
        return if (employee != null) {
            query.filter { redemption ->
                // Get employee number from bill -> employeeId
                redemption.payment.bill.employeeId == employee
            }
        } else {
            query.toList()
        }
    }
}
