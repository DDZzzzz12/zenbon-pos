package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.core.data.database.LocalCacheAvailability
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

class GeneralSettingsViewModel(
    private val syncService: SyncService,
    localCache: LocalCacheAvailability,
) : ViewModel() {
    /**
     * Whether to offer the "sync product data" action at all. It force-fills the local cache, so
     * on a client without one it would report success having done nothing.
     */
    val showProductSync: Boolean = localCache.isAvailable

    private val logger = Logger.withTag("GeneralSettingsViewModel")

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun syncProductData() {
        if (_syncState.value == SyncState.InProgress) return

        viewModelScope.launch {
            _syncState.value = SyncState.InProgress
            logger.i { "Starting force sync of product data" }

            runCatchingApi { syncService.performCompleteSync() }
                .onSuccess {
                    logger.i { "Product data sync completed successfully" }
                    _syncState.value = SyncState.Success
                }.onError { uiError ->
                    logger.e { "Product data sync failed: $uiError" }
                    _syncState.value = SyncState.Failed
                }

            delay(3000)
            _syncState.value = SyncState.Idle
        }
    }
}

enum class SyncState {
    Idle,
    InProgress,
    Success,
    Failed,
}
