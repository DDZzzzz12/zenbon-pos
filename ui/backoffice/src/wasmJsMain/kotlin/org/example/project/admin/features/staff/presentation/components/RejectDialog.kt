package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.approvals.RejectDraft
import org.example.project.admin.features.staff.presentation.approvals.RejectField
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel

/** Reason-required reject dialog for a pending approval submission. */
@Composable
fun RejectDialog(
    draft: RejectDraft,
    onReason: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberPosFormFocus<RejectField>()
    val formErrors = draft.fieldErrors.entries.associate { (field, key) -> field to str(key) }
    LaunchedEffect(draft.validationAttempt) {
        if (draft.validationAttempt > 0) focus.revealFirst(RejectField.entries, formErrors)
    }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.ApprovalsRejectTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(AdminStringKeys.ApprovalsReject), onConfirm, isLoading = draft.saving),
        destructive = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PosTextField(
                value = draft.reason,
                onValueChange = onReason,
                label = requiredLabel(str(AdminStringKeys.ApprovalsRejectReason)),
                modifier = with(focus) { Modifier.formAnchor(RejectField.REASON) },
                isError = RejectField.REASON in formErrors,
                supportingText = formErrors[RejectField.REASON]?.let { msg -> { Text(msg) } },
            )
            draft.error?.let { uiError ->
                Text(
                    str(uiError.messageKey, *uiError.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
