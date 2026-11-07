package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.ApiDocsPolicy
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.AuditEntityRef
import org.koin.ktor.ext.inject

/**
 * Platform shop configuration routes: browser stations and web access. The other `/api/v1/config`
 * endpoints are mounted by the owning areas (ecr, fiscal, accounting, backoffice).
 *
 * Endpoints:
 * - GET/PUT /api/v1/config/{shopId}/browser-stations
 * - GET/PUT /api/v1/config/{shopId}/web-access
 */
fun Route.configurationRoutes() {
    val configRepository by inject<ConfigurationRepository>()
    val configManager by inject<IShopConfigurationManager>()
    val terminalService by inject<TerminalService>()

    authenticate("auth-jwt") {
        route("/api/v1/config") {
            /**
             * Get the browser-station setting.
             *
             * Description: The terminal roles that may run as a browser station in this shop. Empty by default — browser stations are off until a manager enables them.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/{shopId}/browser-stations") {
                val shopId = call.shopIdOrThrow()
                val stored =
                    dbQuery { configRepository.getSetting(shopId, ConfigKey.BROWSER_STATIONS.key) }
                        ?: Json.parseToJsonElement(ConfigKey.BROWSER_STATIONS.defaultValue)
                val roles =
                    (stored as? JsonObject)
                        ?.get("roles")
                        ?.let { it as? JsonArray }
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList()
                call.respond(HttpStatusCode.OK, ShopConfigurationDto.BrowserStationsResponseDto(shopId = shopId, roles = roles))
            }

            /**
             * Update the browser-station setting.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Replaces the set of terminal roles that may run as a browser station; only CASHIER and ORDER_STATION can be enabled. The change is audited as UPDATE_BROWSER_STATIONS.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or a role is not CASHIER or ORDER_STATION (VALIDATION_FAILED).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             */
            put("/{shopId}/browser-stations") {
                val shopId = call.shopIdOrThrow()
                val employeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<ShopConfigurationDto.UpdateBrowserStationsRequestDto>()
                val roles = browserStationRolesOrThrow(request.roles)

                val rolesJson =
                    buildJsonObject {
                        put("roles", buildJsonArray { roles.forEach { add(JsonPrimitive(it.name)) } })
                    }
                val previousRoles = configManager.getBrowserStationRoles()
                ExceptionConverter.catchingThrows(
                    context = "updateBrowserStations(shopId=$shopId)",
                    employeeId = employeeId,
                    operation = "UPDATE_BROWSER_STATIONS",
                    entityType = "SHOP_CONFIGURATION",
                    auditEntity = { AuditEntityRef(id = shopId.toString(), name = "browser_stations") },
                    auditState = { rolesJson.toString() },
                ) {
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.BROWSER_STATIONS,
                            value = rolesJson,
                            description = ConfigKey.BROWSER_STATIONS.description,
                        )
                    }
                    // A failed refresh leaves the enforcement cache on the OLD roles while the
                    // stored setting is already new: do not report success for that.
                    if (!configManager.refreshConfiguration()) {
                        throw PosException(
                            PosErrorCode.SERVICE_UNAVAILABLE,
                            HttpStatusCode.ServiceUnavailable,
                            "Browser-station setting saved, but the configuration cache could not be refreshed; retry",
                        )
                    }
                }
                // After the commit: a stale push must not race the old setting.
                terminalService.onBrowserStationRolesChanged(previousRoles, roles)
                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.BrowserStationsResponseDto(shopId = shopId, roles = roles.map { it.name }),
                )
            }

            /**
             * Get the web-access settings.
             *
             * Description: The API-documentation switch (stored value, effective state, and whether the server configuration locks it) and the server-wide default page for `/`, with the targets that may be chosen right now.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the configuration-read permission.
             */
            get("/{shopId}/web-access") {
                val shopId = call.shopIdOrThrow()
                call.respond(HttpStatusCode.OK, webAccessDto(shopId, configManager))
            }

            /**
             * Update the web-access settings.
             *
             * Description: Requires the configuration-write permission. Saves the API-documentation switch and the server-wide default page for `/` (`none`, `admin`, or `pos` — `pos` only while a browser-station role is enabled). While `POS_API_DOCS` locks the switch, the stored value is still saved but has no effect. Audited as UPDATE_WEB_ACCESS.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, the start page is unknown, or it is `pos` while browser stations are off (VALIDATION_FAILED).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the configuration-write permission.
             * Response: 503 Saved, but the configuration cache could not be refreshed; retry.
             */
            put("/{shopId}/web-access") {
                val shopId = call.shopIdOrThrow()
                val employeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<ShopConfigurationDto.UpdateWebAccessRequestDto>()
                val target = startPageTargetOrThrow(request.startPageDefault, configManager.getBrowserStationRoles())

                val stateJson =
                    buildJsonObject {
                        put("apiDocsEnabled", JsonPrimitive(request.apiDocsEnabled))
                        put("startPageDefault", JsonPrimitive(target.wire))
                    }
                ExceptionConverter.catchingThrows(
                    context = "updateWebAccess(shopId=$shopId)",
                    employeeId = employeeId,
                    operation = "UPDATE_WEB_ACCESS",
                    entityType = "SHOP_CONFIGURATION",
                    auditEntity = { AuditEntityRef(id = shopId.toString(), name = "web_access") },
                    auditState = { stateJson.toString() },
                ) {
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.API_DOCS_ENABLED,
                            value = JsonPrimitive(request.apiDocsEnabled),
                            description = ConfigKey.API_DOCS_ENABLED.description,
                        )
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.START_PAGE_DEFAULT,
                            value = JsonPrimitive(target.wire),
                            description = ConfigKey.START_PAGE_DEFAULT.description,
                        )
                    }
                    // Same rule as browser stations: a stale cache would keep serving the old state.
                    if (!configManager.refreshConfiguration()) {
                        throw PosException(
                            PosErrorCode.SERVICE_UNAVAILABLE,
                            HttpStatusCode.ServiceUnavailable,
                            "Web-access settings saved, but the configuration cache could not be refreshed; retry",
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, webAccessDto(shopId, configManager))
            }
        }
    }
}

/**
 * The `{shopId}` path parameter, or a 400.
 *
 * Throws rather than responding so the caller reads as a straight line; StatusPages turns it into
 * the same RFC 7807 body every other validation failure produces.
 */
fun ApplicationCall.shopIdOrThrow(): Long =
    parameters["shopId"]?.toLongOrNull()
        ?: throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Invalid shop ID",
        )

/**
 * Parses the role names of a browser-station update. Only CASHIER and ORDER_STATION may run in a
 * browser; an unknown or non-eligible name refuses the whole request, naming each offender.
 */
private fun browserStationRolesOrThrow(names: List<String>): Set<TerminalRole> {
    val eligible = setOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION)
    val parsed = names.map { it to TerminalRole.parseOrNull(it) }
    val refused = parsed.filter { (_, role) -> role == null || role !in eligible }
    if (refused.isNotEmpty()) {
        throw PosException(
            code = PosErrorCode.VALIDATION_FAILED,
            httpStatus = HttpStatusCode.BadRequest,
            message = "Only CASHIER and ORDER_STATION can run as browser stations",
            fields = refused.map { (name, _) -> FieldError(field = "roles", code = "NOT_ELIGIBLE: $name") },
        )
    }
    return parsed.mapNotNull { it.second }.toSet()
}

/**
 * Parses the default start page of a web-access update. An unknown value refuses the request, and so
 * does `pos` while no browser-station role is enabled — `/` would forward to a till nobody may open.
 */
private fun startPageTargetOrThrow(
    wire: String,
    browserStationRoles: Set<TerminalRole>,
): StartPageTarget {
    val target =
        StartPageTarget.fromWire(wire)
            ?: throw PosException(
                code = PosErrorCode.VALIDATION_FAILED,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Unknown start page",
                fields = listOf(FieldError(field = "startPageDefault", code = "UNKNOWN_TARGET")),
            )
    if (target == StartPageTarget.POS && browserStationRoles.isEmpty()) {
        throw PosException(
            code = PosErrorCode.VALIDATION_FAILED,
            httpStatus = HttpStatusCode.BadRequest,
            message = "The till cannot be the start page while browser stations are off",
            fields = listOf(FieldError(field = "startPageDefault", code = "BROWSER_STATIONS_OFF")),
        )
    }
    return target
}

/** The web-access settings as served right now; `pos` is offered only while a browser-station role is enabled. */
private suspend fun webAccessDto(
    shopId: Long,
    configManager: IShopConfigurationManager,
): ShopConfigurationDto.WebAccessResponseDto {
    val docs = ApiDocsPolicy.current(configManager)
    val available =
        buildList {
            add(StartPageTarget.NONE.wire)
            add(StartPageTarget.ADMIN.wire)
            if (configManager.getBrowserStationRoles().isNotEmpty()) add(StartPageTarget.POS.wire)
        }
    return ShopConfigurationDto.WebAccessResponseDto(
        shopId = shopId,
        apiDocsEnabled = docs.enabled,
        apiDocsSetting = configManager.isApiDocsSettingEnabled(),
        apiDocsLockedByServerConfig = docs.lockedByServerConfig,
        startPageDefault = configManager.getStartPageDefault().wire,
        availableStartPageTargets = available,
    )
}
