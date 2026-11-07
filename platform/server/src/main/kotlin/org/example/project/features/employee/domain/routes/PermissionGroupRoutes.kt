package org.example.project.features.employee.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getTokenClaims
import org.example.project.features.employee.data.service.PermissionGroupServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import java.util.UUID

/**
 * Managing permission groups — the Stage 5 API behind adminApp's group editor.
 *
 * Every route requires `PERMISSION_ADMIN`, which is `Assignability.RESERVED` and therefore held only
 * by the ADMIN group and grantable by no group edit. That is what stops "may manage groups" from
 * becoming a way to acquire it.
 *
 * The guardrails live in [PermissionGroupServiceImpl], not here: routes extract and delegate.
 */
fun Routing.permissionGroupRoutes() {
    val service by inject<PermissionGroupServiceImpl>()

    authenticate("auth-jwt") {
        route("/api/admin/permission-groups") {
            /**
             * List every permission group.
             *
             * Description: Requires PERMISSION_ADMIN. Each group reports its capabilities, whether it is a seeded system group (capabilities fixed, not deletable) and how many employees point at it.
             *
             * Tag: permission-groups
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN.
             */
            get {
                call.respond(HttpStatusCode.OK, service.list())
            }

            /**
             * Every capability the server knows, for the editor's picker.
             *
             * Description: Requires PERMISSION_ADMIN. `assignable` is false for reserved capabilities, which no group edit may confer — the editor greys them out rather than letting an operator pick one and collect a rejection.
             *
             * Tag: permission-groups
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN.
             */
            get("/capabilities") {
                call.respond(HttpStatusCode.OK, service.capabilityCatalog())
            }

            /**
             * Read one permission group.
             *
             * Tag: permission-groups
             * Path: code The group's stable code, e.g. ACCOUNTANT.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN.
             * Response: 404 No group carries that code.
             */
            get("/{code}") {
                call.respond(HttpStatusCode.OK, service.get(requireCode(call.parameters["code"])))
            }

            /**
             * Create a permission group.
             *
             * Description: Requires PERMISSION_ADMIN. The code is normalised to upper case and is immutable afterwards — employees point at a group by code, so renaming one would silently unassign every member. Reserved capabilities are refused, and you cannot grant a capability you do not hold yourself.
             *
             * Tag: permission-groups
             * Response: 400 The code is malformed, or a capability name is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN, or the set contains a reserved capability, or one the caller does not hold.
             * Response: 409 That code is already taken.
             */
            post {
                val request = call.receive<PermissionGroupDto.CreateGroupRequestDto>()
                call.respond(HttpStatusCode.Created, service.create(request, actorId()))
            }

            /**
             * Update a permission group's label or capabilities.
             *
             * Description: Requires PERMISSION_ADMIN. A seeded system group's capabilities are fixed — its name and description stay editable. An edit that would leave no active employee holding PERMISSION_ADMIN or SYSTEM_CONFIG is refused, as is one that removes either from your own group: you would not be able to undo it.
             *
             * Tag: permission-groups
             * Path: code The group's stable code.
             * Response: 400 A capability name is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN; or the group is a system group; or the set contains a reserved capability or one the caller does not hold; or the edit would lock somebody out.
             * Response: 404 No group carries that code.
             */
            put("/{code}") {
                val request = call.receive<PermissionGroupDto.UpdateGroupRequestDto>()
                call.respond(HttpStatusCode.OK, service.update(requireCode(call.parameters["code"]), request, actorId()))
            }

            /**
             * Delete a permission group.
             *
             * Description: Requires PERMISSION_ADMIN. A seeded system group cannot be deleted, and neither can one that still has members — an employee with no group resolves to no capabilities and can reach nothing, so they must be reassigned first.
             *
             * Tag: permission-groups
             * Path: code The group's stable code.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold PERMISSION_ADMIN, or the group is a system group.
             * Response: 404 No group carries that code.
             * Response: 409 The group still has members.
             */
            delete("/{code}") {
                service.delete(requireCode(call.parameters["code"]), actorId())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun requireCode(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "A group code is required",
        )

private suspend fun io.ktor.server.routing.RoutingContext.actorId(): UUID {
    val claims =
        call.getTokenClaims()
            ?: throw PosException(
                PosErrorCode.AUTH_INVALID_CREDENTIALS,
                HttpStatusCode.Unauthorized,
                "Invalid credentials",
            )
    return runCatching { UUID.fromString(claims.employeeId) }.getOrNull()
        ?: throw PosException(
            PosErrorCode.AUTH_INVALID_CREDENTIALS,
            HttpStatusCode.Unauthorized,
            "Invalid credentials",
        )
}
