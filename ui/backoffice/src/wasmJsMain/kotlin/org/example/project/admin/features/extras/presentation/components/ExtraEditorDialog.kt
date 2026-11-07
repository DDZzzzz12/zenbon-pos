package org.example.project.admin.features.extras.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.extras.presentation.ExtraEditor
import org.example.project.admin.features.extras.presentation.ExtraField
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ExtrasTabKeys
import org.example.project.core.presentation.strings.keys.ProductsTabKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

@Composable
fun ExtraEditorDialog(
    editor: ExtraEditor,
    onField: ((ExtraEditor) -> ExtraEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** True when the form holds unsaved edits: closing it then asks first. */
    dirty: Boolean = false,
    /** Required choice groups that cannot do without this extra: it may not be deactivated (NOTE-c21). */
    deactivationBlockedBy: List<String> = emptyList(),
) {
    val focus = rememberPosFormFocus<ExtraField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: ExtraField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(ExtraField.entries, formErrors)
    }

    val close = rememberGuardedDismiss(dirty = dirty, onClose = onCancel)
    PosDialog(
        onDismiss = close,
        title = str(if (editor.isCreate) ExtrasTabKeys.createExtraTitle else ExtrasTabKeys.editExtraTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor.fieldError == "save") {
                Text(
                    text = str(AdminStringKeys.ProductErrorSaveFailed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            editor.saveError?.let { err ->
                Text(text = err.displayMessage(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            PosTextField(
                editor.name,
                { v -> onField { it.copy(name = v) } },
                requiredLabel(str(ExtrasTabKeys.name)),
                modifier = anchor(ExtraField.NAME),
                isError = ExtraField.NAME in formErrors,
                supportingText = formErrors[ExtraField.NAME]?.let { msg -> { Text(msg) } },
            )
            PosTextField(editor.en, { v -> onField { it.copy(en = v) } }, str(ProductsTabKeys.englishName))
            PosTextField(editor.ch, { v -> onField { it.copy(ch = v) } }, str(ProductsTabKeys.chineseName))
            PosTextField(editor.description, { v ->
                onField {
                    it.copy(description = v)
                }
            }, str(ExtrasTabKeys.description), singleLine = false, minLines = 2)
            PosTextField(
                editor.price,
                { v -> onField { it.copy(price = v) } },
                str(ExtrasTabKeys.price),
                textFieldType = PosTextFieldType.Price,
                modifier = anchor(ExtraField.PRICE),
                isError = ExtraField.PRICE in formErrors,
                supportingText = formErrors[ExtraField.PRICE]?.let { msg -> { Text(msg) } },
            )
            // Deactivating hides the extra from its groups like a delete does, so the same guard
            // applies: the switch stays on while a required group needs the extra, and says why.
            val deactivationBlocked = editor.isActive && deactivationBlockedBy.isNotEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(ExtrasTabKeys.active), Modifier.weight(1f))
                Switch(editor.isActive, { v -> onField { it.copy(isActive = v) } }, enabled = !deactivationBlocked)
            }
            if (editor.fieldError == "required_group") {
                Text(
                    text = str(AdminStringKeys.ExtraNeededByRequiredGroups, deactivationBlockedBy.joinToString(", ")),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (deactivationBlocked) {
                Text(
                    text = str(AdminStringKeys.ExtraNeededByRequiredGroups, deactivationBlockedBy.joinToString(", ")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
