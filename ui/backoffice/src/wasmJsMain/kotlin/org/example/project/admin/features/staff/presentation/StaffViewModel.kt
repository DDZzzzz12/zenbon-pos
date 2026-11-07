package org.example.project.admin.features.staff.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PermissionGroupApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * The permission group that is the shared order-station identity — never a person's.
 *
 * A literal here on purpose: the client only has to recognise the code the server sends back, and the
 * enforcement lives in `EmployeeServiceImpl.assignPermissionGroup`, which derives the same code from
 * `EmployeeRole` so it cannot drift.
 */
private const val TERMINAL_DEVICE_GROUP_CODE = "TERMINAL_DEVICE"

class StaffViewModel(
    private val employeeApi: EmployeeApiClient,
    private val permissionGroupApi: PermissionGroupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("staff")

    var state by mutableStateOf<StaffState>(StaffState.Loading)
        private set

    /**
     * Whether the caller holds PERMISSION_ADMIN, the capability `GET /api/admin/permission-groups`
     * requires. Remembered from the last [refresh] so a retry keeps the screen's answer.
     */
    private var mayListGroups = true

    /**
     * Loads the directory. [mayListGroups] is the client half of the PERMISSION_ADMIN gate (NEW-22):
     * without it the group list is not requested at all — it used to be fired anyway and answered
     * 403 on every visit — and stays empty, which hides the group picker exactly as before.
     */
    fun refresh(mayListGroups: Boolean = this.mayListGroups) {
        this.mayListGroups = mayListGroups
        state = StaffState.Loading
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val summaries = employeeApi.getEmployeeSummaries()
                    val me = employeeApi.getCurrentEmployee()
                    StaffState.Data(
                        all = summaries,
                        currentEmployeeId = me.id,
                        currentUsername = me.username,
                        // Requires PERMISSION_ADMIN, which most callers of this screen do not hold,
                        // so a 403 here is the normal case and must not fail the whole directory.
                        // Empty then means "you may not assign groups", and the control is hidden.
                        //
                        // TERMINAL_DEVICE is dropped: it is the shared order-station identity, never a
                        // person's. The server refuses to assign it (EmployeeServiceImpl
                        // .assignPermissionGroup) and this is the client half of that guard (rule 8).
                        // Filtered here rather than in the picker so no consumer of `groups` can offer
                        // it. The Berechtigungen screen keeps its own list and still shows the group —
                        // editing what the station may reach is legitimate; handing it to a person is
                        // not. ORDER_ONLY, a strict subset of it, stays: that IS the group for
                        // order-taking staff, which is why this matches on the code and not on what
                        // the group confers.
                        groups =
                            if (mayListGroups) {
                                runCatchingApi { permissionGroupApi.getGroups() }
                                    .getOrDefault(emptyList())
                                    .filterNot { it.code.equals(TERMINAL_DEVICE_GROUP_CODE, ignoreCase = true) }
                            } else {
                                emptyList()
                            },
                    )
                }
            }.onSuccess { state = it }
                .onError(retry = { refresh() }) { uiError ->
                    log.e { "load staff failed" }
                    state = StaffState.Error(uiError)
                }
        }
    }

    /** Clears a dismissed error without discarding what is already on screen. */
    fun clearError() {
        state =
            when (val s = state) {
                is StaffState.Data -> s.copy(error = null)
                is StaffState.Error -> StaffState.Error(null)
                else -> s
            }
    }

    private inline fun withData(block: (StaffState.Data) -> StaffState.Data) {
        (state as? StaffState.Data)?.let { state = block(it) }
    }

    fun onSearch(text: String) = withData { it.copy(search = text) }

    fun toggleShowInactive() = withData { it.copy(showInactive = !it.showInactive) }

    fun selectDetailTab(tab: DetailTab) = withData { it.copy(detailTab = tab) }

    fun select(id: String) {
        withData { it.copy(selectedId = id, selected = null, error = null) }
        viewModelScope.launch {
            runCatchingApi { employeeApi.getEmployeeById(id) }
                .onSuccess { detail ->
                    withData { if (it.selectedId == id) it.copy(selected = detail) else it }
                }.onError(retry = { select(id) }) { uiError ->
                    log.e { "load employee $id failed" }
                    withData { it.copy(error = uiError) }
                }
        }
    }

    var editor by mutableStateOf<EmployeeEditor?>(null)
        private set

    /** The editor as it was opened, to tell an edited form from an untouched one. */
    private var editorOriginal: EmployeeEditor? = null

    /** True while the open editor differs from how it was opened. Read by the leave guard. */
    val editorDirty: Boolean get() = editor?.isDirtyFrom(editorOriginal) == true

    fun openCreate() {
        val fresh = EmployeeEditor()
        editorOriginal = fresh
        editor = fresh
    }

    fun openEditSelected() {
        val e = (state as? StaffState.Data)?.selected ?: return
        if (isReadOnlyStaffTarget(e.role)) return
        val opened =
            EmployeeEditor(
                id = e.id,
                username = e.username,
                role = e.role,
                notes = e.notes ?: "",
                languagePreference = e.languagePreference,
                employmentStatus = e.employmentStatus,
                trainingMode = e.trainingMode,
                personnelNumber = e.personnelNumber ?: "",
            )
        editorOriginal = opened
        editor = opened
    }

    fun onEditorField(block: (EmployeeEditor) -> EmployeeEditor) {
        editor = editor?.let { previous -> block(previous).withEditedErrorsCleared(previous) }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    fun saveEditor() {
        val e = editor ?: return
        val errors = validateEmployeeDraft(e)
        if (errors.isNotEmpty()) {
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null)
            return
        }
        val summary = (state as? StaffState.Data)?.all?.firstOrNull { it.id == e.id }
        if (!e.isCreate && (summary == null || !canChangeRole(summary, e.role))) {
            log.w { "role change for ${e.id} blocked (self/last admin/missing summary)" }
            editor = e.copy(fieldError = "roleGuard", fieldErrors = emptyMap())
            return
        }
        editor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            try {
                if (e.isCreate) {
                    employeeApi.createEmployee(
                        EmployeeDto.CreateEmployeeRequestDto(
                            username = e.username,
                            password = e.password,
                            role = e.role,
                            notes = e.notes.ifBlank { null },
                            languagePreference = e.languagePreference,
                            employmentStatus = e.employmentStatus,
                            trainingMode = e.trainingMode,
                            personnelNumber = e.personnelNumber.ifBlank { null },
                        ),
                    )
                } else {
                    employeeApi.updateEmployee(
                        e.id!!,
                        EmployeeDto.UpdateEmployeeRequestDto(
                            role = e.role,
                            notes = e.notes.ifBlank { null },
                            languagePreference = e.languagePreference,
                            employmentStatus = e.employmentStatus,
                            trainingMode = e.trainingMode,
                            personnelNumber = e.personnelNumber.ifBlank { null },
                        ),
                    )
                }
                log.d { "employee ${if (e.isCreate) "created" else "updated"}" }
                editor = null
                reloadDirectory(e.id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: PosApiException) {
                editor = editor?.copy(saving = false, fieldError = ex.message ?: "save")
            } catch (ex: Throwable) {
                log.e(ex) { "save employee failed" }
                editor = editor?.copy(saving = false, fieldError = "save")
            }
        }
    }

    /** Whether [emp] may be deactivated: not yourself, not an admin record (read-only), and not the last active admin. */
    fun canDeactivate(emp: EmployeeDto.EmployeeSummaryResponseDto): Boolean {
        val data = state as? StaffState.Data ?: return false
        if (emp.id == data.currentEmployeeId) return false
        if (isReadOnlyStaffTarget(emp.role)) return false
        if (emp.role == "ADMIN" && emp.isActive && data.activeAdminCount <= 1) return false
        return true
    }

    fun deactivate(id: String) {
        val data = state as? StaffState.Data ?: return
        val emp = data.all.firstOrNull { it.id == id } ?: return
        if (!canDeactivate(emp)) {
            log.w { "deactivate $id blocked (self or last active admin)" }
            return
        }
        viewModelScope.launch {
            runCatchingApi {
                employeeApi.deactivateEmployee(id)
                log.d { "employee $id deactivated" }
                reloadDirectory(id)
            }.onError(retry = { deactivate(id) }) { uiError ->
                log.e { "deactivate $id failed" }
                withData { it.copy(error = uiError) }
            }
        }
    }

    /** Whether [emp] may take [newRole]: can't demote yourself, can't demote the last active admin. */
    fun canChangeRole(
        emp: EmployeeDto.EmployeeSummaryResponseDto,
        newRole: String,
    ): Boolean {
        val data = state as? StaffState.Data ?: return false
        if (isReadOnlyStaffTarget(emp.role)) return false
        // changing your own role is blocked (avoid self-lockout)
        if (emp.id == data.currentEmployeeId && newRole != emp.role) return false
        // demoting the last active admin away from ADMIN is blocked
        if (emp.role == "ADMIN" && emp.isActive && newRole != "ADMIN" && data.activeAdminCount <= 1) return false
        return true
    }

    private suspend fun reloadDirectory(selectedId: String?) {
        val summaries = employeeApi.getEmployeeSummaries()
        val detail = selectedId?.let { runCatching { employeeApi.getEmployeeById(it) }.getOrNull() }
        withData {
            it.copy(
                all = summaries,
                selected = if (it.selectedId == selectedId) (detail ?: it.selected) else it.selected,
            )
        }
    }

    // ---------- Permission group ----------

    /**
     * Whether the group of [employeeId] may be changed from here.
     *
     * False for your OWN record even when you hold PERMISSION_ADMIN, matching the server: undoing a
     * self-demotion would need the very capability you just gave away. False also when the group
     * list is empty, which is what a caller without PERMISSION_ADMIN sees.
     */
    fun canAssignGroup(employeeId: String): Boolean {
        val data = state as? StaffState.Data ?: return false
        val target = data.all.firstOrNull { it.id == employeeId }
        if (target != null && isReadOnlyStaffTarget(target.role)) return false
        return data.groups.isNotEmpty() && employeeId != data.currentEmployeeId
    }

    fun openGroupPicker() {
        val data = state as? StaffState.Data ?: return
        val employee = data.selected ?: return
        if (!canAssignGroup(employee.id)) return
        withData {
            it.copy(
                groupPicker =
                    GroupPicker(
                        employeeId = employee.id,
                        currentCode = employee.permissionGroupCode,
                        selectedCode =
                            employee.permissionGroupCode ?: data.groups
                                .firstOrNull()
                                ?.code
                                .orEmpty(),
                    ),
            )
        }
    }

    fun pickGroup(code: String) = withData { it.copy(groupPicker = it.groupPicker?.copy(selectedCode = code)) }

    fun closeGroupPicker() = withData { it.copy(groupPicker = null) }

    fun assignGroup() {
        val data = state as? StaffState.Data ?: return
        val picker = data.groupPicker?.takeIf { it.canSave } ?: return

        withData { it.copy(groupPicker = picker.copy(saving = true, error = null)) }
        viewModelScope.launch {
            runCatchingApi { employeeApi.assignPermissionGroup(picker.employeeId, picker.selectedCode) }
                .onSuccess { assignment ->
                    log.d { "assigned ${assignment.employeeId} to ${assignment.groupCode}" }
                    withData { it.copy(groupPicker = null) }
                    reloadDirectory(picker.employeeId)
                }.onError { uiError ->
                    log.e { "assign group failed: $uiError" }
                    // Kept in the dialog: the rejections here (self-assignment, unknown code) are
                    // things the operator resolves in place, not reasons to lose the whole screen.
                    withData { it.copy(groupPicker = picker.copy(saving = false, error = uiError)) }
                }
        }
    }

    var credentialsEditor by mutableStateOf<CredentialsEditor?>(null)
        private set

    fun openResetPassword(employeeId: String) {
        openCredentials(employeeId, CredentialsMode.PASSWORD)
    }

    fun openResetPin(employeeId: String) {
        openCredentials(employeeId, CredentialsMode.PIN)
    }

    /** Keyed by id, not by the loaded detail DTO, so the directory row menu can drive it. */
    private fun openCredentials(
        employeeId: String,
        mode: CredentialsMode,
    ) {
        val target = (state as? StaffState.Data)?.all?.firstOrNull { it.id == employeeId }
        if (target != null && isReadOnlyStaffTarget(target.role)) return
        credentialsEditor = CredentialsEditor(employeeId = employeeId, mode = mode)
    }

    fun onCredentialField(block: (CredentialsEditor) -> CredentialsEditor) {
        credentialsEditor = credentialsEditor?.let { previous -> block(previous).withEditedErrorsCleared(previous) }
    }

    fun closeCredentials() {
        credentialsEditor = null
    }

    fun saveCredentials() {
        val e = credentialsEditor ?: return
        val data = state as? StaffState.Data ?: return
        val errors = validateCredentialsDraft(e)
        if (errors.isNotEmpty()) {
            credentialsEditor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, error = null)
            return
        }
        credentialsEditor = e.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            try {
                employeeApi.authenticate(data.currentUsername, e.managerPassword)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                log.w { "credential re-auth failed for ${e.employeeId}" }
                // The wrong password is the manager-password field's problem: flag it there and move focus to it.
                credentialsEditor =
                    credentialsEditor?.let { current ->
                        current.copy(
                            saving = false,
                            fieldErrors = mapOf(CredentialsField.MANAGER_PASSWORD to AdminStringKeys.StaffCredErrorReauth),
                            validationAttempt = current.validationAttempt + 1,
                        )
                    }
                return@launch
            }
            try {
                when (e.mode) {
                    CredentialsMode.PASSWORD -> employeeApi.resetPassword(e.employeeId, e.newValue)
                    CredentialsMode.PIN -> employeeApi.resetPin(e.employeeId, e.newValue)
                }
                log.d { "credential (${e.mode}) reset for ${e.employeeId}" }
                credentialsEditor = null
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                log.e(ex) { "credential reset failed for ${e.employeeId}" }
                credentialsEditor = credentialsEditor?.copy(saving = false, error = CredentialsError.SERVER)
            }
        }
    }
}
