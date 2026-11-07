package org.example.project.ui.screens.reservation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableBar
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.StatusChip
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosReservationDetailPanel(
    reservation: ReservationDto.ResponseDto?,
    isManager: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStatusChange: (ReservationStatus) -> Unit,
    onOpenAssignment: () -> Unit,
    onAutoAssign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reservation == null) {
        // Empty state
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.EventBusy,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = str(ReservationKeys.noReservationsForDate),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    val statusColor =
        when (reservation.status) {
            ReservationStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
            ReservationStatus.SEATED -> PosTheme.colors.posAvailable
            ReservationStatus.COMPLETED -> MaterialTheme.colorScheme.outline
            ReservationStatus.CANCELLED -> MaterialTheme.colorScheme.outline
            ReservationStatus.NO_SHOW -> MaterialTheme.colorScheme.error
        }

    val statusLabel =
        when (reservation.status) {
            ReservationStatus.CONFIRMED -> str(ReservationKeys.statusConfirmed)
            ReservationStatus.SEATED -> str(ReservationKeys.statusSeated)
            ReservationStatus.COMPLETED -> str(ReservationKeys.statusCompleted)
            ReservationStatus.CANCELLED -> str(ReservationKeys.statusCancelled)
            ReservationStatus.NO_SHOW -> str(ReservationKeys.statusNoShow)
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: Name + Edit/Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = reservation.guestName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PosTextButton(
                    text = str(CommonKeys.edit),
                    icon = Icons.Default.Edit,
                    onClick = onEdit,
                )
                if (isManager) {
                    PosTextButton(
                        text = str(CommonKeys.delete),
                        icon = Icons.Default.Delete,
                        onClick = onDelete,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    )
                }
            }
        }

        // Status chip
        StatusChip(
            text = statusLabel,
            statusColor = statusColor,
        )

        HorizontalDivider()

        // Info section: Time, Duration, Party Size, Phone, Created By
        Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            DetailInfoRow(
                icon = Icons.Default.Schedule,
                label = "${reservation.reservationDate} ${reservation.reservationTime.take(5)}",
            )
            reservation.durationMinutes?.let { duration ->
                DetailInfoRow(
                    icon = Icons.Default.Schedule,
                    label = str(ReservationKeys.durationMinutes, duration),
                )
            }
            DetailInfoRow(
                icon = Icons.Default.People,
                label = "${str(ReservationKeys.partySize)}: ${reservation.partySize}",
            )
            reservation.guestPhone?.let { phone ->
                if (phone.isNotBlank()) {
                    DetailInfoRow(
                        icon = Icons.Default.Phone,
                        label = phone,
                    )
                }
            }
            reservation.createdByName?.let { createdBy ->
                DetailInfoRow(
                    icon = Icons.Default.Person,
                    label = createdBy,
                )
            }
        }

        // Notes section
        reservation.notes?.let { notes ->
            if (notes.isNotBlank()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = str(CommonKeys.notes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // Table Assignments section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(ReservationKeys.assignTables),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosTextButton(
                        text = str(ReservationKeys.autoAssign),
                        icon = Icons.Default.AutoAwesome,
                        onClick = onAutoAssign,
                    )
                    PosTextButton(
                        text = str(ReservationKeys.manualAssign),
                        icon = Icons.Default.TableBar,
                        onClick = onOpenAssignment,
                    )
                }
            }

            if (reservation.tableAssignments.isEmpty()) {
                Text(
                    text = str(ReservationKeys.noTablesAssigned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val grouped = reservation.tableAssignments.groupBy { it.groupNumber }
                grouped.forEach { (groupNum, tables) ->
                    val groupSeats = tables.sumOf { it.seats }
                    Text(
                        text = "${str(
                            ReservationKeys.group,
                            groupNum.toString(),
                        )} — ${str(ReservationKeys.seatsOf, groupSeats.toString(), reservation.partySize.toString())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tables.forEach { assignment ->
                            AssistChip(
                                onClick = onOpenAssignment,
                                label = {
                                    Text(
                                        "${assignment.tableNumber} (${assignment.seats})",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.height(28.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Actions section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = str(CommonKeys.actions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reservation.status == ReservationStatus.CONFIRMED) {
                    PosButton(
                        text = str(ReservationKeys.markSeated),
                        onClick = { onStatusChange(ReservationStatus.SEATED) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (reservation.status == ReservationStatus.CONFIRMED) {
                    PosSecondaryButton(
                        text = str(ReservationKeys.markNoShow),
                        onClick = { onStatusChange(ReservationStatus.NO_SHOW) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (reservation.status == ReservationStatus.SEATED) {
                    PosButton(
                        text = str(ReservationKeys.markCompleted),
                        onClick = { onStatusChange(ReservationStatus.COMPLETED) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (reservation.status == ReservationStatus.CONFIRMED ||
                    reservation.status == ReservationStatus.SEATED
                ) {
                    PosSecondaryButton(
                        text = str(CommonKeys.cancel),
                        onClick = { onStatusChange(ReservationStatus.CANCELLED) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
