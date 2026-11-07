package org.example.project.features.auth.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.presentation.strings.approvalOperationLabel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ApprovalKeys
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Full-screen dialog for managers to approve or deny an incoming approval request.
 *
 * Shows employee info, operation details, countdown timer, and approve/deny actions.
 * Uses PosDialog as the base container.
 */
@Composable
fun ApprovalRequestDialog(
    request: AuthDto.ApprovalRequestDto,
    isLoading: Boolean,
    onApprove: (String) -> Unit,
    onDeny: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val languageManager = LocalLanguageManager.current
    val language by languageManager.currentLanguage

    var showDenyDialog by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }

    // Mirrors AuthNotificationManager's TTL check server-side: the timestamp is normally an ISO
    // instant, but a bare epoch-millis string is accepted too for backward compatibility.
    val createdAtMs =
        remember(request.requestId) {
            try {
                Instant.parse(request.timestamp).toEpochMilliseconds()
            } catch (e: Exception) {
                request.timestamp.toLongOrNull() ?: Clock.System.now().toEpochMilliseconds()
            }
        }
    var remainingSeconds by remember(request.requestId) {
        val elapsed = (Clock.System.now().toEpochMilliseconds() - createdAtMs) / 1000
        mutableIntStateOf((180 - elapsed).toInt().coerceAtLeast(0))
    }

    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        onDismiss()
    }

    val operationLabel = approvalOperationLabel(request.operation, language)

    // Map detail keys to localized labels
    val detailKeyLabels =
        mapOf(
            "items" to getString(ApprovalKeys.detailItems, language),
            "itemCount" to getString(ApprovalKeys.detailItemCount, language),
            "reason" to getString(ApprovalKeys.detailReason, language),
            "targetTable" to getString(ApprovalKeys.detailTargetTable, language),
            "belegDatum" to getString(ApprovalKeys.detailBelegDatum, language),
            "belegtext" to getString(ApprovalKeys.detailBelegtext, language),
            "amount" to getString(ApprovalKeys.detailAmount, language),
        )

    if (showDenyDialog) {
        PosDialog(
            onDismiss = { showDenyDialog = false },
            title = getString(ApprovalKeys.approvalDenyReason, language),
            footer = {
                PosSecondaryButton(
                    text = getString(ApprovalKeys.approvalDeny, language),
                    onClick = {
                        onDeny(request.requestId, denyReason.ifBlank { getString(NotificationKeys.noReasonProvided, language) })
                        showDenyDialog = false
                    },
                )
            },
        ) {
            PosTextField(
                value = denyReason,
                onValueChange = { denyReason = it },
                label = getString(ApprovalKeys.approvalDenyReasonHint, language),
                singleLine = false,
            )
        }
    }

    PosDialog(
        onDismiss = onDismiss,
        title = getString(ApprovalKeys.approvalRequestTitle, language),
        titleIcon = Icons.Default.GppGood,
        maxWidth = 440.dp,
        dismissOnClickOutside = false,
        footer = {
            PosSecondaryButton(
                text = getString(ApprovalKeys.approvalDeny, language),
                onClick = { showDenyDialog = true },
                enabled = !isLoading,
            )
            Spacer(modifier = Modifier.width(12.dp))
            PosButton(
                text = getString(ApprovalKeys.approvalApprove, language),
                onClick = { onApprove(request.requestId) },
                isLoading = isLoading,
            )
        },
    ) {
        // Employee number (prefer display number over raw UUID)
        val employeeDisplay = request.requestingEmployeeNumber.ifBlank { request.requestingEmployeeId }
        InfoRow(
            icon = Icons.Default.Person,
            label = getString(ApprovalKeys.approvalRequestFrom, language, employeeDisplay),
        )

        Spacer(modifier = Modifier.height(8.dp))

        InfoRow(
            icon = Icons.Default.GppGood,
            label = getString(ApprovalKeys.approvalRequestOperation, language, operationLabel),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Terminal name (prefer resolved name over raw deviceId)
        val deviceDisplay = request.terminalName.ifBlank { request.deviceId }
        InfoRow(
            icon = Icons.Default.PhoneAndroid,
            label = getString(ApprovalKeys.approvalRequestDevice, language, deviceDisplay),
        )

        Spacer(modifier = Modifier.height(8.dp))

        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeText = "$minutes:${seconds.toString().padStart(2, '0')}"
        val timerColor = if (remainingSeconds < 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

        InfoRow(
            icon = Icons.Default.Timer,
            label = getString(ApprovalKeys.approvalRequestTimeRemaining, language, timeText),
            labelColor = timerColor,
        )

        // Details section with localized labels
        val displayDetails = request.details.filter { (_, value) -> value.isNotBlank() }
        if (displayDetails.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            displayDetails.forEach { (key, value) ->
                val label = detailKeyLabels[key] ?: key
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = labelColor,
        )
    }
}
