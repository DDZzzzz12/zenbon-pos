/**
 * Voucher Source List Item component.
 *
 * Displays a single voucher source configuration with its details and action buttons.
 * Shows the source type, name, description, and enabled/disabled status. Provides
 * edit and delete functionality for managing voucher sources.
 *
 * The component follows Material 3 design with a card layout displaying:
 * - Source type as a colored badge
 * - Source name and description
 * - Enabled/disabled status indicator
 * - Edit and Delete action buttons
 *
 * @param item The voucher source to display
 * @param language Current app language for localization
 * @param onEditClick Callback when edit button is clicked
 * @param onDeleteClick Callback when delete button is clicked
 * @param modifier Optional modifier for the component
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.voucher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EnumKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.*

private const val SEEDED_DEFAULT_NAME = "Internal System Vouchers"
private const val SEEDED_DEFAULT_DESCRIPTION = "Vouchers created and sold through the POS system"

/**
 * List item component for displaying voucher source information.
 *
 * Displays voucher source with type badge, name, description, and status.
 * Includes action buttons for editing and deleting the source.
 *
 * @param item Voucher source to display
 * @param language Current language for localization
 * @param onEditClick Callback invoked when edit button is clicked
 * @param onDeleteClick Callback invoked when delete button is clicked
 * @param modifier Optional modifier for customization
 * @param editable false hides the edit/delete actions for a principal who may not write the config
 * @param deletable false disables the delete action and states why under the description: the INTERNAL
 *   source while vouchers are enabled (the server refuses that config too, `VoucherConfig.isValid`).
 *   A trash icon that silently did nothing was the alternative (browser pass note).
 */
@Composable
fun VoucherSourceListItem(
    item: VoucherSource,
    language: Language,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    deletable: Boolean = true,
) {
    // Display only: the stored name/description stay as saved (the seeded default is English).
    val isSeededDefault = item.type == VoucherSourceType.INTERNAL && item.name == SEEDED_DEFAULT_NAME
    val displayName = if (isSeededDefault) getString(EnumKeys.voucherSourceDefaultName, language) else item.name
    val displayDescription =
        if (isSeededDefault && item.description == SEEDED_DEFAULT_DESCRIPTION) {
            getString(EnumKeys.voucherSourceDefaultDescription, language)
        } else {
            item.description
        }
    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Plain,
    ) {
        body {
            // Source Type Badge and Name
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Type Badge
                PosStatusChip(
                    text = item.type.getName(language),
                    backgroundColor =
                        when (item.type.name) {
                            "INTERNAL" -> MaterialTheme.colorScheme.primaryContainer
                            "LEGACY" -> MaterialTheme.colorScheme.secondaryContainer
                            "EXTERNAL" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    textColor =
                        when (item.type.name) {
                            "INTERNAL" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "LEGACY" -> MaterialTheme.colorScheme.onSecondaryContainer
                            "EXTERNAL" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )

                // Source Name
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Description (if available)
            displayDescription?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (editable && !deletable) {
                Text(
                    text = str(SettingsKeys.voucherSourceInternalRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Enabled/Disabled Status
            Text(
                text =
                    if (item.enabled) {
                        str(SettingsKeys.active)
                    } else {
                        str(SettingsKeys.inactive)
                    },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (item.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }

        if (editable) {
            trailing {
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
                        enabled = deletable,
                    )
                }
            }
        }
    }
}
