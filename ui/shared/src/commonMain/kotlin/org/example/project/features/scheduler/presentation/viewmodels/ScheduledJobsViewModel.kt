package org.example.project.features.scheduler.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient
import org.example.project.features.scheduler.presentation.state.ScheduledJobsState

class ScheduledJobsViewModel(
    private val api: ScheduledJobApiClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ScheduledJobsState())
    val state: StateFlow<ScheduledJobsState> = _state.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() =
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val jobsResult = runCatchingApi { api.list() }
            val typesResult = runCatchingApi { api.listTypes() }
            _state.update {
                it.copy(
                    jobs = jobsResult.getOrNull() ?: it.jobs,
                    types = typesResult.getOrNull() ?: it.types,
                    isLoading = false,
                )
            }
            // Surface the first error encountered (if any) — preserves Batch-1 single-error semantics.
            listOf(jobsResult, typesResult)
                .firstOrNull { it.isFailure }
                ?.onError { _error.value = it }
        }

    fun runNow(id: String) =
        viewModelScope.launch {
            runCatchingApi { api.runNow(id) }
                .onSuccess { refresh() }
                .onError { _error.value = it }
        }

    fun loadDetail(id: String) =
        viewModelScope.launch {
            runCatchingApi { api.getDetail(id) }
                .onSuccess { detail -> _state.update { it.copy(selectedJobDetail = detail) } }
                .onError { _error.value = it }
        }

    fun loadMoreExecutions(id: String) =
        viewModelScope.launch {
            val current = _state.value.selectedJobDetail ?: return@launch
            val offset = current.recentExecutions.size
            runCatchingApi { api.listExecutions(id, limit = 20, offset = offset) }
                .onSuccess { page ->
                    val merged = current.recentExecutions + page.items
                    _state.update {
                        it.copy(
                            selectedJobDetail = current.copy(recentExecutions = merged),
                        )
                    }
                }.onError { _error.value = it }
        }

    fun clearError() {
        _error.value = null
    }

    fun clearSelectedDetail() {
        _state.update { it.copy(selectedJobDetail = null) }
    }
}
