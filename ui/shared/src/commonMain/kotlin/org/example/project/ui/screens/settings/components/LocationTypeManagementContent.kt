/**
 * Location type management content component.
 *
 * @since 1.0.0
 */
package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton

/**
 * Content section for managing location types.
 *
 * @param locationTypes List of location types
 * @param isLoading Whether data is being loaded
 * @param language Current language for localization
 * @param onAddClick Callback when add button is clicked
 * @param onEditClick Callback when edit is clicked for a location type
 * @param onDeleteClick Callback when delete is clicked for a location type
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun LocationTypeManagementContent(
    locationTypes: List<LocationTypeDto.ResponseDto>,
    isLoading: Boolean,
    language: Language,
    onAddClick: () -> Unit,
    onEditClick: (LocationTypeDto.ResponseDto) -> Unit,
    onDeleteClick: (LocationTypeDto.ResponseDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Action Button
        PosButton(
            text = str(SettingsKeys.addNew),
            icon = Icons.Default.Add,
            onClick = onAddClick,
            modifier = Modifier.fillMaxWidth(),
        )

        // List
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (locationTypes.isEmpty()) {
            Text(
                text = str(SettingsKeys.noLocationTypes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            locationTypes.forEach { item ->
                LocationTypeListItem(
                    item = item,
                    language = language,
                    onEditClick = { onEditClick(item) },
                    onDeleteClick = { onDeleteClick(item) },
                )
            }
        }
    }
}

/**
 * List item displaying a location type with edit/delete actions.
 */
@Composable
private fun LocationTypeListItem(
    item: LocationTypeDto.ResponseDto,
    language: Language,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (item.isVirtual) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = str(SettingsKeys.isVirtualLabel),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    colors =
                                        AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        ),
                                )
                            }
                            if (item.isTakeaway) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = str(SettingsKeys.isTakeawayLabel),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    colors =
                                        AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        ),
                                )
                            }
                        }
                        if (item.isSystem) {
                            Text(
                                text = str(BackOfficeErrorKeys.systemRecordHint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // A system row has no actions; the server refuses update/delete (SYSTEM_RECORD_PROTECTED).
                // Its hint sits under the chips, where it can no longer run into them on a phone.
                if (!item.isSystem) {
                    Row {
                        PosIconButton(
                            icon = Icons.Default.Edit,
                            contentDescription = getString(CommonKeys.edit, language),
                            onClick = onEditClick,
                        )
                        PosIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = getString(CommonKeys.delete, language),
                            onClick = onDeleteClick,
                        )
                    }
                }
            }
        }
    }
}
