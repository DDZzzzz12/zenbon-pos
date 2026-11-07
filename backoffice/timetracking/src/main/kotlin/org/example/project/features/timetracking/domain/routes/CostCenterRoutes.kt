package org.example.project.features.timetracking.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.service.CostCenterService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.costCenterRoutes() {
    val service by inject<CostCenterService>()

    authenticate("auth-jwt") {
        route("/api/costcenters") {
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, service.getAll(includeInactive))
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: throw idRequired()
                call.respond(HttpStatusCode.OK, service.getById(id))
            }

            post {
                val request = call.receive<CostCenterDto.CreateCostCenterRequestDto>()
                call.respond(HttpStatusCode.Created, service.create(request))
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: throw idRequired()
                val request = call.receive<CostCenterDto.UpdateCostCenterRequestDto>()
                call.respond(HttpStatusCode.OK, service.update(id, request))
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: throw idRequired()
                service.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun idRequired() =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        "id is required",
    )
