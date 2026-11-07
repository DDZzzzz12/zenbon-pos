package org.example.project.features.timetracking.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.service.DatevExportService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.datevExportRoutes() {
    val service by inject<DatevExportService>()

    authenticate("auth-jwt") {
        route("/api/timetracking/datev") {
            get("/preview") {
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.generatePreview(year, month))
            }

            get("/export") {
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                val csv = service.generateCsv(year, month)
                val fileName = "Bewegungsdaten_${String.format("%02d", month)}$year.txt"
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                call.respondBytes(csv, ContentType.Text.CSV)
            }

            get("/pdf") {
                val employeeId =
                    call.request.queryParameters["employeeId"]
                        ?: throw validationFailed("employeeId is required")
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                val pdf = service.generatePdfTimesheet(employeeId, year, month)
                val fileName = "Stundenzettel_${String.format("%02d", month)}${year}_$employeeId.pdf"
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                call.respondBytes(pdf, ContentType.Application.Pdf)
            }
        }
    }
}

private fun validationFailed(msg: String) =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        msg,
    )
