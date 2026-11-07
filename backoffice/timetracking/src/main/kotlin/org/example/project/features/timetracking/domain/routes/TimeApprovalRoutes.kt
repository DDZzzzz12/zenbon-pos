package org.example.project.features.timetracking.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.timetracking.domain.service.TimeApprovalService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.timeApprovalRoutes() {
    val service by inject<TimeApprovalService>()

    authenticate("auth-jwt") {
        route("/api/timetracking/approval") {
            post("/submit") {
                val employeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<TimeApprovalDto.SubmitRequestDto>()
                call.respond(HttpStatusCode.Created, service.submitMonth(employeeId ?: "", request))
            }

            post("/{id}/approve") {
                val managerId = call.getEmployeeIdFromJwt()
                val request = call.receive<TimeApprovalDto.ApproveRequestDto>()
                call.respond(HttpStatusCode.OK, service.approveMonth(managerId ?: "", request))
            }

            post("/{id}/reject") {
                val managerId = call.getEmployeeIdFromJwt()
                val request = call.receive<TimeApprovalDto.RejectRequestDto>()
                call.respond(HttpStatusCode.OK, service.rejectMonth(managerId ?: "", request))
            }

            get("/pending") {
                call.respond(HttpStatusCode.OK, service.getPendingApprovals())
            }

            get("/my-status") {
                val employeeId = call.getEmployeeIdFromJwt()
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "year is required",
                        )
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "month is required",
                        )
                call.respond(HttpStatusCode.OK, service.getApprovalStatus(employeeId ?: "", year, month))
            }
        }
    }
}
