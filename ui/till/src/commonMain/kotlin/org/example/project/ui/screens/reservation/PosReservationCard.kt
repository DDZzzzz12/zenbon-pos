package org.example.project.ui.screens.reservation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.ReservationCardStatus
import org.example.project.ui.components.ReservationStatusChip
import org.example.project.ui.components.toPosCardStatus
import org.example.project.ui.theme.PosSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosReservationCard(
    reservation: ReservationDto.ResponseDto,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardStatus =
        when (reservation.status) {
            ReservationStatus.CONFIRMED -> ReservationCardStatus.CONFIRMED
            ReservationStatus.SEATED -> ReservationCardStatus.SEATED
            ReservationStatus.COMPLETED -> ReservationCardStatus.COMPLETED
            ReservationStatus.CANCELLED -> ReservationCardStatus.CANCELLED
            ReservationStatus.NO_SHOW -> ReservationCardStatus.NO_SHOW
        }

    PosCard(
        modifier = modifier,
        onClick = onClick,
        selected = isSelected,
        status = cardStatus.toPosCardStatus(),
    ) {
        header {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = reservation.reservationTime.take(5),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    PosAutoSizeText(
                        text = reservation.guestName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PosSpacing.xs),
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = str(ReservationKeys.partySize),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${reservation.partySize}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReservationStatusChip(status = cardStatus)
                }
            }
        }
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    reservation.durationMinutes?.let { duration ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = str(ReservationKeys.durationMinutes, duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    reservation.guestPhone?.let { phone ->
                        if (phone.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = phone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (reservation.tableAssignments.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = str(ReservationKeys.noTablesAssigned),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        reservation.tableAssignments.forEach { assignment ->
                            AssistChip(
                                onClick = {},
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
    }
}
