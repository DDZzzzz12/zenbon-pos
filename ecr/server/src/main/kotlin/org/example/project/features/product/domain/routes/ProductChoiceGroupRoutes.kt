package org.example.project.features.product.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.ProductChoiceGroupService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

fun Route.productChoiceGroupRoutes() {
    val service by inject<ProductChoiceGroupService>()
    authenticate("auth-jwt") {
        route("/api/product-choice-groups") {
            /**
             * List every product-to-choice-group attachment.
             *
             * Description: Returns the full attachment table with its per-placement price deltas, which the POS caches to build the option configurator. Any authenticated employee may read it.
             *
             * Tag: product-choice-groups
             * Response: 401 Missing or invalid bearer token.
             */
            get { call.respond(HttpStatusCode.OK, service.getAll()) }

            /**
             * Get attachments changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the attachments updated after the given timestamp.
             *
             * Tag: product-choice-groups
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val t =
                    call.parameters["lastSyncTime"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                call.respond(HttpStatusCode.OK, service.getUpdatedAfter(t))
            }

            /**
             * List the choice groups attached to one product.
             *
             * Description: What the POS calls when a cashier taps a configurable product. Each group comes back with the price delta configured for this placement, which may differ from the group's default.
             *
             * Tag: product-choice-groups
             * Path: productId Product identifier.
             * Response: 400 productId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/product/{productId}") {
                val pid =
                    call.parameters["productId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                call.respond(HttpStatusCode.OK, service.getForProduct(pid))
            }

            /**
             * Attach a choice group to a product.
             *
             * Description: Requires MANAGER role. The body may override the group's option price deltas for this product only, leaving the group's own defaults and other products untouched.
             *
             * Tag: product-choice-groups
             * Response: 400 The body failed validation, or the product or group id is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val uid = call.getEmployeeIdFromJwt()
                val req = call.receive<ProductChoiceGroupDto.AttachRequestDto>()
                call.respond(HttpStatusCode.OK, service.attach(uid, req))
            }

            /**
             * Attach choice groups to every product in a category.
             *
             * Description: Requires MANAGER role. Fans the listed groups out over all products currently in the category — products added later are not covered retroactively. The body is validated before any attachment is written.
             *
             * Tag: product-choice-groups
             * Response: 400 The body failed validation, or the category or a group id is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post("/bulk-category") {
                val uid = call.getEmployeeIdFromJwt()
                val req = call.receive<ProductChoiceGroupDto.BulkCategoryAttachRequestDto>()
                req.validate()
                call.respond(HttpStatusCode.OK, service.bulkAttachToCategory(uid, req.categoryId, req.groupIds))
            }

            /**
             * Detach a choice group from every product in a category.
             *
             * Description: Requires MANAGER role. The inverse of the bulk attach — it removes the group from all products currently in the category. The group itself is left intact.
             *
             * Tag: product-choice-groups
             * Path: categoryId Product category identifier.
             * Path: groupId Choice group identifier.
             * Response: 204 Detached. No body.
             * Response: 400 categoryId or groupId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            delete("/bulk-category/{categoryId}/{groupId}") {
                val uid = call.getEmployeeIdFromJwt()
                val categoryId =
                    call.parameters["categoryId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid group ID")
                service.bulkRemoveFromCategory(uid, categoryId, groupId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
