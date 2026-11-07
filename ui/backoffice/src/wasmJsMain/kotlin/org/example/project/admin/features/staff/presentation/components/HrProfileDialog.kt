package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.hr.HrEditorError
import org.example.project.admin.features.staff.presentation.hr.HrField
import org.example.project.admin.features.staff.presentation.hr.HrProfileEditor
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * The 16-field HR editor.
 *
 * PosDialog self-scrolls, so this content lambda must NEVER add a verticalScroll of its own —
 * that measures at infinite height and crashes on wasm.
 */
@Composable
fun HrProfileDialog(
    editor: HrProfileEditor,
    employeeName: String,
    onField: ((HrProfileEditor) -> HrProfileEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** The form differs from how it was opened; closing then asks before discarding. */
    dirty: Boolean = false,
) {
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onCancel)
    // Validate on Save: every field at fault is marked; the first is scrolled to and focused.
    val focus = rememberPosFormFocus<HrField>()
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(HrField.entries, editor.fieldErrors)
    }
    val hint = str(CommonKeys.dateFormatHint)

    @Composable
    fun field(
        key: HrField,
        value: String,
        label: String,
        onChange: (String) -> Unit,
    ) {
        val error = editor.fieldErrors[key]
        PosTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(key) },
            isError = error != null,
            supportingText = error?.let { message -> { Text(str(message)) } },
        )
    }

    @Composable
    fun dateField(
        key: HrField,
        value: String,
        label: String,
        onChange: (String) -> Unit,
    ) {
        val error = editor.fieldErrors[key]
        PosDateField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(key) },
            isError = error != null,
            supportingText = error?.let { message -> str(message, hint) },
        )
    }

    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.StaffHrEditNamedTitle, employeeName),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editor.error?.let { err ->
                Text(
                    text = errorText(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Only on edit: on create there is no stored value to preserve, so the caveat
            // would be noise. Server-side, EmployeeInfoRepositoryImpl merges with
            // `updateData.field?.let { field = it }` — null means "leave unchanged", not "clear".
            if (!editor.isCreate) {
                Text(
                    text = str(AdminStringKeys.StaffHrClearHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel(str(EmployeeHubKeys.personalInformation))
            field(HrField.FIRST_NAME, editor.firstName, requiredLabel(str(EmployeeHubKeys.firstName))) { v -> onField { it.copy(firstName = v) } }
            field(HrField.LAST_NAME, editor.lastName, requiredLabel(str(EmployeeHubKeys.lastName))) { v -> onField { it.copy(lastName = v) } }
            field(HrField.EMAIL, editor.email, str(EmployeeHubKeys.email)) { v -> onField { it.copy(email = v) } }
            PosTextField(editor.phoneNumber, { v -> onField { it.copy(phoneNumber = v) } }, str(EmployeeHubKeys.phone))
            dateField(HrField.DATE_OF_BIRTH, editor.dateOfBirth, str(EmployeeHubKeys.dateOfBirth)) { v -> onField { it.copy(dateOfBirth = v) } }

            SectionLabel(str(EmployeeHubKeys.address))
            PosTextField(editor.street, { v -> onField { it.copy(street = v) } }, str(EmployeeHubKeys.street))
            PosTextField(editor.city, { v -> onField { it.copy(city = v) } }, str(EmployeeHubKeys.city))
            PosTextField(editor.zipCode, { v -> onField { it.copy(zipCode = v) } }, str(EmployeeHubKeys.postalCode))
            PosTextField(editor.state, { v -> onField { it.copy(state = v) } }, str(EmployeeHubKeys.state))
            PosTextField(editor.country, { v -> onField { it.copy(country = v) } }, str(EmployeeHubKeys.country))

            SectionLabel(str(EmployeeHubKeys.employment))
            PosTextField(editor.jobTitle, { v -> onField { it.copy(jobTitle = v) } }, str(EmployeeHubKeys.jobTitle))
            dateField(HrField.HIRE_DATE, editor.hireDate, str(EmployeeHubKeys.hireDate)) { v -> onField { it.copy(hireDate = v) } }
            field(HrField.HOURLY_RATE, editor.hourlyRate, str(EmployeeHubKeys.hourlyRate)) { v -> onField { it.copy(hourlyRate = v) } }
            field(HrField.SALARY, editor.salary, str(EmployeeHubKeys.salary)) { v -> onField { it.copy(salary = v) } }
            field(HrField.CONTRACTED_HOURS, editor.contractedHours, str(EmployeeHubKeys.contractedHours)) { v ->
                onField { it.copy(contractedHours = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContractedHoursPeriod.entries.forEach { period ->
                    // raw Material 3 by design: no Pos* segmented/filter chip template exists yet
                    FilterChip(
                        selected = editor.contractedHoursPeriod == period,
                        onClick = { onField { it.copy(contractedHoursPeriod = period) } },
                        label = { Text(periodLabel(period)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun errorText(err: HrEditorError): String =
    when (err) {
        HrEditorError.SAVE_FAILED -> str(AdminStringKeys.ProductErrorSaveFailed)
    }

@Composable
internal fun periodLabel(period: ContractedHoursPeriod): String =
    when (period) {
        ContractedHoursPeriod.WEEKLY -> str(AdminStringKeys.StaffHrPeriodWeekly)
        ContractedHoursPeriod.MONTHLY -> str(AdminStringKeys.StaffHrPeriodMonthly)
    }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
