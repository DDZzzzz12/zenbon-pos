package org.example.project.features.admin.data.service

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.domain.dto.employee.PermissionDiagnosticsDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.security.AccessDecision
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.RouteRuleCatalog
import org.example.project.infrastructure.security.description
import org.example.project.utils.dbQuery
import java.util.UUID

/**
 * "What can this group do?" and "why can't this person do X?", answered from the same code the gate
 * enforces with.
 *
 * Both answers walk the [RouteRuleCatalog] and evaluate through [AccessDecision] — the comparison the
 * `RouteAuthorizationPlugin` makes, extracted in Stage 5c precisely so this cannot become a second
 * opinion. A diagnostic is consulted when somebody is already confused; one that disagrees with the
 * gate would confirm the wrong answer with authority.
 *
 * Capabilities come from [GroupCapabilityResolver], not `CapabilityBundles`, for the same reason:
 * since Stage 2a the rows are the authority, and since Stage 5b an operator can edit them. A group
 * created this morning does not appear in any compiled set.
 */
class PermissionDiagnosticsServiceImpl(
    private val resolver: GroupCapabilityResolver,
    private val groups: PermissionGroupRepository,
    private val routeRules: RouteRuleCatalog,
) {
    /**
     * What [groupCode] reaches, as seen from [stationName].
     *
     * An unknown or absent station is `UNBOUND` — the full mask — matching
     * [StationClass.fromTerminalRole]. That default is deliberate there and inherited here: it must
     * never quietly claim a station identity nothing proved.
     */
    suspend fun effectiveAccess(
        groupCode: String,
        stationName: String?,
    ): PermissionDiagnosticsDto.EffectiveAccessDto {
        val code = groupCode.trim().uppercase()
        dbQuery { groups.findGroup(code) }
            ?: throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.NotFound,
                "No permission group '$code'",
            )

        val identity =
            resolver.forGroupCode(code)
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.NotFound,
                    "Group '$code' resolves to no capabilities",
                )
        val station = StationClass.fromTerminalRole(stationName)
        val effective = TerminalMasks.apply(identity, station)

        val (reachable, unreachable) = partitionRoutes(effective)

        return PermissionDiagnosticsDto.EffectiveAccessDto(
            groupCode = code,
            station = station.name,
            identityCapabilities = identity.names(),
            effectiveCapabilities = effective.names(),
            maskedOut = (identity - effective).names(),
            reachable = reachable,
            unreachable = unreachable,
        )
    }

    /**
     * Whether [employeeId] is admitted to [path] with [method], and why.
     *
     * Evaluates the IDENTITY axis only. The station mask is not applied because this diagnostic has
     * no request and therefore no device: a person's own requests arrive `UNBOUND` anyway, and
     * assuming a station here would report a narrowing that would not happen. [effectiveAccess]
     * is where the station question is asked, and the caveat says so.
     */
    suspend fun explain(
        employeeId: UUID,
        path: String,
        method: String,
    ): PermissionDiagnosticsDto.AccessExplanationDto {
        val httpMethod = HttpMethod.parse(method.trim().uppercase())
        val held = resolver.forEmployee(employeeId)
        val groupCode = resolver.groupCodeFor(employeeId)

        val rule = routeRules.findRule(path, httpMethod)
        val base =
            PermissionDiagnosticsDto.AccessExplanationDto(
                employeeId = employeeId.toString(),
                groupCode = groupCode,
                path = path,
                method = httpMethod.value,
                ruleFound = rule != null,
                requires = rule?.description,
                allowed = false,
                reason = "",
            )

        if (rule == null) {
            // The single most confusing failure this system produces: the route exists, it returns
            // 403, and nothing in the group's capability list explains why. Naming it explicitly is
            // most of this endpoint's value.
            return base.copy(
                reason =
                    "No permission rule is registered for ${httpMethod.value} $path, so it is denied " +
                        "by default. Either the path is wrong, or the route is missing from RoutePermissions.",
            )
        }

        val denial = AccessDecision.check(rule, held)
        val ownership = rule is AccessLevel.CapWithOwnership

        return base.copy(
            allowed = denial == null,
            reason =
                denial?.reason
                    ?: if (groupCode == null) {
                        "Allowed. Note the employee belongs to no permission group."
                    } else {
                        "Allowed by group $groupCode."
                    },
            missing = denial?.missing.orEmpty().names(),
            ownershipApplies = ownership,
            caveat =
                when {
                    ownership && denial == null ->
                        "This route also checks ownership in the handler, so an allowed verdict here " +
                            "is a floor, not a promise of 200 for every row."
                    else -> null
                },
        )
    }

    /** Splits every registered rule by whether [held] gets through it. */
    private fun partitionRoutes(held: Set<Capability>): Pair<List<PermissionDiagnosticsDto.RouteAccessDto>, List<PermissionDiagnosticsDto.RouteAccessDto>> {
        val reachable = mutableListOf<PermissionDiagnosticsDto.RouteAccessDto>()
        val unreachable = mutableListOf<PermissionDiagnosticsDto.RouteAccessDto>()

        routeRules.registeredRules().forEach { (path, method, access) ->
            val denial = AccessDecision.check(access, held)
            val entry =
                PermissionDiagnosticsDto.RouteAccessDto(
                    path = path,
                    method = method.value,
                    requires = access.description,
                    missing = denial?.missing.orEmpty().names(),
                )
            if (denial == null) reachable.add(entry) else unreachable.add(entry)
        }

        return reachable.sortedBy { it.path + it.method } to unreachable.sortedBy { it.path + it.method }
    }

    private fun Set<Capability>.names() = map { it.name }.toSortedSet().toSet()
}
