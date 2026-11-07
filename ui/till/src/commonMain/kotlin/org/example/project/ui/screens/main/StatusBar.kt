package org.example.project.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.state.TseConnectionState
import org.example.project.ui.theme.PosTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
internal fun StatusBar(
    employeeNumber: String?,
    terminalId: String?,
    connectionState: ConnectionState,
    tseConnectionState: TseConnectionState,
    language: Language,
    compact: Boolean = false,
) {
    // Live clock (date + time; compact shows the date only, so the tick is irrelevant there)
    var stamp by remember { mutableStateOf("") }
    LaunchedEffect(compact) {
        while (true) {
            stamp = StatusBarFormat.stamp(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()), compact)
            delay(1.seconds)
        }
    }

    val semantic = PosTheme.colors
    val statusColor =
        when {
            connectionState == ConnectionState.Connected -> semantic.posAvailable
            connectionState == ConnectionState.Connecting -> semantic.posOccupied
            else -> semantic.posError
        }

    val statusText =
        when {
            connectionState == ConnectionState.Connected -> getString(MainScreenKeys.connected, language)
            connectionState == ConnectionState.Connecting -> getString(MainScreenKeys.connecting, language)
            else -> getString(MainScreenKeys.disconnected, language)
        }

    val tseColor =
        when (tseConnectionState) {
            TseConnectionState.Connected -> semantic.posAvailable
            TseConnectionState.Disconnected -> semantic.posError
            TseConnectionState.Disabled -> semantic.posOccupied
            TseConnectionState.Simulation -> semantic.posOccupied
            TseConnectionState.Unknown -> MaterialTheme.colorScheme.outline
            TseConnectionState.Maintenance -> semantic.posReserved
            TseConnectionState.Degraded -> semantic.posError
            TseConnectionState.NormalOffline -> semantic.posOccupied
        }

    val tseText =
        when (tseConnectionState) {
            TseConnectionState.Connected -> getString(MainScreenKeys.tseConnected, language)
            TseConnectionState.Disconnected -> getString(MainScreenKeys.tseDisconnected, language)
            TseConnectionState.Disabled -> getString(MainScreenKeys.tseDisabled, language)
            TseConnectionState.Simulation -> getString(MainScreenKeys.tseSimulation, language)
            TseConnectionState.Unknown -> getString(MainScreenKeys.tseUnknown, language)
            TseConnectionState.Maintenance -> getString(MainScreenKeys.tseMaintenance, language)
            TseConnectionState.Degraded -> getString(MainScreenKeys.tseDegraded, language)
            TseConnectionState.NormalOffline -> getString(MainScreenKeys.tseNormalOffline, language)
        }

    val separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                // Employee number
                if (employeeNumber != null) {
                    Text(
                        text = "#$employeeNumber",
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = separatorColor)
                }

                // Terminal ID (full bar only — a phone has no room and the id is not needed there)
                if (terminalId != null && !compact) {
                    Text(
                        text = "${getString(MainScreenKeys.terminalLabel, language)}: $terminalId",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = separatorColor)
                }

                // Connection status: dot + text (compact: short label)
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(color = statusColor, shape = CircleShape),
                )
                Text(
                    text = if (compact) getString(MainScreenKeys.serverShort, language) else statusText,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                )

                // TSE status: dot + text (compact: short label)
                Text("·", style = MaterialTheme.typography.labelSmall, color = separatorColor)
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(color = tseColor, shape = CircleShape),
                )
                Text(
                    text = if (compact) "TSE" else tseText,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                )
            }

            // Clock: measured before the left group (which gives way), so a big font never cuts the date
            Text(
                text = stamp,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}
