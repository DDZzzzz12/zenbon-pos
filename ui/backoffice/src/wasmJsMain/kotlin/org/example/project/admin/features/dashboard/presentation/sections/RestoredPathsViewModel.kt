package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Drives the "Nach der Wiederherstellung: Speicherpfade prüfen" notice.
 *
 * Both endpoints require `BACKUP_RESTORE`; the card is composed only for a principal holding it, so
 * neither call is issued by somebody the server would refuse. The server is the guard — while a path
 * is listed it skips the scheduled writers and refuses explicit exports into it — this only offers
 * the decision.
 */
class RestoredPathsViewModel(
    private val backupApiClient: BackupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-restored-paths")

    var state by mutableStateOf(RestoredPathsState())
        private set

    fun refresh() {
        viewModelScope.launch {
            runCatchingApi { backupApiClient.getRestoredPaths() }
                .onSuccess { review ->
                    if (review.pending) log.w { "restored paths await review: ${review.paths.map { it.setting }}" }
                    state = RestoredPathsState(isLoading = false, paths = review.paths)
                }.onError { uiError ->
                    // A failed read must not hide a pending review behind silence: surface it.
                    log.w { "restored-path review load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun decide(
        setting: RestoredPathSetting,
        decision: RestoredPathDecision,
    ) {
        if (state.deciding != null) return
        // Mirrors the server's 400: the restored documents live in the default folder.
        if (decision == RestoredPathDecision.KEEP && !setting.keepAllowed) {
            log.w { "KEEP is not offered for $setting — ignored" }
            return
        }
        state = state.copy(deciding = setting, error = null)
        viewModelScope.launch {
            val request = RestoredPathsDto.ResolveRequestDto(listOf(RestoredPathsDto.DecisionDto(setting, decision)))
            runCatchingApi { backupApiClient.resolveRestoredPaths(request) }
                .onSuccess { review ->
                    log.i { "restored path $setting decided $decision; still pending: ${review.paths.map { it.setting }}" }
                    state =
                        RestoredPathsState(
                            isLoading = false,
                            paths = review.paths,
                            justResolved = !review.pending,
                        )
                }.onError { uiError ->
                    log.e { "deciding restored path $setting failed: $uiError" }
                    state = state.copy(deciding = null, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}
