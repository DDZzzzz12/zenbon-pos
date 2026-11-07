package org.example.project.admin.features.zBon.closing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.state.ZBonClosingController

/**
 * Closing the day from the adminApp — the same dialog logic as the till, through [ZBonClosingController].
 * Which till signs is the server's decision (spec 2026-10-09); this VM only shows it.
 */
class ZBonClosingViewModel(
    private val zBonApiClient: ZBonApiClient,
    kassenbuchApiClient: KassenbuchApiClient,
    cashBookApiClient: CashBookApiClient,
) : ViewModel() {
    var state by mutableStateOf(ZBonClosingState())
        private set

    private val closing =
        ZBonClosingController(
            scope = viewModelScope,
            zBonApiClient = zBonApiClient,
            kassenbuchApiClient = kassenbuchApiClient,
            cashBookApiClient = cashBookApiClient,
            readDraft = { state.draft },
            writeDraft = { draft -> state = state.copy(draft = draft) },
        )

    fun open(includeTipPayouts: Boolean) = closing.open(includeTipPayouts)

    fun updateCount(text: String) = closing.updateCount(text)

    fun setTipChecked(
        employeeId: String,
        checked: Boolean,
    ) = closing.setTipChecked(employeeId, checked)

    fun updateTipAmount(
        employeeId: String,
        text: String,
    ) = closing.updateTipAmount(employeeId, text)

    fun setTipMethod(
        employeeId: String,
        method: TipPayoutMethod,
    ) = closing.setTipMethod(employeeId, method)

    fun selectTopUpSource(cashBookId: Long) = closing.selectTopUpSource(cashBookId)

    fun updateTopUpAmount(text: String) = closing.updateTopUpAmount(text)

    fun dismiss() = closing.dismiss()

    fun confirm() {
        if (state.isSubmitting) return
        val currency =
            state.draft
                ?.readiness
                ?.currency
                ?.takeIf { it.isNotBlank() }
        val request = closing.takeRequest() ?: return
        state = state.copy(isSubmitting = true)
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.createZBon(request.countedAmount, request.tipPayouts, request.topUp) }
                .onSuccess { created -> state = state.copy(isSubmitting = false, created = created, createdCurrency = currency) }
                .onError { uiError -> state = state.copy(isSubmitting = false, error = uiError) }
        }
    }

    fun consumeCreated() {
        state = state.copy(created = null, createdCurrency = null)
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}
