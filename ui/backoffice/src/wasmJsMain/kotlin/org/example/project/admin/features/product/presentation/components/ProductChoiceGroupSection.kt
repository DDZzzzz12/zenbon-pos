package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.features.product.presentation.ProductEditor
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.form.PosFormSectionLabel
import org.example.project.ui.theme.PosSpacing

/**
 * Product-editor section for attaching choice groups to an existing product, with detach and
 * up/down reordering. Attachment requires a saved product (id != null) — new/unsaved products
 * show a "save first" hint instead.
 */
@Composable
fun ProductChoiceGroupSection(
    editor: ProductEditor,
    groups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    onAttach: (Int) -> Unit,
    onDetach: (Int) -> Unit,
    onMove: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PosFormSectionLabel(str(ChoiceGroupKeys.attachedGroups))

        if (editor.isCreate) {
            Text(
                text = str(ChoiceGroupKeys.saveProductFirst),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val attachedIds = editor.attachedGroupIds

            if (attachedIds.isEmpty()) {
                Text(
                    text = str(ChoiceGroupKeys.noAttachedGroups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
                    attachedIds.forEachIndexed { index, id ->
                        val name = groups.firstOrNull { it.id == id }?.name ?: ""
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            PosIconButton(
                                icon = Icons.Default.ArrowUpward,
                                contentDescription = str(ChoiceGroupKeys.moveUp),
                                onClick = { onMove(id, true) },
                                enabled = index > 0,
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                            PosIconButton(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = str(ChoiceGroupKeys.moveDown),
                                onClick = { onMove(id, false) },
                                enabled = index < attachedIds.lastIndex,
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                            PosDangerIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = str(CommonKeys.delete),
                                onClick = { onDetach(id) },
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                        }
                    }
                }
            }

            var showPicker by remember { mutableStateOf(false) }
            val attachableGroups = groups.filter { it.id !in attachedIds }

            Column {
                PosSecondaryButton(
                    onClick = { showPicker = true },
                    icon = Icons.Default.Add,
                    text = str(ChoiceGroupKeys.attachGroups),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(expanded = showPicker, onDismissRequest = { showPicker = false }) {
                    if (attachableGroups.isEmpty()) {
                        PosDropdownItem(text = str(ChoiceGroupKeys.noGroups), onClick = { showPicker = false })
                    } else {
                        attachableGroups.forEach { group ->
                            PosDropdownItem(
                                text = group.name,
                                onClick = {
                                    onAttach(group.id)
                                    showPicker = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
