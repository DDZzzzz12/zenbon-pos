package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.utils.config.VatMappingUtil
import org.example.project.utils.config.VoucherConfigUtil
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EcrConfigurationRoutes")

/**
 * Shop configuration routes owned by the ecr area: VAT mapping, voucher configuration, print settings,
 * order rules and billing rules. Same paths and handlers as before the split of `ConfigurationRoutes`.
 */
fun Route.ecrConfigurationRoutes() {
    val configRepository by inject<ConfigurationRepository>()
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/v1/config") {
            /**
             * Update the VAT mapping.
             *
             * Description: Requires the system-configuration permission or MANAGER role. The whole mapping is validated before it is stored — every product type must be present and every VAT class reference must be A, B or C. Because VAT classes drive the receipts and the DSFinV-K export, a partial mapping is rejected outright rather than merged.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the mapping is incomplete or names an unknown VAT class.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/vat-mapping") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdateVatMappingRequestDto>()

                    logger.info("Updating VAT mapping for shopId: $shopId")

                    // Validate the VAT mapping before saving
                    request.validate()
                    if (!request.vatMapping.isValid()) {
                        logger.warn("Invalid VAT mapping provided for shopId: $shopId")
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid VAT mapping configuration",
                                "details" to "The provided configuration contains invalid VAT class references. Valid classes are: A, B, C",
                            ),
                        )
                        return@put
                    }

                    // Convert to JSON for storage
                    val vatMappingJson = VatMappingUtil.toJson(request.vatMapping)

                    // Save the validated configuration
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.VAT_MAPPING,
                            value = vatMappingJson,
                            description = "VAT mapping configuration: Maps ProductType to VAT class based on conditions",
                        )
                    }

                    // Force refresh of configuration cache
                    configManager.refreshConfiguration()

                    logger.info("Successfully updated VAT mapping for shopId: $shopId")

                    // Get description for response
                    val description = VatMappingUtil.describeVatMapping(vatMappingJson)

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.VatMappingResponseDto(
                            shopId = shopId,
                            vatMapping = request.vatMapping,
                            isValid = true,
                            description = description,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update VAT mapping for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update VAT mapping",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Get the VAT mapping.
             *
             * Description: The VAT mapping decides which VAT class (A, B or C) each product type is booked under, split by dine-in, to-go and reduced. Any authenticated employee may read it. The response also carries a validation status and a human-readable description of the mapping.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/vat-mapping") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    logger.info("Getting VAT mapping for shopId: $shopId")

                    // Get VAT mapping from repository as JSON
                    val vatMappingJson =
                        dbQuery {
                            configRepository.getStringSetting(shopId, ConfigKey.VAT_MAPPING)
                        }

                    // Parse to type-safe data class
                    val vatMapping = VatMappingUtil.parseVatMapping(vatMappingJson)
                    val isValid = vatMapping.isValid()
                    val description = VatMappingUtil.describeVatMapping(vatMappingJson)

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.VatMappingResponseDto(
                            shopId = shopId,
                            vatMapping = vatMapping,
                            isValid = isValid,
                            description = description,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get VAT mapping for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve VAT mapping",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Update the voucher configuration.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Validated before saving — every source must be fully configured, and an internal source must exist while vouchers are enabled. Answers 204 with no body.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 204 Saved. No body.
             * Response: 400 shopId is not a number, or a voucher source is incomplete or missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/voucher-config") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdateVoucherConfigRequestDto>()

                    logger.info("Updating voucher configuration for shopId: $shopId")

                    // Validate the voucher configuration before saving. Checked here rather than via
                    // `request.validate()`, whose `require` threw into the catch-all below and turned a
                    // refused config (e.g. INTERNAL removed while vouchers are on) into a 500.
                    if (!request.voucherConfig.isValid()) {
                        logger.warn("Invalid voucher configuration provided for shopId: $shopId")
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid voucher configuration",
                                "details" to "The provided configuration contains invalid source configurations or settings",
                            ),
                        )
                        return@put
                    }

                    // Convert to JSON for storage
                    val voucherConfigJson = VoucherConfigUtil.toJson(request.voucherConfig)

                    // Save the validated configuration
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.VOUCHER_CONFIG,
                            value = voucherConfigJson,
                            description = "Voucher system configuration: Defines supported voucher sources and validation rules",
                        )
                    }

                    // Force refresh of configuration cache
                    configManager.refreshConfiguration()

                    logger.info("Successfully updated voucher configuration for shopId: $shopId")

                    call.respond(HttpStatusCode.NoContent)
                } catch (e: Exception) {
                    logger.error("Failed to update voucher configuration for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update voucher configuration",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Get the voucher configuration.
             *
             * Description: Reports whether vouchers are enabled and which sources (internal, legacy, external partner) are accepted, with a validation status.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/voucher-config") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    logger.info("Getting voucher configuration for shopId: $shopId")

                    // Get voucher configuration from repository as JSON
                    val voucherConfigJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.VOUCHER_CONFIG.key)
                        } ?: Json.parseToJsonElement(ConfigKey.VOUCHER_CONFIG.defaultValue)

                    // Parse to type-safe data class
                    val voucherConfig = VoucherConfigUtil.toVoucherConfig(voucherConfigJson)
                    val isValid = voucherConfig.isValid()
                    val enabledSources =
                        voucherConfig.sources
                            .filter { it.enabled }
                            .map { "${it.name} (${it.type.name})" }

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.VoucherConfigResponseDto(
                            shopId = shopId,
                            voucherConfig = voucherConfig,
                            isValid = isValid,
                            enabledSources = enabledSources,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get voucher configuration for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve voucher configuration",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Get the print settings.
             *
             * Description: Reports whether receipt printing is enabled for the shop. Every printing route refuses with 503 while this is off.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/print-settings") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    logger.info("Getting print settings for shopId: $shopId")

                    // Get print settings from repository as JSON
                    val printSettingsJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.PRINT_SETTINGS.key)
                        } ?: Json.parseToJsonElement(ConfigKey.PRINT_SETTINGS.defaultValue)

                    // Extract enabled status
                    val enabled = printSettingsJson.jsonObject["enabled"]?.toString()?.toBoolean() ?: false

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.PrintSettingsResponseDto(
                            shopId = shopId,
                            enabled = enabled,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get print settings for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve print settings",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Update the print settings.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Turning printing off makes the bill and reference-print routes answer 503 immediately.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/print-settings") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdatePrintSettingsRequestDto>()

                    logger.info("Updating print settings for shopId: $shopId - enabled: ${request.enabled}")

                    // Create JSON for storage
                    val printSettingsJson = """{"enabled":${request.enabled}}"""

                    // Save the configuration
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.PRINT_SETTINGS,
                            value = Json.parseToJsonElement(printSettingsJson),
                            description = "Printer configuration enabled",
                        )
                    }

                    // Force refresh of configuration cache
                    configManager.refreshConfiguration()

                    logger.info("Successfully updated print settings for shopId: $shopId")

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.PrintSettingsResponseDto(
                            shopId = shopId,
                            enabled = request.enabled,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update print settings for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update print settings",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Get the order rules.
             *
             * Description: The rules governing what a cashier may do while ordering — custom prices, discounts and similar guards the POS enforces in its UI and the server re-checks.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/order-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    val orderRulesJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.ORDER_RULES.key)
                        } ?: Json.parseToJsonElement(ConfigKey.ORDER_RULES.defaultValue)

                    val employeeSelectionOnFreeTable =
                        orderRulesJson.jsonObject["employeeSelectionOnFreeTable"]
                            ?.toString()
                            ?.toBoolean() ?: false
                    val roundRobinAssignment =
                        orderRulesJson.jsonObject["roundRobinAssignment"]
                            ?.toString()
                            ?.toBoolean() ?: false

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.OrderRulesResponseDto(
                            shopId = shopId,
                            employeeSelectionOnFreeTable = employeeSelectionOnFreeTable,
                            roundRobinAssignment = roundRobinAssignment,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get order rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve order rules",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Update the order rules.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Loosening a rule takes effect on the next order; orders already booked are untouched.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/order-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdateOrderRulesRequestDto>()

                    logger.info(
                        "Updating order rules for shopId: $shopId - employeeSelectionOnFreeTable: {}, roundRobinAssignment: {}",
                        request.employeeSelectionOnFreeTable,
                        request.roundRobinAssignment,
                    )

                    val orderRulesJson =
                        """{"employeeSelectionOnFreeTable":${request.employeeSelectionOnFreeTable},"roundRobinAssignment":${request.roundRobinAssignment}}"""

                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.ORDER_RULES,
                            value = Json.parseToJsonElement(orderRulesJson),
                            description = "Order processing rules configuration",
                        )
                    }

                    configManager.refreshConfiguration()
                    // The round-robin toggle decides whether there is a "next" at all. Published only now: the
                    // save has committed and the config cache is refreshed, so peekNext sees the new rule.
                    RotationNextNotifier.publishCurrent()

                    logger.info("Successfully updated order rules for shopId: $shopId")

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.OrderRulesResponseDto(
                            shopId = shopId,
                            employeeSelectionOnFreeTable = request.employeeSelectionOnFreeTable,
                            roundRobinAssignment = request.roundRobinAssignment,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update order rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update order rules",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Get the billing rules.
             *
             * Description: The rules governing settlement — tipping, splitting and the guards applied when a bill is created.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/billing-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    val billingRulesJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.BILLING_RULES.key)
                        } ?: Json.parseToJsonElement(ConfigKey.BILLING_RULES.defaultValue)

                    val allowCrossEmployeeBilling =
                        billingRulesJson.jsonObject["allowCrossEmployeeBilling"]
                            ?.toString()
                            ?.toBoolean() ?: false

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.BillingRulesResponseDto(
                            shopId = shopId,
                            allowCrossEmployeeBilling = allowCrossEmployeeBilling,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get billing rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve billing rules",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Update the billing rules.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Changes apply to bills created afterwards; settled bills are fiscal documents and are never re-evaluated.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/billing-rules") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdateBillingRulesRequestDto>()

                    logger.info(
                        "Updating billing rules for shopId: $shopId - allowCrossEmployeeBilling: ${request.allowCrossEmployeeBilling}",
                    )

                    val billingRulesJson = """{"allowCrossEmployeeBilling":${request.allowCrossEmployeeBilling}}"""

                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.BILLING_RULES,
                            value = Json.parseToJsonElement(billingRulesJson),
                            description = "Billing rules configuration",
                        )
                    }

                    configManager.refreshConfiguration()

                    logger.info("Successfully updated billing rules for shopId: $shopId")

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.BillingRulesResponseDto(
                            shopId = shopId,
                            allowCrossEmployeeBilling = request.allowCrossEmployeeBilling,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update billing rules for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update billing rules",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }
        }
    }
}
