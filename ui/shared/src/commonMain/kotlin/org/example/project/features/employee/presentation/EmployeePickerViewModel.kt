package org.example.project.features.employee.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.sortEmployeesForPicker

/**
 * The single method [EmployeePickerViewModel] needs. Narrower than [EmployeeApiClient] so tests and
 * other hosts are not forced to stub the client's full surface.
 */
interface EmployeeSummaryProvider {
    suspend fun getEmployeeSummaries(
        limit: Int? = null,
        offset: Int? = null,
        active: Boolean? = null,
    ): List<EmployeeDto.EmployeeSummaryResponseDto>
}

/** Adapts the full API client to the narrow provider the picker consumes. */
class EmployeeApiSummaryProvider(
    private val client: EmployeeApiClient,
) : EmployeeSummaryProvider {
    override suspend fun getEmployeeSummaries(
        limit: Int?,
        offset: Int?,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> = client.getEmployeeSummaries(limit, offset, active)
}

/**
 * `GET /api/employees/summary` defaults `limit` to 50 server-side. The picker wants the whole
 * roster in one page, not the server's chat-list-sized default, so it asks for the server's actual
 * ceiling (`EmployeeServiceImpl.getEmployeeSummaries`: `limit !in 1..1000`) explicitly.
 */
private const val ROSTER_LIMIT = 1000

/**
 * Owns the employee roster shown by `EmployeePicker` — and only the roster. The selected id belongs
 * to the caller: `koinViewModel()` hands every picker in the app the same instance, so a selection
 * stored here would leak between screens.
 */
class EmployeePickerViewModel(
    private val provider: EmployeeSummaryProvider,
) : ViewModel() {
    private val log = Logger.withTag("employeePicker")

    var state by mutableStateOf(EmployeePickerState())
        private set

    /**
     * Fetches the roster, called from a `LaunchedEffect(activeOnly)` in the composable rather than
     * `init`: adminApp's ViewModels survive logout, so an `init` fetch would show the previous
     * session's roster after a re-login. The guard alone cannot fix that — a fetch-once guard fires
     * exactly once per ViewModel lifetime whether or not a login happened in between — so re-login
     * safety additionally requires the app root to call [reset] on logout (see `AdminApp.kt`).
     *
     * The guard remembers which `activeOnly` produced the current roster: a repeat call with the
     * same value short-circuits, but a changed value (or the very first call) always refetches, so
     * `LaunchedEffect(activeOnly)`'s key is honoured instead of being silently ignored.
     */
    fun load(activeOnly: Boolean) {
        if (state.isLoading) return
        if (state.loaded && state.loadedActiveOnly == activeOnly) return
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                provider.getEmployeeSummaries(limit = ROSTER_LIMIT, active = if (activeOnly) true else null)
            }.onSuccess { employees ->
                // TERMINAL_DEVICE is a device identity, not a person — filtering vouchers/reports by
                // it is meaningless, so it never appears in the picker. Filtered by role, not by name,
                // so every consumer of this shared ViewModel benefits.
                val roster = sortEmployeesForPicker(employees.filterNot { it.role == EmployeeRole.TERMINAL_DEVICE.name })
                log.d { "roster loaded: ${roster.size} employees (activeOnly=$activeOnly)" }
                state = state.copy(employees = roster, isLoading = false, loaded = true, loadedActiveOnly = activeOnly)
            }.onError { uiError ->
                // Deliberately not rethrown: a broken filter must never hide the data behind it.
                log.e { "roster load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * Clears the roster so the next [load] call refetches instead of returning stale, previously
     * fetched data. The app root calls this on logout — the ViewModel instance is shared and outlives
     * the session, so without an explicit reset a re-login would keep showing the prior session's
     * employees.
     */
    fun reset() {
        state = EmployeePickerState()
    }
}
