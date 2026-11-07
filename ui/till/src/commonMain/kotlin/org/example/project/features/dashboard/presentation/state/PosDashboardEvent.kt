package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.ui.screens.voucher.components.VoucherDialogMode

sealed interface PosDashboardEvent {
    data class SelectTable(
        val table: RTable?,
    ) : PosDashboardEvent

    data class SetPendingTable(
        val table: RTable?,
    ) : PosDashboardEvent

    data class ShowDetailPanel(
        val show: Boolean,
    ) : PosDashboardEvent

    data class ShowTransferDialog(
        val show: Boolean,
    ) : PosDashboardEvent

    data class ShowDeleteDialog(
        val show: Boolean,
    ) : PosDashboardEvent

    data class ShowVoucherDialog(
        val show: Boolean,
        val mode: VoucherDialogMode = VoucherDialogMode.TABLE_SESSION,
    ) : PosDashboardEvent

    data class ShowTableNumberDialog(
        val show: Boolean,
    ) : PosDashboardEvent

    data class ShowEmployeeSelectionDialog(
        val show: Boolean,
    ) : PosDashboardEvent

    data object DismissOverlay : PosDashboardEvent
}
