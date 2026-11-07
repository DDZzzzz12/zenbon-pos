package org.example.project.features.admin.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.core.error.PosErrorCode
import org.example.project.features.admin.data.service.PermissionDiagnosticsServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.security.RouteRuleCatalog
import org.koin.ktor.ext.inject
import java.util.UUID

fun Route.adminRoutes() {
    val diagnostics by inject<PermissionDiagnosticsServiceImpl>()
    val routeRules by inject<RouteRuleCatalog>()

    authenticate("auth-jwt") {
        route("/api/admin") {
            /**
             * List the route permission rules.
             *
             * Description: Requires ADMIN role. Dumps the server's whole authorization table — every path and method with the role or permission it demands. It is how an admin audits what is actually enforced, rather than what the documentation claims.
             *
             * Tag: admin
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/permissions") {
                call.respond(HttpStatusCode.OK, routeRules.allRules())
            }

            /**
             * What a permission group actually reaches.
             *
             * Description: Requires PERMISSION_ADMIN. Walks every registered rule and reports which the group gets through and which it does not, with the capability it lacks. Answered from the permission-group rows, not the compiled bundles — a group created through the editor exists only in the database. Pass `station` to see the same group as a login-less station of that class sees it; anything else is UNBOUND, the full mask, which is what a person's own request always is.
             *
             * Tag: admin
             * Query: group The group code, e.g. ACCOUNTANT.
             * Query: station Optional terminal role — CASHIER, ORDER_STATION, KITCHEN, CUSTOMER_DISPLAY, KIOSK.
             * Response: 400 No group was named.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN.
             * Response: 404 No such permission group.
             */
            get("/permissions/effective") {
                val group =
                    call.request.queryParameters["group"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "A group code is required, e.g. ?group=ACCOUNTANT",
                        )
                val station = call.request.queryParameters["station"]
                call.respond(HttpStatusCode.OK, diagnostics.effectiveAccess(group, station))
            }

            /**
             * Why one employee is or is not admitted to one route.
             *
             * Description: Requires PERMISSION_ADMIN. Evaluates the request the gate would evaluate and reports the verdict, the rule that produced it and the capability that is missing. It names the case nothing else explains: a route with no registered rule is denied by default, so it 403s while the group's capability list looks complete.
             *
             * Tag: admin
             * Query: employeeId The employee's UUID.
             * Query: path The concrete request path, e.g. /api/employees/summary.
             * Query: method The HTTP method. Defaults to GET.
             * Response: 400 A parameter is missing or malformed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN.
             */
            get("/permissions/explain") {
                val employeeId =
                    call.request.queryParameters["employeeId"]
                        ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "employeeId must be a UUID",
                        )
                val path =
                    call.request.queryParameters["path"]
                        ?.trim()
                        ?.takeIf { it.startsWith("/") }
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "path must be a concrete request path starting with '/'",
                        )
                val method =
                    call.request.queryParameters["method"]
                        ?.trim()
                        .orEmpty()
                        .ifEmpty { "GET" }
                call.respond(HttpStatusCode.OK, diagnostics.explain(employeeId, path, method))
            }
        }
    }
}
