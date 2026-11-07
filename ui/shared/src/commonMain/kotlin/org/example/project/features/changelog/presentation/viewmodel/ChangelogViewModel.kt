package org.example.project.features.changelog.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ChangelogApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.changelog.presentation.state.ChangelogUiState

/**
 * ViewModel for the Changelog ("What's new") screen.
 *
 * Loads release-note entries from the paginated `GET /api/v1/changelog`
 * endpoint and exposes them as a [StateFlow]. The first page is loaded in
 * [init]; subsequent pages are appended by [loadMore] until the server
 * reports no further page.
 *
 * @param apiClient API client for fetching the paginated changelog
 */
class ChangelogViewModel(
    private val apiClient: ChangelogApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("ChangelogViewModel")

    private val _state = MutableStateFlow(ChangelogUiState())
    val state: StateFlow<ChangelogUiState> = _state.asStateFlow()

    private val pageSize: Int = 20

    init {
        loadFirstPage()
    }

    /**
     * (Re)load the first page, replacing the current list.
     */
    fun loadFirstPage() =
        viewModelScope.launch {
            if (_state.value.isLoading || _state.value.isLoadingMore) return@launch
            logger.i { "ChangelogViewModel.loadFirstPage: loading page=0" }
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatchingApi { apiClient.list(page = 0, size = pageSize) }
                .onSuccess { page0 ->
                    logger.i { "ChangelogViewModel.loadFirstPage: loaded ${page0.items.size} entries" }
                    _state.value =
                        ChangelogUiState(
                            items = page0.items,
                            pagination = page0,
                            isLoading = false,
                        )
                }.onError { uiError ->
                    logger.e { "ChangelogViewModel.loadFirstPage: failed to load changelog: $uiError" }
                    _state.value = _state.value.copy(isLoading = false, error = uiError)
                }
        }

    /**
     * Append the next page to the current list, if one exists.
     */
    fun loadMore() =
        viewModelScope.launch {
            if (_state.value.isLoading || _state.value.isLoadingMore) return@launch
            val current = _state.value.pagination ?: return@launch
            if (!current.pagination.hasNext) return@launch
            logger.i { "ChangelogViewModel.loadMore: loading page=${current.pagination.currentPage + 1}" }
            _state.value = _state.value.copy(isLoadingMore = true)
            val nextPage = current.pagination.currentPage + 1
            runCatchingApi { apiClient.list(page = nextPage, size = pageSize) }
                .onSuccess { page ->
                    _state.value =
                        _state.value.copy(
                            items = _state.value.items + page.items,
                            pagination = page,
                            isLoadingMore = false,
                        )
                }.onError { uiError ->
                    logger.e { "ChangelogViewModel.loadMore: failed to load changelog page: $uiError" }
                    _state.value = _state.value.copy(isLoadingMore = false, error = uiError)
                }
        }

    /**
     * Clear the error state.
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
