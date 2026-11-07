package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel

/**
 * Asks for the reason before a Beleg's invoice is cancelled by a correction (T9-19) — the reason is
 * mandatory and becomes the correction's booking text; a press before [canConfirm] marks the field
 * instead of confirming (the button is never greyed out for it, UI-E2-06).
 */

/** Focus key of the reason field. */
private const val REASON_FIELD = "reason"

@Composable
fun CancelInvoiceDialog(
    filename: String,
    reason: String,
    reasonTooLong: Boolean,
    canConfirm: Boolean,
    isCancelling: Boolean,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Validate on press (UI-E2-06): Confirm is never greyed out for a missing reason.
    var attempt by remember { mutableStateOf(0) }
    val reasonError =
        when {
            reasonTooLong -> str(AdminStringKeys.DocumentCancelReasonTooLong)
            attempt > 0 && reason.isBlank() -> str(CommonKeys.fieldRequired)
            else -> null
        }
    val focus = rememberPosFormFocus<String>()
    LaunchedEffect(attempt) { if (attempt > 0) focus.revealFirst(listOf(REASON_FIELD), mapOf(REASON_FIELD to true)) }
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.DocumentCancelDialogTitle),
        dismissOnClickOutside = !isCancelling,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.DocumentCancelConfirm),
                onClick = { if (canConfirm) onConfirm() else attempt++ },
                isLoading = isCancelling,
            ),
        destructive = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(AdminStringKeys.DocumentCancelDialogBody, filename), style = MaterialTheme.typography.bodyMedium)
            PosTextField(
                value = reason,
                onValueChange = onReasonChange,
                label = requiredLabel(str(AdminStringKeys.DocumentCancelReason)),
                enabled = !isCancelling,
                isError = reasonError != null,
                errorMessage = reasonError,
                // The running count against the limit, as in the design (the server refuses a longer reason too).
                supportingText = {
                    Text(
                        str(
                            AdminStringKeys.BelegflowCounter,
                            reason.trim().length,
                            DocumentDto.CancelInvoiceRequestDto.MAX_REASON_LENGTH,
                        ),
                    )
                },
                singleLine = false,
                minLines = 3,
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(REASON_FIELD) },
            )
        }
    }
}
