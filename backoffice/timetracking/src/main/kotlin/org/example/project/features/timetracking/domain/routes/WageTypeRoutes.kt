package org.example.project.features.timetracking.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.timetracking.WageTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.service.WageTypeService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.wageTypeRoutes() {
    val service by inject<WageTypeService>()

    authenticate("auth-jwt") {
        route("/api/wagetypes") {
            get {
                call.respond(HttpStatusCode.OK, service.getAll())
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: throw idRequired()
                call.respond(HttpStatusCode.OK, service.getById(id))
            }

            post {
                val request = call.receive<WageTypeDto.CreateWageTypeRequestDto>()
                call.respond(HttpStatusCode.Created, service.create(request))
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: throw idRequired()
                val request = call.receive<WageTypeDto.UpdateWageTypeRequestDto>()
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
