package org.example.project.admin.features.permissions

import kotlinx.coroutines.CompletableDeferred
import org.example.project.core.data.remote.PermissionGroupApiClient
import org.example.project.core.domain.dto.employee.PermissionGroupDto

/** Hand-written fake; records mutating calls so tests can assert on what was actually sent. */
class FakePermissionGroupApiClient(
    var groups: List<PermissionGroupDto.PermissionGroupResponseDto> = emptyList(),
    var catalog: List<PermissionGroupDto.CapabilityInfoDto> = emptyList(),
    /** Fails [getGroups]; a `var` so a test can arm it *after* the initial load. */
    var onListError: (() -> Nothing)? = null,
    var onUpdateError: (() -> Nothing)? = null,
    var onCreateError: (() -> Nothing)? = null,
    var onDeleteError: (() -> Nothing)? = null,
) : PermissionGroupApiClient {
    var updatedCode: String? = null
    var updated: PermissionGroupDto.UpdateGroupRequestDto? = null
    var created: PermissionGroupDto.CreateGroupRequestDto? = null
    var deletedCode: String? = null

    /** When set, [updateGroup] suspends until it completes, so a test can observe a save in flight. */
    var updateGate: CompletableDeferred<Unit>? = null

    /** How often the group list was requested — what proves a gated caller never asks. */
    var listCalls: Int = 0

    override suspend fun getGroups(): List<PermissionGroupDto.PermissionGroupResponseDto> {
        listCalls++
        onListError?.invoke()
        return groups
    }

    override suspend fun getGroup(code: String) = groups.first { it.code == code }

    override suspend fun getCapabilityCatalog() = PermissionGroupDto.CapabilityCatalogDto(catalog)

    override suspend fun createGroup(request: PermissionGroupDto.CreateGroupRequestDto): PermissionGroupDto.PermissionGroupResponseDto {
        onCreateError?.invoke()
        created = request
        return PermissionGroupDto.PermissionGroupResponseDto(
            code = request.code,
            name = request.name,
            description = request.description,
            isSystem = false,
            isDeletable = true,
            capabilities = request.capabilities,
            memberCount = 0,
        )
    }

    override suspend fun updateGroup(
        code: String,
        request: PermissionGroupDto.UpdateGroupRequestDto,
    ): PermissionGroupDto.PermissionGroupResponseDto {
        updateGate?.await()
        onUpdateError?.invoke()
        updatedCode = code
        updated = request
        val existing = groups.first { it.code == code }
        return existing.copy(
            name = request.name ?: existing.name,
            description = request.description ?: existing.description,
            capabilities = request.capabilities ?: existing.capabilities,
        )
    }

    override suspend fun deleteGroup(code: String) {
        onDeleteError?.invoke()
        deletedCode = code
    }
}
