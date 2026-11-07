/**
 * Payment type management content component.
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.business.PaymentTypeListItem
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton

/**
 * Content section for managing payment types.
 *
 * @param paymentTypes List of payment types
 * @param isLoading Whether data is being loaded
 * @param language Current language for localization
 * @param onAddClick Callback when add button is clicked
 * @param onEditClick Callback when edit is clicked for a payment type
 * @param onDeleteClick Callback when delete is clicked for a payment type
 * @param onSyncClick Callback when sync button is clicked
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun PaymentTypeManagementContent(
    paymentTypes: List<PaymentTypeDto.PaymentTypeResponseDto>,
    isLoading: Boolean,
    language: Language,
    onAddClick: () -> Unit,
    onEditClick: (PaymentTypeDto.PaymentTypeResponseDto) -> Unit,
    onDeleteClick: (Long) -> Unit,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PosButton(
                text = str(SettingsKeys.addNew),
                icon = Icons.Default.Add,
                onClick = onAddClick,
                modifier = Modifier.weight(1f),
            )

            PosSecondaryButton(
                text = str(SettingsKeys.sync),
                icon = Icons.Default.Sync,
                onClick = onSyncClick,
                modifier = Modifier.weight(1f),
            )
        }

        // List
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (paymentTypes.isEmpty()) {
            Text(
                text = str(SettingsKeys.noPaymentTypes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            paymentTypes.forEach { item ->
                PaymentTypeListItem(
                    item = item,
                    language = language,
                    onEditClick = { onEditClick(item) },
                    onDeleteClick = { onDeleteClick(item.id) },
                )
            }
        }
    }
}
