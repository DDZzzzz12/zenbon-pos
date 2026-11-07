package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.employee.InfoRow
import org.example.project.ui.components.employee.OverviewSectionCard
import org.example.project.ui.theme.PosSpacing

/**
 * The employee record that `PUT /api/employees/{id}` owns — role and the administrative fields —
 * with the one edit button that writes exactly this set.
 *
 * The permission GROUP is shown here too but is not part of that save: it has its own endpoint
 * requiring PERMISSION_ADMIN, so a manager who may edit staff records cannot hand out capabilities
 * through this form. The exception-permission chips this replaced were dead — Stage 2a retired
 * per-employee grants, and the server has reported an empty set ever since.
 *
 * Identity is NOT repeated here; [EmployeeDetailHeader] owns it. The edit button sits inside the
 * card rather than above it so it reads as belonging to these fields and no others.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmployeeProfileSection(
    employee: EmployeeDto.EmployeeResponseDto,
    canManage: Boolean,
    groups: List<PermissionGroupDto.PermissionGroupResponseDto>,
    canAssignGroup: Boolean,
    onEdit: () -> Unit,
    onAssignGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current

    Column(modifier.fillMaxWidth().padding(PosSpacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverviewSectionCard(title = str(AdminStringKeys.StaffSectionProfile)) {
            InfoRow(str(AdminStringKeys.StaffFieldRole), EmployeeRole.getRole(employee.role).getName(language))
            InfoRow(str(AdminStringKeys.StaffFieldNumber), employee.personnelNumber ?: EMPTY_VALUE)
            InfoRow(str(AdminStringKeys.StaffFieldStatus), employmentStatusLabel(employee.employmentStatus, language))
            InfoRow(str(AdminStringKeys.StaffFieldNotes), employee.notes ?: EMPTY_VALUE)
            PermissionGroupRow(employee, groups, canAssignGroup, onAssignGroup)
            if (canManage) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    PosSecondaryButton(text = str(AdminStringKeys.StaffEditTitle), onClick = onEdit)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionGroupRow(
    employee: EmployeeDto.EmployeeResponseDto,
    groups: List<PermissionGroupDto.PermissionGroupResponseDto>,
    canAssign: Boolean,
    onAssign: () -> Unit,
) {
    val code = employee.permissionGroupCode
    val group = groups.firstOrNull { it.code == code }
    // A seeded group's name is stored in English and stays editable through the API, so it is
    // translated by code; a group an operator created keeps the name it was given.
    val groupKey = code?.let { AdminStringKeys.permissionGroupName(it) }
    val groupLabel = groupKey?.let { str(it) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            str(AdminStringKeys.StaffFieldPermissionGroup),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                // No group resolves to NO capabilities: this employee can reach nothing at all. It
                // is a broken row rather than a choice, so it is flagged rather than shown as "—".
                code == null ->
                    PosStatusChip(
                        text = str(AdminStringKeys.StaffNoPermissionGroup),
                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                else ->
                    PosStatusChip(
                        text = groupLabel ?: group?.name ?: code,
                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
            }
            if (canAssign) {
                PosSecondaryButton(text = str(AdminStringKeys.StaffChangePermissionGroup), onClick = onAssign)
            }
        }
        // A seeded group's description is reset to its compiled English on every boot, so it is
        // translated by code, exactly as the permissions editor does (admin20 BO-21).
        val seededDescription = group?.takeIf { it.isSystem }?.let { AdminStringKeys.permissionGroupDescription(it.code) }
        (seededDescription?.let { str(it) } ?: group?.description)?.let { description ->
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The employment status as the server spelled it, localized.
 *
 * The DTO carries a raw `String` rather than the [EmploymentStatus] enum, so a value this build does
 * not know — an older or newer server, or a hand-edited row — must still render as itself. Falling
 * back to the raw text keeps an unrecognised status visible instead of throwing inside the
 * composition or collapsing it to a wrong-but-plausible one.
 */
private fun employmentStatusLabel(
    raw: String,
    language: Language,
): String {
    val normalized = raw.uppercase()
    return EmploymentStatus.entries.firstOrNull { it.name == normalized }?.getName(language) ?: raw
}
