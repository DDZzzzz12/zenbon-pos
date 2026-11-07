package org.example.project.admin.features.kassenbuch.presentation.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.SigningTerminalPicker
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField

/**
 * Which till's TSE client signs the Ladenkasse's cash movements when a closing is triggered from a
 * device that is not itself a till. Rendered only for the fiscal book and only when the picker is
 * visible (the caller holds `TERMINAL_MANAGE`); the choice is saved explicitly, never on pick, and
 * by its own button: it persists immediately and independently of the book form around it, which
 * the help text and a success toast both say.
 */
@Composable
fun SigningTerminalSection(
    picker: SigningTerminalPicker,
    onSelect: (Long?) -> Unit,
    onSave: () -> Unit,
) {
    if (!picker.visible) return
    var expanded by remember { mutableStateOf(false) }
    val unsetLabel = str(AdminStringKeys.CashBookSigningTerminalUnset)
    val enabled = !picker.isSaving && picker.candidates.isNotEmpty()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = str(AdminStringKeys.CashBookSigningTerminalLabel),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Box {
            PosPickerField(
                value = picker.selectedName ?: unsetLabel,
                onClick = { if (enabled) expanded = true },
                label = str(AdminStringKeys.CashBookSigningTerminalLabel),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                PosDropdownItem(
                    text = unsetLabel,
                    selected = picker.selectedId == null,
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                picker.candidates.forEach { candidate ->
                    PosDropdownItem(
                        text = candidate.name,
                        selected = candidate.terminalId == picker.selectedId,
                        onClick = {
                            onSelect(candidate.terminalId)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(
            text = str(AdminStringKeys.CashBookSigningTerminalHelp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (picker.isProposal) {
            Text(
                text = str(AdminStringKeys.CashBookSigningTerminalProposal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (picker.candidates.isEmpty()) {
            Text(
                text = str(AdminStringKeys.CashBookSigningTerminalNone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PosButton(
            text = str(AdminStringKeys.CashBookSigningTerminalSave),
            onClick = onSave,
            enabled = picker.hasUnsavedChange && !picker.isSaving,
            isLoading = picker.isSaving,
        )
    }
}
