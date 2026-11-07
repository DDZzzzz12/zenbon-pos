/**
 * REST API routes for VAT management operations.
 *
 * Throws PosException directly on validation/auth errors; the global StatusPages
 * plugin emits application/problem+json responses. Service throws PosException
 * directly (Stage B Batch 4) — no Result matching here.
 */
package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.service.VatService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VatRoutes")

/**
 * Configures VAT-related API routes.
 */
fun Routing.vatRoutes() {
    val vatService by inject<VatService>()
    authenticate("auth-jwt") {
        route("/api/vat") {
            /**
             * List VAT rates.
             *
             * Description: The VAT classes and their percentage rates. Any authenticated employee may read them — every price calculation and receipt depends on them.
             *
             * Tag: vat
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                logger.debug("Retrieving all VAT rates")
                call.respond(HttpStatusCode.OK, vatService.getAllVatRates())
            }

            /**
             * Create a VAT rate.
             *
             * Description: Requires MANAGER role. The rate is held in percent units (19.00 means 19 %). Existing receipts keep the rate they were signed with; a new rate only affects future sales.
             *
             * Tag: vat
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 That VAT class already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<VatDto.CreateVatRequestDto>()
                logger.info("Creating new VAT rate: class=${request.vatClass}, rate=${request.vatRate}")
                val response = vatService.createVatRate(userId, request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Update a VAT rate.
             *
             * Description: Requires MANAGER role. Changing a rate is a fiscally significant act — it applies from now on and never rewrites bills already signed on the TSE.
             *
             * Tag: vat
             * Path: id VAT rate identifier.
             * Response: 400 id is missing, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No VAT rate with that id.
             */
            put("/{id}") {
                val vatIdParam =
                    call.parameters["id"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing VAT ID parameter",
                        )
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<VatDto.UpdateVatRequestDto>()
                logger.info("Updating VAT rate: $vatIdParam")
                val response = vatService.updateVatRate(userId, vatIdParam, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Delete a VAT rate.
             *
             * Description: Requires MANAGER role. A rate still referenced by products or by the VAT mapping cannot be removed, since receipts and the DSFinV-K export must keep resolving it.
             *
             * Tag: vat
             * Path: id VAT rate identifier.
             * Response: 400 id is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No VAT rate with that id.
             * Response: 409 The VAT rate is still referenced and cannot be deleted.
             */
            delete("/{id}") {
                val vatIdParam =
                    call.parameters["id"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing VAT ID parameter",
                        )
                val userId = call.getEmployeeIdFromJwt()
                logger.info("Deleting VAT rate: $vatIdParam")
                vatService.deleteVatRate(userId, vatIdParam)
                call.respond(HttpStatusCode.OK, "VAT rate deleted successfully")
            }
        }
    }
}
