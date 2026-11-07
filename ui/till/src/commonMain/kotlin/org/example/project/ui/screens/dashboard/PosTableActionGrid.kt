package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableActionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosTileButton
import org.example.project.ui.theme.PosTheme

private data class ActionItem(
    val id: String,
    val icon: ImageVector? = null,
    val label: String,
    val onClick: () -> Unit,
    val isDestructive: Boolean = false,
    val isPrimary: Boolean = false,
)

@Composable
fun PosTableActionGrid(
    onNewOrder: () -> Unit,
    onCreateBill: () -> Unit,
    onRequestBilling: () -> Unit = {},
    onTransfer: () -> Unit,
    onHistory: () -> Unit,
    onAssignCustomer: () -> Unit,
    onAddVoucher: () -> Unit = {},
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this bill is the caller's to create — their own table, or cross-employee billing is
     * enabled for the shop (`TableBillingActions.ownsBill`).
     *
     * Stays a parameter, unlike the capability gates below: it is OWNERSHIP, and only the caller
     * knows whose table this is.
     */
    ownsBill: Boolean = true,
    /**
     * `TokenStore.isStation` — true only for the order station's own session. The station's shared
     * account is offered no billing action at all — neither "Rechnung" nor "Rechnung anfordern" —
     * whatever [ownsBill] says (finding OS-b); a person's login, on any device, is not a station session.
     */
    isStation: Boolean = false,
    isVoucherEnabled: Boolean = false,
) {
    // Asked here rather than passed in. `holds` reads LocalAccessPolicy, which every caller already
    // sits inside, and a parameter would let one call site answer differently from another.
    //
    // Both `transfer` and `delete` hit Cap(TABLE_SESSION_OVERRIDE) server-side — `/api/tables/{id}/
    // transfer` and `DELETE /api/tables/{id}/session` — so they ask one question, not two. The
    // single `isManagerOrHigher` this replaces also covered creating a bill, which is a different
    // capability and now asks separately.
    val canOverrideSession = holds(Capability.TABLE_SESSION_OVERRIDE)
    val canWriteBills = holds(Capability.BILL_WRITE)
    // Both used to hide behind `!isTerminal`. Assigning a customer is in the station mask (the
    // order flow attaches one); vouchers are not. The capability says so; the role no longer has to.
    val canAssignCustomer = holds(Capability.CUSTOMER_WRITE)
    val canAddVoucher = holds(Capability.VOUCHER_WRITE)
    // Resolve labels at composition time
    val newOrderLabel = str(DashboardKeys.newOrderAction)
    val createBillLabel = str(DashboardKeys.createBillAction)
    val requestBillingLabel = str(TableActionKeys.requestBillingAction)
    val transferLabel = str(DashboardKeys.transferAction)
    val historyLabel = str(DashboardKeys.historyAction)
    val assignLabel = str(DashboardKeys.assignCustomerAction)
    val voucherLabel = str(TableActionKeys.addVoucherAction)
    val deleteLabel = str(DashboardKeys.deleteTableAction)

    val billing = TableBillingActions.resolve(isStation, ownsBill, canWriteBills, canOverrideSession)

    val actions =
        remember(
            billing,
            canOverrideSession,
            canAssignCustomer,
            canAddVoucher,
            isVoucherEnabled,
            newOrderLabel,
            createBillLabel,
            requestBillingLabel,
            transferLabel,
            historyLabel,
            assignLabel,
            voucherLabel,
            deleteLabel,
        ) {
            buildList {
//            add(ActionItem("new_order", Icons.Default.ShoppingCart, newOrderLabel, onNewOrder, isPrimary = true))
                // Both billing actions come from `TableBillingActions.resolve`: an order station is
                // offered neither (OS-b — a station bills in no way, whatever the shop's
                // cross-employee setting or a step-up says); the till needs BILL_WRITE plus ownership
                // or the TABLE_SESSION_OVERRIDE escape for the bill, and is offered the request for a
                // table that is not its to bill.
                if (billing.bill) {
                    add(ActionItem("create_bill", Icons.Default.Receipt, createBillLabel, onCreateBill, isPrimary = true))
                }

                if (billing.requestBill) {
                    add(ActionItem("request_billing", null, requestBillingLabel, onRequestBilling, isPrimary = true))
                }
                if (canOverrideSession) {
                    add(ActionItem("transfer", Icons.Default.SwapHoriz, transferLabel, onTransfer))
                }
                add(ActionItem("history", Icons.Default.History, historyLabel, onHistory))
                if (canAssignCustomer) {
                    add(ActionItem("assign", Icons.Default.PersonAdd, assignLabel, onAssignCustomer))
                }
                if (canAddVoucher && isVoucherEnabled) {
                    add(ActionItem("voucher", Icons.Default.CardGiftcard, voucherLabel, onAddVoucher))
                }
                if (canOverrideSession) {
                    add(ActionItem("delete", Icons.Default.Delete, deleteLabel, onDelete, isDestructive = true))
                }
            }
        }

    val rows = (actions.size + 2) / 3

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.height((rows * 70 + (rows - 1).coerceAtLeast(0) * 8).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(actions, key = { it.id }) { action ->
            val bgColor =
                when {
                    action.isPrimary -> MaterialTheme.colorScheme.primary
                    action.isDestructive -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            val fgColor =
                when {
                    action.isPrimary -> MaterialTheme.colorScheme.onPrimary
                    action.isDestructive -> PosTheme.colors.posError
                    else -> MaterialTheme.colorScheme.onSurface
                }
            val border =
                when {
                    action.isPrimary -> null
                    action.isDestructive -> BorderStroke(1.dp, PosTheme.colors.posError)
                    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                }

            PosTileButton(
                icon = action.icon,
                label = action.label,
                onClick = action.onClick,
                containerColor = bgColor,
                contentColor = fgColor,
                border = border,
                height = 70.dp,
            )
        }
    }
}
