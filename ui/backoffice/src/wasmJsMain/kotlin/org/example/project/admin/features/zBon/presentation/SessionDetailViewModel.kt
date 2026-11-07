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
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.data.remote.TableSessionApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.ui.components.BillDetailTrail

class SessionDetailViewModel(
    private val sessionId: Long,
    private val sessionApiClient: TableSessionApiClient,
    private val billApiClient: BillApiClient,
    private val orderApiClient: OrderApiClient,
    private val orderItemApiClient: OrderItemApiClient,
) : ViewModel() {
    private val log = Logger.withTag("SessionDetail")

    var state by mutableStateOf(SessionDetailState())
        private set

    fun clearError() {
        state = state.copy(sessionError = null, ordersError = null, cancellationsError = null, transfersError = null, billsError = null)
    }

    fun selectTab(tab: SessionDetailTab) {
        state = state.copy(activeTab = tab)
    }

    fun selectOrder(id: Long) {
        state =
            state.copy(
                selectedOrderId =
                    if (state.selectedOrderId ==
                        id
                    ) {
                        null
                    } else {
                        id
                    },
                selectedCancellationId = null,
                selectedTransferId = null,
                selectedBill = null,
            )
    }

    fun selectCancellation(id: Long) {
        state =
            state.copy(
                selectedCancellationId =
                    if (state.selectedCancellationId ==
                        id
                    ) {
                        null
                    } else {
                        id
                    },
                selectedOrderId = null,
                selectedTransferId = null,
                selectedBill = null,
            )
    }

    fun selectTransfer(id: Long) {
        state =
            state.copy(
                selectedTransferId =
                    if (state.selectedTransferId ==
                        id
                    ) {
                        null
                    } else {
                        id
                    },
                selectedOrderId = null,
                selectedCancellationId = null,
                selectedBill = null,
            )
    }

    fun selectBill(bill: BillDto.BillResponseDto?) {
        state =
            state.copy(
                selectedBill = bill,
                billTrail = BillDetailTrail(),
                selectedOrderId = null,
                selectedCancellationId = null,
                selectedTransferId = null,
            )
    }

    /**
     * Follow a related-bill link in the detail panel. A Storno or correction usually sits in the same
     * session, so the loaded list is tried first; a bill from another session is fetched by id.
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
                    log.e { "Linked bill ${link.billId} load failed" }
                    state = state.copy(billsError = uiError)
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

    fun clearSelection() {
        state =
            state.copy(
                selectedOrderId = null,
                selectedCancellationId = null,
                selectedTransferId = null,
                selectedBill = null,
                billTrail = BillDetailTrail(),
            )
    }

    fun selectTransferDirection(dir: TransferDirection) {
        state = state.copy(transferSubTab = dir, selectedTransferId = null)
    }

    fun load() {
        state =
            state.copy(
                sessionLoading = true,
                sessionError = null,
                ordersLoading = true,
                ordersError = null,
                cancellationsLoading = true,
                cancellationsError = null,
                transfersLoading = true,
                transfersError = null,
                billsLoading = true,
                billsError = null,
            )
        viewModelScope.launch {
            // Stage 1: load session detail
            runCatchingApi { sessionApiClient.getSessionById(sessionId) }
                .onSuccess { session ->
                    log.d { "Session $sessionId loaded" }
                    state = state.copy(session = session, sessionLoading = false)

                    val startDate = session.startTime?.toString()
                    val endDate = session.endTime?.toString()

                    // Stage 2: load orders, cancellations, transfers, bills in parallel
                    runCatchingApi {
                        coroutineScope {
                            val ordersDeferred =
                                async {
                                    loadOrdersBySession(sessionId)
                                }
                            val cancellationsDeferred =
                                async {
                                    if (startDate != null && endDate != null) {
                                        loadCancellations(startDate, endDate, sessionId)
                                    } else {
                                        emptyList()
                                    }
                                }
                            val transfersDeferred =
                                async {
                                    loadTransfers(sessionId)
                                }
                            val billsDeferred =
                                async {
                                    // Every receipt of the session, the cancelled originals included:
                                    // without them a Storno was listed (and summed) without the sale
                                    // it reverses (admin16 FAIL-2).
                                    billApiClient.getBillsByTableSessionId(sessionId, includeDeleted = true)
                                }
                            AllSessionData(
                                ordersDeferred.await(),
                                cancellationsDeferred.await(),
                                transfersDeferred.await(),
                                billsDeferred.await(),
                            )
                        }
                    }.onSuccess { (orders, cancellations, transfers, bills) ->
                        log.d {
                            "orders=${orders.size} cancellations=${cancellations.size} transfers=${transfers.size} bills=${bills.size}"
                        }
                        state =
                            state.copy(
                                orders = orders,
                                ordersLoading = false,
                                cancellations = cancellations,
                                cancellationsLoading = false,
                                transfers = transfers,
                                transfersLoading = false,
                                bills = bills,
                                billsLoading = false,
                            )
                    }.onError(retry = { load() }) { uiError ->
                        log.e { "Failed loading session data" }
                        state =
                            state.copy(
                                ordersLoading = false,
                                ordersError = uiError,
                                cancellationsLoading = false,
                                cancellationsError = uiError,
                                transfersLoading = false,
                                transfersError = uiError,
                                billsLoading = false,
                                billsError = uiError,
                            )
                    }
                }.onError(retry = { load() }) { uiError ->
                    log.e { "Session $sessionId load failed" }
                    state =
                        state.copy(
                            sessionLoading = false,
                            sessionError = uiError,
                            ordersLoading = false,
                            cancellationsLoading = false,
                            transfersLoading = false,
                            billsLoading = false,
                        )
                }
        }
    }

    private suspend fun loadOrdersBySession(sessionId: Long): List<OrderDto.OrderResponseDto> = orderApiClient.getOrdersByTableSessionId(sessionId)

    private suspend fun loadCancellations(
        startDate: String,
        endDate: String,
        sessionId: Long,
    ): List<OrderItemDto.CancellationResponseDto> {
        val all = orderItemApiClient.getCancellations(startDate, endDate)
        return all.filter { it.sessionId == sessionId }
    }

    private suspend fun loadTransfers(sessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> =
        orderItemApiClient.getTransferHistory(sessionId)
}

private data class AllSessionData(
    val orders: List<OrderDto.OrderResponseDto>,
    val cancellations: List<OrderItemDto.CancellationResponseDto>,
    val transfers: List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>,
    val bills: List<BillDto.BillResponseDto>,
)
