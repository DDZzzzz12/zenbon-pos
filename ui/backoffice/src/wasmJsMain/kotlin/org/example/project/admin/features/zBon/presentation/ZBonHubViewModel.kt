package org.example.project.admin.features.zBon.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.TableSessionApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.data.remote.getAllSessionsInRange
import org.example.project.core.data.remote.searchAllReceipts
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.FileSaver
import org.example.project.ui.components.BillDetailTrail

/**
 * Drives the Z-Bon hub screen with three tabs: Overview (ZBonDetailPanel), Bills, and Sessions.
 * Loads Z-Bon detail first, then fetches the related bills and sessions in parallel.
 */
class ZBonHubViewModel(
    private val zbonId: Long,
    private val zBonApiClient: ZBonApiClient,
    private val billApiClient: BillApiClient,
    private val sessionApiClient: TableSessionApiClient,
) : ViewModel() {
    private val log = Logger.withTag("zBonHub")

    var state by mutableStateOf(ZBonHubState())
        private set

    fun clearError() {
        state = state.copy(zBonError = null, billsError = null, sessionsError = null)
    }

    fun clearActionError() {
        state = state.copy(actionError = null)
    }

    /** Send this Z-Bon to the configured receipt printer. */
    fun printZBon() {
        if (state.printing) return
        state = state.copy(printing = true, actionError = null)
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.printZBon(zbonId) }
                .onSuccess {
                    log.i { "Z-Bon $zbonId print sent" }
                    state = state.copy(printing = false)
                }.onError { uiError ->
                    log.e { "Z-Bon $zbonId print failed: $uiError" }
                    state = state.copy(printing = false, actionError = uiError)
                }
        }
    }

    /** Download this Z-Bon's PDF and hand it to the browser. */
    fun downloadPdf() {
        if (state.pdfDownloading) return
        state = state.copy(pdfDownloading = true, actionError = null)
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.downloadZBonPdf(zbonId) }
                .onSuccess { bytes ->
                    FileSaver.saveFile(
                        data = bytes,
                        filename = "zbon-$zbonId.pdf",
                        mimeType = "application/pdf",
                        onSuccess = { log.i { "Z-Bon $zbonId PDF downloaded" } },
                        onError = { message -> log.e { "Z-Bon $zbonId PDF save failed: $message" } },
                    )
                    state = state.copy(pdfDownloading = false)
                }.onError { uiError ->
                    log.e { "Z-Bon $zbonId PDF download failed: $uiError" }
                    state = state.copy(pdfDownloading = false, actionError = uiError)
                }
        }
    }

    fun selectTab(tab: ZBonHubTab) {
        state = state.copy(activeTab = tab)
    }

    fun onBillSearchQueryChanged(query: String) {
        state = state.copy(billSearchQuery = query)
    }

    /** Open (or, with null, close) the receipt panel for a bill of this Z-Bon. */
    fun selectBill(bill: BillDto.BillResponseDto?) {
        state = state.copy(selectedBill = bill, billTrail = BillDetailTrail())
    }

    /**
     * Follow a related-bill link in the receipt panel. The Z-Bon's own bills are tried first; a bill
     * outside it (a Storno booked on a later day never is — Stornos are refused after the Z-Bon — but a
     * clearing's invoiced bill can be) is fetched by id.
     */
    fun openLinkedBill(link: BillDto.BillLinkDto) {
        val current = state.selectedBill ?: return
        val loaded = state.bills.firstOrNull { it.billId == link.billId }
        if (loaded != null) {
            showLinked(current, loaded)
            return
        }
        viewModelScope.launch {
            runCatchingApi { billApiClient.getBillById(link.billId) }
                .onSuccess { bill -> showLinked(current, bill) }
                .onError { uiError ->
                    log.e { "Linked bill ${link.billId} load failed: $uiError" }
                    state = state.copy(actionError = uiError)
                }
        }
    }

    /** Return to the bill the last followed link was opened from; a no-op when there is none. */
    fun backToPreviousBill() {
        val (previous, trail) = state.billTrail.back()
        if (previous == null) return
        state = state.copy(selectedBill = previous, billTrail = trail)
    }

    private fun showLinked(
        current: BillDto.BillResponseDto,
        linked: BillDto.BillResponseDto,
    ) {
        state = state.copy(selectedBill = linked, billTrail = state.billTrail.follow(current, linked.billId))
    }

    fun load() {
        state =
            state.copy(
                zBonLoading = true,
                billsLoading = true,
                sessionsLoading = true,
                zBonError = null,
                billsError = null,
                sessionsError = null,
            )
        viewModelScope.launch {
            // Stage 1: load Z-Bon detail — needed before bills/sessions (date range)
            runCatchingApi { zBonApiClient.getById(zbonId) }
                .onSuccess { zBon ->
                    log.d { "Z-Bon $zbonId loaded" }
                    state = state.copy(zBon = zBon, zBonLoading = false)
                    val zbonStart = zBon.startTime
                    val zbonEnd = zBon.endTime

                    // Stage 2: load bills and sessions in parallel
                    runCatchingApi {
                        coroutineScope {
                            val billsDeferred =
                                async {
                                    // Every Beleg of the Z-Bon — Stornos and cancelled originals
                                    // included — paged to the end, never capped at one page.
                                    billApiClient.searchAllReceipts(startDate = zbonStart, endDate = zbonEnd)
                                }
                            val sessionsDeferred =
                                async {
                                    // Every session of the Z-Bon, paged to the end — the
                                    // server's default page of 50 truncated #146 (58 sessions).
                                    sessionApiClient.getAllSessionsInRange(startDate = zbonStart, endDate = zbonEnd)
                                }
                            BillsAndSessions(billsDeferred.await(), sessionsDeferred.await())
                        }
                    }.onSuccess { (bills, sessions) ->
                        log.d { "bills=${bills.size} sessions=${sessions.size} for Z-Bon $zbonId" }
                        state =
                            state.copy(
                                bills = bills,
                                sessions = sessions,
                                billsLoading = false,
                                sessionsLoading = false,
                            )
                    }.onError(retry = { load() }) { uiError ->
                        log.e { "bills/sessions load failed for Z-Bon $zbonId" }
                        state =
                            state.copy(
                                billsLoading = false,
                                sessionsLoading = false,
                                billsError = uiError,
                                sessionsError = uiError,
                            )
                    }
                }.onError(retry = { load() }) { uiError ->
                    log.e { "Z-Bon $zbonId load failed" }
                    state =
                        state.copy(
                            zBonLoading = false,
                            billsLoading = false,
                            sessionsLoading = false,
                            zBonError = uiError,
                        )
                }
        }
    }
}

private data class BillsAndSessions(
    val bills: List<org.example.project.core.domain.dto.bill.BillDto.BillResponseDto>,
    val sessions: List<org.example.project.core.domain.dto.table.TableSessionDto.TableSessionResponseDto>,
)
