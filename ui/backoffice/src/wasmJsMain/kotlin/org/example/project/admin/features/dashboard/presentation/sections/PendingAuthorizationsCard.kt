package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatAdminInstant
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.approvalOperationLabel
import org.example.project.core.presentation.strings.keys.ApprovalKeys
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosColors
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun PendingAuthorizationsCard(
    refreshTick: Int,
    modifier: Modifier = Modifier,
    viewModel: PendingAuthorizationsViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(AdminStringKeys.DashboardAuthRequestsTitle)
    val language = LocalAppLanguage.current

    val settled = rememberSectionSettled(state.isLoading, state.failed)
    if (state.isLoading && !settled) return
    if (state.failed) {
        SectionUnavailable(title = title, modifier = modifier)
        return
    }
    if (state.isEmpty) return

    val errorColor = LocalPosColors.current.posError
    PosCard(
        modifier = modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        accentColor = MaterialTheme.colorScheme.error,
    ) {
        body {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = errorColor)
                    CountBadge(state.requests.size)
                }
                state.requests.forEach { request ->
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    AuthorizationRow(
                        text = approvalOperationLabel(request.operation, language),
                        detail =
                            "${request.requestingEmployeeNumber} · ${request.terminalName} · " +
                                formatAdminInstant(request.timestamp),
                        onApprove = { viewModel.approve(request.requestId) },
                        onDeny = { viewModel.startDeny(request.requestId) },
                    )
                }
            }
        }
    }

    val denyingId = state.denyingRequestId
    if (denyingId != null) {
        var reason by remember(denyingId) { mutableStateOf("") }
        val blankReasonText = str(NotificationKeys.noReasonProvided)
        PosDialog(
            onDismiss = viewModel::cancelDeny,
            title = str(ApprovalKeys.approvalDenyReason),
            kind = PosDialogKind.Form,
            primary =
                PosDialogAction(
                    text = str(ApprovalKeys.approvalDeny),
                    onClick = { viewModel.deny(denyingId, reason, blankReasonText) },
                ),
            destructive = true,
        ) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(str(ApprovalKeys.approvalDenyReasonHint)) },
                singleLine = true,
            )
        }
    }
}

/** Round error-coloured count of pending requests. */
@Composable
private fun CountBadge(count: Int) {
    Box(
        Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * One request: text left and the two buttons right while the row is wide enough, otherwise the text
 * with a full-width 50/50 pair under it.
 */
@Composable
private fun AuthorizationRow(
    text: String,
    detail: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val wide = maxWidth >= AuthorizationRowWideMinWidth
        val texts =
            @Composable { modifier: Modifier ->
                Column(modifier) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                texts(Modifier.weight(1f))
                PosSecondaryButton(text = str(ApprovalKeys.approvalDeny), onClick = onDeny)
                PosButton(text = str(ApprovalKeys.approvalApprove), onClick = onApprove)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                texts(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosSecondaryButton(text = str(ApprovalKeys.approvalDeny), onClick = onDeny, modifier = Modifier.weight(1f))
                    PosButton(text = str(ApprovalKeys.approvalApprove), onClick = onApprove, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val AuthorizationRowWideMinWidth = 420.dp
