package org.example.project.features.monthlyclose.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.error.PosErrorCode
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun Route.monthlyCloseRoutes() {
    val service by inject<MonthlyCloseService>()

    authenticate("auth-jwt") {
        route("/api/v1/monthly-close") {
            /**
             * Get the monthly close report.
             *
             * Description: Requires MANAGER role. The period's closing figures — turnover, VAT and export readiness — for handing the month to the accountant. `periodStart` and `periodEnd` are required as ISO date-times. `comparePreviousPeriod` defaults to true and adds the prior period's figures; `cadence` defaults to MONTHLY.
             *
             * Tag: monthly-close
             * Response: 400 periodStart or periodEnd is missing, or is not an ISO date-time.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val periodStart =
                    call.request.queryParameters["periodStart"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val periodEnd =
                    call.request.queryParameters["periodEnd"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val compare =
                    call.request.queryParameters["comparePreviousPeriod"]
                        ?.toBooleanStrictOrNull() ?: true
                val cadence =
                    call.request.queryParameters["cadence"]
                        ?.let { runCatching { DatevExportCadence.valueOf(it) }.getOrNull() }
                        ?: DatevExportCadence.MONTHLY

                if (periodStart == null || periodEnd == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "periodStart and periodEnd required (ISO LocalDateTime)",
                    )
                }

                val claim =
                    call.getTokenClaims()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.Unauthorized, "Invalid token")

                val dto = service.getMonthlyClose(periodStart, periodEnd, claim.employeeId, compare, cadence)
                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}
