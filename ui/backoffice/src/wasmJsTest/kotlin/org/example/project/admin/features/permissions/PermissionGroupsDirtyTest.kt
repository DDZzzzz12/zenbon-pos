package org.example.project.admin.features.permissions

import org.example.project.admin.features.permissions.presentation.CreateGroupDraft
import org.example.project.admin.features.permissions.presentation.GroupDraft
import org.example.project.admin.features.permissions.presentation.PermissionGroupsState
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionGroupsDirtyTest {
    private val floor =
        PermissionGroupDto.PermissionGroupResponseDto(
            code = "FLOOR",
            name = "Floor",
            description = null,
            isSystem = false,
            isDeletable = true,
            capabilities = setOf("SELF_READ"),
            memberCount = 0,
        )

    private fun data(draft: GroupDraft?) =
        PermissionGroupsState.Data(
            groups = listOf(floor),
            catalog = emptyList(),
            selectedCode = "FLOOR",
            draft = draft,
        )

    @Test
    fun `no draft is clean`() {
        assertFalse(data(null).isDirty)
    }

    @Test
    fun `a changed draft is dirty`() {
        assertTrue(data(GroupDraft.of(floor).copy(name = "Floor lead")).isDirty)
    }

    @Test
    fun `an edit that was undone is clean`() {
        assertFalse(data(GroupDraft.of(floor)).isDirty)
    }

    @Test
    fun `a create draft counts only typed input, not saving or error flags`() {
        assertFalse(CreateGroupDraft().hasInput)
        assertFalse(CreateGroupDraft(saving = true).hasInput)
        assertTrue(CreateGroupDraft(code = "X").hasInput)
    }
}
