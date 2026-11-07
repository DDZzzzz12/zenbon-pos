package org.example.project.core.data.remote

import org.example.project.core.domain.dto.employee.PermissionGroupDto

/**
 * The permission-group management API — `/api/admin/permission-groups`.
 *
 * Every call requires `PERMISSION_ADMIN`, which is reserved and therefore held only by the admin
 * group. The client half gates the UI; the server enforces the same capability and rejects anything
 * that gets past it.
 */
interface PermissionGroupApiClient {
    suspend fun getGroups(): List<PermissionGroupDto.PermissionGroupResponseDto>

    suspend fun getGroup(code: String): PermissionGroupDto.PermissionGroupResponseDto

    /**
     * Every capability the server knows, flagged with whether a group may be given it.
     *
     * The editor's picker is built from this rather than from the client's own `Capability` enum: a
     * client meeting a newer server must show the capabilities that server actually enforces, and a
     * locally compiled list would quietly omit them.
     */
    suspend fun getCapabilityCatalog(): PermissionGroupDto.CapabilityCatalogDto

    suspend fun createGroup(request: PermissionGroupDto.CreateGroupRequestDto): PermissionGroupDto.PermissionGroupResponseDto

    suspend fun updateGroup(
        code: String,
        request: PermissionGroupDto.UpdateGroupRequestDto,
    ): PermissionGroupDto.PermissionGroupResponseDto

    suspend fun deleteGroup(code: String)
}
