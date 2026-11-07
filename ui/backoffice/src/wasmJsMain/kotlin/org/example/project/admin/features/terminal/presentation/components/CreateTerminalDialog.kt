package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.CreateTerminalDraft
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberGuardedDismiss

/** Localized label for a [TerminalRole]. */
private fun roleKey(role: TerminalRole): StringKey =
    when (role) {
        TerminalRole.CASHIER -> AdminStringKeys.TerminalRoleCashier
        TerminalRole.ORDER_STATION -> AdminStringKeys.TerminalRoleOrderStation
        TerminalRole.KITCHEN -> AdminStringKeys.TerminalRoleKitchen
        TerminalRole.CUSTOMER_DISPLAY -> AdminStringKeys.TerminalRoleCustomerDisplay
        TerminalRole.KIOSK -> AdminStringKeys.TerminalRoleKiosk
    }

@Composable
fun CreateTerminalDialog(
    draft: CreateTerminalDraft,
    onField: ((CreateTerminalDraft) -> CreateTerminalDraft) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rolePickerOpen by remember { mutableStateOf(false) }

    val close = rememberGuardedDismiss(dirty = draft.isDirty, onClose = onDismiss)

    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.TerminalCreateTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.TerminalCreate),
                onClick = onSubmit,
                enabled = !draft.saving,
                isLoading = draft.saving,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(AdminStringKeys.TerminalCreateDescription))
            PosTextField(
                value = draft.name,
                onValueChange = { v -> onField { it.copy(name = v) } },
                label = str(AdminStringKeys.TerminalNameLabel),
            )
            Box(Modifier.fillMaxWidth()) {
                PosPickerField(
                    value = str(roleKey(draft.role)),
                    onClick = { rolePickerOpen = true },
                    label = str(AdminStringKeys.TerminalRoleLabel),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(expanded = rolePickerOpen, onDismissRequest = { rolePickerOpen = false }) {
                    TerminalRole.entries.forEach { role ->
                        PosDropdownItem(
                            text = str(roleKey(role)),
                            selected = role == draft.role,
                            onClick = {
                                onField { it.copy(role = role) }
                                rolePickerOpen = false
                            },
                            minHeight = 48.dp,
                        )
                    }
                }
            }
            Text(
                str(
                    AdminStringKeys.TerminalFiscalPreview,
                    str(if (draft.fiscalPreview) AdminStringKeys.CommonYes else AdminStringKeys.CommonNo),
                ),
            )
            draft.error?.let { err ->
                Text(
                    text = str(err.messageKey, *err.messageArgs.toTypedArray()),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
