package org.example.project.admin.features.extras.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.extras.presentation.ExtrasState
import org.example.project.admin.features.extras.presentation.ExtrasViewModel
import org.example.project.admin.features.product.presentation.components.ProductStatusLabel
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ExtrasTabKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMoneyText
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ExtrasManagerDialog(
    onDismiss: () -> Unit,
    viewModel: ExtrasViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    var pendingDelete by remember { mutableStateOf<ProductExtrasDto.ProductExtrasResponseDto?>(null) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.ExtrasManagerTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(ExtrasTabKeys.createExtra), viewModel::openCreate),
        secondary = PosDialogAction(str(CommonKeys.close), onDismiss),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            viewModel.deleteError?.let { err ->
                Text(
                    text = err.displayMessage(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            when (val s = viewModel.state) {
                is ExtrasState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                is ExtrasState.Error ->
                    Text(
                        text = str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                is ExtrasState.Data -> {
                    if (s.extras.isEmpty()) {
                        Text(str(ExtrasTabKeys.noExtrasYet), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        s.extras.forEach { extra ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(extra.name, style = MaterialTheme.typography.bodyMedium)
                                    // PosMoneyText takes no colour parameter; supply it through
                                    // LocalContentColor, which Material3's Text falls back to.
                                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                        PosMoneyText(extra.price, extra.currency, style = MaterialTheme.typography.bodySmall, forceSign = true)
                                    }
                                }
                                if (!extra.isActive) ProductStatusLabel(active = false, modifier = Modifier.padding(horizontal = 8.dp))
                                PosIconButton(
                                    Icons.Default.Edit,
                                    str(CommonKeys.edit),
                                    { viewModel.openEdit(extra) },
                                    size = adminTouchSize(32.dp),
                                    iconSize = 18.dp,
                                )
                                PosDangerIconButton(
                                    Icons.Default.Delete,
                                    str(CommonKeys.delete),
                                    { pendingDelete = extra },
                                    size = adminTouchSize(32.dp),
                                    iconSize = 18.dp,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    viewModel.editor?.let { e ->
        ExtraEditorDialog(
            editor = e,
            onField = viewModel::onField,
            onSave = viewModel::save,
            onCancel = viewModel::closeEditor,
            dirty = viewModel.isEditorDirty,
            deactivationBlockedBy = e.id?.let { viewModel.requiredGroupsNeeding(it) }.orEmpty(),
        )
    }

    pendingDelete?.let { extra ->
        // Client half of the NOTE-c21 guard: the delete stays disabled while a required choice
        // group would be left with fewer options than its minimum, and the dialog names the group.
        val neededBy = viewModel.requiredGroupsNeeding(extra.id)
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityExtra),
            entityName = extra.name,
            blockedReason =
                neededBy
                    .takeIf { it.isNotEmpty() }
                    ?.let { str(AdminStringKeys.ExtraNeededByRequiredGroups, it.joinToString(", ")) },
            onConfirm = {
                viewModel.delete(extra.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
