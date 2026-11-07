package org.example.project.admin.features.staff

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.FakePermissionGroupApiClient
import org.example.project.admin.features.staff.presentation.CredentialsField
import org.example.project.admin.features.staff.presentation.CredentialsMode
import org.example.project.admin.features.staff.presentation.StaffState
import org.example.project.admin.features.staff.presentation.StaffViewModel
import org.example.project.admin.features.staff.presentation.isReadOnlyStaffTarget
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun vm(
    summaries: List<org.example.project.core.domain.dto.employee.EmployeeDto.EmployeeSummaryResponseDto> = emptyList(),
    fake: FakeEmployeeApiClient = FakeEmployeeApiClient(summaries = summaries),
    groupApi: FakePermissionGroupApiClient = FakePermissionGroupApiClient(),
) = StaffViewModel(fake, groupApi)

class StaffViewModelTest {
    @Test
    fun load_populatesDirectory_andCurrentEmployee() =
        runTest {
            val vm = vm(listOf(summary("1", "alice", "CASHIER"), summary("2", "bob", "MANAGER")))
            vm.refresh()
            val data = assertIs<StaffState.Data>(vm.state)
            assertEquals(listOf("alice", "bob"), data.visible.map { it.username })
            assertEquals("me", data.currentEmployeeId) // FakeEmployeeApiClient.current.id
        }

    @Test
    fun search_filtersByUsername() =
        runTest {
            val vm = vm(listOf(summary("1", "alice", "CASHIER"), summary("2", "bob", "MANAGER")))
            vm.refresh()
            vm.onSearch("bo")
            assertEquals(listOf("bob"), assertIs<StaffState.Data>(vm.state).visible.map { it.username })
        }

    @Test
    fun search_matchesTheHrDisplayName_andTheDirectorySortsByIt() =
        runTest {
            val vm =
                vm(
                    listOf(
                        summary("1", "k001", "CASHIER", displayName = "Zora Zander"),
                        summary("2", "k002", "CASHIER", displayName = "Anna Berger"),
                        summary("3", "k003", "CASHIER"),
                    ),
                )
            vm.refresh()
            assertEquals(listOf("k002", "k003", "k001"), assertIs<StaffState.Data>(vm.state).visible.map { it.username })

            vm.onSearch("berger")

            assertEquals(listOf("k002"), assertIs<StaffState.Data>(vm.state).visible.map { it.username })
        }

    @Test
    fun filterInactive_hidesInactive() =
        runTest {
            val vm = vm(listOf(summary("1", "alice", "CASHIER", active = true), summary("2", "bob", "MANAGER", active = false)))
            vm.refresh()
            assertEquals(listOf("alice"), assertIs<StaffState.Data>(vm.state).visible.map { it.username })
        }

    @Test
    fun loadFailure_yieldsNetworkError() =
        runTest {
            val vm = vm(fake = FakeEmployeeApiClient(onError = { throw NetworkApiException(RuntimeException("down")) }))
            vm.refresh()
            assertTrue(vm.state is StaffState.Error)
            assertIs<UiError.Network>((vm.state as StaffState.Error).error)
        }

    @Test
    fun createEmployee_sendsPersonnelNumber() =
        runTest {
            // admin19 FAIL-A: the Personalnummer typed at create was silently dropped.
            val fake = FakeEmployeeApiClient()
            val vm = vm(fake = fake)
            vm.refresh()
            vm.openCreate()
            vm.onEditorField {
                it.copy(username = "carol", password = "secret1", role = "CASHIER", personnelNumber = "K905")
            }
            vm.saveEditor()
            assertEquals("K905", assertNotNull(fake.created).personnelNumber)
        }

    @Test
    fun createEmployee_blankPersonnelNumber_sendsNull() =
        runTest {
            val fake = FakeEmployeeApiClient()
            val vm = vm(fake = fake)
            vm.refresh()
            vm.openCreate()
            vm.onEditorField { it.copy(username = "carol", password = "secret1", role = "CASHIER") }
            vm.saveEditor()
            assertEquals(null, assertNotNull(fake.created).personnelNumber)
        }

    @Test
    fun createEmployee_callsApi() =
        runTest {
            val fake = FakeEmployeeApiClient()
            val vm = vm(fake = fake)
            vm.refresh()
            vm.openCreate()
            vm.onEditorField { it.copy(username = "carol", password = "secret1", role = "CASHIER") }
            vm.saveEditor()
            val created = kotlin.test.assertNotNull(fake.created)
            assertEquals("carol", created.username)
            assertEquals("CASHIER", created.role)
        }

    @Test
    fun editEmployee_sendsUpdate_withoutUsername() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            val vm = vm(fake = fake)
            vm.refresh()
            vm.select("7")
            vm.openEditSelected()
            vm.onEditorField { it.copy(notes = "night shift", employmentStatus = "ACTIVE") }
            vm.saveEditor()
            assertEquals("7", fake.updatedId)
            assertEquals("night shift", kotlin.test.assertNotNull(fake.updated).notes)
        }

    @Test
    fun deactivate_callsApi() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER"), summary("me", "manager", "MANAGER")))
            val vm = vm(fake = fake)
            vm.refresh()
            vm.deactivate("7")
            assertEquals("7", fake.deactivatedId)
        }

    @Test
    fun canDeactivate_false_forSelf_andForLastActiveAdmin() =
        runTest {
            // current = "me" (MANAGER, id "me"); one active admin "a1"
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("me", "manager", "MANAGER"), summary("a1", "boss", "ADMIN")))
            val vm = vm(fake = fake)
            vm.refresh()
            val data = assertIs<StaffState.Data>(vm.state)
            assertFalse(vm.canDeactivate(summary("me", "manager", "MANAGER"))) // self
            assertFalse(vm.canDeactivate(summary("a1", "boss", "ADMIN"))) // last active admin
            assertTrue(vm.canDeactivate(summary("x", "temp", "CASHIER"))) // ordinary
        }

    @Test
    fun canDeactivate_false_forAnAdminRecord_evenWhenMultipleActiveAdmins() =
        runTest {
            val fake =
                FakeEmployeeApiClient(
                    summaries =
                        listOf(
                            summary("me", "manager", "MANAGER"),
                            summary("a1", "boss", "ADMIN"),
                            summary("a2", "boss2", "ADMIN"),
                        ),
                )
            val vm = vm(fake = fake)
            vm.refresh()
            // an ADMIN record is read-only (D5), even when it is not the last admin
            assertFalse(vm.canDeactivate(summary("a1", "boss", "ADMIN")))
        }

    @Test
    fun canChangeRole_false_demotingSelf_orLastAdmin() =
        runTest {
            // current = "me" (MANAGER); admins: only "a1" active
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("me", "manager", "MANAGER"), summary("a1", "boss", "ADMIN")))
            val vm = vm(fake = fake)
            vm.refresh()
            // demoting the last active admin away from ADMIN is blocked
            assertFalse(vm.canChangeRole(summary("a1", "boss", "ADMIN"), newRole = "MANAGER"))
            // an ADMIN record is read-only: even keeping the role is not a write from here
            assertFalse(vm.canChangeRole(summary("a1", "boss", "ADMIN"), newRole = "ADMIN"))
            // demoting yourself is blocked (current "me")
            assertFalse(vm.canChangeRole(summary("me", "manager", "MANAGER"), newRole = "CASHIER"))
            // an ordinary promotion is fine
            assertTrue(vm.canChangeRole(summary("x", "temp", "CASHIER"), newRole = "MANAGER"))
        }

    @Test
    fun resetPassword_reauthsThenResets() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            val vm = vm(fake = fake)
            vm.refresh()
            vm.select("7")
            vm.openResetPassword("7")
            vm.onCredentialField { it.copy(newValue = "secret1", confirmValue = "secret1", managerPassword = "bosspass") }
            vm.saveCredentials()
            assertEquals("manager" to "bosspass", fake.authenticatedWith) // current.username defaults to "manager"
            assertEquals("7", fake.resetPasswordId)
            assertEquals("secret1", fake.resetPasswordValue)
            assertEquals(null, vm.credentialsEditor) // closed on success
        }

    @Test
    fun resetPin_reauthsThenResets() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            val vm = vm(fake = fake)
            vm.refresh()
            vm.select("7")
            vm.openResetPin("7")
            vm.onCredentialField { it.copy(newValue = "1234", confirmValue = "1234", managerPassword = "bosspass") }
            vm.saveCredentials()
            assertEquals("7", fake.resetPinId)
            assertEquals("1234", fake.resetPinValue)
        }

    @Test
    fun saveCredentials_reauthFails_doesNotReset() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            fake.authShouldFail = true
            val vm = vm(fake = fake)
            vm.refresh()
            vm.select("7")
            vm.openResetPassword("7")
            vm.onCredentialField { it.copy(newValue = "secret1", confirmValue = "secret1", managerPassword = "wrong") }
            vm.saveCredentials()
            assertEquals(null, fake.resetPasswordId) // reset never called
            val editor = assertNotNull(vm.credentialsEditor)
            assertEquals(AdminStringKeys.StaffCredErrorReauth, editor.fieldErrors[CredentialsField.MANAGER_PASSWORD])
            assertEquals(1, editor.validationAttempt, "a refused re-auth moves focus to the manager password")
            assertFalse(editor.saving)
        }

    @Test
    fun saveCredentials_mismatch_doesNotCallApi() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            val vm = vm(fake = fake)
            vm.refresh()
            vm.select("7")
            vm.openResetPassword("7")
            vm.onCredentialField { it.copy(newValue = "secret1", confirmValue = "secret2", managerPassword = "bosspass") }
            vm.saveCredentials()
            assertEquals(null, fake.authenticatedWith) // validation blocked before re-auth
            assertEquals(
                mapOf(CredentialsField.CONFIRM_VALUE to AdminStringKeys.StaffCredErrorMismatch),
                assertNotNull(vm.credentialsEditor).fieldErrors,
            )
        }

    @Test
    fun saveEditor_sendsRoleAndPermissionsInOneCall() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.select("1")
            vm.openEditSelected()
            vm.onEditorField { it.copy(role = "MANAGER", notes = "promoted") }
            vm.saveEditor()

            val sent = assertNotNull(fake.updated)
            assertEquals("1", fake.updatedId)
            assertEquals("MANAGER", sent.role)
            assertEquals("promoted", sent.notes)
        }

    @Test
    fun openEditSelected_doesNothingOnAnAdminRecord() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "root", "ADMIN")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.select("1")
            vm.openEditSelected()

            assertEquals(null, vm.editor, "an ADMIN record is read-only")
            assertEquals(null, fake.updated)
        }

    @Test
    fun adminRecord_isReadOnly_forCredentialsAndGroup() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("me", "manager", "MANAGER"), summary("a1", "boss", "ADMIN")))
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()

            vm.openResetPassword("a1")
            vm.openResetPin("a1")
            assertEquals(null, vm.credentialsEditor)
            assertFalse(vm.canAssignGroup("a1"))
            vm.openResetPin("x")
            assertNotNull(vm.credentialsEditor, "other roles are unaffected")
        }

    @Test
    fun isReadOnlyStaffTarget_isTrueOnlyForAdmin() {
        assertTrue(isReadOnlyStaffTarget("ADMIN"))
        assertFalse(isReadOnlyStaffTarget("MANAGER"))
        assertFalse(isReadOnlyStaffTarget("CASHIER"))
        assertFalse(isReadOnlyStaffTarget(null))
    }

    @Test
    fun saveEditor_failsClosedWhenSummaryIsMissing() =
        runTest {
            // "ghost" has no matching entry in the directory summaries, but select()/getEmployeeById
            // still resolves a detail record (the fake always synthesizes one) — the same shape a
            // stale/removed-from-list employee would produce against the real API.
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.select("ghost")
            vm.openEditSelected()
            vm.saveEditor()

            assertEquals("roleGuard", assertNotNull(vm.editor).fieldError)
            assertEquals(null, fake.updated)
        }

    @Test
    fun selectFailure_surfacesATypedError() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            fake.detailShouldFail = true
            vm.select("1")

            val data = assertIs<StaffState.Data>(vm.state)
            assertIs<UiError.Network>(assertNotNull(data.error))
        }

    @Test
    fun deactivateFailure_surfacesATypedError() =
        runTest {
            val fake =
                FakeEmployeeApiClient(
                    summaries = listOf(summary("1", "alice", "CASHIER"), summary("2", "root", "ADMIN")),
                )
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            fake.deactivateShouldFail = true
            vm.deactivate("1")

            val data = assertIs<StaffState.Data>(vm.state)
            assertIs<UiError.Network>(assertNotNull(data.error))
        }

    @Test
    fun clearError_leavesTheDirectoryUsableInsteadOfStrandingASpinner() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            fake.detailShouldFail = true
            vm.select("1")
            vm.clearError()

            val data = assertIs<StaffState.Data>(vm.state)
            assertEquals(null, data.error)
            assertEquals(listOf("alice"), data.visible.map { it.username })
        }

    @Test
    fun openResetPin_worksFromTheListWithoutALoadedDetail() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            // no select() — nothing is loaded into `selected`
            vm.openResetPin("1")

            val editor = assertNotNull(vm.credentialsEditor)
            assertEquals("1", editor.employeeId)
            assertEquals(CredentialsMode.PIN, editor.mode)
        }
    // ---------- Permission group ----------

    private fun groupApi() =
        FakePermissionGroupApiClient(
            groups =
                listOf(
                    permissionGroup("CASHIER", isSystem = true),
                    permissionGroup("ACCOUNTANT", isSystem = true),
                ),
        )

    @Test
    fun load_carriesThePermissionGroupsWhenTheCallerMayReadThem() =
        runTest {
            val vm = vm(listOf(summary("1", "alice", "CASHIER")), groupApi = groupApi())
            vm.refresh()

            assertEquals(listOf("CASHIER", "ACCOUNTANT"), assertIs<StaffState.Data>(vm.state).groups.map { it.code })
        }

    /** NEW-22: without PERMISSION_ADMIN the list is not requested at all, so no 403 is provoked. */
    @Test
    fun load_doesNotRequestTheGroupsWithoutPermissionAdmin() =
        runTest {
            val groups = groupApi()
            val vm = vm(listOf(summary("1", "alice", "CASHIER")), groupApi = groups)
            vm.refresh(mayListGroups = false)

            val data = assertIs<StaffState.Data>(vm.state)
            assertEquals(0, groups.listCalls)
            assertTrue(data.groups.isEmpty())
            assertFalse(vm.canAssignGroup("1"), "no picker without the groups")

            vm.refresh()
            assertEquals(0, groups.listCalls, "a retry keeps the gate")
        }

    @Test
    fun load_survivesA403OnTheGroupList() =
        runTest {
            // Listing groups needs PERMISSION_ADMIN, which most callers of this screen lack, so a
            // rejection here is the NORMAL case. Failing the whole directory over it would make the
            // staff screen unusable for every manager.
            val groups = groupApi()
            groups.onListError = { throw NetworkApiException(RuntimeException("forbidden")) }
            val vm = vm(listOf(summary("1", "alice", "CASHIER")), groupApi = groups)
            vm.refresh()

            val data = assertIs<StaffState.Data>(vm.state)
            assertTrue(data.groups.isEmpty())
            assertEquals(listOf("alice"), data.visible.map { it.username })
        }

    @Test
    fun load_dropsTheTerminalDeviceGroup_butKeepsEveryOther() =
        runTest {
            // TERMINAL_DEVICE is the shared order-station identity, never a person's. The server
            // refuses to assign it (EmployeeServiceImpl.assignPermissionGroup); this is the client
            // half of the same guard (CLAUDE.md rule 8), so the dialog stops offering it as a choice.
            val groups =
                FakePermissionGroupApiClient(
                    groups =
                        listOf(
                            permissionGroup("CASHIER", isSystem = true),
                            permissionGroup("TERMINAL_DEVICE", isSystem = true),
                            permissionGroup("ORDER_ONLY", isSystem = true),
                        ),
                )
            val vm = vm(listOf(summary("1", "alice", "CASHIER")), groupApi = groups)
            vm.refresh()

            // Both halves matter: ORDER_ONLY is a strict subset of TERMINAL_DEVICE and MUST survive,
            // so a filter that dropped everything would pass the first assertion and fail this one.
            val codes = assertIs<StaffState.Data>(vm.state).groups.map { it.code }
            assertFalse(codes.contains("TERMINAL_DEVICE"), "the station identity must not be offered")
            assertEquals(listOf("CASHIER", "ORDER_ONLY"), codes, "every other group must still reach the picker")
        }

    @Test
    fun canAssignGroup_isFalseForYourOwnRecord() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("me", "manager", "MANAGER")))
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()

            // Matches the server: undoing a self-demotion needs the capability just given away.
            assertFalse(vm.canAssignGroup("me"))
            assertTrue(vm.canAssignGroup("1"))
        }

    @Test
    fun canAssignGroup_isFalseWhenTheGroupListIsEmpty() =
        runTest {
            // An empty list is what a caller without PERMISSION_ADMIN sees, so offering the control
            // would only earn them a 403.
            val vm = vm(listOf(summary("1", "alice", "CASHIER")), groupApi = FakePermissionGroupApiClient())
            vm.refresh()

            assertFalse(vm.canAssignGroup("1"))
        }

    @Test
    fun assignGroup_sendsThePickAndRefreshesTheDetail() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            fake.groupCodeById = mapOf("1" to "CASHIER")
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()
            vm.select("1")
            vm.openGroupPicker()
            vm.pickGroup("ACCOUNTANT")
            vm.assignGroup()

            assertEquals("1" to "ACCOUNTANT", fake.assignedTo)
            val data = assertIs<StaffState.Data>(vm.state)
            assertEquals(null, data.groupPicker, "a successful assignment closes the picker")
            assertEquals("ACCOUNTANT", data.selected?.permissionGroupCode, "the panel must show the new group")
        }

    @Test
    fun openGroupPicker_preselectsTheCurrentGroup() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            fake.groupCodeById = mapOf("1" to "ACCOUNTANT")
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()
            vm.select("1")
            vm.openGroupPicker()

            assertEquals("ACCOUNTANT", assertNotNull(assertIs<StaffState.Data>(vm.state).groupPicker).selectedCode)
        }

    @Test
    fun groupPicker_canSaveOnlyOnceTheCurrentGroupIsLeft() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            fake.groupCodeById = mapOf("1" to "ACCOUNTANT")
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()
            vm.select("1")
            vm.openGroupPicker()

            assertFalse(assertNotNull(assertIs<StaffState.Data>(vm.state).groupPicker).canSave)
            vm.assignGroup()
            assertEquals(null, fake.assignedTo, "re-saving the current group must not reach the server")

            vm.pickGroup("CASHIER")
            assertTrue(assertNotNull(assertIs<StaffState.Data>(vm.state).groupPicker).canSave)
        }

    @Test
    fun openGroupPicker_doesNothingForYourOwnRecord() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("me", "manager", "MANAGER")))
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()
            vm.select("me")
            vm.openGroupPicker()

            assertEquals(null, assertIs<StaffState.Data>(vm.state).groupPicker)
        }

    @Test
    fun assignGroup_keepsThePickerOpenOnRejection() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("1", "alice", "CASHIER")))
            val vm = StaffViewModel(fake, groupApi())
            vm.refresh()
            vm.select("1")
            vm.openGroupPicker()
            vm.pickGroup("ACCOUNTANT")
            fake.assignShouldFail = true
            vm.assignGroup()

            val picker = assertNotNull(assertIs<StaffState.Data>(vm.state).groupPicker)
            assertIs<UiError.Network>(picker.error)
            assertFalse(picker.saving)
        }
}
