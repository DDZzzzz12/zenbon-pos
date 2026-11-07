package org.example.project.admin.features.choicegroup.presentation.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupsState
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupsViewModel
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosIconButton
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChoiceGroupsManagerDialog(
    onDismiss: () -> Unit,
    viewModel: ChoiceGroupsViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    var pendingDelete by remember { mutableStateOf<ChoiceGroupDto.ChoiceGroupResponseDto?>(null) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(ChoiceGroupKeys.manageGroupsTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(ChoiceGroupKeys.addGroup), viewModel::openCreate),
        secondary = PosDialogAction(str(CommonKeys.close), onDismiss),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            when (val s = viewModel.state) {
                is ChoiceGroupsState.Loading ->
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        Alignment.Center,
                    ) { CircularProgressIndicator() }
                is ChoiceGroupsState.Error ->
                    Text(
                        text = str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                is ChoiceGroupsState.Data -> {
                    if (s.groups.isEmpty()) {
                        Text(str(ChoiceGroupKeys.noGroups), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        s.groups.forEach { group ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(group.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        str(ChoiceGroupKeys.optionCount, group.options.size.toString()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                PosIconButton(
                                    Icons.Default.Edit,
                                    str(CommonKeys.edit),
                                    { viewModel.openEdit(group) },
                                    size = adminTouchSize(32.dp),
                                    iconSize = 18.dp,
                                )
                                PosDangerIconButton(
                                    Icons.Default.Delete,
                                    str(CommonKeys.delete),
                                    { pendingDelete = group },
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
        ChoiceGroupEditorDialog(
            editor = e,
            availableExtras = viewModel.availableExtras(),
            onField = viewModel::onField,
            onSetPrice = viewModel::setOptionPriceDelta,
            onSetDefault = viewModel::setOptionDefault,
            onMove = viewModel::moveOption,
            onRemove = viewModel::removeOption,
            onAddOption = viewModel::addOption,
            onSave = viewModel::saveGroup,
            onCancel = viewModel::closeEditor,
            dirty = viewModel.isEditorDirty,
        )
    }

    pendingDelete?.let { group ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityChoiceGroup),
            entityName = group.name,
            onConfirm = {
                viewModel.delete(group.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
