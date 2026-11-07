package org.example.project.admin.features.finance.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * The Buchungsbeginn banner's state and its three writes, owned by whichever screen shows it.
 *
 * Both Rechnungen and Zuordnung host the banner, and the cut-off decides what either list contains,
 * so neither screen may own the behaviour alone — and one screen's ViewModel must not depend on the
 * other's. This holds it once; each ViewModel keeps an instance and forwards to it.
 *
 * It runs on the hosting ViewModel's [scope] and reports failures through [onError], so an error
 * here reaches the same `PosErrorWatcher` as every other failure on that screen.
 */
class BookkeepingStartController(
    private val api: BookkeepingStartApiClient,
    private val scope: CoroutineScope,
    private val onError: (UiError) -> Unit,
) {
    private val log = Logger.withTag("bookkeeping-start")

    var state by mutableStateOf(BookkeepingStartUiState())
        private set

    /**
     * The banner is raised by the server's `needsAcknowledgement`, never by "is this the first load".
     *
     * That flag is durable: it stays true on every read until a manager confirms the date, which is
     * what lets two screens share one banner without either of them consuming it for the other.
     */
    fun load() {
        scope.launch {
            runCatchingApi { api.get() }
                .onSuccess { response ->
                    log.d { "bookkeeping start=${response.startDate} needsAck=${response.needsAcknowledgement}" }
                    state = state.copy(startDate = response.startDate, showBanner = response.needsAcknowledgement)
                }.onError { uiError ->
                    log.e { "bookkeeping start load failed: $uiError" }
                    onError(uiError)
                }
        }
    }

    /**
     * Dismissing writes, and only then lowers the banner.
     *
     * The acknowledgement is stored server-side, so a dismissal that stayed local would last until
     * the next screen was opened and no further — the flag is true on every read until it is set.
     * The list is deliberately **not** re-read: acknowledging changes no date, so nothing it holds
     * can have moved.
     */
    fun acknowledge() {
        scope.launch {
            runCatchingApi { api.acknowledge() }
                .onSuccess { response ->
                    log.d { "bookkeeping start acknowledged" }
                    state = state.copy(startDate = response.startDate, showBanner = false)
                }.onError { uiError ->
                    log.e { "bookkeeping start acknowledge failed: $uiError" }
                    onError(uiError)
                }
        }
    }

    fun startEditing() {
        state = state.copy(isEditing = true)
    }

    fun cancelEditing() {
        state = state.copy(isEditing = false)
    }

    /**
     * Writes the new cut-off, then hands back to [onSaved] so the hosting screen re-reads its list.
     *
     * The Buchungsbeginn decides which rows that list contains at all, so keeping the current one
     * after changing the date would leave rows on screen the server no longer counts as work.
     *
     * Saving needs no separate [acknowledge]: the server acknowledges as part of the write, since
     * choosing a date means the operator has seen it. A date the server would refuse never leaves
     * here — the same rule that greys out *Speichern*.
     */
    fun save(
        date: String,
        onSaved: () -> Unit,
    ) {
        val iso = isoDateOrNull(date) ?: return
        state = state.copy(isSaving = true)
        scope.launch {
            runCatchingApi { api.update(iso) }
                .onSuccess { response ->
                    log.d { "bookkeeping start set to ${response.startDate}" }
                    state =
                        BookkeepingStartUiState(
                            startDate = response.startDate,
                            showBanner = response.needsAcknowledgement,
                            isEditing = false,
                            isSaving = false,
                        )
                    onSaved()
                }.onError { uiError ->
                    log.e { "bookkeeping start save failed: $uiError" }
                    state = state.copy(isSaving = false)
                    onError(uiError)
                }
        }
    }
}
