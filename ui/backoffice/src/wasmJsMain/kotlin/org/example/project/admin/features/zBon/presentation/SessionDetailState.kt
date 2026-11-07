package org.example.project.admin.features.zBon.presentation

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.activeBillsRevenue
import org.example.project.money.Decimal
import org.example.project.ui.components.BillDetailTrail

data class SessionDetailState(
    val session: TableSessionDto.TableSessionResponseDto? = null,
    val sessionLoading: Boolean = false,
    val sessionError: UiError? = null,
    val activeTab: SessionDetailTab = SessionDetailTab.ORDERS,
    val orders: List<OrderDto.OrderResponseDto> = emptyList(),
    val ordersLoading: Boolean = false,
    val ordersError: UiError? = null,
    val selectedOrderId: Long? = null,
    val cancellations: List<OrderItemDto.CancellationResponseDto> = emptyList(),
    val cancellationsLoading: Boolean = false,
    val cancellationsError: UiError? = null,
    val selectedCancellationId: Long? = null,
    val transfers: List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> = emptyList(),
    val transfersLoading: Boolean = false,
    val transfersError: UiError? = null,
    val transferSubTab: TransferDirection = TransferDirection.IN,
    val selectedTransferId: Long? = null,
    val bills: List<BillDto.BillResponseDto> = emptyList(),
    val billsLoading: Boolean = false,
    val billsError: UiError? = null,
    val selectedBill: BillDto.BillResponseDto? = null,
    /** Bills left behind by following related-bill links in the detail panel ("Zurück zu RE-…"). */
    val billTrail: BillDetailTrail = BillDetailTrail(),
) {
    /**
     * The session's revenue: Σ paid amount of its ACTIVE bills. [bills] holds every receipt — Stornos
     * and cancelled originals too — so a plain sum would net a Storno against nothing whenever its
     * original was missing; the active-bill rule is the server's (`activeBillOp`). A receivable
     * clearing booked into the session is left out too: its turnover was the invoiced sale's.
     */
    val billsRevenue: Decimal get() = bills.activeBillsRevenue()
}

enum class SessionDetailTab { ORDERS, CANCELLATIONS, TRANSFERS, BILLS }

enum class TransferDirection { IN, OUT }
