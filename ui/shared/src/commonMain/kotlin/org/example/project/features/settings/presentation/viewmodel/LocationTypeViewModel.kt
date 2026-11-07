package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.settings.data.remote.LocationTypeApiClient
import org.example.project.features.settings.presentation.state.LocationTypeManagementState

/**
 * ViewModel for managing location types.
 *
 * Provides state management for location type CRUD operations
 * with server synchronization via the LocationTypeApiClient.
 */
class LocationTypeViewModel(
    private val locationTypeApiClient: LocationTypeApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("LocationTypeViewModel")

    private val _uiState = MutableStateFlow(LocationTypeManagementState())
    val uiState: StateFlow<LocationTypeManagementState> = _uiState.asStateFlow()

    /**
     * Loads location types from the server.
     */
    fun loadLocationTypes() {
        logger.d { "LocationTypeViewModel.loadLocationTypes: loading location types" }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                )
            }

            runCatchingApi { locationTypeApiClient.getAllLocationTypes() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            locationTypes = data.locationTypes,
                            isLoading = false,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "LocationTypeViewModel.loadLocationTypes: failed to load location types" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    /**
     * Shows the add location type dialog.
     */
    fun showAddLocationTypeDialog() {
        _uiState.update { it.copy(showAddDialog = true) }
    }

    /**
     * Hides the add location type dialog.
     */
    fun hideAddLocationTypeDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = false,
                editingLocationType = null,
            )
        }
    }

    /**
     * Shows the edit location type dialog.
     */
    fun showEditLocationTypeDialog(locationType: LocationTypeDto.ResponseDto) {
        _uiState.update {
            it.copy(
                showAddDialog = true,
                editingLocationType = locationType,
            )
        }
    }

    /**
     * Hides the edit location type dialog.
     */
    fun hideEditLocationTypeDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = false,
                editingLocationType = null,
            )
        }
    }

    /**
     * Adds a new location type. A second call while a save is in flight is ignored ([LocationTypeManagementState.isSaving]),
     * so a double press cannot create the type twice.
     */
    fun addLocationType(
        name: String,
        englishName: String,
        chineseName: String,
        isVirtual: Boolean,
        isTakeaway: Boolean,
        sortOrder: Int,
    ) {
        if (_uiState.value.isSaving) return
        logger.i { "LocationTypeViewModel.addLocationType: name=$name" }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val request =
                LocationTypeDto.CreateRequestDto(
                    name = name,
                    englishName = englishName,
                    chineseName = chineseName,
                    isVirtual = isVirtual,
                    isTakeaway = isTakeaway,
                    sortOrder = sortOrder,
                )
            runCatchingApi { locationTypeApiClient.createLocationType(request) }
                .onSuccess {
                    logger.i { "LocationTypeViewModel.addLocationType: created successfully" }
                    _uiState.update { it.copy(isSaving = false) }
                    loadLocationTypes()
                    hideAddLocationTypeDialog()
                }.onError { uiError ->
                    logger.e { "LocationTypeViewModel.addLocationType: failed to create location type" }
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
        }
    }

    /**
     * Updates an existing location type.
     */
    fun updateLocationType(
        id: Long,
        name: String,
        englishName: String,
        chineseName: String,
        isVirtual: Boolean,
        isTakeaway: Boolean,
        sortOrder: Int,
    ) {
        if (_uiState.value.isSaving) return
        logger.i { "LocationTypeViewModel.updateLocationType: id=$id, name=$name" }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val request =
                LocationTypeDto.UpdateRequestDto(
                    name = name,
                    englishName = englishName,
                    chineseName = chineseName,
                    isVirtual = isVirtual,
                    isTakeaway = isTakeaway,
                    sortOrder = sortOrder,
                )
            runCatchingApi { locationTypeApiClient.updateLocationType(id, request) }
                .onSuccess {
                    logger.i { "LocationTypeViewModel.updateLocationType: updated id=$id successfully" }
                    _uiState.update { it.copy(isSaving = false) }
                    loadLocationTypes()
                    hideAddLocationTypeDialog()
                }.onError { uiError ->
                    logger.e { "LocationTypeViewModel.updateLocationType: failed to update id=$id" }
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
        }
    }

    /**
     * Deletes a location type.
     */
    fun deleteLocationType(locationType: LocationTypeDto.ResponseDto) {
        logger.i { "LocationTypeViewModel.deleteLocationType: id=${locationType.id}" }
        viewModelScope.launch {
            runCatchingApi { locationTypeApiClient.deleteLocationType(locationType.id) }
                .onSuccess {
                    logger.i { "LocationTypeViewModel.deleteLocationType: deleted id=${locationType.id}" }
                    loadLocationTypes()
                }.onError { uiError ->
                    logger.e { "LocationTypeViewModel.deleteLocationType: failed to delete id=${locationType.id}" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
