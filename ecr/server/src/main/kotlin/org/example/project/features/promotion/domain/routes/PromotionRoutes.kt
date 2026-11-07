package org.example.project.features.promotion.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.promotion.domain.service.PromotionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

fun Route.promotionRoutes() {
    val promotionService by inject<PromotionService>()

    authenticate("auth-jwt") {
        route("/api/promotions") {
            /**
             * List active promotions.
             *
             * Description: Open to any authenticated employee — the POS needs the currently valid promotions to apply them at the till. Expired and deactivated ones are excluded.
             *
             * Tag: promotions
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                call.respond(HttpStatusCode.OK, promotionService.getActivePromotions())
            }

            /**
             * List all promotions.
             *
             * Description: Requires the capability RoutePermissions names for this route. Includes expired and deactivated promotions, for the management screen.
             *
             * Tag: promotions
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/all") {
                call.respond(HttpStatusCode.OK, promotionService.getAllPromotions())
            }

            /**
             * Get a promotion by ID.
             *
             * Description: Requires the capability RoutePermissions names for this route.
             *
             * Tag: promotions
             * Path: id Promotion identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No promotion with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid promotion ID",
                        )
                call.respond(HttpStatusCode.OK, promotionService.getPromotionById(id))
            }

            /**
             * Create a promotion.
             *
             * Description: Requires the capability RoutePermissions names for this route. The creating employee is taken from the JWT. A promotion applies to sales made after it is created — it never re-prices bills already settled.
             *
             * Tag: promotions
             * Response: 400 The request body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            post {
                val request =
                    try {
                        call.receive<PromotionDto.CreatePromotionRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val employeeId = call.getEmployeeIdFromJwt()
                call.respond(HttpStatusCode.Created, promotionService.createPromotion(employeeId, request))
            }

            /**
             * Update a promotion.
             *
             * Description: Requires the capability RoutePermissions names for this route. Changes apply to future sales only; discounts already granted stay on their bills.
             *
             * Tag: promotions
             * Path: id Promotion identifier.
             * Response: 400 id is not a number, or the body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No promotion with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid promotion ID",
                        )
                val request =
                    try {
                        call.receive<PromotionDto.UpdatePromotionRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                call.respond(HttpStatusCode.OK, promotionService.updatePromotion(id, request))
            }

            /**
             * Deactivate a promotion.
             *
             * Description: Requires the capability RoutePermissions names for this route. Nothing is erased — the promotion is flagged inactive so bills that used it keep resolving it.
             *
             * Tag: promotions
             * Path: id Promotion identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No promotion with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid promotion ID",
                        )
                promotionService.deactivatePromotion(id)
                call.respond(HttpStatusCode.OK)
            }

            /**
             * Get a promotion's usage figures.
             *
             * Description: Requires the capability RoutePermissions names for this route. How often the promotion was applied and what it cost in discount.
             *
             * Tag: promotions
             * Path: id Promotion identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No promotion with that id.
             */
            get("/{id}/usage") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid promotion ID",
                        )
                call.respond(HttpStatusCode.OK, promotionService.getPromotionUsage(id))
            }
        }
    }
}
