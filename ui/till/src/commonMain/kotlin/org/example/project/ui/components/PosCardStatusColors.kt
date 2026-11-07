package org.example.project.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@Composable
fun statusColor(status: PosCardStatus): Color =
    when (status) {
        PosCardStatus.Available -> PosTheme.colors.posAvailable
        PosCardStatus.Occupied -> PosTheme.colors.posOccupied
        PosCardStatus.Reserved -> PosTheme.colors.posReserved
        PosCardStatus.Billing -> PosTheme.colors.posBilling
        PosCardStatus.Active -> PosTheme.colors.posAvailable
        PosCardStatus.Pending -> PosTheme.colors.posOccupied
        PosCardStatus.Completed -> MaterialTheme.colorScheme.outline
        PosCardStatus.Cancelled -> MaterialTheme.colorScheme.outline
        PosCardStatus.Warning -> PosTheme.colors.posOccupied
        PosCardStatus.Error -> PosTheme.colors.posError
        PosCardStatus.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
fun tableStatusColor(status: TableCardStatus): Color =
    when (status) {
        TableCardStatus.FREE -> PosTheme.colors.posAvailable
        TableCardStatus.OCCUPIED -> PosTheme.colors.posOccupied
        TableCardStatus.RESERVED -> PosTheme.colors.posReserved
        TableCardStatus.BILLING -> PosTheme.colors.posBilling
        TableCardStatus.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
fun reservationStatusColor(status: ReservationCardStatus): Color =
    when (status) {
        ReservationCardStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
        ReservationCardStatus.SEATED -> PosTheme.colors.posAvailable
        ReservationCardStatus.COMPLETED -> MaterialTheme.colorScheme.outline
        ReservationCardStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        ReservationCardStatus.NO_SHOW -> PosTheme.colors.posError
    }

@Composable
fun orderItemStatusColor(status: OrderItemCardStatus): Color =
    when (status) {
        OrderItemCardStatus.PAID -> PosTheme.colors.posAvailable
        OrderItemCardStatus.OPEN -> PosTheme.colors.posOccupied
        OrderItemCardStatus.CANCELLED -> PosTheme.colors.posError
    }

@Composable
fun voucherStatusColor(status: VoucherCardStatus): Color =
    when (status) {
        VoucherCardStatus.ACTIVE -> PosTheme.colors.posAvailable
        VoucherCardStatus.PARTIALLY_REDEEMED -> PosTheme.colors.posOccupied
        VoucherCardStatus.FULLY_REDEEMED -> MaterialTheme.colorScheme.outline
        VoucherCardStatus.EXPIRED -> PosTheme.colors.posError
        VoucherCardStatus.INVALIDATED -> PosTheme.colors.posError
    }

@Composable
fun StatusChip(
    text: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = statusColor.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = PosSpacing.xs),
        )
    }
}

@Composable
fun TableStatusChip(
    status: TableCardStatus,
    inactive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when {
            inactive -> "Inactive" to MaterialTheme.colorScheme.error
            else -> status.label to tableStatusColor(status)
        }
    StatusChip(text = text, statusColor = color, modifier = modifier)
}

@Composable
fun ReservationStatusChip(
    status: ReservationCardStatus,
    modifier: Modifier = Modifier,
) {
    StatusChip(
        text = reservationStatusLabel(status),
        statusColor = reservationStatusColor(status),
        modifier = modifier,
    )
}

@Composable
private fun reservationStatusLabel(status: ReservationCardStatus): String =
    when (status) {
        ReservationCardStatus.CONFIRMED -> str(ReservationKeys.statusConfirmed)
        ReservationCardStatus.SEATED -> str(ReservationKeys.statusSeated)
        ReservationCardStatus.COMPLETED -> str(ReservationKeys.statusCompleted)
        ReservationCardStatus.CANCELLED -> str(ReservationKeys.statusCancelled)
        ReservationCardStatus.NO_SHOW -> str(ReservationKeys.statusNoShow)
    }

@Composable
fun OrderItemStatusChip(
    status: OrderItemCardStatus,
    modifier: Modifier = Modifier,
) {
    StatusChip(
        text = status.label,
        statusColor = orderItemStatusColor(status),
        modifier = modifier,
    )
}

@Composable
fun StatusIcon(
    status: ReservationCardStatus,
    modifier: Modifier = Modifier,
) {
    val (icon, color) =
        when (status) {
            ReservationCardStatus.NO_SHOW -> Icons.Default.Warning to PosTheme.colors.posError
            ReservationCardStatus.SEATED -> Icons.Default.Check to PosTheme.colors.posAvailable
            ReservationCardStatus.CONFIRMED -> Icons.Default.Schedule to MaterialTheme.colorScheme.primary
            else -> Icons.Default.Schedule to MaterialTheme.colorScheme.outline
        }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

enum class TableCardStatus(
    val label: String,
) {
    FREE("Free"),
    OCCUPIED("Occupied"),
    RESERVED("Reserved"),
    BILLING("Billing"),
    INACTIVE("Inactive"),
}

/**
 * Display text comes from [ReservationKeys] via [reservationStatusLabel] — deliberately NOT a
 * `label` constructor property, so the chip cannot drift back to an untranslated English literal.
 */
enum class ReservationCardStatus {
    CONFIRMED,
    SEATED,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
}

enum class OrderItemCardStatus(
    val label: String,
) {
    PAID("Paid"),
    OPEN("Open"),
    CANCELLED("Cancelled"),
}

enum class VoucherCardStatus(
    val label: String,
) {
    ACTIVE("Active"),
    PARTIALLY_REDEEMED("Partially Redeemed"),
    FULLY_REDEEMED("Fully Redeemed"),
    EXPIRED("Expired"),
    INVALIDATED("Invalidated"),
}
