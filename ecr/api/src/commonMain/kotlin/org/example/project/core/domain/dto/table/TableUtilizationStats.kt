/**
 * Data Transfer Objects for table utilization statistics.
 *
 * Container object for all table utilization-related DTOs including
 * operational metrics and business intelligence data structures.
 * Follows the standard object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.table

import kotlinx.serialization.Serializable

/**
 * Container object for all table utilization statistics DTOs.
 *
 * Organizes DTOs by functionality: metrics, reports, and analytics
 * to maintain clear separation of concerns and consistent API structure.
 */
object TableUtilizationStatsDto {
    /**
     * Response DTO for table utilization statistics.
     *
     * Provides operational metrics for business intelligence
     * and management reporting on restaurant table usage.
     */
    @Serializable
    data class TableUtilizationStatsResponseDto(
        /** Total number of tables in the system */
        val totalTables: Int,
        /** Number of currently available (free) tables */
        val availableTables: Int,
        /** Number of currently occupied tables */
        val occupiedTables: Int,
        /** Number of currently reserved tables */
        val reservedTables: Int,
        /** Current utilization percentage */
        val utilizationPercentage: Double,
        /** Tables by type breakdown */
        val tablesByType: Map<String, Int>,
    )

    /**
     * Detailed utilization analytics DTO.
     *
     * Provides extended metrics and time-based analytics
     * for comprehensive business reporting.
     */
    @Serializable
    data class DetailedUtilizationAnalyticsDto(
        /** Basic utilization stats */
        val basicStats: TableUtilizationStatsResponseDto,
        /** Hourly utilization pattern */
        val hourlyUtilization: Map<Int, Double>,
        /** Peak hours information */
        val peakHours: List<Int>,
        /** Low utilization hours */
        val lowUtilizationHours: List<Int>,
        /** Average session duration by table type */
        val avgSessionDurationByType: Map<String, Double>,
        /** Revenue per table type */
        val revenueByTableType: Map<String, Double>,
    )

    /**
     * Historical utilization trends DTO.
     *
     * Provides time-series data for trend analysis
     * and capacity planning.
     */
    @Serializable
    data class HistoricalUtilizationTrendsDto(
        /** Daily utilization over time period */
        val dailyUtilization: Map<String, Double>, // ISO date string -> utilization %
        /** Weekly patterns */
        val weeklyPattern: Map<String, Double>, // Day of week -> avg utilization %
        /** Monthly trends */
        val monthlyTrends: Map<String, Double>, // Month -> avg utilization %
        /** Seasonal variations */
        val seasonalVariations: Map<String, Double>, // Season -> avg utilization %
    )
}
