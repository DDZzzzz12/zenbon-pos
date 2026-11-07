package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.time.EntryEditor
import org.example.project.admin.features.staff.presentation.time.EntryMode
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField

/** Create/edit dialog for a single manual time entry — fields differ by [EntryEditor.mode] (create carries date+break, edit doesn't). */
@Composable
fun EntryDialog(
    editor: EntryEditor,
    onField: ((EntryEditor) -> EntryEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(if (editor.mode == EntryMode.CREATE) AdminStringKeys.TimeAddEntry else AdminStringKeys.TimeEditEntry),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(AdminStringKeys.CommonSave), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor.mode == EntryMode.CREATE) {
                PosDateField(
                    value = editor.date,
                    onValueChange = { v -> onField { it.copy(date = v, error = null) } },
                    label = str(AdminStringKeys.TimeFieldDate),
                )
            }
            PosTextField(
                value = editor.startTime,
                onValueChange = { v -> onField { it.copy(startTime = v, error = null) } },
                label = str(AdminStringKeys.TimeFieldStart),
            )
            PosTextField(
                value = editor.endTime,
                onValueChange = { v -> onField { it.copy(endTime = v, error = null) } },
                label = str(AdminStringKeys.TimeFieldEnd),
            )
            if (editor.mode == EntryMode.CREATE) {
                PosTextField(
                    value = editor.breakMinutes,
                    onValueChange = { v -> onField { it.copy(breakMinutes = v.filter(Char::isDigit)) } },
                    label = str(AdminStringKeys.TimeFieldBreak),
                    keyboardType = KeyboardType.Number,
                )
            }
            PosTextField(
                value = editor.comment,
                onValueChange = { v -> onField { it.copy(comment = v) } },
                label = str(AdminStringKeys.TimeFieldComment),
            )
            editor.error?.let { uiError ->
                Text(
                    str(uiError.messageKey, *uiError.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
