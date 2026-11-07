/**
 * Voucher configuration management content component.
 *
 * @since 1.0.0
 */
package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.voucher.VoucherSourceListItem
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * Content section for managing voucher configuration.
 *
 * @param voucherConfig Current voucher configuration
 * @param isLoading Whether data is being loaded
 * @param language Current language for localization
 * @param onAddClick Callback when add button is clicked
 * @param onEditClick Callback when edit is clicked for a source
 * @param onDeleteClick Callback when delete is clicked for a source
 * @param onToggleEnabled Callback when voucher system is toggled
 * @param modifier Modifier for customizing the layout
 * @param editable false renders the configuration read-only: the master switch disabled and the
 *   add/edit/delete actions hidden. The server refuses the write without `CONFIG_WRITE` regardless
 *   (QA admin19 NOTE-j: a cashier was offered the toggle and the source actions).
 * @param isDeletable false for a source that may not be removed right now; its delete action is shown
 *   disabled with the reason instead of silently doing nothing.
 */
@Composable
fun VoucherConfigManagementContent(
    voucherConfig: VoucherConfig?,
    isLoading: Boolean,
    language: Language,
    onAddClick: () -> Unit,
    onEditClick: (VoucherSource) -> Unit,
    onDeleteClick: (VoucherSourceType) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    isDeletable: (VoucherSourceType) -> Boolean = { true },
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Master Toggle for Voucher System
        if (voucherConfig != null) {
            PosCard(
                modifier = Modifier.fillMaxWidth(),
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                body {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(SettingsKeys.voucherSystemEnabled),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = str(SettingsKeys.voucherSystemEnabledDescription),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = voucherConfig.enabled,
                            onCheckedChange = onToggleEnabled,
                            enabled = editable,
                        )
                    }
                }
            }
        }

        // Action Button
        if (editable) {
            PosButton(
                text = str(SettingsKeys.addNew),
                icon = Icons.Default.Add,
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && (voucherConfig?.sources?.size ?: 0) < 5, // Max 5 source types
            )
        }

        if (isLoading) {
            // Loading State
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (voucherConfig == null || voucherConfig.sources.isEmpty()) {
            // Empty State
            Text(
                text = str(SettingsKeys.noVoucherSources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // List of Voucher Sources
            voucherConfig.sources.forEach { source ->
                VoucherSourceListItem(
                    item = source,
                    language = language,
                    onEditClick = { onEditClick(source) },
                    onDeleteClick = { onDeleteClick(source.type) },
                    editable = editable,
                    deletable = isDeletable(source.type),
                )
            }
        }
    }
}
