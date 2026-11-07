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
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.hr.HrProfileState
import org.example.project.admin.features.staff.presentation.hr.HrProfileViewModel
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatHours
import org.example.project.core.utils.hoursToMinutesOrNull
import org.example.project.core.utils.orDash
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.employee.InfoRow
import org.example.project.ui.components.employee.OverviewSectionCard
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The full HR record for the selected employee. Self-contained: owns its VM, keyed to
 * [employeeId] — the same shape as [ScheduleSection] and [TimeEntriesSection].
 */
@Composable
fun HrProfileSection(
    employeeId: String,
    canManage: Boolean,
    employeeName: String,
    modifier: Modifier = Modifier,
    viewModel: HrProfileViewModel = koinViewModel(),
) {
    LaunchedEffect(employeeId) { viewModel.load(employeeId) }

    Column(modifier.fillMaxWidth().padding(PosSpacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(str(AdminStringKeys.StaffSectionHr), style = MaterialTheme.typography.titleMedium)
            if (canManage && viewModel.state is HrProfileState.Data) {
                PosSecondaryButton(text = str(AdminStringKeys.StaffHrEdit), onClick = viewModel::openEditor)
            }
        }

        when (val s = viewModel.state) {
            is HrProfileState.Loading -> CircularProgressIndicator()
            is HrProfileState.Error ->
                Text(
                    str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                )
            is HrProfileState.Data -> {
                PersonalCard(s.info)
                AddressCard(s.info)
                EmploymentCard(s.info)
            }
        }
    }

    viewModel.editor?.let { e ->
        HrProfileDialog(
            editor = e,
            employeeName = employeeName,
            onField = viewModel::onEditorField,
            onSave = viewModel::save,
            onCancel = viewModel::closeEditor,
            dirty = viewModel.editorDirty,
        )
    }
}

/** The DTO carries no currency; EUR matches the existing adminApp precedent for currency-less DTOs. */
private const val CURRENCY = "EUR"

@Composable
private fun PersonalCard(info: EmployeeInfoDto.EmployeeInfoResponseDto?) {
    val language = LocalAppLanguage.current
    OverviewSectionCard(title = str(EmployeeHubKeys.personalInformation)) {
        if (info == null) {
            Text(
                str(EmployeeHubKeys.completeProfilePersonal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@OverviewSectionCard
        }
        InfoRow(str(EmployeeHubKeys.firstName), info.firstName)
        InfoRow(str(EmployeeHubKeys.lastName), info.lastName)
        InfoRow(str(EmployeeHubKeys.email), info.email ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.phone), info.phoneNumber ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.dateOfBirth), info.dateOfBirth.asDisplayDay(language).orDash())
    }
}

@Composable
private fun AddressCard(info: EmployeeInfoDto.EmployeeInfoResponseDto?) {
    OverviewSectionCard(title = str(EmployeeHubKeys.address)) {
        if (info == null) {
            Text(
                str(EmployeeHubKeys.completeProfileAddress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@OverviewSectionCard
        }
        InfoRow(str(EmployeeHubKeys.street), info.street ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.city), info.city ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.postalCode), info.zipCode ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.state), info.state ?: EMPTY_VALUE)
        InfoRow(str(EmployeeHubKeys.country), info.country ?: EMPTY_VALUE)
    }
}

@Composable
private fun EmploymentCard(info: EmployeeInfoDto.EmployeeInfoResponseDto?) {
    val language = LocalAppLanguage.current
    OverviewSectionCard(title = str(EmployeeHubKeys.employment)) {
        if (info == null) {
            Text(
                str(EmployeeHubKeys.completeProfileEmployment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            InfoRow(str(EmployeeHubKeys.jobTitle), info.jobTitle ?: EMPTY_VALUE)
            InfoRow(str(EmployeeHubKeys.hireDate), info.hireDate.asDisplayDay(language).orDash())
            MoneyInfoRow(str(EmployeeHubKeys.hourlyRate), info.hourlyRate)
            MoneyInfoRow(str(EmployeeHubKeys.salary), info.salary)
            // Contracted hours is a QUANTITY, not money — never PosMoneyText, which would print €.
            InfoRow(
                str(EmployeeHubKeys.contractedHours),
                info.contractedHours?.hoursToMinutesOrNull()?.let { minutes ->
                    "${formatHours(minutes, language)} ${periodLabel(info.contractedHoursPeriod ?: ContractedHoursPeriod.WEEKLY)}"
                } ?: EMPTY_VALUE,
            )
        }
    }
}

@Composable
private fun MoneyInfoRow(
    label: String,
    amount: String?,
) {
    if (amount == null) {
        InfoRow(label, EMPTY_VALUE)
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PosMoneyText(amount = amount, currencyCode = CURRENCY, style = MaterialTheme.typography.bodyLarge)
    }
}
