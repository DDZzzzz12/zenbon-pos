package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.schedule.DayEditor
import org.example.project.admin.features.staff.presentation.schedule.ShiftDraft
import org.example.project.admin.features.staff.presentation.schedule.ShiftField
import org.example.project.admin.features.staff.presentation.schedule.ShiftPart
import org.example.project.admin.features.staff.presentation.schedule.shiftFieldOrder
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/** Edit dialog for a single day's shifts — add/remove/adjust rows, save via [onSave]. */
@Composable
fun ScheduleDayDialog(
    editor: DayEditor,
    employeeName: String,
    /** Full weekday name ("Montag"). */
    dayLabel: String,
    onAddShift: () -> Unit,
    onRemoveShift: (Int) -> Unit,
    onShiftField: (Int, (ShiftDraft) -> ShiftDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** The shifts differ from how the dialog was opened; closing then asks before discarding. */
    dirty: Boolean = false,
) {
    val focus = rememberPosFormFocus<ShiftField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: ShiftField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(shiftFieldOrder(editor.shifts.size), formErrors)
    }

    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.ScheduleDayDialogTitle, employeeName, dayLabel),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(AdminStringKeys.CommonSave), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editor.shifts.forEachIndexed { i, shift ->
                if (i > 0) HorizontalDivider()
                val start = ShiftField(i, ShiftPart.START)
                val end = ShiftField(i, ShiftPart.END)
                val breakField = ShiftField(i, ShiftPart.BREAK)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosTextField(
                            value = shift.startTime,
                            onValueChange = { v -> onShiftField(i) { it.copy(startTime = v) } },
                            label = requiredLabel(str(AdminStringKeys.ScheduleStart)),
                            modifier = Modifier.weight(1f).then(anchor(start)),
                            isError = start in formErrors,
                            supportingText = formErrors[start]?.let { msg -> { Text(msg) } },
                        )
                        PosTextField(
                            value = shift.endTime,
                            onValueChange = { v -> onShiftField(i) { it.copy(endTime = v) } },
                            label = requiredLabel(str(AdminStringKeys.ScheduleEnd)),
                            modifier = Modifier.weight(1f).then(anchor(end)),
                            isError = end in formErrors,
                            supportingText = formErrors[end]?.let { msg -> { Text(msg) } },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosTextField(
                            value = shift.breakDuration,
                            onValueChange = { v -> onShiftField(i) { it.copy(breakDuration = v.filter(Char::isDigit)) } },
                            label = str(AdminStringKeys.ScheduleBreak),
                            modifier = Modifier.weight(1f).then(anchor(breakField)),
                            keyboardType = KeyboardType.Number,
                            isError = breakField in formErrors,
                            supportingText = formErrors[breakField]?.let { msg -> { Text(msg) } },
                        )
                        PosTextButton(str(AdminStringKeys.ScheduleRemoveShift), onClick = { onRemoveShift(i) })
                    }
                }
            }

            PosSecondaryButton(str(AdminStringKeys.ScheduleAddShift), onClick = onAddShift)

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
