package org.example.project.admin.core.presentation.belegfluss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import org.example.project.admin.features.allocations.presentation.AllocationStripCounts
import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.ui.auth.LocalAccessPolicy
import org.koin.compose.koinInject

private val log = Logger.withTag("belegflow")

/** One page big enough for the whole queue of a shop; a bigger queue still gets its count, just no sum. */
private const val QUEUE_PROBE_LIMIT = 500

/**
 * The open payments the *Zuordnen* tile states, read from the Zuordnung queue once per [refreshKey]
 * (so once per screen load). Null until answered, without `ACCOUNTING_READ` (the queue would only earn a
 * 403) and when the request fails — callers pass that to [InvoiceFlowCounts.withOpenPayments], which
 * draws a dash for it, never a wrong 0.
 */
@Composable
fun rememberOpenPayments(refreshKey: Any? = Unit): OpenPaymentsSummary? {
    val api = koinInject<AllocationApiClient>()
    val mayRead = LocalAccessPolicy.current.allows(Capability.ACCOUNTING_READ)
    var summary by remember { mutableStateOf<OpenPaymentsSummary?>(null) }
    LaunchedEffect(refreshKey, mayRead) {
        if (!mayRead) {
            summary = null
            return@LaunchedEffect
        }
        runCatchingApi { api.queue(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, QUEUE_PROBE_LIMIT, 0) }
            .onSuccess { response ->
                summary = AllocationStripCounts.summaryOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, response.total, response.items)
            }.onError { uiError ->
                summary = null
                log.w { "open payments failed: $uiError" }
            }
    }
    return summary
}
