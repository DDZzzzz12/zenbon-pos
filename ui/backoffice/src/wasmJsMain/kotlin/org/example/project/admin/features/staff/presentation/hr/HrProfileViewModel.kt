package org.example.project.admin.features.staff.presentation.hr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.toCanonicalMoneyOrNull

/**
 * HR profile for one employee. Self-contained and keyed to the selected employee, matching the
 * sibling `ScheduleViewModel` / `TimeEntriesViewModel` rather than growing `StaffViewModel`.
 */
class HrProfileViewModel(
    private val employeeApi: EmployeeApiClient,
) : ViewModel() {
    private val log = Logger.withTag("staff-hr")

    var state by mutableStateOf<HrProfileState>(HrProfileState.Loading)
        private set

    fun load(employeeId: String) {
        state = HrProfileState.Loading
        viewModelScope.launch {
            runCatchingApi { fetchInfoOrNull(employeeId) }
                .onSuccess { info ->
                    log.d { "hr profile loaded for $employeeId (present=${info != null})" }
                    state = HrProfileState.Data(employeeId = employeeId, info = info)
                }.onError(retry = { load(employeeId) }) { uiError ->
                    log.e { "hr profile load failed for $employeeId" }
                    state = HrProfileState.Error(uiError)
                }
        }
    }

    /** A missing HR record is the normal case for a new login, not an error — 404 maps to null. */
    private suspend fun fetchInfoOrNull(employeeId: String): EmployeeInfoDto.EmployeeInfoResponseDto? =
        try {
            employeeApi.getEmployeeInfoByEmployeeId(employeeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.example.project.core.network.error.PosApiException) {
            if (e.httpStatus == 404) {
                log.d { "no hr record for $employeeId yet" }
                null
            } else {
                throw e
            }
        }

    var editor by mutableStateOf<HrProfileEditor?>(null)
        private set

    /**
     * The editor as it was opened, to tell an edited form from an untouched one; also, a field blank
     * now but non-blank here is a deliberate clear.
     */
    private var editorOriginal: HrProfileEditor? = null

    /** True while the open editor differs from how it was opened. Read by the leave guard. */
    val editorDirty: Boolean get() = editor?.isDirtyFrom(editorOriginal) == true

    fun openEditor() {
        val data = state as? HrProfileState.Data ?: return
        val info = data.info
        val opened =
            HrProfileEditor(
                employeeId = data.employeeId,
                isCreate = info == null,
                firstName = info?.firstName.orEmpty(),
                lastName = info?.lastName.orEmpty(),
                email = info?.email.orEmpty(),
                phoneNumber = info?.phoneNumber.orEmpty(),
                dateOfBirth = info?.dateOfBirth.orEmpty(),
                street = info?.street.orEmpty(),
                city = info?.city.orEmpty(),
                state = info?.state.orEmpty(),
                zipCode = info?.zipCode.orEmpty(),
                country = info?.country.orEmpty(),
                jobTitle = info?.jobTitle.orEmpty(),
                hireDate = info?.hireDate.orEmpty(),
                hourlyRate = info?.hourlyRate.orEmpty(),
                salary = info?.salary.orEmpty(),
                contractedHours = info?.contractedHours.orEmpty(),
                contractedHoursPeriod = info?.contractedHoursPeriod ?: ContractedHoursPeriod.WEEKLY,
            )
        editorOriginal = opened
        editor = opened
    }

    fun onEditorField(block: (HrProfileEditor) -> HrProfileEditor) {
        editor = editor?.let { previous -> block(previous).let { next -> next.copy(fieldErrors = next.fieldErrors.clearedFor(previous, next)) } }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    /** Blank → null for a create (nothing stored yet); updates go through [hrUpdateRequest], which sends `""` to clear. */
    private fun String.orNullIfBlank(): String? = trim().takeIf { it.isNotBlank() }

    /** Canonical money/decimal string, or null when blank. Throws nothing — invalid returns null. */
    private fun String.canonicalOrNull(): String? = orNullIfBlank()?.toCanonicalMoneyOrNull()

    private fun createRequest(e: HrProfileEditor) =
        EmployeeInfoDto.CreateEmployeeInfoRequestDto(
            employeeId = e.employeeId,
            firstName = e.firstName.trim(),
            lastName = e.lastName.trim(),
            email = e.email.orNullIfBlank(),
            phoneNumber = e.phoneNumber.orNullIfBlank(),
            street = e.street.orNullIfBlank(),
            city = e.city.orNullIfBlank(),
            state = e.state.orNullIfBlank(),
            zipCode = e.zipCode.orNullIfBlank(),
            country = e.country.orNullIfBlank(),
            dateOfBirth = e.dateOfBirth.orNullIfBlank(),
            hireDate = e.hireDate.orNullIfBlank(),
            jobTitle = e.jobTitle.orNullIfBlank(),
            hourlyRate = e.hourlyRate.canonicalOrNull(),
            salary = e.salary.canonicalOrNull(),
            contractedHours = e.contractedHours.canonicalOrNull(),
            contractedHoursPeriod = e.contractedHoursPeriod,
        )

    /** Writes the HR record. Returns true on success; on failure the caller keeps the dialog open. */
    private suspend fun writeHrRecord(e: HrProfileEditor): Boolean =
        try {
            if (e.isCreate) {
                employeeApi.createEmployeeInfo(createRequest(e))
            } else {
                employeeApi.updateEmployeeInfoByEmployeeId(e.employeeId, hrUpdateRequest(e, editorOriginal))
            }
            log.d { "hr record ${if (e.isCreate) "created" else "updated"} for ${e.employeeId}" }
            true
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            log.e(ex) { "hr record write failed for ${e.employeeId}" }
            false
        }

    /** One endpoint, one atomic write. Failure leaves the dialog open with a typed error. */
    fun save() {
        val e = editor ?: return
        if (e.saving) return
        val errors = validateHrEditor(e)
        if (errors.isNotEmpty()) {
            log.d { "HR save refused locally: ${errors.keys}" }
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, error = null)
            return
        }
        editor = e.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            if (!writeHrRecord(e)) {
                editor = e.copy(saving = false, error = HrEditorError.SAVE_FAILED)
                return@launch
            }
            editor = null
            load(e.employeeId)
        }
    }
}
