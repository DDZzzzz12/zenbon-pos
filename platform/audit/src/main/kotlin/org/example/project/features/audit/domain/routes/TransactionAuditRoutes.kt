/**
 * REST API routes for Transaction Audit operations.
 *
 * Defines HTTP endpoints for querying transaction audit logs. Routes throw
 * [PosException] on parameter-validation failures; service errors propagate
 * via StatusPages. Migrated from Result<T, Error> in Stage B Batch 9.
 */
package org.example.project.features.audit.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TransactionAuditRoutes")

/**
 * Configure transaction audit routes.
 */
fun Route.transactionAuditRoutes() {
    val transactionAuditService by inject<TransactionAuditService>()

    authenticate("auth-jwt") {
        route("/api/audit/transactions") {
            /**
             * Query transaction audit logs.
             *
             * Description: Requires MANAGER role. The authorization trail — who did what, and who approved it. Every filter is an optional query parameter: `employeeNumber`, `authorizingManagerId`, `operation`, `entityType`, `entityId`, `fromDate` and `toDate`. `limit` defaults to 100. Use the paginated route for large ranges.
             *
             * Tag: audit
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val query =
                    TransactionAuditDto.TransactionAuditQueryDto(
                        employeeId = call.request.queryParameters["employeeNumber"],
                        authorizingManagerId = call.request.queryParameters["authorizingManagerId"],
                        operation = call.request.queryParameters["operation"],
                        entityType = call.request.queryParameters["entityType"],
                        entityId = call.request.queryParameters["entityId"],
                        fromDate = call.request.queryParameters["fromDate"],
                        toDate = call.request.queryParameters["toDate"],
                        limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                    )
                val logs = transactionAuditService.getTransactionLogs(query)
                logger.info("Retrieved ${logs.size} transaction audit logs")
                call.respond(HttpStatusCode.OK, logs)
            }

            /**
             * Query transaction audit logs, paginated.
             *
             * Description: Requires MANAGER role. The same trail with a wider filter set — `employeeId`, `employeeNumber`, `operation`, `entityType`, `entityId`, `entityName`, `fromDate`, `toDate`, a free-text `search`, `amountMin` and `amountMax` as decimal strings, and `authorizationType`. Pages with `page` (from 0) and `size` (1–100, default 20).
             *
             * Tag: audit
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/paginated") {
                val query =
                    TransactionAuditDto.PaginatedTransactionAuditQueryDto(
                        employeeId = call.request.queryParameters["employeeId"],
                        employeeNumber = call.request.queryParameters["employeeNumber"],
                        operation = call.request.queryParameters["operation"],
                        entityType = call.request.queryParameters["entityType"],
                        entityId = call.request.queryParameters["entityId"],
                        entityName = call.request.queryParameters["entityName"],
                        fromDate = call.request.queryParameters["fromDate"],
                        toDate = call.request.queryParameters["toDate"],
                        search = call.request.queryParameters["search"],
                        amountMin = call.request.queryParameters["amountMin"]?.takeIf { it.isNotBlank() },
                        amountMax = call.request.queryParameters["amountMax"]?.takeIf { it.isNotBlank() },
                        authorizationType = call.request.queryParameters["authorizationType"],
                        page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                        pageSize = (call.request.queryParameters["size"]?.toIntOrNull() ?: 20).coerceIn(1, 100),
                    )
                val response = transactionAuditService.getPaginatedTransactionLogs(query)
                logger.info("Retrieved paginated transaction audit logs: page ${query.page}")
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
