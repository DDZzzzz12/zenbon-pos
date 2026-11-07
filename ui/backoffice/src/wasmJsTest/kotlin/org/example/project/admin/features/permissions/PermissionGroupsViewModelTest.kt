package org.example.project.admin.features.permissions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.permissions.presentation.PermissionGroupsState
import org.example.project.admin.features.permissions.presentation.PermissionGroupsViewModel
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun group(
    code: String,
    isSystem: Boolean = false,
    capabilities: Set<String> = setOf("SELF_READ"),
    memberCount: Int = 0,
) = PermissionGroupDto.PermissionGroupResponseDto(
    code = code,
    name = code.lowercase().replaceFirstChar { it.uppercase() },
    description = null,
    isSystem = isSystem,
    isDeletable = !isSystem,
    capabilities = capabilities,
    memberCount = memberCount,
)

private val CATALOG =
    listOf(
        PermissionGroupDto.CapabilityInfoDto("ORDER_READ", assignable = true),
        PermissionGroupDto.CapabilityInfoDto("ORDER_WRITE", assignable = true),
        PermissionGroupDto.CapabilityInfoDto("SELF_READ", assignable = true),
        PermissionGroupDto.CapabilityInfoDto("ZBON_CLOSE", assignable = false),
    )

class PermissionGroupsViewModelTest {
    private fun vm(api: FakePermissionGroupApiClient) = PermissionGroupsViewModel(api)

    private suspend fun loaded(api: FakePermissionGroupApiClient): Pair<PermissionGroupsViewModel, PermissionGroupsState.Data> {
        val viewModel = vm(api)
        viewModel.refresh()
        return viewModel to assertIs<PermissionGroupsState.Data>(viewModel.state)
    }

    @Test
    fun `system groups sort first and the first group is selected`() =
        runTest {
            val api =
                FakePermissionGroupApiClient(
                    groups = listOf(group("FLOOR"), group("ADMIN", isSystem = true), group("CASHIER", isSystem = true)),
                    catalog = CATALOG,
                )
            val (_, data) = loaded(api)

            assertEquals(listOf("ADMIN", "CASHIER", "FLOOR"), data.groups.map { it.code })
            assertEquals("ADMIN", data.selectedCode, "something must be selected, or the panel opens empty")
        }

    @Test
    fun `capabilities are grouped by area and sorted`() =
        runTest {
            val (_, data) = loaded(FakePermissionGroupApiClient(groups = listOf(group("FLOOR")), catalog = CATALOG))

            assertEquals(listOf("ORDER", "SELF", "ZBON"), data.catalogByArea.keys.toList())
            assertEquals(2, data.catalogByArea["ORDER"]?.size)
        }

    @Test
    fun `switching group discards the unsaved draft`() =
        runTest {
            val api =
                FakePermissionGroupApiClient(
                    groups = listOf(group("FLOOR"), group("HOSTS")),
                    catalog = CATALOG,
                )
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.toggleCapability("ORDER_WRITE")
            assertNotNull(assertIs<PermissionGroupsState.Data>(viewModel.state).draft)

            viewModel.select("HOSTS")

            // Carrying the draft across would let an operator save FLOOR's capabilities onto HOSTS
            // without noticing — the two panels look identical apart from the heading.
            assertNull(assertIs<PermissionGroupsState.Data>(viewModel.state).draft)
        }

    @Test
    fun `saving a system group sends no capabilities`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("CASHIER", isSystem = true)), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.editName("Service")
            viewModel.save()

            assertEquals("CASHIER", api.updatedCode)
            assertEquals("Service", api.updated?.name)
            // Sending the unchanged set would earn a 403 on an edit that only touched the label:
            // the server refuses ANY capability field on a system group. Null means "leave it alone".
            assertNull(api.updated?.capabilities, "a system group's capabilities must not be sent")
        }

    @Test
    fun `saving a custom group sends the edited capability set`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.toggleCapability("ORDER_WRITE")
            viewModel.save()

            assertEquals(setOf("SELF_READ", "ORDER_WRITE"), api.updated?.capabilities)
            val data = assertIs<PermissionGroupsState.Data>(viewModel.state)
            assertNull(data.draft, "a successful save clears the draft")
            assertEquals(setOf("SELF_READ", "ORDER_WRITE"), data.selected?.capabilities)
        }

    @Test
    fun `a failed save keeps the draft so the edit is not lost`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.toggleCapability("ORDER_WRITE")
            api.onUpdateError = { throw NetworkApiException(RuntimeException("offline")) }
            viewModel.save()

            val data = assertIs<PermissionGroupsState.Data>(viewModel.state)
            assertNotNull(data.draft, "the operator's edit must survive a rejection")
            assertTrue(data.draft!!.capabilities.contains("ORDER_WRITE"))
            assertIs<UiError.Network>(data.actionError)
            assertEquals(false, data.saving)
        }

    @Test
    fun `deleting removes the group and selects another`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true), group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.deleteSelected()

            assertEquals("FLOOR", api.deletedCode)
            val data = assertIs<PermissionGroupsState.Data>(viewModel.state)
            assertEquals(listOf("ADMIN"), data.groups.map { it.code })
            assertEquals("ADMIN", data.selectedCode, "the panel must not be left pointing at a deleted group")
        }

    @Test
    fun `a group with members or a seeded group is never sent for deletion`() =
        runTest {
            val api =
                FakePermissionGroupApiClient(
                    groups = listOf(group("ADMIN", isSystem = true), group("FLOOR", memberCount = 2)),
                    catalog = CATALOG,
                )
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.deleteSelected()
            viewModel.select("ADMIN")
            viewModel.deleteSelected()

            assertNull(api.deletedCode, "the client guard mirrors the disabled Delete and the server's refusal")
            val data = assertIs<PermissionGroupsState.Data>(viewModel.state)
            assertEquals(listOf("ADMIN", "FLOOR"), data.groups.map { it.code })
        }

    @Test
    fun `creating normalises the code and selects the new group`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.openCreate()
            viewModel.editCreate { it.copy(code = "floor lead", name = "Floor lead", capabilities = setOf("ORDER_READ")) }
            viewModel.submitCreate()

            // The server normalises and then freezes the code, so the client must send what will
            // actually be stored — otherwise the operator creates "floor lead" and cannot find it.
            assertEquals("FLOOR_LEAD", api.created?.code)
            assertNull(viewModel.createDraft, "a successful create closes the dialog")
            val data = assertIs<PermissionGroupsState.Data>(viewModel.state)
            assertEquals("FLOOR_LEAD", data.selectedCode)
            assertTrue(data.groups.any { it.code == "FLOOR_LEAD" })
        }

    @Test
    fun `a failed create keeps the dialog open with its error`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.openCreate()
            viewModel.editCreate { it.copy(code = "ADMIN", name = "Copy") }
            api.onCreateError = { throw NetworkApiException(RuntimeException("offline")) }
            viewModel.submitCreate()

            // Duplicate code and reserved capability are both fixed in place, so escalating to a
            // full-screen error dialog would throw away the form the operator is halfway through.
            val draft = assertNotNull(viewModel.createDraft)
            assertIs<UiError.Network>(draft.error)
            assertEquals(false, draft.saving)
        }

    @Test
    fun `an invalid create is not submitted`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.openCreate()
            viewModel.editCreate { it.copy(code = "ok", name = "  ") }
            viewModel.submitCreate()

            assertNull(api.created, "a blank name must not reach the server")
        }

    @Test
    fun `a load failure surfaces as a retryable error state`() =
        runTest {
            val api = FakePermissionGroupApiClient(onListError = { throw NetworkApiException(RuntimeException("offline")) })
            val viewModel = vm(api)
            viewModel.refresh()

            val state = assertIs<PermissionGroupsState.Error>(viewModel.state)
            assertIs<UiError.Network>(state.error)
        }

    @Test
    fun `a refresh keeps the selected group when it still exists`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true), group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.refresh()

            assertEquals("FLOOR", assertIs<PermissionGroupsState.Data>(viewModel.state).selectedCode)
        }

    @Test
    fun `the editor starts closed so a phone lands on the list`() =
        runTest {
            val (viewModel, data) = loaded(FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)), catalog = CATALOG))

            // selectedCode is non-null (first group) but the drill must still show the list.
            assertEquals("ADMIN", data.selectedCode)
            assertEquals(false, viewModel.detailOpen)
        }

    @Test
    fun `selecting a group opens the editor and closing it drops the draft`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true), group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            assertEquals(true, viewModel.detailOpen)
            viewModel.toggleCapability("ORDER_WRITE")
            assertNotNull(assertIs<PermissionGroupsState.Data>(viewModel.state).draft)

            viewModel.closeDetail()

            assertEquals(false, viewModel.detailOpen)
            // Same rule as switching groups: an unsaved edit must not survive leaving the editor.
            assertNull(assertIs<PermissionGroupsState.Data>(viewModel.state).draft)
            assertEquals("FLOOR", assertIs<PermissionGroupsState.Data>(viewModel.state).selectedCode)
        }

    @Test
    fun `deleting the open group returns to the list`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true), group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.deleteSelected()

            assertEquals(false, viewModel.detailOpen, "the editor must not stay open on a group that no longer exists")
        }

    @Test
    fun `creating a group opens its editor`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true)), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.openCreate()
            viewModel.editCreate { it.copy(code = "floor", name = "Floor", capabilities = setOf("ORDER_READ")) }
            viewModel.submitCreate()

            assertEquals(true, viewModel.detailOpen)
            assertEquals("FLOOR", assertIs<PermissionGroupsState.Data>(viewModel.state).selectedCode)
        }

    @Test
    fun `a refresh that no longer finds the open group returns to the list`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("ADMIN", isSystem = true), group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            assertEquals(true, viewModel.detailOpen)
            api.groups = listOf(group("ADMIN", isSystem = true))
            viewModel.refresh()

            assertEquals("ADMIN", assertIs<PermissionGroupsState.Data>(viewModel.state).selectedCode)
            assertEquals(false, viewModel.detailOpen, "the editor must not stay open on a group that vanished")
        }

    @Test
    fun `closing the editor is inert while a save is in flight`() =
        runTest {
            val api = FakePermissionGroupApiClient(groups = listOf(group("FLOOR")), catalog = CATALOG)
            val (viewModel, _) = loaded(api)
            viewModel.select("FLOOR")
            viewModel.toggleCapability("ORDER_WRITE")
            val gate = CompletableDeferred<Unit>()
            api.updateGate = gate
            viewModel.save()
            assertEquals(true, assertIs<PermissionGroupsState.Data>(viewModel.state).saving)

            viewModel.closeDetail()

            // The save owns the draft: dropping it now would lose the edit if the server rejects it.
            assertEquals(true, viewModel.detailOpen)
            assertNotNull(assertIs<PermissionGroupsState.Data>(viewModel.state).draft)
            gate.complete(Unit)
        }
}
