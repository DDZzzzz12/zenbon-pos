package org.example.project.features.reservation.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reservation.data.remote.ReservationApiClient
import org.example.project.features.reservation.presentation.state.ReservationRulesEvent
import org.example.project.features.reservation.presentation.state.ReservationRulesState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ReservationRulesViewModel(
    private val reservationApiClient: ReservationApiClient,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("ReservationRulesViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(ReservationRulesState())
    val uiState: StateFlow<ReservationRulesState> = _uiState.asStateFlow()

    init {
        loadRules()
    }

    fun onEvent(event: ReservationRulesEvent) {
        when (event) {
            is ReservationRulesEvent.LoadRules -> loadRules(event.startDate, event.endDate)
            is ReservationRulesEvent.DeleteRule -> deleteRule(event.id)
            is ReservationRulesEvent.ShowCreateDialog -> _uiState.update { it.copy(showCreateDialog = true, editingRule = null) }
            is ReservationRulesEvent.HideCreateDialog -> _uiState.update { it.copy(showCreateDialog = false, editingRule = null) }
            is ReservationRulesEvent.EditRule -> _uiState.update { it.copy(showCreateDialog = true, editingRule = event.rule) }
            is ReservationRulesEvent.Refresh -> loadRules()
            is ReservationRulesEvent.CreateRule -> createRule(event.request)
            is ReservationRulesEvent.UpdateRule -> updateRule(event.id, event.request)
        }
    }

    private fun loadRules(
        startDate: String = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(),
        endDate: String =
            Clock.System
                .todayIn(TimeZone.currentSystemDefault())
                .plus(90, DateTimeUnit.DAY)
                .toString(),
    ) {
        viewModelScope.launch {
            logger.i { "ReservationRulesViewModel.loadRules: $startDate to $endDate" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatchingApi { reservationApiClient.getRulesByDateRange(startDate, endDate) }
                .onSuccess { rules ->
                    logger.i { "ReservationRulesViewModel.loadRules: loaded ${rules.size} rules" }
                    _uiState.update { it.copy(rules = rules, isLoading = false) }
                }.onError { uiError ->
                    logger.e { "ReservationRulesViewModel.loadRules: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun createRule(request: ReservationRuleDto.CreateRequestDto) {
        viewModelScope.launch {
            logger.i { "ReservationRulesViewModel.createRule: date=${request.ruleDate}, type=${request.ruleType}" }
            _uiState.update { it.copy(isSaving = true) }
            runCatchingApi { reservationApiClient.createRule(request) }
                .onSuccess {
                    logger.i { "ReservationRulesViewModel.createRule: rule created successfully" }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showCreateDialog = false,
                            editingRule = null,
                            successMessage = localized(ReservationKeys.ruleCreated),
                        )
                    }
                    loadRules()
                }.onError { uiError ->
                    logger.e { "ReservationRulesViewModel.createRule: failed" }
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
        }
    }

    private fun updateRule(
        id: Long,
        request: ReservationRuleDto.UpdateRequestDto,
    ) {
        viewModelScope.launch {
            logger.i { "ReservationRulesViewModel.updateRule: id=$id" }
            _uiState.update { it.copy(isSaving = true) }
            runCatchingApi { reservationApiClient.updateRule(id, request) }
                .onSuccess {
                    logger.i { "ReservationRulesViewModel.updateRule: rule updated successfully" }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showCreateDialog = false,
                            editingRule = null,
                            successMessage = localized(ReservationKeys.ruleUpdated),
                        )
                    }
                    loadRules()
                }.onError { uiError ->
                    logger.e { "ReservationRulesViewModel.updateRule: failed" }
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
        }
    }

    private fun deleteRule(id: Long) {
        viewModelScope.launch {
            logger.i { "ReservationRulesViewModel.deleteRule: id=$id" }
            runCatchingApi { reservationApiClient.deleteRule(id) }
                .onSuccess {
                    _uiState.update { it.copy(successMessage = localized(ReservationKeys.ruleDeleted)) }
                    loadRules()
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
