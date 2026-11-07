/**
 * Dialog for confirming order item cancellation with optional reason.
 *
 * Provides a confirmation dialog for canceling selected items with
 * a text field for entering the cancellation reason.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.table

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Confirmation dialog for canceling order items.
 *
 * Displays the number of items to be canceled and provides a text field
 * for entering the cancellation reason.
 *
 * @param itemCount Number of items being canceled
 * @param currentLanguage Current app language for localization
 * @param onConfirm Callback when cancel is confirmed with reason
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun CancelItemsDialog(
    itemCount: Int,
    currentLanguage: Language,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TableDetailKeys.cancelItems),
        titleIcon = Icons.Default.Cancel,
        footer = {
            PosTextButton(
                text = getString(CommonKeys.cancel, currentLanguage),
                onClick = onDismiss,
            )
            PosDangerButton(
                text = getString(CommonKeys.confirm, currentLanguage),
                onClick = { onConfirm(reason.trim()) },
            )
        },
    ) {
        // No verticalScroll here: PosDialog's body already scrolls, and a nested scroll container
        // is measured with infinite height and throws.
        Column(
            modifier = Modifier.keyboardAwarePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = str(TableDetailKeys.selectedItems, itemCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = str(TableDetailKeys.authorizationRequired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PosTextField(
                value = reason,
                onValueChange = { reason = it },
                label = str(TableDetailKeys.cancelReason),
                placeholder = null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                imeAction = ImeAction.Done,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
            )

            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = str(TableDetailKeys.cancelWarning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
