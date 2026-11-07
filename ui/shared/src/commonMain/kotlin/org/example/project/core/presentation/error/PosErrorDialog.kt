package org.example.project.core.presentation.error

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.example.project.core.error.FieldError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

/**
 * Material3 dialog for a [UiError]. Displays:
 *  - title + body (localized)
 *  - field errors (if any)
 *  - status / code / traceId (with copy button) for [UiError.FromServer]
 *  - retry callback if supplied
 *  - timestamp set when the dialog was first shown
 *
 * Pure composable — observation + queueing live in [PosErrorDialogHost].
 */
@Composable
fun PosErrorDialog(
    error: UiError,
    onDismiss: () -> Unit,
    timestampLabel: String,
    retry: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(error) { mutableStateOf(false) }

    val title = str(error.titleKey)
    val message = str(error.messageKey, *error.messageArgs.toTypedArray())

    val closeText = str(CommonKeys.errorDialogClose)
    val primary =
        if (retry != null) {
            PosDialogAction(
                text = str(CommonKeys.errorDialogRetry),
                onClick = {
                    retry()
                    onDismiss()
                },
            )
        } else {
            PosDialogAction(text = closeText, onClick = onDismiss)
        }
    PosDialog(
        onDismiss = onDismiss,
        title = title,
        kind = PosDialogKind.Confirm,
        primary = primary,
        secondary = if (retry != null) PosDialogAction(text = closeText, onClick = onDismiss) else null,
        showSecondary = retry != null,
    ) {
        Text(message)

        val fields = (error as? UiError.FromServer)?.fieldErrors.orEmpty()
        if (fields.isNotEmpty()) {
            Text(
                text = str(CommonKeys.errorDialogValidationErrorsLabel),
                style = MaterialTheme.typography.titleSmall,
            )
            fields.forEach { fe: FieldError ->
                Text(
                    text = "\u2022 ${fe.field}: ${fe.code}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val fromServer = error as? UiError.FromServer
        if (fromServer != null) {
            LabeledRow(str(CommonKeys.errorDialogStatusLabel), fromServer.httpStatus.toString())
            LabeledRow(
                label = str(CommonKeys.errorDialogCodeLabel),
                value = fromServer.code.name,
                valueMonospace = true,
            )
            val traceId = fromServer.traceId
            if (!traceId.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = str(CommonKeys.errorDialogTraceIdLabel),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = traceId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // raw Material 3 by design: multi-child content (Icon+Spacer+Text) not expressible via PosSecondaryButton params
                    androidx.compose.material3.OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(traceId))
                        copied = true
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (copied) {
                                str(CommonKeys.errorDialogCopiedToClipboard)
                            } else {
                                str(CommonKeys.errorDialogCopyTraceId)
                            },
                        )
                    }
                }
            }
            val rawDetail = fromServer.rawDetail
            if (!rawDetail.isNullOrBlank()) {
                Text(
                    text = str(CommonKeys.errorDialogTechnicalDetails),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = rawDetail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        LabeledRow(str(CommonKeys.errorDialogTimestampLabel), timestampLabel)
    }
}

@Composable
private fun LabeledRow(
    label: String,
    value: String,
    valueMonospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (valueMonospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}
