package org.example.project.admin.features.terminal.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str

/** No terminal exists yet: says so and, with TERMINAL_MANAGE, offers "Terminal erstellen" right there. */
@Composable
internal fun TerminalsEmptyState(
    canManage: Boolean,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdminListEmptyState(
        message = str(AdminStringKeys.TerminalsEmpty),
        modifier = modifier,
        icon = Icons.Filled.PointOfSale,
        actionText = if (canManage) str(AdminStringKeys.TerminalCreate) else null,
        onAction = if (canManage) onCreate else null,
    )
}
