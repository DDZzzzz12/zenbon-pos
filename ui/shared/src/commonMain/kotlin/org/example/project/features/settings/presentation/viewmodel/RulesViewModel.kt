package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.settings.DeviceSettingsMirror
import org.example.project.features.settings.presentation.state.EmployeeRotationItem
import org.example.project.features.settings.presentation.state.RulesState

class RulesViewModel(
    private val configApi: EcrConfigurationApiClient,
    private val employeeApi: EmployeeApiClient,
    private val deviceMirror: DeviceSettingsMirror,
    private val shopId: Long = 1L,
) : ViewModel() {
    private val _state = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingApi { configApi.getOrderRules(shopId) }
                .onSuccess { rules ->
                    deviceMirror.setEmployeeSelectionOnFreeTable(rules.employeeSelectionOnFreeTable)
                    deviceMirror.setRoundRobinAssignment(rules.roundRobinAssignment)
                    _state.update {
                        it.copy(
                            employeeSelectionOnFreeTable = rules.employeeSelectionOnFreeTable,
                            roundRobinAssignment = rules.roundRobinAssignment,
                        )
                    }
                }.onError { uiError -> _state.update { it.copy(error = uiError) } }

            runCatchingApi { configApi.getBillingRules(shopId) }
                .onSuccess { rules ->
                    deviceMirror.setAllowCrossEmployeeBilling(rules.allowCrossEmployeeBilling)
                    _state.update { it.copy(allowCrossEmployeeBilling = rules.allowCrossEmployeeBilling) }
                }.onError { uiError -> _state.update { it.copy(error = uiError) } }

            runCatchingApi { employeeApi.getEmployeeSummaries(limit = 1000, offset = 0, active = null) }
                .onSuccess { summaries ->
                    val items =
                        summaries
                            // A terminal account can never be dealt a table (spec 2026-09-14 §4.3); the server refuses to pool one.
                            .filterNot { it.role == EmployeeRole.TERMINAL_DEVICE.name }
                            .sortedBy { it.employeeNumber } // stable pool order regardless of server order
                            .map {
                                EmployeeRotationItem(
                                    id = it.id,
                                    displayName = it.username,
                                    employeeNumber = it.employeeNumber,
                                    inRotationPool = it.inRotationPool,
                                    isActive = it.isActive,
                                )
                            }
                    _state.update { it.copy(rotationPoolEmployees = items, isLoading = false) }
                }.onError { uiError -> _state.update { it.copy(error = uiError, isLoading = false) } }
        }
    }

    fun setEmployeeSelectionOnFreeTable(enabled: Boolean) {
        val current = _state.value
        val nextRoundRobin = if (enabled) false else current.roundRobinAssignment
        _state.update { it.copy(employeeSelectionOnFreeTable = enabled, roundRobinAssignment = nextRoundRobin) }
        persistOrderRules(employeeSelection = enabled, roundRobin = nextRoundRobin)
    }

    fun setRoundRobinAssignment(enabled: Boolean) {
        val current = _state.value
        val nextSelection = if (enabled) false else current.employeeSelectionOnFreeTable
        _state.update { it.copy(roundRobinAssignment = enabled, employeeSelectionOnFreeTable = nextSelection) }
        persistOrderRules(employeeSelection = nextSelection, roundRobin = enabled)
    }

    fun setAllowCrossEmployeeBilling(enabled: Boolean) {
        _state.update { it.copy(allowCrossEmployeeBilling = enabled) }
        viewModelScope.launch {
            runCatchingApi {
                configApi.updateBillingRules(
                    shopId,
                    ShopConfigurationDto.UpdateBillingRulesRequestDto(allowCrossEmployeeBilling = enabled),
                )
            }.onSuccess { deviceMirror.setAllowCrossEmployeeBilling(it.allowCrossEmployeeBilling) }
                .onError { uiError -> _state.update { it.copy(error = uiError) } }
        }
    }

    fun setRotationPool(
        employeeId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            runCatchingApi { employeeApi.setRotationPool(employeeId, enabled) }
                .onSuccess { updated ->
                    _state.update { s ->
                        s.copy(
                            rotationPoolEmployees =
                                s.rotationPoolEmployees.map {
                                    if (it.id == updated.id) it.copy(inRotationPool = updated.inRotationPool) else it
                                },
                        )
                    }
                }.onError { uiError -> _state.update { it.copy(error = uiError) } }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun persistOrderRules(
        employeeSelection: Boolean,
        roundRobin: Boolean,
    ) {
        viewModelScope.launch {
            runCatchingApi {
                configApi.updateOrderRules(
                    shopId,
                    ShopConfigurationDto.UpdateOrderRulesRequestDto(
                        employeeSelectionOnFreeTable = employeeSelection,
                        roundRobinAssignment = roundRobin,
                    ),
                )
            }.onSuccess { rules ->
                deviceMirror.setEmployeeSelectionOnFreeTable(rules.employeeSelectionOnFreeTable)
                deviceMirror.setRoundRobinAssignment(rules.roundRobinAssignment)
            }.onError { uiError -> _state.update { it.copy(error = uiError) } }
        }
    }
}
