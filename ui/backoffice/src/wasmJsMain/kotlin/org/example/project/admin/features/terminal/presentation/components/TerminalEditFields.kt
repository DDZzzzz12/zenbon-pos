package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalEditDraft
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField

/**
 * The edit form: Terminal-Name (optional), Standort and the role picker, the hint that fiscal cannot
 * be changed (the field stays absent, exactly as in [TerminalEditDraft]) and the inline save error.
 * Shared by the side panel / drawer and the compact edit page, which differ only in their buttons.
 */
@Composable
internal fun TerminalEditFields(
    edit: TerminalEditDraft,
    fiscal: Boolean,
    onEditField: ((TerminalEditDraft) -> TerminalEditDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PosTextField(
            value = edit.name,
            onValueChange = { value -> onEditField { it.copy(name = value) } },
            label = str(AdminStringKeys.TerminalNameLabel),
            enabled = !edit.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextField(
            value = edit.location,
            onValueChange = { value -> onEditField { it.copy(location = value) } },
            label = str(AdminStringKeys.TerminalLocationLabel),
            enabled = !edit.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        RolePicker(
            selected = edit.role,
            enabled = !edit.saving,
            onSelect = { role -> onEditField { it.copy(role = role) } },
        )
        Text(
            text = str(AdminStringKeys.TerminalFiscalFixedHint, str(if (fiscal) AdminStringKeys.CommonYes else AdminStringKeys.CommonNo)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        edit.error?.let { error ->
            Text(
                text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RolePicker(
    selected: TerminalRole,
    enabled: Boolean,
    onSelect: (TerminalRole) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        PosPickerField(
            value = str(roleKey(selected.name)),
            onClick = { if (enabled) expanded = true },
            label = str(AdminStringKeys.TerminalRoleLabel),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            TerminalRole.entries.forEach { role ->
                PosDropdownItem(
                    text = str(roleKey(role.name)),
                    selected = role == selected,
                    onClick = {
                        expanded = false
                        onSelect(role)
                    },
                    minHeight = adminTouchSize(48.dp),
                )
            }
        }
    }
}
