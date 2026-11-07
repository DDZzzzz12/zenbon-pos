package org.example.project.admin.features.tse.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.TseFindingsApiClient
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.FileSaver

/**
 * Drives TSE-Befunde: the transaction numbers our own till data cannot account for, and the
 * append-only register that explains them.
 *
 * Every mutation ends in [load] rather than adjusting the local lists: the server decides which
 * findings a new explanation now covers, and which earlier entry it superseded, so a locally
 * patched list drifts from the register an auditor would read.
 */
class TseFindingsViewModel(
    private val api: TseFindingsApiClient,
) : ViewModel() {
    private val log = Logger.withTag("tseFindings")

    var state by mutableStateOf(TseFindingsState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearExplainError() {
        state = state.copy(explainError = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.findings() }
                .onSuccess { response ->
                    log.d { "tse findings loaded: findings=${response.findings.size} explanations=${response.explanations.size}" }
                    state =
                        state.copy(
                            isLoading = false,
                            findings = response.findings,
                            explanations = response.explanations,
                            history = response.history,
                        )
                }.onError(retry = { load() }) { uiError ->
                    log.e { "tse findings load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /**
     * Records why [finding] has no POS record.
     *
     * The request is validated with the **same** `validationError()` the server calls before it
     * writes, so the two levels cannot disagree (CLAUDE.md rule 8); a rejected one never reaches the
     * API. Re-explaining an already-explained finding sends `supersedesId`, which is why a
     * correction is a new entry rather than an edit — the register is append-only.
     */
    fun explain(
        finding: TseExplanationDto.FindingDto,
        category: TseExplanationCategory,
        text: String,
    ) {
        val request =
            TseExplanationDto.CreateRequestDto(
                tseSerialHex = finding.tseSerialHex,
                tanrFrom = finding.tanrFrom,
                tanrTo = finding.tanrTo,
                category = category,
                text = text.trim(),
                supersedesId = finding.explanationId,
            )
        val validationError = request.validationError()
        if (validationError != null) {
            log.w { "tse explanation blocked: $validationError" }
            // Only a blank text earns the "enter an explanation" wording; an inverted range or a
            // text over the limit is a different problem and must not be reported as an empty one.
            val key =
                if (request.text.isBlank()) AdminStringKeys.TseFindingsTextRequired else AdminStringKeys.TseFindingsNotAccepted
            state = state.copy(explainError = key)
            return
        }
        state = state.copy(isBusy = true, explainError = null, error = null)
        viewModelScope.launch { dispatchExplain(request) }
    }

    private suspend fun dispatchExplain(request: TseExplanationDto.CreateRequestDto) {
        val result = runCatchingApi { api.explain(request) }
        // A server-side refusal of the CONTENT belongs under the input, like the client-side one:
        // routing it to `error` would put it behind a global toast while the operator's text sits in
        // a dialog that says nothing. Only a success raises explainAcceptedCount, which closes it. Everything else — auth,
        // network, a 500 — stays a global error with its retry.
        val refusal = result.exceptionOrNull() as? PosApiException
        if (refusal != null && refusal.code == PosErrorCode.VALIDATION_FAILED) {
            log.w { "tse explanation refused by the server: ${refusal.message}" }
            state = state.copy(isBusy = false, explainError = AdminStringKeys.TseFindingsNotAccepted)
            return
        }
        result
            .onSuccess { explanation ->
                log.d { "tse explanation ${explanation.id} recorded for TANR ${explanation.tanrFrom}-${explanation.tanrTo}" }
                state = state.copy(isBusy = false, explainAcceptedCount = state.explainAcceptedCount + 1)
                load()
            }.onError { uiError ->
                log.e { "tse explanation failed: $uiError" }
                state = state.copy(isBusy = false, error = uiError)
            }
    }

    /**
     * Hands the browser the register as a file; nothing is fetched, the rows are already loaded.
     *
     * The whole register, not the current view: a correction supersedes an entry for the findings
     * list, but the earlier text is still part of the record an auditor asks for (E2E round 4,
     * R4-5). An older server sends no history, so the current entries are the fallback.
     */
    fun exportCsv() {
        val rows = state.history.ifEmpty { state.explanations }
        FileSaver.saveFile(
            data = TseFindingsCsv.build(rows).encodeToByteArray(),
            filename = "tse-erlaeuterungen.csv",
            mimeType = "text/csv",
            onSuccess = { log.i { "tse explanations exported: ${rows.size} row(s)" } },
            onError = { message -> log.e { "tse explanations export failed: $message" } },
        )
    }
}
