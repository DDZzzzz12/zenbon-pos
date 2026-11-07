package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.reason.ActionReasons
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.ReasonChips
import org.example.project.ui.components.ReasonSelectionState

@Composable
fun PosCancelItemsDialog(
    itemCount: Int,
    hint: CancelAuthorizationHint,
    authorizingManager: String?,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val reasonState = remember { ReasonSelectionState(ActionReasons.cancelItems) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TableDetailKeys.cancelItems),
        titleIcon = Icons.Default.Cancel,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosButton(
                text = str(CommonKeys.confirm),
                onClick = { reasonState.submitOrNull()?.let(onConfirm) },
            )
        },
    ) {
        Text(
            text = str(TableDetailKeys.selectedItems, itemCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        val hintText =
            when (hint) {
                CancelAuthorizationHint.REQUIRED -> str(TableDetailKeys.authorizationRequired)
                CancelAuthorizationHint.AUTHORIZED_BY -> str(TableDetailKeys.authorizedBy, authorizingManager.orEmpty())
                CancelAuthorizationHint.NONE -> null
            }
        if (hintText != null) {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ReasonChips(state = reasonState)

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = str(TableDetailKeys.cancelWarning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
            )
        }
    }
}
