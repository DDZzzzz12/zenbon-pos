/**
 * REST API routes for Statistics operations.
 *
 * Defines HTTP endpoints for business intelligence and analytics operations
 * including sales statistics, product performance, employee metrics, and
 * financial reporting with proper authentication and error handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.statistics.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.TokenClaims
import org.example.project.core.security.heldCapabilities
import org.example.project.features.statistics.domain.service.StatisticsService
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.TestOnlyApi
import org.koin.ktor.ext.inject

/**
 * Parsed common parameters for statistics endpoints.
 */
private data class StatisticsParams(
    val claim: TokenClaims,
    /** Whether this caller holds REPORT_ALL_READ — resolved once, used by every route below. */
    val seesEveryone: Boolean,
    /**
     * Every capability the caller holds. The operator rollups name a colleague by the HR name only
     * for `EMPLOYEE_READ` (`EmployeeHrNames.visibleTo`), so the service needs the set, not a role.
     */
    val capabilities: Set<Capability>,
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val filterEmployeeId: String?,
    val page: Int,
    val pageSize: Int,
    val trainingOnly: Boolean,
    val paymentTypes: List<String>?,
    val sort: StatisticsDto.StatisticsSortOption,
)

/**
 * The window start a statistics query should actually run with.
 *
 * `@TestOnlyApi` rather than private so `StatisticsWindowClampTest` can exercise the rule itself. The
 * route-level tests can only observe a 200, which is returned whether or not the clamp fired — a
 * green test that proves nothing, and one this file has already produced once.
 *
 * @param requested what the caller asked for
 * @param tradingDayStart the last Z-Bon's close, or null when the caller may see the whole history
 *   (REPORT_ALL_READ) or when no Z-Bon has ever been closed
 */
@TestOnlyApi
fun clampToTradingDay(
    requested: LocalDateTime,
    tradingDayStart: LocalDateTime?,
): LocalDateTime =
    when {
        tradingDayStart == null -> requested
        // Asking for a NARROWER window than the trading day is fine — the clamp is a floor on how
        // far back you may look, not an instruction to always return the whole day.
        tradingDayStart <= requested -> requested
        else -> tradingDayStart
    }

/**
 * Parse and validate common statistics query parameters.
 *
 * Throws [PosException] for validation failures; the StatusPages handler
 * converts to application/problem+json.
 */
@OptIn(TestOnlyApi::class)
private suspend fun RoutingContext.parseStatisticsParams(): StatisticsParams {
    val zBonService by call.application.inject<ZBonService>()

    val claim =
        call.getTokenClaims()
            ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.Unauthorized, "Invalid token")

    val startDateStr = call.request.queryParameters["start_date"]
    val endDateStr = call.request.queryParameters["end_date"]
    val filterEmployeeId = call.request.queryParameters["employee_id"]

    // REPORT_ALL_READ is what "may see other people's figures" means. It replaces
    // `EmployeeRole.isManagerOrAdmin(claim.role)`, which decided this from the employee row's role
    // field — so an AUDITOR carrying role CASHIER was refused the filter on a shop they exist to
    // audit, and an ACCOUNTANT carrying role MANAGER was allowed it by accident rather than by grant.
    val capabilities = call.heldCapabilities()
    val seesEveryone = Capability.REPORT_ALL_READ in capabilities
    if (filterEmployeeId != null && !seesEveryone) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.Forbidden,
            "Filtering statistics by employee requires REPORT_ALL_READ",
        )
    }

    if (startDateStr == null || endDateStr == null) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "start_date and end_date query parameters are required",
        )
    }

    val (requestedStart, endDate) =
        try {
            Pair(LocalDateTime.parse(startDateStr), LocalDateTime.parse(endDateStr))
        } catch (e: Exception) {
            throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "Invalid date format. Use ISO-8601 (e.g. 2024-01-01T00:00:00)",
            )
        }

    // Without REPORT_ALL_READ you see your own figures for the CURRENT TRADING DAY, not for any
    // window you care to name. `seesEveryone` already bounds *whose* rows come back; this bounds
    // *when*, which nothing did before — `start_date` was required and otherwise unconstrained.
    //
    // "Today" is the Z-Bon boundary, not midnight. A German restaurant cashes up once a day into a
    // fiscal daily close, and that close is what `/api/z-bon/latest/datetime` reports — the same
    // value the till already asks for to show when the current trading day began. Using midnight
    // would cut a late shift off from its own evening at 00:00, mid-service.
    //
    // A shop that has never closed a Z-Bon gets the 2020-01-01 fallback, so nothing is clamped
    // before the first close. That degradation is deliberate: a new shop must not lose its own
    // figures because it has not cashed up yet.
    //
    // The clamp is SILENT, not a rejection. The client already fetches this boundary for the
    // trading-day label, so it can bound its own period picker; a 403 on a screen staff use daily
    // is a worse failure than a narrow window. It does mean a caller asking for May currently gets
    // today's numbers under a May heading until composeApp's picker is bounded to match.
    // `endTime` crosses the wire as an ISO string; an unparseable one must not silently disable the
    // clamp, so a parse failure leaves the window alone rather than trusting a value we could not
    // read.
    val tradingDayStart =
        if (seesEveryone) {
            null
        } else {
            zBonService.getLatestReportByShop()?.endTime?.let {
                runCatching { LocalDateTime.parse(it) }.getOrNull()
            }
        }
    val startDate = clampToTradingDay(requestedStart, tradingDayStart)

    val page =
        call.request.queryParameters["page"]
            ?.toIntOrNull()
            ?.coerceAtLeast(0) ?: 0
    val pageSize =
        call.request.queryParameters["size"]
            ?.toIntOrNull()
            ?.coerceIn(1, 100) ?: 20
    val trainingOnly = call.request.queryParameters["training_only"]?.toBoolean() ?: false
    val paymentTypesParam = call.request.queryParameters["payment_types"]
    val paymentTypes = paymentTypesParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

    // TRAINING_REPORT_READ already existed for `GET /api/v1/reports/training` and means exactly
    // this. Same set of people as the role check today; separable from ordinary reporting later.
    if (trainingOnly && Capability.TRAINING_REPORT_READ !in capabilities) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.Forbidden,
            "Viewing training data requires TRAINING_REPORT_READ",
        )
    }

    val sortStr = call.request.queryParameters["sort"]?.uppercase()
    val sort =
        try {
            if (sortStr != null) {
                StatisticsDto.StatisticsSortOption.valueOf(sortStr)
            } else {
                StatisticsDto.StatisticsSortOption.RECENT
            }
        } catch (e: IllegalArgumentException) {
            StatisticsDto.StatisticsSortOption.RECENT
        }

    return StatisticsParams(
        claim = claim,
        seesEveryone = seesEveryone,
        capabilities = capabilities,
        startDate = startDate,
        endDate = endDate,
        filterEmployeeId = filterEmployeeId,
        page = page,
        pageSize = pageSize,
        trainingOnly = trainingOnly,
        paymentTypes = paymentTypes,
        sort = sort,
    )
}

/**
 * Configure Statistics routes with authentication and authorization.
 */
fun Routing.statisticsRoutes() {
    val statisticsService by inject<StatisticsService>()

    authenticate("auth-jwt") {
        route("/api/statistics") {
            // =========================== SALES STATISTICS ===========================

            /**
             * Get the sales overview.
             *
             * Description: Headline turnover figures for the period. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Pass `compare=previous` together with `granularity` to add a baseline — a day compares against the same weekday last week, a month against the previous month. `compare` without `granularity` yields no comparison rather than a guess.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/overview") {
                val params = parseStatisticsParams()
                // Read here rather than in parseStatisticsParams(): eight routes share that helper
                // and none of the others take a comparison.
                //
                // compare=previous needs the granularity to pick the right baseline (a day compares
                // to the same weekday last week, a month to the previous month); the server derives
                // whole-vs-same-elapsed itself from the window. compare without granularity yields no
                // comparison rather than a guess.
                val compareGranularity =
                    call.request.queryParameters["compare"]
                        ?.takeIf { it == "previous" }
                        ?.let { call.request.queryParameters["granularity"] }
                val dto =
                    statisticsService.getSalesOverviewSummary(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                        compareGranularity = compareGranularity,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get sales trends.
             *
             * Description: Turnover over time, for the trend chart. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/trends") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getSalesTrends(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List bills for the statistics view.
             *
             * Description: Paged bill rows behind the sales figures. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. `page` (from 0) and `size` (1–100, default 20) page the result, `sort` picks an order (default RECENT, an unknown value falls back to it), and `payment_types` takes a comma-separated list.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/bills") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getPaginatedBills(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        page = params.page,
                        pageSize = params.pageSize,
                        sort = params.sort,
                        trainingOnly = params.trainingOnly,
                        paymentTypes = params.paymentTypes,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get sales grouped by table.
             *
             * Description: One card per table with its turnover for the period. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Pages with `page` and `size`, and `payment_types` narrows by tender.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/by-table") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getPaginatedTableCards(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        page = params.page,
                        pageSize = params.pageSize,
                        trainingOnly = params.trainingOnly,
                        paymentTypes = params.paymentTypes,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List one table's sessions.
             *
             * Description: Drill-down from a table card into its individual sessions in the period. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures.
             *
             * Tag: statistics
             * Path: tableId Table identifier.
             * Response: 400 tableId is not a number, or start_date or end_date is missing or malformed.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/by-table/{tableId}/sessions") {
                val params = parseStatisticsParams()
                val tableId =
                    call.parameters["tableId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                val dto =
                    statisticsService.getTableSessions(
                        tableId = tableId,
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                        paymentTypes = params.paymentTypes,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get sales grouped by employee.
             *
             * Description: Turnover per employee for the period — the basis for tip and performance discussions. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Pages with `page` and `size`, plus `sort` and `payment_types`. Each group carries `employeeName` (the username, kept for older clients) and `displayName`, always filled: the first and last name from the personnel record when the caller holds EMPLOYEE_READ and the employee has a record, otherwise that same username.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/by-employee") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getPaginatedEmployeeGroups(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        page = params.page,
                        pageSize = params.pageSize,
                        sort = params.sort,
                        trainingOnly = params.trainingOnly,
                        paymentTypes = params.paymentTypes,
                        callerCapabilities = params.capabilities,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List payment records.
             *
             * Description: The individual tenders behind the period's turnover, paged. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Pages with `page` and `size`, and `sort` picks an order.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/payments") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getPaymentRecords(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        page = params.page,
                        pageSize = params.pageSize,
                        sort = params.sort,
                        trainingOnly = params.trainingOnly,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List table sessions for the statistics view.
             *
             * Description: Paged sessions in the period. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. `completed=true|false` narrows to settled or still-open sessions; omitting it returns both. `employeeInfo.lastName` is the operator's last name from the personnel record only for a caller holding EMPLOYEE_READ, otherwise the username.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/sessions") {
                val params = parseStatisticsParams()
                val completed = call.request.queryParameters["completed"]?.toBooleanStrictOrNull()
                val dto =
                    statisticsService.getPaginatedSessions(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        page = params.page,
                        pageSize = params.pageSize,
                        trainingOnly = params.trainingOnly,
                        completed = completed,
                        callerCapabilities = params.capabilities,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List cancelled items.
             *
             * Description: Items cancelled in the period — the figure an auditor looks at first, since a high cancellation rate on one employee is the classic till-fraud signal. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Each item carries `employeeName` (the username, kept for older clients) and `displayName`, always filled: the first and last name from the personnel record when the caller holds EMPLOYEE_READ and the employee has a record, otherwise that same username.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/sales/canceled-items") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getCanceledItems(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                        callerCapabilities = params.capabilities,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get the financial overview.
             *
             * Description: Net, VAT and gross totals for the period, split by VAT class. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. Each `paymentMethodBreakdown` row is keyed by the payment type's German name (`paymentMethod`) and also carries its English and Chinese names (`paymentMethodEN`, `paymentMethodCH`, null without a translation) for display only.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/financial") {
                val params = parseStatisticsParams()
                val dto =
                    statisticsService.getFinancialOverview(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get the catalogue breakdown.
             *
             * Description: Turnover by product and category — what actually sold. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. `compare=previous` with `granularity` adds the previous-period baseline.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/catalog") {
                val params = parseStatisticsParams()
                val compareGranularity =
                    call.request.queryParameters["compare"]
                        ?.takeIf { it == "previous" }
                        ?.let { call.request.queryParameters["granularity"] }
                val dto =
                    statisticsService.getCatalogBreakdown(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                        compareGranularity = compareGranularity,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get the promotion breakdown.
             *
             * Description: What each promotion cost and how often it was applied in the period. The required `start_date` and `end_date` query parameters bound the window as ISO-8601 date-times. `employee_id` narrows to one employee and `training_only=true` switches to training-mode data — both are manager-only; without them a caller sees their own figures. `compare=previous` with `granularity` adds the previous-period baseline.
             *
             * Tag: statistics
             * Response: 400 start_date or end_date is missing, or is not an ISO-8601 date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller filtered by employee_id or asked for training_only and is not a MANAGER.
             */
            get("/promotions") {
                val params = parseStatisticsParams()
                val compareGranularity =
                    call.request.queryParameters["compare"]
                        ?.takeIf { it == "previous" }
                        ?.let { call.request.queryParameters["granularity"] }
                val dto =
                    statisticsService.getPromotionBreakdown(
                        startDate = params.startDate,
                        endDate = params.endDate,
                        employeeId = params.claim.employeeId,
                        filterEmployeeId = params.filterEmployeeId,
                        seesEveryone = params.seesEveryone,
                        trainingOnly = params.trainingOnly,
                        compareGranularity = compareGranularity,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}
