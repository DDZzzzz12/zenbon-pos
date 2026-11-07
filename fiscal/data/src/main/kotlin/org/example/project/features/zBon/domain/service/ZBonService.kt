/**
 * Service interface for ZBon (Z-report) business logic operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from `Result<T, Error>` in Stage B Batch 3.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.auth.Capability

/**
 * Service interface for ZBon (Z-report) operations.
 *
 * Handles business logic for daily closing reports including validation,
 * data transformation, and business rule enforcement. Coordinates between
 * repository layer and API responses using proper DTO mapping.
 */
interface ZBonService {
    /** Retrieves all Z-reports. */
    suspend fun getAllReports(): List<ZBonDto.ZBonResponseDto>

    /** Retrieves a specific Z-report by its unique identifier. */
    suspend fun getReportById(
        id: Long,
        withKassenbuchData: Boolean = false,
    ): ZBonDto.ZBonResponseDto

    /** Retrieves Z-reports within a specific date range with business validation. */
    suspend fun getReportsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<ZBonDto.ZBonResponseDto>

    /** Retrieves the most recent Z-report (or null if none). */
    suspend fun getLatestReportByShop(): ZBonDto.ZBonResponseDto?

    /**
     * Creates a new Z-report. With [countedAmount] (the drawer count) the Kassendifferenz is booked
     * first — but only after the closing's refusable preconditions have passed.
     *
     * [tipPayouts] (F58) are employee-tip payouts booked inside the closing's own transaction, so
     * they belong to this closing and are rolled back with it. They need `CASHBOOK_WRITE` in
     * [heldCapabilities] (403 otherwise) and each must fit the employee's employee tips of the period
     * being closed (409 otherwise) — all checked before anything, the drawer count included, is
     * written. The count is then compared against the drawer as it stands after the payouts.
     */
    suspend fun createReport(
        employeeId: String,
        deviceId: String,
        countedAmount: String? = null,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto> = emptyList(),
        heldCapabilities: Set<Capability> = emptySet(),
        topUp: ZBonDto.ClosingTopUpRequestDto? = null,
    ): ZBonDto.ZBonResponseDto

    /**
     * The employee tips (TrinkgeldAN) of the still-open period per employee, split cash/card — what
     * the closing dialog offers to pay out in the closing (F58).
     */
    suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto>

    /** Who would sign a closing triggered from [deviceId], and whether a daily Geldtransit is configured. */
    suspend fun getClosingReadiness(deviceId: String): ZBonDto.ClosingReadinessDto

    /** Retrieves Z-Bon history summaries for a specified date range (YYYY-MM-DD strings). */
    suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto>

    /** Retrieves the most recently created Z-Bon summaries. */
    suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto>

    /** Returns the stored Z-Bon PDF bytes (regenerates on miss). */
    suspend fun getZBonPdfBytes(zbonId: Long): ByteArray
}
