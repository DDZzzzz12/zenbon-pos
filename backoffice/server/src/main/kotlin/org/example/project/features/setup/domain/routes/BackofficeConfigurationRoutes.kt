package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("BackofficeConfigurationRoutes")

/**
 * Shop configuration routes owned by the backoffice area: working-time break rules. Same paths and
 * handlers as before the split of `ConfigurationRoutes`.
 */
fun Route.backofficeConfigurationRoutes() {
    val configRepository by inject<ConfigurationRepository>()
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/v1/config") {
            /**
             * Get the break rules.
             *
             * Description: The working-time break thresholds the time-tracking feature applies. Falls back to the built-in defaults when never configured.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/break-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid shop ID"))
                    return@get
                }

                try {
                    val configJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.BREAK_RULES_CONFIG.key)
                        } ?: Json.parseToJsonElement(ConfigKey.BREAK_RULES_CONFIG.defaultValue)

                    val config = Json.decodeFromString<ShopConfigurationDto.BreakRulesConfig>(configJson.toString())

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.BreakRulesConfigResponseDto(
                            shopId = shopId,
                            rules = config.rules,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get break rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve break rules", "message" to (e.message ?: "Unknown error")),
                    )
                }
            }

            /**
             * Update the break rules.
             *
             * Description: Requires the system-configuration permission or MANAGER role.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/break-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid shop ID"))
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.BreakRulesConfig>()

                    // Validate rules
                    request.rules.forEach { rule ->
                        require(rule.thresholdMinutes > 0) { "Threshold must be positive" }
                        require(rule.breakMinutes > 0) { "Break minutes must be positive" }
                    }

                    val configJson = Json.encodeToString(request)

                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.BREAK_RULES_CONFIG,
                            value = Json.parseToJsonElement(configJson),
                            description = "Break rules configuration",
                        )
                    }

                    configManager.refreshConfiguration()

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.BreakRulesConfigResponseDto(
                            shopId = shopId,
                            rules = request.rules,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Validation failed")))
                } catch (e: Exception) {
                    logger.error("Failed to update break rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update break rules", "message" to (e.message ?: "Unknown error")),
                    )
                }
            }
        }
    }
}
