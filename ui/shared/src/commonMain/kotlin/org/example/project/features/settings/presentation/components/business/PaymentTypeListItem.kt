/**
 * Payment type list item component.
 *
 * @since 1.0.0
 */
package org.example.project.features.settings.presentation.components.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.touchTargetSize
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton

/**
 * List item displaying a payment type with edit/delete actions.
 *
 * @param item Payment type data
 * @param language Current language for localization
 * @param onEditClick Callback when edit is clicked
 * @param onDeleteClick Callback when delete is clicked
 */
@Composable
fun PaymentTypeListItem(
    item: PaymentTypeDto.PaymentTypeResponseDto,
    language: Language,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    PosCard(
        variant = PosCardVariant.Plain,
        status = if (item.isActive) PosCardStatus.Active else PosCardStatus.Neutral,
        inactive = !item.isActive,
    ) {
        body {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (item.isCash) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = str(SettingsKeys.isCashPayment),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = if (item.isActive) str(SettingsKeys.active) else str(SettingsKeys.inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
                )
            }
        }
        trailing {
            Row {
                PosIconButton(
                    icon = Icons.Default.Edit,
                    contentDescription = getString(CommonKeys.edit, language),
                    onClick = onEditClick,
                    size = touchTargetSize(40.dp),
                )
                PosIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = getString(CommonKeys.delete, language),
                    onClick = onDeleteClick,
                    size = touchTargetSize(40.dp),
                )
            }
        }
    }
}
