/**
 * Ktor implementation of Statistics API client.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorStatisticsApiClient(
    private val httpCall: HttpCall,
) : StatisticsApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/statistics"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    private fun HttpRequestBuilder.commonParams(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ) {
        parameter("start_date", startDate)
        parameter("end_date", endDate)
        if (employeeId != null) parameter("employee_id", employeeId)
    }

    private fun HttpRequestBuilder.paginatedParams(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        trainingOnly: Boolean,
    ) {
        commonParams(startDate, endDate, employeeId)
        page?.let { parameter("page", it.toString()) }
        size?.let { parameter("size", it.toString()) }
        if (trainingOnly) parameter("training_only", "true")
    }

    override suspend fun getSalesOverview(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.SalesOverviewResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/overview")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
                compare?.let { parameter("compare", it) }
                granularity?.let { parameter("granularity", it) }
            }
        }

    override suspend fun getSalesTrends(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ): StatisticsDto.SalesTrendsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/trends")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
            }
        }

    override suspend fun getFinancialOverview(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ): StatisticsDto.FinancialOverviewResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/financial")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
            }
        }

    override suspend fun getCatalog(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.CatalogBreakdownResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/catalog")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
                compare?.let { parameter("compare", it) }
                granularity?.let { parameter("granularity", it) }
            }
        }

    override suspend fun getPromotions(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.PromotionBreakdownResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/promotions")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
                compare?.let { parameter("compare", it) }
                granularity?.let { parameter("granularity", it) }
            }
        }

    override suspend fun getPaginatedBills(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedBillsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/bills")) {
                paginatedParams(startDate, endDate, employeeId, page, size, trainingOnly)
                sort?.let { parameter("sort", it.name) }
                if (!paymentTypes.isNullOrEmpty()) parameter("payment_types", paymentTypes.joinToString(","))
            }
        }

    override suspend fun getPaymentRecords(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
    ): StatisticsDto.PaginatedPaymentsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/payments")) {
                paginatedParams(startDate, endDate, employeeId, page, size, trainingOnly)
                sort?.let { parameter("sort", it.name) }
            }
        }

    override suspend fun getPaginatedTableCards(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedTableCardsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/by-table")) {
                paginatedParams(startDate, endDate, employeeId, page, size, trainingOnly)
                if (!paymentTypes.isNullOrEmpty()) parameter("payment_types", paymentTypes.joinToString(","))
            }
        }

    override suspend fun getTableSessions(
        tableId: Long,
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.TableSessionsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/by-table/$tableId/sessions")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
                if (!paymentTypes.isNullOrEmpty()) parameter("payment_types", paymentTypes.joinToString(","))
            }
        }

    override suspend fun getPaginatedEmployeeGroups(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/by-employee")) {
                paginatedParams(startDate, endDate, employeeId, page, size, trainingOnly)
                sort?.let { parameter("sort", it.name) }
                if (!paymentTypes.isNullOrEmpty()) parameter("payment_types", paymentTypes.joinToString(","))
            }
        }

    override suspend fun getPaginatedSessions(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        trainingOnly: Boolean,
        completed: Boolean?,
    ): StatisticsDto.PaginatedSessionsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/sessions")) {
                paginatedParams(startDate, endDate, employeeId, page, size, trainingOnly)
                completed?.let { parameter("completed", it.toString()) }
            }
        }

    override suspend fun getCanceledItems(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ): StatisticsDto.CanceledItemsResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sales/canceled-items")) {
                commonParams(startDate, endDate, employeeId)
                if (trainingOnly) parameter("training_only", "true")
            }
        }
}
