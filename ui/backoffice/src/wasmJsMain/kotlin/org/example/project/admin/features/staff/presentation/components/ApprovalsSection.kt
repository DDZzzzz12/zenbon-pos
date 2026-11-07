package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.approvals.ApprovalsState
import org.example.project.admin.features.staff.presentation.approvals.ApprovalsViewModel
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** Selected employee's pending monthly time-approval submissions; manager can approve or reject-with-reason. Self-contained: owns its VM keyed to [employeeId]. */
@Composable
fun ApprovalsSection(
    employeeId: String,
    canManage: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ApprovalsViewModel = koinViewModel(),
) {
    LaunchedEffect(employeeId) { viewModel.load(employeeId) }

    Column(modifier.fillMaxWidth().padding(PosSpacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(AdminStringKeys.StaffSectionApprovals), style = MaterialTheme.typography.titleMedium)

        when (val s = viewModel.state) {
            is ApprovalsState.Loading -> CircularProgressIndicator()
            is ApprovalsState.Error ->
                Text(
                    str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                )
            is ApprovalsState.Data -> {
                if (s.pending.isEmpty()) {
                    Text(str(AdminStringKeys.ApprovalsNone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    s.pending.forEach { p ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${p.periodYear}-${twoDigits(p.periodMonth)}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                str(AdminStringKeys.ApprovalsHours, p.totalWorkMinutes / 60, p.entryCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                str(AdminStringKeys.ApprovalsSubmitted, p.submittedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (canManage) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PosButton(str(AdminStringKeys.ApprovalsApprove), onClick = { viewModel.approve(p.id) })
                                    PosSecondaryButton(str(AdminStringKeys.ApprovalsReject), onClick = { viewModel.openReject(p.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewModel.rejectDraft?.let { d ->
        RejectDialog(
            draft = d,
            onReason = viewModel::onRejectReason,
            onConfirm = viewModel::confirmReject,
            onDismiss = viewModel::closeReject,
        )
    }
}

private fun twoDigits(n: Int) = if (n < 10) "0$n" else "$n"
