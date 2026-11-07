package org.example.project.admin.features.permissions

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.presentation.CreateGroupDraft
import org.example.project.admin.features.permissions.presentation.CreateGroupField
import org.example.project.admin.features.permissions.presentation.GroupEditorField
import org.example.project.admin.features.permissions.presentation.PermissionGroupsState
import org.example.project.admin.features.permissions.presentation.PermissionGroupsViewModel
import org.example.project.admin.features.permissions.presentation.validateCreateGroupDraft
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun group(
    code: String,
    isSystem: Boolean = false,
) = PermissionGroupDto.PermissionGroupResponseDto(
    code = code,
    name = code.lowercase(),
    description = null,
    isSystem = isSystem,
    isDeletable = !isSystem,
    capabilities = setOf("SELF_READ"),
    memberCount = 0,
)

/** Create and edit both validate on the Save click instead of disabling it. */
class PermissionGroupValidationTest {
    private suspend fun loaded(api: FakePermissionGroupApiClient): PermissionGroupsViewModel = PermissionGroupsViewModel(api).also { it.refresh() }

    @Test
    fun submitCreate_onAnEmptyForm_marksCodeAndName_andMakesNoRequest() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)))
            val vm = loaded(api)
            vm.openCreate()

            vm.submitCreate()

            val draft = assertNotNull(vm.createDraft)
            assertEquals(
                mapOf(CreateGroupField.CODE to CommonKeys.fieldRequired, CreateGroupField.NAME to CommonKeys.fieldRequired),
                draft.fieldErrors,
            )
            assertEquals(1, draft.validationAttempt)
            assertNull(api.created)
            assertFalse(draft.hasInput, "validation flags are not input: closing still does not ask")
        }

    @Test
    fun editingTheCode_clearsOnlyItsError_andAFixedDraftIsCreated() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)))
            val vm = loaded(api)
            vm.openCreate()
            vm.submitCreate()

            vm.editCreate { it.copy(code = "floor lead") }
            assertEquals(setOf(CreateGroupField.NAME), assertNotNull(vm.createDraft).fieldErrors.keys)

            vm.editCreate { it.copy(name = "Floor lead") }
            vm.submitCreate()
            assertEquals("FLOOR_LEAD", api.created?.code)
        }

    @Test
    fun aCodeWithPunctuation_isRefusedWithItsOwnMessage() {
        val errors = validateCreateGroupDraft(CreateGroupDraft(code = "floor-lead", name = "Floor lead"))
        assertEquals(mapOf(CreateGroupField.CODE to AdminStringKeys.PermissionGroupCodeInvalid), errors)
    }

    @Test
    fun saveEdit_withABlankNameOnACustomGroup_marksTheName_andMakesNoRequest() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("FLOOR")))
            val vm = loaded(api)
            vm.select("FLOOR")
            vm.editName("  ")

            vm.save()

            val data = assertIs<PermissionGroupsState.Data>(vm.state)
            assertEquals(CommonKeys.fieldRequired, data.draftErrors[GroupEditorField.NAME])
            assertEquals(1, data.validationAttempt)
            assertNull(api.updated)

            vm.editName("Floor")
            assertTrue(assertIs<PermissionGroupsState.Data>(vm.state).draftErrors.isEmpty(), "typing clears the error")
            vm.save()
            assertEquals("Floor", api.updated?.name)
        }
}
