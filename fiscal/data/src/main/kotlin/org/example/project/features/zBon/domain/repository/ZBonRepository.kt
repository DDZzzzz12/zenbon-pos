/**
 * Repository interface for ZBon (Z-report) data access operations.
 *
 * Defines contract for daily closing report persistence operations including
 * creation, retrieval, and querying by various criteria. Supports financial
 * reconciliation and business reporting requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import java.util.UUID

/**
 * Repository interface for ZBon (Z-report) operations.
 *
 * Provides data access layer for daily closing reports including
 * CRUD operations and business-specific queries for financial
 * reconciliation and reporting.
 */
interface ZBonRepository {
    /**
     * Retrieves all Z-reports from the database.
     *
     * Returns complete list of all daily closing reports for
     * comprehensive business analysis and historical review.
     *
     * @return List of all ZBon entities
     */
    fun getAllReports(): List<ZBon>

    /**
     * Retrieves a specific Z-report by its unique identifier.
     *
     * Used for detailed report viewing and analysis of specific
     * daily closing periods.
     *
     * @param id Unique identifier of the Z-report
     * @return ZBon entity if found, null otherwise
     */
    fun getReportById(id: Long): ZBon?

    /**
     * Retrieves Z-reports for a specific shop location.
     *
     * Enables multi-location business analysis and shop-specific
     * financial reporting and reconciliation.
     *
     * @param shopInfoId Unique identifier of the shop
     * @return List of ZBon entities for the shop
     */
    fun getReportsByShop(shopInfoId: Long): List<ZBon>

    /**
     * Retrieves Z-reports created by a specific employee.
     *
     * Supports employee performance analysis and audit trail
     * for report generation accountability.
     *
     * @param employeeId Unique identifier of the employee
     * @return List of ZBon entities created by the employee
     */
    fun getReportsByEmployee(employeeId: UUID): List<ZBon>

    /**
     * Retrieves Z-reports within a specific date range.
     *
     * Essential for period-based financial analysis, accounting
     * reconciliation, and business performance reporting.
     *
     * @param startDate Beginning of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @return List of ZBon entities in the date range
     */
    fun getReportsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<ZBon>

    /**
     * Retrieves the most recent Z-report for a specific shop.
     *
     * Used for determining current business state and starting
     * points for new reporting periods.
     *
     * @return Latest ZBon entity if found, null otherwise
     */
    fun getLatestReportByShop(): ZBon?

    /**
     * Creates a new Z-report with comprehensive validation.
     *
     * Validates business rules, ensures data integrity, and creates
     * complete daily closing report with all financial summaries.
     *
     * @param employee Reference to the employee creating the report
     * @param shopInfo Reference to the shop location
     * @return Created ZBon entity
     */
    fun createReport(
        employee: Employee,
        shopInfo: ShopInfo,
    ): ZBon

    /**
     * Checks if a Z-report exists for a specific time period and shop.
     *
     * Prevents duplicate report creation and ensures proper
     * business period management.
     *
     * @param shopInfoId Shop identifier
     * @param startTime Period start time
     * @param endTime Period end time
     * @return True if a report exists for the period
     */
    fun existsForPeriod(
        shopInfoId: Long,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
    ): Boolean

    /**
     * Retrieves Z-Bon history summaries for a specified date range.
     *
     * Returns lightweight summary data (id, shop name, time range, total revenue)
     * optimized for list views and historical overview displays without loading
     * complete Z-Bon details.
     *
     * @param startDate Start date in ISO format (YYYY-MM-DD)
     * @param endDate End date in ISO format (YYYY-MM-DD)
     * @return List of ZBonSummeryDto
     */
    fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto>

    /**
     * Retrieves all closed Z-Bons whose endTime falls in [start, end).
     *
     * Used by the monthly-close roll-up to collect Z-Bons for a reporting period.
     *
     * @param start Period start (inclusive)
     * @param end Period end (exclusive)
     * @return List of ZBon entities sorted by startTime ascending
     */
    fun findClosedInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<ZBon>

    /**
     * Retrieves the most recent closed Z-Bon whose endTime is strictly before [before].
     *
     * Used by monthly-close chain-gap detection to find the prior Z-Bon anchor.
     *
     * @param before Upper bound (exclusive) for endTime
     * @return Latest ZBon entity with endTime < before, or null if none exists
     */
    fun findLastClosedBefore(before: LocalDateTime): ZBon?

    /**
     * Retrieves the most recently created Z-Bon reports.
     *
     * Used by the admin dashboard to show the latest Z-Bon closings.
     *
     * @param limit Maximum number of reports to return
     * @return List of ZBon entities ordered by createdAt descending
     */
    fun getRecentReports(limit: Int): List<ZBon>
}
