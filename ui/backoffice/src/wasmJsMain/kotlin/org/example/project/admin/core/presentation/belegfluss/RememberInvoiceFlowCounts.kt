package org.example.project.admin.core.presentation.belegfluss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.ui.auth.LocalAccessPolicy
import org.koin.compose.koinInject

private val log = Logger.withTag("belegflow")

/**
 * The incoming-flow counts for a screen that has no invoice list of its own (Dokumente, Zuordnung):
 * one cheap request (a page of one) whose response carries every bucket count and sum.
 *
 * Null when the principal may not read invoices (the strip would only earn a 403) or until the first
 * answer arrives; a failed request leaves the strip hidden rather than raising an error over a
 * screen that does not depend on it. Reloads whenever [refreshKey] changes.
 */
@Composable
fun rememberInvoiceFlowCounts(refreshKey: Any? = Unit): InvoiceFlowCounts? {
    val api = koinInject<InvoiceListApiClient>()
    val mayRead = LocalAccessPolicy.current.allows(Capability.INVOICE_READ)
    var counts by remember { mutableStateOf<InvoiceFlowCounts?>(null) }
    LaunchedEffect(refreshKey, mayRead) {
        if (!mayRead) {
            counts = null
            return@LaunchedEffect
        }
        runCatchingApi { api.list(bucket = InvoiceListDto.Bucket.ZU_ERFASSEN, limit = 1) }
            .onSuccess { response ->
                counts = InvoiceFlowCounts(response.bucketCounts, response.bucketSums)
            }.onError { uiError -> log.w { "belegflow counts failed: $uiError" } }
    }
    // The Zuordnen tile means open payments on every screen; the invoice bucket count is not that.
    val payments = rememberOpenPayments(refreshKey)
    return counts?.withOpenPayments(payments)
}
