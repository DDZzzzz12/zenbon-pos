package org.example.project.features.employee.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.domain.enums.auth.Assignability
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The group-management API, and the guardrails that make an editable group table safe against a
 * deny-by-default registry.
 *
 * Every rule in the parent spec's §7 is enforced here rather than in the repository, so a rejection
 * rolls the whole edit back and the policy lives in one readable place.
 *
 * **The capabilities a group confers are the rows, not the compiled `CapabilityBundles`.** Stage 5a
 * moved every reader onto the table for exactly this moment: once an operator can create a group,
 * anything answering from the definitions is answering about a group that does not exist.
 */
class PermissionGroupServiceImpl(
    private val repository: PermissionGroupRepository,
    private val resolver: GroupCapabilityResolver,
) {
    private val logger = LoggerFactory.getLogger(PermissionGroupServiceImpl::class.java)

    /**
     * The two capabilities that can lock a shop out of its own configuration.
     *
     * `PERMISSION_ADMIN` is the one that undoes a bad group edit; `SYSTEM_CONFIG` is the one that
     * undoes a bad configuration. An edit driving either to zero active holders is unrecoverable
     * through the UI, so it is refused however it is phrased.
     */
    private val lockoutCritical = setOf(Capability.PERMISSION_ADMIN, Capability.SYSTEM_CONFIG)

    suspend fun list(): List<PermissionGroupDto.PermissionGroupResponseDto> = dbQuery { repository.listGroups().map { it.toDto() } }

    suspend fun get(code: String): PermissionGroupDto.PermissionGroupResponseDto = dbQuery { repository.findGroup(code)?.toDto() ?: throw groupNotFound(code) }

    /** Every capability, flagged with whether a group may be given it. Drives the editor's picker. */
    fun capabilityCatalog(): PermissionGroupDto.CapabilityCatalogDto =
        PermissionGroupDto.CapabilityCatalogDto(
            capabilities =
                Capability.entries
                    .sortedBy { it.name }
                    .map {
                        PermissionGroupDto.CapabilityInfoDto(
                            name = it.name,
                            assignable = it.assignability == Assignability.ASSIGNABLE,
                        )
                    },
        )

    suspend fun create(
        request: PermissionGroupDto.CreateGroupRequestDto,
        actorId: UUID,
    ): PermissionGroupDto.PermissionGroupResponseDto {
        val code = request.code.trim().uppercase()
        requireWellFormedCode(code)
        val capabilities = parseCapabilities(request.capabilities)
        requireAssignable(capabilities)
        requireActorHolds(capabilities, actorId)

        return dbQuery {
            if (!repository.createGroup(code, request.name.trim(), request.description?.trim(), capabilities)) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.Conflict,
                    "A permission group with code '$code' already exists",
                )
            }
            audit(code, LogOperation.CREATE, "created with ${capabilities.size} capabilities", actorId)
            repository.findGroup(code)!!.toDto()
        }.also { invalidate(code) }
    }

    suspend fun update(
        code: String,
        request: PermissionGroupDto.UpdateGroupRequestDto,
        actorId: UUID,
    ): PermissionGroupDto.PermissionGroupResponseDto {
        val existing = dbQuery { repository.findGroup(code) } ?: throw groupNotFound(code)

        val capabilities = request.capabilities?.let { parseCapabilities(it) }
        if (capabilities != null) {
            // A seeded group's capability set is the contract the migrations and CapabilityBundles
            // both encode. Editing it through the API would put the database and the compiled seed
            // permanently out of step, and SeededGroupsMatchBundlesTest would start failing on a
            // deployment nobody could see. The display name stays editable.
            if (existing.isSystem) {
                throw PosException(
                    PosErrorCode.AUTH_FORBIDDEN,
                    HttpStatusCode.Forbidden,
                    "'$code' is a system group; its capabilities are fixed. Create a group instead.",
                )
            }
            requireAssignable(capabilities)
            requireActorHolds(capabilities, actorId)
            requireNoLockout(existing, capabilities, actorId)
        }

        return dbQuery {
            repository.updateGroupMetadata(code, request.name?.trim(), request.description?.trim())
            capabilities?.let { repository.replaceCapabilities(code, it) }
            audit(
                code,
                LogOperation.UPDATE,
                capabilities?.let { "capabilities -> ${it.size}" } ?: "metadata updated",
                actorId,
            )
            repository.findGroup(code)!!.toDto()
        }.also { invalidate(code) }
    }

    suspend fun delete(
        code: String,
        actorId: UUID,
    ) {
        val existing = dbQuery { repository.findGroup(code) } ?: throw groupNotFound(code)

        if (!existing.isDeletable) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "'$code' is a system group and cannot be deleted",
            )
        }
        if (existing.memberCount > 0) {
            // Deleting it would leave every member with no group, and an employee with no group
            // resolves to NO capabilities — they would be silently unable to do anything, with
            // nothing in the UI explaining why. Reassign them first.
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.Conflict,
                "'$code' still has ${existing.memberCount} member(s). Reassign them before deleting it — " +
                    "an employee with no group can reach nothing.",
            )
        }

        dbQuery {
            repository.deleteGroup(code)
            audit(code, LogOperation.DELETE, "deleted", actorId)
        }
        invalidate(code)
    }

    // ---- guardrails ----------------------------------------------------------------------------

    private fun requireWellFormedCode(code: String) {
        if (code.isBlank() || !code.all { it.isLetterOrDigit() || it == '_' }) {
            throw validation("A group code may contain only letters, digits and underscores")
        }
        if (code.length > 50) throw validation("A group code may be at most 50 characters")
    }

    private fun parseCapabilities(names: Set<String>): Set<Capability> {
        // Rejected, not dropped. Silently ignoring an unrecognised name would leave an operator
        // believing they granted something they did not — the worst outcome for a screen whose whole
        // job is to show what a group can do.
        val unknown = names.filter { name -> Capability.entries.none { it.name == name } }
        if (unknown.isNotEmpty()) {
            throw validation("Unknown capabilities: ${unknown.sorted().joinToString()}")
        }
        return names.mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun requireAssignable(capabilities: Set<Capability>) {
        val reserved = capabilities.filter { it.assignability == Assignability.RESERVED }
        if (reserved.isNotEmpty()) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "These capabilities are reserved and cannot be granted by editing a group: " +
                    reserved.sortedBy { it.name }.joinToString { it.name },
            )
        }
    }

    private suspend fun requireActorHolds(
        capabilities: Set<Capability>,
        actorId: UUID,
    ) {
        // Without this, "may manage groups" silently equals "may become admin": an editor would
        // create a group holding everything and assign themselves to it.
        val held = resolver.forEmployee(actorId)
        val beyond = capabilities - held
        if (beyond.isNotEmpty()) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "You cannot grant capabilities you do not hold yourself: " +
                    beyond.sortedBy { it.name }.joinToString { it.name },
            )
        }
    }

    private suspend fun requireNoLockout(
        existing: PermissionGroupRepository.GroupRecord,
        proposed: Set<Capability>,
        actorId: UUID,
    ) {
        val removed = existing.capabilities - proposed
        val critical = removed intersect lockoutCritical
        if (critical.isEmpty()) return

        // Removing it from your OWN effective set is refused outright: you would be unable to put it
        // back, whoever else still holds it.
        val actorHolds = resolver.forEmployee(actorId)
        val fromSelf = critical.filter { it in actorHolds }
        if (fromSelf.isNotEmpty() && existing.memberCount > 0) {
            val actorsGroup = resolver.groupCodeFor(actorId)
            if (actorsGroup == existing.code) {
                throw PosException(
                    PosErrorCode.AUTH_FORBIDDEN,
                    HttpStatusCode.Forbidden,
                    "This edit removes ${fromSelf.joinToString { it.name }} from your own group. " +
                        "You would not be able to undo it.",
                )
            }
        }

        // And refused if it would leave nobody at all — the shop-wide version of the same lockout.
        dbQuery {
            critical.forEach { capability ->
                val holdersElsewhere = repository.activeHolderCount(capability) - existing.memberCount
                if (holdersElsewhere <= 0) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "This edit would leave no active employee holding ${capability.name}. " +
                            "Grant it to another group first.",
                    )
                }
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    /**
     * Drops every cached capability set.
     *
     * Deliberately [GroupCapabilityResolver.invalidateAll] rather than a targeted eviction: a group
     * edit changes the answer for every employee pointing at it AND for any login-less station
     * resolving it by code, and the resolver is keyed by employee and by code, not by membership.
     * A clever partial eviction here is how a revoked capability keeps working — the mistake
     * `TerminalCapabilityResolver` already made once.
     */
    private fun invalidate(code: String) {
        resolver.invalidateAll()
        logger.info("Permission group {} changed — capability caches cleared", code)
    }

    private fun audit(
        code: String,
        operation: LogOperation,
        detail: String,
        actorId: UUID,
    ) {
        EntryLogHelper.logOperation(
            type = ChangeEntityType.EMPLOYEE,
            operation = operation,
            entityId = code,
            entityName = code,
            message = "Permission group $code: $detail, by $actorId",
            userId = actorId.toString(),
        )
    }

    private fun PermissionGroupRepository.GroupRecord.toDto() =
        PermissionGroupDto.PermissionGroupResponseDto(
            code = code,
            name = name,
            description = description,
            isSystem = isSystem,
            isDeletable = isDeletable,
            capabilities = capabilities.map { it.name }.toSortedSet(),
            memberCount = memberCount,
        )

    private fun groupNotFound(code: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.NotFound, "No permission group '$code'")

    private fun validation(detail: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
}
