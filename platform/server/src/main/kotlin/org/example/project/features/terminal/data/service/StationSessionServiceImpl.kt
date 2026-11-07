package org.example.project.features.terminal.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.JwtConfig
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.terminal.domain.service.StationSessionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * See [StationSessionService].
 *
 * The capability set is resolved **by employee id**, then narrowed by the station mask — the same
 * branch `RouteAuthorizationPlugin` will take when this token comes back, because the JWT minted
 * here carries that employee id in `sub`. The client seeds its UI gates from what this returns, so
 * the two must agree by construction; resolving by group code instead would agree only for as long
 * as the terminal-employee happens to sit in the group named after their role, and would silently
 * desynchronise the moment an admin reassigns them. Fails closed either way, as the plugin does.
 */
class StationSessionServiceImpl(
    private val groupResolver: GroupCapabilityResolver,
    private val browserStationPolicy: BrowserStationPolicy,
) : StationSessionService {
    private val logger = LoggerFactory.getLogger(StationSessionServiceImpl::class.java)

    override suspend fun openSession(cap: TerminalCapability): TerminalDto.StationSessionResponseDto {
        browserStationPolicy.requireAllowed(cap.clientKind, TerminalRole.fromCode(cap.role))
        val role = requireStationRole(cap)
        val deviceId = requirePhysicalDeviceId(cap)
        val employee = terminalEmployee()
        val capabilities = maskedCapabilitiesFor(employee, role)
        val token = mintAccessToken(employee, deviceId, role)

        logger.info("Station session opened for terminal ${cap.clientId} (device $deviceId)")
        return TerminalDto.StationSessionResponseDto(
            accessToken = token.accessToken,
            expiresAt = token.expiresAt,
            terminalId = cap.clientId,
            role = role.name,
            capabilities = capabilities,
        )
    }

    /** The station shell exists for ORDER_STATION only — a till logs a person in, so CASHIER is refused too. */
    private fun requireStationRole(cap: TerminalCapability): TerminalRole {
        val role = TerminalRole.fromCode(cap.role)
        if (role != TerminalRole.ORDER_STATION) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "no shell for role ${cap.role}",
            )
        }
        return role
    }

    /**
     * The PHYSICAL device id — `OrderServiceImpl.createOrder` resolves the terminal via
     * `findByDeviceId`, which matches the device_id column, not the TER- client id.
     */
    private fun requirePhysicalDeviceId(cap: TerminalCapability): String =
        cap.deviceId ?: throw PosException(
            PosErrorCode.INTERNAL_ERROR,
            HttpStatusCode.InternalServerError,
            "Bound terminal ${cap.clientId} has no physical device id",
        )

    /** The shared login-less employee, read out of the transaction as plain values. */
    private suspend fun terminalEmployee(): StationEmployee =
        dbQuery {
            val employee = TerminalEmployee.requireRow()
            StationEmployee(id = employee.id.value, number = employee.employeeNumber)
        }

    /** What the employee holds, narrowed to what a station of [role] may ever exercise. */
    private suspend fun maskedCapabilitiesFor(
        employee: StationEmployee,
        role: TerminalRole,
    ): Set<String> {
        val held = groupResolver.forEmployee(employee.id)
        return TerminalMasks.apply(held, StationClass.fromTerminalRole(role.name)).map { it.name }.toSet()
    }

    private fun mintAccessToken(
        employee: StationEmployee,
        deviceId: String,
        role: TerminalRole,
    ): StationToken {
        val accessToken =
            JwtConfig.generateStationAccessToken(
                employeeId = employee.id.toString(),
                employeeNumber = employee.number,
                deviceId = deviceId,
                station = role.name,
            )
        val expiresAt =
            JwtConfig.validateToken(accessToken)?.expiresAt
                ?: throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Station token generation failed",
                )
        return StationToken(accessToken = accessToken, expiresAt = expiresAt.toString())
    }

    /** The two employee fields the mint and the capability resolution need, detached from the entity. */
    private data class StationEmployee(
        val id: UUID,
        val number: String,
    )

    private data class StationToken(
        val accessToken: String,
        val expiresAt: String,
    )
}
