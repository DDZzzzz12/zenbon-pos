package org.example.project.features.employees.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.auth.PermissionManager
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.data.remote.WorkScheduleApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.employees.presentation.state.CashierProfileUiState
import org.example.project.features.employees.presentation.state.CredentialChangeMode
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class CashierProfileViewModel(
    private val employeeApiClient: EmployeeApiClient,
    private val workScheduleApiClient: WorkScheduleApiClient,
    private val timeTrackingApiClient: TimeTrackingApiClient,
    private val permissionManager: PermissionManager,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("CashierProfileViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(CashierProfileUiState())
    val uiState: StateFlow<CashierProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        logger.d { "CashierProfileViewModel.loadProfile: loading current employee profile" }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatchingApi { employeeApiClient.getCurrentEmployee() }
                .onSuccess { employee ->
                    logger.i { "CashierProfileViewModel.loadProfile: loaded employee id=${employee.id}" }
                    _uiState.update { it.copy(employee = employee) }
                    val empId = employee.id
                    launch {
                        runCatchingApi { employeeApiClient.getMyEmployeeInfo() }
                            .onSuccess { info -> _uiState.update { it.copy(employeeInfo = info) } }
                        // Info may not exist yet — silently ignore failures
                    }
                    launch {
                        // WorkScheduleApiClient is now throws-style (Stage C Batch 3).
                        runCatchingApi { workScheduleApiClient.getWeeklySchedule(empId) }
                            .onSuccess { schedule -> _uiState.update { it.copy(schedule = schedule) } }
                        // Schedule fetch failure is non-critical for the profile view.
                    }
                    launch {
                        val now =
                            Clock.System
                                .now()
                                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                        runCatchingApi { timeTrackingApiClient.getMonthlyOverview(now.year, now.month.number) }
                            .onSuccess { data -> _uiState.update { it.copy(overview = data) } }
                        // overview load failure is non-critical for the profile view
                    }
                }.onError { uiError ->
                    logger.e { "CashierProfileViewModel.loadProfile: failed to load employee" }
                    _uiState.update { it.copy(error = uiError) }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun updateMyProfile(request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto) {
        logger.i { "CashierProfileViewModel.updateMyProfile: updating profile" }
        viewModelScope.launch {
            runCatchingApi { employeeApiClient.updateMyEmployeeInfo(request) }
                .onSuccess { data ->
                    logger.i { "CashierProfileViewModel.updateMyProfile: profile updated successfully" }
                    _uiState.update { it.copy(employeeInfo = data, showEditProfileDialog = false, successMessage = localized(EmployeeHubKeys.profileUpdated)) }
                }.onError { uiError ->
                    logger.e { "CashierProfileViewModel.updateMyProfile: failed to update profile" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun changePassword(request: EmployeeDto.PasswordChangeRequestDto) {
        val employeeId = _uiState.value.employee?.id ?: return
        logger.i { "CashierProfileViewModel.changePassword: changing password for employeeId=$employeeId" }
        viewModelScope.launch {
            runCatchingApi { employeeApiClient.changePassword(employeeId, request) }
                .onSuccess {
                    logger.i { "CashierProfileViewModel.changePassword: password changed successfully" }
                    _uiState.update { it.copy(credentialChangeMode = null, successMessage = localized(EmployeeHubKeys.passwordChanged)) }
                }.onError { uiError ->
                    logger.e { "CashierProfileViewModel.changePassword: failed to change password" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun changePin(newPin: String) {
        logger.i { "CashierProfileViewModel.changePin: changing PIN" }
        viewModelScope.launch {
            runCatchingApi { employeeApiClient.changeMyPin(newPin) }
                .onSuccess {
                    logger.i { "CashierProfileViewModel.changePin: PIN changed successfully" }
                    _uiState.update { it.copy(credentialChangeMode = null, successMessage = localized(EmployeeHubKeys.pinChanged)) }
                }.onError { uiError ->
                    logger.e { "CashierProfileViewModel.changePin: failed to change PIN" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun viewEntries() {
        viewModelScope.launch {
            if (_uiState.value.showEntries) {
                _uiState.update { it.copy(showEntries = false) }
                return@launch
            }
            val now =
                Clock.System
                    .now()
                    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            runCatchingApi { timeTrackingApiClient.getMyEntries(now.year, now.month.number) }
                .onSuccess { entries -> _uiState.update { it.copy(entries = entries, showEntries = true) } }
                .onError { uiError -> _uiState.update { it.copy(error = uiError) } }
        }
    }

    fun submitMonth() {
        logger.i { "CashierProfileViewModel.submitMonth: submitting month for approval" }
        viewModelScope.launch {
            val overview = _uiState.value.overview ?: return@launch
            val request = TimeApprovalDto.SubmitRequestDto(year = overview.year, month = overview.month)
            runCatchingApi { timeTrackingApiClient.submitMonth(request) }
                .onSuccess {
                    // Reload overview to reflect new status
                    runCatchingApi { timeTrackingApiClient.getMonthlyOverview(overview.year, overview.month) }
                        .onSuccess { data ->
                            _uiState.update { it.copy(overview = data, successMessage = localized(EmployeeHubKeys.monthSubmitted)) }
                        }.onError { _ ->
                            _uiState.update { it.copy(successMessage = localized(EmployeeHubKeys.monthSubmitted)) }
                        }
                }.onError { uiError -> _uiState.update { it.copy(error = uiError) } }
        }
    }

    fun changeLanguage(languageCode: String) {
        logger.i { "CashierProfileViewModel.changeLanguage: languageCode=$languageCode" }
        viewModelScope.launch {
            runCatchingApi { employeeApiClient.setLanguageForEmployee(languageCode) }
                .onSuccess {
                    // Update local state to reflect new language
                    _uiState.update { state ->
                        state.copy(
                            employee = state.employee?.copy(languagePreference = languageCode),
                            successMessage = localized(EmployeeHubKeys.languageUpdated),
                        )
                    }
                }.onError { uiError -> _uiState.update { it.copy(error = uiError) } }
        }
    }

    fun hideEntries() = _uiState.update { it.copy(showEntries = false) }

    fun showEditProfileDialog() = _uiState.update { it.copy(showEditProfileDialog = true) }

    fun showCredentialDialog(mode: CredentialChangeMode) = _uiState.update { it.copy(credentialChangeMode = mode) }

    fun dismissDialog() = _uiState.update { it.copy(showEditProfileDialog = false, credentialChangeMode = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun clearSuccessMessage() = _uiState.update { it.copy(successMessage = null) }
}
